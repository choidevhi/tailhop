package com.choidev.tailhop

import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.util.Locale

/**
 * 클라우드 모드(docs/PROTOCOL.md §12, 1.8.0)의 경로 규칙·목록·응답 형식. 안드로이드 의존성 없음(JVM 단위 테스트).
 * 허브(이 폰이 클라우드)와 클라이언트(PC 클라우드를 보는 쪽)가 같은 검사를 쓴다 — 한쪽만 느슨하면 다른 쪽이 거부한다.
 */
object CloudPath {
    const val MAX_SEGMENTS = 16
    const val MAX_LEN = 1024

    /**
     * §12.1: 빈 문자열(맨 위) 또는 `/`로 나눈 조각들. 각 조각은 비어 있지 않고 `.`·`..`이 아니며 §5 파일명 정리를 거쳐도
     * 그대로여야 한다(그래서 `\`·`:`·제어문자·장치 이름·앞의 `.`·끝의 `.`/공백이 모두 걸린다). 조각 16개, 전체 1024자(코드포인트, PC와 같다).
     */
    fun isValid(path: String): Boolean {
        if (path.isEmpty()) return true
        if (path.codePointCount(0, path.length) > MAX_LEN) return false
        val parts = path.split('/')
        if (parts.size > MAX_SEGMENTS) return false
        return parts.all { isSegment(it) }
    }

    fun isSegment(s: String): Boolean = s.isNotEmpty() && s != "." && s != ".." && Protocol.sanitizeFilename(s) == s

    /** 검사하고 조각으로. 어기면 bad_path. */
    fun segments(path: String): List<String> {
        if (!isValid(path)) throw ProtocolException("bad_path")
        return if (path.isEmpty()) emptyList() else path.split('/')
    }

    /** 헤더의 path·dir 필드: 없으면 맨 위(""), 있으면 규칙에 맞는 문자열이어야 한다(PC와 같다). */
    fun field(h: JSONObject, name: String = "path"): String {
        val v = Xfer.field(h, name) ?: return ""
        if (v !is String || !isValid(v)) throw ProtocolException("bad_path")
        return v
    }

    fun join(dir: String, name: String): String = if (dir.isEmpty()) name else "$dir/$name"

    fun parent(path: String): String = path.substringBeforeLast('/', "")

    fun name(path: String): String = path.substringAfterLast('/')

    /**
     * 목록에 보일 이름인지: 조각 규칙에 맞고 `.part`로 끝나지 않는다(받는 중인 조각·숨김 파일은 보이지 않는다, §12.1).
     * 숨김(앞이 `.`)은 조각 규칙이 이미 막는다.
     */
    fun isListable(name: String): Boolean = isSegment(name) && !isPartName(name)

    /** 클라우드에 둔 이름 뒤에 더 붙을 수 있는 글자 수: 같은 이름이 있을 때의 번호 " (n)"(n ≤ 99면 5자)와 여유 1자. */
    const val NAME_ROOM = 6
    private const val NAME_MAX = 150

    /**
     * 클라우드에 저장할 이름. 들어오는 이름은 이미 §5 정리를 거쳤다.
     * - `.part`로 끝나면 목록에 보이지 않으므로(§12.1) 뒤에 `_`를 붙인다.
     * - 같은 이름이 있으면 저장소가 " (1)"을 붙인다. 150자 이름에 그것이 붙으면 §5 규칙(150자)을 넘어 어느 PC에서도 보이지도 지워지지도
     *   않으므로, 처음부터 150 - NAME_ROOM자 안으로 줄인다(확장자는 남기고 앞부분을 자른다, PC와 같은 규칙).
     */
    fun storableName(sanitized: String): String {
        var n = if (isPartName(sanitized)) sanitized + "_" else sanitized
        val max = NAME_MAX - NAME_ROOM
        if (n.codePointCount(0, n.length) <= max) return n
        val dot = n.lastIndexOf('.')
        val ext = if (dot > 0) n.substring(dot) else ""
        val extLen = ext.codePointCount(0, ext.length)
        n = if (extLen in 2..17) cpTake(n.substring(0, dot), max - extLen).trimEnd(' ', '.') + ext
        else cpTake(n, max).trimEnd(' ', '.')
        return n
    }

