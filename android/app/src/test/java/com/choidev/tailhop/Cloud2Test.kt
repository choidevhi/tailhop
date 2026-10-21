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

/** 2.0.0 클라우드 미리보기·검색(§15)과 잠긴 폴더(§14)의 클라이언트 쪽 형식, 대화방 거르기. */
class Cloud2Test {
    private val v = JSONObject(File(System.getProperty("tailhop.vectors")!!).readText(Charsets.UTF_8))

    private fun rejects(code: String, block: () -> Unit) {
        try { block(); fail("expected $code") } catch (e: ProtocolException) { assertEquals(code, e.code) }
    }

    // ---------- 종류 표(file_kinds 벡터와 같아야 한다) ----------

    @Test fun fileKindsMatchVectors() {
        val fk = v.getJSONObject("file_kinds")
        val kinds = fk.keys().asSequence().toSet()
        assertEquals(kinds, FileKinds.TABLE.keys)
        for (k in kinds) {
            val exts = fk.getJSONArray(k).let { a -> (0 until a.length()).map { a.getString(it) } }
            assertEquals("kind $k", exts.toSet(), FileKinds.TABLE.getValue(k).toSet())
            for (e in exts) {
                assertEquals("a.$e", k, FileKinds.of("a.$e"))
                assertEquals("A.${e.uppercase()}", k, FileKinds.of("A.${e.uppercase()}"))
            }
        }
        // 표에 없는 것·확장자 없음은 other(Python Path.suffix와 같게)
        assertEquals(FileKinds.OTHER, FileKinds.of("readme"))
        assertEquals(FileKinds.OTHER, FileKinds.of(".jpg"))
        assertEquals(FileKinds.OTHER, FileKinds.of("a."))
        assertEquals(FileKinds.OTHER, FileKinds.of("a.jpg.unknown"))
        assertEquals(FileKinds.ARCHIVE, FileKinds.of("backup.tar.gz"))
        assertEquals(listOf("all", "image", "video", "audio", "doc", "archive", "other"), FileKinds.FILTERS)
        assertTrue(FileKinds.previewable("image") && FileKinds.previewable("video") && !FileKinds.previewable("doc"))
    }

    // ---------- 찾기 ----------

    @Test fun searchRequest() {
        val f = CloudSearch.fields("사진", "여름", "image", 0)
        assertEquals("사진", f.getString("path"))
        assertEquals("여름", f.getString("query"))
        assertEquals("image", f.getString("kind"))
        assertFalse(f.has("cursor"))
        assertEquals(500, CloudSearch.fields("", "", "all", 500).getInt("cursor"))
        assertEquals(100, CloudSearch.fields("", "가".repeat(100), "all", 0).getString("query").length)
        for (bad in listOf<() -> Unit>(
            { CloudSearch.fields("", "x".repeat(101), "all", 0) },
            { CloudSearch.fields("", "", "pictures", 0) },
            { CloudSearch.fields("../a", "", "all", 0) },
            { CloudSearch.fields("", "", "all", -1) },
        )) try { bad(); fail("expected IllegalArgumentException") } catch (e: IllegalArgumentException) { }
    }

    private fun item(name: String, path: String? = null, dir: Boolean = false) = JSONObject().put("name", name).put("dir", dir)
        .put("size", 10).put("mtime", 5).apply { if (path != null) put("path", path) }

    @Test fun searchReply() {
        val items = JSONArray()
            .put(item("a.jpg", "2026/a.jpg").put("kind", "image"))
            .put(item("여름", "여름", dir = true))
            .put(item("b.jpg")) // path 없음 → 찾기 결과에서 버림
            .put(item("c.jpg", "x/d.jpg")) // 마지막 조각이 이름과 다름
            .put(item("e.jpg", "../e.jpg"))
            .put(item("f.jpg", "x.part/f.jpg")) // 보이지 않는 폴더 안
        // partial(훑다가 멈춤)과 next(다음 쪽)는 따로 온다(§15)
        val page = CloudSearch.parse(JSONObject().put("ok", true).put("items", items).put("next", 500).put("partial", true))
        assertEquals(listOf("2026/a.jpg", "여름"), page.page.items.map { it.path })
        assertEquals("image", page.page.items[0].kind)
        assertEquals(500, page.page.next)
        assertTrue(page.partial)
        assertFalse(CloudSearch.parse(JSONObject().put("items", JSONArray()).put("next", JSONObject.NULL)).partial)
    }

