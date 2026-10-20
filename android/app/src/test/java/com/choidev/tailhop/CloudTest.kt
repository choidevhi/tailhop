package com.choidev.tailhop

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/** 클라우드 모드(docs/PROTOCOL.md §12) — 경로 규칙, 목록 정렬·쪽, cloud_get 선 형식, 빈 폴더 기록, 보낼 곳 목록의 새 종류. */
class CloudTest {
    private val v = JSONObject(File(System.getProperty("tailhop.vectors")!!).readText(Charsets.UTF_8))

    private fun rejects(code: String, block: () -> Unit) {
        try { block(); fail("expected $code") } catch (e: ProtocolException) { assertEquals(code, e.code) }
    }

    // ---------- §12.1 경로 ----------

    @Test fun pathVectors() {
        val c = v.getJSONObject("cloud_paths")
        val ok = c.getJSONArray("ok")
        val bad = c.getJSONArray("bad")
        assertTrue(ok.length() >= 5 && bad.length() >= 16)
        for (i in 0 until ok.length()) {
            val p = ok.getString(i)
            assertTrue("ok: $p", CloudPath.isValid(p))
            assertEquals(if (p.isEmpty()) emptyList() else p.split('/'), CloudPath.segments(p))
        }
        for (i in 0 until bad.length()) {
            val p = bad.getString(i)
            assertFalse("bad: $p", CloudPath.isValid(p))
            rejects("bad_path") { CloudPath.segments(p) }
        }
    }

    @Test fun pathLimits() {
        val seg = "x".repeat(150)
        // 조각 16개는 되고 17개는 안 된다.
        assertTrue(CloudPath.isValid(List(16) { "d" }.joinToString("/")))
        assertFalse(CloudPath.isValid(List(17) { "d" }.joinToString("/")))
        // 전체 1024자(코드포인트): 조각 수가 16 이하여도 길이로 걸린다.
        assertFalse(CloudPath.isValid(List(7) { seg }.joinToString("/"))) // 1056자
        val exactly = List(6) { seg }.joinToString("/") + "/" + "y".repeat(1024 - 906)
        assertEquals(1024, exactly.length)
        assertTrue(CloudPath.isValid(exactly))
        assertFalse(CloudPath.isValid(exactly + "y"))
        // 진짜 역슬래시(벡터의 "a\b"는 JSON에서 백스페이스로 읽힌다)도 막는다.
        assertFalse(CloudPath.isValid("a\\b"))
        assertFalse(CloudPath.isValid("a/b\\c"))
    }

    @Test fun pathField() {
        assertEquals("", CloudPath.field(JSONObject()))
        assertEquals("", CloudPath.field(JSONObject().put("path", JSONObject.NULL)))
        assertEquals("a/b", CloudPath.field(JSONObject().put("path", "a/b")))
        assertEquals("x", CloudPath.field(JSONObject().put("dir", "x"), "dir"))
        rejects("bad_path") { CloudPath.field(JSONObject().put("path", 1)) }
        rejects("bad_path") { CloudPath.field(JSONObject().put("path", "../a")) }
        assertEquals("a", CloudPath.parent("a/b"))
        assertEquals("", CloudPath.parent("a"))
        assertEquals("b", CloudPath.name("a/b"))
        assertEquals("a/b", CloudPath.join("a", "b"))
        assertEquals("b", CloudPath.join("", "b"))
    }

    @Test fun listableAndStorable() {
        assertTrue(CloudPath.isListable("a.txt"))
        assertFalse(CloudPath.isListable("a.txt.part"))
        assertFalse(CloudPath.isListable("a.txt.PART")) // 대소문자만 다른 별칭으로 조각을 열지 못한다
        assertEquals("b.Part_", CloudPath.storableName("b.Part"))
        assertFalse(CloudPath.isListable(".hidden"))
        assertFalse(CloudPath.isListable("CON"))
        assertEquals("a.txt", CloudPath.storableName("a.txt"))
        assertEquals("a.part_", CloudPath.storableName("a.part"))
        assertTrue(CloudPath.isListable(CloudPath.storableName("a.part")))
    }

