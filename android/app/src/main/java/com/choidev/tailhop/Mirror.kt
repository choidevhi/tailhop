package com.choidev.tailhop

import android.content.Context
import android.os.StatFs
import com.google.crypto.tink.Aead
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet

/** 이 폰의 앱 전용 저장소(filesDir) 용량(§19.1). 받기 서비스·화면·작업이 init을 부른 뒤부터 hello에 싣는다. */
object LocalSpace {
    @Volatile private var dir: File? = null

    fun init(ctx: Context) { if (dir == null) dir = ctx.applicationContext.filesDir }

    fun now(): DeviceSpace? = dir?.let { of(it) }

    fun of(d: File): DeviceSpace? = try { StatFs(d.path).let { DeviceSpace(it.availableBytes, it.totalBytes) } } catch (e: Exception) { null }
}

/** 이 기기 동기화 설정(§19.3): 받기(기본 켜짐), 모바일 데이터로도(기본 꺼짐). */
object MirrorSettings {
    private const val PREFS = "settings"
    private const val K_ON = "mirrorOn"
    private const val K_MOBILE = "mirrorMobile"

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isOn(ctx: Context): Boolean = prefs(ctx).getBoolean(K_ON, true)

    fun mobile(ctx: Context): Boolean = prefs(ctx).getBoolean(K_MOBILE, false)

    /** 끄면 지금 사본을 모두 지운다(부른 쪽이 먼저 확인을 받는다). 이전 사본(§19.5)은 남는다. 주기 작업도 다시 정한다. */
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
 * 허브 하나의 기기 쪽 사본(§19.3, 2.3.1): filesDir/mirror/<sha256(허브 id) 앞 16자>/. 이 폰은 PC를 IP로 알아보므로 허브 id는 PC IP다.
 * - ledger.bin: 장부(Ledger) — 인증 없이 여는 열쇠로 잠금. 허브 id·ver·객체 이름·ih(경로의 열쇠 붙은 해시)·저장소 번호. 경로·크기는 없다.
 * - part.bin: 받다 만 파일 하나(MirrorPart, 그 파일 열쇠 포함 — 받는 동안만). 장부와 같은 열쇠.
 * - box/<8자리 번호>: 상자 조각. 머리("THB1" ‖ 공개키로 감싼 데이터 열쇠) 뒤에 기록(길이 4 ‖ AES-GCM(데이터 열쇠, JSON, aad=mid·조각·순번))을
 *   덧붙인다. 기록은 경로·크기·수정 시각·파일 열쇠·미리보기 열쇠. 쓰는 쪽은 맞추기 한 번 동안만 데이터 열쇠를 메모리에 두고 지운다 —
 *   맞추기는 지문 없이 쓰고, 읽기는 개인키(지문·화면 잠금)로 데이터 열쇠를 풀어야 한다. 풀어 읽은 뒤에는 조각 하나로 다시 쓴다(정리).
 * - objects/<32 hex>: 내용·미리보기(MirrorObject, 파일마다 열쇠로 1 MiB 청크 AES-GCM). 이름도 난수라 무엇인지 드러나지 않는다.
 * 평문(내용·이름·경로)은 디스크·로그·알림 어디에도 쓰지 않는다. 화면은 이 객체를 통해서만 읽고, 푼 목록은 메모리에만 두며 lock()이 지운다.
 * 이 클래스(동반 객체의 Context 함수 밖)는 안드로이드 없이 돈다 — 열쇠는 MirrorKeys로 받는다(JVM 테스트).
 */
class Mirror(val dir: File, private val keys: MirrorKeys) {
    private var ledger = Ledger.fresh()
    /** 장부 쓰기를 몰아서(파일 몇 개마다). */
    private var dirty = 0
    /** 지금 덧붙이는 상자 조각(맞추기 한 번 동안만): 번호·데이터 열쇠·다음 순번. */
    private var boxSeq = -1
    private var boxKey: ByteArray? = null
    private var boxCount = 0
    /** 개인키로 푼 목록(메모리에만). */
    private var view: View? = null
    /** PIN으로 연 잠긴 폴더(§19.6): 잠긴 폴더 id → 그 폴더 열쇠(Tink AEAD, 메모리에만 — lock()이 버린다). */
    private val vaults = HashMap<String, Aead>()
    /** 연 잠긴 폴더 안을 풀어 둔 것. 안의 줄·받은 객체가 바뀌면(innerDirty) 다시 푼다. */
    private var inner: Inner? = null
    private var innerDirty = true

    /** 미리보기 하나: 허브 tver, 이 폰의 객체 이름, 그 열쇠. */
    class Thumb(val tver: String, val obj: String, val key: ByteArray)

    private class FileKeys(val fkey: ByteArray, var tkey: ByteArray?) {
        fun wipe() { fkey.fill(0); tkey?.fill(0); tkey = null }
    }

    private class View(
        val entries: LinkedHashMap<String, MirrorEntry>, val keys: HashMap<String, FileKeys>,
        val seals: HashMap<String, SyncManifest.VaultLine>,
    ) {
        fun wipe() { keys.values.forEach { it.wipe() }; keys.clear(); entries.clear(); seals.clear() }
    }

    /** 잠긴 폴더 안 파일의 열쇠(meta의 k, 미리보기의 k·크기) — 그 폴더를 연 동안만. */
    private class RawKeys(val fk: ByteArray, val tk: ByteArray?, val ts: Long) {
        fun wipe() { fk.fill(0); tk?.fill(0) }
    }

    private class Inner(val entries: List<MirrorEntry>, val keys: Map<String, RawKeys>) {
        fun wipe() { keys.values.forEach { it.wipe() } }
    }

    /** 상자에서 다시 만든 항목 정보(풀어 읽는 동안만). */
    private class Info(var path: String, var dir: Boolean, var size: Long, var mtime: Long) {
        var obj: String? = null
        var fkey: ByteArray? = null
        var tobj: String? = null
        var tkey: ByteArray? = null
        var vault: SyncManifest.VaultLine? = null
    }

