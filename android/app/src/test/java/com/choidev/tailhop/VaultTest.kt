package com.choidev.tailhop

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.KeyPairGenerator
import javax.crypto.Cipher

/**
 * 2.3.1 잠긴 폴더를 잠긴 채로(§19.6). 벡터(src/test/resources/vault_vector.json)는 허브 PC 코드(cloudkeys·cloudstore)로 만든
 * 실제 봉인·meta·청크 암호문이다(시험용 PIN·복구 코드). 목록 검사, 봉인 열기(Argon2id·Tink), 청크 풀기, 사본에 받은 그대로 두기,
 * 지문 잠금 뒤 PIN으로 열기, 틀린 횟수·기다림, 복원에서 빼기.
 */
class VaultTest {
    @get:Rule val tmp = TemporaryFolder()

    private val v: JSONObject by lazy {
        JSONObject(javaClass.getResourceAsStream("/vault_vector.json")!!.use { String(it.readBytes(), Charsets.UTF_8) })
    }

    private fun rejects(code: String, block: () -> Unit) {
        try { block(); fail("expected $code") } catch (e: ProtocolException) { assertEquals(code, e.code) }
    }

    private fun b64(s: String) = Protocol.b64uDecode(s)!!

    private fun page(extra: List<JSONObject> = emptyList()): SyncManifest.Page {
        val items = JSONArray().put(v.getJSONObject("vault"))
        val rows = v.getJSONArray("rows")
        for (i in 0 until rows.length()) items.put(rows.getJSONObject(i))
        extra.forEach { items.put(it) }
        return SyncManifest.parse(JSONObject().put("digest", "0123456789abcdef").put("items", items).put("next", JSONObject.NULL))
    }

    private val vaultLine get() = page().items.single().vault!!

    // ---------- 목록 ----------

    @Test fun manifestCarriesVaultAndRows() {
        val p = page()
        val vault = p.items.single()
        assertTrue(vault.dir)
        assertEquals("Secret", vault.path)
        assertEquals("7".repeat(32), vault.vault!!.ring)
        assertEquals(3, p.rows.size)
        assertTrue(p.rows.all { it.vault == vault.id && it.path == "Secret" })
        // 같은 id(잠긴 폴더)가 여러 줄이어도 안의 줄은 row마다 남는다
        val full = SyncManifest.collect({ p })
        assertEquals(1, full.items.size)
        assertEquals(3, full.rows.size)
        // 모양이 틀린 줄은 그 줄만 버린다
        val good = v.getJSONArray("rows").getJSONObject(0)
        fun bad(edit: (JSONObject) -> Unit) = JSONObject(good.toString()).also(edit)
        val bads = listOf(
            bad { it.put("row", "xyz") }, bad { it.put("parent", "A".repeat(32)) }, bad { it.remove("ring") },
            bad { it.put("meta", "a".repeat(65537)) }, bad { it.put("meta", "") },
            bad { it.put("objs", JSONArray().apply { repeat(17) { put(JSONObject().put("o", "1".repeat(32)).put("s", 1)) } }) },
            bad { it.put("objs", JSONArray().put(JSONObject().put("o", "../x").put("s", 1))) },
            bad { it.put("objs", JSONArray().put(JSONObject().put("o", "1".repeat(32)).put("s", -1))) },
            bad { it.put("path", "../x") },
        )
        assertEquals(3, page(bads).rows.size)
        // 다른 잠긴 폴더의 줄(목록에 그 잠긴 폴더가 없음)은 버린다
        assertEquals(0, SyncManifest.collect({ SyncManifest.Page("0123456789abcdef", emptyList(), null, p.rows) }).rows.size)
        // 봉인 모양이 틀린 잠긴 폴더 줄은 버린다
        val vl = JSONObject(v.getJSONObject("vault").toString())
        vl.getJSONObject("vault").getJSONObject("pin").put("t", "3")
        assertTrue(SyncManifest.parse(JSONObject().put("digest", "0123456789abcdef").put("items", JSONArray().put(vl))).items.isEmpty())
        // sync_raw 첫 프레임
        assertEquals(SyncRaw.First.Ok(5), SyncRaw.parseFirst("""{"ok":true,"size":5}""".toByteArray(), false))
        assertTrue(SyncRaw.parseFirst("""{"ok":false,"code":"not_found"}""".toByteArray(), true) is SyncRaw.First.Refused)
        rejects("bad_reply") { SyncRaw.parseFirst("""{"ok":true}""".toByteArray(), false) }
        rejects("bad_reply") { SyncRaw.parseFirst("""{"ok":true,"size":5}""".toByteArray(), true) }
    }

    // ---------- 봉인·meta·청크(허브와 같은 형식) ----------

