package com.choidev.tailhop

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.CopyOnWriteArraySet
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 인증이 필요 없는 Android Keystore AES-256-GCM 열쇠(내보내기 불가, 가능하면 StrongBox — 없으면 TEE). 사본의 장부(§19.3 2.3.1)를
 * 잠근다: 화면이 잠겨 있어도 뒤에서 맞추기(WorkManager)가 이어 받게. 잠근 모양은 AesSealKey와 같다(iv 12 ‖ 암호문).
 */
class KeystoreAes(private val alias: String) : SealKey {
    @Volatile private var cached: SecretKey? = null

    @Synchronized private fun key(): SecretKey {
        cached?.let { return it }
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { cached = it; return it }
        fun spec(strong: Boolean) = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .apply { if (strong && Build.VERSION.SDK_INT >= 28) setIsStrongBoxBacked(true) }
            .build()
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        // StrongBox가 없거나(StrongBoxUnavailableException) 그 열쇠를 못 만들면 TEE 열쇠로
        val k = try { gen.init(spec(true)); gen.generateKey() } catch (e: Exception) { gen.init(spec(false)); gen.generateKey() }
        cached = k
        return k
    }

    override fun seal(plain: ByteArray, aad: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        c.updateAAD(aad)
        val body = c.doFinal(plain)
        val iv = c.iv
        if (iv.size != 12) throw ProtocolException("mirror_damaged")
        return iv + body
    }

    override fun open(sealed: ByteArray, aad: ByteArray): ByteArray {
        if (sealed.size < 12 + 16) throw ProtocolException("mirror_damaged")
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, 12))
            c.updateAAD(aad)
            c.doFinal(sealed, 12, sealed.size - 12)
        } catch (e: AEADBadTagException) { throw ProtocolException("mirror_damaged") } catch (e: ProtocolException) { throw e } catch (e: Exception) {
            throw ProtocolException("mirror_damaged") // 열쇠가 없어짐 등
        }
    }

    @Synchronized fun delete() {
        cached = null
        deleteAlias(alias)
    }

    companion object {
        fun deleteAlias(alias: String) {
            try { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias) } catch (e: Exception) { }
        }
    }
}

/**
 * 이 폰의 사본 열쇠(§19.3 2.3.1).
 * - 기기 키 쌍: Keystore RSA 3072, OAEP(SHA-256, MGF1 SHA-1), 내보내기 불가, 가능하면 StrongBox(없거나 3072를 못 만들면 TEE).
 *   개인키는 강한 생체(지문 등) 또는 화면 잠금(PIN·패턴·비밀번호)으로 인증한 뒤 5분 동안만 쓸 수 있고, 시도 횟수는 하드웨어·시스템이 막는다.
 *   공개키 감싸기는 인증이 필요 없다(소프트웨어 사본으로 계산).
 * - 장부 열쇠: 인증 없는 Keystore AES(KeystoreAes).
 * - 지문 등록 감시(canary): 시간 인증(5분) 키는 Android가 "새 지문 등록으로 무효"를 적용하지 않으므로(setInvalidatedByBiometricEnrollment는
 *   매번 인증하는 키에만 듣는다), 매번 생체 인증하는 작은 AES 키를 하나 더 두고 그것이 무효가 되면(새 지문 등록) 기기 키도 무효로 본다.
 *   처음 만들 때 등록된 지문이 없었으면(만들 수 없음) 나중에 그 키를 만들 수 있게 된 것 = 그 사이 지문을 등록한 것으로 본다.
 * app에는 applicationContext만 둔다(화면 Context가 아니라 새지 않는다).
 */
@SuppressLint("StaticFieldLeak")
object DeviceMirrorKeys : MirrorKeys {
    private const val RSA = "tailhop_mirror_device_v2"
    private const val CANARY = "tailhop_mirror_canary_v2"
    private const val LEDGER = "tailhop_mirror_ledger_v2"
    /** 2.3.0의 사본 열쇠(인증 없음). 옮길 때 지운다. */
    const val OLD_ALIAS = "tailhop_mirror_v1"
    /** 인증 뒤 개인키를 쓸 수 있는 시간(초). */
    const val AUTH_SECONDS = 300
    private const val PREFS = "mirror_keys"
    private const val K_CANARY = "canary"
    private const val CANARY_SET = "set"
    private const val CANARY_NONE = "none"

    enum class State {
        /** 기기 키 쌍이 없다. */
        NONE,
        /** 있지만 지금은 인증이 필요하다. */
        LOCKED,
        /** 인증한 지 5분이 안 되었다(Keystore가 개인키를 쓰게 한다). */
        OPEN,
        /** 영구히 무효(새 지문 등록·화면 잠금을 없앰): 사본을 지우고 새 열쇠로. */
        INVALID,
    }