    private val partFile get() = File(dir, "part.bin")
    private val boxDir get() = File(dir, "box")
    private val objDir get() = File(dir, "objects")
    /** 잠긴 폴더 안 객체의 받은 그대로인 암호문(§19.6): vobjects/<허브 객체 id>. */
    private val rawDir get() = File(dir, "vobjects")

    /**
     * 연다. create면 없거나 풀 수 없는 장부(장부 열쇠가 없어짐·손상)를 새로 만든다(맞추기 — 다시 받으면 된다).
     * 아니면(이전 사본) mirror_damaged — 지우지 않는다.
     */
    fun load(create: Boolean = true): Mirror {
        val l = readLedger(dir, keys)
        if (l != null) ledger = l
        else {
            if (!create) throw ProtocolException("mirror_damaged")
            if (dir.exists()) dir.deleteRecursively()
            dir.mkdirs()
            ledger = Ledger.fresh()
            writeLedger(dir, keys, ledger)
        }
        objDir.mkdirs()
        prune()
        return this
    }

    /** 장부·받다 만 것이 쓰지 않는 객체 파일을 지운다(장부를 쓰기 전에 끊긴 받기 등). 정해진 이름(32자 hex)만 지운다. */
    private fun prune() {
        val keep = ledger.objects() + listOfNotNull(readPart()?.obj)
        for (f in objDir.listFiles().orEmpty()) if (HEX32.matches(f.name) && f.name !in keep) f.delete()
        pruneRaw()
    }

    /** 잠긴 폴더 안의 줄이 쓰지 않는 암호문을 지운다(정해진 이름만). */
    private fun pruneRaw() {
        val keep = ledger.vrows.values.flatMapTo(HashSet()) { r -> r.objs.map { it.o } }
        for (f in rawDir.listFiles().orEmpty()) if (HEX32.matches(f.name) && f.name !in keep) f.delete()
    }

    fun objFile(obj: String): File {
        require(HEX32.matches(obj))
        return File(objDir, obj)
    }

    /** 디스크에서 쓰는 바이트(암호문만). */
    fun diskBytes(): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    // ---------- 장부(잠긴 채로) ----------

    @Synchronized fun items(): Map<String, LedgerItem> = LinkedHashMap(ledger.items)

    @Synchronized fun ih(w: SyncManifest.Item): String = ledger.ih(w)

    @Synchronized fun digest(): String? = ledger.digest

    @Synchronized fun setDigest(d: String?) { ledger.digest = d; dirty++ }

    @Synchronized fun storeId(): String? = ledger.storeId

    /** 허브 저장소 번호(§19.5)와 허브 이름(이전 사본 목록에 보인다)을 기억한다. */
    @Synchronized fun setIdentity(storeId: String?, hub: String?) {
        if (storeId != null && storeId != ledger.storeId) { ledger.storeId = storeId; dirty++ }
        val h = hub?.take(Ledger.HUB_MAX)
        if (h != null && h != ledger.hub) { ledger.hub = h; dirty++ }
    }

    @Synchronized fun hasContent(): Boolean = ledger.items.isNotEmpty() || ledger.vrows.isNotEmpty() || partFile.isFile

    /** 다 받은 파일 수와 디스크 크기(지문 없이 — 설정 화면). */
    @Synchronized fun usage(): Pair<Int, Long> = ledger.files() to diskBytes()

    // ---------- 쓰기(맞추기, 공개키만) ----------

    /**
     * 폴더를 넣거나 자리를 고친다. 같은 id가 파일이었으면 그 객체를 지운다. 잠긴 폴더(§19.6)면 PIN·복구 코드 봉인도
     * 기록에 넣는다 — 상자는 공개키로 잠기니 봉인이 기기 잠금(지문)으로 한 번 더 감싸인다(파일을 빼 가도 PIN 대입을 못 하게).
     */
    @Synchronized fun putDir(w: SyncManifest.Item) {
        append(listOf(moveRec(w.id, w.path, true, 0, 0, w.vault)))
        ledger.items[w.id]?.let { if (!it.dir) dropObjects(it) }
        ledger.items[w.id] = LedgerItem(w.id, true, ledger.ih(w))
        view?.let { v ->
            v.keys.remove(w.id)?.wipe()
            v.entries[w.id] = MirrorEntry(w.id, w.path, true, vault = w.vault != null, sealed = w.vault != null)
            if (w.vault != null) v.seals[w.id] = w.vault else v.seals.remove(w.id)
        }
        if (w.vault == null || vaults[w.id] != null) { vaults.remove(w.id); innerDirty = true } // 봉인이 바뀌면 다시 PIN으로
        dirty++
    }

    // ---------- 잠긴 폴더 안(§19.6, 받은 그대로) ----------

    /** 잠긴 폴더 안의 줄을 허브 목록에 맞춘다(지문 없이 — 풀지 않는다). 쓰지 않는 암호문은 지운다. */
    @Synchronized fun syncRows(rows: List<SyncManifest.VaultRow>) {
        val want = LinkedHashMap<String, SyncManifest.VaultRow>()
        for (r in rows) want[r.row] = r.copy(path = "") // 잠긴 폴더 경로(평문)는 장부에 두지 않는다
        var changed = false
        for (r in ledger.vrows.keys.toList()) if (r !in want) { ledger.vrows.remove(r); changed = true }
        for ((rid, w) in want) if (ledger.vrows[rid] != w) { ledger.vrows[rid] = w; changed = true }
        if (changed) { dirty++; innerDirty = true }
        pruneRaw()
    }

    fun rawFile(oid: String): File {
        require(HEX32.matches(oid))
        return File(rawDir, oid)
    }

    /** 받은 암호문 길이(없으면 -1). */
    fun rawSize(oid: String): Long = rawFile(oid).let { if (it.isFile) it.length() else -1L }