    @Test fun openSealDecryptMetaAndChunks() {
        val line = vaultLine
        val aead = VaultCrypto.open(line.pin, v.getString("pin"), line.pinAd)
        assertNotNull(aead)
        assertNull(VaultCrypto.open(line.pin, "wrong-pin-0", line.pinAd))
        // 복구 코드: 소문자·띄어쓰기·하이픈 없이·O/I/L을 섞어도(허브 normalize_code와 같다)
        val code = v.getString("recovery")
        val messy = code.lowercase().replace("-", " ").replace('0', 'o').replace('1', 'l')
        assertEquals(code, VaultCrypto.normalizeCode(messy))
        assertTrue(VaultCrypto.looksLikeCode(messy))
        assertNotNull(VaultCrypto.open(line.recovery, VaultCrypto.normalizeCode(messy), line.recoveryAd))
        // meta와 파일 청크
        val rows = page().rows
        val objects = v.getJSONObject("objects")
        val names = HashMap<String, String>()
        for (r in rows) {
            val meta = JSONObject(String(aead!!.decrypt(b64(r.meta), VaultCrypto.nodeAd(r.row, r.parent, r.ring))))
            names[r.row] = meta.getString("n")
            if (meta.optBoolean("d")) continue
            val f = File(tmp.root, meta.getString("o")).apply { writeBytes(b64(objects.getString(meta.getString("o")))) }
            val size = meta.getLong("s")
            VaultReader(f, VaultCrypto.fileAead(b64(meta.getString("k"))), size).use { rd ->
                val got = rd.readAll(Long.MAX_VALUE)
                if (size > 0) assertArrayEquals(b64(v.getString("content")), got) else assertEquals(0, got.size)
            }
            // 다른 자리(AD)로는 meta가 열리지 않는다
            try { aead.decrypt(b64(r.meta), VaultCrypto.nodeAd(r.row, r.ring, r.parent)); fail("ad") } catch (e: Exception) { }
        }
        assertEquals(setOf("sub", "photo.jpg", "empty.txt"), names.values.toSet())
        // 청크 한 바이트를 바꾸면 mirror_damaged, 길이가 맞지 않으면 열지 않는다
        val photo = rows.first { it.objs.size == 2 }
        val meta = JSONObject(String(aead!!.decrypt(b64(photo.meta), VaultCrypto.nodeAd(photo.row, photo.parent, photo.ring))))
        val raw = b64(objects.getString(meta.getString("o")))
        val bad = File(tmp.root, "bad").apply { writeBytes(raw.copyOf().also { it[40] = (it[40] + 1).toByte() }) }
        VaultReader(bad, VaultCrypto.fileAead(b64(meta.getString("k"))), meta.getLong("s")).use { rd -> rejects("mirror_damaged") { rd.readAll(Long.MAX_VALUE) } }
        rejects("mirror_damaged") { VaultReader(bad, VaultCrypto.fileAead(b64(meta.getString("k"))), meta.getLong("s") + 1) }
    }

    @Test fun weakOrBrokenSealRefused() {
        val pin = JSONObject(vaultLine.pin)
        for ((k, value) in listOf("t" to 2, "m" to 65536, "p" to 0, "t" to 11, "m" to 2 * 1024 * 1024, "p" to 17, "alg" to "scrypt")) {
            val s = JSONObject(pin.toString()).put(k, value)
            rejects("mirror_damaged") { VaultCrypto.open(s.toString(), "test-pin-1234", vaultLine.pinAd) }
        }
        rejects("mirror_damaged") { VaultCrypto.open(JSONObject(pin.toString()).put("salt", "AAAA").toString(), "x", "") }
        // PIN 길이(코드 포인트)·기다림(허브와 같다)
        assertTrue(VaultCrypto.validPin("123456"))
        assertFalse(VaultCrypto.validPin("12345"))
        assertFalse(VaultCrypto.validPin("x".repeat(65)))
        assertEquals(listOf(0L, 0L, 0L, 0L, 60_000L, 120_000L, 240_000L), (1..7).map { VaultCrypto.waitMs(it) })
        assertEquals(3_600_000L, VaultCrypto.waitMs(50))
    }

    // ---------- 사본에 받은 그대로 두고, 지문 → PIN으로 열기 ----------

    private class Keys : MirrorKeys {
        private val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.genKeyPair()
        var locked = true
        override val ledger: SealKey = AesSealKey(ByteArray(32) { 9 })
        override fun wrap(key: ByteArray): ByteArray = RsaOaep.encrypt(pair.public, key)
        override fun unwrap(wrapped: ByteArray): ByteArray {
            if (locked) throw ProtocolException("mirror_locked")
            return Cipher.getInstance(RsaOaep.TRANSFORM).run { init(Cipher.DECRYPT_MODE, pair.private, RsaOaep.SPEC); doFinal(wrapped) }
        }
    }

