package com.choidev.tailhop

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
import java.io.RandomAccessFile
import java.security.KeyPair
import java.security.KeyPairGenerator
import javax.crypto.Cipher

/**
 * 2.3.1 사본 잠금(§19.3)·허브가 바뀌었을 때·허브로 복원(§19.5). 열쇠는 주입한 소프트웨어 RSA(Keystore 대신, 잠금을 흉내 낸다):
 * 잠긴 채로 쓰기는 되고 읽기는 열기 전까지 mirror_locked, 저장소 번호 판단표, 연결 해제 → 이전 사본, 복원 계획, 감싸기·손상 검사.
 */
class MirrorLockTest {
    @get:Rule val tmp = TemporaryFolder()

    /** 테스트용 기기 열쇠: 공개키 감싸기는 늘 되고, 개인키 풀기는 locked=false일 때만. */
    private class TestKeys(private val pair: KeyPair = PAIR) : MirrorKeys {
        var locked = true
        var unwraps = 0
        override val ledger: SealKey = AesSealKey(ByteArray(32) { 7 })

        override fun wrap(key: ByteArray): ByteArray = RsaOaep.encrypt(pair.public, key)

        override fun unwrap(wrapped: ByteArray): ByteArray {
            if (locked) throw ProtocolException("mirror_locked")
            unwraps++
            val c = Cipher.getInstance(RsaOaep.TRANSFORM)
            c.init(Cipher.DECRYPT_MODE, pair.private, RsaOaep.SPEC)
            return try { c.doFinal(wrapped) } catch (e: Exception) { throw ProtocolException("mirror_damaged") }
        }
    }

    companion object {
        /** Android와 같은 RSA 3072(한 번만 만든다). */
        val PAIR: KeyPair by lazy { KeyPairGenerator.getInstance("RSA").apply { initialize(3072) }.genKeyPair() }
        val OTHER: KeyPair by lazy { KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.genKeyPair() }
    }

    private fun rejects(code: String, block: () -> Unit) {
        try { block(); fail("expected $code") } catch (e: ProtocolException) { assertEquals(code, e.code) }
    }

    private val ID1 = "0".repeat(31) + "1"
    private val ID2 = "0".repeat(31) + "2"
    private val ID3 = "0".repeat(31) + "3"
    private val VER1 = "a".repeat(32)
    private val TVER = "c".repeat(32)
    private val STORE_A = "1".repeat(32)
    private val STORE_B = "2".repeat(32)
    private val DIGEST = "0123456789abcdef"

    private fun data(n: Int, seed: Int = 7) = ByteArray(n) { (it * 31 + seed).toByte() }

    private fun contains(hay: ByteArray, needle: ByteArray): Boolean =
        String(hay, Charsets.ISO_8859_1).contains(String(needle, Charsets.ISO_8859_1))

    /** 맞추기가 하는 대로: 열쇠를 장부 쪽에 두고(받는 동안) 객체를 쓰고 다 받으면 상자에. 미리보기도. */
    private fun download(m: Mirror, w: SyncManifest.Item, plain: ByteArray, thumb: ByteArray? = null) {
        val key = Protocol.randomBytes(32)
        val obj = Protocol.newId()
        m.startPart(MirrorPart(w.id, w.ver, w.size, w.mtime, obj, Protocol.b64uEncode(key)))
        assertNotNull(m.part()) // 받는 동안만 장부 쪽에 열쇠
        MirrorObject.Writer(m.objFile(obj), key, plain.size.toLong()).use { it.write(plain); it.finish() }
        val t = thumb?.let { bytes ->
            val tk = Protocol.randomBytes(32)
            val to = Protocol.newId()
            MirrorObject.Writer(m.objFile(to), tk, bytes.size.toLong()).use { it.write(bytes); it.finish() }
            Mirror.Thumb(w.tver ?: TVER, to, tk)
        }
        m.complete(w, obj, key, t)
        assertNull(m.part()) // 다 받으면 열쇠는 장부에서 빠진다
    }

    private fun segments(dir: File) = File(dir, "box").listFiles().orEmpty().filter { it.isFile }

    // ---------- 감싸기 ----------

