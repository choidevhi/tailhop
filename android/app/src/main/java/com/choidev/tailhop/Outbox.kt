package com.choidev.tailhop

import android.content.Context
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
        return OutboxCodec.parse(raw, System.currentTimeMillis()).toMutableList().also { cache = it }
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

    /** 이 PC로 보낼 건(넣은 순서). */
    @Synchronized fun forDevice(ctx: Context, dev: String): List<OutboxJob> = list(ctx).filter { it.dev == dev }

    /** 아직 보낼 건(대기·보내는 중)이 있는지. 없으면 서비스가 멈춘다. */
    @Synchronized fun hasActive(ctx: Context): Boolean = list(ctx).any { !it.failed }

    @Synchronized fun activeDevices(ctx: Context): Set<String> = list(ctx).filter { !it.failed }.map { it.dev }.toSet()

    /** 이 PC의 다음 건: 실패하지 않은 건 중 가장 먼저 넣은 것(기기마다 차례로 보낸다). */
    @Synchronized fun head(ctx: Context, dev: String): OutboxJob? = list(ctx).firstOrNull { it.dev == dev && !it.failed }

    @Synchronized
    private fun add(ctx: Context, jobs: List<OutboxJob>) {
        val l = list(ctx)
        l += jobs
        while (l.size > MAX) {
            val i = l.indexOfFirst { it.failed }
            if (i < 0) break
            l.removeAt(i)
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
        return l.removeAt(i).also { commit(ctx) }
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
