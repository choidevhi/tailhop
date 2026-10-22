package com.choidev.tailhop

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.PublicKey
import java.security.spec.MGF1ParameterSpec
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

/*
 * 사본 잠금(docs/PROTOCOL.md §19.3, 2.3.1)과 허브가 바뀌었을 때·허브로 복원(§19.5)의 형식과 계산. 안드로이드 의존성 없음(JVM 단위 테스트).
 * - 쓰기(맞추기)는 공개키로만 잠근다: 지문·화면 잠금 없이 뒤에서 계속 받는다(누구나 쓸 수 있지만 아무도 못 읽음).
 * - 읽기(화면·복원)는 개인키가 있어야 한다: Android는 Keystore RSA 개인키(강한 생체나 화면 잠금 뒤 5분).
 * - 받는 동안만 필요한 것(버전·객체 이름·경로의 열쇠 붙은 해시, 받다 만 파일의 열쇠)은 인증 없이 여는 "장부"에 둔다.
 */

/** 사본 열쇠 제공자. Android는 DeviceMirrorKeys(Keystore), JVM 테스트는 소프트웨어 열쇠(잠금을 흉내 낸다). */
interface MirrorKeys {
    /** 장부를 잠그는 열쇠(인증 없이 쓰고 읽는다). */
    val ledger: SealKey

    /** 데이터 열쇠(32바이트)를 공개키로 감싼다. 인증이 필요 없다. */
    fun wrap(key: ByteArray): ByteArray

    /** 감싼 열쇠를 개인키로 푼다. 잠겨 있으면 mirror_locked, 감싼 것이 틀리면 mirror_damaged. */
    fun unwrap(wrapped: ByteArray): ByteArray
}

/**
 * RSA-OAEP(SHA-256, MGF1 SHA-1). Android Keystore의 RSA 개인키는 OAEP의 MGF1을 SHA-1로만 확실히 푼다(API 34 전에는 바꿀 수 없음).
 * 감싸기는 공개키(소프트웨어 사본)로 이 앱 안에서 계산한다 — 인증이 필요 없다.
 */
object RsaOaep {
    const val TRANSFORM = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"
    val SPEC: OAEPParameterSpec = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)

    fun encrypt(pub: PublicKey, plain: ByteArray): ByteArray {
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, pub, SPEC)
        return c.doFinal(plain)
    }
}

/**
 * 공개키로 잠그기(하이브리드): 머리(magic 4 ‖ 감싼 열쇠 길이 2, big-endian ‖ 감싼 열쇠) 뒤에 nonce(12) ‖ AES-256-GCM(데이터 열쇠, 평문, aad).
 * 데이터 열쇠는 32바이트 난수이고 잠근 뒤 메모리에서 지운다 — 쓴 쪽도 개인키 없이는 다시 읽지 못한다.
 */
object MirrorSeal {
    val MAGIC: ByteArray = "THW1".toByteArray(Charsets.US_ASCII)

    fun head(wrapped: ByteArray, magic: ByteArray = MAGIC): ByteArray {
        require(wrapped.size in 1..0xFFFF && magic.size == 4)
        return magic + byteArrayOf((wrapped.size shr 8).toByte(), wrapped.size.toByte()) + wrapped
    }

    /** 머리를 읽는다: (감싼 열쇠, 머리 뒤 자리). 모양이 틀리면 mirror_damaged. */
    fun readHead(b: ByteArray, magic: ByteArray = MAGIC): Pair<ByteArray, Int> {
        if (b.size < 6 || !b.copyOfRange(0, 4).contentEquals(magic)) throw ProtocolException("mirror_damaged")
        val n = ((b[4].toInt() and 0xff) shl 8) or (b[5].toInt() and 0xff)
        if (n == 0 || b.size < 6 + n) throw ProtocolException("mirror_damaged")
        return b.copyOfRange(6, 6 + n) to 6 + n
    }

    fun seal(keys: MirrorKeys, plain: ByteArray, aad: ByteArray): ByteArray {
        val dk = Protocol.randomBytes(32)
        try {
            return head(keys.wrap(dk)) + AesSealKey(dk).seal(plain, aad)
        } finally { dk.fill(0) }
    }

    /** 잠겨 있으면 mirror_locked, 열쇠·aad·내용이 틀리거나 바뀌었으면 mirror_damaged. */
    fun open(keys: MirrorKeys, sealed: ByteArray, aad: ByteArray): ByteArray {
        val (w, at) = readHead(sealed)
        val dk = keys.unwrap(w)
        try {
            if (dk.size != 32) throw ProtocolException("mirror_damaged")
            return AesSealKey(dk).open(sealed.copyOfRange(at, sealed.size), aad)
        } finally { dk.fill(0) }
    }
}

