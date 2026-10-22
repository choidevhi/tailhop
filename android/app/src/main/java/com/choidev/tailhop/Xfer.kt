package com.choidev.tailhop

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * 전송 신뢰성 확장(docs/PROTOCOL.md §11, 1.7.0)의 형식 검사·만들기. 안드로이드 의존성 없음(JVM 단위 테스트).
 * 선(wire) 형식은 §3 그대로이고 헤더에 `xfer`·`offset`을, 파일 끝에 SHA-256 트레일러 프레임을 더한다.
 */
object Xfer {
    /** 조각·끝난 xfer를 기억하는 시간이자 보내는 쪽이 포기하기까지의 시간(§11.2, §11.3). */
    const val KEEP_MS = 24L * 3600 * 1000

    /** 이 기기가 아는 확장(hello 응답의 caps). 클라우드 모드(§12)는 꺼져 있어도 안다 — 켜졌는지는 hello의 "cloud". */
    val CAPS: List<String> = listOf("xfer", "cloud")

    private val XFER_RE = Regex("[0-9a-f]{32}")
    private val SHA_RE = Regex("[0-9a-f]{64}")

    fun isValid(x: Any?): Boolean = x is String && XFER_RE.matches(x)

    /** 보내기 한 건의 xfer(32자 hex 난수). 다시 보낼 때는 같은 값을 쓴다. */
    fun newXfer(): String = Protocol.newId()

    /** 헤더 값이 JSON null이면 없는 것으로 본다(PC 구현과 같다). */
    fun field(h: JSONObject, name: String): Any? = if (h.isNull(name)) null else h.opt(name)

    /** JSON 정수(bool·실수 아님)만 Long으로. */
    fun jsonLong(v: Any?): Long? = (v as? Number)?.takeIf { v is Int || v is Long }?.toLong()

    /** file 헤더의 offset: 없으면 0(PC와 같다), 있으면 0 ≤ offset ≤ size 인 정수여야 한다. 어기면 bad_xfer. */
    fun offset(h: JSONObject, size: Long): Long {
        if (!h.has("offset")) return 0
        val o = jsonLong(h.opt("offset"))
        if (o == null || o < 0 || o > size) throw ProtocolException("bad_xfer")
        return o
    }

    fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    fun sha256(): MessageDigest = MessageDigest.getInstance("SHA-256")

    /** 트레일러 프레임 평문: `{"sha256":"<64자 hex>"}`. */
    fun trailer(digest: ByteArray): ByteArray =
        JSONObject().put("sha256", hex(digest)).toString().toByteArray(Charsets.UTF_8)

    /** 트레일러에서 SHA-256(소문자 64자 hex)을 읽는다. 형식이 틀리면 bad_xfer. */
    fun parseTrailer(raw: ByteArray): String {
        val sha = try { JSONObject(String(raw, Charsets.UTF_8)).opt("sha256") } catch (e: Exception) {
            throw ProtocolException("bad_xfer")
        }
        if (sha !is String || !SHA_RE.matches(sha)) throw ProtocolException("bad_xfer")
        return sha
    }

    /** hello 응답(§11.1). have는 끝난 xfer면 0. space는 이 폰의 앱 전용 저장소 용량(§19.1, 모르면 넣지 않는다). */
    fun helloReply(have: Long, done: Boolean, cloud: Boolean = false, space: DeviceSpace? = null): JSONObject {
        val o = JSONObject().put("ok", true).put("caps", JSONArray(CAPS)).put("cloud", cloud)
            .put("have", if (done) 0L else have).put("done", done)
        if (space != null) o.put("space", space.json())
        return o
    }

    /** 상대의 hello 응답(보내는 쪽). 모르는 caps 값은 그대로 두고 쓰는 쪽에서 무시한다. */
    /** update: 배포 PC(§13)가 알린 Android 업데이트(없거나 형식이 틀리면 null). store: 2.2 허브의 저장소 상태(§17.2, HubStore). */
    /**
     * 2.3.0(§19): space = 상대의 기기 용량(형식이 틀리면 null), sync = 허브의 동기화 대상 요약값(16자 hex, 대상이 없으면 null),
     * syncAll = 허브의 "전체 동기화"가 켜져 있는지("sync_all":true일 때만).
     * 2.3.1(§19.5): storeId = 허브 저장소 고유 번호(32자 소문자 hex, 없거나 형식이 틀리면 null).
     */
    data class Hello(val caps: Set<String>, val cloud: Boolean, val have: Long, val done: Boolean,
                     val update: UpdateOffer? = null, val store: String? = null, val strong: Boolean = false,
                     val space: DeviceSpace? = null, val sync: String? = null, val syncAll: Boolean = false,
                     val storeId: String? = null) {
        val xfer: Boolean get() = "xfer" in caps
    }

    fun parseHello(reply: JSONObject): Hello {
        val arr = reply.optJSONArray("caps")
        val caps = HashSet<String>()
        if (arr != null) for (i in 0 until arr.length()) (arr.opt(i) as? String)?.let { caps += it }
        return Hello(caps, reply.opt("cloud") == true, jsonLong(reply.opt("have")) ?: 0L, reply.opt("done") == true,
            Updates.fromHello(reply, caps), HubStore.parse(reply), reply.opt("strong") == true,
            DeviceSpace.parse(reply.opt("space")), SyncDigest.parse(reply.opt("sync")), reply.opt("sync_all") == true,
            MirrorIdentity.parse(reply.opt("store_id")))
    }

    /** 이어 보낼 위치: 상대가 알린 have가 0..size 밖이면 처음부터(0). */
    fun resumeAt(hello: Hello, size: Long): Long = if (hello.have in 0..size) hello.have else 0L
}
