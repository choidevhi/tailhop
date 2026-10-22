package com.choidev.tailhop

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 모든 기기 동기화·기기 용량(docs/PROTOCOL.md §19, 2.3.0)의 형식과 계산. 안드로이드 의존성 없음(JVM 단위 테스트).
 * 이 폰은 기기 쪽이다: 허브(PC)의 sync_set·sync_manifest를 부르기만 하고 직접 답하지 않는다.
 */

/** 기기 용량(§19.1): 남은·전체 바이트. */
data class DeviceSpace(val free: Long, val total: Long) {
    fun json(): JSONObject = JSONObject().put("free", free).put("total", total)

    companion object {
        /** hello의 "space": free·total이 모두 0 이상 JSON 정수(bool·실수 아님)인 객체만. 아니면 null(무시한다). */
        fun parse(v: Any?): DeviceSpace? {
            val o = v as? JSONObject ?: return null
            val free = Xfer.jsonLong(o.opt("free")) ?: return null
            val total = Xfer.jsonLong(o.opt("total")) ?: return null
            if (free < 0 || total < 0) return null
            return DeviceSpace(free, total)
        }
    }
}

/**
 * 기기마다 마지막으로 받은 용량과 받은 시각(§19.1). 이 프로세스의 메모리에만 둔다(디스크에 쓰지 않는다).
 * 키는 이 폰이 PC를 알아보는 값(PC IP). version은 바뀔 때마다 늘어 화면이 다시 그릴지 안다.
 */
class PeerSpaces {
    data class Seen(val space: DeviceSpace, val at: Long)

    private val map = HashMap<String, Seen>()

    @Volatile var version = 0
        private set

    @Synchronized fun put(dev: String, space: DeviceSpace?, now: Long = System.currentTimeMillis()) {
        if (space == null) return
        if (map[dev]?.space != space) version++
        map[dev] = Seen(space, now)
    }

    @Synchronized fun get(dev: String): Seen? = map[dev]

    @Synchronized fun forget(dev: String) { if (map.remove(dev) != null) version++ }

    companion object {
        /** 이 프로세스의 기록(받기 서비스·hello·화면이 같이 쓴다). */
        val shared = PeerSpaces()
    }
}

/** hello의 "sync"(§19.2): 대상 목록의 요약값(16자 소문자 hex). */
object SyncDigest {
    private val RE = Regex("[0-9a-f]{16}")

    fun parse(v: Any?): String? = (v as? String)?.takeIf { RE.matches(it) }
}

/** 동기화 대상 바꾸기(§19.2 sync_set). 허브가 hello caps에 "sync"를 알릴 때만 쓴다. */
object SyncSet {
    const val MAX_PATHS = 500
    private val CODE_RE = Regex("[a-z_]{1,40}")

    /** 결과: 바뀐 수와 안 된 것(경로, 오류 코드 — sync_vault 등). */
    data class Result(val changed: Int, val failed: List<MoveResult.Failed>) {
        operator fun plus(o: Result) = Result(changed + o.changed, failed + o.failed)
    }

    /** 요청 필드(paths 1–500개, 빈 경로가 아닌 §12.1 규칙의 경로). 어기면 IllegalArgumentException(부른 쪽의 실수). */
    fun fields(paths: List<String>, on: Boolean): JSONObject {
        require(paths.size in 1..MAX_PATHS && paths.all { it.isNotEmpty() && CloudPath.isValid(it) })
        return JSONObject().put("paths", JSONArray(paths)).put("on", on)
    }

    /** 저장소 전체 설정(sync_all). */
    fun allFields(on: Boolean): JSONObject = JSONObject().put("all", on)

    /** 응답: changed는 0 이상 정수여야 한다(아니면 bad_reply). failed는 선택이고 모양이 틀린 줄은 버린다. */
    fun parse(reply: JSONObject): Result {
        val changed = Xfer.jsonLong(reply.opt("changed"))?.takeIf { it in 0..Int.MAX_VALUE } ?: throw ProtocolException("bad_reply")
        val arr = reply.opt("failed") as? JSONArray
        val failed = ArrayList<MoveResult.Failed>()
        if (arr != null) for (i in 0 until arr.length()) {
            val o = arr.opt(i) as? JSONObject ?: continue
            val path = (o.opt("path") as? String)?.takeIf { it.isNotEmpty() && CloudPath.isValid(it) } ?: continue
            val code = (o.opt("code") as? String)?.takeIf { CODE_RE.matches(it) } ?: continue
            failed += MoveResult.Failed(path, code)
        }
        return Result(changed.toInt(), failed)
    }

