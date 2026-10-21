package com.choidev.tailhop

import org.json.JSONArray
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
        dropTokens(pc)
    }

    /** 그 PC의 열쇠만 모두 버린다(허브 저장소가 다시 잠김, §18.3). 잠긴 폴더 자리는 남긴다(PIN을 다시 묻게). */
    @Synchronized fun dropTokens(pc: String) { tokens.keys.removeIf { it.startsWith("$pc|") } }

    /**
     * 항목이 from에서 to로 옮겨졌거나 이름이 바뀌었다(2.2.1, §18.1·§18.2): from이거나 그 아래인 잠긴 폴더 자리와 열쇠가 새 자리를 따라간다.
     * 허브는 옮긴 잠긴 폴더의 열쇠를 그대로 두므로(안은 그대로 잠김) 이 폰도 새 자리로 기억해야 다시 PIN을 묻지 않는다.
     */
    @Synchronized fun retarget(pc: String, from: String, to: String) {
        if (from.isEmpty() || to.isEmpty() || !CloudPath.isValid(to)) return
        val f = low(from)
        val m = mounts[pc] ?: return
        for ((k, shown) in m.entries.toList()) {
            if (k != f && !k.startsWith("$f/")) continue
            val moved = to + shown.substring(minOf(from.length, shown.length))
            m.remove(k)
            val t = tokens.remove("$pc|$k")
            if (!CloudPath.isValid(moved)) continue // 규칙을 넘는 깊이·길이가 됐다: 잊는다(다시 열면 PIN을 묻는다)
            m[low(moved)] = moved
            if (t != null) tokens[key(pc, moved)] = t
        }
    }

    companion object {
        /** 이 프로세스의 열쇠(화면·보내기 서비스가 같이 쓴다). */
        val shared = VaultTokens()
    }
}

/** cloud_move(§16) 결과: 옮긴 것(원래 경로 → 옮긴 곳의 이름)과 못 옮긴 것(원래 경로, 오류 코드). */
data class MoveResult(val moved: List<MoveResult.Moved>, val failed: List<MoveResult.Failed>) {
    data class Moved(val path: String, val name: String)
    data class Failed(val path: String, val code: String)

    operator fun plus(o: MoveResult) = MoveResult(moved + o.moved, failed + o.failed)

    companion object { val EMPTY = MoveResult(emptyList(), emptyList()) }
}

/**
 * 여러 항목 옮기기(§16, 2.1.0). 허브가 hello caps에 "move"를 알릴 때만 쓴다. 안드로이드 의존성 없음(JVM 단위 테스트).
 * 한 요청에 경로 1–MAX_PATHS개(헤더가 1 MiB 프레임을 넘지 않게) — 더 많으면 나눠 보내고 결과를 합친다(run).
 */
object CloudMove {
    const val MAX_PATHS = 200
    private val CODE_RE = Regex("[a-z_]{1,40}")

    /** 요청 필드. paths는 1–MAX_PATHS개의 빈 경로가 아닌 규칙(§12.1)에 맞는 경로, dest는 규칙에 맞는 폴더(맨 위 ""도 된다). */
    fun fields(paths: List<String>, dest: String): JSONObject {
        require(paths.size in 1..MAX_PATHS && paths.all { it.isNotEmpty() && CloudPath.isValid(it) } && CloudPath.isValid(dest))
        return JSONObject().put("paths", JSONArray(paths)).put("dest", dest)
    }

    /**
     * 응답. moved·failed 중 하나는 배열이어야 한다(둘 다 없으면 bad_reply). 형식이 틀린 항목(경로가 규칙에 맞지 않거나 비었음,
     * 이름이 보이는 이름이 아님, 코드가 코드 모양이 아님)은 버린다.
     */
    fun parse(reply: JSONObject): MoveResult {
        val movedArr = reply.opt("moved") as? JSONArray
        val failedArr = reply.opt("failed") as? JSONArray
        if (movedArr == null && failedArr == null) throw ProtocolException("bad_reply")
        val moved = ArrayList<MoveResult.Moved>()
        if (movedArr != null) for (i in 0 until movedArr.length()) {
            val o = movedArr.opt(i) as? JSONObject ?: continue
            val path = (o.opt("path") as? String)?.takeIf { it.isNotEmpty() && CloudPath.isValid(it) } ?: continue
            val name = (o.opt("name") as? String)?.takeIf { CloudPath.isListable(it) } ?: continue
            moved += MoveResult.Moved(path, name)
        }
        val failed = ArrayList<MoveResult.Failed>()
        if (failedArr != null) for (i in 0 until failedArr.length()) {
            val o = failedArr.opt(i) as? JSONObject ?: continue
            val path = (o.opt("path") as? String)?.takeIf { it.isNotEmpty() && CloudPath.isValid(it) } ?: continue
            val code = (o.opt("code") as? String)?.takeIf { CODE_RE.matches(it) } ?: continue
            failed += MoveResult.Failed(path, code)
        }
        return MoveResult(moved, failed)
    }

    /**
     * 경로들을 MAX_PATHS개씩 나눠 send로 보내고 결과를 합친다(같은 경로는 한 번만). 첫 묶음이 통째로 거부되면(dest 없음·
     * 잠긴 폴더 열쇠 없음 등) 그 예외를 그대로 던진다 — 아무것도 옮기지 않았다. 뒤 묶음이 통째로 거부되면 앞의 결과는 살리고
     * 그 묶음의 경로들을 그 코드로 failed에 넣는다.
     */
    fun run(paths: List<String>, send: (List<String>) -> MoveResult): MoveResult {
        var out = MoveResult.EMPTY
        paths.distinct().chunked(MAX_PATHS).forEachIndexed { i, chunk ->
            out += try { send(chunk) } catch (e: ProtocolException) {
                if (i == 0) throw e
                MoveResult(emptyList(), chunk.map { MoveResult.Failed(it, e.code) })
            }
        }
        return out
    }