    /** 암호문을 이어 쓸 곳(끝에 덧붙인다). 사본 폴더가 없어졌으면(지움·옮김) 되살리지 않는다. */
    @Synchronized fun openRaw(oid: String): FileOutputStream {
        if (!dir.isDirectory) throw IOException("gone")
        rawDir.mkdir()
        return FileOutputStream(rawFile(oid), true)
    }

    /** 암호문 하나를 다 받았다(연 잠긴 폴더 안을 다시 푼다). */
    @Synchronized fun rawDone() { innerDirty = true }

    /** 같은 id·ver 파일의 경로·크기·수정 시각을 고친다(내용은 그대로). 허브 미리보기가 없어졌으면 이 폰의 것도 지운다. */
    @Synchronized fun move(w: SyncManifest.Item) {
        val old = ledger.items[w.id] ?: return
        if (old.dir || w.dir) return
        append(listOf(moveRec(w.id, w.path, false, w.size, w.mtime)))
        val dropThumb = w.tver == null && old.tobj != null
        if (dropThumb) objFile(old.tobj!!).delete()
        val now = old.copy(ih = ledger.ih(w), tver = if (dropThumb) null else old.tver, tobj = if (dropThumb) null else old.tobj)
        ledger.items[w.id] = now
        view?.let { v ->
            v.entries[w.id]?.let { e -> v.entries[w.id] = e.copy(path = w.path, size = w.size, mtime = w.mtime, tver = now.tver, tobj = now.tobj) }
            if (dropThumb) v.keys[w.id]?.let { it.tkey?.fill(0); it.tkey = null }
        }
        dirty++
    }

    /** 항목을 지운다(객체도, 휴지통 없음 §19.3). */
    @Synchronized fun remove(id: String) {
        val old = ledger.items.remove(id) ?: return
        dropObjects(old)
        view?.let { v -> v.entries.remove(id); v.keys.remove(id)?.wipe() }
        dirty++
    }

    private fun dropObjects(l: LedgerItem) { for (o in listOfNotNull(l.obj, l.tobj)) objFile(o).delete() }

    @Synchronized fun part(): MirrorPart? = readPart()

    private fun readPart(): MirrorPart? = try {
        if (partFile.isFile) MirrorPart.parse(keys.ledger.open(partFile.readBytes(), partAad())) else null
    } catch (e: Exception) { null }

    /** 받기 시작: 그 파일 열쇠를 장부 쪽(part.bin)에 둔다 — 끊겨도 잠긴 채로 이어 받게. */
    @Synchronized fun startPart(p: MirrorPart) {
        if (!dir.isDirectory) throw IOException("gone")
        writeAtomic(partFile, keys.ledger.seal(p.encode(), partAad()))
    }

    /** 받다 만 것을 버린다(objDrop이면 그 객체도). */
    @Synchronized fun clearPart(objDrop: Boolean) {
        val p = readPart()
        partFile.delete()
        if (objDrop && p != null && p.obj !in ledger.objects()) objFile(p.obj).delete()
    }

    /**
     * 다 받았다: 경로·크기·시각·파일 열쇠(·미리보기 열쇠)를 공개키로 잠근 상자에 쓰고, 장부에는 열쇠 없이 남긴다.
     * 받다 만 것(그 파일 열쇠)은 바로 지운다. 같은 id가 쓰던 다른 객체는 지운다.
     */
    @Synchronized fun complete(w: SyncManifest.Item, obj: String, fkey: ByteArray, thumb: Thumb?) {
        val recs = arrayListOf(fileRec(w.id, w.path, w.size, w.mtime, obj, fkey))
        if (thumb != null) recs += thumbRec(w.id, thumb.obj, thumb.key)
        append(recs)
        val old = ledger.items[w.id]
        ledger.items[w.id] = LedgerItem(w.id, false, ledger.ih(w), w.ver, thumb?.tver, obj, thumb?.obj)
        if (old != null) for (o in listOfNotNull(old.obj, old.tobj)) if (o != obj && o != thumb?.obj) objFile(o).delete()
        if (readPart()?.obj == obj) partFile.delete()
        view?.let { v ->
            v.entries[w.id] = MirrorEntry(w.id, w.path, false, w.size, w.mtime, w.ver, thumb?.tver, obj, thumb?.obj)
            v.keys.put(w.id, FileKeys(fkey.copyOf(), thumb?.key?.copyOf()))?.wipe()
        }
        dirty++
    }

    /** 다 받은 파일의 미리보기를 바꾼다(null이면 없앤다). */
    @Synchronized fun setThumb(w: SyncManifest.Item, t: Thumb?) {
        val old = ledger.items[w.id] ?: return
        if (old.dir) return
        if (t != null) append(listOf(thumbRec(w.id, t.obj, t.key)))
        old.tobj?.let { if (it != t?.obj) objFile(it).delete() }
        ledger.items[w.id] = old.copy(tver = t?.tver, tobj = t?.obj)
        view?.let { v ->
            v.entries[w.id]?.let { e -> v.entries[w.id] = e.copy(tver = t?.tver, tobj = t?.obj) }
            v.keys[w.id]?.let { k -> k.tkey?.fill(0); k.tkey = t?.key?.copyOf() }
        }
        dirty++
    }

    /** 장부를 쓴다(force가 아니면 바뀐 것이 몇 개 모였을 때만). */
    @Synchronized fun save(force: Boolean = true) {
        if (dirty == 0 || (!force && dirty < SAVE_EVERY)) return
        writeLedger(dir, keys, ledger)
        dirty = 0
    }

    /** 맞추기가 끝났다: 데이터 열쇠를 메모리에서 지운다(다음 맞추기는 새 조각에). */
    @Synchronized fun closeBox() {
        boxKey?.fill(0)
        boxKey = null
        boxSeq = -1
        boxCount = 0
    }

    // ---------- 읽기(개인키 — 지문·화면 잠금) ----------

    /**
     * 다 받은 파일과 폴더(경로 등). 잠긴 폴더(§19.6)는 vault, 아직 PIN으로 열지 않았으면 sealed이고, 연 잠긴 폴더 안의 것도 함께
     * (raw = 그 잠긴 폴더 id). 잠겨 있으면 mirror_locked.
     */
    fun entries(): List<MirrorEntry> = synchronized(this) { all() }

