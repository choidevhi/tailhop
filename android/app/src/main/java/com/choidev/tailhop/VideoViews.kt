package com.choidev.tailhop

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

/**
 * 영상 재생기의 탐색 막대(2.2.1, 앱 모양): 바탕 줄, 받아 둔 범위(buffered), 지나온 부분(played), 손잡이. 누르거나 끌면 그 자리로
 * (끄는 동안 onScrub, 놓으면 onScrubEnd). 위치는 0–1 비율. 끄는 동안 손잡이가 커진다. 키보드 왼쪽·오른쪽은 5 %씩.
 */
class ScrubBar @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    interface Listener {
        fun onScrubStart()
        fun onScrub(f: Float)
        fun onScrubEnd(f: Float)
    }

    var listener: Listener? = null
    /** 지나온 비율(0–1). 끄는 동안에는 손가락 자리가 이긴다. */
    var played = 0f
        set(v) { field = v.coerceIn(0f, 1f); if (!dragging) invalidate() }
    /** 받아 둔 비율(0–1). */
    var buffered = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }

    private val dp = resources.displayMetrics.density
    private var dragging = false
    private var dragF = 0f
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40FFFFFF; strokeCap = Paint.Cap.ROUND; strokeWidth = 3 * dp }
    private val buf = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x80FFFFFF.toInt(); strokeCap = Paint.Cap.ROUND; strokeWidth = 3 * dp }
    private val done = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); strokeCap = Paint.Cap.ROUND; strokeWidth = 3 * dp }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val pad = 10 * dp

    init {
        isFocusable = true
        isClickable = true
    }

    private fun xOf(f: Float) = pad + (width - pad * 2) * f
    private fun fOf(x: Float) = ((x - pad) / maxOf(1f, width - pad * 2)).coerceIn(0f, 1f)

    override fun onDraw(canvas: Canvas) {
        val y = height / 2f
        val f = if (dragging) dragF else played
        canvas.drawLine(xOf(0f), y, xOf(1f), y, track)
        if (buffered > 0f) canvas.drawLine(xOf(0f), y, xOf(maxOf(buffered, f)), y, buf)
        if (f > 0f) canvas.drawLine(xOf(0f), y, xOf(f), y, done)
        canvas.drawCircle(xOf(f), y, (if (dragging) 8 else 6) * dp, knob)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = true
                dragF = fOf(e.x)
                listener?.onScrubStart()
                listener?.onScrub(dragF)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> if (dragging) { dragF = fOf(e.x); listener?.onScrub(dragF); invalidate() }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                dragging = false
                played = dragF
                if (e.actionMasked == MotionEvent.ACTION_UP) performClick()
                listener?.onScrubEnd(dragF)
                invalidate()
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        val step = when (keyCode) {
            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> -0.05f
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> 0.05f
            else -> return super.onKeyDown(keyCode, event)
        }
        val f = (played + step).coerceIn(0f, 1f)
        listener?.onScrubStart()
        listener?.onScrubEnd(f)
        played = f
        return true
    }
}

/**
 * 영상 위의 손짓 받이(2.2.1): 한 번 탭 = 조작 막대 숨기기·보이기, 왼쪽·오른쪽 두 번 탭 = ∓10초(반복 탭 누적, TapSeek),
 * 가로로 끌기 = 탐색(놓을 때 옮긴다), 오른쪽 절반 세로로 끌기 = 음량, 왼쪽 절반 = 밝기(창 밝기만). 화면 위·아래 끝에서 시작한
 * 끌기는 시스템 몫(알림창·몰입 해제)이라 받지 않는다. 계산은 VideoMath, 할 일은 listener가 한다.
 */
class VideoGestures @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    interface Listener {
        fun onSingleTap()
        /** 두 번 탭 탐색: 이번 연속 탭에서 모은 거리(ms, 부호 포함)와 누른 쪽(-1 왼쪽, +1 오른쪽). */
        fun onTapSeek(totalMs: Long, side: Int)
        fun onSeekDragStart()
        /** 가로로 끈 거리(px, 오른쪽 +)와 이 뷰의 폭. */
        fun onSeekDrag(dx: Float, width: Int)
        fun onSeekDragEnd(commit: Boolean)
        /** 세로로 끌기 시작: volume이면 음량(오른쪽), 아니면 밝기(왼쪽). */
        fun onLevelStart(volume: Boolean)
        /** 세로로 끈 거리(px, 아래 +)와 이 뷰의 높이. */
        fun onLevelDrag(volume: Boolean, dy: Float, height: Int)
        fun onLevelEnd(volume: Boolean)
    }

    var listener: Listener? = null
    val taps = TapSeek()

    private val vc = ViewConfiguration.get(context)
    private val slop = vc.scaledTouchSlop.toFloat()
    private val tapTimeout = ViewConfiguration.getTapTimeout().toLong() + 120
    private val dp = resources.displayMetrics.density
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    /** 0 = 아직(탭일 수도), 1 = 가로 탐색, 2 = 음량, 3 = 밝기, 4 = 받지 않음(끝에서 시작·여러 손가락). */
    private var mode = 0
    private val singleCheck = Runnable { if (taps.single(System.currentTimeMillis())) listener?.onSingleTap() }

    init { isClickable = true }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                downAt = System.currentTimeMillis()
                val edge = 28 * dp
                mode = if (e.y < edge || e.y > height - edge) 4 else 0
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (mode == 0) mode = 4
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - downX
                val dy = e.y - downY
                if (mode == 0 && (abs(dx) > slop * 1.5f || abs(dy) > slop * 1.5f)) {
                    mode = when {
                        abs(dx) > abs(dy) -> 1
                        downX > width / 2f -> 2
                        else -> 3
                    }
                    parent?.requestDisallowInterceptTouchEvent(true)
                    removeCallbacks(singleCheck)
                    taps.reset()
                    when (mode) {
                        1 -> listener?.onSeekDragStart()
                        else -> listener?.onLevelStart(mode == 2)
                    }
                }
                when (mode) {
                    1 -> listener?.onSeekDrag(dx, width)
                    2, 3 -> listener?.onLevelDrag(mode == 2, dy, height)
                }
            }
            MotionEvent.ACTION_UP -> {
                when (mode) {
                    0 -> if (System.currentTimeMillis() - downAt <= tapTimeout + 200) tap(e.x)
                    1 -> listener?.onSeekDragEnd(true)
                    2, 3 -> listener?.onLevelEnd(mode == 2)
                }
                if (mode == 0) performClick()
                mode = 0
            }
            MotionEvent.ACTION_CANCEL -> {
                when (mode) {
                    1 -> listener?.onSeekDragEnd(false)
                    2, 3 -> listener?.onLevelEnd(mode == 2)
                }
                mode = 0
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun tap(x: Float) {
        val side = if (x < width / 2f) -1 else 1
        val now = System.currentTimeMillis()
        val total = taps.tap(side, now)
        removeCallbacks(singleCheck)
        if (total != null) listener?.onTapSeek(total, side)
        else postDelayed(singleCheck, 320) // 두 번째 탭이 오지 않으면 한 번 탭
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(singleCheck)
        super.onDetachedFromWindow()
    }
}
