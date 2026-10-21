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

/** 2.2.0 암호화 클라우드 저장소(§17)의 앱 쪽 형식: 저장소 상태, cloud_get 범위·딸린 버전, 라이트박스가 고를 것. */
class Cloud3Test {
    private fun rejects(code: String, block: () -> Unit) {
        try { block(); fail("expected $code") } catch (e: ProtocolException) { assertEquals(code, e.code) }
    }

    private fun item(name: String, dir: Boolean = false, variants: Any? = null): JSONObject {
        val o = JSONObject().put("name", name).put("dir", dir).put("size", 10).put("mtime", 1)
        if (variants != null) o.put("variants", variants)
        return o
    }

    private fun parse(vararg items: JSONObject) = CloudListing.parse(JSONObject().put("items", JSONArray(items.toList()))).items

    // ---------- §17.4 딸린 버전 ----------

    @Test fun variantsParsed() {
        val got = parse(
            item("a.jpg", variants = JSONArray(listOf("screen", "thumb"))),
            item("b.mp4", variants = JSONArray(listOf("play", "poster", "thumb", "hdr", 5, JSONObject.NULL))),
            item("c.txt"),
            item("d.jpg", variants = "screen"), // 배열이 아니면 없음
            item("e", dir = true, variants = JSONArray(listOf("thumb"))), // 폴더에는 없다
        )
        assertEquals(setOf("screen", "thumb"), got[0].variants)
        assertEquals(setOf("play", "poster", "thumb"), got[1].variants) // 모르는 이름·문자열이 아닌 것은 버린다
        assertEquals(emptySet<String>(), got[2].variants)
        assertEquals(emptySet<String>(), got[3].variants)
        assertEquals(emptySet<String>(), got[4].variants)
        // 검색 결과도 같은 해석
        val search = CloudSearch.parse(JSONObject().put("items", JSONArray().put(
            item("x.jpg", variants = JSONArray(listOf("screen"))).put("path", "sub/x.jpg"))))
        assertEquals(setOf("screen"), search.page.items[0].variants)
    }

    // ---------- §17.2 허브 저장소 상태 ----------

    private fun hello(json: String) = Xfer.parseHello(JSONObject(json))

    @Test fun hubStoreStates() {
        val caps = """"caps":["xfer","cloud","cloud2","update","move","store"]"""
        assertEquals("open", hello("""{"ok":true,$caps,"cloud":true,"store":"open"}""").store)
        assertNull(hello("""{"ok":true,$caps,"cloud":true,"store":"weird"}""").store)
        assertNull(hello("""{"ok":true,$caps,"cloud":true,"store":3}""").store)
        assertEquals(HubCloud.ON, HubStore.state(hello("""{"ok":true,$caps,"cloud":true,"store":"open"}""")))
        assertEquals(HubCloud.STORE_LOCKED, HubStore.state(hello("""{"ok":true,$caps,"cloud":false,"store":"locked"}""")))
        assertEquals(HubCloud.STORE_LOCKED, HubStore.state(hello("""{"ok":true,$caps,"cloud":false,"store":"new"}""")))
        assertEquals(HubCloud.STORE_MISSING, HubStore.state(hello("""{"ok":true,$caps,"cloud":false,"store":"missing"}""")))
        // 꺼짐(store 없음)·예전 허브(store를 모름)
        assertEquals(HubCloud.OFF, HubStore.state(hello("""{"ok":true,$caps,"cloud":false}""")))
        assertEquals(HubCloud.OFF, HubStore.state(hello("""{"ok":true,"caps":["xfer","cloud"],"cloud":false}""")))
        assertEquals(HubCloud.OLD, HubStore.state(hello("""{"ok":true,"caps":["xfer"],"cloud":false}""")))
        assertEquals(HubCloud.OLD, HubStore.state(null))
    }