    @Volatile private var app: Context? = null
    @Volatile private var pub: PublicKey? = null
    private val ledgerKey = KeystoreAes(LEDGER)

    fun init(ctx: Context) { if (app == null) app = ctx.applicationContext }

    override val ledger: SealKey get() = ledgerKey

    private fun ks(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun prefs() = app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 공개키(소프트웨어 사본 — 인증 없이 감싼다). 키 쌍이 없으면 mirror_setup. */
    private fun publicKey(): PublicKey {
        pub?.let { return it }
        val cert = try { ks().getCertificate(RSA) } catch (e: Exception) { null } ?: throw ProtocolException("mirror_setup")
        val k = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(cert.publicKey.encoded))
        pub = k
        return k
    }

    override fun wrap(key: ByteArray): ByteArray = RsaOaep.encrypt(publicKey(), key)

    /** 개인키로 푼다. 이 앱에서 지문·화면 잠금으로 열지 않았거나(MirrorLock) 5분이 지났으면 mirror_locked. */
    override fun unwrap(wrapped: ByteArray): ByteArray {
        if (!MirrorLock.isOpen) throw ProtocolException("mirror_locked")
        val priv = try { ks().getKey(RSA, null) as? PrivateKey } catch (e: Exception) { null } ?: throw ProtocolException("mirror_locked")
        val c = Cipher.getInstance(RsaOaep.TRANSFORM)
        try { c.init(Cipher.DECRYPT_MODE, priv, RsaOaep.SPEC) } catch (e: Exception) {
            throw ProtocolException("mirror_locked") // 인증이 지남(UserNotAuthenticated)·무효(다음 열기에서 state가 알아본다)
        }
        return try { c.doFinal(wrapped) } catch (e: Exception) {
            // 하는 사이 5분이 지났으면 잠김, 아니면 감싼 것이 손상
            if (state() == State.OPEN) throw ProtocolException("mirror_damaged") else throw ProtocolException("mirror_locked")
        }
    }

    /** 지금 상태(작업 스레드에서 — Keystore를 부른다). 인증을 묻지 않는다. */
    fun state(): State {
        val ks = try { ks() } catch (e: Exception) { return State.NONE }
        val priv = try { ks.getKey(RSA, null) as? PrivateKey } catch (e: Exception) { null } ?: return State.NONE
        val usable = try {
            Cipher.getInstance(RsaOaep.TRANSFORM).init(Cipher.DECRYPT_MODE, priv, RsaOaep.SPEC)
            true
        } catch (e: KeyPermanentlyInvalidatedException) { return State.INVALID } catch (e: UserNotAuthenticatedException) { false } catch (e: Exception) { false }
        if (enrollmentChanged(ks)) return State.INVALID
        return if (usable) State.OPEN else State.LOCKED
    }

    /** 지문 등록이 바뀌었는지(canary). */
    private fun enrollmentChanged(ks: KeyStore): Boolean {
        val k = try { ks.getKey(CANARY, null) as? SecretKey } catch (e: Exception) { null }
        if (k != null) {
            return try {
                Cipher.getInstance("AES/GCM/NoPadding").init(Cipher.ENCRYPT_MODE, k)
                false
            } catch (e: KeyPermanentlyInvalidatedException) { true } catch (e: Exception) { false }
        }
        val sp = prefs() ?: return false
        if (sp.getString(K_CANARY, null) != CANARY_NONE) {
            // 표시가 "있음"인데 열쇠가 없다(지워짐): 다시 만들어 둔다(이 일로 사본을 지우지 않는다)
            sp.edit().putString(K_CANARY, if (makeCanary()) CANARY_SET else CANARY_NONE).apply()
            return false
        }
        // 처음에는 지문이 없어 못 만들었다: 지금 만들 수 있으면 그 사이 지문을 등록했다
        if (!makeCanary()) return false
        KeystoreAes.deleteAlias(CANARY)
        return true
    }

