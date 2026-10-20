package com.choidev.tailhop

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** 1.8.0 보안 검토에서 고친 것(A1–A6, H2–H4)의 순수 로직. */
class CloudReviewTest {
    private val x1 = "00112233445566778899aabbccddeeff"

    private fun xferN(i: Int) = "%032x".format(i)

    // ---------- A6: `.part` 이름이 낀 경로 ----------

    @Test fun visiblePath() {
        assertTrue(CloudPath.isVisiblePath(""))
        assertTrue(CloudPath.isVisiblePath("a/b.txt"))
        assertFalse(CloudPath.isVisiblePath("a.part/x.txt")) // 중간 폴더
        assertFalse(CloudPath.isVisiblePath("x/y.PART")) // 대소문자 무시
        assertFalse(CloudPath.isVisiblePath("a/b.part"))
        assertFalse(CloudPath.isVisiblePath("../a")) // 규칙 위반도 false(허브는 그 전에 bad_path)
        assertTrue(CloudPath.isValid("a.part/x.txt")) // 경로 규칙 자체는 통과 — 허브가 not_found로 막는다
    }

    // ---------- A5: 긴 이름 ----------

    private fun cp(s: String) = s.codePointCount(0, s.length)

    @Test fun longNamesLeaveRoomForNumbering() {
        val cases = listOf(
            "x".repeat(146) + ".txt", // 150자
            "y".repeat(145) + ".part", // 150자 + `_`
            "z".repeat(150), // 확장자 없음
            "😀".repeat(140) + ".jpeg", // 코드포인트로 센다(이모지 140개 = 280 UTF-16)
            "a".repeat(120) + "." + "b".repeat(29), // 확장자가 너무 길면 확장자 취급하지 않는다
        )
        for (raw in cases) {
            val n = CloudPath.storableName(Protocol.sanitizeFilename(raw))
            assertTrue("$raw -> $n", cp(n) <= 150 - CloudPath.NAME_ROOM)
            assertTrue(n, CloudPath.isListable(n))
            // 저장소가 " (99)"를 붙여도 보이는 이름으로 남는다
            assertTrue(n, CloudPath.isListable(Protocol.numberedName(n, 99)))
        }
        assertTrue(CloudPath.storableName("x".repeat(146) + ".txt").endsWith(".txt"))
        assertTrue(CloudPath.storableName("y".repeat(145) + ".part").endsWith(".part_"))
        // 짧은 이름은 그대로
        assertEquals("a.txt", CloudPath.storableName("a.txt"))
        assertEquals("x".repeat(144), CloudPath.storableName("x".repeat(144)))
    }

    // ---------- A1: 폴더 지우기는 보이는 것만 ----------

    @Test fun mediaStoreFolderRemoval() {
        val files = listOf(
            CloudEntry("d", "a.txt", 1, 1, "1"),
            CloudEntry("d", "b.part", 1, 1, "2"),
            CloudEntry("d/sub", "c.txt", 1, 1, "3"),
            CloudEntry("d/x.part", "e.txt", 1, 1, "4"),
            CloudEntry("other", "f.txt", 1, 1, "5"),
        )
        val r = CloudTree.removal(files, "d")
        assertEquals(setOf("1", "3"), r.delete.map { it.id }.toSet())
        assertTrue(r.left)
        val clean = CloudTree.removal(files, "d/sub")
        assertEquals(listOf("3"), clean.delete.map { it.id })
        assertFalse(clean.left)
    }

