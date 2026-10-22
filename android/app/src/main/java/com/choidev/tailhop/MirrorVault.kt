package com.choidev.tailhop

import com.google.crypto.tink.Aead
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.aead.AeadConfig
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 잠긴 폴더를 잠긴 채로(docs/PROTOCOL.md §19.6, 2.3.1)의 암호 계산. 허브 PC(cloudkeys.py·cloudstore.py)와 같은 형식을
 * 검증된 구현으로만 푼다: 키 묶음·AEAD는 Google Tink, PIN·복구 코드의 열쇠 만들기는 BouncyCastle Argon2id(RFC 9106).
 * 안드로이드 의존성 없음(JVM 단위 테스트 — 테스트 벡터는 PC 코드로 만든 실제 봉인).
 */
object VaultCrypto {
    /** 허브와 같다: 5번 틀리면 60초, 그 뒤 틀릴 때마다 두 배, 최대 1시간. */
    const val MAX_FAILS = 5
    const val MIN_PIN = 6
    const val MAX_PIN = 64
    /** §17.1 KDF 값의 아래·위 한도(기록이 이보다 약하거나 터무니없이 크면 열지 않는다). */
    private const val MIN_T = 3
    private const val MIN_M = 262144
    private const val MAX_T = 10
    private const val MAX_M = 1024 * 1024
    private const val MAX_P = 16
    private const val CODE_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    /** Tink AES-GCM 키 묶음의 암호문 = 키 표시 5 + nonce 12 + 평문 + 태그 16(허브 OVERHEAD와 같다). */
    const val OVERHEAD = 33
    const val CHUNK = 1024 * 1024

    init { AeadConfig.register() }

    fun validPin(pin: String): Boolean = pin.codePointCount(0, pin.length) in MIN_PIN..MAX_PIN

    /** 복구 코드를 정식 모양으로(허브 normalize_code와 같다): 영숫자만 대문자로, O→0·I/L→1, 4글자씩 '-'. */
    fun normalizeCode(text: String): String {
        val up = text.uppercase(java.util.Locale.ROOT)
        val sb = StringBuilder()
        var i = 0
        while (i < up.length) {
            val cp = up.codePointAt(i)
            if (Character.isLetterOrDigit(cp)) sb.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        val clean = sb.toString().replace('O', '0').replace('I', '1').replace('L', '1')
        return clean.chunked(4).joinToString("-")
    }

    /** 복구 코드처럼 보이는지(Crockford base32 32글자). */
    fun looksLikeCode(text: String): Boolean = normalizeCode(text).replace("-", "").let { c -> c.length == 32 && c.all { it in CODE_ALPHABET } }

    /** 대기 시간(ms): count번째로 틀린 뒤. */
    fun waitMs(count: Int): Long = if (count < MAX_FAILS) 0 else minOf(3_600_000L, 60_000L shl minOf(count - MAX_FAILS, 10))

    fun nodeAd(row: String, parent: String, ring: String): ByteArray = "tailhop node|$row|$parent|$ring".toByteArray(Charsets.UTF_8)

    fun chunkAd(index: Long, last: Boolean): ByteArray =
        "tailhop chunk|".toByteArray(Charsets.US_ASCII) + ByteBuffer.allocate(8).putLong(index).array() + byteArrayOf(if (last) 1 else 0)

    /** 봉인(§17.1 seal_secret)의 KDF 값. 약하거나 모양이 틀리면 mirror_damaged. */
    class Seal(val t: Int, val m: Int, val p: Int, val salt: ByteArray, val blob: ByteArray)

    fun parseSeal(json: String): Seal {
        val o = try { JSONObject(json) } catch (e: Exception) { throw ProtocolException("mirror_damaged") }
        val t = Xfer.jsonLong(o.opt("t"))
        val m = Xfer.jsonLong(o.opt("m"))
        val p = Xfer.jsonLong(o.opt("p"))
        val salt = (o.opt("salt") as? String)?.let { Protocol.b64uDecode(it) }
        val blob = (o.opt("blob") as? String)?.let { Protocol.b64uDecode(it) }
        if (o.opt("alg") != "argon2id" || t == null || m == null || p == null || salt == null || blob == null) throw ProtocolException("mirror_damaged")
        if (t < MIN_T || t > MAX_T || m < MIN_M || m > MAX_M || p < 1 || p > MAX_P || salt.size < 16 || blob.isEmpty()) {
            throw ProtocolException("mirror_damaged")
        }
        return Seal(t.toInt(), m.toInt(), p.toInt(), salt, blob)
    }

    /** Argon2id(비밀, salt, t, m KiB, p, 32바이트) — 허브(cryptography Argon2id, 버전 0x13)와 같은 값. */
    fun derive(secret: String, s: Seal): ByteArray {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withIterations(s.t).withMemoryAsKB(s.m).withParallelism(s.p).withSalt(s.salt).build()
        val gen = Argon2BytesGenerator()
        gen.init(params)
        val out = ByteArray(32)
        val pw = secret.toByteArray(Charsets.UTF_8)
        try { gen.generateBytes(pw, out) } finally { pw.fill(0) }
        return out
    }

    /** 키 묶음을 감싼 AES-256-GCM(nonce 12 ‖ 암호문 — 허브 _KeyAead와 같다). 푸는 데만 쓴다. */
    private class KeyAead(private val key: ByteArray) : Aead {
        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray?): ByteArray = throw GeneralSecurityException("read only")

        override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray?): ByteArray {
            if (ciphertext.size < 12 + 16) throw GeneralSecurityException("short")
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, ciphertext, 0, 12))
            associatedData?.let { c.updateAAD(it) }
            return try { c.doFinal(ciphertext, 12, ciphertext.size - 12) } catch (e: Exception) { throw GeneralSecurityException("wrong key") }
        }
    }

    /**
     * 봉인을 비밀(PIN 또는 정식 모양 복구 코드)로 연다 → 그 잠긴 폴더의 Tink AEAD. 틀리면 null.
     * 봉인 값이 약하거나 손상이면 mirror_damaged. 메모리가 모자라면(Argon2id m KiB) vault_memory.
     */
    fun open(sealJson: String, secret: String, ad: String): Aead? {
        val s = parseSeal(sealJson)
        val k = try { derive(secret, s) } catch (e: OutOfMemoryError) { throw ProtocolException("vault_memory") }
        return try {
            val handle = TinkProtoKeysetFormat.parseEncryptedKeyset(s.blob, KeyAead(k), ad.toByteArray(Charsets.UTF_8))
            primitive(handle)
        } catch (e: GeneralSecurityException) { null } finally { k.fill(0) }
    }

    private fun primitive(h: KeysetHandle): Aead = h.getPrimitive(RegistryConfiguration.get(), Aead::class.java)

    /** 파일 키 묶음(meta의 k, base64url 평문 키 묶음 — 폴더 열쇠로 잠긴 meta 안에서만 왔다). */
    fun fileAead(k: ByteArray): Aead = try {
        primitive(TinkProtoKeysetFormat.parseKeyset(k, InsecureSecretKeyAccess.get()))
    } catch (e: GeneralSecurityException) { throw ProtocolException("mirror_damaged") }

    /** 청크 수(빈 파일도 하나). */
    fun chunks(size: Long): Long = if (size <= 0) 1 else (size + CHUNK - 1) / CHUNK

    /** 평문 size의 암호문 파일 길이. */
    fun rawLength(size: Long): Long = size + chunks(size) * OVERHEAD
}