    fun find(path: String): MirrorEntry? = synchronized(this) { MirrorTree.find(all(), path) }

    private fun all(): List<MirrorEntry> {
        val v = unlocked()
        val base = v.entries.values.map { if (it.vault) it.copy(sealed = it.id !in vaults) else it }
        return if (vaults.isEmpty()) base else base + innerOf(v).entries
    }

    /** 내용 읽기(원하는 위치부터, 부른 쪽이 닫는다). 열쇠는 사본을 받아 잠가도 이 Reader는 끝까지 읽는다. */
    fun reader(e: MirrorEntry): MirrorReader {
        if (e.raw != null) {
            val (obj, fk) = synchronized(this) {
                val i = innerOf(unlocked())
                val cur = i.entries.firstOrNull { it.id == e.id } ?: throw ProtocolException("not_found")
                (cur.obj ?: throw ProtocolException("not_found")) to (i.keys[e.id]?.fk?.copyOf() ?: throw ProtocolException("mirror_damaged"))
            }
            return try { VaultReader(rawFile(obj), VaultCrypto.fileAead(fk), e.size) } finally { fk.fill(0) }
        }
        val (obj, key) = synchronized(this) {
            val v = unlocked()
            val obj = v.entries[e.id]?.obj ?: throw ProtocolException("not_found")
            obj to (v.keys[e.id]?.fkey?.copyOf() ?: throw ProtocolException("mirror_damaged"))
        }
        return MirrorObject.Reader(objFile(obj), key)
    }

    /** 미리보기 JPEG(메모리로만). 없으면 null. */
    fun thumb(e: MirrorEntry): ByteArray? {
        if (e.raw != null) {
            val (t, tk, ts) = synchronized(this) {
                val i = innerOf(unlocked())
                val cur = i.entries.firstOrNull { it.id == e.id } ?: return null
                val k = i.keys[e.id] ?: return null
                Triple(cur.tobj ?: return null, k.tk?.copyOf() ?: return null, k.ts)
            }
            return try { VaultReader(rawFile(t), VaultCrypto.fileAead(tk), ts).use { it.readAll(SyncManifest.THUMB_MAX) } } finally { tk.fill(0) }
        }
        val (t, key) = synchronized(this) {
            val v = unlocked()
            val t = v.entries[e.id]?.tobj ?: return null
            t to (v.keys[e.id]?.tkey?.copyOf() ?: return null)
        }
        return try { MirrorObject.Reader(objFile(t), key).use { it.readAll(SyncManifest.THUMB_MAX) } } finally { key.fill(0) }
    }

    /** 푼 목록·열쇠와 연 잠긴 폴더를 메모리에서 지운다(앱이 오래 뒤로 감·화면 꺼짐). 다음 읽기는 다시 개인키를 쓴다. */
    @Synchronized fun lock() {
        view?.wipe()
        view = null
        vaults.clear()
        inner?.wipe()
        inner = null
        innerDirty = true
    }

    /** 그 경로의 잠긴 폴더 id(사본이 열려 있어야 안다). */
    fun vaultAt(path: String): String? = synchronized(this) { unlocked().entries.values.firstOrNull { it.vault && it.path == path }?.id }

    @Synchronized fun vaultOpen(vid: String): Boolean = vid in vaults

    /**
     * 잠긴 폴더를 PIN(6–64자) 또는 복구 코드로 연다(§19.6). 사본이 먼저 열려 있어야 한다(봉인이 지문 잠금 상자 안에 있다).
     * 허브와 같이 5번 틀리면 60초, 그 뒤 두 배(최대 1시간) 기다린다 — 횟수는 확인 전에 올려 장부에 쓴다(앱을 껐다 켜도 이어진다).
     * 틀리면 vault_pin, 기다려야 하면 vault_wait(retry초). Argon2id(256 MiB)를 돌리므로 작업 스레드에서.
     */
    fun openVault(vid: String, pin: String?, recovery: String?, now: Long = System.currentTimeMillis()) {
        val seal = synchronized(this) {
            val s = unlocked().seals[vid] ?: throw ProtocolException("not_found")
            val (count, until) = ledger.vfail[vid] ?: (0 to 0L)
            if (now < until) throw HubRefusal("vault_wait", JSONObject().put("retry", (until - now + 999) / 1000))
            val n = count + 1
            ledger.vfail[vid] = n to now + VaultCrypto.waitMs(n)
            dirty++
            save()
            s
        }
        val aead = when {
            pin != null && VaultCrypto.validPin(pin) -> VaultCrypto.open(seal.pin, pin, seal.pinAd)
            !recovery.isNullOrBlank() -> VaultCrypto.open(seal.recovery, VaultCrypto.normalizeCode(recovery), seal.recoveryAd)
            else -> null
        } ?: throw ProtocolException("vault_pin")
        synchronized(this) {
            ledger.vfail.remove(vid)
            dirty++
            try { save() } catch (e: IOException) { }
            if (view == null) return // 그 사이 사본이 잠겼다
            vaults[vid] = aead
            innerDirty = true
        }
    }