    // ---------- 목록 정렬·쪽 ----------

    private fun f(n: String, t: Long = 0) = CloudItem(n, false, 1, t)
    private fun d(n: String) = CloudItem(n, true, 0, 0)

    @Test fun listingOrder() {
        val page = CloudListing.page(listOf(f("b.txt"), d("zeta"), f("A.txt"), d("Alpha"), f("a.txt"), d("beta")), 0)
        assertEquals(listOf("Alpha", "beta", "zeta", "A.txt", "a.txt", "b.txt"), page.items.map { it.name })
        assertNull(page.next)
    }

    @Test fun listingPages() {
        val all = (0 until 1203).map { f("f%04d".format(it)) }.shuffled(java.util.Random(7))
        val p1 = CloudListing.page(all, 0)
        assertEquals(500, p1.items.size)
        assertEquals(500, p1.next)
        val p2 = CloudListing.page(all, p1.next!!)
        assertEquals(1000, p2.next)
        val p3 = CloudListing.page(all, p2.next!!)
        assertEquals(203, p3.items.size)
        assertNull(p3.next)
        assertEquals((0 until 1203).map { "f%04d".format(it) }, (p1.items + p2.items + p3.items).map { it.name })
        // 정확히 끝에서 끝나면 next 없음, 목록보다 큰 cursor는 빈 쪽
        assertNull(CloudListing.page(all.take(500), 0).next)
        assertEquals(CloudPage(emptyList(), null), CloudListing.page(all, 5000))
        assertEquals(2, CloudListing.page(all, 0, limit = 2).next)
    }

    @Test fun listingCursor() {
        assertEquals(0, CloudListing.cursor(JSONObject()))
        assertEquals(0, CloudListing.cursor(JSONObject().put("cursor", JSONObject.NULL)))
        assertEquals(500, CloudListing.cursor(JSONObject().put("cursor", 500)))
        for (bad in listOf<Any>(-1, "1", true, 1.5, 1L shl 40)) rejects("bad_header") { CloudListing.cursor(JSONObject().put("cursor", bad)) }
    }

    @Test fun listingReplyRoundTrip() {
        val page = CloudPage(listOf(d("사진"), CloudItem("a b (1).txt", false, 12345, 1_760_000_000_123)), 500)
        val wire = JSONObject(CloudListing.reply(page).toString())
        assertEquals(true, wire.get("ok"))
        assertEquals(page, CloudListing.parse(wire))
        val last = JSONObject(CloudListing.reply(CloudPage(page.items, null)).toString())
        assertTrue(last.isNull("next"))
        assertNull(CloudListing.parse(last).next)
    }

    @Test fun listingParseDropsBadItems() {
        val items = JSONArray()
            .put(JSONObject().put("name", "ok.txt").put("dir", false).put("size", 1).put("mtime", 2))
            .put(JSONObject().put("name", "../x").put("dir", false).put("size", 1).put("mtime", 2))
            .put(JSONObject().put("name", "p.part").put("dir", false).put("size", 1).put("mtime", 2))
            .put(JSONObject().put("name", "s").put("dir", "no").put("size", 1).put("mtime", 2))
            .put(JSONObject().put("name", "n").put("dir", false).put("size", -1).put("mtime", 2))
            .put(JSONObject().put("name", "m").put("dir", true).put("size", 0).put("mtime", 1.5))
            .put("not an object")
        val page = CloudListing.parse(JSONObject().put("ok", true).put("items", items))
        assertEquals(listOf("ok.txt"), page.items.map { it.name })
        rejects("bad_reply") { CloudListing.parse(JSONObject().put("ok", true)) }
        rejects("bad_reply") { CloudListing.parse(JSONObject().put("items", JSONArray()).put("next", "500")) }
        rejects("bad_reply") { CloudListing.parse(JSONObject().put("items", JSONArray()).put("next", 0)) }
    }