    @Test fun hybridSealRoundTripAndTamper() {
        val keys = TestKeys().apply { locked = false }
        val plain = "photos/2024/secret.jpg".toByteArray()
        val aad = "a".toByteArray()
        val sealed = MirrorSeal.seal(keys, plain, aad)
        assertFalse(contains(sealed, plain))
        assertArrayEquals(plain, MirrorSeal.open(keys, sealed, aad))
        // 쓰기는 잠긴 채로 되고, 읽기는 잠겨 있으면 mirror_locked
        keys.locked = true
        val sealed2 = MirrorSeal.seal(keys, plain, aad)
        rejects("mirror_locked") { MirrorSeal.open(keys, sealed2, aad) }
        keys.locked = false
        assertArrayEquals(plain, MirrorSeal.open(keys, sealed2, aad))
        // 다른 aad·본문 한 바이트·감싼 열쇠 한 바이트·머리가 바뀌면 mirror_damaged
        rejects("mirror_damaged") { MirrorSeal.open(keys, sealed, "b".toByteArray()) }
        rejects("mirror_damaged") { MirrorSeal.open(keys, sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }, aad) }
        rejects("mirror_damaged") { MirrorSeal.open(keys, sealed.copyOf().also { it[10] = (it[10] + 1).toByte() }, aad) }
        rejects("mirror_damaged") { MirrorSeal.open(keys, sealed.copyOf().also { it[0] = 'X'.code.toByte() }, aad) }
        rejects("mirror_damaged") { MirrorSeal.open(keys, sealed.copyOf(20), aad) }
        // 다른 기기의 열쇠로는 열리지 않는다
        rejects("mirror_damaged") { MirrorSeal.open(TestKeys(OTHER).apply { locked = false }, sealed, aad) }
    }

    // ---------- 잠긴 채로 쓰기, 열어야 읽기 ----------

    @Test fun lockedWritesThenUnlockedReads() {
        val keys = TestKeys()
        val dir = File(tmp.root, "m")
        val m = Mirror(dir, keys).load()
        m.setIdentity(STORE_A, "Desk PC")
        val plain = data(MirrorObject.CHUNK + 300)
        val thumb = data(500, 3)
        val d = SyncManifest.Item(ID1, "photos", true)
        val w = SyncManifest.Item(ID2, "photos/a.jpg", false, plain.size.toLong(), 5, VER1, 500, TVER)
        m.putDir(d)
        download(m, w, plain, thumb)
        m.setDigest(DIGEST)
        m.save()
        m.closeBox()
        // 잠긴 채로: 읽기는 모두 mirror_locked
        rejects("mirror_locked") { m.entries() }
        rejects("mirror_locked") { m.find("photos/a.jpg") }
        rejects("mirror_locked") { m.snapshot() }
        // 장부만으로 계획을 세운다: 같은 목록이면 할 일 없음
        assertTrue(SyncPlan.make(m.items(), listOf(d, w), m::ih).idle)
        // 디스크 어디에도 평문(경로·이름·내용·열쇠)이 없다
        for (f in dir.walkTopDown().filter { it.isFile }) {
            val b = f.readBytes()
            for (needle in listOf("photos".toByteArray(), "a.jpg".toByteArray(), "Desk PC".toByteArray(), plain.copyOf(64), thumb.copyOf(64))) {
                assertFalse("${f.name}: plaintext", contains(b, needle))
            }
        }
        // 다시 열어도(새 프로세스) 잠긴 채로 장부는 읽힌다
        val m2 = Mirror(dir, keys).load()
        assertEquals(DIGEST, m2.digest())
        assertEquals(STORE_A, m2.storeId())
        assertEquals(1, m2.usage().first)
        rejects("mirror_locked") { m2.entries() }
        // 연다: 경로·내용·미리보기가 보인다
        keys.locked = false
        assertEquals(listOf("photos", "photos/a.jpg"), m2.entries().map { it.path })
        val e = m2.find("photos/a.jpg")!!
        assertEquals(plain.size.toLong(), e.size)
        assertEquals(TVER, e.tver)
        m2.reader(e).use { assertArrayEquals(plain, it.readAll(Long.MAX_VALUE)) }
        assertArrayEquals(thumb, m2.thumb(e))
        m2.reader(e).use { r -> assertArrayEquals(plain, r.stream().readBytes()) }
        // 다시 잠그면(화면 꺼짐) 메모리의 목록이 지워져 다시 개인키가 필요하다
        m2.lock()
        keys.locked = true
        rejects("mirror_locked") { m2.entries() }
    }

    @Test fun movesThumbsAndCompaction() {
        val keys = TestKeys()
        val dir = File(tmp.root, "m")
        val m = Mirror(dir, keys).load()
        val a = SyncManifest.Item(ID1, "a.jpg", false, 10, 5, VER1, 50, TVER)
        val b = SyncManifest.Item(ID2, "b.txt", false, 20, 5, VER1)
        download(m, a, data(10), data(50))
        m.closeBox()
        download(m, b, data(20))
        m.closeBox()
        // 잠긴 채로: a는 자리·시각이 바뀌고 허브 미리보기가 없어짐, b는 목록에서 빠짐
        val a2 = a.copy(path = "new/a.jpg", mtime = 9, thumb = 0, tver = null)
        val plan = SyncPlan.make(m.items(), listOf(a2), m::ih)
        assertEquals(listOf("new/a.jpg"), plan.moves.map { it.path })
        assertEquals(listOf(ID2), plan.drop)
        plan.drop.forEach { m.remove(it) }
        plan.moves.forEach { m.move(it) }
        m.save()
        m.closeBox()
        assertTrue(SyncPlan.make(m.items(), listOf(a2), m::ih).idle)
        assertEquals(3, segments(dir).size) // 맞추기 세 번 = 조각 셋
        // 열면 하나로 다시 쓴다(다음 열기는 개인키를 한 번만)
        keys.locked = false
        val got = m.entries()
        assertEquals(listOf("new/a.jpg"), got.map { it.path })
        assertEquals(9L, got[0].mtime)
        assertNull(got[0].tobj)
        assertNull(m.thumb(got[0]))
        assertEquals(1, segments(dir).size)
        val fresh = Mirror(dir, keys).load()
        keys.unwraps = 0
        assertEquals(listOf("new/a.jpg"), fresh.entries().map { it.path })
        assertEquals(1, keys.unwraps)
        fresh.reader(fresh.entries()[0]).use { assertArrayEquals(data(10), it.readAll(100)) }
        // 열린 동안 받은 것은 메모리 목록에도 바로 들어간다
        val c = SyncManifest.Item(ID3, "c.bin", false, 7, 1, VER1)
        download(fresh, c, data(7))
        assertEquals(listOf("new/a.jpg", "c.bin"), fresh.entries().map { it.path })
    }

    @Test fun damagedBoxDropsEntryForResync() {
        val keys = TestKeys()
        val dir = File(tmp.root, "m")
        val m = Mirror(dir, keys).load()
        val a = SyncManifest.Item(ID1, "a.bin", false, 10, 5, VER1)
        val b = SyncManifest.Item(ID2, "b.bin", false, 10, 5, VER1)
        download(m, a, data(10))
        m.closeBox()
        download(m, b, data(10))
        m.setDigest(DIGEST)
        m.save()
        m.closeBox()
        // 두 번째 조각의 기록 한 바이트를 바꾼다
        val seg = segments(dir).maxByOrNull { it.name }!!
        RandomAccessFile(seg, "rw").use { r -> val at = r.length() - 5; r.seek(at); val x = r.read(); r.seek(at); r.write(x xor 1) }
        keys.locked = false
        val again = Mirror(dir, keys).load()
        assertEquals(listOf("a.bin"), again.entries().map { it.path })
        // 장부에서도 빠져 다음 맞추기가 다시 받는다(요약값도 지워 목록을 다시 받게)
        assertNull(again.digest())
        assertEquals(listOf("b.bin"), SyncPlan.make(again.items(), listOf(a, b), again::ih).fetch.map { it.path })
    }

    // ---------- 저장소 번호(§19.5) ----------

    @Test fun storeIdDecisionTable() {
        // 같은 번호 / 둘 다 없음(예전 허브)
        assertEquals(MirrorIdentity.Decision.SAME, MirrorIdentity.decide(STORE_A, STORE_A, true))
        assertEquals(MirrorIdentity.Decision.SAME, MirrorIdentity.decide(null, null, true))
        // 사본에 번호가 없다(새 사본·예전 허브 때 만든 사본): 받아들인다
        assertEquals(MirrorIdentity.Decision.ADOPT, MirrorIdentity.decide(null, STORE_A, true))
        assertEquals(MirrorIdentity.Decision.ADOPT, MirrorIdentity.decide(null, STORE_A, false))
        // 다른 번호: 담은 것이 있으면 이전 사본으로, 비어 있으면 새로
        assertEquals(MirrorIdentity.Decision.ARCHIVE, MirrorIdentity.decide(STORE_A, STORE_B, true))
        assertEquals(MirrorIdentity.Decision.FRESH, MirrorIdentity.decide(STORE_A, STORE_B, false))
        // 허브가 번호를 알리지 않음(저장소 없음·예전 허브): 아무것도 바꾸지 않는다
        assertEquals(MirrorIdentity.Decision.HOLD, MirrorIdentity.decide(STORE_A, null, true))
        // "대상이 없으면 지운다"는 같은 저장소일 때만
        assertTrue(MirrorIdentity.mayWipe(MirrorIdentity.Decision.SAME))
        assertTrue(MirrorIdentity.mayWipe(MirrorIdentity.Decision.ADOPT))
        for (x in listOf(MirrorIdentity.Decision.ARCHIVE, MirrorIdentity.Decision.FRESH, MirrorIdentity.Decision.HOLD)) assertFalse(MirrorIdentity.mayWipe(x))
        // 형식: 32자 소문자 hex만
        assertEquals(STORE_A, MirrorIdentity.parse(STORE_A))
        for (bad in listOf("A".repeat(32), "1".repeat(31), "1".repeat(33), "", null, 5, true)) assertNull(MirrorIdentity.parse(bad))
    }

    // ---------- 연결 해제·허브 바뀜 → 이전 사본 ----------

    @Test fun unpairMovesToArchive() {
        val keys = TestKeys()
        val live = File(tmp.root, "mirror/abcdef0123456789")
        val oldRoot = File(tmp.root, "mirror_old")
        val m = Mirror(live, keys).load()
        m.setIdentity(STORE_A, "Old name")
        val w = SyncManifest.Item(ID1, "doc/a.txt", false, 30, 5, VER1)
        download(m, w, data(30))
        m.save()
        m.closeBox()
        // 연결 해제(지문 없이): 지우지 않고 옮긴다
        val target = MirrorArchive.move(live, oldRoot, keys, "Desk PC", 1234L)
        assertNotNull(target)
        assertFalse(live.exists())
        assertTrue(MirrorArchive.isId(target!!.name))
        val l = Mirror.readLedger(target, keys)!!
        assertEquals("Desk PC", l.hub)
        assertEquals(1234L, l.archivedAt)
        assertEquals(STORE_A, l.storeId)
        val list = MirrorArchive.list(oldRoot, keys)
        assertEquals(1, list.size)
        assertEquals(MirrorArchive.Info(target.name, "Desk PC", STORE_A, 1234L, 1, list[0].bytes, true), list[0])
        assertTrue(list[0].bytes > 30)
        // 이전 사본도 잠겨 있다가 열면 읽힌다(복원은 묶음으로 — 다시 잠가도 시작한 복원은 끝까지)
        val arch = Mirror(target, keys).load(create = false)
        rejects("mirror_locked") { arch.entries() }
        keys.locked = false
        arch.snapshot().use { snap ->
            arch.lock()
            keys.locked = true
            val e = snap.entries.single()
            assertEquals("doc/a.txt", e.path)
            snap.open(e).use { assertArrayEquals(data(30), it.readBytes()) }
        }
        // 담은 것이 없는 사본은 옮기지 않고 지운다
        val empty = File(tmp.root, "mirror/0000000000000000")
        Mirror(empty, keys).load()
        assertNull(MirrorArchive.move(empty, oldRoot, keys, "X", 1L))
        assertFalse(empty.exists())
        // 장부를 풀 수 없는 이전 사본은 지우지 않고 "열 수 없음"으로 보인다
        val bad = File(oldRoot, "f".repeat(32)).apply { mkdirs() }
        File(bad, Mirror.LEDGER).writeBytes(data(100))
        val listed = MirrorArchive.list(oldRoot, keys).first { it.id == bad.name }
        assertFalse(listed.readable)
        rejects("mirror_damaged") { Mirror(bad, keys).load(create = false) }
        assertTrue(File(bad, Mirror.LEDGER).isFile)
        // 맞추기 쪽(create)은 풀 수 없는 장부를 새로 시작한다
        val broken = File(tmp.root, "mirror/1111111111111111").apply { mkdirs() }
        File(broken, Mirror.LEDGER).writeBytes(data(100))
        assertFalse(Mirror(broken, keys).load().hasContent())
    }

    // ---------- 허브로 복원 ----------

    @Test fun restorePlanTopLevel() {
        val o = "e".repeat(32)
        val entries = listOf(
            MirrorEntry(ID1, "photos", true),
            MirrorEntry("1".repeat(32), "photos/2024", true),
            MirrorEntry(ID2, "photos/2024/a.jpg", false, 10, 5, VER1, obj = o),
            MirrorEntry(ID3, "b.txt", false, 3, 5, VER1, obj = o),
            MirrorEntry("2".repeat(32), "empty", true),
            MirrorEntry("3".repeat(32), "empty/sub", true),
            MirrorEntry("4".repeat(32), "c.jpg", false, 3, 5, VER1), // 다 받지 못함: 올리지 않는다
        )
        val plan = MirrorRestore.plan(entries)
        assertEquals(listOf("b.txt", "photos/2024/a.jpg"), plan.files.map { it.path })
        assertEquals(13L, plan.bytes)
        // 파일이 든 폴더는 cloud_put이 만든다: 빈 폴더만 따로(위에서부터)
        assertEquals(listOf("empty", "empty/sub"), plan.dirs)
        assertEquals(listOf("b.txt", "photos", "empty"), plan.tops)
        // 맨 위 파일은 허브가 저장한 이름(번호가 붙었을 수 있다), 폴더 안이면 맨 위 폴더
        assertEquals("b (1).txt", MirrorRestore.top("b.txt", "b (1).txt"))
        assertEquals("b.txt", MirrorRestore.top("b.txt", "../x"))
        assertEquals("b.txt", MirrorRestore.top("b.txt", null))
        assertEquals("photos", MirrorRestore.top("photos/2024/a.jpg", "a (1).jpg"))
        assertTrue(MirrorRestore.plan(emptyList()).tops.isEmpty())
    }
}