    /**
     * MAX_PATHS개씩 나눠 보내고 결과를 합친다(같은 경로는 한 번만). 첫 묶음이 통째로 거부되면 그 예외를 던진다(아무것도 바꾸지 않았다).
     * 뒤 묶음이 통째로 거부되면 앞의 결과는 살리고 그 묶음을 그 코드로 failed에 넣는다(옮기기·이름 바꾸기와 같다).
     */
    fun run(paths: List<String>, send: (List<String>) -> Result): Result {
        var out = Result(0, emptyList())
        paths.distinct().chunked(MAX_PATHS).forEachIndexed { i, chunk ->
            out += try { send(chunk) } catch (e: ProtocolException) {
                if (i == 0) throw e
                Result(0, chunk.map { MoveResult.Failed(it, e.code) })
            }
        }
        return out
    }
}

/** 허브의 동기화 목록(§19.2 sync_manifest). 받은 것은 모두 다시 검사한다(허브가 걸러야 하지만 한 번 더). */
object SyncManifest {
    /** 허브가 한 쪽에 담는 최대 항목 수. 이보다 많으면 형식 위반(bad_reply). */
    const val MAX_PAGE = 2000
    /** 한 허브에서 받아들이는 최대 항목 수(메모리 한도). 넘으면 bad_reply. */
    const val MAX_ITEMS = 500_000
    /** 미리보기(§17.4 thumb, 긴 변 512 JPEG) 크기 한도. 넘으면 미리보기 없이 받는다(PC mirror.py와 같다). */
    const val THUMB_MAX = 2L * 1024 * 1024
    /** 쪽 사이에 요약값이 바뀌어 처음부터 다시 받는 최대 횟수(그래도 바뀌면 다음 맞추기로 미룬다). */
    const val MAX_RESTARTS = 3

    private val ID_RE = Regex("[0-9a-f]{32}")

    /**
     * 목록의 한 줄. 폴더면 size·mtime·ver 없음. thumb·tver는 허브 미리보기가 있을 때만(없으면 0·null).
     * vault: 잠긴 폴더 줄(§19.6)의 봉인들 — 그 폴더 안은 따로 VaultRow로 온다.
     */
    data class Item(
        val id: String, val path: String, val dir: Boolean,
        val size: Long = 0, val mtime: Long = 0, val ver: String = "", val thumb: Long = 0, val tver: String? = null,
        val vault: VaultLine? = null,
    )

    /** 잠긴 폴더 줄의 봉인(§19.6): ring, PIN·복구 코드 봉인(§17.1 seal_secret JSON 문자열 그대로), 각 봉인의 AD. */
    data class VaultLine(val ring: String, val pin: String, val recovery: String, val pinAd: String, val recoveryAd: String) {
        fun json(): JSONObject = JSONObject().put("ring", ring).put("pin", JSONObject(pin)).put("recovery", JSONObject(recovery))
            .put("pin_ad", pinAd).put("recovery_ad", recoveryAd)

        companion object {
            /** 모양이 틀리면 null(허브 mirror.py와 같은 검사: ring 32 hex, 봉인의 alg·salt·blob 문자열 4096자 미만·t·m·p 0 이상 정수, AD 300자 이하). */
            fun parse(v: Any?): VaultLine? {
                val o = v as? JSONObject ?: return null
                val ring = (o.opt("ring") as? String)?.takeIf { ID_RE.matches(it) } ?: return null
                val pin = (o.opt("pin") as? JSONObject)?.takeIf { sealOk(it) } ?: return null
                val rec = (o.opt("recovery") as? JSONObject)?.takeIf { sealOk(it) } ?: return null
                val pinAd = (o.opt("pin_ad") as? String)?.takeIf { it.length <= 300 } ?: return null
                val recAd = (o.opt("recovery_ad") as? String)?.takeIf { it.length <= 300 } ?: return null
                return VaultLine(ring, clean(pin).toString(), clean(rec).toString(), pinAd, recAd)
            }

            private fun sealOk(o: JSONObject): Boolean =
                listOf("alg", "salt", "blob").all { (o.opt(it) as? String)?.let { s -> s.length < 4096 } == true } &&
                    listOf("t", "m", "p").all { (Xfer.jsonLong(o.opt(it)) ?: -1) >= 0 }

            /** 아는 필드만 남긴다(모르는 값을 사본에 옮겨 두지 않게). */
            private fun clean(o: JSONObject): JSONObject = JSONObject().put("alg", o.get("alg")).put("t", o.get("t"))
                .put("m", o.get("m")).put("p", o.get("p")).put("salt", o.get("salt")).put("blob", o.get("blob"))
        }
    }

    /** 잠긴 폴더의 객체 하나(§19.6 objs): 허브의 객체 id와 암호문 파일 크기. */
    data class RawObj(val o: String, val s: Long)

