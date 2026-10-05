package com.choidev.tailhop

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer

class VectorTest {
    private val v = JSONObject(File(System.getProperty("tailhop.vectors")!!).readText(Charsets.UTF_8))

    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun h(key: String, o: JSONObject = v) = hex(o.getString(key))

    private val s get() = h("S_hex")
    private val p get() = h("P_hex")
    private val salt get() = h("salt_hex")

    @Test fun keys() {
        assertArrayEquals(h("k_phone_to_pc_hex"), Protocol.deriveKey(s, Protocol.INFO_PHONE_TO_PC))
        assertArrayEquals(h("k_pc_to_phone_hex"), Protocol.deriveKey(s, Protocol.INFO_PC_TO_PHONE))
        assertArrayEquals(h("k_pair_hex"), Protocol.deriveKey(p, Protocol.INFO_PAIR))
    }

    @Test fun b64AndConfirm() {
        assertEquals(v.getString("S_b64url"), Protocol.b64uEncode(s))
        assertArrayEquals(s, Protocol.b64uDecode(v.getString("S_b64url")))
        assertEquals(v.getString("confirm_code"), Protocol.confirmCode(s))
    }

    @Test fun nonce() {
        assertArrayEquals(h("nonce_dir1_index258_last_hex"), Protocol.nonce(1, 258, true))
    }

    private fun checkSession(o: JSONObject, kind: Int, dirKey: ByteArray) {
        val prefix = Protocol.prefix(kind, salt)
        assertArrayEquals(h("prefix_hex", o), prefix)
        assertArrayEquals(h("session_key_hex", o), Protocol.sessionKey(dirKey, salt))

        // 클라이언트 쪽: 보내는 바이트가 벡터와 같아야 한다.
        val out = ByteArrayOutputStream()
        val replyFrame = h("reply_sealed_hex", o)
        val replyIn = ByteArrayInputStream(ByteBuffer.allocate(4).putInt(replyFrame.size).array() + replyFrame)
        val client = Protocol.openClient(replyIn, out, kind, dirKey, salt)
        client.send(h("frame0_plain_hex", o), last = false)
        client.send(ByteArray(0), last = true)
        val f0 = h("frame0_sealed_hex", o)
        val f1 = h("frame1_sealed_hex", o)
        val expected = prefix + ByteBuffer.allocate(4).putInt(f0.size).array() + f0 +
            ByteBuffer.allocate(4).putInt(f1.size).array() + f1
        assertArrayEquals(expected, out.toByteArray())
        val (reply, last) = client.recv()
        assertTrue(last)
        assertArrayEquals(h("reply_plain_hex", o), reply)

        // 서버 쪽: 같은 바이트를 받아 풀고, 응답이 벡터와 같아야 한다.
        val input = ByteArrayInputStream(out.toByteArray())
        val sOut = ByteArrayOutputStream()
        val pr = Protocol.readPrefix(input)
        val server = Protocol.openServer(input, sOut, pr, dirKey)
        val (h0, l0) = server.recv()
        assertArrayEquals(h("frame0_plain_hex", o), h0); assertFalse(l0)
        val (b1, l1) = server.recv()
        assertEquals(0, b1.size); assertTrue(l1)
        try { server.recv(); fail() } catch (e: ProtocolException) { }
        server.send(h("reply_plain_hex", o), last = true)
        assertArrayEquals(ByteBuffer.allocate(4).putInt(replyFrame.size).array() + replyFrame, sOut.toByteArray())
    }

    @Test fun messageSession() =
        checkSession(v.getJSONObject("msg_phone_to_pc"), Protocol.KIND_MSG, Protocol.deriveKey(s, Protocol.INFO_PHONE_TO_PC))

    @Test fun pairSession() =
        checkSession(v.getJSONObject("pair"), Protocol.KIND_PAIR, Protocol.deriveKey(p, Protocol.INFO_PAIR))