    @Test fun listExtraFields() {
        val items = JSONArray()
            .put(item("a.mp4").put("kind", "video"))
            .put(item("b.bin").put("kind", "weird"))
            .put(item("금고", dir = true).put("locked", true).put("state", "locking").put("progress", 42))
            .put(item("열린", dir = true).put("locked", "yes").put("state", "locking"))
            .put(item("x.txt").put("locked", true))
            .put(item("p", dir = true).put("locked", true).put("state", "boom").put("progress", 101))
            .put(item("멈춤", dir = true).put("locked", true).put("state", "stalled"))
        val got = CloudListing.parse(JSONObject().put("items", items)).items
        assertEquals("video", got[0].kind)
        assertEquals("video", got[0].kindOrGuess)
        assertNull(got[1].kind)
        assertEquals(FileKinds.OTHER, got[1].kindOrGuess)
        assertEquals(CloudItem("금고", true, 10, 5, null, true, "locking", 42), got[2])
        assertTrue(got[2].busy)
        assertFalse(got[3].locked) // bool이 아니면 잠김 아님
        assertNull(got[3].state)
        assertFalse(got[4].locked) // 파일은 잠긴 폴더가 아니다
        assertTrue(got[5].locked)
        assertNull(got[5].state)
        assertNull(got[5].progress)
        assertTrue(got[6].stalled && got[6].locked && !got[6].busy) // 멈춘 잠그기: 들어갈 수 없지만 진행 중도 아니다
        // 목록 응답의 path는 쓰지 않아도 되지만, 맞으면 받아 둔다(찾기 결과 표시)
        assertNull(CloudListing.parse(JSONObject().put("items", JSONArray().put(item("a")))).items[0].path)
    }

    // ---------- 미리보기 ----------

    private val key = ByteArray(32) { (it * 5).toByte() }
    private val prefix = Protocol.prefix(Protocol.KIND_MSG, ByteArray(16) { (it + 9).toByte() })

    private fun hubSends(block: (Channel) -> Unit): Channel {
        val out = ByteArrayOutputStream()
        block(Channel(ByteArrayInputStream(ByteArray(0)), out, key, prefix, isClient = false))
        return Channel(ByteArrayInputStream(out.toByteArray()), ByteArrayOutputStream(), key, prefix, isClient = true)
    }

    @Test fun thumbFrames() {
        val jpeg = ByteArray(70_000) { (it % 200).toByte() }
        val client = hubSends { hub ->
            hub.send("""{"ok":true,"mime":"image/jpeg","w":256,"h":171}""".toByteArray(), last = false)
            hub.send(jpeg.copyOfRange(0, 65_536), last = false)
            hub.send(jpeg.copyOfRange(65_536, jpeg.size), last = true)
        }
        val (raw, last) = client.recv()
        assertEquals(CloudThumb.First.Ok(ThumbHead("image/jpeg", 256, 171)), CloudThumb.parseFirst(raw, last))
        assertArrayEquals(jpeg, CloudThumb.readData(client))

        val tooBig = hubSends { hub -> hub.send(ByteArray(200_000), last = false); hub.send(ByteArray(70_000), last = true) }
        rejects("bad_reply") { CloudThumb.readData(tooBig) }
        val emptyData = hubSends { hub -> hub.send(ByteArray(0), last = true) }
        rejects("bad_reply") { CloudThumb.readData(emptyData) }

        val refused = CloudThumb.parseFirst("""{"ok":false,"code":"not_found"}""".toByteArray(), true)
        assertEquals("not_found", (refused as CloudThumb.First.Refused).reply.getString("code"))
        for (bad in listOf("""{"ok":true,"mime":"text/plain","w":1,"h":1}""", """{"ok":true,"mime":"image/jpeg","w":0,"h":1}""",
            """{"ok":true,"mime":"image/jpeg","w":1}""", """{"ok":true,"mime":"image/jpeg","w":"1","h":1}"""))
            rejects("bad_reply") { CloudThumb.parseFirst(bad.toByteArray(), false) }
        rejects("bad_reply") { CloudThumb.parseFirst("""{"ok":true,"mime":"image/jpeg","w":1,"h":1}""".toByteArray(), true) }
        assertEquals(64, CloudThumb.px(10))
        assertEquals(512, CloudThumb.px(4000))
        assertEquals(256, CloudThumb.px(CloudThumb.GRID_PX))
    }