    @Test fun legacyFolderRemovalKeepsHiddenAndParts() {
        val root = Files.createTempDirectory("thcloud")
        try {
            val d = Files.createDirectories(root.resolve("d"))
            Files.write(d.resolve("a.txt"), byteArrayOf(1))
            Files.write(d.resolve(".nomedia"), byteArrayOf())
            Files.write(d.resolve("other.part"), byteArrayOf(2)) // 다른 PC가 받는 중
            Files.createDirectories(d.resolve("sub")).also { Files.write(it.resolve("b.txt"), byteArrayOf(3)) }
            Files.createDirectories(d.resolve("keep")).also { Files.write(it.resolve("c.PART"), byteArrayOf(4)) }
            assertFalse(CloudFiles.deleteVisible(d))
            assertFalse(Files.exists(d.resolve("a.txt")))
            assertFalse(Files.exists(d.resolve("sub")))
            assertTrue(Files.exists(d.resolve(".nomedia")))
            assertTrue(Files.exists(d.resolve("other.part")))
            assertTrue(Files.exists(d.resolve("keep/c.PART")))
            assertTrue(Files.isDirectory(d))

            val e = Files.createDirectories(root.resolve("e"))
            Files.createDirectories(e.resolve("f/g")).also { Files.write(it.resolve("h.txt"), byteArrayOf(5)) }
            Files.write(e.resolve("i.txt"), byteArrayOf(6))
            assertTrue(CloudFiles.deleteVisible(e))
            assertFalse(Files.exists(e))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    // ---------- A2: 조각 상한은 PC마다 ----------

    @Test fun partialCapIsPerPeer() {
        var t = 0L
        val b = TransferBook { t }
        repeat(TransferBook.MAX_PARTIALS) { i ->
            t = 100L + i
            b.begin("pc1", xferN(i), 1, "a$i", "f"); b.keep("pc1", xferN(i), "a$i")
        }
        // 다른 PC가 조각을 많이 만들어도 pc1의 조각은 밀려나지 않는다
        repeat(TransferBook.MAX_PARTIALS + 3) { i ->
            t = 1000L + i
            b.begin("pc2", xferN(1000 + i), 1, "b$i", "f"); b.keep("pc2", xferN(1000 + i), "b$i")
        }
        val gone = b.overflow("pc2") + b.overflow("pc1")
        assertEquals(listOf("b0", "b1", "b2"), gone.map { it.loc })
        assertNotNull(b.partial("pc1", xferN(0), 1))
        assertEquals(2 * TransferBook.MAX_PARTIALS, b.partialCount)
        // pc1이 하나 더 만들면 pc1의 가장 오래된 것만
        t = 5000
        b.begin("pc1", xferN(99), 1, "a99", "f"); b.keep("pc1", xferN(99), "a99")
        assertEquals(listOf("a0"), b.prune().map { it.loc })
        assertNotNull(b.partial("pc2", xferN(1003), 1))
    }

    // ---------- A3: 종류와 폴더가 모두 같아야 이어 쓴다 ----------

    @Test fun partialKindBinding() {
        val chatInCloud = PartialRec("pc", x1, 1, "l", "n", 0, "Cloud", PartialRec.FILE)
        assertTrue(chatInCloud.fits("Cloud", PartialRec.FILE))
        assertFalse(chatInCloud.fits("Cloud", PartialRec.CLOUD_PUT)) // 같은 폴더라도 cloud_put은 안 된다
        assertFalse(chatInCloud.fits("", PartialRec.FILE))
        val b = TransferBook { 0L }
        b.begin("pc", x1, 1, "l", "n", "Cloud/a", PartialRec.CLOUD_PUT)
        b.begin("pc", xferN(7), 1, "m", "n")
        val back = TransferBook.parse(b.encode()) { 0L }
        assertEquals(PartialRec.CLOUD_PUT, back.partial("pc", x1, 1)?.kind)
        assertEquals(PartialRec.FILE, back.partial("pc", xferN(7), 1)?.kind) // 1.7 기록(kind 없음)은 file
        assertFalse(JSONObject(b.encode()).getJSONArray("partials").getJSONObject(1).has("kind"))
    }

    // ---------- A4·H3: 조건으로 버리기, 공개 전에 떼기 ----------

    @Test fun forgetWhereAndRelease() {
        val b = TransferBook { 0L }
        b.begin("pc", xferN(1), 1, "put", "n", "Cloud/x", PartialRec.CLOUD_PUT); b.keep("pc", xferN(1), "put")
        b.begin("pc", xferN(2), 1, "chatCloud", "n", "Cloud", PartialRec.FILE); b.keep("pc", xferN(2), "chatCloud")
        b.begin("pc", xferN(3), 1, "chat", "n"); b.keep("pc", xferN(3), "chat")
        b.begin("pc", xferN(4), 1, "busyPut", "n", "Cloud", PartialRec.CLOUD_PUT) // 받는 중
        val gone = b.forgetWhere { it.kind == PartialRec.CLOUD_PUT || it.dest.isNotEmpty() }
        assertEquals(setOf("put", "chatCloud"), gone.map { it.loc }.toSet()) // 받는 중인 것은 파일을 돌려주지 않는다
        assertNull(b.partial("pc", xferN(4), 1)) // 기록은 지웠다
        assertFalse(b.keep("pc", xferN(4), "busyPut")) // 그 연결이 끝나면 자기 조각을 지운다
        assertNotNull(b.partial("pc", xferN(3), 1))

        b.begin("pc", xferN(5), 1, "done", "n")
        b.release("pc", xferN(5), "other") // 위치가 다르면 그대로
        assertNotNull(b.partial("pc", xferN(5), 1))
        b.release("pc", xferN(5), "done")
        assertNull(b.partial("pc", xferN(5), 1))
        b.finish("pc", xferN(5))
        assertTrue(b.isDone("pc", xferN(5)))
        assertTrue(b.prune().none { it.loc == "done" }) // 공개된 파일을 prune이 지우지 않는다
    }

    // ---------- H2: 목록 파일의 URI·조각 위치 ----------

    @Test fun outboxUriValidation() {
        val own = listOf("com.choidev.tailhop.files")
        assertTrue(OutboxCodec.isSourceUri("content://com.android.providers.media.documents/document/image%3A1", own))
        assertTrue(OutboxCodec.isSourceUri("content://media/external/images/media/5", own))
        assertFalse(OutboxCodec.isSourceUri("content://com.choidev.tailhop.files/tailhop/a.txt", own))
        assertFalse(OutboxCodec.isSourceUri("content://0@com.choidev.tailhop.files/tailhop/a.txt", own))
        assertFalse(OutboxCodec.isSourceUri("content://COM.CHOIDEV.TAILHOP.FILES/x", own))
        assertFalse(OutboxCodec.isSourceUri("file:///sdcard/a.txt", own))
        assertFalse(OutboxCodec.isSourceUri("content://", own))
        assertFalse(OutboxCodec.isSourceUri(null, own))

        assertTrue(OutboxCodec.isPartLoc("content://media/external_primary/downloads/42"))
        assertTrue(OutboxCodec.isPartLoc("/storage/emulated/0/Download/TailHop/a.bin.part"))
        assertFalse(OutboxCodec.isPartLoc("content://media/external/images/media/42"))
        assertFalse(OutboxCodec.isPartLoc("content://com.choidev.tailhop.files/x"))
        assertFalse(OutboxCodec.isPartLoc("/storage/emulated/0/Download/TailHop/../../a.part"))
        assertFalse(OutboxCodec.isPartLoc("/data/data/com.choidev.tailhop/files/outbox.json"))
        assertFalse(OutboxCodec.isPartLoc("/storage/emulated/0/Download/TailHop/a.bin"))

        fun base() = JSONObject().put("id", "x").put("dev", "100.64.0.2").put("t", false).put("xfer", x1).put("created", 1)
        fun one(o: JSONObject) = OutboxCodec.parse(JSONArray().put(o).toString(), 0, own)
        assertEquals(0, one(base().put("uri", "content://com.choidev.tailhop.files/tailhop/a")).size)
        assertEquals(0, one(base().put("kind", "cloud_up").put("uri", "file:///x").put("remote", "")).size)
        assertEquals(1, one(base().put("uri", "content://media/external/images/media/5")).size)
        assertEquals(0, one(base().put("kind", "cloud_down").put("remote", "a.txt").put("part", "/data/x.part")).size)
        assertEquals(1, one(base().put("kind", "cloud_down").put("remote", "a.txt")
            .put("part", "content://media/external/downloads/9")).size)
    }

    // ---------- H4: 영구 읽기 권한 돌려주기 ----------

    @Test fun urisToRelease() {
        fun job(id: String, uri: String?) = OutboxJob(id = id, dev = "d", isText = uri == null, text = if (uri == null) "t" else null,
            uri = uri, xfer = x1, created = 0)
        val gone = listOf(job("1", "content://a/1"), job("2", "content://a/2"), job("3", null))
        val remaining = listOf(job("4", "content://a/2"), job("5", null))
        assertEquals(setOf("content://a/1"), OutboxCodec.urisToRelease(gone, remaining))
        assertEquals(emptySet<String>(), OutboxCodec.urisToRelease(emptyList(), remaining))
    }
}