    /**
     * 잠긴 폴더 안의 줄 하나(§19.6): vault = 그 잠긴 폴더의 id(목록의 id), path = 그 잠긴 폴더 경로, row·parent·ring(32 hex),
     * meta(그 폴더 열쇠로 잠긴 Tink 암호문, base64url — 받은 그대로 둔다), objs(16개까지).
     */
    data class VaultRow(val vault: String, val path: String, val row: String, val parent: String, val ring: String,
                        val meta: String, val objs: List<RawObj>) {
        fun json(): JSONObject = JSONObject().put("vault", vault).put("row", row).put("parent", parent).put("ring", ring)
            .put("meta", meta).put("objs", JSONArray().also { a -> objs.forEach { a.put(JSONObject().put("o", it.o).put("s", it.s)) } })

        companion object {
            const val META_MAX = 65536
            const val OBJS_MAX = 16
            /** 잠긴 폴더 객체 암호문의 최대 크기(평문 4 GiB + 청크마다 덧붙는 것, 허브와 같다). */
            const val RAW_MAX = Protocol.MAX_FILE + (Protocol.MAX_FILE shr 10)

            /** 목록 줄(path는 이미 검사함) 또는 사본에 둔 줄(vault 필드)을 읽는다. 틀리면 null. */
            fun parse(o: JSONObject, vault: String, path: String): VaultRow? {
                fun hex(k: String) = (o.opt(k) as? String)?.takeIf { ID_RE.matches(it) }
                val row = hex("row") ?: return null
                val parent = hex("parent") ?: return null
                val ring = hex("ring") ?: return null
                val meta = (o.opt("meta") as? String)?.takeIf { it.length in 1..META_MAX && Protocol.b64uDecode(it) != null } ?: return null
                val arr = o.opt("objs") as? JSONArray ?: return null
                if (arr.length() > OBJS_MAX) return null
                val objs = ArrayList<RawObj>()
                for (i in 0 until arr.length()) {
                    val x = arr.opt(i) as? JSONObject ?: return null
                    val oid = (x.opt("o") as? String)?.takeIf { ID_RE.matches(it) } ?: return null
                    val s = Xfer.jsonLong(x.opt("s"))?.takeIf { it in 0..RAW_MAX } ?: return null
                    objs += RawObj(oid, s)
                }
                return VaultRow(vault, path, row, parent, ring, meta, objs)
            }
        }
    }

    data class Page(val digest: String, val items: List<Item>, val next: Int?, val rows: List<VaultRow> = emptyList())

    fun fields(cursor: Int): JSONObject {
        require(cursor >= 0)
        return JSONObject().put("cursor", cursor)
    }

    /**
     * 한 줄 검사(맞지 않으면 null — 그 줄만 버린다): id는 32자 hex, path는 빈 경로가 아닌 §12.1 규칙의 경로이고 모든 조각이
     * 목록에 보이는 이름, 파일은 size 0–4 GiB·mtime 0 이상 정수·ver 32자 hex. thumb·tver는 둘 다 맞을 때만(미리보기 한도 안).
     */
    fun item(v: Any?): Item? {
        val o = v as? JSONObject ?: return null
        val id = (o.opt("id") as? String)?.takeIf { ID_RE.matches(it) } ?: return null
        val path = (o.opt("path") as? String)?.takeIf { it.isNotEmpty() && CloudPath.isVisiblePath(it) } ?: return null
        val dir = o.opt("dir") as? Boolean ?: return null
        if (dir && o.has("vault")) return VaultLine.parse(o.opt("vault"))?.let { Item(id, path, true, vault = it) }
        if (dir) return Item(id, path, true)
        val size = Xfer.jsonLong(o.opt("size"))?.takeIf { it in 0..Protocol.MAX_FILE } ?: return null
        val mtime = Xfer.jsonLong(o.opt("mtime"))?.takeIf { it >= 0 } ?: return null
        val ver = (o.opt("ver") as? String)?.takeIf { ID_RE.matches(it) } ?: return null
        val thumb = Xfer.jsonLong(o.opt("thumb"))
        val tver = (o.opt("tver") as? String)?.takeIf { ID_RE.matches(it) }
        val hasThumb = thumb != null && thumb in 1..THUMB_MAX && tver != null
        return Item(id, path, false, size, mtime, ver, if (hasThumb) thumb!! else 0, if (hasThumb) tver else null)
    }

    /**
     * 한 쪽: digest는 16자 hex, items는 배열(MAX_PAGE개까지), next는 null 또는 1 이상 정수여야 한다(아니면 bad_reply).
     * 모양이 틀린 줄은 버린다.
     */
    fun parse(reply: JSONObject): Page {
        val digest = SyncDigest.parse(reply.opt("digest")) ?: throw ProtocolException("bad_reply")
        val arr = reply.opt("items") as? JSONArray ?: throw ProtocolException("bad_reply")
        if (arr.length() > MAX_PAGE) throw ProtocolException("bad_reply")
        val items = ArrayList<Item>(arr.length())
        val rows = ArrayList<VaultRow>()
        for (i in 0 until arr.length()) {
            val o = arr.opt(i) as? JSONObject
            if (o != null && o.has("row")) row(o)?.let { rows += it } else item(o)?.let { items += it }
        }
        val nextRaw = Xfer.field(reply, "next")
        val next = if (nextRaw == null) null else Xfer.jsonLong(nextRaw)?.takeIf { it in 1..Int.MAX_VALUE }?.toInt()
            ?: throw ProtocolException("bad_reply")
        return Page(digest, items, next, rows)
    }