    /** 연 잠긴 폴더 안을 푼다: meta(그 폴더 열쇠, AD = 줄·부모·ring)에서 이름·크기·시각·파일 열쇠를 읽어 경로를 붙인다. */
    private fun innerOf(v: View): Inner {
        inner?.let { if (!innerDirty) return it }
        inner?.wipe()
        val list = ArrayList<MirrorEntry>()
        val ks = HashMap<String, RawKeys>()
        for ((vid, aead) in vaults) {
            val root = v.entries[vid]?.path ?: continue
            val rows = ledger.vrows.values.filter { it.vault == vid }
            val metas = HashMap<String, Pair<SyncManifest.VaultRow, JSONObject>>()
            for (r in rows) {
                val ct = Protocol.b64uDecode(r.meta) ?: continue
                val plain = try { aead.decrypt(ct, VaultCrypto.nodeAd(r.row, r.parent, r.ring)) } catch (e: Exception) { continue } // 망가진 줄은 보이지 않게
                val m = try { JSONObject(String(plain, Charsets.UTF_8)) } catch (e: Exception) { null }
                plain.fill(0)
                if (m == null || (m.opt("n") as? String)?.let { CloudPath.isSegment(it) } != true) continue
                metas[r.row] = r to m
            }
            fun pathOf(rid: String, depth: Int): String? {
                val (r, m) = metas[rid] ?: return null
                if (depth > 64) return null
                val name = m.getString("n")
                val up = if (r.parent == vid) root else pathOf(r.parent, depth + 1) ?: return null
                return "$up/$name".takeIf { CloudPath.isValid(it) }
            }
            for ((rid, rm) in metas) {
                val (r, m) = rm
                val path = pathOf(rid, 0) ?: continue
                if (m.opt("d") == true) { list += MirrorEntry(rid, path, true, raw = vid); continue }
                val size = Xfer.jsonLong(m.opt("s"))?.takeIf { it in 0..Protocol.MAX_FILE } ?: continue
                val mtime = Xfer.jsonLong(m.opt("m")) ?: 0L
                val sizes = r.objs.associate { it.o to it.s }
                val o = (m.opt("o") as? String)?.takeIf { HEX32.matches(it) }
                val k = (m.opt("k") as? String)?.let { Protocol.b64uDecode(it) }
                // 다 받은 것만(받은 암호문 길이 = 목록의 크기 = 평문 크기에 맞는 길이)
                val done = o != null && k != null && sizes[o] == rawSize(o) && sizes[o] == VaultCrypto.rawLength(size)
                val th = (m.opt("v") as? JSONObject)?.opt("thumb") as? JSONObject
                val to = (th?.opt("o") as? String)?.takeIf { HEX32.matches(it) && sizes[it] != null && sizes[it] == rawSize(it) }
                val tk = (th?.opt("k") as? String)?.let { Protocol.b64uDecode(it) }
                val ts = Xfer.jsonLong(th?.opt("s"))?.takeIf { it in 0..SyncManifest.THUMB_MAX }
                val thumbOk = done && to != null && tk != null && ts != null
                list += MirrorEntry(rid, path, false, size, mtime, "", if (thumbOk) THUMB_TVER else null,
                    if (done) o else null, if (thumbOk) to else null, raw = vid)
                if (done) ks[rid] = RawKeys(k!!, if (thumbOk) tk else null, ts ?: 0L) else k?.fill(0)
            }
        }
        val out = Inner(list, ks)
        inner = out
        innerDirty = false
        return out
    }

    /**
     * 허브로 복원(§19.5)용 묶음: 지금 항목과 파일 열쇠의 사본. 화면이 잠겨도 시작한 복원은 끝까지 읽는다. close가 열쇠를 지운다.
     */
    class Snapshot internal constructor(private val m: Mirror, val entries: List<MirrorEntry>, private val keys: Map<String, ByteArray>) : AutoCloseable {
        /** 그 파일을 처음부터 읽는 스트림(메모리 조각 하나에만 평문). */
        fun open(e: MirrorEntry): InputStream {
            val obj = e.obj ?: throw ProtocolException("not_found")
            val k = keys[e.id] ?: throw ProtocolException("mirror_damaged")
            return MirrorObject.Reader(m.objFile(obj), k.copyOf()).stream()
        }

        override fun close() { keys.values.forEach { it.fill(0) } }
    }

    @Synchronized fun snapshot(): Snapshot {
        val v = unlocked()
        return Snapshot(this, v.entries.values.toList(), v.keys.mapValues { it.value.fkey.copyOf() })
    }

    /**
     * 개인키로 상자를 풀어 목록을 만든다(한 번 풀면 lock()까지 메모리에). 상자에 정보가 없는 장부 줄(손상·끊긴 쓰기)은 장부에서 빼서
     * 다음 맞추기가 다시 받게 한다. 조각이 여럿이거나 고친 것이 있으면 하나로 다시 쓴다(다음 열기가 개인키를 한 번만 쓰게).
     */
    private fun unlocked(): View {
        view?.let { return it }
        val (recs, damaged) = readBox()
        val infos = replay(recs)
        val entries = LinkedHashMap<String, MirrorEntry>()
        val ks = HashMap<String, FileKeys>()
        val seals = HashMap<String, SyncManifest.VaultLine>()
        var healed = false
        try {
            for (l in ledger.items.values.toList()) {
                val i = infos[l.id]
                if (i == null || i.dir != l.dir || (!l.dir && (i.obj != l.obj || i.fkey == null))) {
                    ledger.items.remove(l.id)
                    dropObjects(l)
                    healed = true
                    continue
                }
                var item = l
                // 상자의 정보가 장부보다 늦다(끊긴 쓰기): 다음 맞추기가 정보를 다시 쓰게
                val iv = i.vault
                val ihNow = if (iv != null) ledger.ihVault(i.path, iv) else ledger.ih(i.dir, i.path, i.size, i.mtime)
                if (ihNow != l.ih) { item = item.copy(ih = ""); healed = true }
                val thumbOk = !l.dir && l.tobj != null && i.tobj == l.tobj && i.tkey != null
                if (!l.dir && l.tobj != null && !thumbOk) {
                    objFile(l.tobj).delete()
                    item = item.copy(tver = null, tobj = null)
                    healed = true
                }
                if (item != l) ledger.items[l.id] = item
                if (l.dir) {
                    entries[l.id] = MirrorEntry(l.id, i.path, true, vault = iv != null, sealed = iv != null)
                    if (iv != null) seals[l.id] = iv
                    continue
                }
                entries[l.id] = MirrorEntry(l.id, i.path, false, i.size, i.mtime, l.ver, item.tver, l.obj, item.tobj)
                ks[l.id] = FileKeys(i.fkey!!.copyOf(), if (thumbOk) i.tkey!!.copyOf() else null)
            }
        } finally {
            infos.values.forEach { it.fkey?.fill(0); it.tkey?.fill(0) }
        }
        val v = View(entries, ks, seals)
        if (healed) {
            ledger.digest = null // 다음 맞추기가 목록을 다시 받아 빠진 것을 채운다
            dirty++
            try { save() } catch (e: IOException) { }
        }
        if (healed || damaged || segments().size > 1) try { compact(v) } catch (e: IOException) { }
        view = v
        return v
    }