    /** 매번 강한 생체 인증하는 작은 키(지문을 새로 등록하면 무효). 등록된 지문이 없으면 만들 수 없다(false). */
    private fun makeCanary(): Boolean = try {
        val b = KeyGenParameterSpec.Builder(CANARY, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= 30) b.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(b.build()) }.generateKey()
        true
    } catch (e: Exception) { false }

    /** 기기 키 쌍을 만든다(없으면). 화면 잠금이 없거나 만들 수 없으면 mirror_setup. 처음 만들면 몇 초 걸릴 수 있다(작업 스레드에서). */
    @Synchronized fun ensure() {
        val ks = try { ks() } catch (e: Exception) { throw ProtocolException("mirror_setup") }
        if (ks.containsAlias(RSA)) return
        val km = app?.getSystemService(KeyguardManager::class.java)
        if (km != null && !km.isDeviceSecure) throw ProtocolException("mirror_setup")
        fun spec(strong: Boolean): KeyGenParameterSpec {
            val b = KeyGenParameterSpec.Builder(RSA, KeyProperties.PURPOSE_DECRYPT or KeyProperties.PURPOSE_ENCRYPT)
                .setKeySize(3072)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(true)
            if (Build.VERSION.SDK_INT >= 30) {
                b.setUserAuthenticationParameters(AUTH_SECONDS, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
            } else {
                @Suppress("DEPRECATION")
                b.setUserAuthenticationValidityDurationSeconds(AUTH_SECONDS)
            }
            if (strong && Build.VERSION.SDK_INT >= 28) b.setIsStrongBoxBacked(true)
            return b.build()
        }
        val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
        try {
            // StrongBox는 RSA 3072를 못 만드는 기기가 많다(StrongBoxUnavailableException·ProviderException): 그때 TEE로
            try { gen.initialize(spec(true)); gen.generateKeyPair() } catch (e: Exception) { gen.initialize(spec(false)); gen.generateKeyPair() }
        } catch (e: Exception) {
            KeystoreAes.deleteAlias(RSA)
            throw ProtocolException("mirror_setup")
        }
        pub = null
        KeystoreAes.deleteAlias(CANARY)
        prefs()?.edit()?.putString(K_CANARY, if (makeCanary()) CANARY_SET else CANARY_NONE)?.apply()
    }

    /** 모든 사본 열쇠를 지운다(사본을 모두 지운 뒤 — 무효가 됨). */
    @Synchronized fun reset() {
        pub = null
        KeystoreAes.deleteAlias(RSA)
        KeystoreAes.deleteAlias(CANARY)
        ledgerKey.delete()
        prefs()?.edit()?.remove(K_CANARY)?.apply()
    }
}

/**
 * 사본이 열려 있는지(이 프로세스의 메모리에만). 지문·화면 잠금으로 연 뒤에만 열리고(MirrorUnlock), 화면이 꺼지거나 앱이 5분 넘게
 * 뒤로 가 있으면 다시 잠근다 — 푼 목록·열쇠를 메모리에서 지운다. Keystore의 5분 인증 시간과 따로 지킨다.
 */
object MirrorLock {
    /** 앱이 뒤로 간 뒤 잠그기까지(인증 시간과 같다). */
    const val AWAY_MS = DeviceMirrorKeys.AUTH_SECONDS * 1000L

    @Volatile var isOpen = false
        private set

    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val ui = Handler(Looper.getMainLooper())
    private val lockLater = Runnable { lock() }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    fun opened() { isOpen = true }

    /** 바로 잠근다. 메모리 정리는 작업 스레드에서(맞추기가 사본을 쥐고 있어도 화면을 막지 않게). */
    fun lock() {
        val was = isOpen
        isOpen = false
        Thread({ Mirror.lockAll() }, "tailhop-mirror-lock").start()
        if (was) listeners.forEach { try { it() } catch (e: Exception) { } }
    }

    /** 모든 화면이 가려짐: AWAY_MS 뒤 잠근다. 다시 보이면 취소. */
    fun away() { ui.removeCallbacks(lockLater); ui.postDelayed(lockLater, AWAY_MS) }

    fun back() { ui.removeCallbacks(lockLater) }
}

/** 화면에 한 번 알릴 사본 소식(사본을 지우거나 옮긴 이유). 코드만 둔다(이름·경로 없음). */
object MirrorNotice {
    const val MIGRATED = "migrated"
    const val KEY_RESET = "key_reset"
    const val ARCHIVED = "archived"
    private const val PREFS = "mirror_notice"
    private val ORDER = listOf(MIGRATED, KEY_RESET, ARCHIVED)

    fun add(ctx: Context, code: String) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(code, true).apply()
    }

    /** 쌓인 소식을 꺼낸다(꺼내면 지운다). */
    fun take(ctx: Context): List<String> {
        val sp = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val got = ORDER.filter { sp.getBoolean(it, false) }
        if (got.isNotEmpty()) sp.edit().clear().apply()
        return got
    }

    fun text(ctx: Context, code: String): String = ctx.getString(when (code) {
        MIGRATED -> R.string.mirror_notice_migrated
        KEY_RESET -> R.string.mirror_notice_key_reset
        else -> R.string.mirror_notice_archived
    })
}
