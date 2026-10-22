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
import java.io.RandomAccessFile

/** 2.3.0 모든 기기 동기화·기기 용량(§19): 목록 검사, 맞추기 계획, 쪽 사이 요약값 바뀜, 사본 청크 암호, 남은 공간, 용량 읽기. */
class SyncTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun rejects(code: String, block: () -> Unit) {
        try { block(); fail("expected $code") } catch (e: ProtocolException) { assertEquals(code, e.code) }
    }

    private val ID1 = "0".repeat(31) + "1"
    private val ID2 = "0".repeat(31) + "2"
    private val ID3 = "0".repeat(31) + "3"
    private val VER1 = "a".repeat(32)
    private val VER2 = "b".repeat(32)
    private val TVER = "c".repeat(32)
    private val TVER2 = "d".repeat(32)

    private fun file(id: String, path: String, size: Any = 10, mtime: Any = 5, ver: Any = VER1): JSONObject =
        JSONObject().put("id", id).put("path", path).put("dir", false).put("size", size).put("mtime", mtime).put("ver", ver)

    // ---------- §19.1 기기 용량 ----------

    @Test fun spaceParsing() {
        assertEquals(DeviceSpace(5, 10), DeviceSpace.parse(JSONObject("""{"free":5,"total":10}""")))
        assertEquals(DeviceSpace(0, 0), DeviceSpace.parse(JSONObject("""{"free":0,"total":0}""")))
        assertEquals(DeviceSpace(5_000_000_000_000L, 9_000_000_000_000L),
            DeviceSpace.parse(JSONObject("""{"free":5000000000000,"total":9000000000000}""")))
        // 0 이상 정수가 아니면 무시
        for (bad in listOf("""{"free":-1,"total":10}""", """{"free":1,"total":-10}""", """{"free":true,"total":10}""",
            """{"free":1.5,"total":10}""", """{"free":"1","total":10}""", """{"free":1}""", """{}""")) {
            assertNull(bad, DeviceSpace.parse(JSONObject(bad)))
        }
        assertNull(DeviceSpace.parse("free"))
        assertNull(DeviceSpace.parse(null))
    }

    @Test fun helloCarriesSpaceAndSync() {
        val h = Xfer.parseHello(JSONObject("""{"ok":true,"caps":["sync","store"],"store":"open","sync":"0123456789abcdef",
            "sync_all":true,"space":{"free":1,"total":2}}"""))
        assertEquals(DeviceSpace(1, 2), h.space)
        assertEquals("0123456789abcdef", h.sync)
        assertTrue(h.syncAll)
        val old = Xfer.parseHello(JSONObject("""{"ok":true,"caps":["sync"],"sync":"XYZ","sync_all":1,"space":{"free":-1,"total":2}}"""))
        assertNull(old.space)
        assertNull(old.sync) // 16자 소문자 hex만
        assertFalse(old.syncAll) // true일 때만
        assertNull(Xfer.parseHello(JSONObject("""{"ok":true,"caps":[],"sync":""}""")).sync) // 빈 값 = 대상 없음
        // 2.3.1(§19.5): 저장소 번호는 32자 소문자 hex만
        assertEquals("0123456789abcdef".repeat(2), Xfer.parseHello(JSONObject("""{"ok":true,"store_id":"${"0123456789abcdef".repeat(2)}"}""")).storeId)
        for (bad in listOf("\"${"A".repeat(32)}\"", "\"abc\"", "12", "null", "true")) {
            assertNull(bad, Xfer.parseHello(JSONObject("""{"ok":true,"store_id":$bad}""")).storeId)
        }
        assertNull(h.storeId)
        // 이 폰의 hello 응답에 싣는 용량
        val reply = Xfer.helloReply(0, false, false, DeviceSpace(3, 4))
        assertEquals(DeviceSpace(3, 4), DeviceSpace.parse(reply.opt("space")))
        assertFalse(Xfer.helloReply(0, false).has("space"))
    }

    @Test fun peerSpacesRemember() {
        val s = PeerSpaces()
        s.put("100.64.0.1", DeviceSpace(1, 2), now = 7)
        val v = s.version
        s.put("100.64.0.1", null) // 형식이 틀린 값은 지우지 않는다
        assertEquals(PeerSpaces.Seen(DeviceSpace(1, 2), 7), s.get("100.64.0.1"))
        s.put("100.64.0.1", DeviceSpace(1, 2), now = 9)
        assertEquals(v, s.version) // 값이 같으면 다시 그리지 않는다
        assertEquals(9L, s.get("100.64.0.1")!!.at)
        s.forget("100.64.0.1")
        assertNull(s.get("100.64.0.1"))
    }

    // ---------- §19.2 목록 검사 ----------

    @Test fun manifestParsesAndValidates() {
        val items = JSONArray()
            .put(JSONObject().put("id", ID1).put("path", "a").put("dir", true))
            .put(file(ID2, "a/b.jpg").put("thumb", 300).put("tver", TVER))
            .put(file(ID3, "a/c.jpg").put("thumb", 300).put("tver", "nothex")) // 미리보기만 버린다
            .put(file("XYZ", "a/d.jpg")) // id 틀림
            .put(file(ID1.replace('1', '4'), "../e.jpg")) // 경로 틀림
            .put(file(ID1.replace('1', '5'), "a//f.jpg"))
            .put(file(ID1.replace('1', '6'), ".hidden"))
            .put(file(ID1.replace('1', '7'), "a/g.part")) // 보이지 않는 이름
            .put(file(ID1.replace('1', '8'), "a/h.jpg", size = -1))
            .put(file(ID1.replace('1', '9'), "a/i.jpg", size = Protocol.MAX_FILE + 1))
            .put(file("a".repeat(31) + "1", "a/j.jpg", size = true))
            .put(file("a".repeat(31) + "2", "a/k.jpg", ver = "short"))
            .put(file("a".repeat(31) + "3", "a/l.jpg", mtime = -5))
            .put(file("a".repeat(31) + "4", "", ))
            .put(file("a".repeat(31) + "5", "a/m.jpg").put("thumb", SyncManifest.THUMB_MAX + 1).put("tver", TVER))
            .put("not an object")
        val page = SyncManifest.parse(JSONObject().put("digest", "0123456789abcdef").put("items", items).put("next", JSONObject.NULL))
        assertEquals("0123456789abcdef", page.digest)
        assertNull(page.next)
        assertEquals(listOf("a", "a/b.jpg", "a/c.jpg", "a/m.jpg"), page.items.map { it.path })
        assertTrue(page.items[0].dir)
        assertEquals(TVER, page.items[1].tver)
        assertEquals(300L, page.items[1].thumb)
        assertNull(page.items[2].tver)
        assertEquals(0L, page.items[2].thumb)
        assertNull(page.items[3].tver) // 미리보기 한도를 넘음
    }

    @Test fun manifestRejectsBadPages() {
        fun page(digest: Any?, items: Any?, next: Any? = JSONObject.NULL): JSONObject {
            val o = JSONObject()
            if (digest != null) o.put("digest", digest)
            if (items != null) o.put("items", items)
            return o.put("next", next)
        }
        rejects("bad_reply") { SyncManifest.parse(page(null, JSONArray())) }
        rejects("bad_reply") { SyncManifest.parse(page("0123456789ABCDEF", JSONArray())) }
        rejects("bad_reply") { SyncManifest.parse(page("0123", JSONArray())) }
        rejects("bad_reply") { SyncManifest.parse(page("0123456789abcdef", "x")) }
        rejects("bad_reply") { SyncManifest.parse(page("0123456789abcdef", JSONArray(), next = 0)) }
        rejects("bad_reply") { SyncManifest.parse(page("0123456789abcdef", JSONArray(), next = -3)) }
        rejects("bad_reply") { SyncManifest.parse(page("0123456789abcdef", JSONArray(), next = "5")) }
        val big = JSONArray()
        repeat(SyncManifest.MAX_PAGE + 1) { big.put(JSONObject()) }
        rejects("bad_reply") { SyncManifest.parse(page("0123456789abcdef", big)) }
        assertEquals(2000, SyncManifest.parse(page("0123456789abcdef", JSONArray(), next = 2000)).next)
    }

    // ---------- 쪽 사이 요약값 바뀜 ----------

    private fun item(id: String, path: String, ver: String = VER1) = SyncManifest.Item(id, path, false, 10, 5, ver)

    @Test fun collectRestartsWhenDigestChanges() {
        val calls = ArrayList<Int>()
        var round = 0
        val full = SyncManifest.collect({ cursor ->
            calls += cursor
            when {
                round == 0 && cursor == 0 -> SyncManifest.Page("1111111111111111", listOf(item(ID1, "a")), 1)
                // 둘째 쪽을 받는 사이 허브의 대상이 바뀌었다: 처음부터 다시
                round == 0 && cursor == 1 -> { round = 1; SyncManifest.Page("2222222222222222", listOf(item(ID3, "c")), null) }
                cursor == 0 -> SyncManifest.Page("2222222222222222", listOf(item(ID1, "a2")), 1)
                else -> SyncManifest.Page("2222222222222222", listOf(item(ID2, "b")), null)
            }
        })
        assertEquals(listOf(0, 1, 0, 1), calls)
        assertEquals("2222222222222222", full.digest)
        assertEquals(listOf("a2", "b"), full.items.map { it.path }) // 앞서 받은 쪽은 버렸다
    }

    @Test fun collectGivesUpAndChecksCursor() {
        var n = 0
        rejects("cloud_changed") {
            SyncManifest.collect({ c ->
                n++
                SyncManifest.Page(if (c == 0) "1111111111111111" else "%016x".format(n.toLong()), emptyList(), if (c == 0) 5 else null)
            })
        }
        rejects("bad_reply") { SyncManifest.collect({ c -> SyncManifest.Page("1111111111111111", emptyList(), if (c == 0) 3 else 3) }) }
        rejects("disconnected") { SyncManifest.collect({ SyncManifest.Page("1111111111111111", emptyList(), null) }, stop = { true }) }
        // 같은 id·같은 경로는 처음 것만
        val full = SyncManifest.collect({ SyncManifest.Page("1111111111111111", listOf(item(ID1, "a"), item(ID1, "b"), item(ID2, "a")), null) })
        assertEquals(listOf("a"), full.items.map { it.path })
    }

    // ---------- 맞추기 계획 ----------

    // 장부(잠긴 채로 세우는 계획, 2.3.1): 경로 대신 ih(경로·크기·시각의 열쇠 붙은 해시)
    private val ledger = Ledger.fresh()

    private fun ih(w: SyncManifest.Item) = ledger.ih(w)

    private fun have(vararg e: LedgerItem) = e.associateBy { it.id }

    private fun done(id: String, path: String, ver: String = VER1, tver: String? = null) =
        LedgerItem(id, false, ledger.ih(false, path, 10, 5), ver, tver, obj = "e".repeat(32), tobj = tver?.let { "f".repeat(32) })

    @Test fun planRenameOnlyDownloadDelete() {
        val old = have(
            done(ID1, "old/name.jpg"), // 같은 id·ver: 경로만
            done(ID2, "b.jpg", ver = VER1), // ver가 바뀜: 다시 받기
            done(ID3, "gone.jpg"), // 목록에 없음: 지우기
        )
        val want = listOf(item(ID1, "new/name.jpg"), item(ID2, "b.jpg", ver = VER2), item("9".repeat(32), "new.jpg"))
        val plan = SyncPlan.make(old, want, ::ih)
        assertEquals(listOf("new/name.jpg"), plan.moves.map { it.path })
        assertEquals(listOf("b.jpg", "new.jpg"), plan.fetch.map { it.path })
        assertEquals(listOf(ID3), plan.drop)
        assertTrue(plan.thumbs.isEmpty())
        assertFalse(plan.idle)
        // 다 맞으면 할 일이 없다
        assertTrue(SyncPlan.make(have(done(ID1, "a.jpg")), listOf(item(ID1, "a.jpg")), ::ih).idle)
    }

    @Test fun planIncompleteDirsAndThumbs() {
        // 장부에 없는 것(받다 만 것은 장부 줄이 없다)은 같은 ver여도 받는다(받다 만 청크부터)
        assertEquals(listOf("a.jpg"), SyncPlan.make(emptyMap(), listOf(item(ID1, "a.jpg")), ::ih).fetch.map { it.path })
        // 폴더: 없거나 자리가 바뀌면 고친다
        val dirWant = SyncManifest.Item(ID2, "f", true)
        assertEquals(listOf("f"), SyncPlan.make(emptyMap(), listOf(dirWant), ::ih).moves.map { it.path })
        assertTrue(SyncPlan.make(have(LedgerItem(ID2, true, ih(dirWant))), listOf(dirWant), ::ih).idle)
        assertEquals(1, SyncPlan.make(have(LedgerItem(ID2, true, ih(dirWant))), listOf(dirWant.copy(path = "g")), ::ih).moves.size)
        // 미리보기만 바뀜: 내용은 그대로 두고 미리보기만 받는다
        val withThumb = item(ID3, "t.jpg").copy(thumb = 100, tver = TVER2)
        val p = SyncPlan.make(have(done(ID3, "t.jpg", tver = TVER)), listOf(withThumb), ::ih)
        assertEquals(listOf("t.jpg"), p.thumbs.map { it.path })
        assertTrue(p.fetch.isEmpty() && p.moves.isEmpty())
        // 미리보기가 없어짐: 정보만 고친다(미리보기 객체를 지운다)
        assertEquals(listOf("t.jpg"), SyncPlan.make(have(done(ID3, "t.jpg", tver = TVER)), listOf(item(ID3, "t.jpg")), ::ih).moves.map { it.path })
        // 수정 시각만 바뀜(ver 같음): 정보만
        val moved = SyncPlan.make(have(done(ID1, "a.jpg")), listOf(item(ID1, "a.jpg").copy(mtime = 99)), ::ih)
        assertEquals(1, moved.moves.size)
        assertTrue(moved.fetch.isEmpty())
        // ih는 사본마다 다른 열쇠: 다른 장부의 ih와는 맞지 않는다(장부만으로 경로를 맞혀 볼 수 없다)
        assertFalse(Ledger.fresh().ih(item(ID1, "a.jpg")) == ih(item(ID1, "a.jpg")))
    }

    // ---------- 남은 공간 ----------

    @Test fun freeSpaceGuard() {
        val gib = 1L shl 30
        assertTrue(SyncPlan.enoughSpace(gib + 100, 100))
        assertFalse(SyncPlan.enoughSpace(gib + 99, 100))
        assertFalse(SyncPlan.enoughSpace(gib - 1, 0))
        assertTrue(SyncPlan.enoughSpace(Long.MAX_VALUE, Protocol.MAX_FILE))
        assertFalse(SyncPlan.enoughSpace(Protocol.MAX_FILE, Protocol.MAX_FILE))
    }

    // ---------- 사본 청크 암호 ----------

    private val key = ByteArray(32) { it.toByte() }

    private fun data(n: Int) = ByteArray(n) { (it * 31 + 7).toByte() }

    private fun write(f: File, plain: ByteArray, k: ByteArray = key) {
        MirrorObject.Writer(f, k, plain.size.toLong()).use { w ->
            var o = 0
            while (o < plain.size) { val n = minOf(300_000, plain.size - o); w.write(plain, o, n); o += n }
            w.finish()
        }
    }

    @Test fun chunkRoundTrip() {
        val C = MirrorObject.CHUNK
        for (n in listOf(0, 1, C - 1, C, C + 1, C * 2 + C / 2)) {
            val f = File(tmp.root, "o$n")
            val plain = data(n)
            write(f, plain)
            assertEquals(MirrorObject.length(n.toLong()), f.length())
            MirrorObject.Reader(f, key).use { r ->
                assertEquals(n.toLong(), r.size)
                assertArrayEquals(plain, r.readAll(Long.MAX_VALUE))
                if (n > C) {
                    // 원하는 위치부터(조각 경계를 넘어)
                    val buf = ByteArray(10)
                    val got = r.read(C - 3L, buf, 0, 10)
                    assertEquals(3, got) // 한 번에 한 조각 안에서
                    assertArrayEquals(plain.copyOfRange(C - 3, C), buf.copyOf(3))
                    assertEquals(-1, r.read(n.toLong(), buf, 0, 10))
                }
            }
            // 평문이 그대로 디스크에 있지 않다
            if (n >= 64) assertFalse(String(f.readBytes(), Charsets.ISO_8859_1).contains(String(plain.copyOfRange(0, 64), Charsets.ISO_8859_1)))
        }
    }

    @Test fun chunkTamperDetected() {
        val f = File(tmp.root, "t")
        val plain = data(MirrorObject.CHUNK + 50)
        write(f, plain)
        // 두 번째 조각의 한 바이트를 바꾼다
        RandomAccessFile(f, "rw").use { r ->
            val at = MirrorObject.HEAD + MirrorObject.CHUNK + MirrorObject.TAG + 5L
            r.seek(at); val b = r.read(); r.seek(at); r.write(b xor 1)
        }
        MirrorObject.Reader(f, key).use { r ->
            val buf = ByteArray(10)
            assertEquals(10, r.read(0, buf, 0, 10)) // 첫 조각은 그대로 열린다
            rejects("mirror_damaged") { r.read(MirrorObject.CHUNK.toLong(), buf, 0, 10) }
        }
        // 다른 열쇠
        val g = File(tmp.root, "g")
        write(g, data(100))
        MirrorObject.Reader(g, ByteArray(32)).use { r -> rejects("mirror_damaged") { r.readAll(1000) } }
        // 잘린 파일·머리 바꿈
        val h = File(tmp.root, "h")
        write(h, data(100))
        RandomAccessFile(h, "rw").use { it.setLength(it.length() - 1) }
        rejects("mirror_damaged") { MirrorObject.Reader(h, key) }
        val s = File(tmp.root, "s")
        write(s, data(100))
        RandomAccessFile(s, "rw").use { it.seek(19); it.write(99) } // 크기를 바꿈 → 길이가 맞지 않음
        rejects("mirror_damaged") { MirrorObject.Reader(s, key) }
        // 마지막 조각을 앞 조각과 바꿔 붙이면 aad(번호·마지막)가 달라 열리지 않는다
        val sw = File(tmp.root, "sw")
        val two = data(MirrorObject.CHUNK * 2)
        write(sw, two)
        RandomAccessFile(sw, "rw").use { r ->
            val len = MirrorObject.CHUNK + MirrorObject.TAG
            val a = ByteArray(len); val b = ByteArray(len)
            r.seek(MirrorObject.HEAD.toLong()); r.readFully(a); r.readFully(b)
            r.seek(MirrorObject.HEAD.toLong()); r.write(b); r.write(a)
        }
        MirrorObject.Reader(sw, key).use { r -> rejects("mirror_damaged") { r.readAll(Long.MAX_VALUE) } }
        // 크기를 넘게 쓰거나 덜 쓰고 끝내면 거부
        MirrorObject.Writer(File(tmp.root, "x"), key, 3).use { w ->
            rejects("file_too_big") { w.write(data(4)) }
            w.write(data(2))
            rejects("file_incomplete") { w.finish() }
        }
    }

    @Test fun chunkResumeAfterInterruption() {
        val C = MirrorObject.CHUNK
        val f = File(tmp.root, "r")
        val plain = data(C * 3 + 10)
        // 두 조각 반을 쓰고 끊김(버퍼의 반 조각은 디스크에 없다)
        MirrorObject.Writer(f, key, plain.size.toLong()).use { w -> w.write(plain, 0, C * 2 + C / 2) }
        assertEquals(MirrorObject.HEAD + 2L * (C + MirrorObject.TAG), f.length())
        // 찢긴 쓰기: 셋째 조각이 조금 붙어 있다
        RandomAccessFile(f, "rw").use { it.seek(it.length()); it.write(ByteArray(100)) }
        MirrorObject.Writer(f, key, plain.size.toLong()).use { w ->
            assertEquals(2L * C, w.done)
            assertFalse(w.complete)
            w.write(plain, 2 * C, plain.size - 2 * C)
            w.finish()
        }
        MirrorObject.Reader(f, key).use { assertArrayEquals(plain, it.readAll(Long.MAX_VALUE)) }
        // 다 쓴 파일을 다시 열면 complete
        MirrorObject.Writer(f, key, plain.size.toLong()).use { w -> assertTrue(w.complete); assertEquals(plain.size.toLong(), w.done) }
        MirrorObject.Reader(f, key).use { assertArrayEquals(plain, it.readAll(Long.MAX_VALUE)) }
        // 마지막으로 쓴 조각이 손상됐으면 한 조각 앞에서 잇는다
        val g = File(tmp.root, "r2")
        MirrorObject.Writer(g, key, plain.size.toLong()).use { w -> w.write(plain, 0, C * 2) ; w.write(plain, C * 2, 1) }
        RandomAccessFile(g, "rw").use { r -> val at = MirrorObject.HEAD + C + MirrorObject.TAG + 10L; r.seek(at); r.write(0x55) }
        MirrorObject.Writer(g, key, plain.size.toLong()).use { w -> assertEquals(C.toLong(), w.done) }
        // 크기가 다르면(다른 파일) 처음부터
        MirrorObject.Writer(g, key, 5).use { w -> assertEquals(0L, w.done); w.write(data(5)); w.finish() }
        MirrorObject.Reader(g, key).use { assertArrayEquals(data(5), it.readAll(10)) }
        // 다른 열쇠로 이으려 하면 처음 조각부터
        val k2 = ByteArray(32) { 9 }
        val h = File(tmp.root, "r3")
        MirrorObject.Writer(h, key, plain.size.toLong()).use { w -> w.write(plain, 0, C * 2) }
        MirrorObject.Writer(h, k2, plain.size.toLong()).use { w -> assertEquals(0L, w.done) }
    }

    @Test fun sealKeyRoundTrip() {
        val k = AesSealKey(ByteArray(32) { 3 })
        val sealed = k.seal("hello".toByteArray(), "a".toByteArray())
        assertArrayEquals("hello".toByteArray(), k.open(sealed, "a".toByteArray()))
        rejects("mirror_damaged") { k.open(sealed, "b".toByteArray()) } // 다른 자리
        rejects("mirror_damaged") { k.open(sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }, "a".toByteArray()) }
        rejects("mirror_damaged") { AesSealKey(ByteArray(32)).open(sealed, "a".toByteArray()) }
        rejects("mirror_damaged") { k.open(ByteArray(5), "a".toByteArray()) }
    }

    // ---------- 사본 목록 ----------

    @Test fun ledgerRoundTripAndTree() {
        // 장부: 경로·크기 없이 id·ih·ver·객체 이름만(2.3.1)
        val l = Ledger.fresh()
        l.digest = "0123456789abcdef"
        l.storeId = "a".repeat(32)
        l.hub = "Desk PC"
        val li = LedgerItem(ID2, false, l.ih(false, "photos/2024/a.jpg", 10, 5), VER1, TVER, "e".repeat(32), "f".repeat(32))
        l.items[ID1] = LedgerItem(ID1, true, l.ih(true, "photos", 0, 0))
        l.items[ID2] = li
        val back = Ledger.parse(l.encode())
        assertEquals("0123456789abcdef", back.digest)
        assertEquals("a".repeat(32), back.storeId)
        assertEquals("Desk PC", back.hub)
        assertEquals(listOf(l.items[ID1], li), back.items.values.toList())
        assertEquals(l.ih(false, "x", 1, 2), back.ih(false, "x", 1, 2)) // HMAC 열쇠도 그대로
        assertEquals(1, back.files())
        assertEquals(setOf("e".repeat(32), "f".repeat(32)), back.objects())
        assertFalse(String(l.encode(), Charsets.UTF_8).contains("photos")) // 경로가 장부에 없다
        // 형식이 틀린 줄은 버린다(객체 이름은 32자 hex만)
        assertNull(LedgerItem.parse(li.json().put("obj", "../../etc")))
        assertNull(LedgerItem.parse(JSONObject().put("id", "x").put("d", true).put("ih", "")))
        rejects("mirror_damaged") { Ledger.parse("nope".toByteArray()) }
        rejects("mirror_damaged") { Ledger.parse(JSONObject(String(l.encode())).put("v", 1).toString().toByteArray()) }

        // 푼 목록(메모리)에서 폴더 보기
        val a = MirrorEntry(ID1, "photos", true)
        val b = MirrorEntry(ID2, "photos/2024/a.jpg", false, 10, 5, VER1, TVER, "e".repeat(32), "f".repeat(32))
        val c = MirrorEntry(ID3, "photos/b.jpg", false, 10, 5, VER1) // 다 받지 못함
        val all = listOf(a, b, c)
        // 맨 위: photos 폴더 하나. photos 안: 2024(파일 경로에서 생긴 폴더), 다 받지 못한 b.jpg는 보이지 않는다
        assertEquals(listOf("photos"), MirrorTree.list(all, "").map { it.name })
        val inside = MirrorTree.list(all, "photos")
        assertEquals(listOf("2024"), inside.map { it.name })
        assertTrue(inside[0].dir && inside[0].sync)
        assertEquals(listOf("a.jpg"), MirrorTree.list(all, "photos/2024").map { it.name })
        assertNotNull(MirrorTree.find(all, "photos/2024/a.jpg"))
        assertNull(MirrorTree.find(all, "photos/b.jpg"))
        assertEquals(listOf("2024/a.jpg"), MirrorTree.search(all, "photos", "A.J", FileKinds.ALL).map { it.path })
        assertTrue(MirrorTree.search(all, "", "", FileKinds.VIDEO).isEmpty())
        // 같은 경로·크기·수정 시각이면 사본에서 연다
        assertTrue(MirrorTree.same(b, CloudItem("a.jpg", false, 10, 5)))
        assertFalse(MirrorTree.same(b, CloudItem("a.jpg", false, 10, 6)))
        assertFalse(MirrorTree.same(null, CloudItem("a.jpg", false, 10, 5)))
    }

    @Test fun partMatches() {
        val p = MirrorPart(ID1, VER1, 10, 5, "e".repeat(32), Protocol.b64uEncode(ByteArray(32) { 1 }))
        assertNull(MirrorPart.parse(p.copy(fkey = "AAAA").encode())) // 파일 열쇠는 32바이트만
        assertEquals(p, MirrorPart.parse(p.encode()))
        assertTrue(p.matches(item(ID1, "x")))
        assertFalse(p.matches(item(ID1, "x", ver = VER2)))
        assertFalse(p.matches(item(ID1, "x").copy(mtime = 6)))
        assertNull(MirrorPart.parse("{}".toByteArray()))
        assertNull(MirrorPart.parse(p.encode().let { String(it).replace("e".repeat(32), "../x").toByteArray() }))
    }

    // ---------- sync_set ----------

    @Test fun syncSetFieldsAndResults() {
        val f = SyncSet.fields(listOf("a", "b/c"), true)
        assertEquals(2, f.getJSONArray("paths").length())
        assertEquals(true, f.get("on"))
        assertEquals(false, SyncSet.allFields(false).get("all"))
        for (bad in listOf(emptyList(), listOf(""), listOf("../x"), List(501) { "p$it" })) {
            try { SyncSet.fields(bad, true); fail("expected reject") } catch (e: IllegalArgumentException) { }
        }
        val r = SyncSet.parse(JSONObject("""{"ok":true,"changed":2,"failed":[{"path":"v/x","code":"sync_vault"},
            {"path":"../y","code":"sync_vault"},{"path":"z","code":"BAD"},5]}"""))
        assertEquals(2, r.changed)
        assertEquals(listOf(MoveResult.Failed("v/x", "sync_vault")), r.failed)
        rejects("bad_reply") { SyncSet.parse(JSONObject("""{"ok":true}""")) }
        rejects("bad_reply") { SyncSet.parse(JSONObject("""{"ok":true,"changed":-1}""")) }
        // 500개씩 나눠 보내고, 뒤 묶음이 통째로 거부되면 그 묶음만 실패로
        val sent = ArrayList<Int>()
        val all = List(1200) { "f$it" } + "f0"
        val out = SyncSet.run(all) { chunk ->
            sent += chunk.size
            if (sent.size == 3) throw ProtocolException("vault_locked")
            SyncSet.Result(chunk.size, emptyList())
        }
        assertEquals(listOf(500, 500, 200), sent)
        assertEquals(1000, out.changed)
        assertEquals(200, out.failed.size)
        assertTrue(out.failed.all { it.code == "vault_locked" })
        rejects("store_locked") { SyncSet.run(listOf("a")) { throw ProtocolException("store_locked") } }
    }

    @Test fun listingCarriesSyncFlag() {
        val page = CloudListing.parse(JSONObject("""{"items":[{"name":"a.jpg","dir":false,"size":1,"mtime":1,"sync":true},
            {"name":"b.jpg","dir":false,"size":1,"mtime":1,"sync":"yes"},{"name":"c","dir":true,"size":0,"mtime":1}]}"""))
        assertEquals(listOf(true, false, false), page.items.map { it.sync })
    }
}