/**
 * 잠긴 폴더 안 파일의 받은 그대로인 암호문(§17.1 청크: 1 MiB씩 Tink AEAD, AD = "tailhop chunk|" + 번호 8바이트 + 마지막 여부 1바이트)을
 * 원하는 위치부터 조각 하나씩 메모리로만 푼다. 길이가 평문 크기와 맞지 않거나 태그가 틀리면 mirror_damaged.
 */
class VaultReader(file: File, private val aead: Aead, override val size: Long) : MirrorReader() {
    private val raf = try { RandomAccessFile(file, "r") } catch (e: IOException) { throw ProtocolException("mirror_damaged") }
    private val count = VaultCrypto.chunks(size)
    private var cachedIndex = -1L
    private var cached = ByteArray(0)

    init {
        if (size < 0 || raf.length() != VaultCrypto.rawLength(size)) { raf.close(); throw ProtocolException("mirror_damaged") }
    }

    override fun read(pos: Long, out: ByteArray, off: Int, len: Int): Int {
        if (pos >= size) return -1
        if (len == 0) return 0
        val i = pos / VaultCrypto.CHUNK
        if (i != cachedIndex) {
            val last = i == count - 1
            val plainLen = if (last) (size - i * VaultCrypto.CHUNK).toInt() else VaultCrypto.CHUNK
            val sealed = ByteArray(plainLen + VaultCrypto.OVERHEAD)
            cached = try {
                raf.seek(i * (VaultCrypto.CHUNK + VaultCrypto.OVERHEAD))
                raf.readFully(sealed)
                aead.decrypt(sealed, VaultCrypto.chunkAd(i, last))
            } catch (e: Exception) { throw ProtocolException("mirror_damaged") }
            if (cached.size != plainLen) throw ProtocolException("mirror_damaged")
            cachedIndex = i
        }
        val at = (pos - i * VaultCrypto.CHUNK).toInt()
        val n = minOf(len, cached.size - at)
        System.arraycopy(cached, at, out, off, n)
        return n
    }

    override fun close() {
        try { raf.close() } catch (e: IOException) { }
        cached.fill(0)
    }
}
