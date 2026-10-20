package com.choidev.tailhop

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException

/** 전송 신뢰성 확장(docs/PROTOCOL.md §11) — 형식·해시·재시도·이어받기 장부. */
class XferTest {
    private val v = JSONObject(File(System.getProperty("tailhop.vectors")!!).readText(Charsets.UTF_8))

    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun rejects(code: String, block: () -> Unit) {
        try { block(); fail("expected $code") } catch (e: ProtocolException) { assertEquals(code, e.code) }
    }

    // ---------- 트레일러 ----------

    @Test fun trailerVector() {
        val t = v.getJSONObject("xfer_trailer")
        val data = hex(t.getString("data_hex"))
        val md = Xfer.sha256().apply { update(data) }
        val trailer = Xfer.trailer(md.digest())
        assertEquals("""{"sha256":"${t.getString("sha256")}"}""", String(trailer, Charsets.UTF_8))
        assertEquals(t.getString("sha256"), Xfer.parseTrailer(trailer))
    }

    @Test fun trailerRejectsBadFormat() {
        val good = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
        for (bad in listOf("", "{}", "[]", "not json", """{"sha256":1}""", """{"sha256":"${good.uppercase()}"}""",
            """{"sha256":"${good.dropLast(1)}"}""", """{"sha256":"${good}0"}""", """{"sha":"$good"}"""))
            rejects("bad_xfer") { Xfer.parseTrailer(bad.toByteArray()) }
    }

    /** 이어 보내기: 보내는 쪽은 앞 have 바이트를 자기 파일에서, 받는 쪽은 자기 조각에서 해시해도 같은 값이 나온다. */
    @Test fun resumedHashMatchesWholeFile() {
        val file = ByteArray(3 * 1024 * 1024 + 17) { (it * 31 + 7).toByte() }
        val whole = Xfer.hex(Xfer.sha256().digest(file))
        for (have in listOf(0, 1, 1024 * 1024, file.size - 1, file.size)) {
            // 보내는 쪽: 앞부분 해시 → 나머지를 1 MiB씩 보내며 해시 이어 감
            val send = Xfer.sha256().apply { update(file, 0, have) }
            val frames = ArrayList<ByteArray>()
            var pos = have
            while (pos < file.size) {
                val n = minOf(Protocol.CHUNK, file.size - pos)
                frames += file.copyOfRange(pos, pos + n); send.update(file, pos, n); pos += n
            }
            val trailer = Xfer.trailer(send.digest())
            // 받는 쪽: 조각 앞부분(같은 바이트) + 받은 데이터
            val recv = Xfer.sha256().apply { update(file, 0, have) }
            frames.forEach { recv.update(it) }
            assertEquals("have=$have", whole, Xfer.parseTrailer(trailer))
            assertEquals("have=$have", whole, Xfer.hex(recv.digest()))
        }
    }

    /** 실제 암호 프레임으로: 데이터 프레임은 모두 last=0이고 트레일러 하나만 last=1. */
    @Test fun framesOnTheWire() {
        val key = ByteArray(32) { it.toByte() }
        val out = ByteArrayOutputStream()
        val client = Protocol.openClient(ByteArrayInputStream(ByteArray(0)), out, Protocol.KIND_MSG, key)
        val data = "hello".toByteArray()
        val header = JSONObject().put("v", 1).put("type", "file").put("name", "a.txt").put("size", 5)
            .put("xfer", "00112233445566778899aabbccddeeff").put("offset", 2)
        client.send(header.toString().toByteArray(), last = false)
        client.send(data.copyOfRange(2, 5), last = false)
        client.send(Xfer.trailer(Xfer.sha256().digest(data)), last = true)

        val input = ByteArrayInputStream(out.toByteArray())
        val server = Protocol.openServer(input, ByteArrayOutputStream(), Protocol.readPrefix(input), key)
        val (h, l0) = server.recv()
        assertFalse(l0)
        val hj = JSONObject(String(h))
        assertEquals(2L, Xfer.offset(hj, 5))
        val md = Xfer.sha256().apply { update(data, 0, 2) }
        val (d1, l1) = server.recv()
        assertFalse(l1); md.update(d1)
        val (t, l2) = server.recv()
        assertTrue(l2)
        assertEquals(Xfer.parseTrailer(t), Xfer.hex(md.digest()))
        assertEquals(v.getJSONObject("xfer_trailer").getString("sha256"), Xfer.parseTrailer(t))
    }

    // ---------- 헤더 검사 ----------

