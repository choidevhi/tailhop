package com.choidev.tailhop

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 2.2.1 여러 개 이름 바꾸기(§18.2)의 새 이름 만들기·규칙 검사·미리보기·요청 형식과, 잠긴 폴더 사이로 옮기기(§18.1)의 열쇠. */
class CloudRenameTest {
    private fun rejects(code: String, block: () -> Unit) {
        try { block(); fail("expected $code") } catch (e: ProtocolException) { assertEquals(code, e.code) }
    }

    @Test fun splitExtension() {
        assertEquals("여행" to ".jpg", CloudRename.split("여행.jpg", false))
        assertEquals("a.tar" to ".gz", CloudRename.split("a.tar.gz", false))
        assertEquals("README" to "", CloudRename.split("README", false))
        assertEquals("사진.2026" to "", CloudRename.split("사진.2026", true)) // 폴더는 확장자가 없다
        assertEquals("a." to "", CloudRename.split("a.", false))
        assertEquals("x" to "", CloudRename.split("x", false))
        val long = "a." + "b".repeat(17) // 17자는 확장자로 보지 않는다
        assertEquals(long to "", CloudRename.split(long, false))
    }

    @Test fun rules() {
        val r = CloudRename.Rule(CloudRename.Mode.REPLACE, find = "IMG", replace = "사진")
        assertEquals("사진_001.jpg", CloudRename.apply(r, "img_001.jpg", false, 0)) // 기본은 대소문자 무시
        assertEquals("img_001.jpg", CloudRename.apply(r.copy(matchCase = true), "img_001.jpg", false, 0))
        assertEquals("a.jpg", CloudRename.apply(r.copy(find = ""), "a.jpg", false, 0)) // 찾을 말이 없으면 그대로
        assertEquals("2026_a.jpg", CloudRename.apply(CloudRename.Rule(CloudRename.Mode.PREFIX, text = "2026_"), "a.jpg", false, 0))
        assertEquals("a_편집.jpg", CloudRename.apply(CloudRename.Rule(CloudRename.Mode.SUFFIX, text = "_편집"), "a.jpg", false, 0))
        assertEquals("폴더_편집", CloudRename.apply(CloudRename.Rule(CloudRename.Mode.SUFFIX, text = "_편집"), "폴더", true, 0))
        val num = CloudRename.Rule(CloudRename.Mode.NUMBER, pattern = "여행_{n}", start = 1, digits = 3)
        assertEquals("여행_001.jpg", CloudRename.apply(num, "IMG_5512.jpg", false, 0))
        assertEquals("여행_012.MOV", CloudRename.apply(num, "b.MOV", false, 11))
        assertEquals("여행7", CloudRename.apply(num.copy(pattern = "여행", start = 7, digits = 1), "x", true, 0)) // {n}이 없으면 끝에
        assertEquals("1000", CloudRename.number(1000, 3)) // 넘치면 그대로 길게
        val case = CloudRename.Rule(CloudRename.Mode.CASE, case = CloudRename.Case.UPPER)
        assertEquals("HELLO WORLD.jpg", CloudRename.apply(case, "hello world.jpg", false, 0)) // 확장자는 그대로
        assertEquals("hello.JPG", CloudRename.apply(case.copy(case = CloudRename.Case.LOWER), "HELLO.JPG", false, 0))
        assertEquals("My_Trip-Day One.png", CloudRename.apply(case.copy(case = CloudRename.Case.TITLE), "my_trip-DAY one.png", false, 0))
    }

    @Test fun checkNames() {
        assertNull(CloudRename.check("여행_001.jpg"))
        assertNull(CloudRename.check(" 앞 공백.txt")) // 앞 공백은 이름의 일부(§5 정리가 그대로 둔다)
        assertEquals(CloudRename.EMPTY, CloudRename.check(""))
        assertEquals(CloudRename.BAD_NAME, CloudRename.check("a/b"))
        assertEquals(CloudRename.BAD_NAME, CloudRename.check("a:b"))
        assertEquals(CloudRename.BAD_NAME, CloudRename.check(".숨김"))
        assertEquals(CloudRename.BAD_NAME, CloudRename.check("끝 "))
        assertEquals(CloudRename.BAD_NAME, CloudRename.check("끝."))
        assertEquals(CloudRename.BAD_NAME, CloudRename.check("CON"))
        assertEquals(CloudRename.BAD_NAME, CloudRename.check(".."))
        assertEquals(CloudRename.BAD_NAME, CloudRename.check("a\u0000b"))
        assertEquals(CloudRename.TOO_LONG, CloudRename.check("가".repeat(151)))
        assertNull(CloudRename.check("가".repeat(150)))
        assertEquals(CloudRename.PART, CloudRename.check("영상.mp4.PART"))
    }

