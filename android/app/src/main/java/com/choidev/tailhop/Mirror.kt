package com.choidev.tailhop

import android.content.Context
import android.os.Build
import android.os.StatFs
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.LruCache
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.util.concurrent.CopyOnWriteArraySet
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 이 폰의 앱 전용 저장소(filesDir) 용량(§19.1). 받기 서비스·화면·작업이 init을 부른 뒤부터 hello에 싣는다. */
object LocalSpace {
    @Volatile private var dir: File? = null

    fun init(ctx: Context) { if (dir == null) dir = ctx.applicationContext.filesDir }

    fun now(): DeviceSpace? = dir?.let { of(it) }

    fun of(d: File): DeviceSpace? = try { StatFs(d.path).let { DeviceSpace(it.availableBytes, it.totalBytes) } } catch (e: Exception) { null }
}

/**
 * 사본의 열쇠(§19.3): Android Keystore의 AES-256-GCM 열쇠(내보내기 불가, 가능하면 StrongBox — 없으면 TEE).
 * 사본마다의 목록 열쇠(key.bin)와 파일마다의 열쇠(목록의 fkey)를 이 열쇠로 잠근다. 잠근 모양은 AesSealKey와 같다(iv 12 ‖ 암호문).
 */
object MirrorKeystore : SealKey {
    private const val ALIAS = "tailhop_mirror_v1"

    @Volatile private var cached: SecretKey? = null

    @Synchronized private fun key(): SecretKey {
        cached?.let { return it }
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { cached = it; return it }
        fun spec(strong: Boolean) = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
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
            throw ProtocolException("mirror_damaged") // 열쇠가 없어짐(잠금 화면을 바꿈 등)
        }
    }

    /** 모든 사본을 지웠다: 열쇠도 지운다(다음 사본은 새 열쇠로). */
    @Synchronized fun delete() {
        cached = null
        try { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) } catch (e: Exception) { }
    }
}

/** 이 기기 동기화 설정(§19.3): 받기(기본 켜짐), 모바일 데이터로도(기본 꺼짐). */
object MirrorSettings {
    private const val PREFS = "settings"
    private const val K_ON = "mirrorOn"
    private const val K_MOBILE = "mirrorMobile"

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isOn(ctx: Context): Boolean = prefs(ctx).getBoolean(K_ON, true)

    fun mobile(ctx: Context): Boolean = prefs(ctx).getBoolean(K_MOBILE, false)

    /** 끄면 모든 사본을 지운다(부른 쪽이 먼저 확인을 받는다). 주기 작업도 다시 정한다. */
    fun setOn(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(K_ON, on).apply()
        if (!on) {
            MirrorSync.cancelRunning()
            val app = ctx.applicationContext
            Thread({ Mirror.deleteAll(app) }, "tailhop-mirror-wipe").start()
        }
        MirrorWork.schedule(ctx)
    }

    fun setMobile(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(K_MOBILE, on).apply()
        MirrorWork.schedule(ctx)
    }
}

/**
 * 허브마다 마지막 맞추기 결과(설정 화면용): 마지막으로 다 맞춘 시각과 마지막 상태 코드. 이름·경로는 두지 않는다(키는 사본 폴더 이름).
 * 지금 맞추는 허브와 남은 파일 수는 메모리에만.
 */
object MirrorStatus {
    private const val PREFS = "mirror_status"
    const val OK = "ok"
    const val OFFLINE = "offline"
    const val STORE = "store"
    const val UNSUPPORTED = "unsupported"
    const val METERED = "metered"
    const val SPACE = "sync_space"

    data class Rec(val okAt: Long, val code: String?, val at: Long)

    @Volatile var running: String? = null
        private set
    @Volatile var left = 0
        private set

    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    fun changed() { listeners.forEach { try { it() } catch (e: Exception) { } } }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(ctx: Context, ip: String): Rec {
        val k = Mirror.hash(ip)
        val sp = prefs(ctx)
        return Rec(sp.getLong("$k.ok", 0L), sp.getString("$k.code", null), sp.getLong("$k.at", 0L))
    }

    fun set(ctx: Context, ip: String, code: String) {
        val k = Mirror.hash(ip)
        val now = System.currentTimeMillis()
        val e = prefs(ctx).edit().putString("$k.code", code).putLong("$k.at", now)
        if (code == OK) e.putLong("$k.ok", now)
        e.apply()
        changed()
    }

    fun forget(ctx: Context, ip: String) {
        val k = Mirror.hash(ip)
        prefs(ctx).edit().remove("$k.ok").remove("$k.code").remove("$k.at").apply()
        changed()
    }

    fun clearAll(ctx: Context) { prefs(ctx).edit().clear().apply(); changed() }

    fun progress(ip: String?, n: Int) {
        running = ip
        left = n
        changed()
    }
}