    private fun cpTake(s: String, n: Int): String =
        if (s.codePointCount(0, s.length) <= n) s else s.substring(0, s.offsetByCodePoints(0, maxOf(n, 0)))

    /**
     * 허브가 요청을 받아들일 경로: 규칙에 맞고(isValid) 모든 조각이 목록에 보이는 이름이다(A6: `.part`로 끝나는 중간 폴더도 막는다).
     * 보이지 않는 이름이 낀 경로는 PC처럼 not_found로 답한다.
     */
    fun isVisiblePath(path: String): Boolean = isValid(path) && (path.isEmpty() || path.split('/').all { isListable(it) })

    /** 받는 중인 조각 이름인지. 공용 저장소는 대소문자를 가리지 않으므로 `.PART`도 같다(PC와 같은 규칙). */
    fun isPartName(name: String): Boolean = name.lowercase(java.util.Locale.ROOT).endsWith(".part")
}

/** 클라우드 목록의 한 항목(§12.2 cloud_list). mtime은 유닉스 ms. */
data class CloudItem(val name: String, val dir: Boolean, val size: Long, val mtime: Long)

/** 목록 한 쪽: 항목과 다음 쪽 cursor(없으면 null). */
data class CloudPage(val items: List<CloudItem>, val next: Int?)

object CloudListing {
    const val PAGE = 500

    /** 폴더 먼저, 이름순(대소문자 무시). 같으면 원래 이름으로 한 번 더 — 쪽을 나눠도 순서가 흔들리지 않게. */
    val ORDER: Comparator<CloudItem> = compareBy<CloudItem> { !it.dir }
        .thenBy { it.name.lowercase(Locale.ROOT) }
        .thenBy { it.name }

    /**
     * 정렬한 목록에서 cursor(앞에서 건너뛸 개수)부터 한 쪽. 끝까지 왔으면 next = null.
     * cursor가 목록보다 크면(그 사이 지워졌다) 빈 쪽을 돌려준다.
     */
    fun page(all: List<CloudItem>, cursor: Int, limit: Int = PAGE): CloudPage {
        require(cursor >= 0 && limit > 0)
        val sorted = all.sortedWith(ORDER)
        if (cursor >= sorted.size) return CloudPage(emptyList(), null)
        val end = minOf(sorted.size, cursor + limit)
        return CloudPage(sorted.subList(cursor, end).toList(), if (end < sorted.size) end else null)
    }

    /** 요청의 cursor: 없으면 0, 있으면 0 이상의 JSON 정수. 어기면 bad_header(§12에 정한 코드가 없다). */
    fun cursor(h: JSONObject): Int {
        val v = Xfer.field(h, "cursor") ?: return 0
        val c = Xfer.jsonLong(v)
        if (c == null || c < 0 || c > Int.MAX_VALUE) throw ProtocolException("bad_header")
        return c.toInt()
    }

    fun reply(page: CloudPage): JSONObject {
        val arr = JSONArray()
        page.items.forEach {
            arr.put(JSONObject().put("name", it.name).put("dir", it.dir).put("size", it.size).put("mtime", it.mtime))
        }
        return JSONObject().put("ok", true).put("items", arr).put("next", page.next ?: JSONObject.NULL)
    }

