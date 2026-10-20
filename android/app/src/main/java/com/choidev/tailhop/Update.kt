package com.choidev.tailhop

import org.json.JSONObject
import java.security.MessageDigest

/** 배포 PC가 알린 Android 업데이트(hello의 updates.android, docs/PROTOCOL.md §13.1). */
data class UpdateOffer(val version: String, val code: Int, val size: Long)

/** update_get 첫 프레임(§13.2): 받을 APK의 버전·크기·SHA-256. */
data class UpdateHead(val version: String, val size: Long, val sha256: String)

/**
 * 업데이트(§13, 1.9.0)의 형식 검사. 안드로이드 의존성 없음(JVM 단위 테스트).
 * 이 폰은 업데이트를 받기만 한다(자기 caps에 "update"를 넣지 않는다).
 */
object Updates {
    /** APK 크기 상한. 4 GiB(§4 파일 상한)보다 훨씬 작게 — 받기 전에 캐시가 넘치지 않게. */
    const val MAX_APK = 512L * 1024 * 1024

    private val VERSION_RE = Regex("[0-9A-Za-z.+-]{1,32}")
    private val SHA_RE = Regex("[0-9a-f]{64}")

    /**
     * hello 응답의 Android 업데이트. caps에 "update"가 있고 updates.android가 형식에 맞을 때만(version 문자열, code 1 이상 정수,
     * size 1..MAX_APK 정수). 아니면 null — 업데이트를 모르는 PC이거나 배포 PC가 아니다.
     */
    fun fromHello(reply: JSONObject, caps: Set<String>): UpdateOffer? {
        if ("update" !in caps) return null
        val a = (reply.opt("updates") as? JSONObject)?.opt("android") as? JSONObject ?: return null
        val version = a.opt("version") as? String ?: return null
        val code = Xfer.jsonLong(a.opt("code")) ?: return null
        val size = Xfer.jsonLong(a.opt("size")) ?: return null
        if (!VERSION_RE.matches(version) || code !in 1..Int.MAX_VALUE || size !in 1..MAX_APK) return null
        return UpdateOffer(version, code.toInt(), size)
    }

    /** 지금 버전보다 높은지(§13.2: Android는 code 비교). */
    fun isNewer(offer: UpdateOffer?, currentCode: Int): Boolean = offer != null && offer.code > currentCode

    /** 여러 PC가 알린 것 중 가장 높은 code(같으면 먼저 온 것). 지금보다 높은 것이 없으면 null. */
    fun <K> best(offers: List<Pair<K, UpdateOffer>>, currentCode: Int): Pair<K, UpdateOffer>? =
        offers.filter { isNewer(it.second, currentCode) }.maxByOrNull { it.second.code }

    /**
     * update_get 첫 프레임(§13.2, cloud_get과 같은 모양). 거부는 last=1 한 프레임(CloudWire.First.Refused로 돌려준다).
     * ok인데 last=1이거나 version·size·sha256이 형식에 맞지 않으면 bad_reply.
     */
    sealed class First {
        data class Ok(val head: UpdateHead) : First()
        data class Refused(val reply: JSONObject) : First()
    }

    fun parseFirst(raw: ByteArray, last: Boolean): First {
        val o = try { JSONObject(String(raw, Charsets.UTF_8)) } catch (e: Exception) {
            throw ProtocolException("unreadable_reply")
        }
        if (o.opt("ok") != true) {
            if (!last) throw ProtocolException("bad_reply")
            return First.Refused(o)
        }
        if (last) throw ProtocolException("bad_reply")
        val version = o.opt("version") as? String
        val size = Xfer.jsonLong(o.opt("size"))
        val sha = o.opt("sha256") as? String
        if (version == null || !VERSION_RE.matches(version) || size == null || size !in 1..MAX_APK || sha == null || !SHA_RE.matches(sha))
            throw ProtocolException("bad_reply")
        return First.Ok(UpdateHead(version, size, sha))
    }

    /** 다 받은 파일의 SHA-256이 첫 프레임과 같은지. 다르면 hash_mismatch(받은 파일을 버린다). */
    fun checkSha(head: UpdateHead, md: MessageDigest) {
        val got = Xfer.hex(md.digest())
        if (!MessageDigest.isEqual(got.toByteArray(), head.sha256.toByteArray())) throw ProtocolException("hash_mismatch")
    }

    fun certDigest(cert: ByteArray): String = Xfer.hex(Xfer.sha256().digest(cert))

    /**
     * 받은 APK의 서명 인증서(SHA-256 목록)가 지금 앱과 같은지. 둘 다 비어 있지 않고 집합이 같아야 한다(여러 서명자면 모두).
     * 다르면 설치하지 않는다(update_signer).
     */
    fun sameSigner(installed: List<String>, apk: List<String>): Boolean =
        installed.isNotEmpty() && apk.isNotEmpty() && installed.toSet() == apk.toSet()
}
