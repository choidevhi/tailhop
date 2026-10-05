package com.choidev.tailhop

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 보안 점검 2차 수정의 회귀 테스트(JVM, 안드로이드 의존성 없음). */
class SecurityFixTest {
    private fun pairing(): Pairing = Pairing("100.64.1.2", 47100, "PC", ByteArray(32) { (it + 1).toByte() })

    @Test fun pairingIsSavedOnlyAfterConfirm() {
        val pending = PendingPair(pairing())
        val saved = ArrayList<Pairing>()
        assertEquals(PendingPair.State.WAITING, pending.state)
        assertEquals(Protocol.confirmCode(ByteArray(32) { (it + 1).toByte() }), pending.code)
        assertTrue(saved.isEmpty()) // 교환만 하고 아직 저장하지 않았다
        assertTrue(pending.confirm { saved += it })
        assertFalse(pending.confirm { saved += it }) // 두 번 저장하지 않는다
        assertFalse(pending.cancel { error("cancel after confirm") })
        assertEquals(1, saved.size)
        assertEquals(PendingPair.State.SAVED, pending.state)
    }

    @Test fun cancelDiscardsSecretAndNotifiesWithCopy() {
        val original = ByteArray(32) { (it + 1).toByte() }
        val pending = PendingPair(pairing())
        var notified: Pairing? = null
        assertTrue(pending.cancel { notified = it })
        assertArrayEquals(original, notified!!.secret) // 상대에게 취소를 알릴 사본
        assertArrayEquals(ByteArray(32), pending.pairing.secret) // 들고 있던 S는 지웠다
        val saved = ArrayList<Pairing>()
        assertFalse(pending.confirm { saved += it }) // 취소 뒤에는 저장할 수 없다
        assertTrue(saved.isEmpty())
        assertEquals(PendingPair.State.CANCELLED, pending.state)
    }

    @Test fun cancelSurvivesNotifyFailure() {
        val pending = PendingPair(pairing())
        assertTrue(pending.cancel { throw IllegalStateException("offline") })
        assertEquals(PendingPair.State.CANCELLED, pending.state)
    }

    @Test fun ownAuthorityDetectsUserPrefix() {
        val own = listOf("com.choidev.tailhop.files")
        assertTrue(Protocol.isOwnAuthority("com.choidev.tailhop.files", own))
        assertTrue(Protocol.isOwnAuthority("0@com.choidev.tailhop.files", own)) // 사용자 번호 접두 우회
        assertTrue(Protocol.isOwnAuthority("10@COM.Choidev.Tailhop.FILES", own))
        assertTrue(Protocol.isOwnAuthority(null, own))
        assertTrue(Protocol.isOwnAuthority("", own))
        assertFalse(Protocol.isOwnAuthority("media", own))
        assertFalse(Protocol.isOwnAuthority("0@media", own))
        assertFalse(Protocol.isOwnAuthority("com.choidev.tailhop.files.evil", own))
    }

    @Test fun sanitizeBidiAndDeviceNames() {
        assertEquals("evil_gnp.exe", Protocol.sanitizeFilename("evil\u202egnp.exe"))
        assertEquals("a_b.txt", Protocol.sanitizeFilename("a\u2066b.txt"))
        assertEquals("a_b.txt", Protocol.sanitizeFilename("a\u009bb.txt"))
        assertEquals("👨\u200d👩.png", Protocol.sanitizeFilename("👨\u200d👩.png"))
        assertEquals("_COM1 .txt", Protocol.sanitizeFilename("COM1 .txt"))
        assertEquals("_con", Protocol.sanitizeFilename("con"))
        assertEquals("_nul", Protocol.sanitizeFilename("nul "))
        assertEquals("_AUX", Protocol.sanitizeFilename("AUX  .  ."))
        assertEquals("_LPT\u00b9.log", Protocol.sanitizeFilename("LPT\u00b9.log"))
        assertEquals("_CONOUT$", Protocol.sanitizeFilename("CONOUT$"))
        assertEquals("COM10.txt", Protocol.sanitizeFilename("COM10.txt"))
        assertEquals("report.txt", Protocol.sanitizeFilename("report.txt. . "))
    }

    @Test fun deviceNameStripsBidi() {
        assertEquals("MyPC", Protocol.cleanDeviceName("  My\u202ePC\u2069\u0007 "))
        assertEquals(64, Protocol.cleanDeviceName("x".repeat(100)).length)
    }
}