    // ---------- 잠긴 폴더 ----------

    @Test fun vaultFormats() {
        // 새 PIN은 6–64자(§14.2)
        assertTrue(Vault.pinOk("123456"))
        assertFalse(Vault.pinOk("1234"))
        assertFalse(Vault.pinOk("12345"))
        assertTrue(Vault.pinOk(" ab 12")) // 공백도 PIN의 일부
        assertTrue(Vault.pinOk("가".repeat(64)))
        assertFalse(Vault.pinOk("x".repeat(65)))
        assertFalse(Vault.pinOk("123\n456"))
        assertTrue(Vault.pinOk("😀".repeat(6))) // 코드포인트로 센다
        assertFalse(Vault.pinOk("😀".repeat(5)))
        // 지금 PIN(열기·풀기)은 비어 있지 않고 64자 이하면 보낸다(맞는지는 허브가)
        assertTrue(Vault.pinEntered("1234"))
        assertFalse(Vault.pinEntered(""))
        assertFalse(Vault.pinEntered("x".repeat(65)))
        // 복구 코드는 앞뒤 공백만 떼고 그대로(허브가 영숫자만 남기고 O→0, I/L→1로 읽는다)
        assertEquals("abcd-ef01", Vault.normalizeRecovery("  abcd-ef01 "))
        assertEquals("abcd ef01", Vault.normalizeRecovery("abcd ef01"))
        assertNull(Vault.normalizeRecovery("   "))
        assertNull(Vault.normalizeRecovery("ab\ncd"))
        // vault_locked의 mount(§14.2)
        assertEquals("사진/금고", Vault.lockedMount(JSONObject().put("code", "vault_locked").put("mount", "사진/금고")))
        assertNull(Vault.lockedMount(JSONObject().put("mount", "../x")))
        assertNull(Vault.lockedMount(JSONObject().put("mount", "")))
        assertNull(Vault.lockedMount(JSONObject()))
        assertNull(Vault.lockedMount(null))
        val t = "0123456789abcdef0123456789abcdef"
        assertEquals(t, Vault.parseToken(JSONObject().put("ok", true).put("token", t).put("ttl", 1800)))
        rejects("bad_reply") { Vault.parseToken(JSONObject().put("token", t.uppercase())) }
        rejects("bad_reply") { Vault.parseToken(JSONObject()) }
        val code = "0123-4567-89AB-CDEF-GHJK-MNPQ-RSTV-WXYZ"
        assertEquals(code, Vault.parseRecovery(JSONObject().put("recovery", code)))
        assertEquals("odd but kept", Vault.parseRecovery(JSONObject().put("recovery", " odd but kept ")))
        rejects("bad_reply") { Vault.parseRecovery(JSONObject().put("recovery", "")) }
        rejects("bad_reply") { Vault.parseRecovery(JSONObject().put("recovery", 5)) }
        assertEquals("0123-4567-89AB-CDEF-\nGHJK-MNPQ-RSTV-WXYZ", Vault.displayRecovery(code))
        assertEquals("AB-CD", Vault.displayRecovery("AB-CD"))
        assertEquals(60, Vault.retrySeconds(JSONObject().put("retry", 60)))
        assertNull(Vault.retrySeconds(JSONObject().put("retry", "60")))
        assertNull(Vault.retrySeconds(null))
    }