    @Test fun strongLockFromHello() {
        val caps = """"caps":["xfer","cloud","store","store_unlock"]"""
        assertEquals(true, hello("""{"ok":true,$caps,"cloud":true,"store":"open","strong":true}""").strong)
        assertEquals(false, hello("""{"ok":true,$caps,"cloud":true,"store":"open"}""").strong)
        assertEquals(false, hello("""{"ok":true,$caps,"cloud":true,"store":"open","strong":"yes"}""").strong)
    }

    // ---------- §17.3 cloud_get 범위 ----------

    @Test fun rangeFields() {
        val f = CloudRange.fields("v/a.mp4", 4096, 1024, "play", 99, 7)
        assertEquals(4096L, f.getLong("offset"))
        assertEquals(1024L, f.getLong("length"))
        assertEquals("play", f.getString("variant"))
        assertEquals(99L, f.getLong("size"))
        assertEquals(7L, f.getLong("mtime"))
        // 예전 모양(없는 것은 보내지 않는다 — 예전 허브와 같은 요청)
        val old = CloudRange.fields("a.jpg", 0, null, null, null, null)
        assertEquals(setOf("path", "offset"), old.keys().asSequence().toSet())
        rejects("bad_path") { CloudRange.fields("", 0, null, null, null, null) }
        rejects("bad_path") { CloudRange.fields("a//b", 0, null, null, null, null) }
        for (bad in listOf<() -> Unit>(
            { CloudRange.fields("a", -1, null, null, null, null) },
            { CloudRange.fields("a", 0, -1, null, null, null) },
            { CloudRange.fields("a", 0, null, "hdr", null, null) },
        )) try { bad(); fail("expected IllegalArgumentException") } catch (e: IllegalArgumentException) { }
    }

    @Test fun rangeEnd() {
        assertEquals(100L, CloudRange.end(10, null, 100))
        assertEquals(60L, CloudRange.end(10, 50, 100))
        assertEquals(100L, CloudRange.end(90, 50, 100)) // 끝에서 잘린다
        assertEquals(100L, CloudRange.end(100, 50, 100)) // 받을 것 없음
        assertEquals(10L, CloudRange.end(10, 0, 100))
        rejects("cloud_changed") { CloudRange.end(101, 5, 100) }
    }

    private val key = ByteArray(32) { (it * 7).toByte() }
    private val prefix = Protocol.prefix(Protocol.KIND_MSG, ByteArray(16) { (it + 3).toByte() })

    private fun hubSends(block: (Channel) -> Unit): Channel {
        val out = ByteArrayOutputStream()
        block(Channel(ByteArrayInputStream(ByteArray(0)), out, key, prefix, isClient = false))
        return Channel(ByteArrayInputStream(out.toByteArray()), ByteArrayOutputStream(), key, prefix, isClient = true)
    }

    /** 허브처럼 offset부터 length만 1 MiB 프레임으로(마지막 last=1, 받을 것이 없으면 빈 프레임 하나). */
    private fun serveRange(file: ByteArray, offset: Int, length: Int): Channel = hubSends { hub ->
        hub.send(CloudWire.head(file.size.toLong(), 5), last = false)
        val end = minOf(file.size, offset + length)
        var pos = offset
        if (pos == end) hub.send(ByteArray(0), last = true)
        while (pos < end) {
            val n = minOf(Protocol.CHUNK, end - pos)
            hub.send(file.copyOfRange(pos, pos + n), last = pos + n == end)
            pos += n
        }
    }

    private fun readRange(ch: Channel, offset: Long, length: Long): Pair<CloudWire.Head, ByteArray> {
        val (raw, last) = ch.recv()
        val head = (CloudWire.parseFirst(raw, last) as CloudWire.First.Ok).head
        val end = CloudRange.end(offset, length, head.size)
        val sink = CloudRange.Sink((end - offset).toInt())
        CloudWire.readData(ch, offset, end, sink)
        assertEquals(sink.bytes.size, sink.count)
        return head to sink.bytes
    }