    /**
     * 허브의 목록 응답(클라이언트). 이름이 규칙에 맞지 않거나 형식이 틀린 항목은 버린다(허브가 걸러야 하지만 한 번 더).
     * items가 배열이 아니거나 next가 정수·null이 아니면 bad_reply.
     */
    fun parse(reply: JSONObject): CloudPage {
        val arr = reply.opt("items") as? JSONArray ?: throw ProtocolException("bad_reply")
        val out = ArrayList<CloudItem>()
        for (i in 0 until arr.length()) {
            val o = arr.opt(i) as? JSONObject ?: continue
            val name = o.opt("name") as? String ?: continue
            val dir = o.opt("dir") as? Boolean ?: continue
            val size = Xfer.jsonLong(o.opt("size")) ?: continue
            val mtime = Xfer.jsonLong(o.opt("mtime")) ?: continue
            if (!CloudPath.isListable(name) || size < 0) continue
            out += CloudItem(name, dir, size, mtime)
        }
        val nextRaw = Xfer.field(reply, "next")
        val next = if (nextRaw == null) null else Xfer.jsonLong(nextRaw)?.takeIf { it in 1..Int.MAX_VALUE }?.toInt()
            ?: throw ProtocolException("bad_reply")
        return CloudPage(out, next)
    }
}

/**
 * cloud_get(§12.2)의 선 형식. 허브는 먼저 `{"ok":true,"size","mtime"}`을 last=0 프레임으로, 이어서 offset부터 끝까지
 * 데이터 프레임(마지막만 last=1, 빈 평문 가능)을 보낸다. 거부는 §3 응답 한 프레임(last=1).
 */
object CloudWire {
    data class Head(val size: Long, val mtime: Long)

    /** 첫 프레임의 결과: 받을 파일 정보(Head) 또는 거부 응답(Refused, §3 JSON 그대로 — 문장은 부른 쪽이 만든다). */
    sealed class First {
        data class Ok(val head: Head) : First()
        data class Refused(val reply: JSONObject) : First()
    }

    fun head(size: Long, mtime: Long): ByteArray =
        JSONObject().put("ok", true).put("size", size).put("mtime", mtime).toString().toByteArray(Charsets.UTF_8)

    /** 첫 프레임 읽기. last=1이면 거부여야 한다(ok:true인데 last=1이면 형식 위반). */
    fun parseFirst(raw: ByteArray, last: Boolean): First {
        val o = try { JSONObject(String(raw, Charsets.UTF_8)) } catch (e: Exception) {
            throw ProtocolException("unreadable_reply")
        }
        if (o.opt("ok") != true) {
            if (!last) throw ProtocolException("bad_reply")
            return First.Refused(o)
        }
        if (last) throw ProtocolException("bad_reply")
        val size = Xfer.jsonLong(o.opt("size"))
        val mtime = Xfer.jsonLong(o.opt("mtime"))
        if (size == null || size < 0 || size > Protocol.MAX_FILE || mtime == null) throw ProtocolException("bad_reply")
        return First.Ok(Head(size, mtime))
    }

    /**
     * 요청의 path·offset·size·mtime(허브). offset은 없으면 0, 있으면 0 이상 정수. size·mtime은 선택 정수.
     * 형식이 틀리면 bad_header(§12에 정한 코드가 없어 PC와 같게).
     */
    data class GetReq(val path: String, val offset: Long, val size: Long?, val mtime: Long?)

    fun parseGet(h: JSONObject): GetReq {
        val path = CloudPath.field(h)
        fun int(name: String): Long? = Xfer.field(h, name)?.let { Xfer.jsonLong(it) ?: throw ProtocolException("bad_header") }
        val offset = int("offset") ?: 0L
        if (offset < 0) throw ProtocolException("bad_header")
        return GetReq(path, offset, int("size"), int("mtime"))
    }

    /**
     * 이어받는 사이 파일이 바뀌었는지(허브): 요청이 준 size·mtime이 지금과 다르면 cloud_changed. 이것을 먼저 본다 —
     * 파일이 줄어 offset이 크기를 넘은 경우에도 가져오는 쪽이 처음부터 다시 받을 수 있게. 그 뒤 offset이 크기보다 크면 bad_header.
     */
    fun checkGet(req: GetReq, now: Head) {
        if ((req.size != null && req.size != now.size) || (req.mtime != null && req.mtime != now.mtime))
            throw ProtocolException("cloud_changed")
        if (req.offset > now.size) throw ProtocolException("bad_header")
    }

