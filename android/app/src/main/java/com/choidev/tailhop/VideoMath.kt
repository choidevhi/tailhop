package com.choidev.tailhop

import org.json.JSONObject
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 앱 안 영상 재생기(2.2.1 다듬음)의 계산: 시간 글자, 두 번 탭 ±10초(반복 탭 누적), 가로로 끌어 탐색, 이어 보기 기록.
 * 안드로이드 의존성 없음(JVM 단위 테스트).
 */
object VideoMath {
    /** 재생 속도 고르기(0.5–2배). */
    val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

    /** 시간 글자: 1시간 이상이면 H:MM:SS, 아니면 M:SS. 음수·모름(<0)은 0:00. */
    fun time(ms: Long): String {
        val s = maxOf(0L, ms) / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec) else String.format(Locale.ROOT, "%d:%02d", m, sec)
    }

    /** 앞뒤로 얼마나 옮기는지: +0:15, −1:02:03(빼기는 진짜 빼기 기호). */
    fun delta(ms: Long): String = (if (ms < 0) "−" else "+") + time(kotlin.math.abs(ms))

    /** 속도 글자(1×, 1.25×, 0.5×). */
    fun speed(s: Float): String {
        val t = if (s == s.toInt().toFloat()) s.toInt().toString() else String.format(Locale.ROOT, "%.2f", s).trimEnd('0').trimEnd('.')
        return "$t×"
    }

    /**
     * 가로로 끌어 탐색: 화면 폭을 다 끌면 영상 전체(길면 3분)만큼. 시작 자리 start에서 dx(오른쪽 +)만큼 끈 뒤의 자리(0–duration).
     * duration을 모르면(≤0) 그대로.
     */
    fun dragSeek(start: Long, dx: Float, width: Int, duration: Long): Long {
        if (duration <= 0 || width <= 0) return start
        val span = minOf(duration, 180_000L)
        return (start + (dx / width * span).toLong()).coerceIn(0L, duration)
    }

    /** 세로로 끌어 음량·밝기: 보이는 높이의 60 %를 끌면 0 → 1. 위로(dy < 0) 올린다. */
    fun dragLevel(start: Float, dy: Float, height: Int): Float =
        if (height <= 0) start else (start - dy / (height * 0.6f)).coerceIn(0f, 1f)

    /** PiP 창 비율(Android 한도 1:2.39 ~ 2.39:1 안으로). 모르면 16:9. */
    fun pipRatio(w: Int, h: Int): Pair<Int, Int> {
        if (w <= 0 || h <= 0) return 16 to 9
        val r = w.toFloat() / h
        return when {
            r > 2.39f -> 239 to 100
            r < 1 / 2.39f -> 100 to 239
            else -> w to h
        }
    }
}

/**
 * 두 번 탭 ±10초(YouTube처럼): 화면 왼쪽(side = -1)·오른쪽(+1)을 빨리 두 번 누르면 10초, 그 뒤 keepMs 안에 같은 쪽을 또 누를 때마다
 * 10초씩 더한다(+20, +30…). 한 번만 누른 것은 doubleMs가 지나야 "한 번 탭"으로 안다(막대 숨기기·보이기).
 */
class TapSeek(private val doubleMs: Long = 300, private val keepMs: Long = 800, private val stepMs: Long = 10_000) {
    private var lastTap = Long.MIN_VALUE
    private var lastSide = 0
    private var seeking = false
    private var lastSeek = Long.MIN_VALUE
    /** 이번 연속 탭에서 모은 거리(ms, 부호 포함). */
    var total = 0L
        private set

    /** 탭 하나. 탐색이 되면 지금까지 모은 거리(ms, 왼쪽은 음수), 아직 한 번 탭이면 null(doubleMs 뒤 single로 확인). */
    fun tap(side: Int, now: Long): Long? {
        if (seeking && side == lastSide && now - lastSeek <= keepMs) {
            total += side * stepMs
            lastSeek = now
            return total
        }
        if (!seeking && side == lastSide && lastTap != Long.MIN_VALUE && now - lastTap <= doubleMs) {
            seeking = true
            total = side * stepMs
            lastSeek = now
            lastTap = Long.MIN_VALUE
            return total
        }
        seeking = false
        total = 0
        lastTap = now
        lastSide = side
        return null
    }

