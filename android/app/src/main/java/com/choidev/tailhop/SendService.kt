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
 * 실패하면 3초·10초·30초·1분·2분, 그 뒤 5분마다 다시 보내고 30일(Retry.KEEP_MS)이 지나면 실패로 둔다.
 * 보낼 건이 남아 있는 동안만 포그라운드로 돌고, 다 보내면(남은 건이 실패뿐이면) 멈춘다.
 *
 * 2.2.0 쉬기(Idle): 상대 PC가 꺼져 있어(연결 실패) 다음 시도까지 2분 넘게 기다려야 하는 줄은 일꾼을 끝내고, 모든 줄이 그렇게 기다리기만
 * 하면 서비스를 멈추고 OutboxWake(JobScheduler)를 걸어 둔다. 상대가 보낸 메시지·hello(ReceiverService → wake), 이 폰의 hello가
 * 닿음(HelloPing), 그 작업이 다시 깨운다. 단 공유받은 파일(임시 권한, 아래)이 남아 있으면 예전처럼 서비스가 기다린다.
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
    /** 모든 줄이 기다리기만 해 멈추는 중(Idle). 깨우기는 이 서비스 대신 새로 시작한다. */
    @Volatile private var sleeping = false
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
        sleeping = false
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

    /** 보낼 건이 있는 줄(PC마다 대화 줄·클라우드 줄, OutboxJob.lane)마다 일꾼 하나. */
    private fun ensureWorkers() {
        if (!running) return
        synchronized(workers) {
            for (lane in Outbox.activeLanes(this)) {
                if (workers.containsKey(lane)) continue
                workers[lane] = Thread({ work(lane) }, "tailhop-send").also { it.start() }
            }
        }
        kick()
    }

    /** 한 줄의 일꾼: 그 줄의 맨 앞 건을 다음 시도 시각에 보낸다. 보낼 건이 없으면 끝난다. */
    private fun work(lane: String) {
        while (running) {
            val job = synchronized(workers) {
                Outbox.head(this, lane) ?: run { workers.remove(lane); null }
            } ?: break
            if (job.nextAt > System.currentTimeMillis()) {
                // 상대가 꺼져 오래 기다릴 줄: 모든 줄이 그러면 서비스를 멈춘다(maybeSleep). 멈추면 이 일꾼도 끝난다(running).
                if (Idle.parked(job, System.currentTimeMillis())) ui.post { maybeSleep() }
                // 잠금 안에서 다시 읽고 잔다: 그 사이 깨우기(wake·취소·새 건)가 와도 놓치지 않게.
                synchronized(signal) {
                    val wait = (Outbox.head(this, lane)?.nextAt ?: 0L) - System.currentTimeMillis()
                    if (wait > 0 && running) try { signal.wait(wait) } catch (e: InterruptedException) { }
                }
                continue
            }
            attempt(job)
        }
        synchronized(workers) { if (workers[lane] === Thread.currentThread()) workers.remove(lane) }
        ui.post { maybeStop() }
    }

    /** 보낼 건이 없으면 멈춘다(깨우는 작업도 끈다). 그 사이 새 시작 요청이 왔으면(start id가 바뀜) 멈추지 않는다. */
    private fun maybeStop() {
        if (!running || Outbox.hasActive(this)) { refresh(); return }
        synchronized(workers) { if (workers.isNotEmpty()) return }
        OutboxWake.cancel(this)
        stopSelfResult(lastStartId)
    }

    /**
     * 쉬기(Idle): 모든 줄의 맨 앞이 상대가 꺼져 2분 넘게 기다리는 중이고 임시 권한에 기대는 건이 없으면, 깨우는 작업을 걸고 멈춘다
     * (기다리던 일꾼들은 running이 꺼지며 끝난다). 메인 스레드에서만. 멈추지 못했으면(새 시작 요청) 그대로 돈다.
     */
    private fun maybeSleep() {
        if (!running || sleeping || !Outbox.hasActive(this)) return
        if (!Idle.allParked(Outbox.heads(this), System.currentTimeMillis()) || !sleepAllowed()) return
        OutboxWake.schedule(this)
        sleeping = true
        if (!stopSelfResult(lastStartId)) sleeping = false
    }

    /** 서비스를 멈춰도 되는지: 남은 건 중 이 서비스의 시작 요청에 묶인 임시 읽기 권한에 기대는 것이 없다(Idle.canSleep). */
    private fun sleepAllowed(): Boolean = Idle.canSleep(Outbox.all(this), Outbox.persistedUris(this))

    private fun attempt(job: OutboxJob) {
        val token = SendToken()
        tokens[job.id] = token
        Outbox.update(this, job.id) { it.copy(state = OutboxJob.SENDING) } ?: run { tokens.remove(job.id); return }
        try {
            val p = Store.get(this, job.dev) ?: throw ProtocolException("unpaired")
            when (job.kind) {
                OutboxJob.TEXT -> sendText(job, p, token)
                OutboxJob.CLOUD_UP -> uploadCloud(job, p, token)
                OutboxJob.CLOUD_DOWN -> downloadCloud(job, p, token)
                else -> sendFile(job, p, token)
            }
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
        val now = System.currentTimeMillis()
        var dropPart: String? = null
        val j = Outbox.update(this, id) {
            when {
                it.kind == OutboxJob.CLOUD_DOWN && code == "cloud_changed" -> {
                    dropPart = it.part // 허브의 파일이 바뀌었다: 조각을 버리고 처음부터(한 번만)
                    Retry.afterChanged(it, now)
                }
                // 허브가 알린 크기와 맞지 않게 왔다: 그 조각은 믿을 수 없으니 버린다(다시 받으면 처음부터).
                it.kind == OutboxJob.CLOUD_DOWN && code in BROKEN_DOWNLOAD -> {
                    dropPart = it.part
                    Retry.afterFailure(it.copy(part = null, size = -1, mtime = null), now, Retry.isRetryable(e), code, detail)
                }
                else -> Retry.afterFailure(it, now, Retry.isRetryable(e), code, detail)
            }
        } ?: return
        dropPart?.let { PartFiles.delete(this, it) }
        if (j.failed) notifyFailed(this, j)
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

    /**
     * 클라우드 올리기(§12.2 cloud_put): 파일 보내기와 같은 이어 보내기·트레일러에 올릴 폴더(remote)를 더한다.
     * 상대가 클라우드를 모르면 cloud_unsupported, 꺼져 있으면 cloud_off(둘 다 다시 시도하지 않는다). 대화 기록에는 넣지 않는다.
     */
    private fun uploadCloud(job: OutboxJob, p: Pairing, token: SendToken) {
        val uri = Uri.parse(job.uri ?: throw ProtocolException("file_gone"))
        Net.openSource(this, uri).close()
        val meta = Net.fileMeta(this, uri)
        if (meta.size > Protocol.MAX_FILE) throw ProtocolException("file_too_large")
        val mtime = Net.lastModified(this, uri)
        val changed = job.size >= 0 && (job.size != meta.size || (job.mtime != null && mtime != null && job.mtime != mtime))
        val cur = Outbox.update(this, job.id) {
            it.copy(name = meta.name, size = meta.size, mtime = mtime, xfer = if (changed) Xfer.newXfer() else it.xfer)
        } ?: return
        val hello = Net.hello(p, cur.xfer, meta.size, token) ?: throw ProtocolException("cloud_unsupported")
        caps[p.pcIp] = hello.caps
        if ("cloud" !in hello.caps || !hello.xfer) throw ProtocolException("cloud_unsupported")
        if (!hello.cloud) throw ProtocolException("cloud_off")
        if (!hello.done) {
            Net.sendFileXfer(this, uri, meta, p, cur.xfer, Xfer.resumeAt(hello, meta.size), token,
                { sent, total -> Outbox.setProgress(job.id, sent, total) }, cloudDir = job.remote ?: "")
        }
        Outbox.remove(this, job.id)
    }

    /**
     * 클라우드 가져오기(§12.2 cloud_get) → Download/TailHop. 받다 만 조각(job.part)이 있으면 그 길이부터, 조각을 만들 때의
     * size·mtime을 함께 보내 이어 받는다. 허브의 파일이 바뀌었으면 cloud_changed → fail()이 조각을 버리고 한 번만 처음부터.
     * 조각이 사라졌거나(사용자·시스템이 지움) 알린 크기보다 길면 처음부터 받는다.
     */
    private fun downloadCloud(job: OutboxJob, p: Pairing, token: SendToken) {
        val remote = job.remote ?: throw ProtocolException("bad_path")
        val name = Protocol.sanitizeFilename(job.name ?: CloudPath.name(remote))
        var part = job.part
        var offset = part?.let { PartFiles.length(this, it) } ?: 0L
        if (part != null && (PartFiles.length(this, part) == null || offset > job.size || job.mtime == null)) {
            PartFiles.delete(this, part)
            part = null
            offset = 0L
            Outbox.update(this, job.id) { it.copy(part = null) } ?: return
        }
        val resuming = part != null
        Net.cloudGet(p, remote, offset, if (resuming) job.size else null, if (resuming) job.mtime else null, token, open = { head ->
            if (PartFiles.freeBytes() < head.size - offset + ReceiverService.DISK_RESERVE) throw ProtocolException("disk_full")
            val loc = part ?: PartFiles.create(this, name).also { created ->
                part = created
                // 조각을 만들 때의 허브 크기·시각을 적어 둔다(다음 이어받기에 보낸다). 그 사이 취소됐으면 조각을 지운다.
                Outbox.update(this, job.id) { it.copy(part = created, size = head.size, mtime = head.mtime) }
                    ?: run { PartFiles.delete(this, created); throw ProtocolException("disconnected") }
            }
            try { PartFiles.openAt(this, loc, offset, null) } catch (e: ProtocolException) {
                // 조각이 이어 받을 위치보다 짧아졌다: 버리고 다음 시도에 처음부터
                PartFiles.delete(this, loc)
                Outbox.update(this, job.id) { it.copy(part = null) }
                throw ProtocolException("resume_lost")
            }
        }, onProgress = { got, total -> Outbox.setProgress(job.id, got, total) })
        val loc = part ?: throw ProtocolException("storage")
        // 공개하기 전에 조각을 이 건에서 떼어 낸다: 그 사이 취소했으면(null) 취소 쪽이 조각을 지우고, 떼어 낸 뒤의 취소는
        // 공개한 파일을 지우지 않는다. 공개에 실패하면 조각을 지우고 실패로 보인다(다시 받기는 처음부터).
        Outbox.update(this, job.id) { it.copy(part = null) } ?: return
        val (uri, finalName) = try { PartFiles.commit(this, loc, name) } catch (e: Exception) {
            PartFiles.delete(this, loc); throw e
        }
        Outbox.remove(this, job.id)
        Notifs.cloudDownloaded(this, p.pcName, finalName, uri, PartFiles.mime(finalName))
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
        val head = getString(when (sending.kind) {
            OutboxJob.CLOUD_UP -> R.string.send_notif_uploading
            OutboxJob.CLOUD_DOWN -> R.string.send_notif_downloading
            else -> R.string.send_notif_sending
        }, what(sending), pct)
        return if (others > 0) head + " · " + resources.getQuantityString(R.plurals.send_notif_more, others, others) else head
    }

    companion object {
        private const val STALL_MS = 60_000L
        /** 클라우드에서 받은 조각을 버릴 실패(허브의 응답이 형식·크기 규칙과 맞지 않음). */
        private val BROKEN_DOWNLOAD = setOf("file_too_big", "file_incomplete", "bad_reply", "unreadable_reply")
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

        /** 지금 깨워 쓸 수 있는 서비스(멈추는 중이면 null — 새로 시작한다). */
        private val live: SendService? get() = instance?.takeIf { !it.sleeping }

        /**
         * 보낼 건이 있는데 서비스가 멈춰 있으면 시작한다(앱 화면을 열 때·네트워크가 바뀔 때·깨우는 작업). 상대가 꺼져 기다리기만 하는 줄뿐이면
         * 켜지 않는다(Idle.needsService) — 그 줄은 상대에 닿을 때(wake) 켠다.
         */
        fun resumeIfNeeded(ctx: Context) {
            if (live == null && Idle.needsService(Outbox.heads(ctx))) start(ctx.applicationContext)
        }

        /** 상대 PC가 켜졌다(그 PC에서 메시지·hello가 옴, 이 폰의 hello가 닿음): 그 PC로 보낼 대기 건을 바로 보낸다. 실패해도 조용히. */
        fun wake(ctx: Context, dev: String) {
            try {
                if (!Outbox.wake(ctx, dev)) return
                val s = live
                if (s != null) s.ensureWorkers() else start(ctx.applicationContext)
            } catch (e: Exception) { }
        }

        /** 끝내 보내지 못한 건의 알림(탭하면 그 PC의 대화방·클라우드 화면). */
        fun notifyFailed(ctx: Context, j: OutboxJob) {
            val pc = Store.get(ctx, j.dev)?.pcName ?: j.dev
            Notifs.sendFailed(ctx, j.dev, pc, what(ctx, j), reason(ctx, j), j.isCloud)
        }

        /** 화면·알림에서 취소한 건: 보내는 중이면 연결을 끊는다. 클라우드에서 받다 만 조각도 지운다. */
        fun cancel(ctx: Context, id: String) {
            val gone = Outbox.remove(ctx, id)
            instance?.tokens?.get(id)?.cancel()
            gone?.let { discard(ctx, listOf(it)) }
            OutboxWake.sync(ctx)
        }

        fun cancelAll(ctx: Context) {
            val gone = Outbox.cancelActive(ctx)
            instance?.let { s -> gone.forEach { s.tokens[it.id]?.cancel() } }
            discard(ctx, gone)
            OutboxWake.sync(ctx)
        }

        /** 연결 해제: 그 PC로 보낼 건을 지우고 보내는 중이면 끊는다. */
        fun forgetDevice(ctx: Context, dev: String) {
            val gone = Outbox.removeDevice(ctx, dev)
            instance?.let { s -> gone.forEach { s.tokens[it.id]?.cancel() } }
            discard(ctx, gone)
            OutboxWake.sync(ctx)
        }

        /**
         * 지운 클라우드 가져오기의 조각을 지운다. 보내는 중이던 연결은 위에서 끊었고, 그 일꾼이 끝난 뒤에 지우도록 따로 스레드에서 잠시 기다린다
         * (쓰는 중인 조각을 지우면 MediaStore가 실패를 돌려줄 수 있다).
         */
        private fun discard(ctx: Context, jobs: List<OutboxJob>) {
            val parts = jobs.mapNotNull { j -> j.part?.takeIf { j.kind == OutboxJob.CLOUD_DOWN } }
            if (parts.isEmpty()) return
            val app = ctx.applicationContext
            Thread {
                try { Thread.sleep(1500) } catch (e: InterruptedException) { }
                parts.forEach { PartFiles.delete(app, it) }
            }.start()
        }

        fun retry(ctx: Context, id: String) {
            Outbox.retry(ctx, id) ?: return
            val s = live
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
