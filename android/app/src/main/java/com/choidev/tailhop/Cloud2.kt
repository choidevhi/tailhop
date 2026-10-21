package com.choidev.tailhop

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * 파일 종류(docs/PROTOCOL.md §15, 2.0.0): 확장자로 정한다. 표는 docs/test_vectors.json의 `file_kinds`와 같아야 한다(PC와 같은 표).
 * 확장자가 없거나 표에 없으면 other. 확장자는 마지막 `.` 뒤(Python Path.suffix와 같게: 이름이 `.`으로 시작하거나 끝나면 없음).
 */
object FileKinds {
    const val ALL = "all"
    const val IMAGE = "image"
    const val VIDEO = "video"
    const val AUDIO = "audio"
    const val DOC = "doc"
    const val ARCHIVE = "archive"
    const val OTHER = "other"

    /** 검색 종류 고르기 순서(전체·사진·영상·음악·문서·압축·기타). */
    val FILTERS = listOf(ALL, IMAGE, VIDEO, AUDIO, DOC, ARCHIVE, OTHER)

    val TABLE: Map<String, List<String>> = linkedMapOf(
        IMAGE to listOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "tif", "tiff", "avif", "svg", "raw", "dng",
            "cr2", "nef", "arw"),
        VIDEO to listOf("mp4", "mov", "mkv", "avi", "webm", "m4v", "wmv", "flv", "3gp", "mts", "m2ts", "ts"),
        AUDIO to listOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma", "aiff", "alac"),
        DOC to listOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "hwp", "hwpx", "txt", "md", "rtf", "csv", "odt", "ods",
            "odp", "pages", "numbers", "key", "epub"),
        ARCHIVE to listOf("zip", "7z", "rar", "tar", "gz", "tgz", "bz2", "xz", "zst", "apk", "exe", "msi", "iso", "dmg"),
    )

    private val BY_EXT: Map<String, String> = HashMap<String, String>().also { m ->
        TABLE.forEach { (kind, exts) -> exts.forEach { m[it] = kind } }
    }

    val KINDS: Set<String> = TABLE.keys + OTHER

    fun of(name: String): String {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return OTHER
        return BY_EXT[name.substring(dot + 1).lowercase(Locale.ROOT)] ?: OTHER
    }

    /** 미리보기(cloud_thumb·휴대폰 썸네일)를 물어볼 종류. 나머지는 벡터 아이콘만 보인다. */
    fun previewable(kind: String): Boolean = kind == IMAGE || kind == VIDEO
}

/** cloud_search 응답 한 쪽(§15): 목록과 같은 모양 + 허브가 다 훑지 못했는지(partial). */
data class SearchPage(val page: CloudPage, val partial: Boolean)

object CloudSearch {
    const val MAX_QUERY = 100

    /** 요청 필드. query는 0–100자(코드포인트), kind는 FileKinds.FILTERS 중 하나. 어기면 IllegalArgumentException(화면이 막는다). */
    fun fields(path: String, query: String, kind: String, cursor: Int): JSONObject {
        require(CloudPath.isValid(path) && query.codePointCount(0, query.length) <= MAX_QUERY && kind in FileKinds.FILTERS && cursor >= 0)
        val o = JSONObject().put("path", path).put("query", query).put("kind", kind)
        if (cursor > 0) o.put("cursor", cursor)
        return o
    }

    /** 응답: 항목마다 path(검색 시작 폴더 기준, 마지막 조각 = name)가 있어야 한다. 없거나 틀린 항목은 버린다. */
    fun parse(reply: JSONObject): SearchPage = SearchPage(CloudListing.parse(reply, requirePath = true), reply.opt("partial") == true)
}

/** cloud_thumb 첫 프레임(§15): JPEG 미리보기의 정보. */
data class ThumbHead(val mime: String, val w: Int, val h: Int)

object CloudThumb {
    const val MIN_PX = 64
    const val MAX_PX = 512
    const val MAX_BYTES = 256 * 1024
    /** 격자 칸·목록 줄에 쓸 크기(허브가 긴 변을 이 픽셀로 맞춘다). */
    const val GRID_PX = 256
    const val LIST_PX = 96

    fun px(want: Int): Int = want.coerceIn(MIN_PX, MAX_PX)