    /** now에 보아 마지막 탭이 한 번 탭으로 끝났는지(그 뒤 두 번째 탭이 오지 않았다). 한 번만 true. */
    fun single(now: Long): Boolean {
        if (seeking || lastTap == Long.MIN_VALUE || now - lastTap < doubleMs) return false
        lastTap = Long.MIN_VALUE
        return true
    }

    /** 연속 탐색이 끝났는지(마지막 탭 뒤 keepMs가 지남). */
    fun idle(now: Long): Boolean = !seeking || now - lastSeek > keepMs

    fun reset() { seeking = false; total = 0; lastTap = Long.MIN_VALUE; lastSeek = Long.MIN_VALUE }
}

/**
 * 이어 보기 기록(2.2.1): 같은 파일을 다시 열면 마지막 자리부터. 파일 이름·경로는 평문으로 두지 않는다 — 키는 이 앱만 아는 난수 열쇠로
 * 만든 HMAC-SHA256(허브 주소 + 경로)의 앞 32자, 값은 자리(ms)와 기록한 시각뿐. 오래된 것부터 MAX개까지만 둔다.
 * 저장(SharedPreferences)은 부른 쪽이 encode/parse로 한다.
 */
class ResumeBook(private val secret: ByteArray) {
    private val entries = LinkedHashMap<String, Pair<Long, Long>>() // 키 → (자리, 기록 시각)

    val size: Int get() = entries.size

    fun key(pc: String, path: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        return Xfer.hex(mac.doFinal("$pc\n$path".toByteArray(Charsets.UTF_8))).take(32)
    }

    fun get(pc: String, path: String): Long? = entries[key(pc, path)]?.first

    /** 지금 자리를 적는다. 처음 몇 초·끝 가까이면 지운다(처음부터 보게). */
    fun put(pc: String, path: String, pos: Long, duration: Long, now: Long) {
        val k = key(pc, path)
        val keep = worth(pos, duration)
        entries.remove(k)
        if (keep) entries[k] = pos to now
        while (entries.size > MAX) entries.remove(oldest() ?: break)
    }

    fun remove(pc: String, path: String) { entries.remove(key(pc, path)) }

    private fun oldest(): String? = entries.minByOrNull { it.value.second }?.key

    fun encode(): String = JSONObject().apply { entries.forEach { (k, v) -> put(k, "${v.first},${v.second}") } }.toString()

    companion object {
        const val MAX = 300
        /** 이보다 앞이면 기록하지 않는다(처음부터와 다를 바 없다). */
        const val MIN_POS = 5_000L
        /** 끝에서 이만큼 안이면 다 본 것으로 친다. */
        const val END_MARGIN = 10_000L
        private val KEY_RE = Regex("[0-9a-f]{32}")

        /** 기록할 만한 자리인지. 길이를 모르면(≤0) 앞 몇 초만 뺀다. */
        fun worth(pos: Long, duration: Long): Boolean =
            pos >= MIN_POS && (duration <= 0 || pos <= duration - END_MARGIN)

        /** 저장된 글에서. 모양이 틀린 줄은 버린다. */
        fun parse(json: String?, secret: ByteArray): ResumeBook {
            val b = ResumeBook(secret)
            val o = try { JSONObject(json ?: return b) } catch (e: Exception) { return b }
            for (k in o.keys()) {
                if (!KEY_RE.matches(k)) continue
                val parts = (o.opt(k) as? String)?.split(',') ?: continue
                val pos = parts.getOrNull(0)?.toLongOrNull() ?: continue
                val ts = parts.getOrNull(1)?.toLongOrNull() ?: continue
                if (pos >= 0) b.entries[k] = pos to ts
            }
            while (b.entries.size > MAX) b.entries.remove(b.oldest() ?: break)
            return b
        }
    }
}
