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

/** 업데이트(docs/PROTOCOL.md §13) — hello의 updates, 버전 비교, update_get 첫 프레임, SHA-256, 서명 비교. */
class UpdateTest {
    private fun rejects(code: String, block: () -> Unit) {
        try { block(); fail("expected $code") } catch (e: ProtocolException) { assertEquals(code, e.code) }
    }

    private fun hello(caps: List<String>, android: JSONObject?): JSONObject {
        val r = JSONObject().put("ok", true).put("caps", JSONArray(caps)).put("cloud", false).put("have", 0).put("done", false)
        if (android != null) r.put("updates", JSONObject().put("pc", JSONObject().put("version", "1.9.0").put("size", 5)).put("android", android))
        return r
    }

    private fun offer() = JSONObject().put("version", "1.9.0").put("code", 9).put("size", 931201)

    @Test fun helloUpdates() {
        val h = Xfer.parseHello(JSONObject(hello(listOf("xfer", "cloud", "update"), offer()).toString()))
        assertEquals(UpdateOffer("1.9.0", 9, 931201), h.update)
        // "update"를 알리지 않은 PC, updates가 없는 배포 PC가 아닌 PC
        assertNull(Xfer.parseHello(hello(listOf("xfer", "cloud"), offer())).update)
        assertNull(Xfer.parseHello(hello(listOf("xfer", "update"), null)).update)
        // 형식이 틀리면 없는 것으로
        for (bad in listOf(
            offer().put("code", 0), offer().put("code", "9"), offer().put("code", 9.5), offer().put("code", true),
            offer().put("size", 0), offer().put("size", Updates.MAX_APK + 1), offer().put("version", ""),
            offer().put("version", "1.9.0; rm"), offer().put("version", 190), JSONObject().put("version", "1.9.0").put("code", 9),
        )) assertNull(bad.toString(), Xfer.parseHello(hello(listOf("update"), bad)).update)
        // 이 폰은 업데이트를 내주지 않는다
        assertFalse("update" in Xfer.CAPS)
    }

    @Test fun versionCompare() {
        val o = UpdateOffer("1.9.0", 9, 1)
        assertTrue(Updates.isNewer(o, 8))
        assertFalse(Updates.isNewer(o, 9))
        assertFalse(Updates.isNewer(o, 10))
        assertFalse(Updates.isNewer(null, 1))
        val best = Updates.best(listOf("a" to UpdateOffer("1.9.0", 9, 1), "b" to UpdateOffer("1.9.1", 10, 1),
            "c" to UpdateOffer("1.8.0", 8, 1), "d" to UpdateOffer("1.9.1", 10, 2)), 8)
        assertEquals("b", best?.first)
        assertNull(Updates.best(listOf("a" to UpdateOffer("1.8.0", 8, 1)), 8))
        assertNull(Updates.best(emptyList<Pair<String, UpdateOffer>>(), 8))
    }

    private val sha = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824" // "hello"

    @Test fun firstFrame() {
        val ok = Updates.parseFirst("""{"ok":true,"version":"1.9.0","size":5,"sha256":"$sha"}""".toByteArray(), false)
        assertEquals(Updates.First.Ok(UpdateHead("1.9.0", 5, sha)), ok)
        val refused = Updates.parseFirst("""{"ok":false,"error":"x","code":"not_found"}""".toByteArray(), true)
        assertEquals("not_found", (refused as Updates.First.Refused).reply.getString("code"))
        rejects("bad_reply") { Updates.parseFirst("""{"ok":true,"version":"1.9.0","size":5,"sha256":"$sha"}""".toByteArray(), true) }
        rejects("bad_reply") { Updates.parseFirst("""{"ok":false,"code":"not_found"}""".toByteArray(), false) }
        for (bad in listOf(
            """{"ok":true,"size":5,"sha256":"$sha"}""",
            """{"ok":true,"version":"1.9.0","sha256":"$sha"}""",
            """{"ok":true,"version":"1.9.0","size":0,"sha256":"$sha"}""",
            """{"ok":true,"version":"1.9.0","size":5,"sha256":"${sha.uppercase()}"}""",
            """{"ok":true,"version":"1.9.0","size":5,"sha256":"${sha.dropLast(1)}"}""",
            """{"ok":true,"version":"1.9.0","size":"5","sha256":"$sha"}""",
        )) rejects("bad_reply") { Updates.parseFirst(bad.toByteArray(), false) }
        rejects("unreadable_reply") { Updates.parseFirst("nope".toByteArray(), false) }
    }

    @Test fun shaCheck() {
        val head = UpdateHead("1.9.0", 5, sha)
        Updates.checkSha(head, Xfer.sha256().apply { update("hello".toByteArray()) })
        rejects("hash_mismatch") { Updates.checkSha(head, Xfer.sha256().apply { update("hellO".toByteArray()) }) }
    }

    /** 실제 암호 프레임으로: 첫 프레임 + 데이터 프레임들(마지막 last=1)을 받아 크기·해시를 맞춘다. */
    @Test fun wireRoundTrip() {
        val key = ByteArray(32) { (it * 7).toByte() }
        val prefix = Protocol.prefix(Protocol.KIND_MSG, ByteArray(16) { (it + 3).toByte() })
        val apk = ByteArray(Protocol.CHUNK + 77) { (it % 253).toByte() }
        val digest = Xfer.hex(Xfer.sha256().digest(apk))
        val wire = ByteArrayOutputStream()
        val hub = Channel(ByteArrayInputStream(ByteArray(0)), wire, key, prefix, isClient = false)
        hub.send(JSONObject().put("ok", true).put("version", "1.9.0").put("size", apk.size).put("sha256", digest)
            .toString().toByteArray(), last = false)
        hub.send(apk.copyOfRange(0, Protocol.CHUNK), last = false)
        hub.send(apk.copyOfRange(Protocol.CHUNK, apk.size), last = true)
        val phone = Channel(ByteArrayInputStream(wire.toByteArray()), ByteArrayOutputStream(), key, prefix, isClient = true)
        val (raw, last) = phone.recv()
        val head = (Updates.parseFirst(raw, last) as Updates.First.Ok).head
        val md = Xfer.sha256()
        val out = ByteArrayOutputStream()
        java.security.DigestOutputStream(out, md).use { CloudWire.readData(phone, 0, head.size, it) }
        Updates.checkSha(head, md)
        assertArrayEquals(apk, out.toByteArray())
    }

    @Test fun signerCompare() {
        val a = Updates.certDigest("cert-a".toByteArray())
        val b = Updates.certDigest("cert-b".toByteArray())
        assertEquals(64, a.length)
        assertTrue(Updates.sameSigner(listOf(a), listOf(a)))
        assertTrue(Updates.sameSigner(listOf(a, b), listOf(b, a)))
        assertFalse(Updates.sameSigner(listOf(a), listOf(b)))
        assertFalse(Updates.sameSigner(listOf(a), listOf(a, b))) // 서명자가 더 있으면 다르다
        assertFalse(Updates.sameSigner(emptyList(), emptyList()))
        assertFalse(Updates.sameSigner(listOf(a), emptyList()))
    }
}