    /** 잠긴 폴더 안의 줄(§19.6): id(그 잠긴 폴더)·path도 보통 줄처럼 검사한다. 틀리면 null(그 줄만 버린다). */
    fun row(o: JSONObject): VaultRow? {
        val id = (o.opt("id") as? String)?.takeIf { ID_RE.matches(it) } ?: return null
        val path = (o.opt("path") as? String)?.takeIf { it.isNotEmpty() && CloudPath.isVisiblePath(it) } ?: return null
        return VaultRow.parse(o, id, path)
    }

    /** 끝까지 받은 목록: 요약값과 항목(같은 id·같은 경로는 처음 것만, 받은 순서 = 경로 순서), 잠긴 폴더 안의 줄(같은 row는 처음 것만). */
    data class Full(val digest: String, val items: List<Item>, val rows: List<VaultRow> = emptyList())

    /**
     * 모든 쪽을 받는다(fetch(cursor)). 쪽 사이에 digest가 바뀌면 처음(cursor 0)부터 다시(MAX_RESTARTS번까지, 넘으면 cloud_changed —
     * 허브가 계속 바뀌는 중이니 다음 맞추기로). next가 지금 cursor보다 크지 않으면 bad_reply(같은 쪽을 맴돌지 않게).
     */
    fun collect(fetch: (Int) -> Page, stop: () -> Boolean = { false }): Full {
        var restarts = 0
        var cursor = 0
        var digest: String? = null
        val items = ArrayList<Item>()
        val rows = ArrayList<VaultRow>()
        while (true) {
            if (stop()) throw ProtocolException("disconnected")
            val page = fetch(cursor)
            if (digest != null && page.digest != digest) {
                if (++restarts > MAX_RESTARTS) throw ProtocolException("cloud_changed")
                digest = null
                cursor = 0
                items.clear()
                rows.clear()
                continue
            }
            digest = page.digest
            items += page.items
            rows += page.rows
            if (items.size + rows.size > MAX_ITEMS) throw ProtocolException("bad_reply")
            val next = page.next ?: break
            if (next <= cursor) throw ProtocolException("bad_reply")
            cursor = next
        }
        val ids = HashSet<String>()
        val paths = HashSet<String>()
        val unique = items.filter { ids.add(it.id) && paths.add(it.path) }
        // 안의 줄은 목록에 있는 잠긴 폴더의 것만(같은 row는 처음 것만)
        val vaults = unique.filter { it.vault != null }.associate { it.id to it.path }
        val seen = HashSet<String>()
        val inside = rows.filter { vaults[it.vault] == it.path && seen.add(it.row) }
        return Full(digest!!, unique, inside)
    }
}

/**
 * 사본의 한 항목(§19.3): 개인키로 푼 목록(메모리에만)의 한 줄. id·ver·tver는 허브 값, obj·tobj는 이 폰의 객체 파일 이름(32자 hex).
 * 열쇠는 여기에 두지 않는다(Mirror가 따로 들고 잠그면 지운다). 파일은 obj가 있어야 다 받은 것이다.
 * §19.6: vault = 잠긴 폴더 자체(sealed = 아직 PIN으로 열지 않음), raw = 잠긴 폴더 안의 항목이면 그 잠긴 폴더 id
 * (id는 허브의 row, obj·tobj는 허브의 객체 id — 받은 암호문 그대로라 그 폴더 열쇠로 푼다).
 */
data class MirrorEntry(
    val id: String, val path: String, val dir: Boolean,
    val size: Long = 0, val mtime: Long = 0, val ver: String = "", val tver: String? = null,
    val obj: String? = null, val tobj: String? = null,
    val vault: Boolean = false, val sealed: Boolean = false, val raw: String? = null,
) {
    val complete: Boolean get() = dir || obj != null
}

/**
 * 받다 만 파일 하나(§19.3 "받다 끊기면 다 쓴 청크부터 이어 받는다"). 같은 id·ver·size·mtime일 때만 이어 받는다.
 * fkey는 그 파일 열쇠(base64url)이고 장부(인증 없이 여는 열쇠로 잠김)에만, 받는 동안만 둔다(§19.3 2.3.1).
 */
data class MirrorPart(val id: String, val ver: String, val size: Long, val mtime: Long, val obj: String, val fkey: String) {
    fun json(): JSONObject = JSONObject().put("id", id).put("ver", ver).put("size", size).put("mtime", mtime)
        .put("obj", obj).put("fkey", fkey)

    fun encode(): ByteArray = json().toString().toByteArray(Charsets.UTF_8)