/**
 * 허브 하나의 기기 쪽 사본(§19.3): filesDir/mirror/<sha256(허브 id) 앞 16자>/. 이 폰은 PC를 IP로 알아보므로 허브 id는 PC IP다.
 * - key.bin: 이 사본의 목록 열쇠(32바이트 난수)를 Keystore 열쇠로 잠근 것.
 * - index.bin: 목록(MirrorIndex: 경로·크기·시각·ver·객체 이름·Keystore로 잠근 파일 열쇠)을 목록 열쇠로 잠근 것.
 * - part.bin: 받다 만 파일 하나(MirrorPart, 같은 열쇠로).
 * - objects/<32 hex>: 내용·미리보기(MirrorObject, 파일 열쇠로 1 MiB 청크 AES-GCM). 이름도 난수라 무엇인지 드러나지 않는다.
 * 평문(내용·이름·경로)은 디스크·로그·알림 어디에도 쓰지 않는다. 화면은 이 객체를 통해서만 읽는다(메모리로만 푼다).
 */
class Mirror private constructor(private val dir: File) {
    private val tag = dir.name
    private lateinit var listKey: AesSealKey
    private var index = MirrorIndex()
    /** 파일 열쇠 풀기는 Keystore를 거치므로(StrongBox면 느리다) 푼 것을 조금 메모리에 둔다. */
    private val keys = LruCache<String, ByteArray>(256)
    /** 목록 쓰기를 몰아서(파일 몇 개마다). */
    private var dirty = 0

    private fun aad(what: String) = "tailhop mirror v1|$tag|$what".toByteArray(Charsets.UTF_8)

    private val keyFile get() = File(dir, "key.bin")
    private val indexFile get() = File(dir, "index.bin")
    private val partFile get() = File(dir, "part.bin")
    private val objDir get() = File(dir, "objects")

    /** 열기: 열쇠·목록을 풀지 못하면(Keystore 열쇠가 없어짐·손상) 이 사본을 지우고 새로 시작한다(다시 받으면 된다). */
    private fun load() {
        dir.mkdirs()
        val raw = try { if (keyFile.isFile) MirrorKeystore.open(keyFile.readBytes(), aad("key")) else null } catch (e: Exception) { null }
        if (raw == null || raw.size != 32) {
            wipeFiles()
            dir.mkdirs()
            val fresh = Protocol.randomBytes(32)
            writeAtomic(keyFile, MirrorKeystore.seal(fresh, aad("key")))
            listKey = AesSealKey(fresh)
            fresh.fill(0)
            index = MirrorIndex()
            objDir.mkdirs()
            return
        }
        listKey = AesSealKey(raw)
        raw.fill(0)
        index = try { if (indexFile.isFile) MirrorIndex.parse(listKey.open(indexFile.readBytes(), aad("index"))) else MirrorIndex() } catch (e: Exception) {
            MirrorIndex() // 목록이 손상: 처음부터 다시 받는다(아래 정리가 남은 객체를 지운다)
        }
        objDir.mkdirs()
        prune()
    }

    /** 목록·받다 만 것이 쓰지 않는 객체 파일을 지운다(목록을 쓰기 전에 끊긴 받기 등). 정해진 이름(32자 hex)만 지운다. */
    private fun prune() {
        val keep = index.objects() + listOfNotNull(readPart()?.obj)
        for (f in objDir.listFiles().orEmpty()) if (HEX32.matches(f.name) && f.name !in keep) f.delete()
    }

    private fun wipeFiles() {
        keys.evictAll()
        dir.deleteRecursively()
    }

