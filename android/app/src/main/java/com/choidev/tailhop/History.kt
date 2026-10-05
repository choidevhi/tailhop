package com.choidev.tailhop

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class HistoryItem(
    val id: String, val ts: Long, val incoming: Boolean, val isText: Boolean,
    /** 텍스트(길면 앞부분 HistoryCodec.TEXT_INLINE자만). 전체는 History.fullText. */
    val text: String?, val name: String?, val size: Long, val uri: String?,
    /** 상대 PC IP(기기 id). 1.1 이하 기록은 null → 첫 페어링 소속. */
    val dev: String? = null,
    /** 전체 텍스트 글자 수. 0이 아니면 전체 글은 texts/<id>.txt에 따로 있다. */
    val textLen: Int = 0,
)

/**
 * 기록 JSON ↔ 항목 (안드로이드 의존 없음, JVM 단위 테스트용).
 * 1.5부터 긴 텍스트는 목록에 앞부분만 둔다. 1.4까지는 텍스트 전체(최대 1 MiB × 200개)가 목록에 있어
 * 화면을 새로 그릴 때마다 그 전체를 읽고 파싱했다.
 */
object HistoryCodec {
    const val TEXT_INLINE = 2000

    fun parse(json: String): List<HistoryItem> = try {
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            HistoryItem(
                o.getString("id"), o.getLong("ts"), o.getBoolean("in"), o.getBoolean("t"),
                if (o.has("text")) o.getString("text") else null,
                if (o.has("name")) o.getString("name") else null,
                o.optLong("size"), if (o.has("uri")) o.getString("uri") else null,
                if (o.has("dev")) o.getString("dev") else null,
                o.optInt("tlen", 0),
            )
        }
    } catch (e: Exception) { emptyList() }

    fun encode(list: List<HistoryItem>): String {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().apply {
                put("id", it.id); put("ts", it.ts); put("in", it.incoming); put("t", it.isText)
                it.text?.let { t -> put("text", t) }
                it.name?.let { n -> put("name", n) }
                put("size", it.size)
                it.uri?.let { u -> put("uri", u) }
                it.dev?.let { d -> put("dev", d) }
                if (it.textLen > 0) put("tlen", it.textLen)
            })
        }
        return arr.toString()
    }

    /** 긴 텍스트면 (목록에 둘 항목, 따로 저장할 전체 글), 아니면 (그대로, null). */
    fun split(item: HistoryItem): Pair<HistoryItem, String?> {
        val text = item.text
        if (!item.isText || text == null || item.textLen > 0 || text.length <= TEXT_INLINE) return item to null
        return item.copy(text = text.take(TEXT_INLINE), textLen = text.length) to text
    }
}

/** 주고받은 기록(앱 전용 저장소의 작은 JSON 파일 + 긴 텍스트 파일). 목록은 메모리에 한 번만 읽어 둔다. */
object History {
    private const val MAX = 200
    private val listeners = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()
    @Volatile private var cache: List<HistoryItem>? = null
    private val ID_RE = Regex("[0-9a-f]{32}")

    /** 앱 내부 UI 갱신용 리스너(프로세스 로컬). 호출 스레드는 임의 — UI는 직접 메인 스레드로 넘길 것. */
    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun changed() { listeners.forEach { try { it() } catch (e: Exception) { } } }

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, "history.json")
    private fun textDir(ctx: Context) = File(ctx.applicationContext.filesDir, "texts")
    private fun textFile(ctx: Context, id: String) = if (ID_RE.matches(id)) File(textDir(ctx), "$id.txt") else null

    @Synchronized
    fun all(ctx: Context): List<HistoryItem> {
        cache?.let { return it }
        val raw = try { file(ctx).readText() } catch (e: Exception) { "[]" }
        var list = HistoryCodec.parse(raw)
        // 1.4 이하 기록: 긴 텍스트를 한 번 따로 옮긴다.
        if (list.any { it.isText && it.textLen == 0 && (it.text?.length ?: 0) > HistoryCodec.TEXT_INLINE }) {
            list = list.map { externalize(ctx, it) }
            try { persist(ctx, list) } catch (e: Exception) { } // 못 쓰면 다음에 다시 옮긴다(따로 둔 파일은 id로 덮어쓴다)
        }
        cache = list
        return list
    }

    private fun externalize(ctx: Context, item: HistoryItem): HistoryItem {
        val (kept, full) = HistoryCodec.split(item)
        if (full == null) return item
        val f = textFile(ctx, item.id) ?: return kept
        return try {
            textDir(ctx).mkdirs()
            val tmp = File(f.path + ".tmp")
            tmp.writeText(full)
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            kept
        } catch (e: Exception) { item } // 못 옮기면 그대로 둔다
    }

    /** 항목의 전체 텍스트(따로 둔 파일이 없어졌으면 남은 앞부분). */
    fun fullText(ctx: Context, item: HistoryItem): String {
        if (item.textLen > 0) textFile(ctx, item.id)?.let { f -> try { return f.readText() } catch (e: Exception) { } }
        return item.text ?: ""
    }

    private fun dropFiles(ctx: Context, removed: List<HistoryItem>) {
        removed.forEach { if (it.textLen > 0) textFile(ctx, it.id)?.delete() }
    }

    @Synchronized
    fun add(ctx: Context, item: HistoryItem) {
        val all = listOf(externalize(ctx, item)) + all(ctx)
        dropFiles(ctx, all.drop(MAX))
        write(ctx, all.take(MAX))
    }

    /** 한 기기의 기록만 지운다(레거시 기록은 첫 기기 소속). */
    @Synchronized
    fun clearDevice(ctx: Context, ip: String) {
        val first = Store.firstIp(ctx)
        val (gone, keep) = all(ctx).partition { it.dev == ip || (it.dev == null && ip == first) }
        dropFiles(ctx, gone)
        write(ctx, keep)
    }

    private fun persist(ctx: Context, list: List<HistoryItem>) {
        val f = file(ctx)
        val tmp = File(f.path + ".tmp")
        tmp.writeText(HistoryCodec.encode(list))
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    @Synchronized
    private fun write(ctx: Context, list: List<HistoryItem>) {
        persist(ctx, list)
        cache = list
        changed()
    }

    /** 특정 기기의 기록(최신 먼저). */
    fun forDevice(ctx: Context, ip: String): List<HistoryItem> {
        val first = Store.firstIp(ctx)
        return all(ctx).filter { it.dev == ip || (it.dev == null && ip == first) }
    }

    fun find(ctx: Context, id: String) = all(ctx).firstOrNull { it.id == id }

    @Synchronized
    fun clear(ctx: Context) {
        file(ctx).delete()
        textDir(ctx).listFiles()?.forEach { it.delete() }
        cache = emptyList()
        changed()
    }
}