    sealed class First {
        data class Ok(val head: ThumbHead) : First()
        data class Refused(val reply: JSONObject) : First()
    }

    /** 첫 프레임. ok면 last=0이고 mime은 image/…, w·h는 1–4096 정수여야 한다. 거부는 last=1 한 프레임. */
    fun parseFirst(raw: ByteArray, last: Boolean): First {
        val o = try { JSONObject(String(raw, Charsets.UTF_8)) } catch (e: Exception) {
            throw ProtocolException("unreadable_reply")
        }
        if (o.opt("ok") != true) {
            if (!last) throw ProtocolException("bad_reply")
            return First.Refused(o)
        }
        if (last) throw ProtocolException("bad_reply")
        val mime = o.opt("mime") as? String
        val w = Xfer.jsonLong(o.opt("w"))
        val h = Xfer.jsonLong(o.opt("h"))
        if (mime == null || !mime.startsWith("image/") || w == null || h == null || w !in 1..4096 || h !in 1..4096)
            throw ProtocolException("bad_reply")
        return First.Ok(ThumbHead(mime, w.toInt(), h.toInt()))
    }

    /** 데이터 프레임을 last=1까지 모은다. 합이 0이거나 256 KiB를 넘으면 bad_reply. */
    fun readData(ch: Channel, onFrame: (() -> Unit)? = null): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val (data, last) = ch.recv()
            if (out.size() + data.size > MAX_BYTES) throw ProtocolException("bad_reply")
            out.write(data)
            onFrame?.invoke()
            if (last) break
        }
        if (out.size() == 0) throw ProtocolException("bad_reply")
        return out.toByteArray()
    }
}

/**
 * 잠긴 폴더(§14) 요청·응답의 형식. PIN 확인·암호화는 모두 허브가 한다(이 폰은 PIN을 암호 채널로 보내기만 한다).
 */
object Vault {
    const val MIN_PIN = 6
    const val MAX_PIN = 64
    private val TOKEN_RE = Regex("[0-9a-f]{32}")

    /** 새 PIN(잠그기·PIN 바꾸기): 6–64자(코드포인트), 제어문자 없음. 앞뒤 공백도 PIN의 일부다(자르지 않는다). */
    fun pinOk(pin: String): Boolean {
        val n = pin.codePointCount(0, pin.length)
        return n in MIN_PIN..MAX_PIN && pin.codePoints().noneMatch { Character.isISOControl(it) }
    }

    /** 지금 PIN(열기·잠금 풀기): 비어 있지 않고 64자 이하면 보낸다 — 맞는지는 허브가 본다(길이 규칙이 바뀌어도 열 수 있게). */
    fun pinEntered(pin: String): Boolean = pin.isNotEmpty() && pin.codePointCount(0, pin.length) <= MAX_PIN

    /**
     * 사용자가 적은 복구 코드: 앞뒤 공백만 떼고 그대로 보낸다. 허브가 영숫자만 남기고 대문자로 바꾼 뒤 O→0, I/L→1로 읽는다(§14.1).
     * 비었거나 100자를 넘거나 제어문자가 있으면 null.
     */
    fun normalizeRecovery(input: String): String? {
        val s = input.trim()
        return s.takeIf { it.isNotEmpty() && it.length <= 100 && it.none { c -> Character.isISOControl(c) } }
    }

    /** vault_locked 거부의 mount(어느 잠긴 폴더의 PIN을 물을지, §14.2). 규칙에 맞는 경로가 아니면 null. */
    fun lockedMount(reply: JSONObject?): String? =
        (reply?.opt("mount") as? String)?.takeIf { it.isNotEmpty() && CloudPath.isValid(it) }

    fun parseToken(reply: JSONObject): String {
        val t = reply.opt("token") as? String
        if (t == null || !TOKEN_RE.matches(t)) throw ProtocolException("bad_reply")
        return t
    }

    /**
     * cloud_lock 응답의 복구 코드. 한 번만 받을 수 있으므로 형식이 조금 달라도 버리지 않는다(제어문자 없는 1–200자면 그대로 보여 준다).
     */
    fun parseRecovery(reply: JSONObject): String {
        val r = (reply.opt("recovery") as? String)?.trim()
        if (r.isNullOrEmpty() || r.length > 200 || r.any { Character.isISOControl(it) }) throw ProtocolException("bad_reply")
        return r
    }