    fun matches(want: SyncManifest.Item): Boolean = id == want.id && ver == want.ver && size == want.size && mtime == want.mtime

    companion object {
        private val HEX32 = Regex("[0-9a-f]{32}")

        fun parse(raw: ByteArray): MirrorPart? = try { fromJson(JSONObject(String(raw, Charsets.UTF_8))) } catch (e: Exception) { null }

        fun fromJson(o: JSONObject): MirrorPart? {
            val id = (o.opt("id") as? String)?.takeIf { HEX32.matches(it) }
            val ver = (o.opt("ver") as? String)?.takeIf { HEX32.matches(it) }
            val obj = (o.opt("obj") as? String)?.takeIf { HEX32.matches(it) }
            val fkey = (o.opt("fkey") as? String)?.takeIf { Protocol.b64uDecode(it)?.size == 32 }
            val size = Xfer.jsonLong(o.opt("size"))?.takeIf { it in 0..Protocol.MAX_FILE }
            val mtime = Xfer.jsonLong(o.opt("mtime"))
            return if (id == null || ver == null || obj == null || fkey == null || size == null || mtime == null) null
            else MirrorPart(id, ver, size, mtime, obj, fkey)
        }
    }
}

/**
 * 맞추기 계획(§19.3): 받을 것(없거나 ver가 다름), 경로·정보만 고칠 것(같은 id·ver), 지울 것(목록에 없음), 미리보기만 받을 것.
 * 잠긴 채로 세운다(2.3.1): 사본 쪽은 장부(경로 대신 ih = 경로·크기·수정 시각의 열쇠 붙은 해시)만 본다.
 */
object SyncPlan {
    data class Plan(
        val fetch: List<SyncManifest.Item>,
        val moves: List<SyncManifest.Item>,
        val drop: List<String>,
        val thumbs: List<SyncManifest.Item>,
    ) {
        val idle: Boolean get() = fetch.isEmpty() && moves.isEmpty() && drop.isEmpty() && thumbs.isEmpty()
    }

    fun make(have: Map<String, LedgerItem>, want: List<SyncManifest.Item>, ih: (SyncManifest.Item) -> String): Plan {
        val fetch = ArrayList<SyncManifest.Item>()
        val moves = ArrayList<SyncManifest.Item>()
        val thumbs = ArrayList<SyncManifest.Item>()
        val seen = HashSet<String>()
        for (w in want) {
            seen += w.id
            val old = have[w.id]
            if (w.dir) {
                if (old == null || !old.dir || old.ih != ih(w)) moves += w
                continue
            }
            if (old == null || old.dir || old.obj == null || old.ver != w.ver) { fetch += w; continue }
            if (old.ih != ih(w) || (w.tver == null && old.tver != null)) moves += w
            if (w.tver != null && (old.tver != w.tver || old.tobj == null)) thumbs += w
        }
        val drop = have.keys.filter { it !in seen }
        return Plan(fetch, moves, drop, thumbs)
    }

    /** 남은 공간 검사(§19.3): 받을 파일 크기 + 1 GiB보다 적게 남았으면 멈춘다. */
    const val RESERVE = 1L shl 30

    fun enoughSpace(free: Long, size: Long): Boolean = free >= size + RESERVE
}

/** 사본 목록에서 폴더 보기(오프라인 사본, §19.3). 위 폴더는 파일 경로에서도 만든다(대상이 아닌 폴더 안의 대상 파일). */
object MirrorTree {
    /** dir 바로 아래 항목: 다 받은 파일과 폴더(목록의 폴더 + 아래 경로에서 생긴 폴더). 폴더 시각은 안의 가장 새 파일. */
    fun list(entries: Collection<MirrorEntry>, dir: String): List<CloudItem> {
        val prefix = if (dir.isEmpty()) "" else "$dir/"
        val folders = HashMap<String, Long>()
        val sealed = HashSet<String>()
        val items = ArrayList<CloudItem>()
        for (e in entries) {
            if (!e.path.startsWith(prefix) || e.path.length == prefix.length) continue
            val rest = e.path.substring(prefix.length)
            val slash = rest.indexOf('/')
            if (slash < 0) {
                if (e.dir) folders.putIfAbsent(rest, 0L)
                else if (e.complete) items += CloudItem(rest, false, e.size, e.mtime, sync = true)
                // 아직 PIN으로 열지 않은 잠긴 폴더(§19.6): 자물쇠로 보인다
                if (e.vault && e.sealed) sealed += rest
            } else if (e.dir || e.complete) {
                val sub = rest.substring(0, slash)
                folders[sub] = maxOf(folders[sub] ?: 0L, if (e.dir) 0L else e.mtime)
            }
        }
        folders.forEach { (name, t) ->
            items += if (name in sealed) CloudItem(name, true, 0, t, locked = true, state = "locked", sync = true) else CloudItem(name, true, 0, t, sync = true)
        }
        return items.sortedWith(CloudListing.ORDER)
    }