    // ---------- cloud_get 선 형식 ----------

    private val key = ByteArray(32) { (it * 3).toByte() }
    private val prefix = Protocol.prefix(Protocol.KIND_MSG, ByteArray(16) { it.toByte() })

    /** 허브(서버)가 보낸 프레임들을 클라이언트 쪽 Channel로 다시 읽는다. */
    private fun hubSends(block: (Channel) -> Unit): Channel {
        val out = ByteArrayOutputStream()
        block(Channel(ByteArrayInputStream(ByteArray(0)), out, key, prefix, isClient = false))
        return Channel(ByteArrayInputStream(out.toByteArray()), ByteArrayOutputStream(), key, prefix, isClient = true)
    }

    @Test fun getFramesResume() {
        val file = ByteArray(2 * Protocol.CHUNK + 99) { (it % 251).toByte() }
        val offset = 1000L
        val client = hubSends { hub ->
            hub.send(CloudWire.head(file.size.toLong(), 1_760_000_000_000), last = false)
            var pos = offset.toInt()
            while (pos < file.size) {
                val n = minOf(Protocol.CHUNK, file.size - pos)
                hub.send(file.copyOfRange(pos, pos + n), last = pos + n == file.size)
                pos += n
            }
        }
        val (raw, last) = client.recv()
        val first = CloudWire.parseFirst(raw, last)
        assertEquals(CloudWire.First.Ok(CloudWire.Head(file.size.toLong(), 1_760_000_000_000)), first)
        val got = ByteArrayOutputStream()
        val seen = ArrayList<Long>()
        assertEquals(file.size.toLong(), CloudWire.readData(client, offset, file.size.toLong(), got) { seen += it })
        assertArrayEquals(file.copyOfRange(offset.toInt(), file.size), got.toByteArray())
        assertEquals(file.size.toLong(), seen.last())
    }

    @Test fun getFramesNothingLeft() {
        // offset == size: 머리 뒤 빈 last=1 데이터 프레임 하나
        val client = hubSends { hub -> hub.send(CloudWire.head(5, 7), last = false); hub.send(ByteArray(0), last = true) }
        val (raw, last) = client.recv()
        assertEquals(CloudWire.First.Ok(CloudWire.Head(5, 7)), CloudWire.parseFirst(raw, last))
        val out = ByteArrayOutputStream()
        assertEquals(5L, CloudWire.readData(client, 5, 5, out))
        assertEquals(0, out.size())
    }

    @Test fun getFirstFrameRefusedOrBroken() {
        val refused = CloudWire.parseFirst("""{"ok":false,"error":"x","code":"not_found"}""".toByteArray(), true)
        assertTrue(refused is CloudWire.First.Refused)
        assertEquals("not_found", (refused as CloudWire.First.Refused).reply.getString("code"))
        rejects("bad_reply") { CloudWire.parseFirst("""{"ok":true,"size":1,"mtime":2}""".toByteArray(), true) }
        rejects("bad_reply") { CloudWire.parseFirst("""{"ok":false,"code":"not_found"}""".toByteArray(), false) }
        rejects("bad_reply") { CloudWire.parseFirst("""{"ok":true,"mtime":2}""".toByteArray(), false) }
        rejects("bad_reply") { CloudWire.parseFirst("""{"ok":true,"size":-1,"mtime":2}""".toByteArray(), false) }
        rejects("bad_reply") { CloudWire.parseFirst("""{"ok":true,"size":true,"mtime":2}""".toByteArray(), false) }
        rejects("bad_reply") { CloudWire.parseFirst("""{"ok":true,"size":1,"mtime":"2"}""".toByteArray(), false) }
        rejects("unreadable_reply") { CloudWire.parseFirst("nope".toByteArray(), false) }
    }