    /** 상자를 조각 하나로 다시 쓴다(새 데이터 열쇠). 다 쓴 뒤에만 예전 조각을 지운다. */
    private fun compact(v: View) {
        closeBox()
        val old = segments()
        val recs = ArrayList<JSONObject>()
        for (e in v.entries.values) {
            if (e.dir) { recs += moveRec(e.id, e.path, true, 0, 0, v.seals[e.id]); continue }
            val k = v.keys[e.id] ?: continue
            recs += fileRec(e.id, e.path, e.size, e.mtime, e.obj ?: continue, k.fkey)
            val tk = k.tkey
            if (e.tobj != null && tk != null) recs += thumbRec(e.id, e.tobj, tk)
        }
        try { append(recs) } finally { closeBox() }
        old.forEach { it.second.delete() }
    }

    // ---------- 상자 ----------

    private fun boxAad(seq: Int, i: Int) = "tailhop mirror box v2|${ledger.mid}|$seq|$i".toByteArray(Charsets.UTF_8)

    private fun partAad() = "tailhop mirror part v2|${ledger.mid}".toByteArray(Charsets.UTF_8)

    private fun segFile(seq: Int) = File(boxDir, String.format(Locale.ROOT, "%08d", seq))

    private fun segments(): List<Pair<Int, File>> =
        boxDir.listFiles().orEmpty().mapNotNull { f -> if (f.isFile && SEG.matches(f.name)) f.name.toInt() to f else null }.sortedBy { it.first }

    /** 기록을 지금 조각에 덧붙이고 디스크에 내린다(조각이 없으면 새 데이터 열쇠로 만든다 — 공개키만 쓴다). */
    private fun append(recs: List<JSONObject>) {
        if (recs.isEmpty()) return
        // 폴더는 만들지 않는다: 사본을 지우거나 옮긴 뒤(연결 해제·사본 지우기) 늦게 끝난 맞추기가 폴더를 되살리지 않게
        if (!dir.isDirectory) throw IOException("gone")
        val dk = boxKey ?: openBox()
        val out = ByteArrayOutputStream()
        var n = boxCount
        for (r in recs) {
            val plain = r.toString().toByteArray(Charsets.UTF_8)
            val sealed = AesSealKey(dk).seal(plain, boxAad(boxSeq, n))
            plain.fill(0)
            out.write(ByteBuffer.allocate(4).putInt(sealed.size).array())
            out.write(sealed)
            n++
        }
        try {
            FileOutputStream(segFile(boxSeq), true).use { it.write(out.toByteArray()); it.fd.sync() }
            boxCount = n
        } catch (e: IOException) {
            closeBox() // 찢겼을 수 있는 조각에는 더 붙이지 않는다
            throw e
        }
    }

    private fun openBox(): ByteArray {
        boxDir.mkdir()
        val seq = (segments().lastOrNull()?.first ?: 0) + 1
        val dk = Protocol.randomBytes(32)
        val head = MirrorSeal.head(keys.wrap(dk), BOX_MAGIC)
        FileOutputStream(segFile(seq)).use { it.write(head); it.fd.sync() }
        boxSeq = seq
        boxKey = dk
        boxCount = 0
        return dk
    }

    /** 상자의 모든 기록(조각·순번 차례로). 잠겨 있으면 mirror_locked. 풀 수 없는 조각·찢긴 끝은 건너뛰고 damaged=true. */
    private fun readBox(): Pair<List<JSONObject>, Boolean> {
        val out = ArrayList<JSONObject>()
        var damaged = false
        for ((seq, f) in segments()) {
            val b = try { f.readBytes() } catch (e: IOException) { damaged = true; continue }
            val (w, start) = try { MirrorSeal.readHead(b, BOX_MAGIC) } catch (e: ProtocolException) { damaged = true; continue }
            val dk = try { keys.unwrap(w) } catch (e: ProtocolException) {
                if (e.code != "mirror_damaged") throw e
                damaged = true
                continue
            }
            try {
                if (dk.size != 32) { damaged = true; continue }
                var pos = start
                var i = 0
                while (pos < b.size) {
                    if (b.size - pos < 4) { damaged = true; break }
                    val n = ByteBuffer.wrap(b, pos, 4).int
                    if (n < 12 + 16 || n > b.size - pos - 4) { damaged = true; break }
                    val plain = try { AesSealKey(dk).open(b.copyOfRange(pos + 4, pos + 4 + n), boxAad(seq, i)) } catch (e: ProtocolException) {
                        damaged = true
                        break
                    }
                    try { out += JSONObject(String(plain, Charsets.UTF_8)) } catch (e: Exception) { damaged = true }
                    plain.fill(0)
                    pos += 4 + n
                    i++
                }
            } finally { dk.fill(0) }
        }
        return out to damaged
    }