    @Test fun tamperedFrameRejected() {
        val o = v.getJSONObject("msg_phone_to_pc")
        val f0 = h("frame0_sealed_hex", o); f0[3] = (f0[3].toInt() xor 1).toByte()
        val bytes = h("prefix_hex", o) + ByteBuffer.allocate(4).putInt(f0.size).array() + f0
        val input = ByteArrayInputStream(bytes)
        val ch = Protocol.openServer(input, ByteArrayOutputStream(), Protocol.readPrefix(input),
            Protocol.deriveKey(s, Protocol.INFO_PHONE_TO_PC))
        try { ch.recv(); fail() } catch (e: ProtocolException) { }
    }

    @Test fun badLengthRejected() {
        val o = v.getJSONObject("msg_phone_to_pc")
        val bytes = h("prefix_hex", o) + ByteBuffer.allocate(4).putInt(Protocol.MAX_FRAME + 1).array()
        val input = ByteArrayInputStream(bytes)
        val ch = Protocol.openServer(input, ByteArrayOutputStream(), Protocol.readPrefix(input), ByteArray(32))
        try { ch.recv(); fail() } catch (e: ProtocolException) { }
    }

    @Test fun sanitize() {
        val m = v.getJSONObject("sanitize")
        for (k in m.keys()) assertEquals("input=$k", m.getString(k), Protocol.sanitizeFilename(k))
        assertEquals("a_b.txt", Protocol.sanitizeFilename("a\u0001b.txt"))
        assertEquals("_nul", Protocol.sanitizeFilename("nul"))
    }

    @Test fun pairUri() {
        val k = Protocol.b64uEncode(p)
        val ok = Protocol.parsePairUri("tailhop://pair?v=1&h=100.101.102.103&p=47100&n=%EB%82%B4%20PC&k=$k")
        assertEquals("100.101.102.103", ok.host); assertEquals(47100, ok.port); assertEquals("내 PC", ok.name)
        assertArrayEquals(p, ok.key)
        val bad = listOf(
            "http://pair?v=1&h=100.101.102.103&p=47100&n=a&k=$k",
            "tailhop://pair?v=2&h=100.101.102.103&p=47100&n=a&k=$k",
            "tailhop://pair?v=1&h=192.168.0.2&p=47100&n=a&k=$k",
            "tailhop://pair?v=1&h=100.128.0.1&p=47100&n=a&k=$k",
            "tailhop://pair?v=1&h=100.101.102.013&p=47100&n=a&k=$k",
            "tailhop://pair?v=1&h=100.101.102.103&p=0&n=a&k=$k",
            "tailhop://pair?v=1&h=100.101.102.103&p=65536&n=a&k=$k",
            "tailhop://pair?v=1&h=100.101.102.103&p=47100&n=a&k=AAEC",
            "tailhop://pair?v=1&h=100.101.102.103&p=47100&n=a&k=$k=",
            "tailhop://pair?v=1&h=100.101.102.103&h=100.101.102.104&p=47100&n=a&k=$k",
        )
        for (u in bad) try { Protocol.parsePairUri(u); fail(u) } catch (e: ProtocolException) { }
    }

    @Test fun replayGuard() {
        val f = File.createTempFile("replay", ".txt"); f.deleteOnExit()
        val now = 1_700_000_000_000L
        val id = "00112233445566778899aabbccddeeff"
        ReplayGuard(f).checkAndAdd(id, now, now)
        try { ReplayGuard(f).checkAndAdd(id, now, now + 1000); fail() } catch (e: ProtocolException) { }
        try { ReplayGuard(f).checkAndAdd("ab", now, now); fail() } catch (e: ProtocolException) { }
        try { ReplayGuard(f).checkAndAdd(Protocol.newId(), now - 300_001, now); fail() } catch (e: ProtocolException) { }
        try { ReplayGuard(f).checkAndAdd(Protocol.newId(), 1.5, now); fail() } catch (e: ProtocolException) { }
        ReplayGuard(f).checkAndAdd(id, now + 700_000, now + 700_000)
    }
}