    @Test fun getDataLengthChecks() {
        val short = hubSends { hub -> hub.send(ByteArray(3), last = false); hub.send(ByteArray(3), last = true) }
        rejects("file_incomplete") { CloudWire.readData(short, 0, 10, ByteArrayOutputStream()) }
        val long = hubSends { hub -> hub.send(ByteArray(8), last = false); hub.send(ByteArray(8), last = true) }
        rejects("file_too_big") { CloudWire.readData(long, 0, 10, ByteArrayOutputStream()) }
        // 끊김(last=1 없이 끝남): 쓴 데까지는 남고 disconnected — 부른 쪽이 조각을 두고 다시 시도한다.
        val cut = hubSends { hub -> hub.send(ByteArray(4) { 9 }, last = false) }
        val partial = ByteArrayOutputStream()
        rejects("disconnected") { CloudWire.readData(cut, 0, 10, partial) }
        assertEquals(4, partial.size())
    }

    @Test fun getRequestChecks() {
        assertEquals(CloudWire.GetReq("a.txt", 0, null, null), CloudWire.parseGet(JSONObject().put("path", "a.txt")))
        assertEquals(CloudWire.GetReq("a.txt", 5, 10, 99),
            CloudWire.parseGet(JSONObject().put("path", "a.txt").put("offset", 5).put("size", 10).put("mtime", 99)))
        rejects("bad_header") { CloudWire.parseGet(JSONObject().put("path", "a").put("offset", -1)) }
        rejects("bad_header") { CloudWire.parseGet(JSONObject().put("path", "a").put("offset", "1")) }
        rejects("bad_header") { CloudWire.parseGet(JSONObject().put("path", "a").put("size", true)) }
        rejects("bad_path") { CloudWire.parseGet(JSONObject().put("path", "a/../b")) }
        val now = CloudWire.Head(10, 99)
        CloudWire.checkGet(CloudWire.GetReq("a", 10, 10, 99), now)
        CloudWire.checkGet(CloudWire.GetReq("a", 3, null, null), now)
        rejects("cloud_changed") { CloudWire.checkGet(CloudWire.GetReq("a", 3, 11, 99), now) }
        rejects("cloud_changed") { CloudWire.checkGet(CloudWire.GetReq("a", 3, 10, 98), now) }
        rejects("bad_header") { CloudWire.checkGet(CloudWire.GetReq("a", 11, null, null), now) }
        // 파일이 줄어 offset이 크기를 넘었어도 size를 줬으면 cloud_changed가 먼저(처음부터 다시 받게)
        rejects("cloud_changed") { CloudWire.checkGet(CloudWire.GetReq("a", 11, 20, 99), now) }
    }

    // ---------- API 29+ 허브의 폴더 기록·목록 ----------

