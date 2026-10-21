package com.choidev.tailhop

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 2.1.0 여러 항목 옮기기(§16)의 요청·응답 형식과 나눠 보내기, 다중 선택 범위, 확대 보기 계산. */
class CloudMoveTest {
    private fun rejects(code: String, block: () -> Unit) {
        try { block(); fail("expected $code") } catch (e: ProtocolException) { assertEquals(code, e.code) }
    }

    @Test fun moveRequest() {
        val f = CloudMove.fields(listOf("a.jpg", "사진/b.png"), "보관")
        assertEquals(listOf("a.jpg", "사진/b.png"), f.getJSONArray("paths").let { a -> (0 until a.length()).map { a.getString(it) } })
        assertEquals("보관", f.getString("dest"))
        assertEquals("", CloudMove.fields(listOf("a"), "").getString("dest")) // 맨 위로도 옮긴다
        assertEquals(200, CloudMove.fields(List(200) { "f$it" }, "d").getJSONArray("paths").length())
        for (bad in listOf<() -> Unit>(
            { CloudMove.fields(emptyList(), "d") },
            { CloudMove.fields(List(201) { "f$it" }, "d") },
            { CloudMove.fields(listOf(""), "d") }, // 맨 위는 옮길 수 없다
            { CloudMove.fields(listOf("../a"), "d") },
            { CloudMove.fields(listOf("a"), "d/") },
        )) try { bad(); fail("expected IllegalArgumentException") } catch (e: IllegalArgumentException) { }
    }

    @Test fun moveReply() {
        val moved = JSONArray()
            .put(JSONObject().put("path", "a.jpg").put("name", "a (1).jpg"))
            .put(JSONObject().put("path", "x/b.png").put("name", "b.png"))
            .put(JSONObject().put("path", "").put("name", "c")) // 빈 경로
            .put(JSONObject().put("path", "../c").put("name", "c")) // 규칙 위반
            .put(JSONObject().put("path", "d").put("name", "d.part")) // 보이지 않는 이름
            .put(JSONObject().put("path", "e").put("name", "x/e")) // 이름에 경로
            .put(JSONObject().put("path", "f")) // 이름 없음
            .put("g")
            .put(JSONObject().put("path", 5).put("name", "h"))
        val failed = JSONArray()
            .put(JSONObject().put("path", "dir").put("code", "bad_move"))
            .put(JSONObject().put("path", "v/a").put("code", "vault_move"))
            .put(JSONObject().put("path", "busy").put("code", "cloud_denied"))
            .put(JSONObject().put("path", "x").put("code", "Bad Code")) // 코드 모양이 아님
            .put(JSONObject().put("path", "y")) // 코드 없음
            .put(JSONObject().put("path", "//").put("code", "not_found"))
        val r = CloudMove.parse(JSONObject().put("ok", true).put("moved", moved).put("failed", failed))
        assertEquals(listOf(MoveResult.Moved("a.jpg", "a (1).jpg"), MoveResult.Moved("x/b.png", "b.png")), r.moved)
        assertEquals(listOf("dir" to "bad_move", "v/a" to "vault_move", "busy" to "cloud_denied"), r.failed.map { it.path to it.code })
        // 한쪽만 있어도 된다
        assertEquals(0, CloudMove.parse(JSONObject().put("ok", true).put("moved", JSONArray())).failed.size)
        assertEquals(3, CloudMove.parse(JSONObject().put("ok", true).put("failed", failed)).failed.size)
        // 둘 다 없거나 배열이 아니면 형식 위반
        rejects("bad_reply") { CloudMove.parse(JSONObject().put("ok", true)) }
        rejects("bad_reply") { CloudMove.parse(JSONObject().put("ok", true).put("moved", "a").put("failed", JSONObject())) }
    }

    @Test fun moveInChunks() {
        val paths = List(450) { "f$it" } + listOf("f0", "f1") // 같은 경로는 한 번만
        val sent = ArrayList<List<String>>()
        val r = CloudMove.run(paths) { chunk ->
            sent += chunk
            MoveResult(chunk.drop(1).map { MoveResult.Moved(it, it) }, listOf(MoveResult.Failed(chunk[0], "cloud_denied")))
        }
        assertEquals(listOf(200, 200, 50), sent.map { it.size })
        assertEquals(List(450) { "f$it" }, sent.flatten())
        assertEquals(447, r.moved.size)
        assertEquals(listOf("f0", "f200", "f400"), r.failed.map { it.path })
        assertEquals(listOf("cloud_denied" to 3), CloudMove.failureCounts(r))
    }

    @Test fun moveChunkRefused() {
        // 첫 묶음이 통째로 거부되면 그대로 던진다(아무것도 옮기지 않음)
        rejects("not_found") { CloudMove.run(List(300) { "f$it" }) { throw ProtocolException("not_found") } }
        // 뒤 묶음만 거부되면 앞 결과는 살리고 그 묶음을 그 코드로 failed에
        var n = 0
        val r = CloudMove.run(List(300) { "f$it" }) { chunk ->
            if (n++ == 0) MoveResult(chunk.map { MoveResult.Moved(it, it) }, emptyList()) else throw ProtocolException("connect_failed")
        }
        assertEquals(200, r.moved.size)
        assertEquals(100, r.failed.size)
        assertTrue(r.failed.all { it.code == "connect_failed" })
        assertEquals("f200", r.failed.first().path)
    }