    /** dir 아래(깊이 무관) 이름에 query가 든(대소문자 무시) 다 받은 파일, kind로 거름. path는 dir 기준. */
    fun search(entries: Collection<MirrorEntry>, dir: String, query: String, kind: String): List<CloudItem> {
        val prefix = if (dir.isEmpty()) "" else "$dir/"
        val q = query.lowercase(java.util.Locale.ROOT)
        val out = ArrayList<CloudItem>()
        for (e in entries) {
            if (e.dir || !e.complete || !e.path.startsWith(prefix)) continue
            val name = CloudPath.name(e.path)
            if (q.isNotEmpty() && !name.lowercase(java.util.Locale.ROOT).contains(q)) continue
            if (kind != FileKinds.ALL && FileKinds.of(name) != kind) continue
            out += CloudItem(name, false, e.size, e.mtime, path = e.path.substring(prefix.length), sync = true)
        }
        return out.sortedBy { it.path?.lowercase(java.util.Locale.ROOT) }
    }

    /** 경로의 다 받은 파일. */
    fun find(entries: Collection<MirrorEntry>, path: String): MirrorEntry? =
        entries.firstOrNull { !it.dir && it.complete && it.path == path }

    /** 허브 목록의 그 파일과 같은 것인지(§19.3: 같은 경로·크기·수정 시각이면 사본에서 연다). */
    fun same(e: MirrorEntry?, item: CloudItem): Boolean = e != null && !item.dir && e.size == item.size && e.mtime == item.mtime
}

/**
 * 열쇠 하나로 작은 값을 잠그고 여는 것(§19.3). Android의 장부 열쇠는 인증 없는 Keystore AES(KeystoreAes), 상자 조각·하이브리드의
 * 데이터 열쇠와 JVM 테스트는 평범한 AES 열쇠(AesSealKey). 잠근 모양 = nonce(12) ‖ AES-256-GCM(평문, aad) — 다른 자리에 옮겨 쓰지 못하게 aad에 용도를 넣는다.
 */
interface SealKey {
    fun seal(plain: ByteArray, aad: ByteArray): ByteArray
    /** 열쇠·aad가 틀리거나 바뀌었으면 mirror_damaged. */
    fun open(sealed: ByteArray, aad: ByteArray): ByteArray
}

class AesSealKey(raw: ByteArray) : SealKey {
    private val key = SecretKeySpec(raw.copyOf().also { require(it.size == 32) }, "AES")

    override fun seal(plain: ByteArray, aad: ByteArray): ByteArray {
        val nonce = Protocol.randomBytes(12)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        c.updateAAD(aad)
        return nonce + c.doFinal(plain)
    }

    override fun open(sealed: ByteArray, aad: ByteArray): ByteArray {
        if (sealed.size < 12 + 16) throw ProtocolException("mirror_damaged")
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed, 0, 12))
            c.updateAAD(aad)
            c.doFinal(sealed, 12, sealed.size - 12)
        } catch (e: AEADBadTagException) { throw ProtocolException("mirror_damaged") }
    }
}

/**
 * 사본의 객체 파일(§19.3, PC cloudstore의 청크 형식을 따른 이 폰만의 모양 — PC와 바꿔 읽을 일은 없다).
 * 머리 `"THM1"`(4) ‖ nonce 접두(8, 난수) ‖ 평문 크기(8, big-endian), 그 뒤 1 MiB씩 자른 조각마다
 * AES-256-GCM(파일 열쇠, 평문, nonce = 접두 ‖ 조각 번호(4, big-endian), aad = 조각 번호(4) ‖ 마지막이면 1 아니면 0(1)).
 * 조각 하나 = 평문 + 태그 16. 빈 파일도 빈 마지막 조각 하나가 있다. 조각 i의 자리를 계산해 원하는 위치부터 읽는다(재생 건너뛰기).
 */
object MirrorObject {
    private val MAGIC = byteArrayOf(0x54, 0x48, 0x4d, 0x31) // "THM1"
    const val HEAD = 20
    const val CHUNK = 1024 * 1024
    const val TAG = 16

    fun chunks(size: Long): Long = if (size == 0L) 1 else (size + CHUNK - 1) / CHUNK

    /** 다 쓴 파일의 길이. */
    fun length(size: Long): Long = HEAD + size + chunks(size) * TAG

    private fun nonce(prefix: ByteArray, i: Long): ByteArray =
        ByteBuffer.allocate(12).put(prefix).putInt(i.toInt()).array()

    private fun aad(i: Long, last: Boolean): ByteArray = ByteBuffer.allocate(5).putInt(i.toInt()).put(if (last) 1 else 0).array()

