package com.choidev.tailhop

import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap

/**
 * 보내기 서비스(1.7.0, docs/PROTOCOL.md §11.3). Outbox의 건을 PC마다 차례로 보내고(서로 다른 PC는 동시에),
 * 실패하면 3초·10초·30초·1분·2분, 그 뒤 5분마다 다시 보내고 24시간이 지나면 실패로 둔다.
 * 보낼 건이 남아 있는 동안만 포그라운드로 돌고, 다 보내면(남은 건이 실패뿐이면) 멈춘다.
 *
 * 공유받은 파일의 읽기 권한: 공유 화면(ShareActivity)이 받은 임시 권한은 그 화면이 끝나면 사라진다. 그래서 화면이
 * 이 서비스를 시작할 때 URI들을 ClipData에 넣고 FLAG_GRANT_READ_URI_PERMISSION을 붙여 권한을 서비스의 시작 요청에 옮긴다.
 * 시스템은 그 권한을 시작 요청(start id)에 묶어 두고, START_REDELIVER_INTENT라 stopSelf로 그 요청을 끝낼 때까지
 * (프로세스가 죽어 다시 시작돼도) 유지한다. 그래서 큐가 빌 때까지 서비스를 멈추지 않는다.
 */
class SendService : Service() {
    private val ui = Handler(Looper.getMainLooper())
    private val workers = HashMap<String, Thread>()
    /** 지금 보내는 건(job id) → 취소·감시 손잡이. */
    private val tokens = ConcurrentHashMap<String, SendToken>()
    /** PC별 능력(hello의 caps). 서비스가 사는 동안 기억하고 실패하면 잊는다. 빈 집합 = 예전 PC(hello를 모름). */
    private val caps = ConcurrentHashMap<String, Set<String>>()
    private val signal = Object()
    @Volatile private var running = false
    @Volatile private var lastStartId = 0
    private var watchdog: Thread? = null
    @Volatile private var lastNotify = 0L
    /** 알림에 보인 상태(보내는 건, 남은 수). 같으면 진행률만 바뀐 것이라 1초에 한 번만 갱신한다. */
    private var lastKey = ""