    /** 기록을 차례로 겹쳐 항목마다 마지막 정보를 만든다. 모양이 틀린 기록은 버린다(경로는 §12.1, 객체 이름은 32자 hex, 열쇠는 32바이트). */
    private fun replay(recs: List<JSONObject>): HashMap<String, Info> {
        val m = HashMap<String, Info>()
        for (r in recs) {
            val id = (r.opt("id") as? String)?.takeIf { HEX32.matches(it) } ?: continue
            val path = (r.opt("path") as? String)?.takeIf { it.isNotEmpty() && CloudPath.isValid(it) }
            when (r.opt("t")) {
                "f" -> {
                    val size = Xfer.jsonLong(r.opt("size"))?.takeIf { it in 0..Protocol.MAX_FILE } ?: continue
                    val mtime = Xfer.jsonLong(r.opt("mtime")) ?: continue
                    val obj = (r.opt("obj") as? String)?.takeIf { HEX32.matches(it) } ?: continue
                    val k = key(r) ?: continue
                    if (path == null) { k.fill(0); continue }
                    val i = m.getOrPut(id) { Info(path, false, size, mtime) }
                    i.path = path; i.dir = false; i.size = size; i.mtime = mtime; i.obj = obj
                    i.fkey?.fill(0)
                    i.fkey = k
                }
                "m" -> {
                    if (path == null) continue
                    val dir = r.opt("dir") == true
                    val size = if (dir) 0L else Xfer.jsonLong(r.opt("size"))?.takeIf { it in 0..Protocol.MAX_FILE } ?: continue
                    val mtime = if (dir) 0L else Xfer.jsonLong(r.opt("mtime")) ?: continue
                    val i = m.getOrPut(id) { Info(path, dir, size, mtime) }
                    i.path = path; i.dir = dir; i.size = size; i.mtime = mtime
                    i.vault = if (dir) (r.opt("vault") as? JSONObject)?.let { SyncManifest.VaultLine.parse(it) } else null
                }
                "h" -> {
                    val tobj = (r.opt("tobj") as? String)?.takeIf { HEX32.matches(it) } ?: continue
                    val k = key(r) ?: continue
                    val i = m[id]
                    if (i == null) { k.fill(0); continue }
                    i.tkey?.fill(0)
                    i.tobj = tobj
                    i.tkey = k
                }
            }
        }
        return m
    }

    private fun key(r: JSONObject): ByteArray? = (r.opt("k") as? String)?.let { Protocol.b64uDecode(it) }?.takeIf { it.size == 32 }

    private fun fileRec(id: String, path: String, size: Long, mtime: Long, obj: String, key: ByteArray) = JSONObject()
        .put("t", "f").put("id", id).put("path", path).put("size", size).put("mtime", mtime).put("obj", obj).put("k", Protocol.b64uEncode(key))

    private fun moveRec(id: String, path: String, dir: Boolean, size: Long, mtime: Long, vault: SyncManifest.VaultLine? = null): JSONObject {
        val o = JSONObject().put("t", "m").put("id", id).put("path", path)
        if (!dir) return o.put("size", size).put("mtime", mtime)
        o.put("dir", true)
        vault?.let { o.put("vault", it.json()) }
        return o
    }

    private fun thumbRec(id: String, tobj: String, key: ByteArray) = JSONObject()
        .put("t", "h").put("id", id).put("tobj", tobj).put("k", Protocol.b64uEncode(key))

