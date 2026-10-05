package com.choidev.tailhop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 기록 목록 형식: 긴 텍스트는 앞부분만 목록에 두어 메모리가 글 길이와 상관없이 묶인다. */
class HistoryCodecTest {
    private fun item(i: Int, text: String) =
        HistoryItem(Protocol.newId(), 1_000L + i, i % 2 == 0, true, text, null, text.length.toLong(), null, "100.64.0.1")

    @Test fun shortTextUnchanged() {
        val it = item(0, "짧은 글")
        val (kept, full) = HistoryCodec.split(it)
        assertEquals(it, kept)
        assertNull(full)
    }

    @Test fun longTextSplit() {
        val long = "가나다라".repeat(10_000)
        val (kept, full) = HistoryCodec.split(item(0, long))
        assertEquals(HistoryCodec.TEXT_INLINE, kept.text!!.length)
        assertEquals(long.length, kept.textLen)
        assertEquals(long, full)
        // 이미 나눈 항목은 다시 나누지 않는다.
        assertNull(HistoryCodec.split(kept).second)
    }

    @Test fun roundTripKeepsTextLength() {
        val list = listOf(HistoryCodec.split(item(0, "x".repeat(5000))).first, item(1, "y"))
        val back = HistoryCodec.parse(HistoryCodec.encode(list))
        assertEquals(list, back)
        // 1.4 형식(tlen 없음)도 읽는다.
        val old = HistoryCodec.parse("""[{"id":"${"a".repeat(32)}","ts":1,"in":true,"t":true,"text":"hi","size":2}]""")
        assertEquals(0, old[0].textLen)
    }

    private fun usedHeap(): Long {
        val rt = Runtime.getRuntime()
        repeat(3) { System.gc(); Thread.sleep(50) }
        return rt.totalMemory() - rt.freeMemory()
    }

    /** 200개 중 100개가 긴 글(20만 자)인 기록: 1.4 형식은 목록을 읽을 때마다 전부 메모리에 올렸다. */
    @Test fun parsedListMemoryIsBounded() {
        val body = "긴 텍스트 Long text ".repeat(12_500) // 20만 자
        val items = (0 until 200).map { i -> item(i, if (i % 2 == 0) "[$i] $body" else "짧은 $i") }
        val oldJson = HistoryCodec.encode(items)
        val newJson = HistoryCodec.encode(items.map { HistoryCodec.split(it).first })

        val base = usedHeap()
        var oldList: List<HistoryItem>? = HistoryCodec.parse(oldJson)
        val oldRetained = usedHeap() - base
        assertEquals(200, oldList!!.size)
        oldList = null

        val base2 = usedHeap()
        val newList = HistoryCodec.parse(newJson)
        val newRetained = usedHeap() - base2
        assertEquals(200, newList.size)

        println("[memory] history JSON chars old=${oldJson.length} new=${newJson.length}; " +
            "parsed list retained old=${oldRetained / 1024} KiB new=${newRetained / 1024} KiB")
        assertTrue("old=$oldRetained new=$newRetained", newRetained < 2 * 1024 * 1024)
        assertTrue(newJson.length < oldJson.length / 50)
    }
}