/**
 * 장부의 한 줄(인증 없이 읽는 것만): 허브 id·폴더인지·ih(경로·크기·수정 시각의 열쇠 붙은 해시)·ver·tver와 이 폰의 객체 이름.
 * 경로·이름·크기는 장부에 두지 않는다(공개키로 잠근 상자에만). 파일 줄은 다 받은 것만 있다(obj가 있음).
 */
data class LedgerItem(
    val id: String, val dir: Boolean, val ih: String,
    val ver: String = "", val tver: String? = null, val obj: String? = null, val tobj: String? = null,
) {
    fun json(): JSONObject {
        val o = JSONObject().put("id", id).put("d", dir).put("ih", ih)
        if (dir) return o
        o.put("ver", ver)
        obj?.let { o.put("obj", it) }
        tver?.let { o.put("tver", it) }
        tobj?.let { o.put("tobj", it) }
        return o
    }

    companion object {
        private val HEX32 = Regex("[0-9a-f]{32}")
        private val IH = Regex("[0-9a-f]{0,32}")

        /** 틀린 줄은 null(이 폰이 쓴 것이지만 형식은 다시 본다 — 객체 이름은 32자 hex만). */
        fun parse(v: Any?): LedgerItem? {
            val o = v as? JSONObject ?: return null
            val id = (o.opt("id") as? String)?.takeIf { HEX32.matches(it) } ?: return null
            val dir = o.opt("d") as? Boolean ?: return null
            val ih = (o.opt("ih") as? String)?.takeIf { IH.matches(it) } ?: return null
            if (dir) return LedgerItem(id, true, ih)
            fun hex(k: String) = (o.opt(k) as? String)?.takeIf { HEX32.matches(it) }
            val ver = hex("ver") ?: return null
            val obj = hex("obj") ?: return null
            val tobj = hex("tobj")
            return LedgerItem(id, false, ih, ver, hex("tver")?.takeIf { tobj != null }, obj, tobj?.takeIf { hex("tver") != null })
        }
    }
}

/**
 * 사본 하나의 장부(§19.3 2.3.1). 인증이 필요 없는 열쇠(Android는 Keystore AES)로 통째로 잠가 ledger.bin에 둔다.
 * mid: 이 사본의 고유 번호(상자 조각의 aad), hkey: ih를 만드는 HMAC 열쇠, storeId: 허브 저장소 번호(§19.5),
 * hub·archivedAt: 이전 사본이 된 허브 이름과 그 시각, digest: 마지막으로 다 맞춘 요약값.
 * 받다 만 파일 하나(MirrorPart, 그 파일 열쇠는 받는 동안만)는 같은 열쇠로 잠근 작은 part.bin에 따로 둔다(큰 장부를 파일마다 다시 쓰지 않게).
 * §19.6: vrows = 잠긴 폴더 안의 줄(허브가 보낸 meta 암호문·객체 크기 그대로 — 그 폴더의 PIN 없이는 아무도 못 읽는다, 경로는 두지 않는다),
 * vfail = 잠긴 폴더마다 PIN을 틀린 횟수와 기다릴 때까지의 시각(앱을 껐다 켜도 이어진다).
 */