    @Test fun tokenStore() {
        val s = VaultTokens()
        s.learn("pc", "금고")
        s.learn("pc", "금고/안/깊은")
        s.learn("pc", "../x") // 규칙 밖은 기억하지 않는다
        assertEquals("금고", s.mountOf("pc", "금고"))
        assertEquals("금고", s.mountOf("pc", "금고/안"))
        assertEquals("금고/안/깊은", s.mountOf("pc", "금고/안/깊은/a.jpg"))
        assertNull(s.mountOf("pc", "금고2/a")) // 이름이 겹쳐도 다른 폴더
        assertNull(s.mountOf("other", "금고/a"))
        assertNull(s.tokenFor("pc", "금고/a")) // 열쇠는 아직 없다
        s.put("pc", "금고", "t1")
        assertEquals("t1", s.tokenFor("pc", "금고/a.jpg"))
        assertEquals("t1", s.tokenFor("pc", "금고"))
        assertNull(s.tokenFor("pc", "금고/안/깊은/x")) // 더 깊은 잠긴 폴더의 열쇠는 따로
        assertNull(s.tokenFor("pc", "다른"))
        s.drop("pc", "금고")
        assertFalse(s.has("pc", "금고"))
        assertEquals("금고", s.mountOf("pc", "금고/a")) // 자리는 기억한다(다시 PIN을 물을 곳)
        s.put("pc", "금고", "t2")
        s.forgetMount("pc", "금고")
        assertNull(s.mountOf("pc", "금고/안/깊은/a"))
        assertNull(s.tokenFor("pc", "금고/a"))
        // 잠긴 폴더 자리는 대소문자 없이 비교한다(허브와 같게, §14.2). 처음 본 모양으로 돌려준다.
        s.learn("pc", "Vault")
        assertEquals("Vault", s.mountOf("pc", "vault/a.jpg"))
        s.put("pc", "VAULT", "t4")
        assertEquals("t4", s.tokenFor("pc", "Vault/b"))
        assertTrue(s.has("pc", "vault"))
        s.forgetMount("pc", "vAuLt")
        assertNull(s.mountOf("pc", "Vault/a"))
        s.put("pc", "x", "t3")
        s.forgetPc("pc")
        assertNull(s.tokenFor("pc", "x"))
    }

    // ---------- 격자·대화방 ----------

    @Test fun gridPaging() {
        assertEquals(3, CloudGrid.columns(360))
        assertEquals(2, CloudGrid.columns(200))
        assertEquals(7, CloudGrid.columns(800))
        assertTrue(CloudGrid.nearEnd(lastVisible = 94, itemCount = 100, margin = 6))
        assertFalse(CloudGrid.nearEnd(lastVisible = 92, itemCount = 100, margin = 6))
        assertFalse(CloudGrid.nearEnd(lastVisible = -1, itemCount = 0, margin = 6))
    }

    private fun h(text: String? = null, name: String? = null, uri: String? = "content://media/external/downloads/1") =
        HistoryItem("id", 0, true, text != null, text, name, 1, if (text != null) null else uri)

    @Test fun chatFilter() {
        val t = h(text = "회의 자료 Link")
        val img = h(name = "여름.JPG")
        val vid = h(name = "clip.mp4")
        val pdf = h(name = "report.pdf")
        assertTrue(ChatFilter.matches(t, "", ChatFilter.ALL))
        assertTrue(ChatFilter.matches(t, "link", ChatFilter.TEXT))
        assertFalse(ChatFilter.matches(t, "link", ChatFilter.FILE))
        assertTrue(ChatFilter.matches(img, "여름", ChatFilter.MEDIA))
        assertTrue(ChatFilter.matches(vid, "", ChatFilter.MEDIA))
        assertFalse(ChatFilter.matches(pdf, "", ChatFilter.MEDIA))
        assertTrue(ChatFilter.matches(pdf, "REPORT", ChatFilter.FILE))
        assertFalse(ChatFilter.matches(img, "", ChatFilter.FILE))
        assertFalse(ChatFilter.matches(pdf, "zzz", ChatFilter.ALL))
        assertFalse(ChatFilter.active(" ", ChatFilter.ALL))
        assertTrue(ChatFilter.active("", ChatFilter.TEXT))
        assertTrue(ChatFilter.isVideo(vid) && !ChatFilter.isVideo(img))
        assertEquals(listOf(img, vid), ChatFilter.media(listOf(t, img, vid, pdf, h(name = "x.png", uri = null))))
    }
}