    @Test fun failureCountsOrder() {
        val r = MoveResult(emptyList(), listOf(
            MoveResult.Failed("a", "vault_move"), MoveResult.Failed("b", "bad_move"),
            MoveResult.Failed("c", "vault_move"), MoveResult.Failed("d", "cloud_denied"),
        ))
        assertEquals(listOf("vault_move" to 2, "bad_move" to 1, "cloud_denied" to 1), CloudMove.failureCounts(r))
    }

    @Test fun cannotMoveIntoItself() {
        val moving = listOf("사진", "문서/a.pdf")
        assertTrue(CloudMove.inside("사진", moving))
        assertTrue(CloudMove.inside("사진/2026", moving))
        assertTrue(CloudMove.inside("사진/2026", listOf("사진")))
        assertTrue(CloudMove.inside("PHOTOS/x", listOf("photos"))) // 허브처럼 대소문자 무관
        assertFalse(CloudMove.inside("사진2", moving)) // 이름 앞부분만 같은 다른 폴더
        assertFalse(CloudMove.inside("", moving))
        assertFalse(CloudMove.inside("문서", moving))
    }

    // ---------- 다중 선택 범위(끌어서 고르기) ----------

    @Test fun dragRange() {
        // 위치별 키(null = 항목이 아닌 줄: 머리·더 불러오기)
        val keys = listOf(null, "a", "b", null, "c", "d", "e")
        assertEquals(setOf("x", "a", "b", "c"), CloudSelect.range(setOf("x"), keys, anchor = 1, current = 4))
        // 위로 끌어도 같은 범위
        assertEquals(setOf("b", "c", "d"), CloudSelect.range(emptySet(), keys, anchor = 5, current = 2))
        // 다시 줄이면 처음 고른 것(base)과 남은 범위만
        assertEquals(setOf("a", "d"), CloudSelect.range(setOf("a"), keys, anchor = 5, current = 5))
        // 범위를 넘는 위치는 끝까지로
        assertEquals(setOf("d", "e"), CloudSelect.range(emptySet(), keys, anchor = 5, current = 99))
    }

    @Test fun dragAutoScroll() {
        // 위·아래 끝 edge 픽셀 안이면 가까울수록 빠르게, 밖이면 0
        assertEquals(0, CloudSelect.autoScroll(y = 500f, height = 1000, edge = 100f, max = 40))
        assertEquals(-40, CloudSelect.autoScroll(y = 0f, height = 1000, edge = 100f, max = 40))
        assertEquals(40, CloudSelect.autoScroll(y = 1000f, height = 1000, edge = 100f, max = 40))
        assertEquals(20, CloudSelect.autoScroll(y = 950f, height = 1000, edge = 100f, max = 40))
        assertEquals(-40, CloudSelect.autoScroll(y = -30f, height = 1000, edge = 100f, max = 40)) // 목록 밖으로 나가도
    }

    // ---------- 확대 보기 ----------

    @Test fun zoomFitAndBounds() {
        // 4000×3000 그림을 1000×2000 화면에: 폭에 맞춤(0.25), 위아래 가운데
        val fit = ZoomMath.fit(4000, 3000, 1000, 2000)
        assertEquals(0.25f, fit, 1e-6f)
        assertEquals(0f, ZoomMath.clampOffset(123f, 4000 * fit, 1000), 1e-3f)
        assertEquals((2000 - 750) / 2f, ZoomMath.clampOffset(0f, 3000 * fit, 2000), 1e-3f)
        // 확대해 화면보다 크면 가장자리 안으로
        assertEquals(0f, ZoomMath.clampOffset(50f, 2000f, 1000), 1e-3f)
        assertEquals(-1000f, ZoomMath.clampOffset(-1500f, 2000f, 1000), 1e-3f)
        assertEquals(-300f, ZoomMath.clampOffset(-300f, 2000f, 1000), 1e-3f)
        // 작은 그림도 화면에 맞게 키운다
        assertEquals(4f, ZoomMath.fit(250, 100, 1000, 2000), 1e-6f)
        // 최대 = 맞춤의 6배 이상
        assertTrue(ZoomMath.maxScale(fit) >= fit * 6)
        assertTrue(ZoomMath.maxScale(4f) >= 24f)
    }

    @Test fun zoomAroundFocus() {
        // 초점(fx, fy) 아래의 그림 점은 배율이 바뀌어도 그 자리에 남는다
        val (tx, ty) = ZoomMath.zoomAt(tx = -100f, ty = 50f, from = 1f, to = 2f, fx = 300f, fy = 400f)
        val imgX = (300f - -100f) / 1f
        val imgY = (400f - 50f) / 1f
        assertEquals(300f, tx + imgX * 2f, 1e-3f)
        assertEquals(400f, ty + imgY * 2f, 1e-3f)
    }
}