    /** 보여 줄 모양: `-`로 나뉜 묶음을 네 개마다 줄을 바꾼다(32글자 코드가 묶음 가운데서 끊기지 않게). 복사는 원래 코드로. */
    fun displayRecovery(code: String): String {
        val groups = code.split('-')
        if (groups.size < 5) return code
        return groups.chunked(4).joinToString("-\n") { it.joinToString("-") }
    }

    /** vault_wait 거부의 retry(초). 없거나 틀리면 null. */
    fun retrySeconds(reply: JSONObject?): Int? = Xfer.jsonLong(reply?.opt("retry"))?.takeIf { it in 0..86_400 }?.toInt()
}

/**
 * 잠긴 폴더의 열쇠(§14.2): (PC, 잠긴 폴더 자리)마다 token을 이 프로세스가 사는 동안만 메모리에 둔다(디스크에 쓰지 않는다).
 * 목록에서 본 잠긴 폴더 자리(mount)도 기억해 그 안을 가리키는 요청에 token을 붙인다. 안드로이드 의존성 없음(JVM 단위 테스트).
 */
class VaultTokens {
    private val tokens = HashMap<String, String>()
    /** PC → (소문자 자리 → 처음 본 모양). 허브는 잠긴 폴더 자리를 대소문자 없이 비교한다(§14.2) — 여기서도 같게. */
    private val mounts = HashMap<String, HashMap<String, String>>()

    private fun low(path: String) = path.lowercase(Locale.ROOT)
    private fun key(pc: String, mount: String) = "$pc|${low(mount)}"

    /** 목록·검색·vault_locked의 mount에서 본 잠긴 폴더 자리. */
    @Synchronized fun learn(pc: String, mount: String) {
        if (mount.isNotEmpty() && CloudPath.isValid(mount)) mounts.getOrPut(pc) { HashMap() }.putIfAbsent(low(mount), mount)
    }

    @Synchronized fun put(pc: String, mount: String, token: String) {
        learn(pc, mount)
        tokens[key(pc, mount)] = token
    }

    @Synchronized fun drop(pc: String, mount: String) { tokens.remove(key(pc, mount)) }

    @Synchronized fun has(pc: String, mount: String): Boolean = tokens.containsKey(key(pc, mount))

    /** path가 들어 있는(같거나 아래, 대소문자 무관) 잠긴 폴더 자리 중 가장 깊은 것(처음 본 모양으로). 모르면 null. */
    @Synchronized fun mountOf(pc: String, path: String): String? {
        val p = low(path)
        return mounts[pc]?.entries?.filter { p == it.key || p.startsWith(it.key + "/") }?.maxByOrNull { it.key.length }?.value
    }

    /** path 안을 가리키는 요청에 붙일 token. */
    @Synchronized fun tokenFor(pc: String, path: String): String? = mountOf(pc, path)?.let { tokens[key(pc, it)] }

    /** 그 폴더가 잠금이 풀렸거나 지워졌다(그 아래 기억도 버린다). */
    @Synchronized fun forgetMount(pc: String, mount: String) {
        val m = low(mount)
        mounts[pc]?.keys?.removeIf { it == m || it.startsWith("$m/") }
        tokens.keys.removeIf { it == "$pc|$m" || it.startsWith("$pc|$m/") }
    }

    @Synchronized fun forgetPc(pc: String) {
        mounts.remove(pc)
        tokens.keys.removeIf { it.startsWith("$pc|") }
    }

    companion object {
        /** 이 프로세스의 열쇠(화면·보내기 서비스가 같이 쓴다). */
        val shared = VaultTokens()
    }
}

/** 격자 칸 수: 화면 폭(dp)에 따라 2칸 이상, 칸 하나가 약 110dp. */
object CloudGrid {
    fun columns(widthDp: Int): Int = maxOf(2, widthDp / 110)

    /** 목록 끝 가까이(남은 줄이 margin 이하) 왔으면 다음 쪽을 읽는다. */
    fun nearEnd(lastVisible: Int, itemCount: Int, margin: Int): Boolean = lastVisible >= 0 && lastVisible >= itemCount - 1 - margin
}