    @Test fun rangeFramesIntoMemory() {
        val file = ByteArray(5 * Protocol.CHUNK + 77) { (it % 253).toByte() }
        // 가운데 범위(프레임 여러 개): 첫 프레임의 size는 늘 전체 크기
        val (head, data) = readRange(serveRange(file, 1000, CloudRange.MAX_LEN), 1000, CloudRange.MAX_LEN.toLong())
        assertEquals(file.size.toLong(), head.size)
        assertArrayEquals(file.copyOfRange(1000, 1000 + CloudRange.MAX_LEN), data)
        // 끝에 걸친 범위는 끝에서 잘린다
        val tail = file.size - 10
        assertArrayEquals(file.copyOfRange(tail, file.size), readRange(serveRange(file, tail, 4096), tail.toLong(), 4096).second)
        // length 0 / 끝에서: 빈 last=1 데이터 프레임 하나
        assertEquals(0, readRange(serveRange(file, 5, 0), 5, 0).second.size)
        assertEquals(0, readRange(serveRange(file, file.size, 100), file.size.toLong(), 100).second.size)
    }

    @Test fun rangeSinkOverflow() {
        // 허브가 요청보다 많이 보냄: readData가 끝을 넘는 프레임을 막는다(버퍼는 넘치지 않는다)
        val file = ByteArray(1000) { it.toByte() }
        val ch = serveRange(file, 0, 1000)
        val (raw, last) = ch.recv()
        CloudWire.parseFirst(raw, last)
        rejects("file_too_big") { CloudWire.readData(ch, 0, 100, CloudRange.Sink(100)) }
        val sink = CloudRange.Sink(4)
        sink.write(byteArrayOf(1, 2, 3), 0, 3)
        rejects("file_too_big") { sink.write(byteArrayOf(4, 5), 0, 2) }
        sink.write(9)
        assertArrayEquals(byteArrayOf(1, 2, 3, 9), sink.bytes)
    }

    // ---------- 라이트박스가 고를 것 ----------

    private fun ci(name: String, size: Long, vararg v: String) = CloudItem(name, false, size, 1, variants = v.toSet())

    @Test fun lightboxChoices() {
        val max = 64L * 1024 * 1024
        assertEquals(CloudView.First.SCREEN, CloudView.first(ci("a.jpg", 30_000_000, "screen", "thumb"), false, max))
        assertEquals(CloudView.First.SCREEN, CloudView.first(ci("a.jpg", max + 1, "screen"), false, max))
        assertEquals(CloudView.First.ORIGINAL, CloudView.first(ci("a.jpg", 500_000, "thumb"), false, max))
        assertEquals(CloudView.First.THUMB, CloudView.first(ci("a.jpg", max + 1), false, max))
        assertEquals(CloudView.First.POSTER, CloudView.first(ci("a.mp4", 9, "poster", "play"), true, max))
        assertEquals(CloudView.First.THUMB, CloudView.first(ci("a.mp4", 9, "thumb"), true, max))
        // 원본으로 바꾸기는 screen을 먼저 보인 64 MB 이하 사진만
        assertTrue(CloudView.canUpgrade(ci("a.jpg", max, "screen"), false, max))
        assertFalse(CloudView.canUpgrade(ci("a.jpg", max + 1, "screen"), false, max))
        assertFalse(CloudView.canUpgrade(ci("a.jpg", 10), false, max))
        assertFalse(CloudView.canUpgrade(ci("a.mp4", 10, "screen"), true, max))
        assertEquals("play", CloudView.playVariant(ci("a.mp4", 1, "play", "poster")))
        assertNull(CloudView.playVariant(ci("a.mp4", 1, "poster")))
    }

    @Test fun overPixel() {
        // 맞춤 0.5배: 1배까지는 화면용으로 충분, 넘으면 원본
        assertFalse(ZoomMath.overPixel(0.5f, 0.5f))
        assertFalse(ZoomMath.overPixel(1f, 0.5f))
        assertTrue(ZoomMath.overPixel(1.2f, 0.5f))
        // 작은 그림(맞춤이 이미 1배 넘음): 맞춤에서는 받지 않고, 더 키우면 받는다
        assertFalse(ZoomMath.overPixel(2f, 2f))
        assertTrue(ZoomMath.overPixel(2.5f, 2f))
    }
}
