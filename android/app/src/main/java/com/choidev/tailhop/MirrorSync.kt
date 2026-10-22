package com.choidev.tailhop

import android.content.Context
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * 기기 쪽 맞추기(§19.3). 한 번에 한 허브씩, 한 파일씩 받는다. 포그라운드 서비스·wakelock 없이 돈다:
 * - 앱·클라우드 화면을 열 때(허브마다 10분에 한 번까지), 허브가 hello 요청 헤더에 "sync"를 넣어 보낼 때(바로),
 *   "지금 동기화"(바로, 망 종류와 상관없이) — 이 프로세스의 작업 스레드 하나(쓰지 않으면 끝난다).
 * - WorkManager 주기 작업(1시간, 배터리 부족 아님 + 기본은 비데이터 망, MirrorWork).
 * 자동으로 시작한 맞추기는 "모바일 데이터로도 동기화"가 꺼져 있으면 데이터 망에서 하지 않는다.
 */
object MirrorSync {
    enum class Why { AUTO, HUB, MANUAL }

    private const val GAP_MS = 10 * 60_000L
    /** 한 번에 받는 범위(§17.3 length). */
    private const val PIECE = 4L * 1024 * 1024
    /** 그 파일만 건너뛰고 다음 맞추기로 미루는 거부(허브에서 그 사이 바뀌거나 지워짐). */
    private val SKIP = setOf("cloud_changed", "not_found", "file_incomplete", "file_too_big")
    private val OFFLINE = setOf("connect_failed", "not_ts_host", "pc_forgot", "disconnected", "unreadable_reply", "bad_reply")

