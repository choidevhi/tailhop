package com.choidev.tailhop

/**
 * 폰 페어링 2단계(1.5.0). QR의 PC와 비밀 S를 나눈 뒤에도 바로 저장하지 않는다.
 * 사용자가 양쪽 화면의 확인 코드가 같다고 고르면(confirm) 그때 저장하고, 다르다고 고르면(cancel) S를 지우고
 * 상대에게 취소를 알린다(PC 1.5+는 그 페어링을 지운다). 한 번만 결정된다. 안드로이드 의존성 없음(JVM 단위 테스트).
 */
class PendingPair(val pairing: Pairing) {
    enum class State { WAITING, SAVED, CANCELLED }

    /** 양쪽 화면에 같아야 하는 6자리 확인 코드. */
    val code: String = pairing.confirmCode

    @Volatile var state = State.WAITING
        private set

    /** 코드가 같다: 저장한다. 이미 결정됐으면 아무것도 하지 않고 false. */
    @Synchronized
    fun confirm(save: (Pairing) -> Unit): Boolean {
        if (state != State.WAITING) return false
        save(pairing)
        state = State.SAVED
        return true
    }

    /**
     * 코드가 다르다(또는 창을 닫음): 저장하지 않는다. notify에는 S를 복사한 Pairing을 넘기고(상대에게 취소 알림용,
     * 받는 쪽이 다 쓰면 지운다) 여기 들고 있던 S는 0으로 지운다. 이미 결정됐으면 false.
     */
    @Synchronized
    fun cancel(notify: (Pairing) -> Unit): Boolean {
        if (state != State.WAITING) return false
        state = State.CANCELLED
        val copy = pairing.copy(secret = pairing.secret.copyOf())
        pairing.secret.fill(0)
        try { notify(copy) } catch (e: Exception) { }
        return true
    }
}