    /**
     * 데이터 프레임을 out에 쓴다(클라이언트). offset + 받은 바이트가 size를 넘으면 file_too_big, last=1에서 모자라면 file_incomplete.
     * 끊기면 Channel이 disconnected·IOException을 던진다 — 부른 쪽은 쓴 데까지 조각을 남긴다.
     */
    fun readData(ch: Channel, offset: Long, size: Long, out: OutputStream, onProgress: ((Long) -> Unit)? = null): Long {
        var got = offset
        while (true) {
            val (data, last) = ch.recv()
            got += data.size
            if (got > size) throw ProtocolException("file_too_big")
            if (data.isNotEmpty()) out.write(data)
            onProgress?.invoke(got)
            if (last) break
        }
        if (got != size) throw ProtocolException("file_incomplete")
        return got
    }
}

/**
 * API 29+ 허브의 빈 폴더 기록(§12.2 cloud_mkdir). MediaStore에는 빈 폴더가 없어(폴더 = 파일들의 RELATIVE_PATH 앞부분)
 * 만든 폴더 경로를 앱 전용 저장소에 따로 적는다. 올리기(cloud_put)가 만든 폴더도 적어서, 안의 파일을 다 지워도 PC처럼
 * 폴더는 남는다. 폴더를 지우면 그 아래 기록도 지운다. 경로는 이미 검사한 것만 넣는다. 안드로이드 의존성 없음.
 */
class CloudDirs(private val clock: () -> Long = System::currentTimeMillis) {
    private val dirs = LinkedHashMap<String, Long>()

    val size: Int get() = dirs.size

    /** 경로와 그 위 폴더들을 모두 적는다(이미 있으면 시각을 바꾸지 않는다). */
    fun add(path: String) {
        if (path.isEmpty()) return
        var p = path
        while (p.isNotEmpty()) {
            if (!dirs.containsKey(p)) dirs[p] = clock()
            p = CloudPath.parent(p)
        }
        while (dirs.size > MAX) dirs.remove(dirs.keys.first())
    }

    fun has(path: String): Boolean = path.isEmpty() || dirs.containsKey(path)

    fun time(path: String): Long? = dirs[path]

    /** dir 바로 아래 기록된 폴더 이름. */
    fun children(dir: String): List<String> {
        val prefix = if (dir.isEmpty()) "" else "$dir/"
        return dirs.keys.filter { it.startsWith(prefix) && it.length > prefix.length && it.indexOf('/', prefix.length) < 0 }
            .map { it.substring(prefix.length) }
    }

    /** 폴더와 그 아래 기록을 지운다. 지운 게 있으면 true. */
    fun removeTree(path: String): Boolean = dirs.keys.removeIf { it == path || it.startsWith("$path/") }

    fun encode(): String = JSONObject().apply { dirs.forEach { (k, v) -> put(k, v) } }.toString()

    companion object {
        const val MAX = 5000

        fun parse(json: String?, clock: () -> Long = System::currentTimeMillis): CloudDirs {
            val d = CloudDirs(clock)
            val o = try { JSONObject(json ?: return d) } catch (e: Exception) { return d }
            for (k in o.keys()) {
                val ts = Xfer.jsonLong(o.opt(k)) ?: continue
                if (k.isNotEmpty() && CloudPath.isValid(k)) d.dirs[k] = ts
            }
            return d
        }
    }
}

/** 허브의 파일 하나(폴더 안 위치 dir, 이름, 크기, 수정 시각 ms). 저장소마다 id는 다르다(MediaStore _ID 또는 파일 경로). */
data class CloudEntry(val dir: String, val name: String, val size: Long, val mtime: Long, val id: String) {
    val path: String get() = CloudPath.join(dir, name)
}

/**
 * API 29+ 허브의 목록 만들기: MediaStore 파일들(위치는 RELATIVE_PATH에서 클라우드 폴더 뒤) + 기록된 빈 폴더.
 * 폴더 = 그 아래 파일이 있거나 기록된 경로. 폴더 크기는 0, 시각은 안의 가장 새 파일(없으면 기록 시각). 안드로이드 의존성 없음.
 */