    private val gate = PingGate(GAP_MS)
    private val runLock = ReentrantLock()
    private val pending = LinkedHashMap<String, Why>()
    private val exec = ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, LinkedBlockingQueue())
    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var token: SendToken? = null
    @Volatile private var cancelGen = 0

    /** ip가 null이면 페어링된 모든 PC. */
    fun request(ctx: Context, ip: String? = null, why: Why = Why.AUTO) {
        val app = ctx.applicationContext
        LocalSpace.init(app)
        if (!MirrorSettings.isOn(app)) return
        val ips = if (ip != null) listOf(ip) else Store.all(app).map { it.pcIp }
        val now = System.currentTimeMillis()
        var any = false
        synchronized(pending) {
            for (i in ips) {
                if (why == Why.AUTO && !gate.take(i, now)) continue
                val was = pending[i]
                if (was == null || why.ordinal > was.ordinal) pending[i] = why
                any = true
            }
        }
        if (any) exec.execute { drain(app) }
    }

    /** 허브가 대상을 바꾼 뒤(허브는 30초 모아 hello를 보낸다) 이 폰도 한 번 더 본다 — 받기를 멈춰 hello를 못 받는 때를 위해. */
    fun requestLater(ctx: Context, ip: String, ms: Long) {
        val app = ctx.applicationContext
        ui.postDelayed({ request(app, ip, Why.HUB) }, ms)
    }

    /** 지금 하는 맞추기를 멈춘다(설정을 끔·사본 지우기). 받던 파일은 다 쓴 청크까지 남는다. */
    fun cancelRunning() {
        cancelGen++
        token?.cancel()
        synchronized(pending) { pending.clear() }
    }

    /** 맞추기를 멈추고(돌고 있으면 끝날 때까지 기다려) block을 한다 — 사본을 지우거나 이전 사본으로 옮길 때. 작업 스레드에서. */
    fun <T> whileStopped(block: () -> T): T {
        cancelRunning()
        runLock.lock()
        try { return block() } finally { runLock.unlock() }
    }

    private fun drain(app: Context) {
        while (true) {
            val (ip, why) = synchronized(pending) {
                val e = pending.entries.firstOrNull() ?: return
                pending.remove(e.key)
                e.key to e.value
            }
            val gen = cancelGen
            syncOne(app, ip, allowMetered = why == Why.MANUAL) { gen != cancelGen }
        }
    }

    /** WorkManager 작업: 페어링된 모든 PC를 차례로(망 조건은 작업이 이미 지켰다). 앱 쪽 맞추기가 돌고 있으면 기다린다. */
    fun runAll(app: Context, stopped: () -> Boolean) {
        val gen = cancelGen
        for (p in Store.all(app)) {
            if (stopped() || gen != cancelGen) return
            syncOne(app, p.pcIp, allowMetered = true) { stopped() || gen != cancelGen }
        }
    }

    private fun metered(ctx: Context): Boolean =
        ctx.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: true

    private fun syncOne(app: Context, ip: String, allowMetered: Boolean, cancelled: () -> Boolean) {
        val p = Store.get(app, ip) ?: return
        if (!MirrorSettings.isOn(app)) return
        if (!allowMetered && !MirrorSettings.mobile(app) && metered(app)) { MirrorStatus.set(app, ip, MirrorStatus.METERED); return }
        runLock.lock()
        try {
            val stop = { cancelled() || !MirrorSettings.isOn(app) }
            MirrorStatus.progress(ip, 0)
            val code = try { work(app, p, stop) } catch (e: ProtocolException) {
                if (e.code in OFFLINE) MirrorStatus.OFFLINE else e.code
            } catch (e: java.io.IOException) { MirrorStatus.OFFLINE } catch (e: Exception) { "storage" }
            if (MirrorSettings.isOn(app) && Store.get(app, ip) != null) MirrorStatus.set(app, ip, code)
        } finally {
            token = null
            MirrorStatus.progress(null, 0)
            runLock.unlock()
        }
    }

    /**
     * 한 허브 맞추기. 상태 코드(MirrorStatus)를 돌려준다. 사본이 잠긴 채로(지문 없이) 돈다 — 장부와 공개키만 쓴다(§19.3 2.3.1).
     * 허브의 저장소 번호(§19.5)가 사본과 다르면 사본을 지우지도 덮지도 않고 이전 사본으로 옮긴 뒤 새 사본을 시작한다.
     */
    private fun work(app: Context, p: Pairing, stop: () -> Boolean): String {
        val tok = SendToken().also { token = it }
        val h = try { Net.hello(p, null, null, tok) } catch (e: Exception) { return MirrorStatus.OFFLINE }
        if (h == null || "sync" !in h.caps) return MirrorStatus.UNSUPPORTED
        // 기기 열쇠: 새 지문 등록 등으로 무효면 모든 사본을 지우고 새 열쇠로(화면이 이유를 알린다), 화면 잠금이 없으면 mirror_setup
        Mirror.prepareKeys(app)
        val cur = Mirror.peek(app, p.pcIp)
        val decision = MirrorIdentity.decide(cur?.storeId(), h.storeId, cur?.hasContent() == true)
        when (decision) {
            MirrorIdentity.Decision.ARCHIVE -> if (Mirror.archive(app, p.pcIp, p.pcName)) MirrorNotice.add(app, MirrorNotice.ARCHIVED)
            MirrorIdentity.Decision.FRESH -> Mirror.delete(app, p.pcIp)
            // 사본에는 번호가 있는데 허브가 알리지 않는다(저장소 없음·예전 허브): 아무것도 바꾸지 않는다
            MirrorIdentity.Decision.HOLD -> return if (h.store != HubStore.OPEN) MirrorStatus.STORE else MirrorStatus.UNSUPPORTED
            MirrorIdentity.Decision.SAME, MirrorIdentity.Decision.ADOPT -> { }
        }
        // 저장소가 열리지 않았으면(잠김·없음) 사본은 그대로 둔다(§19.2)
        if (h.store != HubStore.OPEN) return MirrorStatus.STORE
        val digest = h.sync
        if (digest == null) {
            // 같은 저장소가 열렸는데 sync가 없다 = 대상이 없다: 그 허브의 사본을 지운다(번호가 다르면 위에서 이미 이전 사본으로 옮겼다)
            if (MirrorIdentity.mayWipe(decision) && Mirror.exists(app, p.pcIp)) Mirror.delete(app, p.pcIp)
            return MirrorStatus.OK
        }
        val m = Mirror.get(app, p.pcIp)
        m.setIdentity(h.storeId, p.pcName)
        try {
            if (m.digest() == digest && m.part() == null) { m.save(); return MirrorStatus.OK }
            val full = SyncManifest.collect({ c -> Net.syncManifest(p, c, tok) }, stop)
            val plan = SyncPlan.make(m.items(), full.items, m::ih)
            // 목록에 없는 것은 바로 지우고(휴지통 없음), 같은 id·ver는 경로·정보만 고친다.
            plan.drop.forEach { m.remove(it) }
            for (w in plan.moves) if (w.dir) m.putDir(w) else m.move(w)
            // 잠긴 폴더 안(§19.6): 줄(meta 암호문)과 객체 암호문을 받은 그대로 둔다(풀지 않는다)
            m.syncRows(full.rows)
            m.save()
            val raws = full.rows.flatMap { it.objs }.distinctBy { it.o }.filter { m.rawSize(it.o) != it.s }
            var left = plan.thumbs.size + plan.fetch.size + raws.size
            MirrorStatus.progress(p.pcIp, left)
            var complete = true
            for (o in raws) {
                if (stop()) { m.save(); throw ProtocolException("disconnected") }
                val have = maxOf(0L, m.rawSize(o.o)).let { if (it > o.s) { m.rawFile(o.o).delete(); 0L } else it }
                val free = LocalSpace.of(app.filesDir)?.free ?: Long.MAX_VALUE
                if (!SyncPlan.enoughSpace(free, o.s - have)) { m.save(); return MirrorStatus.SPACE }
                try { fetchRaw(m, p, o, have, tok, stop) } catch (e: ProtocolException) {
                    if (e.code !in SKIP) { m.save(); throw e }
                    complete = false
                }
                m.rawDone()
                MirrorStatus.progress(p.pcIp, --left)
            }
            for (w in plan.thumbs) {
                if (stop()) throw ProtocolException("disconnected")
                val t = thumb(m, p, w, tok)
                try { m.setThumb(w, t) } finally { t?.key?.fill(0) }
                MirrorStatus.progress(p.pcIp, --left)
            }
            for (w in plan.fetch) {
                if (stop()) { m.save(); throw ProtocolException("disconnected") }
                // 남은 공간이 (받을 파일 + 1 GiB)보다 적으면 멈추고 알린다(§19.3)
                val free = LocalSpace.of(app.filesDir)?.free ?: Long.MAX_VALUE
                if (!SyncPlan.enoughSpace(free, w.size)) { m.save(); return MirrorStatus.SPACE }
                try { download(m, p, w, tok, stop) } catch (e: ProtocolException) {
                    if (e.code !in SKIP) { m.save(); throw e }
                    m.clearPart(objDrop = true)
                    complete = false // 그 사이 허브에서 바뀌었다: 요약값을 기억하지 않아 다음에 다시 본다
                }
                MirrorStatus.progress(p.pcIp, --left)
                m.save(force = false)
            }
            if (complete) m.setDigest(full.digest)
            m.save()
            return MirrorStatus.OK
        } finally {
            m.closeBox() // 이번 맞추기의 데이터 열쇠를 메모리에서 지운다
        }
    }

    /**
     * 파일 하나: cloud_get 범위(4 MiB)로 받아 1 MiB 청크로 잠가 쓴다. 받다 만 것(같은 id·ver·size·mtime)은 다 쓴 청크부터 잇는다.
     * 파일 열쇠는 받는 동안만 장부 쪽(part)에 두고, 다 받으면 공개키로 잠근 상자에만 남긴다.
     */
    private fun download(m: Mirror, p: Pairing, w: SyncManifest.Item, tok: SendToken, stop: () -> Boolean) {
        val part = m.part()
        val reuse = part != null && part.matches(w)
        if (part != null && !reuse) m.clearPart(objDrop = true)
        val obj: String
        val key: ByteArray
        if (part != null && reuse) {
            obj = part.obj
            key = Protocol.b64uDecode(part.fkey) ?: throw ProtocolException("mirror_damaged")
        } else {
            obj = Protocol.newId()
            key = Protocol.randomBytes(32)
            m.startPart(MirrorPart(w.id, w.ver, w.size, w.mtime, obj, Protocol.b64uEncode(key)))
        }
        try {
            fetchInto(m, p, w, obj, key, tok, stop)
            val t = if (w.tver != null) thumb(m, p, w, tok) else null
            try { m.complete(w, obj, key, t) } finally { t?.key?.fill(0) }
        } finally { key.fill(0) }
    }

    /** 잠긴 폴더 안 객체 하나의 암호문을 4 MiB씩 받아 끝에 덧붙인다(끊기면 받은 길이부터 잇는다). 크기가 바뀌었으면 cloud_changed. */
    private fun fetchRaw(m: Mirror, p: Pairing, o: SyncManifest.RawObj, from: Long, tok: SendToken, stop: () -> Boolean) {
        var have = from
        m.openRaw(o.o).use { f ->
            // sync_raw가 조각마다 닫는다 — 닫아도 파일은 닫히지 않게 감싼다
            val sink = object : OutputStream() {
                override fun write(b: Int) { f.write(b) }
                override fun write(b: ByteArray, off: Int, len: Int) { f.write(b, off, len) }
            }
            while (have < o.s) {
                if (stop()) throw ProtocolException("disconnected")
                val len = minOf(PIECE, o.s - have)
                val size = Net.syncRaw(p, o.o, have, len, tok, sink)
                if (size != o.s) throw ProtocolException("cloud_changed")
                f.flush()
                f.fd.sync()
                have += len
            }
        }
    }

    private fun fetchInto(m: Mirror, p: Pairing, w: SyncManifest.Item, obj: String, key: ByteArray, tok: SendToken, stop: () -> Boolean) {
        MirrorObject.Writer(m.objFile(obj), key, w.size).use { wr ->
            if (!wr.complete) {
                // cloud_get이 조각마다 닫는다 — 닫아도 쓰기는 끝나지 않게 감싼다
                val sink = object : OutputStream() {
                    override fun write(b: Int) { wr.write(byteArrayOf(b.toByte())) }
                    override fun write(b: ByteArray, off: Int, len: Int) { wr.write(b, off, len) }
                }
                var off = wr.done
                while (off < w.size) {
                    if (stop()) throw ProtocolException("disconnected")
                    val len = minOf(PIECE, w.size - off)
                    Net.cloudGet(p, w.path, off, w.size, w.mtime, tok, open = { sink }, length = len)
                    off += len
                }
                wr.finish()
            }
        }
    }

    /** 허브 미리보기(§17.4 thumb)를 메모리로 받아 새 열쇠로 잠가 둔다. 없거나 너무 크면 null. */
    private fun thumb(m: Mirror, p: Pairing, w: SyncManifest.Item, tok: SendToken): Mirror.Thumb? {
        val tver = w.tver ?: return null
        val buf = ByteArrayOutputStream()
        try {
            Net.cloudGet(p, w.path, 0, null, null, tok, open = { head ->
                if (head.size > SyncManifest.THUMB_MAX) throw ProtocolException("too_large")
                buf
            }, variant = CloudVariants.THUMB)
        } catch (e: ProtocolException) {
            if (e.code == "not_found" || e.code == "too_large" || e.code == "cloud_changed") return null
            throw e
        }
        val bytes = buf.toByteArray()
        val tobj = Protocol.newId()
        val key = Protocol.randomBytes(32)
        try {
            MirrorObject.Writer(m.objFile(tobj), key, bytes.size.toLong()).use { it.write(bytes); it.finish() }
        } catch (e: Exception) {
            key.fill(0)
            throw e
        } finally { bytes.fill(0) }
        return Mirror.Thumb(tver, tobj, key)
    }
}