    @Test fun xferFormat() {
        assertTrue(Xfer.isValid("00112233445566778899aabbccddeeff"))
        assertTrue(Xfer.isValid(Xfer.newXfer()))
        for (bad in listOf(null, 1, "", "00112233445566778899AABBCCDDEEFF", "00112233445566778899aabbccddeef",
            "00112233445566778899aabbccddeeff0", "g0112233445566778899aabbccddeeff"))
            assertFalse("$bad", Xfer.isValid(bad))
    }

    @Test fun offsetValidation() {
        fun h(json: String) = JSONObject(json)
        assertEquals(0L, Xfer.offset(h("{}"), 10)) // 없으면 0(PC와 같다)
        assertEquals(0L, Xfer.offset(h("""{"offset":0}"""), 10))
        assertEquals(10L, Xfer.offset(h("""{"offset":10}"""), 10))
        assertEquals(5_000_000_000L, Xfer.offset(h("""{"offset":5000000000}"""), 5_000_000_000L))
        for (bad in listOf("""{"offset":11}""", """{"offset":-1}""", """{"offset":1.5}""", """{"offset":true}""",
            """{"offset":"3"}""", """{"offset":null}"""))
            rejects("bad_xfer") { Xfer.offset(h(bad), 10) }
    }

    // ---------- hello ----------

    @Test fun helloReplyShape() {
        val r = Xfer.helloReply(1234, false)
        assertEquals(setOf("ok", "caps", "cloud", "have", "done"), r.keys().asSequence().toSet())
        assertEquals(true, r.get("ok"))
        assertEquals(1, r.getJSONArray("caps").length())
        assertEquals("xfer", r.getJSONArray("caps").getString(0))
        assertEquals(false, r.get("cloud"))
        assertEquals(1234L, r.getLong("have"))
        assertEquals(false, r.get("done"))
        // 끝난 xfer면 have는 0
        val d = Xfer.helloReply(99, true)
        assertEquals(0L, d.getLong("have"))
        assertEquals(true, d.get("done"))
        // 보내는 쪽이 같은 응답을 읽는다
        val parsed = Xfer.parseHello(JSONObject(r.toString()))
        assertTrue(parsed.xfer)
        assertEquals(1234L, parsed.have)
        assertFalse(parsed.done)
        assertFalse(parsed.cloud)
    }

    @Test fun helloParsingIsLenient() {
        val h = Xfer.parseHello(JSONObject("""{"ok":true,"caps":["xfer","cloud","future",3],"cloud":true,"have":7,"done":false}"""))
        assertEquals(setOf("xfer", "cloud", "future"), h.caps)
        assertTrue(h.cloud)
        assertEquals(7L, Xfer.resumeAt(h, 10))
        assertEquals(0L, Xfer.resumeAt(h, 5)) // have > size → 처음부터
        val empty = Xfer.parseHello(JSONObject("""{"ok":true}"""))
        assertFalse(empty.xfer)
        assertEquals(0L, empty.have)
        val weird = Xfer.parseHello(JSONObject("""{"ok":true,"caps":["xfer"],"have":true,"done":"yes"}"""))
        assertEquals(0L, weird.have)
        assertFalse(weird.done)
    }

    // ---------- 재시도 규칙(§11.3) ----------

    @Test fun retrySchedule() {
        assertEquals(listOf(3_000L, 10_000L, 30_000L, 60_000L, 120_000L, 300_000L, 300_000L, 300_000L),
            (1..8).map { Retry.delayMs(it) })
        assertEquals(300_000L, Retry.delayMs(100))
    }

    @Test fun retryableErrors() {
        for (c in listOf("connect_failed", "disconnected", "pc_forgot", "resume_lost", "hash_mismatch", "file_changed"))
            assertTrue(c, Retry.isRetryable(ProtocolException(c)))
        for (c in listOf("disk_full", "clock_skew", "bad_xfer", "file_gone", "unpaired", "auth_failed",
            "pc_refused", "pc_refused_reason", "storage", "file_too_large"))
            assertFalse(c, Retry.isRetryable(ProtocolException(c)))
        assertTrue(Retry.isRetryable(SocketTimeoutException()))
        assertTrue(Retry.isRetryable(IOException("reset")))
        assertFalse(Retry.isRetryable(SecurityException()))
        assertFalse(Retry.isRetryable(IllegalStateException()))
    }

    private fun job(created: Long) = OutboxJob(id = Protocol.newId(), dev = "100.64.0.1", isText = true, text = "hi",
        xfer = Xfer.newXfer(), created = created)

