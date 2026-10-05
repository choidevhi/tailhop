package com.choidev.tailhop

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 상대가 규칙을 어겼거나, 보내기·받기를 할 수 없는 사유.
 * code: 언어와 상관없는 고정 코드(문자열 리소스 err_<code>, PC의 err.<code>와 같은 이름). 화면 문장은 Errors.text로 만든다.
 * detail: 코드로 나타낼 수 없는 상대의 문장(예전 PC가 보낸 거부 이유 등).
 */
open class ProtocolException(val code: String, val detail: String? = null) : IOException(code)

/** GCM 태그 검사 실패. 받는 쪽은 응답 없이 바로 끊는다. */
class AuthException : ProtocolException("auth_failed")

/** TailHop 프로토콜 v1 (docs/PROTOCOL.md). 안드로이드 의존성 없음(JVM 단위 테스트 가능). */
object Protocol {
    val MAGIC = byteArrayOf(0x54, 0x48, 0x50, 0x31) // "THP1"
    const val KIND_PAIR = 0x01
    const val KIND_MSG = 0x02
    const val PREFIX_LEN = 22
    const val CHUNK = 1024 * 1024
    const val TAG = 16
    const val MAX_FRAME = CHUNK + TAG
    const val MAX_TEXT = CHUNK
    const val MAX_FILE = 4L * 1024 * 1024 * 1024
    const val MAX_SKEW_MS = 300_000L
    const val SOCKET_TIMEOUT_MS = 30_000
    const val PC_PORT = 47100
    const val PHONE_PORT = 47101

    val INFO_PHONE_TO_PC = "tailhop v1 phone->pc".toByteArray(Charsets.US_ASCII)
    val INFO_PC_TO_PHONE = "tailhop v1 pc->phone".toByteArray(Charsets.US_ASCII)
    val INFO_PAIR = "tailhop v1 pair".toByteArray(Charsets.US_ASCII)

    /** Windows 장치 이름(확장자·뒤 공백이 붙어도 장치). 위첨자 숫자와 0번 포함. Python `_RESERVED`와 같다. */
    private val RESERVED: Set<String> = setOf("CON", "PRN", "AUX", "NUL", "CONIN$", "CONOUT$") +
        listOf("COM", "LPT").flatMap { d -> ("0123456789".map { "$it" } + listOf("\u00b9", "\u00b2", "\u00b3")).map { d + it } }

    /** 화면에서 글자 순서를 바꾸거나 보이지 않는 문자(bidi 제어, 폭 없는 공백 등)와 C0·C1 제어문자. Python `_INVISIBLE`과 같다. */
    fun isInvisible(c: Int): Boolean =
        c < 0x20 || c in 0x7f..0x9f || c == 0x061c || c == 0x200b || c == 0x200e || c == 0x200f ||
            c in 0x202a..0x202e || c in 0x2060..0x2064 || c in 0x2066..0x206f || c == 0xfeff

    /** Windows 장치 이름인지: 첫 `.` 앞부분(뒤 공백 무시, 대소문자 무관). */
    fun isReservedName(name: String): Boolean =
        name.split('.')[0].trimEnd(' ').uppercase(java.util.Locale.ROOT) in RESERVED

    val random = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    fun b64uEncode(data: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(data)

    /** 패딩 없는 base64url만 받는다. 잘못되면 null. */
    fun b64uDecode(text: String): ByteArray? {
        if (!Regex("[A-Za-z0-9_-]*").matches(text) || text.length % 4 == 1) return null
        return try { Base64.getUrlDecoder().decode(text) } catch (e: IllegalArgumentException) { null }
    }

    fun hmac(key: ByteArray, msg: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(msg) }

    /** HKDF-SHA256, salt = 32바이트 0, L = 32. */
    fun deriveKey(ikm: ByteArray, info: ByteArray): ByteArray {
        val prk = hmac(ByteArray(32), ikm)
        return hmac(prk, info + byteArrayOf(1))
    }

    fun sessionKey(dirKey: ByteArray, salt: ByteArray): ByteArray = hmac(dirKey, salt)

    fun confirmCode(secret: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256").digest(secret)
        val v = ByteBuffer.wrap(d, 0, 4).int.toLong() and 0xffffffffL
        return "%06d".format(v % 1_000_000)
    }

    fun nonce(direction: Int, index: Long, last: Boolean): ByteArray {
        val n = ByteArray(12)
        n[0] = direction.toByte()
        var v = index
        for (i in 10 downTo 1) { n[i] = (v and 0xff).toByte(); v = v ushr 8 }
        n[11] = if (last) 1 else 0
        return n
    }

    fun prefix(kind: Int, salt: ByteArray): ByteArray {
        require(salt.size == 16)
        return MAGIC + byteArrayOf(kind.toByte()) + salt + byteArrayOf(0)
    }

    fun seal(key: ByteArray, nonce: ByteArray, plain: ByteArray, aad: ByteArray): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(aad); doFinal(plain)
        }