    @Test fun mirrorKeepsVaultSealedUntilFingerprintAndPin() {
        val keys = Keys()
        val dir = File(tmp.root, "m")
        val m = Mirror(dir, keys).load()
        val full = SyncManifest.collect({ page() })
        val vault = full.items.single()
        // 맞추기(잠긴 채로): 잠긴 폴더 줄·안의 줄·암호문을 받은 그대로
        assertEquals(listOf("Secret"), SyncPlan.make(m.items(), full.items, m::ih).moves.map { it.path })
        m.putDir(vault)
        m.syncRows(full.rows)
        val objects = v.getJSONObject("objects")
        for (o in full.rows.flatMap { it.objs }) m.openRaw(o.o).use { it.write(b64(objects.getString(o.o))) }
        m.rawDone()
        m.save()
        m.closeBox()
        assertTrue(SyncPlan.make(m.items(), full.items, m::ih).idle)
        // 봉인(blob)·이름은 디스크에 평문으로 없다(봉인은 공개키 상자 안, meta는 받은 암호문 그대로)
        val blob = JSONObject(vaultLine.pin).getString("blob")
        for (f in dir.walkTopDown().filter { it.isFile }) {
            val text = String(f.readBytes(), Charsets.ISO_8859_1)
            for (needle in listOf(blob, "Secret", "photo.jpg")) assertFalse("${f.name}: $needle", text.contains(needle))
        }
        // 지문 전: 아무것도 못 읽는다
        rejects("mirror_locked") { m.entries() }
        val again = Mirror(dir, keys).load()
        keys.locked = false
        // 지문 뒤: 잠긴 폴더는 자물쇠로만
        val top = again.entries()
        assertEquals(listOf("Secret"), top.map { it.path })
        assertTrue(top[0].vault && top[0].sealed)
        assertTrue(MirrorTree.list(top, "").single().locked)
        val vid = again.vaultAt("Secret")!!
        // 틀린 PIN: 횟수는 확인 전에 장부에 쓴다(앱을 껐다 켜도 이어진다)
        val t0 = 1_000_000L
        rejects("vault_pin") { again.openVault(vid, "wrong-pin-0", null, t0) }
        assertEquals(1, Mirror.readLedger(dir, keys)!!.vfail[vid]!!.first)
        // 5번째로 틀린 뒤라면 60초 기다린다(맞는 PIN이어도 확인하지 않는다)
        val l = Mirror.readLedger(dir, keys)!!
        l.vfail[vid] = 5 to t0 + 60_000
        Mirror.writeLedger(dir, keys, l)
        val third = Mirror(dir, keys).load()
        try { third.openVault(vid, v.getString("pin"), null, t0 + 1_000); fail("wait") } catch (e: HubRefusal) {
            assertEquals("vault_wait", e.code)
            assertEquals(59L, Xfer.jsonLong(e.reply.opt("retry")))
        }
        third.openVault(vid, v.getString("pin"), null, t0 + 61_000)
        assertNull(Mirror.readLedger(dir, keys)!!.vfail[vid])
        assertTrue(third.vaultOpen(vid))
        val paths = third.entries().map { it.path }.toSet()
        assertEquals(setOf("Secret", "Secret/sub", "Secret/sub/photo.jpg", "Secret/empty.txt"), paths)
        val photo = third.find("Secret/sub/photo.jpg")!!
        assertEquals("8".repeat(32), photo.raw)
        third.reader(photo).use { assertArrayEquals(b64(v.getString("content")), it.readAll(Long.MAX_VALUE)) }
        third.reader(photo).use { assertArrayEquals(b64(v.getString("content")), it.stream().readBytes()) }
        assertArrayEquals(b64(v.getString("thumb")), third.thumb(photo))
        assertEquals(0, third.reader(third.find("Secret/empty.txt")!!).use { it.readAll(10) }.size)
        assertEquals(listOf("photo.jpg"), MirrorTree.list(third.entries(), "Secret/sub").map { it.name })
        assertFalse(MirrorTree.list(third.entries(), "").single().locked)
        // 허브로 복원: 잠긴 폴더와 그 안은 올리지 않는다
        assertTrue(MirrorRestore.plan(third.entries()).files.isEmpty())
        assertTrue(MirrorRestore.plan(third.entries()).tops.isEmpty())
        // 사본을 잠그면 연 잠긴 폴더도 닫힌다
        third.lock()
        assertFalse(third.vaultOpen(vid))
        assertTrue(third.entries().single().sealed)
        // 받다 만 암호문(길이가 모자람)은 보이지 않는다
        val o = photo.obj!!
        third.rawFile(o).writeBytes(b64(objects.getString(o)).copyOf(10))
        third.rawDone()
        // 허브 목록에서 빠진 줄은 지우고 그 암호문도 지운다
        third.syncRows(emptyList())
        assertFalse(third.rawFile(o).exists())
    }
}