    private val outboxListener: () -> Unit = { ui.post { refresh() } }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(Lang.wrap(newBase)) }

    override fun onCreate() {
        super.onCreate()
        Notifs.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        try {
            ServiceCompat.startForeground(this, Notifs.ID_SEND, Notifs.sending(this, summaryText(), null),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        } catch (e: Exception) {
            // 백그라운드 시작 제한·dataSync 하루 시간 초과 등. 보낼 건은 Outbox에 남아 앱을 열면 이어서 보낸다.
            stopSelf(); return START_NOT_STICKY
        }
        if (!running) {
            running = true
            instance = this
            Outbox.addListener(outboxListener)
            watchdog = Thread({ watch() }, "tailhop-send-watch").also { it.isDaemon = true; it.start() }
        }
        Thread({ resolveNames() }, "tailhop-send-meta").start()
        ensureWorkers()
        if (!Outbox.hasActive(this)) maybeStop()
        return START_REDELIVER_INTENT
    }

    /** Android 15+: dataSync는 하루 6시간까지. 시간이 다 되면 바로 멈춰야 한다(보낼 건은 남아 다음에 이어 보낸다). */
    override fun onTimeout(startId: Int, fgsType: Int) {
        tokens.values.forEach { it.close() }
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        if (instance === this) instance = null
        Outbox.removeListener(outboxListener)
        tokens.values.forEach { it.close() }
        synchronized(signal) { signal.notifyAll() }
        watchdog?.interrupt()
        super.onDestroy()
    }

    private fun kick() { synchronized(signal) { signal.notifyAll() } }

    /** 보낼 건이 있는 PC마다 일꾼 하나. */
    private fun ensureWorkers() {
        if (!running) return
        synchronized(workers) {
            for (dev in Outbox.activeDevices(this)) {
                if (workers.containsKey(dev)) continue
                workers[dev] = Thread({ work(dev) }, "tailhop-send").also { it.start() }
            }
        }
        kick()
    }

    /** 한 PC의 일꾼: 그 PC의 맨 앞 건을 다음 시도 시각에 보낸다. 보낼 건이 없으면 끝난다. */
    private fun work(dev: String) {
        while (running) {
            val job = synchronized(workers) {
                Outbox.head(this, dev) ?: run { workers.remove(dev); null }
            } ?: break
            if (job.nextAt > System.currentTimeMillis()) {
                // 잠금 안에서 다시 읽고 잔다: 그 사이 깨우기(wake·취소·새 건)가 와도 놓치지 않게.
                synchronized(signal) {
                    val wait = (Outbox.head(this, dev)?.nextAt ?: 0L) - System.currentTimeMillis()
                    if (wait > 0 && running) try { signal.wait(wait) } catch (e: InterruptedException) { }
                }
                continue
            }
            attempt(job)
        }
        synchronized(workers) { if (workers[dev] === Thread.currentThread()) workers.remove(dev) }
        ui.post { maybeStop() }
    }

    /** 보낼 건이 없으면 멈춘다. 그 사이 새 시작 요청이 왔으면(start id가 바뀜) 멈추지 않는다. */
    private fun maybeStop() {
        if (!running || Outbox.hasActive(this)) { refresh(); return }
        synchronized(workers) { if (workers.isNotEmpty()) return }
        stopSelfResult(lastStartId)
    }

    private fun attempt(job: OutboxJob) {
        val token = SendToken()
        tokens[job.id] = token
        Outbox.update(this, job.id) { it.copy(state = OutboxJob.SENDING) } ?: run { tokens.remove(job.id); return }
        try {
            val p = Store.get(this, job.dev) ?: throw ProtocolException("unpaired")
            if (job.isText) sendText(job, p, token) else sendFile(job, p, token)
        } catch (e: Exception) {
            caps.remove(job.dev) // 실패하면 능력을 잊고 다음에 다시 묻는다
            if (!token.cancelled) fail(job.id, if (e is SecurityException) ProtocolException("file_gone") else e)
        } finally {
            tokens.remove(job.id)
            Outbox.clearProgress(job.id)
        }
    }

    private fun fail(id: String, e: Exception) {
        val code = (e as? ProtocolException)?.code ?: if (e is java.io.IOException) "disconnected" else "unknown"
        val detail = (e as? ProtocolException)?.detail ?: if (e !is ProtocolException && e !is java.io.IOException) e.message else null
        val j = Outbox.update(this, id) { Retry.afterFailure(it, System.currentTimeMillis(), Retry.isRetryable(e), code, detail) } ?: return
        if (j.failed) {
            val pc = Store.get(this, j.dev)?.pcName ?: j.dev
            Notifs.sendFailed(this, j.dev, pc, what(j), reason(this, j))
        }
    }

    /** 상대의 능력. 모르면 hello로 묻는다(파일은 어차피 매번 묻는다). */
    private fun capsFor(p: Pairing, token: SendToken): Set<String> =
        caps[p.pcIp] ?: (Net.hello(p, null, null, token)?.caps ?: emptySet()).also { caps[p.pcIp] = it }

    private fun sendText(job: OutboxJob, p: Pairing, token: SendToken) {
        val text = job.text ?: throw ProtocolException("text_empty")
        val c = capsFor(p, token)
        Net.sendText(p, text, if ("xfer" in c) job.xfer else null, token)
        done(job) {
            HistoryItem(Protocol.newId(), System.currentTimeMillis(), false, true, text, null,
                text.toByteArray(Charsets.UTF_8).size.toLong(), null, p.pcIp)
        }
    }

    private fun sendFile(job: OutboxJob, p: Pairing, token: SendToken) {
        val uri = Uri.parse(job.uri ?: throw ProtocolException("file_gone"))
        // 지금도 읽을 수 있는지 먼저(공유한 앱의 임시 권한은 프로세스가 끝나면 사라질 수 있다).
        Net.openSource(this, uri).close()
        val meta = Net.fileMeta(this, uri)
        if (meta.size > Protocol.MAX_FILE) throw ProtocolException("file_too_large")
        val mtime = Net.lastModified(this, uri)
        // 대기 중에 파일이 바뀌었으면(크기·수정 시각) 새 xfer로 처음부터(§11.3).
        val changed = job.size >= 0 && (job.size != meta.size || (job.mtime != null && mtime != null && job.mtime != mtime))
        val cur = Outbox.update(this, job.id) {
            it.copy(name = meta.name, size = meta.size, mtime = mtime, xfer = if (changed) Xfer.newXfer() else it.xfer)
        } ?: return
        val progress = { sent: Long, total: Long -> Outbox.setProgress(job.id, sent, total) }
        val legacy = caps[p.pcIp]?.isEmpty() == true
        if (legacy) {
            Net.sendFileLegacy(this, uri, meta, p, token, progress)
        } else {
            val hello = Net.hello(p, cur.xfer, meta.size, token)
            caps[p.pcIp] = hello?.caps ?: emptySet()
            when {
                hello == null || !hello.xfer -> Net.sendFileLegacy(this, uri, meta, p, token, progress)
                hello.done -> { } // 응답이 사라졌을 뿐 상대는 이미 다 받았다
                else -> Net.sendFileXfer(this, uri, meta, p, cur.xfer, Xfer.resumeAt(hello, meta.size), token, progress)
            }
        }
        done(job) {
            HistoryItem(Protocol.newId(), System.currentTimeMillis(), false, false, null, meta.name, meta.size, uri.toString(), p.pcIp)
        }
    }

    /** 보냈다: 기록에 넣고(대화방에 보인다) 목록에서 뺀다. 그 사이 취소했어도 상대는 받았으므로 기록한다. */
    private fun done(job: OutboxJob, item: () -> HistoryItem) {
        History.add(this, item())
        Outbox.remove(this, job.id)
    }

    /** 이름·크기를 모르는 파일 건을 채운다(대화방 말풍선에 이름이 보이게). */
    private fun resolveNames() {
        for (j in Outbox.all(this)) {
            if (j.isText || j.name != null || !running) continue
            val uri = Uri.parse(j.uri ?: continue)
            val meta = try { Net.fileMeta(this, uri) } catch (e: Exception) { null }
            val mtime = Net.lastModified(this, uri)
            if (meta != null) Outbox.update(this, j.id) { if (it.name == null) it.copy(name = meta.name, size = meta.size, mtime = mtime) else it }
        }
    }

    /** 상대가 사라져 쓰기가 막힌 연결(TCP는 몇 분이 지나야 포기한다)을 60초 만에 끊어 다시 시도하게 한다. */
    private fun watch() {
        while (running) {
            try { Thread.sleep(10_000) } catch (e: InterruptedException) { return }
            val now = System.currentTimeMillis()
            tokens.values.forEach { if (now - it.lastActivity > STALL_MS) it.close() }
        }
    }

    /** 알림 갱신(진행률은 1초에 한 번까지, 상태가 바뀔 때는 바로). 메인 스레드에서만 부른다. */
    private fun refresh() {
        if (!running) return
        ensureWorkers()
        val now = System.currentTimeMillis()
        val active = Outbox.all(this).filter { !it.failed }
        val sending = active.firstOrNull { it.state == OutboxJob.SENDING }
        val key = "${sending?.id}/${active.size}"
        if (key == lastKey && now - lastNotify < 1000) return
        lastKey = key
        lastNotify = now
        val pct = sending?.let { j -> if (j.size > 0) (Outbox.progressOf(j.id) * 100 / j.size).toInt().coerceIn(0, 100) else 0 }
        try {
            getSystemService(NotificationManager::class.java).notify(Notifs.ID_SEND, Notifs.sending(this, summaryText(), pct))
        } catch (e: Exception) { }
    }

    private fun summaryText(): String {
        val active = Outbox.all(this).filter { !it.failed }
        val sending = active.firstOrNull { it.state == OutboxJob.SENDING }
        val others = active.size - (if (sending != null) 1 else 0)
        if (sending == null) return resources.getQuantityString(R.plurals.send_notif_waiting, active.size, active.size)
        val pct = if (sending.size > 0) (Outbox.progressOf(sending.id) * 100 / sending.size).toInt().coerceIn(0, 100) else 0
        val head = getString(R.string.send_notif_sending, what(sending), pct)
        return if (others > 0) head + " · " + resources.getQuantityString(R.plurals.send_notif_more, others, others) else head
    }

    companion object {
        private const val STALL_MS = 60_000L
        @Volatile private var instance: SendService? = null

        /**
         * 서비스를 시작(또는 깨움)한다. uris는 이번에 넣은 파일들 — 읽기 권한을 서비스로 옮긴다(위 설명).
         * 부른 쪽에 그 권한이 없으면(예: 화면이 끝난 뒤 다시 보내기) 권한 없이 시작한다.
         */
        fun start(ctx: Context, uris: List<Uri> = emptyList()): Boolean {
            val i = Intent(ctx, SendService::class.java)
            if (uris.isNotEmpty()) {
                val clip = ClipData.newRawUri("", uris[0])
                uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
                i.clipData = clip
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            return try { ContextCompat.startForegroundService(ctx, i); true } catch (e: SecurityException) {
                if (uris.isEmpty()) false else start(ctx)
            } catch (e: Exception) { false }
        }

        /** 보낼 건이 있는데 서비스가 멈춰 있으면 시작한다(앱 화면을 열 때). */
        fun resumeIfNeeded(ctx: Context) {
            if (instance == null && Outbox.hasActive(ctx)) start(ctx)
        }

        /** 상대 PC가 켜졌다(그 PC에서 메시지가 옴): 그 PC로 보낼 대기 건을 바로 보낸다. 실패해도 조용히. */
        fun wake(ctx: Context, dev: String) {
            try {
                if (!Outbox.wake(ctx, dev)) return
                val s = instance
                if (s != null) s.ensureWorkers() else start(ctx.applicationContext)
            } catch (e: Exception) { }
        }

        /** 화면·알림에서 취소한 건: 보내는 중이면 연결을 끊는다. */
        fun cancel(ctx: Context, id: String) {
            Outbox.remove(ctx, id)
            instance?.tokens?.get(id)?.cancel()
        }

        fun cancelAll(ctx: Context) {
            val gone = Outbox.cancelActive(ctx)
            val s = instance ?: return
            gone.forEach { s.tokens[it.id]?.cancel() }
        }

        /** 연결 해제: 그 PC로 보낼 건을 지우고 보내는 중이면 끊는다. */
        fun forgetDevice(ctx: Context, dev: String) {
            val gone = Outbox.removeDevice(ctx, dev)
            val s = instance ?: return
            gone.forEach { s.tokens[it.id]?.cancel() }
        }

        fun retry(ctx: Context, id: String) {
            Outbox.retry(ctx, id) ?: return
            val s = instance
            if (s != null) s.ensureWorkers() else start(ctx)
        }

        /** 알림·말풍선에 보일 이름: 파일 이름 또는 "텍스트". */
        fun what(ctx: Context, j: OutboxJob): String =
            if (j.isText) ctx.getString(R.string.outbox_text) else j.name ?: ctx.getString(R.string.file_default)

        /** 마지막 실패 이유(화면 언어로). 모르는 코드면 함께 적어 둔 문장을, 그것도 없으면 "알 수 없는 오류". */
        fun reason(ctx: Context, j: OutboxJob): String {
            val r = Errors.text(ctx, ProtocolException(j.error ?: "unknown", j.detail))
            return if (j.gaveUp) ctx.getString(R.string.outbox_gave_up, r) else r
        }
    }

    private fun what(j: OutboxJob) = what(this, j)
}