    /** 태그가 맞지 않으면 null. */
    fun open(key: ByteArray, nonce: ByteArray, sealed: ByteArray, aad: ByteArray): ByteArray? =
        try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
                updateAAD(aad); doFinal(sealed)
            }
        } catch (e: AEADBadTagException) { null }

    fun readExact(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        try { DataInputStream(input).readFully(buf) } catch (e: EOFException) {
            throw ProtocolException("disconnected")
        }
        return buf
    }

    /** 서버 쪽: 22바이트 머리를 읽고 검사한다. */
    fun readPrefix(input: InputStream): ByteArray {
        val p = readExact(input, PREFIX_LEN)
        if (!p.copyOfRange(0, 4).contentEquals(MAGIC) ||
            (p[4].toInt() != KIND_PAIR && p[4].toInt() != KIND_MSG) || p[21].toInt() != 0
        ) throw ProtocolException("not_tailhop")
        return p
    }

    fun openClient(input: InputStream, output: OutputStream, kind: Int, dirKey: ByteArray, salt: ByteArray = randomBytes(16)): Channel {
        val p = prefix(kind, salt)
        output.write(p); output.flush()
        return Channel(input, output, sessionKey(dirKey, salt), p, isClient = true)
    }

    fun openServer(input: InputStream, output: OutputStream, prefix: ByteArray, dirKey: ByteArray): Channel =
        Channel(input, output, sessionKey(dirKey, prefix.copyOfRange(5, 21)), prefix, isClient = false)

    fun newId(): String = randomBytes(16).joinToString("") { "%02x".format(it) }

    private val ID_RE = Regex("[0-9a-f]{32}")
    fun isValidId(id: Any?): Boolean = id is String && ID_RE.matches(id)

    /** docs §5 파일명 정리. Python 구현과 같은 결과(코드포인트 기준). */
    fun sanitizeFilename(input: String): String {
        var name = input.split('/', '\\').last()
        val sb = StringBuilder()
        name.codePoints().forEach { c ->
            if (isInvisible(c) || "<>:\"/\\|?*".indexOf(c.toChar()) >= 0 && c < 0x80) sb.append('_')
            else sb.appendCodePoint(c)
        }
        name = sb.toString().trimStart('.').trimEnd(' ', '.')
        if (name.codePointCount(0, name.length) > 150) {
            val dot = name.lastIndexOf('.')
            val ext = if (dot >= 0) name.substring(dot + 1) else ""
            val extLen = ext.codePointCount(0, ext.length)
            name = if (dot >= 0 && extLen in 1..16) {
                cpTake(name.substring(0, dot), 150 - extLen - 1) + "." + ext
            } else cpTake(name, 150)
            name = name.trimEnd(' ', '.')
        }
        if (name.isEmpty()) name = "file"
        if (isReservedName(name)) name = "_$name"
        return name
    }

    /**
     * 공유받은 content URI가 이 앱 자신의 provider를 가리키는지(authority 비교).
     * `content://0@<우리 authority>/…`처럼 사용자 번호(userinfo)를 앞에 붙여도 ContentResolver는 같은 provider로 보내므로
     * `@` 앞을 떼고 대소문자 없이 비교한다. authority가 없으면 true(거부).
     */
    fun isOwnAuthority(authority: String?, own: Collection<String>): Boolean {
        if (authority.isNullOrEmpty()) return true
        val bare = authority.substringAfterLast('@').lowercase(java.util.Locale.ROOT)
        return own.any { it.lowercase(java.util.Locale.ROOT) == bare }
    }

    private fun cpTake(s: String, n: Int): String =
        if (s.codePointCount(0, s.length) <= n) s else s.substring(0, s.offsetByCodePoints(0, maxOf(n, 0)))

    /** `이름 (1).확장자` 후보 (Python Path.stem/suffix 규칙). */
    fun numberedName(name: String, n: Int): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0 && dot < name.length - 1) "${name.substring(0, dot)} ($n)${name.substring(dot)}" else "$name ($n)"
    }

    data class PairInfo(val host: String, val port: Int, val name: String, val key: ByteArray)

    private val IPV4_RE = Regex("(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])(\\.(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])){3}")

    /** 100.64.0.0/10 (Tailscale CGNAT) IPv4 문자열인지. */
    fun isTailscaleIp(ip: String): Boolean {
        if (!IPV4_RE.matches(ip)) return false
        val o = ip.split('.').map { it.toInt() }
        return o[0] == 100 && o[1] in 64..127
    }

    fun isTailscaleIp(addr: ByteArray): Boolean =
        addr.size == 4 && (addr[0].toInt() and 0xff) == 100 && (addr[1].toInt() and 0xc0) == 0x40

    /** QR `tailhop://pair?v=1&h=..&p=..&n=..&k=..` 엄격 검사. 실패 시 ProtocolException. */
    fun parsePairUri(uri: String): PairInfo {
        val head = "tailhop://pair?"
        if (uri.length > 2048 || !uri.startsWith(head)) throw ProtocolException("qr_not_tailhop")
        val params = HashMap<String, String>()
        for (part in uri.substring(head.length).split('&')) {
            val eq = part.indexOf('=')
            if (eq <= 0) throw ProtocolException("qr_bad")
            val k = part.substring(0, eq)
            val v = try { URLDecoder.decode(part.substring(eq + 1), "UTF-8") } catch (e: Exception) {
                throw ProtocolException("qr_bad")
            }
            if (k !in setOf("v", "h", "p", "n", "k") || params.put(k, v) != null)
                throw ProtocolException("qr_bad")
        }
        if (params["v"] != "1") throw ProtocolException("qr_version")
        val h = params["h"] ?: throw ProtocolException("qr_no_host")
        if (!isTailscaleIp(h)) throw ProtocolException("not_ts_host")
        val ps = params["p"] ?: throw ProtocolException("qr_no_port")
        if (!Regex("[1-9][0-9]{0,4}").matches(ps) || ps.toInt() > 65535) throw ProtocolException("port_bad")
        val key = params["k"]?.let { b64uDecode(it) }
        if (key == null || key.size != 32) throw ProtocolException("qr_key_bad")
        val name = cleanDeviceName(params["n"] ?: "") .ifEmpty { "PC" }
        return PairInfo(h, ps.toInt(), name, key)
    }

    fun cleanDeviceName(s: String): String {
        val sb = StringBuilder()
        s.codePoints().forEach { if (!isInvisible(it) && !Character.isISOControl(it)) sb.appendCodePoint(it) }
        return cpTake(sb.toString().trim(), 64)
    }
}

