package com.choidev.tailhop

import org.json.JSONArray
import org.json.JSONObject

/**
 * 받다 만 조각 하나(§11.2). loc은 조각이 있는 곳: API 29+는 MediaStore 대기 항목(IS_PENDING=1)의 content URI,
 * API 26–28은 Download/TailHop 안의 `.part` 파일 경로. ts는 마지막으로 쓴 시각(24시간이 지나면 지운다).
 * dest는 받을 폴더(Download/TailHop 아래 상대 경로, 대화방 파일은 "", 클라우드는 "Cloud/…", 1.8.0) — 이어받을 때 같아야 한다.
 */
data class PartialRec(val peer: String, val xfer: String, val size: Long, val loc: String, val name: String, val ts: Long,
                      val dest: String = "")

/**
 * 이어받기 장부(docs/PROTOCOL.md §11): 받다 만 조각과 끝까지 받은 xfer를 상대(PC IP)별로 24시간 기억한다.
 * 파일을 지우거나 저장하지 않는다 — 지울 조각을 돌려주면 부른 쪽(Transfers)이 지운다. 안드로이드 의존성 없음(JVM 단위 테스트).
 * 스레드 안전하지 않다(Transfers가 잠근다). PC의 resume.ResumeBook과 같은 규칙이다.
 */
class TransferBook(private val clock: () -> Long = System::currentTimeMillis) {
    private val partials = LinkedHashMap<String, PartialRec>()
    private val done = LinkedHashMap<String, Long>()
    /** 지금 연결이 쓰고 있는 조각(오래돼도 prune·forgetPeer가 지우지 않는다). */
    private val busy = HashSet<String>()

    val partialCount: Int get() = partials.size
    val doneCount: Int get() = done.size

    private fun fresh(ts: Long, now: Long) = now - ts <= Xfer.KEEP_MS

    /** 24시간 지난 조각·끝난 기록과 상한을 넘는 오래된 것을 버린다. 지울 조각 파일 목록을 돌려준다. */
    fun prune(): List<PartialRec> {
        val now = clock()
        val gone = ArrayList<PartialRec>()
        partials.entries.removeIf { (k, p) -> (!fresh(p.ts, now) && k !in busy).also { if (it) gone += p } }
        // 조각이 너무 많으면(디스크를 계속 잡아먹지 않게) 오래된 것부터.
        if (partials.size > MAX_PARTIALS) {
            val extra = partials.entries.filter { it.key !in busy }.sortedBy { it.value.ts }.take(partials.size - MAX_PARTIALS)
            extra.forEach { partials.remove(it.key); gone += it.value }
        }
        done.entries.removeIf { !fresh(it.value, now) }
        if (done.size > MAX_DONE) {
            val old = done.entries.sortedBy { it.value }.take(done.size - MAX_DONE).map { it.key }
            old.forEach { done.remove(it) }
        }
        return gone
    }

    /** 같은 상대·xfer·size의 살아 있는 조각(hello의 have, 이어받기). */
    fun partial(peer: String, xfer: String, size: Long): PartialRec? =
        partials[key(peer, xfer)]?.takeIf { it.size == size && fresh(it.ts, clock()) }

    fun isDone(peer: String, xfer: String): Boolean = done[key(peer, xfer)]?.let { fresh(it, clock()) } == true

    /** 처음부터 받는 조각을 적는다. 같은 키의 예전 조각(다른 위치)은 돌려준다(부른 쪽이 지운다). */
    fun begin(peer: String, xfer: String, size: Long, loc: String, name: String, dest: String = ""): PartialRec? {
        val k = key(peer, xfer)
        val old = partials[k]?.takeIf { it.loc != loc }
        partials[k] = PartialRec(peer, xfer, size, loc, name, clock(), dest)
        busy += k
        return old
    }

    /** 이어 받을 조각을 잡는다(같은 size일 때만). 잡으면 쓰는 중으로 표시하고 시각을 새로 한다. */
    fun claim(peer: String, xfer: String, size: Long): PartialRec? {
        val k = key(peer, xfer)
        val p = partial(peer, xfer, size) ?: return null
        busy += k
        return p.copy(ts = clock()).also { partials[k] = it }
    }

    /** 끊김·시간 초과: 조각을 남긴다. 그 사이 같은 키를 다른 조각이 차지했으면 false(내 조각 파일은 부른 쪽이 지운다). */
    fun keep(peer: String, xfer: String, loc: String): Boolean {
        val k = key(peer, xfer)
        busy -= k
        val p = partials[k]
        if (p == null || p.loc != loc) return false
        partials[k] = p.copy(ts = clock())
        return true
    }

    /** 규칙 위반·해시 불일치·디스크 부족: 기록을 지운다(파일은 부른 쪽이 지운다). */
    fun drop(peer: String, xfer: String, loc: String) {
        val k = key(peer, xfer)
        busy -= k
        if (partials[k]?.loc == loc) partials.remove(k)
    }

    /** 끝까지 받음(글은 기록에 넣은 뒤): 조각 기록을 지우고 끝난 xfer로 남긴다. */
    fun finish(peer: String, xfer: String) {
        val k = key(peer, xfer)
        busy -= k
        partials.remove(k)
        done[k] = clock()
    }

    /** 연결 해제: 그 상대의 조각(지울 목록)과 끝난 기록을 버린다. */
    fun forgetPeer(peer: String): List<PartialRec> {
        val gone = partials.entries.filter { it.value.peer == peer && it.key !in busy }
        gone.forEach { partials.remove(it.key) }
        done.keys.removeIf { it.startsWith("$peer|") }
        return gone.map { it.value }
    }

    fun encode(): String {
        val arr = JSONArray()
        partials.values.forEach {
            val o = JSONObject().put("peer", it.peer).put("xfer", it.xfer).put("size", it.size)
                .put("loc", it.loc).put("name", it.name).put("ts", it.ts)
            if (it.dest.isNotEmpty()) o.put("dest", it.dest)
            arr.put(o)
        }
        val d = JSONObject()
        done.forEach { (k, v) -> d.put(k, v) }
        return JSONObject().put("partials", arr).put("done", d).toString()
    }

    companion object {
        const val MAX_PARTIALS = 32
        const val MAX_DONE = 5000

        fun key(peer: String, xfer: String) = "$peer|$xfer"

        /** 저장한 장부를 읽는다. 깨진 항목은 건너뛴다(장부가 없어도 받기는 된다 — 이어받기만 못 할 뿐). */
        fun parse(json: String?, clock: () -> Long = System::currentTimeMillis): TransferBook {
            val book = TransferBook(clock)
            val o = try { JSONObject(json ?: return book) } catch (e: Exception) { return book }
            val arr = o.optJSONArray("partials")
            if (arr != null) for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                try {
                    val rec = PartialRec(p.getString("peer"), p.getString("xfer"), p.getLong("size"),
                        p.getString("loc"), p.optString("name", "file"), p.getLong("ts"), p.optString("dest", ""))
                    if (Xfer.isValid(rec.xfer) && rec.size >= 0) book.partials[key(rec.peer, rec.xfer)] = rec
                } catch (e: Exception) { }
            }
            val d = o.optJSONObject("done")
            if (d != null) for (k in d.keys()) {
                val ts = Xfer.jsonLong(d.opt(k)) ?: continue
                book.done[k] = ts
            }
            return book
        }
    }
}