    private fun cipher(mode: Int, key: ByteArray, prefix: ByteArray, i: Long, last: Boolean): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce(prefix, i)))
            updateAAD(aad(i, last))
        }

    private fun readHead(f: RandomAccessFile): Pair<ByteArray, Long>? {
        if (f.length() < HEAD) return null
        val h = ByteArray(HEAD)
        f.seek(0)
        f.readFully(h)
        if (!h.copyOfRange(0, 4).contentEquals(MAGIC)) return null
        val size = ByteBuffer.wrap(h, 12, 8).long
        if (size < 0 || size > Protocol.MAX_FILE) return null
        return h.copyOfRange(4, 12) to size
    }

    /** 조각 i(평문 길이 n)를 연다. 태그가 맞지 않으면 null. */
    private fun openChunk(f: RandomAccessFile, key: ByteArray, prefix: ByteArray, size: Long, i: Long): ByteArray? {
        val last = i == chunks(size) - 1
        val n = if (last) (size - i * CHUNK).toInt() else CHUNK
        val sealed = ByteArray(n + TAG)
        f.seek(HEAD + i * (CHUNK + TAG))
        f.readFully(sealed)
        return try { cipher(Cipher.DECRYPT_MODE, key, prefix, i, last).doFinal(sealed) } catch (e: AEADBadTagException) { null }
    }

    /**
     * 쓰기(받는 대로 1 MiB씩 잠가 붙인다). 파일이 이미 있고 머리가 같은 크기면 이어 쓴다: 다 쓴 조각 뒤를 자르고(마지막으로 쓴 조각이
     * 열리는지도 본다 — 찢긴 쓰기면 한 조각 앞으로) done부터 받으면 된다. 머리가 다르거나 읽을 수 없으면 새로 만든다.
     * 평문은 메모리 버퍼(1 MiB)에만 있고 디스크에는 암호문만 쓴다.
     */
    class Writer(private val file: File, private val key: ByteArray, private val size: Long) : AutoCloseable {
        private val raf: RandomAccessFile
        private val prefix: ByteArray
        private val buf = ByteArray(CHUNK)
        private var bufLen = 0
        private var index: Long
        /** 이미 디스크에 잠가 둔 평문 바이트(이어 받을 위치). */
        val done: Long
        val complete: Boolean

        init {
            require(size in 0..Protocol.MAX_FILE && key.size == 32)
            // 폴더는 만들지 않는다: 사본을 지운 뒤(설정을 끔·연결 해제) 늦게 끝난 받기가 폴더를 되살리지 않게
            raf = RandomAccessFile(file, "rw")
            var resumed: Pair<ByteArray, Long>? = null
            var full = false
            try {
                val head = readHead(raf)
                if (head != null && head.second == size) {
                    val total = chunks(size)
                    val len = raf.length()
                    var k = if (len >= length(size)) total else minOf((len - HEAD) / (CHUNK + TAG), total - 1)
                    // 마지막으로 쓴 조각이 열리는지(찢긴 쓰기·다른 열쇠면 한 조각씩 앞으로)
                    while (k > 0 && openChunk(raf, key, head.first, size, k - 1) == null) k--
                    if (k == total) full = true
                    resumed = head.first to k
                }
            } catch (e: IOException) { resumed = null }
            if (resumed != null) {
                prefix = resumed.first
                index = resumed.second
                complete = full
                done = if (full) size else index * CHUNK
                raf.setLength(if (full) length(size) else HEAD + index * (CHUNK + TAG))
            } else {
                prefix = Protocol.randomBytes(8)
                index = 0
                complete = false
                done = 0
                raf.setLength(0)
                raf.write(MAGIC)
                raf.write(prefix)
                raf.write(ByteBuffer.allocate(8).putLong(size).array())
            }
            raf.seek(raf.length())
        }

        private var written = done

        /** 받은 평문을 붙인다. 알린 크기를 넘으면 file_too_big. */
        fun write(data: ByteArray, off: Int = 0, len: Int = data.size) {
            if (complete) throw ProtocolException("file_too_big")
            if (written + len > size) throw ProtocolException("file_too_big")
            var o = off
            var left = len
            while (left > 0) {
                val n = minOf(left, CHUNK - bufLen)
                System.arraycopy(data, o, buf, bufLen, n)
                bufLen += n
                o += n
                left -= n
                written += n
                // 가득 찬 조각은 뒤에 더 올 것이 있을 때만 바로 잠근다(마지막 조각이면 finish가 last=1로 잠근다).
                if (bufLen == CHUNK && written < size) flushChunk(last = false)
            }
        }

        private fun flushChunk(last: Boolean) {
            val sealed = cipher(Cipher.ENCRYPT_MODE, key, prefix, index, last).doFinal(buf, 0, bufLen)
            raf.write(sealed)
            index++
            bufLen = 0
        }

        /** 끝: 마지막 조각을 잠그고 디스크에 내린다. 받은 것이 알린 크기와 다르면 file_incomplete. */
        fun finish() {
            if (complete) return
            if (written != size) throw ProtocolException("file_incomplete")
            flushChunk(last = true)
            raf.fd.sync()
        }

        /** 지금까지 다 쓴 조각까지만 디스크에 내린다(끊겨도 다음에 이어 받게). 버퍼의 조각은 버린다. */
        override fun close() {
            try { raf.close() } catch (e: IOException) { }
            java.util.Arrays.fill(buf, 0)
        }
    }

    /**
     * 읽기(원하는 위치부터, 조각 하나씩 풀어 메모리에만). 파일 길이가 머리의 크기와 맞지 않거나 태그가 틀리면 mirror_damaged.
     */
    class Reader(file: File, private val key: ByteArray) : MirrorReader() {
        private val raf = try { RandomAccessFile(file, "r") } catch (e: IOException) { throw ProtocolException("mirror_damaged") }
        private val prefix: ByteArray
        override val size: Long
        private var cachedIndex = -1L
        private var cached = ByteArray(0)

        init {
            val head = try { readHead(raf) } catch (e: IOException) { null }
            if (head == null || raf.length() != length(head.second)) { raf.close(); throw ProtocolException("mirror_damaged") }
            prefix = head.first
            size = head.second
        }

        /** pos부터 len바이트까지 out에 담는다. 담은 수(끝이면 -1). */
        override fun read(pos: Long, out: ByteArray, off: Int, len: Int): Int {
            if (pos >= size) return -1
            if (len == 0) return 0
            val i = pos / CHUNK
            if (i != cachedIndex) {
                cached = try { openChunk(raf, key, prefix, size, i) } catch (e: IOException) { null }
                    ?: throw ProtocolException("mirror_damaged")
                cachedIndex = i
            }
            val at = (pos - i * CHUNK).toInt()
            val n = minOf(len, cached.size - at)
            System.arraycopy(cached, at, out, off, n)
            return n
        }

        override fun close() {
            try { raf.close() } catch (e: IOException) { }
            java.util.Arrays.fill(cached, 0)
        }
    }
}

