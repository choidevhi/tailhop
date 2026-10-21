package com.choidev.tailhop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2.2.1 영상 재생기의 계산(시간 글자·두 번 탭 탐색·끌어 탐색·이어 보기)과 라이트박스 확대 보기의 새 계산. */
class VideoMathTest {
    @Test fun timeText() {
        assertEquals("0:00", VideoMath.time(0))
        assertEquals("0:00", VideoMath.time(-5))
        assertEquals("0:09", VideoMath.time(9_999))
        assertEquals("1:05", VideoMath.time(65_000))
        assertEquals("59:59", VideoMath.time(3_599_000))
        assertEquals("1:00:00", VideoMath.time(3_600_000))
        assertEquals("12:03:04", VideoMath.time((12 * 3600 + 3 * 60 + 4) * 1000L))
        assertEquals("+0:15", VideoMath.delta(15_000))
        assertEquals("−1:00", VideoMath.delta(-60_000))
        assertEquals("1×", VideoMath.speed(1f))
        assertEquals("1.25×", VideoMath.speed(1.25f))
        assertEquals("0.5×", VideoMath.speed(0.5f))
        assertEquals("2×", VideoMath.speed(2f))
    }

    @Test fun tapSeekAccumulates() {
        val t = TapSeek(doubleMs = 300, keepMs = 800)
        assertNull(t.tap(1, 0)) // 첫 탭은 아직 모른다
        assertEquals(10_000L, t.tap(1, 200)) // 두 번 → +10
        assertEquals(20_000L, t.tap(1, 700)) // 이어서 한 번 더 → +20
        assertEquals(30_000L, t.tap(1, 1400)) // keepMs 안이면 계속
        assertFalse(t.idle(1500))
        assertTrue(t.idle(2300))
        // 시간이 지나 새로 누르면 다시 처음부터(한 번 탭)
        assertNull(t.tap(1, 3000))
        assertTrue(t.single(3400))
        assertFalse(t.single(3500)) // 한 번만
        // 왼쪽은 음수, 다른 쪽을 누르면 끊긴다
        assertNull(t.tap(-1, 5000))
        assertEquals(-10_000L, t.tap(-1, 5100))
        assertNull(t.tap(1, 5200))
        assertFalse(t.single(5300)) // 아직 doubleMs 안
        assertEquals(10_000L, t.tap(1, 5400))
        // 두 번 탭이 너무 느리면 각각 한 번 탭
        val u = TapSeek(doubleMs = 300)
        assertNull(u.tap(1, 0))
        assertNull(u.tap(1, 400))
    }

    @Test fun dragSeekAndLevel() {
        // 10분 영상: 화면 폭 = 3분
        assertEquals(60_000L + 90_000L, VideoMath.dragSeek(60_000, 500f, 1000, 600_000))
        assertEquals(0L, VideoMath.dragSeek(10_000, -1000f, 1000, 600_000))
        // 짧은 영상: 화면 폭 = 영상 전체, 끝을 넘지 않는다
        assertEquals(20_000L, VideoMath.dragSeek(15_000, 1000f, 1000, 20_000))
        assertEquals(5_000L, VideoMath.dragSeek(5_000, 300f, 1000, 0)) // 길이를 모르면 그대로
        assertEquals(1f, VideoMath.dragLevel(0.5f, -600f, 2000), 1e-4f)
        assertEquals(0.25f, VideoMath.dragLevel(0.5f, 300f, 2000), 1e-4f)
        assertEquals(0f, VideoMath.dragLevel(0.1f, 5000f, 2000), 1e-4f)
        assertEquals(239 to 100, VideoMath.pipRatio(3000, 1000))
        assertEquals(1920 to 1080, VideoMath.pipRatio(1920, 1080))
        assertEquals(100 to 239, VideoMath.pipRatio(100, 1000))
        assertEquals(16 to 9, VideoMath.pipRatio(0, 0))
    }

