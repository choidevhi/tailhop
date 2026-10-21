package com.choidev.tailhop

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 보낼 곳 목록(outbox, docs/PROTOCOL.md §11.3). 앱 전용 저장소의 outbox.json에 적어 두어 화면이 보여 주고,
 * 프로세스가 끝나도 이어서 보낸다. 실제 보내기는 SendService가 한다. 다 보낸 건은 목록에서 빠지고 기록(History)에 들어간다.
 * 실패한 건은 사용자가 다시 보내거나 지울 때까지 남는다.
 */
object Outbox {
    /** 목록이 끝없이 커지지 않게(넘으면 오래된 실패부터 버린다). */
    private const val MAX = 500
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private var cache: MutableList<OutboxJob>? = null
    /** 보내는 중인 건의 보낸 바이트(화면용, 저장하지 않는다). */
    private val progress = ConcurrentHashMap<String, Long>()
    @Volatile private var lastProgressNotify = 0L

    /** 앱 내부 UI·서비스 갱신용(프로세스 로컬). 호출 스레드는 임의 — UI는 직접 메인 스레드로 넘길 것. */
    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun changed() { listeners.forEach { try { it() } catch (e: Exception) { } } }

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, "outbox.json")

    private fun list(ctx: Context): MutableList<OutboxJob> {
        cache?.let { return it }
        val raw = try { file(ctx).readText() } catch (e: Exception) { null }
        return OutboxCodec.parse(raw, System.currentTimeMillis(), listOf(ctx.packageName + ".files")).toMutableList()
            .also { cache = it }
    }

    /**
     * 빠진 건이 영구로 받아 둔 읽기 권한(대화방·클라우드 화면에서 고른 파일, takePersistableUriPermission)을 돌려준다(H4).
     * 남은 건이 같은 URI를 쓰면 둔다. 받아 둔 적이 없는 URI(공유로 받은 임시 권한)는 건너뛴다.
     */
    private fun releaseUris(ctx: Context, gone: List<OutboxJob>) {
        val free = OutboxCodec.urisToRelease(gone, list(ctx))
        if (free.isEmpty()) return
        val cr = ctx.contentResolver
        val held = try { cr.persistedUriPermissions.map { it.uri.toString() }.toSet() } catch (e: Exception) { return }
        for (u in free) {
            if (u !in held) continue
            try { cr.releasePersistableUriPermission(Uri.parse(u), Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) { }
        }
    }

    private fun persist(ctx: Context) {
        val f = file(ctx)
        val tmp = File(f.path + ".tmp")
        try {
            tmp.writeText(OutboxCodec.encode(list(ctx)))
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        } catch (e: Exception) { } // 못 써도 이 프로세스 안에서는 보낸다
    }

    private fun commit(ctx: Context) { persist(ctx); changed() }

    @Synchronized fun all(ctx: Context): List<OutboxJob> = list(ctx).toList()

    @Synchronized fun get(ctx: Context, id: String): OutboxJob? = list(ctx).firstOrNull { it.id == id }

    /** 이 PC의 대화방에 보일 건(텍스트·파일, 넣은 순서). 클라우드 건은 클라우드 화면에 따로 보인다. */
    @Synchronized fun forDevice(ctx: Context, dev: String): List<OutboxJob> = list(ctx).filter { it.dev == dev && !it.isCloud }

    /** 이 PC의 클라우드 올리기·가져오기(넣은 순서). */
    @Synchronized fun cloudJobs(ctx: Context, dev: String): List<OutboxJob> = list(ctx).filter { it.dev == dev && it.isCloud }

    /** 아직 보낼 건(대기·보내는 중)이 있는지. 없으면 서비스가 멈춘다. */
    @Synchronized fun hasActive(ctx: Context): Boolean = list(ctx).any { !it.failed }

    /** 보낼 건이 있는 줄(OutboxJob.lane: PC마다 대화 줄·클라우드 줄). */
    @Synchronized fun activeLanes(ctx: Context): Set<String> = list(ctx).filter { !it.failed }.map { it.lane }.toSet()

    /** 이 줄의 다음 건: 실패하지 않은 건 중 가장 먼저 넣은 것(줄마다 차례로 보낸다). */
    @Synchronized fun head(ctx: Context, lane: String): OutboxJob? = list(ctx).firstOrNull { it.lane == lane && !it.failed }

    /** 줄마다 맨 앞 건(2.2.0, Idle: 모두 상대가 꺼져 기다리는지 볼 때). */
    @Synchronized fun heads(ctx: Context): List<OutboxJob> = list(ctx).filter { !it.failed }.distinctBy { it.lane }

    /** 30일(Retry.KEEP_MS)이 지나도록 기다리기만 한 건을 포기로 둔다(Retry.expire). 포기한 건을 돌려준다(알림은 부른 쪽). */
    @Synchronized
    fun expire(ctx: Context, now: Long): List<OutboxJob> {
        val l = list(ctx)
        val out = ArrayList<OutboxJob>()
        for (i in l.indices) Retry.expire(l[i], now)?.let { l[i] = it; out += it }
        if (out.isNotEmpty()) commit(ctx)
        return out
    }

    /** 영구로 받아 둔 읽기 권한(Idle.needsGrant). 읽지 못하면 빈 집합(= 모두 임시로 본다, 쉬지 않는다). */
    fun persistedUris(ctx: Context): Set<String> =
        try { ctx.contentResolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri.toString() }.toSet() }
        catch (e: Exception) { emptySet() }

    @Synchronized
    private fun add(ctx: Context, jobs: List<OutboxJob>) {
        val l = list(ctx)
        l += jobs
        while (l.size > MAX) {
            val i = l.indexOfFirst { it.failed }
            if (i < 0) break
            releaseUris(ctx, listOf(l.removeAt(i)))
        }
        commit(ctx)
    }

    fun enqueueText(ctx: Context, dev: String, text: String) {
        val now = System.currentTimeMillis()
        add(ctx, listOf(OutboxJob(id = Protocol.newId(), dev = dev, isText = true, text = text, xfer = Xfer.newXfer(), created = now)))
    }

    /** 파일 이름·크기는 SendService가 채운다(화면에서 content provider를 기다리지 않게). */
    fun enqueueFiles(ctx: Context, dev: String, uris: List<String>) {
        val now = System.currentTimeMillis()
        add(ctx, uris.map { OutboxJob(id = Protocol.newId(), dev = dev, isText = false, uri = it, xfer = Xfer.newXfer(), created = now) })
    }

    /** 클라우드 올리기: 파일들을 PC 클라우드의 dir 폴더에. 이름·크기는 SendService가 채운다. */
    fun enqueueCloudUpload(ctx: Context, dev: String, dir: String, uris: List<String>) {
        val now = System.currentTimeMillis()
        add(ctx, uris.map {
            OutboxJob(id = Protocol.newId(), dev = dev, isText = false, uri = it, xfer = Xfer.newXfer(), created = now,
                kind = OutboxJob.CLOUD_UP, remote = dir)
        })
    }

    /** 클라우드 가져오기: PC 클라우드의 path 파일을 Download/TailHop에. size는 목록에서 본 크기(진행률용, 받으면 허브 값으로 바뀐다). */
    fun enqueueCloudDownload(ctx: Context, dev: String, path: String, size: Long) {
        val now = System.currentTimeMillis()
        add(ctx, listOf(OutboxJob(id = Protocol.newId(), dev = dev, isText = false, name = CloudPath.name(path), size = size,
            xfer = Xfer.newXfer(), created = now, kind = OutboxJob.CLOUD_DOWN, remote = path)))
    }

    /** 한 건을 바꾼다(없으면 null — 그 사이 취소됨). */
    @Synchronized
    fun update(ctx: Context, id: String, f: (OutboxJob) -> OutboxJob): OutboxJob? {
        val l = list(ctx)
        val i = l.indexOfFirst { it.id == id }
        if (i < 0) return null
        val j = f(l[i])
        l[i] = j
        commit(ctx)
        return j
    }

    /** 다 보냄·취소·지우기. 지운 건을 돌려준다. */
    @Synchronized
    fun remove(ctx: Context, id: String): OutboxJob? {
        val l = list(ctx)
        val i = l.indexOfFirst { it.id == id }
        if (i < 0) return null
        progress.remove(id)
        return l.removeAt(i).also { commit(ctx); releaseUris(ctx, listOf(it)) }
    }

    /** 알림의 "모두 취소": 아직 보낼 건을 모두 지운다(실패한 건은 화면에 남긴다). */
    @Synchronized
    fun cancelActive(ctx: Context): List<OutboxJob> {
        val l = list(ctx)
        val gone = l.filter { !it.failed }
        if (gone.isEmpty()) return gone
        l.removeAll(gone.toSet())
        gone.forEach { progress.remove(it.id) }
        commit(ctx)
        releaseUris(ctx, gone)
        return gone
    }

    /** 연결 해제: 그 PC로 보낼 건을 모두 지운다. */
    @Synchronized
    fun removeDevice(ctx: Context, dev: String): List<OutboxJob> {
        val l = list(ctx)
        val gone = l.filter { it.dev == dev }
        if (gone.isEmpty()) return gone
        l.removeAll(gone.toSet())
        commit(ctx)
        releaseUris(ctx, gone)
        return gone
    }

    /** 사용자가 고른 "지금 다시 시도"·"다시 보내기". */
    fun retry(ctx: Context, id: String): OutboxJob? = update(ctx, id) { j ->
        val now = System.currentTimeMillis()
        if (j.failed) Retry.restart(j, now) else j.copy(nextAt = now)
    }

    /** 상대에게서 메시지가 왔다(상대가 켜졌다): 그 PC의 대기를 바로 깨운다. 깨운 건이 있으면 true. */
    @Synchronized
    fun wake(ctx: Context, dev: String): Boolean {
        val now = System.currentTimeMillis()
        val l = list(ctx)
        var any = false
        var moved = false
        for (i in l.indices) {
            val j = l[i]
            if (j.dev != dev || j.failed) continue
            any = true
            if (j.nextAt > now) { l[i] = j.copy(nextAt = now); moved = true }
        }
        if (moved) commit(ctx)
        return any
    }

    fun progressOf(id: String): Long = progress[id] ?: 0L

    /** 진행률: 저장하지 않고, 화면·알림 갱신은 0.25초에 한 번만. */
    fun setProgress(id: String, sent: Long, total: Long) {
        progress[id] = sent
        val now = System.currentTimeMillis()
        if (sent >= total || now - lastProgressNotify >= 250) {
            lastProgressNotify = now
            changed()
        }
    }

    fun clearProgress(id: String) { progress.remove(id) }
}