/**
 * 사본에서 푼 내용을 읽는 것(메모리 조각 하나에만 평문): 이 폰이 받아 잠근 객체(MirrorObject.Reader)와
 * 잠긴 폴더 안의 받은 그대로인 암호문(§19.6 VaultReader)이 같은 모양으로 읽힌다.
 */
abstract class MirrorReader : AutoCloseable {
    abstract val size: Long

    /** pos부터 len바이트까지 out에 담는다(한 조각 안에서). 담은 수(끝이면 -1). 손상이면 mirror_damaged. */
    abstract fun read(pos: Long, out: ByteArray, off: Int, len: Int): Int

    /** 처음부터 차례로 읽는 스트림(허브로 복원 §19.5 — 평문은 메모리 조각 하나에만). 닫으면 이 Reader도 닫힌다. */
    fun stream(): java.io.InputStream = object : java.io.InputStream() {
        private var pos = 0L

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            val n = this@MirrorReader.read(pos, b, off, len)
            if (n < 0) return -1
            pos += n
            return n
        }

        override fun close() { this@MirrorReader.close() }
    }

    /** 전체를 메모리로(max 바이트 이하일 때만, 넘으면 too_large). */
    fun readAll(max: Long): ByteArray {
        if (size > max || size > Int.MAX_VALUE - 16) throw ProtocolException("too_large")
        val out = ByteArray(size.toInt())
        var pos = 0L
        while (pos < size) {
            val n = read(pos, out, pos.toInt(), (size - pos).toInt())
            if (n <= 0) throw ProtocolException("mirror_damaged")
            pos += n
        }
        return out
    }
}

/** §19.6 sync_raw의 첫 프레임: {"ok":true,"size":<암호문 전체 크기>}(last=0). 거부는 §3 응답 한 프레임(last=1). */
object SyncRaw {
    sealed class First {
        data class Ok(val size: Long) : First()
        data class Refused(val reply: JSONObject) : First()
    }

    fun fields(oid: String, offset: Long, length: Long): JSONObject {
        require(Regex("[0-9a-f]{32}").matches(oid) && offset >= 0 && length >= 0)
        return JSONObject().put("oid", oid).put("offset", offset).put("length", length)
    }

    fun parseFirst(raw: ByteArray, last: Boolean): First {
        val o = try { JSONObject(String(raw, Charsets.UTF_8)) } catch (e: Exception) { throw ProtocolException("unreadable_reply") }
        if (o.opt("ok") != true) {
            if (!last) throw ProtocolException("bad_reply")
            return First.Refused(o)
        }
        if (last) throw ProtocolException("bad_reply")
        val size = Xfer.jsonLong(o.opt("size"))?.takeIf { it in 0..SyncManifest.VaultRow.RAW_MAX } ?: throw ProtocolException("bad_reply")
        return First.Ok(size)
    }
}