    @Test fun planFollowsHubOrder() {
        val sib = mapOf("" to listOf("a.jpg", "b.jpg", "c.jpg", "B (2).jpg"), "x" to listOf("a.jpg"))
        fun plan(vararg p: Pair<String, String>) = CloudRename.plan(p.map { it.first to false }, sib) { old, _, i ->
            p.first { it.first.endsWith(old) }.second.ifEmpty { "n$i" }
        }
        // 이미 있는 이름(대소문자 무관)은 exists, 번호를 붙이지 않는다
        assertEquals(CloudRename.EXISTS, plan("a.jpg" to "C.JPG")[0].problem)
        // 대소문자만 바꾸기는 된다
        assertNull(plan("a.jpg" to "A.JPG")[0].problem)
        // 그대로면 보내지 않는다
        assertEquals(CloudRename.UNCHANGED, plan("a.jpg" to "a.jpg")[0].problem)
        // 앞에서 비운 이름은 뒤에서 쓸 수 있다(a→z, b→a)
        val swap = plan("a.jpg" to "z.jpg", "b.jpg" to "a.jpg")
        assertNull(swap[0].problem)
        assertNull(swap[1].problem)
        // 서로 바꾸기(a→b, b→a)는 차례대로라 앞의 것이 exists
        val cross = plan("a.jpg" to "b.jpg", "b.jpg" to "a.jpg")
        assertEquals(CloudRename.EXISTS, cross[0].problem)
        assertEquals(CloudRename.EXISTS, cross[1].problem) // a가 바뀌지 않았으니 a는 아직 있다
        // 고른 것끼리 같은 새 이름이면 두 번째가 duplicate
        val dup = plan("a.jpg" to "same.jpg", "b.jpg" to "SAME.jpg")
        assertNull(dup[0].problem)
        assertEquals(CloudRename.DUPLICATE, dup[1].problem)
        // 다른 폴더의 같은 이름은 상관없다
        assertNull(plan("x/a.jpg" to "b.jpg")[0].problem)
        // 규칙 위반이 먼저
        assertEquals(CloudRename.BAD_NAME, plan("a.jpg" to "a?.jpg")[0].problem)
        // 보낼 것만
        assertEquals(listOf(CloudRename.Item("a.jpg", "z.jpg"), CloudRename.Item("b.jpg", "a.jpg")), CloudRename.sendable(swap))
    }

    @Test fun planNumbering() {
        val rule = CloudRename.Rule(CloudRename.Mode.NUMBER, pattern = "여행_{n}", start = 1, digits = 3)
        val items = listOf("IMG_1.jpg" to false, "IMG_2.heic" to false, "폴더" to true)
        val p = CloudRename.plan(items, mapOf("" to items.map { it.first })) { old, dir, i -> CloudRename.apply(rule, old, dir, i) }
        assertEquals(listOf("여행_001.jpg", "여행_002.heic", "여행_003"), p.map { it.new })
        assertTrue(p.all { it.problem == null })
    }

    @Test fun requestAndReply() {
        val a = CloudRename.itemsJson(listOf(CloudRename.Item("사진/a.jpg", "b.jpg")))
        assertEquals("사진/a.jpg", a.getJSONObject(0).getString("path"))
        assertEquals("b.jpg", a.getJSONObject(0).getString("name"))
        for (bad in listOf<() -> Unit>(
            { CloudRename.itemsJson(emptyList()) },
            { CloudRename.itemsJson(List(201) { CloudRename.Item("f$it", "g$it") }) },
            { CloudRename.itemsJson(listOf(CloudRename.Item("", "b"))) },
            { CloudRename.itemsJson(listOf(CloudRename.Item("a", "b/c"))) },
        )) try { bad(); fail("expected IllegalArgumentException") } catch (e: IllegalArgumentException) { }
        val reply = JSONObject().put("ok", true)
            .put("renamed", JSONArray().put(JSONObject().put("path", "a").put("name", "b")).put(JSONObject().put("path", "c").put("name", "../d")))
            .put("failed", JSONArray().put(JSONObject().put("path", "e").put("code", "exists")))
        val r = CloudRename.parse(reply)
        assertEquals(listOf(MoveResult.Moved("a", "b")), r.moved)
        assertEquals(listOf(MoveResult.Failed("e", "exists")), r.failed)
        rejects("bad_reply") { CloudRename.parse(JSONObject().put("ok", true)) }
    }