    @Test fun cloudDirsAndTree() {
        var t = 100L
        val dirs = CloudDirs { t }
        dirs.add("a/b/c")
        t = 200
        dirs.add("x")
        assertTrue(dirs.has("") && dirs.has("a") && dirs.has("a/b") && dirs.has("a/b/c") && dirs.has("x"))
        assertFalse(dirs.has("a/c"))
        assertEquals(setOf("a", "x"), dirs.children("").toSet())
        assertEquals(listOf("b"), dirs.children("a"))

        val files = listOf(
            CloudEntry("", "top.txt", 3, 5000, "1"),
            CloudEntry("", "hidden.part", 3, 5000, "2"),
            CloudEntry("photos", "p.jpg", 10, 9000, "3"),
            CloudEntry("photos/2026", "q.jpg", 10, 9500, "4"),
        )
        val root = CloudTree.list(files, dirs, "").associateBy { it.name }
        assertEquals(setOf("top.txt", "photos", "a", "x"), root.keys)
        assertEquals(CloudItem("photos", true, 0, 9500), root["photos"]) // 안의 가장 새 파일 시각
        assertEquals(CloudItem("a", true, 0, 100), root["a"]) // 빈 폴더는 기록 시각
        assertEquals(setOf("p.jpg", "2026"), CloudTree.list(files, dirs, "photos").map { it.name }.toSet())
        assertTrue(CloudTree.dirExists(files, dirs, "photos/2026"))
        assertTrue(CloudTree.dirExists(files, dirs, "a/b"))
        assertFalse(CloudTree.dirExists(files, dirs, "top.txt"))
        assertFalse(CloudTree.dirExists(files, dirs, "nope"))
        assertEquals("3", CloudTree.find(files, "photos/p.jpg")?.id)
        assertNull(CloudTree.find(files, "hidden.part"))
        assertNull(CloudTree.find(files, "photos"))
        assertEquals(setOf("3", "4"), CloudTree.under(files, "photos").map { it.id }.toSet())

        val back = CloudDirs.parse(dirs.encode())
        assertEquals(dirs.size, back.size)
        assertEquals(100L, back.time("a/b"))
        assertTrue(back.removeTree("a"))
        assertFalse(back.has("a") || back.has("a/b") || back.has("a/b/c"))
        assertTrue(back.has("x"))
        assertFalse(back.removeTree("a"))
        // 깨진 기록·규칙에 맞지 않는 경로는 버린다
        val junk = CloudDirs.parse("""{"ok":1,"../x":2,"":3,"y":"no"}""")
        assertEquals(1, junk.size)
        assertEquals(0, CloudDirs.parse("not json").size)
    }

    // ---------- 보낼 곳 목록의 새 종류 ----------

    private val xfer = "00112233445566778899aabbccddeeff"

    @Test fun jobCodecCloudKinds() {
        val up = OutboxJob(id = "u", dev = "100.64.0.2", isText = false, uri = "content://x/1", name = "a.jpg", size = 10,
            mtime = 5, xfer = xfer, created = 1, kind = OutboxJob.CLOUD_UP, remote = "사진/2026")
        val down = OutboxJob(id = "d", dev = "100.64.0.2", isText = false, name = "b.mp4", size = 99, mtime = 7, xfer = xfer,
            created = 2, attempts = 3, nextAt = 50, kind = OutboxJob.CLOUD_DOWN, remote = "영상/b.mp4",
            part = "content://media/external/downloads/42", restarted = true, error = "disconnected")
        val text = OutboxJob(id = "t", dev = "100.64.0.3", isText = true, text = "hi", xfer = xfer, created = 3)
        val file = OutboxJob(id = "f", dev = "100.64.0.3", isText = false, uri = "content://y/2", xfer = xfer, created = 4)
        val list = listOf(up, down, text, file)
        assertEquals(list, OutboxCodec.parse(OutboxCodec.encode(list), 1000))
        // 대화 건에는 kind를 적지 않는다(1.7.0이 읽는 모양 그대로)
        val raw = JSONArray(OutboxCodec.encode(listOf(text, file)))
        assertFalse(raw.getJSONObject(0).has("kind") || raw.getJSONObject(1).has("kind"))
        assertEquals(OutboxJob.TEXT, text.kind)
        assertEquals(OutboxJob.FILE, file.kind)
        assertTrue(up.isCloud && down.isCloud && !text.isCloud && !file.isCloud)
        assertEquals("100.64.0.2|cloud", up.lane)
        assertEquals("100.64.0.3", file.lane)
    }