    @Test fun resumeBook() {
        val secret = ByteArray(32) { it.toByte() }
        val b = ResumeBook(secret)
        b.put("100.64.0.1", "영상/여행.mp4", 65_000, 600_000, now = 1)
        assertEquals(65_000L, b.get("100.64.0.1", "영상/여행.mp4"))
        assertNull(b.get("100.64.0.2", "영상/여행.mp4")) // 다른 허브는 따로
        // 처음 몇 초·끝 가까이면 지운다
        b.put("100.64.0.1", "영상/여행.mp4", 3_000, 600_000, now = 2)
        assertNull(b.get("100.64.0.1", "영상/여행.mp4"))
        b.put("pc", "a.mp4", 595_000, 600_000, now = 3)
        assertNull(b.get("pc", "a.mp4"))
        b.put("pc", "a.mp4", 100_000, 0, now = 4) // 길이를 모르면 앞만 뺀다
        assertEquals(100_000L, b.get("pc", "a.mp4"))
        // 저장 글에 이름·경로가 평문으로 남지 않는다
        val enc = b.encode()
        assertFalse(enc.contains("a.mp4"))
        assertFalse(enc.contains("pc"))
        // 다시 읽기, 다른 열쇠로는 못 찾는다
        assertEquals(100_000L, ResumeBook.parse(enc, secret).get("pc", "a.mp4"))
        assertNull(ResumeBook.parse(enc, ByteArray(32)).get("pc", "a.mp4"))
        assertEquals(0, ResumeBook.parse("{\"x\":\"1,2\",\"${"a".repeat(32)}\":\"no\"}", secret).size)
        assertEquals(0, ResumeBook.parse("not json", secret).size)
        // 많아지면 오래된 것부터
        val c = ResumeBook(secret)
        for (i in 0 until ResumeBook.MAX + 5) c.put("pc", "v$i.mp4", 60_000, 0, now = i.toLong())
        assertEquals(ResumeBook.MAX, c.size)
        assertNull(c.get("pc", "v0.mp4"))
        assertEquals(60_000L, c.get("pc", "v${ResumeBook.MAX + 4}.mp4"))
    }

    // ---------- 라이트박스 확대 보기(2.2.1) ----------

    @Test fun zoomSafeArea() {
        // 보이는 칸 100..900 안 가운데, 크면 칸 가장자리 안으로
        assertEquals(300f, ZoomMath.clampIn(0f, 400f, 100f, 900f), 1e-3f)
        assertEquals(100f, ZoomMath.clampIn(500f, 2000f, 100f, 900f), 1e-3f)
        assertEquals(-1100f, ZoomMath.clampIn(-5000f, 2000f, 100f, 900f), 1e-3f)
        assertEquals(-500f, ZoomMath.clampIn(-500f, 2000f, 100f, 900f), 1e-3f)
        // 원본 1픽셀이 화면 4픽셀 이상까지(화면용 2048 그림, 원본 8192면 16배)
        assertEquals(16f, ZoomMath.maxScale(0.3f, 4f), 1e-4f)
        assertEquals(4f, ZoomMath.maxScale(0.3f, 1f), 1e-4f)
        assertEquals(30f, ZoomMath.maxScale(5f, 1f), 1e-4f) // 작은 그림은 맞춤의 6배
        assertEquals(1000 to 500, ZoomMath.turned(1000, 500, 180))
        assertEquals(500 to 1000, ZoomMath.turned(1000, 500, 90))
        assertEquals(500 to 1000, ZoomMath.turned(1000, 500, 270))
    }

    @Test fun zoomKeepsSpotOnResize() {
        // 칸 가운데의 그림 점(ix, iy)이 새 칸 가운데에 오고, 맞춤 대비 배율은 그대로
        val (tx, ty, s) = ZoomMath.keepCenter(ix = 200f, iy = 300f, zoom = 2f, newFit = 0.5f, cx = 400f, cy = 600f)
        assertEquals(1f, s, 1e-6f)
        assertEquals(400f, tx + 200f * s, 1e-3f)
        assertEquals(600f, ty + 300f * s, 1e-3f)
    }
}