object CloudTree {
    /** dir이 있는 폴더인지(맨 위는 늘 있다). */
    fun dirExists(files: List<CloudEntry>, dirs: CloudDirs, dir: String): Boolean =
        dir.isEmpty() || dirs.has(dir) || files.any { it.dir == dir || it.dir.startsWith("$dir/") }

    fun list(files: List<CloudEntry>, dirs: CloudDirs, dir: String): List<CloudItem> {
        val prefix = if (dir.isEmpty()) "" else "$dir/"
        val folders = HashMap<String, Long>()
        val items = ArrayList<CloudItem>()
        for (f in files) {
            if (f.dir == dir) {
                if (CloudPath.isListable(f.name)) items += CloudItem(f.name, false, f.size, f.mtime)
            } else if (f.dir.startsWith(prefix)) {
                val sub = f.dir.substring(prefix.length).substringBefore('/')
                folders[sub] = maxOf(folders[sub] ?: Long.MIN_VALUE, f.mtime)
            }
        }
        for (c in dirs.children(dir)) if (!folders.containsKey(c)) folders[c] = dirs.time(CloudPath.join(dir, c)) ?: 0L
        folders.forEach { (name, t) -> if (CloudPath.isListable(name)) items += CloudItem(name, true, 0, t) }
        return items
    }

    /** dir 아래(자신 포함 깊이 무관) 파일들. */
    fun under(files: List<CloudEntry>, dir: String): List<CloudEntry> =
        files.filter { it.dir == dir || it.dir.startsWith("$dir/") }

    /**
     * 폴더 지우기(A1): 지울 파일 = dir 아래에서 목록에 보이는 것(이름과 dir 아래 모든 폴더 조각이 보이는 이름)만.
     * 보이지 않는 것(`.part` 이름, `.part` 폴더 안 등)이 하나라도 남으면 left = true — 폴더는 지우지 않고 cloud_denied.
     */
    data class Removal(val delete: List<CloudEntry>, val left: Boolean)

    fun removal(files: List<CloudEntry>, dir: String): Removal {
        val del = ArrayList<CloudEntry>()
        var left = false
        for (f in under(files, dir)) {
            val below = f.dir.removePrefix(dir).removePrefix("/")
            val visible = CloudPath.isListable(f.name) && (below.isEmpty() || below.split('/').all { CloudPath.isListable(it) })
            if (visible) del += f else left = true
        }
        return Removal(del, left)
    }

    fun find(files: List<CloudEntry>, path: String): CloudEntry? {
        val dir = CloudPath.parent(path)
        val name = CloudPath.name(path)
        return files.firstOrNull { it.dir == dir && it.name == name && CloudPath.isListable(it.name) }
    }
}

/**
 * API 26–28 허브의 폴더 지우기(A1, java.nio만 써서 JVM 단위 테스트). 목록에 보이는 이름만 지운다 — 다른 PC가 받는 중인 `.part`,
 * `.nomedia` 같은 숨은 파일, 링크(따라가지도 지우지도 않음)는 남긴다. 다 비면 폴더도 지우고 true, 남은 게 있으면 폴더는 두고 false.
 */
object CloudFiles {
    fun deleteVisible(dir: java.nio.file.Path): Boolean {
        var left = false
        java.nio.file.Files.newDirectoryStream(dir).use { ds ->
            for (c in ds) {
                val a = try {
                    java.nio.file.Files.readAttributes(c, java.nio.file.attribute.BasicFileAttributes::class.java,
                        java.nio.file.LinkOption.NOFOLLOW_LINKS)
                } catch (e: java.io.IOException) { left = true; continue }
                when {
                    !CloudPath.isListable(c.fileName.toString()) || a.isSymbolicLink || a.isOther -> left = true
                    a.isDirectory -> if (!deleteVisible(c)) left = true
                    else -> try { java.nio.file.Files.delete(c) } catch (e: java.io.IOException) { left = true }
                }
            }
        }
        if (left) return false
        return try { java.nio.file.Files.delete(dir); true } catch (e: java.io.IOException) { false }
    }
}