    @Test fun jobCodecDropsBadCloudJobs() {
        fun one(o: JSONObject) = OutboxCodec.parse(JSONArray().put(o).toString(), 0)
        fun base() = JSONObject().put("id", "x").put("dev", "100.64.0.2").put("t", false).put("xfer", xfer).put("created", 1)
        assertEquals(1, one(base().put("kind", "cloud_up").put("uri", "content://a").put("remote", "")).size)
        assertEquals(0, one(base().put("kind", "cloud_up").put("uri", "content://a")).size) // 폴더 없음
        assertEquals(0, one(base().put("kind", "cloud_up").put("uri", "content://a").put("remote", "../a")).size)
        assertEquals(0, one(base().put("kind", "cloud_up").put("remote", "a")).size) // 파일 없음
        assertEquals(1, one(base().put("kind", "cloud_down").put("remote", "a/b.txt")).size)
        assertEquals(0, one(base().put("kind", "cloud_down").put("remote", "")).size) // 맨 위는 파일이 아니다
        assertEquals(0, one(base().put("kind", "cloud_down").put("remote", "a//b")).size)
        assertEquals(0, one(base().put("kind", "teleport").put("uri", "content://a")).size)
        assertEquals(0, one(base().put("t", true).put("kind", "cloud_up").put("text", "x").put("remote", "")).size)
        // 보내는 중으로 남은 클라우드 건도 바로 다시 시도할 대기로
        val sending = one(base().put("kind", "cloud_down").put("remote", "a.txt").put("state", "sending"))
        assertEquals(OutboxJob.WAITING, sending[0].state)
        assertEquals(0L, sending[0].nextAt)
    }

    @Test fun cloudChangedRestartsOnce() {
        val j = OutboxJob(id = "d", dev = "100.64.0.2", isText = false, name = "b", size = 99, mtime = 7, xfer = xfer,
            created = 0, kind = OutboxJob.CLOUD_DOWN, remote = "b", part = "content://media/1")
        val again = Retry.afterChanged(j, 500)
        assertEquals(OutboxJob.WAITING, again.state)
        assertEquals(500L, again.nextAt)
        assertNull(again.part)
        assertNull(again.mtime)
        assertEquals(-1L, again.size)
        assertTrue(again.restarted)
        val failed = Retry.afterChanged(again.copy(part = "content://media/2", size = 5, mtime = 8), 900)
        assertTrue(failed.failed)
        assertEquals("cloud_changed", failed.error)
        assertNull(failed.part)
        // 사용자가 다시 받기를 고르면 다시 한 번 처음부터 받을 기회가 생긴다
        assertFalse(Retry.restart(failed, 1000).restarted)
        // cloud_changed는 보통 재시도 목록에 없다(가져오기만 따로 다룬다)
        assertFalse(Retry.isRetryable(ProtocolException("cloud_changed")))
        assertFalse(Retry.isRetryable(ProtocolException("cloud_off")))
        assertFalse(Retry.isRetryable(ProtocolException("not_found")))
    }

    // ---------- hello·조각 장부 ----------

    @Test fun helloAnnouncesCloud() {
        val on = Xfer.parseHello(JSONObject(Xfer.helloReply(0, false, cloud = true).toString()))
        assertTrue("cloud" in on.caps && "xfer" in on.caps && on.cloud)
        val off = Xfer.parseHello(JSONObject(Xfer.helloReply(0, false, cloud = false).toString()))
        assertTrue("cloud" in off.caps)
        assertFalse(off.cloud)
    }

    @Test fun partialDestRoundTrip() {
        val book = TransferBook { 1000L }
        book.begin("100.64.0.2", xfer, 10, "content://media/1", "a.txt", "Cloud/사진")
        book.begin("100.64.0.2", "ffeeddccbbaa99887766554433221100", 10, "content://media/2", "b.txt")
        val back = TransferBook.parse(book.encode()) { 1000L }
        assertEquals("Cloud/사진", back.partial("100.64.0.2", xfer, 10)?.dest)
        assertEquals("", back.partial("100.64.0.2", "ffeeddccbbaa99887766554433221100", 10)?.dest)
        assertFalse(JSONObject(book.encode()).getJSONArray("partials").getJSONObject(1).has("dest"))
    }
}
