package com.choidev.tailhop

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class Pairing(val pcIp: String, val pcPort: Int, val pcName: String, val secret: ByteArray) {
    val confirmCode: String get() = Protocol.confirmCode(secret)
}

/**
 * 페어링 목록 저장(PC IP가 키). 각 비밀 S는 Android Keystore의 AES-256-GCM 키(내보내기 불가)로 감싸서만 저장한다.
 * 1.1 이하의 단일 페어링(prefs "ip"/"port"/"name"/"s")은 처음 읽을 때 목록으로 옮긴다(같은 감싸기 키라 재페어링 불필요).
 */
object Store {
    private const val PREFS = "pairing"
    private const val ALIAS = "tailhop_wrap_v1"
    private const val K_LIST = "list"
    private const val K_CUR = "current"

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun wrapKey(create: Boolean): SecretKey? {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        if (!create) return null
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return gen.generateKey()
    }

    @Volatile private var cached: List<Pairing>? = null

    /** 저장된 원본 목록(감싼 S 그대로). 레거시 단일 항목이 있으면 여기서 옮긴다. */
    private fun rawList(ctx: Context): JSONArray {
        val sp = prefs(ctx)
        val arr = try { JSONArray(sp.getString(K_LIST, "[]")) } catch (e: Exception) { JSONArray() }
        val legacyIp = sp.getString("ip", null)
        val legacyS = sp.getString("s", null)
        if (legacyIp != null && legacyS != null) {
            val exists = (0 until arr.length()).any { arr.getJSONObject(it).optString("ip") == legacyIp }
            if (!exists) arr.put(JSONObject().put("ip", legacyIp).put("port", sp.getInt("port", Protocol.PC_PORT))
                .put("name", sp.getString("name", "PC") ?: "PC").put("s", legacyS))
            // 목록을 먼저 쓰고 나서 레거시 키를 지운다(한 번의 commit).
            sp.edit().putString(K_LIST, arr.toString()).remove("ip").remove("port").remove("name").remove("s").commit()
        }
        return arr
    }

    private fun unwrap(o: JSONObject): Pairing? = try {
        val ip = o.getString("ip")
        val blob = Base64.getDecoder().decode(o.getString("s"))
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, wrapKey(false) ?: throw IllegalStateException(), GCMParameterSpec(128, blob, 0, 12))
        val secret = c.doFinal(blob, 12, blob.size - 12)
        if (secret.size != 32 || !Protocol.isTailscaleIp(ip)) null
        else Pairing(ip, o.optInt("port", Protocol.PC_PORT), o.optString("name", "PC").ifEmpty { "PC" }, secret)
    } catch (e: Exception) { null }

    /** 페어링된 모든 PC(페어링한 순서). */
    @Synchronized
    fun all(ctx: Context): List<Pairing> {
        cached?.let { return it }
        val arr = rawList(ctx)
        return (0 until arr.length()).mapNotNull { unwrap(arr.getJSONObject(it)) }.also { cached = it }
    }

    fun get(ctx: Context, ip: String): Pairing? = all(ctx).firstOrNull { it.pcIp == ip }

    /** 원격 IP 바이트로 페어링 찾기(수신 시, 아무것도 읽기 전에). */
    fun byAddress(ctx: Context, addr: ByteArray): Pairing? = all(ctx).firstOrNull {
        try { java.net.InetAddress.getByName(it.pcIp).address.contentEquals(addr) } catch (e: Exception) { false }
    }

    /**
     * 추가(같은 IP면 교체). 기본 공유 대상(현재 기기)은 makeCurrent이거나 아직 없을 때만 이 PC로 바꾼다
     * (새 기기가 말없이 공유 대상이 되지 않게).
     */
    @Synchronized
    fun save(ctx: Context, p: Pairing, makeCurrent: Boolean = false) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, wrapKey(true))
        val wrapped = c.iv + c.doFinal(p.secret)
        val arr = rawList(ctx)
        val out = JSONArray()
        var replaced = false
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("ip") == p.pcIp) {
                if (!replaced) out.put(entry(p, wrapped)); replaced = true
            } else out.put(o)
        }
        if (!replaced) out.put(entry(p, wrapped))
        val cur = prefs(ctx).getString(K_CUR, null)
        val curExists = cur != null && (0 until out.length()).any { out.getJSONObject(it).optString("ip") == cur }
        val edit = prefs(ctx).edit().putString(K_LIST, out.toString())
        if (makeCurrent || !curExists) edit.putString(K_CUR, p.pcIp)
        edit.commit()
        cached = null
    }

    private fun entry(p: Pairing, wrapped: ByteArray) = JSONObject().put("ip", p.pcIp).put("port", p.pcPort)
        .put("name", p.pcName).put("s", Base64.getEncoder().encodeToString(wrapped))

    /** 현재(마지막으로 연) 기기. 없으면 첫 기기. 공유 대상 등 기본 대상. */
    fun load(ctx: Context): Pairing? {
        val list = all(ctx)
        val cur = prefs(ctx).getString(K_CUR, null)
        return list.firstOrNull { it.pcIp == cur } ?: list.firstOrNull()
    }

    fun setCurrent(ctx: Context, ip: String) { prefs(ctx).edit().putString(K_CUR, ip).apply() }

    /** 레거시(기기 id 없는) 기록이 속하는 기기 = 첫 페어링. */
    fun firstIp(ctx: Context): String? = all(ctx).firstOrNull()?.pcIp

    fun isPaired(ctx: Context) = all(ctx).isNotEmpty()

    @Synchronized
    fun remove(ctx: Context, ip: String) {
        val arr = rawList(ctx)
        val out = JSONArray()
        for (i in 0 until arr.length()) arr.getJSONObject(i).let { if (it.optString("ip") != ip) out.put(it) }
        cached = null
        if (out.length() == 0) { clear(ctx); return }
        prefs(ctx).edit().putString(K_LIST, out.toString()).commit()
    }

    @Synchronized
    fun clear(ctx: Context) {
        cached = null
        prefs(ctx).edit().clear().commit()
        try { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) } catch (e: Exception) { }
    }
}