    private fun writeAtomic(f: File, bytes: ByteArray) {
        val tmp = File(f.path + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) throw IOException("rename") }
    }

    // ---------- 읽기(화면) ----------

    @Synchronized fun entries(): List<MirrorEntry> = index.entries.values.toList()

    @Synchronized fun entriesById(): Map<String, MirrorEntry> = LinkedHashMap(index.entries)

    @Synchronized fun digest(): String? = index.digest

    @Synchronized fun hasFiles(): Boolean = index.entries.values.any { !it.dir && it.complete }

    @Synchronized fun find(path: String): MirrorEntry? = MirrorTree.find(index.entries.values, path)

    @Synchronized fun usage(): Pair<Int, Long> = index.usage()

    /** 디스크에서 쓰는 바이트(암호문 객체·목록). */
    fun diskBytes(): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun objFile(obj: String): File {
        require(HEX32.matches(obj))
        return File(objDir, obj)
    }

    /** 파일 열쇠(Keystore로 풀어 메모리에). */
    fun fileKey(fkey: String): ByteArray {
        synchronized(keys) { keys.get(fkey) }?.let { return it }
        val sealed = Protocol.b64uDecode(fkey) ?: throw ProtocolException("mirror_damaged")
        val k = MirrorKeystore.open(sealed, aad("fkey"))
        if (k.size != 32) throw ProtocolException("mirror_damaged")
        synchronized(keys) { keys.put(fkey, k) }
        return k
    }

    /** 새 파일 열쇠: (평문 열쇠, Keystore로 잠근 것 base64url). */
    fun newFileKey(): Pair<ByteArray, String> {
        val k = Protocol.randomBytes(32)
        val sealed = Protocol.b64uEncode(MirrorKeystore.seal(k, aad("fkey")))
        synchronized(keys) { keys.put(sealed, k) }
        return k to sealed
    }

    /** 내용 읽기(원하는 위치부터, 부른 쪽이 닫는다). */
    fun reader(e: MirrorEntry): MirrorObject.Reader {
        val obj = e.obj ?: throw ProtocolException("not_found")
        return MirrorObject.Reader(objFile(obj), fileKey(e.fkey ?: throw ProtocolException("mirror_damaged")))
    }

    /** 미리보기 JPEG(메모리로만). 없으면 null. */
    fun thumb(e: MirrorEntry): ByteArray? {
        val t = e.tobj ?: return null
        return MirrorObject.Reader(objFile(t), fileKey(e.fkey ?: return null)).use { it.readAll(SyncManifest.THUMB_MAX) }
    }

    // ---------- 쓰기(맞추기) ----------

    /** 항목을 넣거나 바꾼다. 바뀐 항목이 쓰던 객체 중 새 것이 쓰지 않는 것은 지운다. */
    @Synchronized fun put(e: MirrorEntry) {
        val old = index.entries[e.id]
        index.entries[e.id] = e
        if (old != null) for (o in listOfNotNull(old.obj, old.tobj)) if (o != e.obj && o != e.tobj) objFile(o).delete()
        dirty++
    }

    /** 항목을 지운다(객체도, 휴지통 없음 §19.3). */
    @Synchronized fun remove(id: String) {
        val old = index.entries.remove(id) ?: return
        for (o in listOfNotNull(old.obj, old.tobj)) objFile(o).delete()
        dirty++
    }

    @Synchronized fun setDigest(d: String?) { index.digest = d; dirty++ }

    /** 목록을 쓴다(force가 아니면 바뀐 것이 몇 개 모였을 때만). */
    @Synchronized fun save(force: Boolean = true) {
        if (dirty == 0 || (!force && dirty < SAVE_EVERY)) return
        writeAtomic(indexFile, listKey.seal(index.encode(), aad("index")))
        dirty = 0
    }

    fun readPart(): MirrorPart? = try {
        if (partFile.isFile) MirrorPart.parse(listKey.open(partFile.readBytes(), aad("part"))) else null
    } catch (e: Exception) { null }

    fun writePart(p: MirrorPart) { writeAtomic(partFile, listKey.seal(p.encode(), aad("part"))) }

    /** 받다 만 것을 버린다(objDrop이면 그 객체도). */
    @Synchronized fun clearPart(objDrop: Boolean) {
        val p = readPart()
        partFile.delete()
        if (objDrop && p != null && index.objects().none { it == p.obj }) objFile(p.obj).delete()
    }

    companion object {
        private val HEX32 = Regex("[0-9a-f]{32}")
        private const val SAVE_EVERY = 20
        private val open = HashMap<String, Mirror>()

        /** 사본 폴더 이름: sha256(허브 id) 앞 16자(허브 이름·주소를 폴더 이름에 쓰지 않는다). */
        fun hash(ip: String): String = Xfer.hex(Xfer.sha256().digest(ip.toByteArray(Charsets.UTF_8))).take(16)

        private fun root(ctx: Context) = File(ctx.applicationContext.filesDir, "mirror")

        private fun dirOf(ctx: Context, ip: String) = File(root(ctx), hash(ip))

        /** 이 허브의 사본이 있는지(파일 여부만, 풀지 않는다). */
        fun exists(ctx: Context, ip: String): Boolean = File(dirOf(ctx, ip), "index.bin").isFile

        /** 연다(없으면 만든다). 같은 허브는 이 프로세스에서 한 객체를 같이 쓴다. 작업 스레드에서. */
        @Synchronized fun get(ctx: Context, ip: String): Mirror =
            open.getOrPut(ip) { Mirror(dirOf(ctx, ip)).also { it.load() } }

        /** 있으면 연다(없으면 null — 만들지 않는다). */
        @Synchronized fun peek(ctx: Context, ip: String): Mirror? = open[ip] ?: if (exists(ctx, ip)) get(ctx, ip) else null

        /** 그 허브의 사본을 지운다(연결 해제·사본 지우기·허브의 대상이 없어짐). */
        @Synchronized fun delete(ctx: Context, ip: String) {
            open.remove(ip)?.let { synchronized(it) { it.keys.evictAll() } }
            dirOf(ctx, ip).deleteRecursively()
            MirrorStatus.forget(ctx, ip)
        }

        /** 모든 사본과 그 열쇠를 지운다("이 기기에 동기화 받기"를 끔). */
        @Synchronized fun deleteAll(ctx: Context) {
            open.values.forEach { synchronized(it) { it.keys.evictAll() } }
            open.clear()
            root(ctx).deleteRecursively()
            MirrorKeystore.delete()
            MirrorStatus.clearAll(ctx)
        }
    }
}