    @Test fun afterFailureBacksOffThenGivesUp() {
        val t0 = 1_700_000_000_000L
        var j = job(t0)
        val waits = ArrayList<Long>()
        var now = t0
        repeat(7) {
            j = Retry.afterFailure(j, now, true, "connect_failed", null)
            assertEquals(OutboxJob.WAITING, j.state)
            waits += j.nextAt - now
            now = j.nextAt
        }
        assertEquals(listOf(3_000L, 10_000L, 30_000L, 60_000L, 120_000L, 300_000L, 300_000L), waits)
        assertEquals(7, j.attempts)
        assertEquals("connect_failed", j.error)
        // 24시간이 지나면 실패(포기)
        val late = Retry.afterFailure(j, t0 + Xfer.KEEP_MS, true, "connect_failed", null)
        assertEquals(OutboxJob.FAILED, late.state)
        assertTrue(late.gaveUp)
        // 다시 시도하지 않는 실패는 바로
        val refused = Retry.afterFailure(job(t0), t0, false, "pc_refused_reason", "싫어요")
        assertEquals(OutboxJob.FAILED, refused.state)
        assertFalse(refused.gaveUp)
        assertEquals("싫어요", refused.detail)
        // 사용자가 다시 보내기: 24시간을 새로 세고 같은 xfer
        val again = Retry.restart(late, t0 + Xfer.KEEP_MS + 5)
        assertEquals(OutboxJob.WAITING, again.state)
        assertEquals(0, again.attempts)
        assertEquals(t0 + Xfer.KEEP_MS + 5, again.created)
        assertEquals(again.created, again.nextAt)
        assertNull(again.error)
        assertEquals(late.xfer, again.xfer)
    }

    @Test fun outboxRoundTripAndRecovery() {
        val now = 1_700_000_000_000L
        val a = job(now).copy(text = "긴 글 \n \"따옴표\"", attempts = 2, nextAt = now + 10_000, error = "connect_failed")
        val b = OutboxJob(id = Protocol.newId(), dev = "100.64.0.2", isText = false, uri = "content://x/1", name = "a.jpg",
            size = 10, mtime = 5, xfer = Xfer.newXfer(), created = now, state = OutboxJob.SENDING)
        val c = b.copy(id = Protocol.newId(), state = OutboxJob.FAILED, error = "pc_refused_reason", detail = "x", gaveUp = true)
        val back = OutboxCodec.parse(OutboxCodec.encode(listOf(a, b, c)), now + 1)
        assertEquals(a, back[0])
        // 프로세스가 끝나며 멈춘 "보내는 중"은 바로 다시 보낼 대기로
        assertEquals(b.copy(state = OutboxJob.WAITING, nextAt = now + 1), back[1])
        assertEquals(c, back[2])
        // 깨진 항목·형식이 틀린 xfer는 버린다
        assertTrue(OutboxCodec.parse("not json", now).isEmpty())
        val broken = """[{"id":"x"},{"id":"a","dev":"d","t":true,"text":"t","xfer":"zz","created":1}]"""
        assertTrue(OutboxCodec.parse(broken, now).isEmpty())
    }

    // ---------- 이어받기 장부 ----------

    private class Clock(var now: Long = 1_700_000_000_000L) { fun get() = now }

    private val x1 = "00112233445566778899aabbccddeeff"
    private val x2 = "ffeeddccbbaa99887766554433221100"

    @Test fun bookPartialLifecycle() {
        val c = Clock()
        val b = TransferBook(c::get)
        assertNull(b.partial("pc1", x1, 100))
        assertNull(b.begin("pc1", x1, 100, "loc1", "a.bin"))
        assertNotNull(b.partial("pc1", x1, 100))
        assertNull(b.partial("pc1", x1, 101)) // size가 다르면 남의 조각
        assertNull(b.partial("pc2", x1, 100)) // 다른 상대가 같은 xfer를 말해도 남의 조각
        // 끊김 → 남겨 둠 → 같은 size로 이어받기
        assertTrue(b.keep("pc1", x1, "loc1"))
        c.now += 1000
        val claimed = b.claim("pc1", x1, 100)!!
        assertEquals("loc1", claimed.loc)
        assertEquals(c.now, claimed.ts)
        assertNull(b.claim("pc1", x1, 99))
        // 끝 → 조각 기록은 없어지고 done
        b.finish("pc1", x1)
        assertNull(b.partial("pc1", x1, 100))
        assertTrue(b.isDone("pc1", x1))
        assertFalse(b.isDone("pc2", x1))
    }