class Ledger(
    val mid: String,
    private val hkey: ByteArray,
    var storeId: String? = null,
    var hub: String? = null,
    var archivedAt: Long = 0,
    var digest: String? = null,
    val items: LinkedHashMap<String, LedgerItem> = LinkedHashMap(),
    val vrows: LinkedHashMap<String, SyncManifest.VaultRow> = LinkedHashMap(),
    val vfail: HashMap<String, Pair<Int, Long>> = HashMap(),
) {
    /** 경로(폴더) 또는 경로·크기·수정 시각(파일)의 열쇠 붙은 해시: HMAC-SHA256 앞 16바이트. 이 사본 안에서만 같은 값. */
    fun ih(dir: Boolean, path: String, size: Long, mtime: Long): String {
        val text = if (dir) "d\u0000$path" else "f\u0000$path\u0000$size\u0000$mtime"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(hkey, "HmacSHA256"))
        return Xfer.hex(mac.doFinal(text.toByteArray(Charsets.UTF_8)).copyOf(16))
    }

    fun ih(w: SyncManifest.Item): String = w.vault?.let { ihVault(w.path, it) } ?: ih(w.dir, w.path, w.size, w.mtime)

    /** 잠긴 폴더(§19.6): 봉인이 바뀌면(PIN을 바꿈) 다시 써야 하니 봉인도 넣는다. */
    fun ihVault(path: String, v: SyncManifest.VaultLine): String =
        ih(true, "v\u0000$path\u0000${v.ring}\u0000${v.pin}\u0000${v.recovery}\u0000${v.pinAd}\u0000${v.recoveryAd}", 0, 0)

    /** 이 장부가 쓰는 객체 파일 이름들(남은 것 정리에). */
    fun objects(): Set<String> {
        val out = HashSet<String>()
        for (e in items.values) { e.obj?.let { out += it }; e.tobj?.let { out += it } }
        return out
    }

    /** 다 받은 파일 수. */
    fun files(): Int = items.values.count { !it.dir && it.obj != null }

    fun encode(): ByteArray {
        val arr = JSONArray()
        items.values.forEach { arr.put(it.json()) }
        val o = JSONObject().put("v", V).put("mid", mid).put("hk", Protocol.b64uEncode(hkey)).put("items", arr)
        storeId?.let { o.put("store", it) }
        hub?.let { o.put("hub", it) }
        if (archivedAt > 0) o.put("arch", archivedAt)
        digest?.let { o.put("digest", it) }
        if (vrows.isNotEmpty()) o.put("vrows", JSONArray().also { a -> vrows.values.forEach { a.put(it.json()) } })
        if (vfail.isNotEmpty()) o.put("vfail", JSONObject().also { f -> vfail.forEach { (k, v) -> f.put(k, JSONArray().put(v.first).put(v.second)) } })
        return o.toString().toByteArray(Charsets.UTF_8)
    }

    companion object {
        const val V = 2
        const val HUB_MAX = 200
        private val HEX32 = Regex("[0-9a-f]{32}")

        fun fresh(): Ledger = Ledger(Protocol.newId(), Protocol.randomBytes(32))

        /** 형식이 틀리면 mirror_damaged(줄 하나가 틀리면 그 줄만 버린다). */
        fun parse(raw: ByteArray): Ledger {
            val o = try { JSONObject(String(raw, Charsets.UTF_8)) } catch (e: Exception) { throw ProtocolException("mirror_damaged") }
            if (Xfer.jsonLong(o.opt("v")) != V.toLong()) throw ProtocolException("mirror_damaged")
            val mid = (o.opt("mid") as? String)?.takeIf { HEX32.matches(it) } ?: throw ProtocolException("mirror_damaged")
            val hk = (o.opt("hk") as? String)?.let { Protocol.b64uDecode(it) }?.takeIf { it.size == 32 } ?: throw ProtocolException("mirror_damaged")
            val arr = o.opt("items") as? JSONArray ?: throw ProtocolException("mirror_damaged")
            val out = Ledger(mid, hk,
                storeId = MirrorIdentity.parse(o.opt("store")),
                hub = (o.opt("hub") as? String)?.take(HUB_MAX),
                archivedAt = Xfer.jsonLong(o.opt("arch"))?.takeIf { it > 0 } ?: 0,
                digest = SyncDigest.parse(o.opt("digest")))
            for (i in 0 until arr.length()) LedgerItem.parse(arr.opt(i))?.let { out.items[it.id] = it }
            (o.opt("vrows") as? JSONArray)?.let { a ->
                for (i in 0 until a.length()) {
                    val r = a.opt(i) as? JSONObject ?: continue
                    val vault = (r.opt("vault") as? String)?.takeIf { HEX32.matches(it) } ?: continue
                    SyncManifest.VaultRow.parse(r, vault, "")?.let { out.vrows[it.row] = it }
                }
            }
            (o.opt("vfail") as? JSONObject)?.let { f ->
                for (k in f.keys()) {
                    val v = f.opt(k) as? JSONArray ?: continue
                    val n = Xfer.jsonLong(v.opt(0))?.takeIf { it in 0..1000 } ?: continue
                    val until = Xfer.jsonLong(v.opt(1)) ?: continue
                    if (HEX32.matches(k)) out.vfail[k] = n.toInt() to until
                }
            }
            return out
        }
    }
}

/** 허브 저장소 번호(§19.5)로 사본을 어떻게 할지. */
object MirrorIdentity {
    enum class Decision {
        /** 같은 저장소(또는 둘 다 번호가 없는 예전 허브): 그대로 맞춘다. */
        SAME,
        /** 사본에 번호가 없다(새 사본·예전 허브 때 만든 사본): 허브의 번호를 기억하고 맞춘다. */
        ADOPT,
        /** 번호가 다른데 사본이 비어 있다: 지우고 새 번호로. */
        FRESH,
        /** 번호가 다르다: 지우지도 덮지도 않고 이전 사본으로 옮긴 뒤 새 사본을 시작한다. */
        ARCHIVE,
        /** 사본에는 번호가 있는데 허브가 번호를 알리지 않는다(저장소 없음·예전 허브): 아무것도 바꾸지 않는다. */
        HOLD,
    }

    private val RE = Regex("[0-9a-f]{32}")

    /** hello의 "store_id": 32자 소문자 hex만. */
    fun parse(v: Any?): String? = (v as? String)?.takeIf { RE.matches(it) }