    companion object {
        const val LEDGER = "ledger.bin"
        private val HEX32 = Regex("[0-9a-f]{32}")
        private val SEG = Regex("[0-9]{8}")
        private val BOX_MAGIC = "THB1".toByteArray(Charsets.US_ASCII)
        private val LEDGER_AAD = "tailhop mirror ledger v2".toByteArray(Charsets.UTF_8)
        private const val SAVE_EVERY = 20
        /** 잠긴 폴더 안 파일의 미리보기가 있다는 표시(허브 tver 대신). */
        private const val THUMB_TVER = "00000000000000000000000000000000"
        private const val MIGRATE_PREFS = "mirror_keys"
        private const val K_MIGRATED = "v231"

        /** 장부를 읽는다(없거나 풀 수 없으면 null). 인증이 필요 없다. */
        fun readLedger(dir: File, keys: MirrorKeys): Ledger? = try {
            val f = File(dir, LEDGER)
            if (!f.isFile) null else Ledger.parse(keys.ledger.open(f.readBytes(), LEDGER_AAD))
        } catch (e: Exception) { null }

        fun writeLedger(dir: File, keys: MirrorKeys, l: Ledger) { writeAtomic(File(dir, LEDGER), keys.ledger.seal(l.encode(), LEDGER_AAD)) }

        private fun writeAtomic(f: File, bytes: ByteArray) {
            val tmp = File(f.path + ".tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) throw IOException("rename") }
        }

        // ---------- 이 폰의 사본들(Context) ----------

        /** 열린 사본: 허브 IP → 사본, 이전 사본은 "old/<id>". */
        private val open = HashMap<String, Mirror>()
        @Volatile private var migrated = false

        /** 사본 폴더 이름: sha256(허브 id) 앞 16자(허브 이름·주소를 폴더 이름에 쓰지 않는다). */
        fun hash(ip: String): String = Xfer.hex(Xfer.sha256().digest(ip.toByteArray(Charsets.UTF_8))).take(16)

        private fun root(ctx: Context) = File(ctx.applicationContext.filesDir, "mirror")

        /** 이전 사본(§19.5): filesDir/mirror_old/<32자 hex>/. */
        private fun oldRoot(ctx: Context) = File(ctx.applicationContext.filesDir, "mirror_old")

        private fun dirOf(ctx: Context, ip: String) = File(root(ctx), hash(ip))

        private fun keys(ctx: Context): MirrorKeys = DeviceMirrorKeys.also { it.init(ctx) }

        /** 이 허브의 사본이 있는지(파일 여부만, 풀지 않는다). */
        fun exists(ctx: Context, ip: String): Boolean = File(dirOf(ctx, ip), LEDGER).isFile

        /** 연다(없으면 만든다). 같은 허브는 이 프로세스에서 한 객체를 같이 쓴다. 작업 스레드에서. */
        @Synchronized fun get(ctx: Context, ip: String): Mirror {
            migrate(ctx)
            return open.getOrPut(ip) { Mirror(dirOf(ctx, ip), keys(ctx)).load() }
        }

        /** 있으면 연다(없으면 null — 만들지 않는다). */
        @Synchronized fun peek(ctx: Context, ip: String): Mirror? = open[ip] ?: if (exists(ctx, ip)) get(ctx, ip) else null

        /** 그 허브의 사본을 지운다(사본 지우기·같은 저장소의 대상이 없어짐). */
        @Synchronized fun delete(ctx: Context, ip: String) {
            open.remove(ip)?.let { it.lock(); it.closeBox() }
            dirOf(ctx, ip).deleteRecursively()
            MirrorStatus.forget(ctx, ip)
        }

        /**
         * 그 허브의 사본을 이전 사본으로 옮긴다(§19.5: 허브의 저장소 번호가 바뀜·페어링을 끊음). 담은 것이 없으면 지우기만 한다.
         * 옮겼으면 true.
         */
        @Synchronized fun archive(ctx: Context, ip: String, hub: String?): Boolean {
            migrate(ctx)
            open.remove(ip)?.let { m ->
                try { m.save() } catch (e: IOException) { }
                m.lock()
                m.closeBox()
            }
            val d = dirOf(ctx, ip)
            val moved = if (File(d, LEDGER).isFile) MirrorArchive.move(d, oldRoot(ctx), keys(ctx), hub, System.currentTimeMillis()) != null
            else { d.deleteRecursively(); false }
            MirrorStatus.forget(ctx, ip)
            return moved
        }

        /** 이전 사본 목록(장부만 — 지문 없이). */
        @Synchronized fun archives(ctx: Context): List<MirrorArchive.Info> {
            migrate(ctx)
            return MirrorArchive.list(oldRoot(ctx), keys(ctx))
        }

        /** 이전 사본을 연다(읽기 전용으로 쓴다 — 맞추기가 쓰지 않는다). 장부를 풀 수 없으면 mirror_damaged. */
        @Synchronized fun openArchive(ctx: Context, id: String): Mirror {
            if (!MirrorArchive.isId(id)) throw ProtocolException("mirror_damaged")
            val d = File(oldRoot(ctx), id)
            if (!d.isDirectory) throw ProtocolException("not_found")
            return open.getOrPut("old/$id") { Mirror(d, keys(ctx)).load(create = false) }
        }

        @Synchronized fun deleteArchive(ctx: Context, id: String) {
            if (!MirrorArchive.isId(id)) return
            open.remove("old/$id")?.lock()
            File(oldRoot(ctx), id).deleteRecursively()
        }

        /** 지금 사본을 모두 지운다("이 기기에 동기화 받기"를 끔). 이전 사본과 기기 열쇠는 남긴다(이전 사본을 열어야 하므로). */
        @Synchronized fun deleteAll(ctx: Context) {
            for (k in open.keys.filter { !it.startsWith("old/") }) open.remove(k)?.let { it.lock(); it.closeBox() }
            root(ctx).deleteRecursively()
            MirrorStatus.clearAll(ctx)
        }

        /**
         * 기기 열쇠가 무효가 되었다(새 지문 등록·화면 잠금을 없앰 — 개인키로 더는 풀 수 없다): 이전 사본까지 모두 지우고 열쇠도 지운다.
         * 다음 맞추기가 새 열쇠로 허브에서 다시 받는다. 화면은 MirrorNotice로 이유를 알린다.
         */
        @Synchronized fun resetAll(ctx: Context) {
            open.values.forEach { it.lock(); it.closeBox() }
            open.clear()
            val had = root(ctx).listFiles().orEmpty().isNotEmpty() || oldRoot(ctx).listFiles().orEmpty().isNotEmpty()
            root(ctx).deleteRecursively()
            oldRoot(ctx).deleteRecursively()
            DeviceMirrorKeys.reset()
            MirrorStatus.clearAll(ctx)
            MirrorLock.lock()
            if (had) MirrorNotice.add(ctx, MirrorNotice.KEY_RESET)
        }

        /** 푼 목록·열쇠를 모두 메모리에서 지운다(MirrorLock). */
        @Synchronized fun lockAll() { open.values.forEach { it.lock() } }

        /**
         * 2.3.0 사본(인증 없는 Keystore 열쇠로만 잠근 index.bin·key.bin)을 한 번 지운다 — 허브에 원본이 있으니 다시 받는다.
         * 지운 것이 있으면 화면에 한 번 알린다. 예전 Keystore 열쇠도 지운다.
         */
        @Synchronized fun migrate(ctx: Context) {
            if (migrated) return
            migrated = true
            val sp = ctx.applicationContext.getSharedPreferences(MIGRATE_PREFS, Context.MODE_PRIVATE)
            if (sp.getBoolean(K_MIGRATED, false)) return
            var wiped = false
            for (d in root(ctx).listFiles().orEmpty()) {
                if (!d.isDirectory || File(d, LEDGER).isFile) continue
                if (File(d, "index.bin").isFile) wiped = true
                d.deleteRecursively()
            }
            KeystoreAes.deleteAlias(DeviceMirrorKeys.OLD_ALIAS)
            if (wiped) {
                MirrorStatus.clearAll(ctx)
                MirrorNotice.add(ctx, MirrorNotice.MIGRATED)
            }
            sp.edit().putBoolean(K_MIGRATED, true).apply()
        }

        /**
         * 맞추기 전에(작업 스레드, 지문 없이): 기기 열쇠가 무효(새 지문·화면 잠금을 없앰)면 모든 사본을 지우고 새 열쇠로,
         * 열쇠가 없으면 만든다(사본이 있는데 열쇠가 없어졌으면 그것도 지운다). 화면 잠금이 없으면 mirror_setup.
         */
        fun prepareKeys(ctx: Context) {
            migrate(ctx)
            val k = DeviceMirrorKeys.also { it.init(ctx) }
            when (k.state()) {
                DeviceMirrorKeys.State.INVALID -> resetAll(ctx)
                DeviceMirrorKeys.State.NONE -> if (anyCopies(ctx)) resetAll(ctx)
                DeviceMirrorKeys.State.LOCKED, DeviceMirrorKeys.State.OPEN -> return
            }
            k.ensure()
        }

        private fun anyCopies(ctx: Context): Boolean =
            root(ctx).listFiles().orEmpty().any { File(it, LEDGER).isFile } || oldRoot(ctx).listFiles().orEmpty().isNotEmpty()
    }
}