/** 한 연결의 양방향 암호 프레임. 클라이언트는 dir 0으로 보내고 1로 받는다. */
class Channel(
    private val input: InputStream,
    private val output: OutputStream,
    private val key: ByteArray,
    private val prefix: ByteArray,
    isClient: Boolean,
) {
    private val sendDir = if (isClient) 0 else 1
    private val recvDir = if (isClient) 1 else 0
    private var sendIndex = 0L
    private var recvIndex = 0L
    private var recvDone = false

    fun sealFrame(plain: ByteArray, len: Int, last: Boolean): ByteArray {
        val p = if (len == plain.size) plain else plain.copyOf(len)
        return Protocol.seal(key, Protocol.nonce(sendDir, sendIndex++, last), p, prefix)
    }

    fun send(plain: ByteArray, last: Boolean, len: Int = plain.size) {
        val sealed = sealFrame(plain, len, last)
        val hdr = ByteBuffer.allocate(4).putInt(sealed.size).array()
        output.write(hdr); output.write(sealed); output.flush()
    }

    fun recv(): Pair<ByteArray, Boolean> {
        if (recvDone) throw ProtocolException("after_last")
        val len = ByteBuffer.wrap(Protocol.readExact(input, 4)).int
        if (len < Protocol.TAG || len > Protocol.MAX_FRAME) throw ProtocolException("bad_frame_len")
        val sealed = Protocol.readExact(input, len)
        for (last in booleanArrayOf(false, true)) {
            val plain = Protocol.open(key, Protocol.nonce(recvDir, recvIndex, last), sealed, prefix) ?: continue
            recvIndex++
            recvDone = last
            return plain to last
        }
        throw AuthException()
    }
}