/** 주기 맞추기(WorkManager, 1시간). 설정을 끄거나 페어링이 없으면 취소한다. */
object MirrorWork {
    private const val NAME = "tailhop-mirror-sync"

    fun schedule(ctx: Context) {
        val app = ctx.applicationContext
        val wm = try { WorkManager.getInstance(app) } catch (e: Exception) { return }
        if (!MirrorSettings.isOn(app) || !Store.isPaired(app)) { wm.cancelUniqueWork(NAME); return }
        val c = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .setRequiredNetworkType(if (MirrorSettings.mobile(app)) NetworkType.CONNECTED else NetworkType.UNMETERED)
            .build()
        val req = PeriodicWorkRequest.Builder(MirrorWorker::class.java, 1, TimeUnit.HOURS).setConstraints(c).build()
        try { wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, req) } catch (e: Exception) { }
    }
}

/** MirrorWork의 작업: 모든 허브를 한 번 맞춘다. 시스템이 멈추면(조건이 사라짐·10분) 받던 것을 끊고 다음 주기에 잇는다. */
class MirrorWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        LocalSpace.init(applicationContext)
        if (MirrorSettings.isOn(applicationContext)) MirrorSync.runAll(applicationContext) { isStopped }
        return Result.success()
    }

    override fun onStopped() { MirrorSync.cancelRunning() }
}