    fun decide(mine: String?, hub: String?, hasContent: Boolean): Decision = when {
        hub == null -> if (mine == null) Decision.SAME else Decision.HOLD
        mine == null -> Decision.ADOPT
        mine == hub -> Decision.SAME
        hasContent -> Decision.ARCHIVE
        else -> Decision.FRESH
    }

    /** "저장소가 열렸는데 대상이 없으면 사본을 지운다"(§19.2)를 해도 되는지: 같은 저장소로 볼 때만. */
    fun mayWipe(d: Decision): Boolean = d == Decision.SAME || d == Decision.ADOPT
}

/** 허브로 복원(§19.5)의 계획: 올릴 파일(경로순), 파일 없이 남은 폴더(만들기만), 다 올린 뒤 sync_set on 할 맨 위 항목. */
object MirrorRestore {
    data class Plan(val files: List<MirrorEntry>, val dirs: List<String>, val tops: List<String>) {
        val bytes: Long get() = files.sumOf { it.size }
    }

    fun plan(all: Collection<MirrorEntry>): Plan {
        // 잠긴 폴더(§19.6)와 그 안은 올리지 않는다(평문 폴더로 올라가 잠금이 풀린 채가 된다)
        val entries = all.filter { !it.vault && it.raw == null }
        val files = entries.filter { !it.dir && it.complete }.sortedBy { it.path }
        val withFiles = HashSet<String>()
        for (f in files) {
            var p = CloudPath.parent(f.path)
            while (p.isNotEmpty() && withFiles.add(p)) p = CloudPath.parent(p)
        }
        // 폴더는 위에서부터(경로순이면 위 폴더가 먼저) — 허브가 중간 폴더를 만들지 않아도 차례로 만든다
        val dirs = entries.filter { it.dir && it.path !in withFiles }.map { it.path }.distinct().sorted()
        val tops = LinkedHashSet<String>()
        for (f in files) tops += top(f.path, null)
        for (d in dirs) tops += top(d, null)
        return Plan(files, dirs, tops.toList())
    }

    /**
     * 올린 뒤 그 항목의 맨 위 이름: 폴더 안이면 맨 위 폴더, 맨 위 파일이면 허브가 저장한 이름(같은 이름이 있어 번호가 붙었을 수 있다).
     * 허브가 준 이름이 §12.1 조각 규칙에 맞지 않으면 원래 이름.
     */
    fun top(path: String, stored: String?): String {
        val slash = path.indexOf('/')
        if (slash >= 0) return path.substring(0, slash)
        return stored?.takeIf { CloudPath.isSegment(it) } ?: path
    }
}

/** 이전 사본(§19.5): 허브의 저장소가 바뀌었거나 페어링을 끊었을 때 사본 폴더를 옮겨 둔 것. 장부만 읽는다(지문 없이). */
object MirrorArchive {
    data class Info(
        val id: String, val hub: String?, val storeId: String?, val at: Long, val files: Int, val bytes: Long, val readable: Boolean,
    )

    private val ID = Regex("[0-9a-f]{32}")

    fun isId(s: String): Boolean = ID.matches(s)

    /**
     * dir의 사본을 oldRoot/<32자 hex>로 옮기고 장부에 허브 이름·시각을 남긴다. 담은 것이 없거나 장부를 풀 수 없으면 지우고 null.
     * 옮긴 자리를 돌려준다(이름을 바꾸기만 한다 — 다시 쓰지 않는다).
     */
    fun move(dir: File, oldRoot: File, keys: MirrorKeys, hub: String?, now: Long): File? {
        val l = Mirror.readLedger(dir, keys)
        if (l == null || l.items.isEmpty()) { dir.deleteRecursively(); return null }
        l.hub = hub?.take(Ledger.HUB_MAX) ?: l.hub
        l.archivedAt = now
        Mirror.writeLedger(dir, keys, l)
        oldRoot.mkdirs()
        var target = File(oldRoot, Protocol.newId())
        while (target.exists()) target = File(oldRoot, Protocol.newId())
        if (!dir.renameTo(target)) throw java.io.IOException("rename")
        return target
    }

    /** 이전 사본 목록(새것 먼저). 장부를 풀 수 없는 것도 지울 수 있게 readable=false로 넣는다. */
    fun list(oldRoot: File, keys: MirrorKeys): List<Info> {
        val out = ArrayList<Info>()
        for (d in oldRoot.listFiles().orEmpty()) {
            if (!d.isDirectory || !isId(d.name)) continue
            val l = Mirror.readLedger(d, keys)
            val bytes = d.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            out += if (l == null) Info(d.name, null, null, d.lastModified(), 0, bytes, false)
            else Info(d.name, l.hub, l.storeId, l.archivedAt.takeIf { it > 0 } ?: d.lastModified(), l.files(), bytes, true)
        }
        return out.sortedByDescending { it.at }
    }
}