    /** 못 옮긴 이유별 개수(많은 것부터, 같으면 코드 이름순) — 결과 알림에 쓴다. */
    fun failureCounts(r: MoveResult): List<Pair<String, Int>> =
        r.failed.groupingBy { it.code }.eachCount().entries.sortedWith(compareBy({ -it.value }, { it.key })).map { it.key to it.value }

    /** 폴더 dir이 옮길 항목 자신이거나 그 아래인지(그리로는 옮길 수 없다, bad_move). 허브처럼 대소문자를 가리지 않는다. */
    fun inside(dir: String, moving: Collection<String>): Boolean {
        val d = dir.lowercase(Locale.ROOT)
        return moving.any { m -> val l = m.lowercase(Locale.ROOT); d == l || d.startsWith("$l/") }
    }

    /** §18.1 "tokens"에 담는 열쇠는 16개까지. */
    const val MAX_TOKENS = 16

    /**
     * §18.1 출발 항목들의 열쇠: 각 경로가 든 잠긴 폴더의 열쇠(tokenFor) 가운데 도착 열쇠(destToken, "token"에 따로 감)를 뺀 것.
     * 겹치지 않게, 처음 나온 순서로 MAX_TOKENS개까지. 없으면 빈 목록(헤더에 넣지 않는다).
     */
    fun sourceTokens(paths: List<String>, destToken: String?, tokenFor: (String) -> String?): List<String> {
        val out = LinkedHashSet<String>()
        for (p in paths) {
            val t = tokenFor(p) ?: continue
            if (t != destToken) out += t
            if (out.size >= MAX_TOKENS) break
        }
        return out.toList()
    }

    /** 옮길 항목 하나: 경로, 그것이 있는 잠긴 폴더 자리(없으면 null), 그것 자체가 잠긴 폴더인지. */
    data class Source(val path: String, val mount: String?, val lockedDir: Boolean)

    /**
     * 화면이 "여기로 옮기기"를 막을지(허브가 vault_move로 거부할 것). destMount는 도착 폴더가 든 잠긴 폴더 자리(없으면 null).
     * - 잠긴 폴더 자체를 다른 잠긴 폴더 안에 넣기: 늘 막는다(§18.1에서도 vault_move).
     * - move2(2.2.1 허브): 그 밖에는 잠긴 폴더 안팎·사이로도 옮긴다(§18.1).
     * - 예전 허브: 잠긴 폴더 안팎을 오가면 막는다(§16). 잠긴 폴더 자체를 바깥 어디로 옮기기는 2.2 허브(store)만 된다(§17.5).
     */
    fun blocked(sources: List<Source>, destMount: String?, move2: Boolean, store: Boolean): Boolean {
        val dest = destMount?.lowercase(Locale.ROOT)
        return sources.any { s ->
            when {
                s.lockedDir -> dest != null || !(move2 || store)
                move2 -> false
                else -> s.mount?.lowercase(Locale.ROOT) != dest
            }
        }
    }
}

/** 클라우드 다중 선택(2.1.0)의 계산: 길게 눌러 끌기(Google 포토처럼)와 끝 가까이에서 저절로 내리기. */
object CloudSelect {
    /**
     * 끌기 중의 선택: 끌기 전에 고른 것(base) + 처음 누른 위치(anchor)부터 지금 손가락 아래(current)까지의 항목.
     * keys는 목록 위치별 키(항목이 아닌 줄은 null). 위치가 목록을 넘으면 끝까지로 본다.
     */
    fun range(base: Set<String>, keys: List<String?>, anchor: Int, current: Int): Set<String> {
        if (keys.isEmpty()) return base
        val a = anchor.coerceIn(0, keys.size - 1)
        val c = current.coerceIn(0, keys.size - 1)
        val out = LinkedHashSet(base)
        for (i in minOf(a, c)..maxOf(a, c)) keys[i]?.let { out += it }
        return out
    }

    /** 손가락이 목록 위·아래 끝 edge 픽셀 안이면 한 번에 내릴 픽셀(위는 음수). 끝에 가까울수록 빠르게, 최대 max. */
    fun autoScroll(y: Float, height: Int, edge: Float, max: Int): Int {
        if (height <= 0 || edge <= 0f) return 0
        val top = (edge - y) / edge
        val bottom = (y - (height - edge)) / edge
        return when {
            top > 0f -> -(max * top.coerceAtMost(1f)).toInt()
            bottom > 0f -> (max * bottom.coerceAtMost(1f)).toInt()
            else -> 0
        }
    }
}

/** 격자 칸 수: 화면 폭(dp)에 따라 2칸 이상, 칸 하나가 약 110dp. */
object CloudGrid {
    fun columns(widthDp: Int): Int = maxOf(2, widthDp / 110)

    /** 목록 끝 가까이(남은 줄이 margin 이하) 왔으면 다음 쪽을 읽는다. */
    fun nearEnd(lastVisible: Int, itemCount: Int, margin: Int): Boolean = lastVisible >= 0 && lastVisible >= itemCount - 1 - margin
}
