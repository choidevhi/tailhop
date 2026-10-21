package com.choidev.tailhop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2.2.0 보낼 곳 목록의 쉬기: 30일 포기, 상대가 꺼져 기다리는지(Idle), hello 간격(PingGate). */
class OutboxIdleTest {
    private val t0 = 1_700_000_000_000L

    private fun text(dev: String = "100.64.0.1", created: Long = t0) =
        OutboxJob(id = Protocol.newId(), dev = dev, isText = true, text = "hi", xfer = Xfer.newXfer(), created = created)

    private fun file(uri: String, kind: String = OutboxJob.FILE) = OutboxJob(id = Protocol.newId(), dev = "100.64.0.1",
        isText = false, uri = uri, xfer = Xfer.newXfer(), created = t0, kind = kind)

    /** n번 실패한 뒤(마지막 실패 시각 now). */
    private fun failed(j: OutboxJob, code: String, n: Int, now: Long): OutboxJob {
        var x = j
        repeat(n) { x = Retry.afterFailure(x, now, true, code, null) }
        return x
    }

    @Test fun keepsThirtyDays() {
        assertEquals(30L * 24 * 3600 * 1000, Retry.KEEP_MS)
        val j = text()
        // 29일째에도 다음 시도(5분 뒤)
        val day29 = Retry.afterFailure(j.copy(attempts = 9), t0 + 29L * 24 * 3600 * 1000, true, "connect_failed", null)
        assertEquals(OutboxJob.WAITING, day29.state)
        assertFalse(day29.gaveUp)
        assertEquals(Retry.LATER_MS, day29.nextAt - (t0 + 29L * 24 * 3600 * 1000))
        val day30 = Retry.afterFailure(j, t0 + Retry.KEEP_MS, true, "connect_failed", null)
        assertEquals(OutboxJob.FAILED, day30.state)
        assertTrue(day30.gaveUp)
    }

    @Test fun expireWhileSleeping() {
        val waiting = failed(text(), "connect_failed", 6, t0 + 1000)
        assertNull(Retry.expire(waiting, t0 + Retry.KEEP_MS - 1))
        val gone = Retry.expire(waiting, t0 + Retry.KEEP_MS)!!
        assertEquals(OutboxJob.FAILED, gone.state)
        assertTrue(gone.gaveUp)
        assertEquals("connect_failed", gone.error)
        // 이미 실패했거나 보내는 중이면 그대로
        assertNull(Retry.expire(gone, t0 + Retry.KEEP_MS * 2))
        assertNull(Retry.expire(waiting.copy(state = OutboxJob.SENDING), t0 + Retry.KEEP_MS * 2))
    }

    @Test fun parkedOnlyWhenPeerOffAndLongWait() {
        val now = t0 + 10_000
        // 처음 넣은 건(실패 없음)·연결 실패 아닌 이유는 꺼짐이 아니다
        assertFalse(Idle.offline(text()))
        assertFalse(Idle.offline(failed(text(), "pc_forgot", 7, now)))
        assertFalse(Idle.offline(failed(text(), "disconnected", 7, now)))
        // 연결 실패: 5번째까지는 2분 이하로 다시 → 쉬지 않는다. 6번째부터 5분 → 쉰다
        val five = failed(text(), "connect_failed", 5, now)
        assertTrue(Idle.offline(five))
        assertEquals(120_000L, five.nextAt - now)
        assertFalse(Idle.parked(five, now))
        val six = failed(text(), "connect_failed", 6, now)
        assertTrue(Idle.parked(six, now))
        // 시간이 흘러 다음 시도가 2분 안으로 다가오면 parked가 아니다
        assertFalse(Idle.parked(six, six.nextAt - 60_000))
        // 보내는 중·실패한 건은 꺼짐 대기가 아니다
        assertFalse(Idle.offline(six.copy(state = OutboxJob.SENDING)))
        assertFalse(Idle.offline(six.copy(state = OutboxJob.FAILED)))
    }

    @Test fun sleepDecision() {
        val now = t0 + 10_000
        val a = failed(text("100.64.0.1"), "connect_failed", 6, now)
        val b = failed(text("100.64.0.2"), "connect_failed", 7, now)
        assertTrue(Idle.allParked(listOf(a, b), now))
        assertFalse(Idle.allParked(emptyList(), now))
        // 한 줄이라도 곧 다시 하거나 보내는 중이면 쉬지 않는다
        assertFalse(Idle.allParked(listOf(a, failed(text("100.64.0.3"), "connect_failed", 2, now)), now))
        assertFalse(Idle.allParked(listOf(a, text("100.64.0.3").copy(state = OutboxJob.SENDING)), now))
        // 쉬는 서비스를 다시 켤 일: 꺼짐 대기가 아닌 맨 앞 건
        assertFalse(Idle.needsService(listOf(a, b)))
        assertTrue(Idle.needsService(listOf(a, text("100.64.0.3"))))
        assertFalse(Idle.needsService(listOf(a.copy(state = OutboxJob.FAILED))))
        assertFalse(Idle.needsService(emptyList()))
    }

    @Test fun temporaryGrantsKeepServiceAwake() {
        val shared = file("content://com.other.app/doc/1")
        val picked = file("content://com.android.providers.media.documents/document/image%3A5")
        val up = file("content://com.other.app/doc/2", OutboxJob.CLOUD_UP)
        val persisted = setOf(picked.uri!!)
        assertTrue(Idle.needsGrant(shared, persisted))
        assertFalse(Idle.needsGrant(picked, persisted))
        assertTrue(Idle.needsGrant(up, persisted))
        // 텍스트·가져오기·실패한 건은 권한이 필요 없다
        assertFalse(Idle.needsGrant(text(), persisted))
        assertFalse(Idle.needsGrant(shared.copy(state = OutboxJob.FAILED), persisted))
        assertTrue(Idle.canSleep(listOf(text(), picked), persisted))
        assertFalse(Idle.canSleep(listOf(text(), picked, shared), persisted))
    }

    @Test fun pingGateSixtySeconds() {
        val g = PingGate(60_000)
        assertTrue(g.take("100.64.0.1", t0))
        assertFalse(g.take("100.64.0.1", t0 + 59_999))
        assertTrue(g.take("100.64.0.2", t0 + 1)) // PC마다 따로
        assertTrue(g.take("100.64.0.1", t0 + 60_000))
        // 시계가 뒤로 가면(사용자가 시간을 바꿈) 막지 않는다
        assertTrue(g.take("100.64.0.2", t0 - 5_000))
    }
}