    @Test fun bookReplaceDropAndStaleKeep() {
        val b = TransferBook(Clock()::get)
        b.begin("pc1", x1, 100, "old", "a")
        b.keep("pc1", x1, "old")
        // 처음부터 다시(offset 0): 예전 조각을 돌려줘 지우게 한다
        assertEquals("old", b.begin("pc1", x1, 100, "new", "a")!!.loc)
        // 늦게 끝난 예전 연결의 keep은 내 조각이 아니라고 알린다(부른 쪽이 지운다)
        assertFalse(b.keep("pc1", x1, "old"))
        assertEquals("new", b.partial("pc1", x1, 100)!!.loc)
        // 해시 불일치 등: 기록을 지운다(다른 위치면 건드리지 않음)
        b.drop("pc1", x1, "other")
        assertNotNull(b.partial("pc1", x1, 100))
        b.drop("pc1", x1, "new")
        assertNull(b.partial("pc1", x1, 100))
    }

    @Test fun bookExpiresAfter24h() {
        val c = Clock()
        val b = TransferBook(c::get)
        b.begin("pc1", x1, 100, "loc1", "a"); b.keep("pc1", x1, "loc1")
        b.begin("pc1", x2, 100, "loc2", "b") // 아직 받는 중(busy)
        b.finish("pc2", x1)
        c.now += Xfer.KEEP_MS + 1
        assertNull(b.partial("pc1", x1, 100)) // 지났으면 have 0
        assertFalse(b.isDone("pc2", x1))
        val gone = b.prune()
        assertEquals(listOf("loc1"), gone.map { it.loc }) // 받는 중인 조각은 지우지 않는다
        assertEquals(1, b.partialCount)
        assertEquals(0, b.doneCount)
    }

    @Test fun bookIsBounded() {
        val c = Clock()
        val b = TransferBook(c::get)
        repeat(TransferBook.MAX_PARTIALS + 5) { i ->
            val x = "%032x".format(i)
            b.begin("pc1", x, 1, "loc$i", "f"); b.keep("pc1", x, "loc$i")
            c.now += 1
        }
        repeat(TransferBook.MAX_DONE + 3) { i -> b.finish("pc2", "%032x".format(i)); c.now += 1 }
        val gone = b.prune()
        assertEquals((0 until 5).map { "loc$it" }, gone.map { it.loc }) // 오래된 것부터
        assertEquals(TransferBook.MAX_PARTIALS, b.partialCount)
        assertEquals(TransferBook.MAX_DONE, b.doneCount)
        assertFalse(b.isDone("pc2", "%032x".format(0)))
        assertTrue(b.isDone("pc2", "%032x".format(TransferBook.MAX_DONE + 2)))
    }

    @Test fun bookForgetPeerAndPersist() {
        val c = Clock()
        val b = TransferBook(c::get)
        b.begin("pc1", x1, 100, "loc1", "a.bin"); b.keep("pc1", x1, "loc1")
        b.begin("pc2", x1, 50, "loc2", "b.bin"); b.keep("pc2", x1, "loc2")
        b.finish("pc1", x2)
        val back = TransferBook.parse(b.encode(), c::get)
        assertEquals("loc1", back.partial("pc1", x1, 100)!!.loc)
        assertEquals("a.bin", back.partial("pc1", x1, 100)!!.name)
        assertTrue(back.isDone("pc1", x2))
        assertEquals(listOf("loc1"), back.forgetPeer("pc1").map { it.loc })
        assertFalse(back.isDone("pc1", x2))
        assertNotNull(back.partial("pc2", x1, 50))
        // 깨진 장부는 빈 장부
        assertEquals(0, TransferBook.parse("{", c::get).partialCount)
        assertEquals(0, TransferBook.parse(null, c::get).partialCount)
        val junk = """{"partials":[{"peer":"p","xfer":"bad","size":1,"loc":"l","ts":1},{"x":1}],"done":{"a|b":"x"}}"""
        val j = TransferBook.parse(junk, c::get)
        assertEquals(0, j.partialCount)
        assertEquals(0, j.doneCount)
    }

    @Test fun cloudVectorsArePresentForLater() {
        // §12(클라우드)는 아직 만들지 않는다. 벡터가 그대로 있는지만 본다(다음 버전에서 경로 검사 테스트로 쓴다).
        val cp = v.getJSONObject("cloud_paths")
        assertTrue(cp.getJSONArray("ok").length() > 0)
        assertTrue(cp.getJSONArray("bad").length() > 0)
        assertArrayEquals(hex("68656c6c6f"), "hello".toByteArray())
    }
}