    @Test fun runInChunks() {
        val items = List(450) { CloudRename.Item("f$it", "g$it") }
        val sizes = ArrayList<Int>()
        val r = CloudRename.run(items + items[0]) { chunk ->
            sizes += chunk.size
            if (sizes.size == 3) throw ProtocolException("cloud_denied")
            MoveResult(chunk.map { MoveResult.Moved(it.path, it.name) }, emptyList())
        }
        assertEquals(listOf(200, 200, 50), sizes) // 같은 경로는 한 번만
        assertEquals(400, r.moved.size)
        assertEquals(50, r.failed.size)
        rejects("vault_locked") { CloudRename.run(items) { throw ProtocolException("vault_locked") } }
    }

    // ---------- 잠긴 폴더 사이로 옮기기(§18.1) ----------

    @Test fun sourceTokens() {
        val tok = mapOf("금고/a" to "t1", "금고/b" to "t1", "비밀/c" to "t2")
        assertEquals(listOf("t2"), CloudMove.sourceTokens(listOf("금고/a", "금고/b", "비밀/c", "평문"), "t1") { tok[it] })
        assertEquals(listOf("t1", "t2"), CloudMove.sourceTokens(listOf("금고/a", "비밀/c"), null) { tok[it] })
        assertEquals(16, CloudMove.sourceTokens(List(40) { "v$it/x" }, null) { "t${it.substringBefore('/')}" }.size)
        assertTrue(CloudMove.sourceTokens(listOf("평문"), null) { null }.isEmpty())
    }

    @Test fun moveBlocking() {
        val plain = CloudMove.Source("a.jpg", null, false)
        val inVault = CloudMove.Source("금고/a.jpg", "금고", false)
        val vault = CloudMove.Source("금고", null, true)
        // move2: 잠긴 폴더 안팎·사이로 된다. 잠긴 폴더 자체를 다른 잠긴 폴더 안에 넣기만 막는다
        assertTrue(!CloudMove.blocked(listOf(plain, inVault), "비밀", move2 = true, store = true))
        assertTrue(!CloudMove.blocked(listOf(inVault), null, move2 = true, store = true))
        assertTrue(CloudMove.blocked(listOf(vault), "비밀", move2 = true, store = true))
        assertTrue(!CloudMove.blocked(listOf(vault), null, move2 = true, store = true))
        // 예전 허브: 같은 보관함 안에서만(대소문자 무관), 잠긴 폴더 자체는 2.2 허브만 바깥으로
        assertTrue(CloudMove.blocked(listOf(plain), "비밀", move2 = false, store = true))
        assertTrue(CloudMove.blocked(listOf(inVault), null, move2 = false, store = true))
        assertTrue(!CloudMove.blocked(listOf(inVault), "금고", move2 = false, store = true))
        assertTrue(!CloudMove.blocked(listOf(CloudMove.Source("GEUM/a", "GEUM", false)), "geum", move2 = false, store = false))
        assertTrue(!CloudMove.blocked(listOf(vault), null, move2 = false, store = true))
        assertTrue(CloudMove.blocked(listOf(vault), null, move2 = false, store = false))
    }

    @Test fun tokensFollowMovedFolders() {
        val v = VaultTokens()
        v.put("pc", "사진/금고", "t1")
        v.learn("pc", "사진/금고/x") // 아래에서 본 자리도 함께
        v.put("pc", "다른", "t2")
        v.retarget("pc", "사진", "보관/사진")
        assertEquals("t1", v.tokenFor("pc", "보관/사진/금고/a.jpg"))
        assertNull(v.tokenFor("pc", "사진/금고/a.jpg"))
        assertEquals("보관/사진/금고", v.mountOf("pc", "보관/사진/금고/y"))
        assertEquals("t2", v.tokenFor("pc", "다른/b"))
        // 이름만 바뀌어도(§18.2) 따라간다
        v.retarget("pc", "보관/사진/금고", "보관/사진/비밀")
        assertEquals("t1", v.tokenFor("pc", "보관/사진/비밀/z"))
        // 허브처럼 대소문자 없이 찾는다
        v.put("pc", "Vault", "t3")
        v.retarget("pc", "vault", "Box")
        assertEquals("t3", v.tokenFor("pc", "box/a"))
        v.retarget("pc", "다른", "")
        assertEquals("t2", v.tokenFor("pc", "다른/b")) // 빈 자리로는 옮기지 않는다
        v.retarget("pc", "없음", "x") // 모르는 자리는 아무 일도 없다
        assertEquals("t2", v.tokenFor("pc", "다른/b"))
    }
}
