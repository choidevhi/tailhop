package com.choidev.tailhop

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.pow

/**
 * 확대 보기 계산(2.1.0). 그림 좌표 → 화면 좌표 = 그림 × s + (tx, ty). 회전(2.2.1)은 그림을 돌린 상자 기준이다(돌린 뒤의 가로세로).
 * 안드로이드 의존성 없음(JVM 단위 테스트).
 */
object ZoomMath {
    /** 화면에 맞춤(그림 전체가 보이는 가장 큰 배율). 작은 그림도 키운다. */
    fun fit(bw: Int, bh: Int, vw: Int, vh: Int): Float =
        if (bw <= 0 || bh <= 0 || vw <= 0 || vh <= 0) 1f else minOf(vw.toFloat() / bw, vh.toFloat() / bh)

    /** 최대 배율: 맞춤의 6배, 큰 그림은 적어도 원본 픽셀 2배까지. */
    fun maxScale(fit: Float): Float = maxOf(fit * 6f, 2f)

    /**
     * 최대 배율(2.2.1): 맞춤의 6배, 그리고 원본 1픽셀이 화면 4픽셀 이상이 될 때까지. nativePerBitmap = 원본 픽셀 ÷ 지금 그림 픽셀
     * (화면용으로 줄인 그림이면 1보다 크다 — 원본으로 바꾸기 전에도 끝까지 확대할 수 있게).
     */
    fun maxScale(fit: Float, nativePerBitmap: Float): Float = maxOf(fit * 6f, 4f * maxOf(1f, nativePerBitmap))

    /** 한 축의 위치: 그림이 화면보다 작으면 가운데, 크면 가장자리가 화면 안으로 들어오지 않게. */
    fun clampOffset(t: Float, content: Float, view: Int): Float =
        if (content <= view) (view - content) / 2f else t.coerceIn(view - content, 0f)

    /**
     * 한 축의 위치(2.2.1): 보이는 칸 start..end(시스템 바·가림을 뺀 자리) 안에서. 작으면 그 칸 가운데,
     * 크면 그림 가장자리가 칸 안으로 들어오지 않게(가장자리까지 옮기면 바에 가리지 않고 끝이 보인다).
     */
    fun clampIn(t: Float, content: Float, start: Float, end: Float): Float {
        val avail = end - start
        return if (content <= avail) start + (avail - content) / 2f else t.coerceIn(end - content, start)
    }

    /** 배율을 from → to로 바꿔도 초점(fx, fy) 아래의 그림 점이 그 자리에 남는 새 위치. */
    fun zoomAt(tx: Float, ty: Float, from: Float, to: Float, fx: Float, fy: Float): Pair<Float, Float> =
        (fx - (fx - tx) / from * to) to (fy - (fy - ty) / from * to)

    /** 맞춤보다 크게 확대했고 그림 1픽셀이 화면 1픽셀보다 크다(화면용 그림으로는 모자란 배율, 2.2.0). */
    fun overPixel(s: Float, fit: Float): Boolean = s > fit * 1.01f && s > 1.001f

    /** 돌린(0·90·180·270도) 뒤의 가로세로. */
    fun turned(w: Int, h: Int, turn: Int): Pair<Int, Int> = if (turn % 180 == 0) w to h else h to w

    /**
     * 화면 크기·여백이 바뀐 뒤(돌림·폴드 펴기·접기) 보던 자리 유지: 옛 칸 가운데의 그림 점(ix, iy)을 새 칸 가운데에 두고,
     * 맞춤 대비 배율(zoom = s ÷ 맞춤)을 그대로 둔다. 새 위치(tx, ty)와 배율을 돌려준다(가장자리 맞추기는 부른 쪽이).
     */
    fun keepCenter(ix: Float, iy: Float, zoom: Float, newFit: Float, cx: Float, cy: Float): Triple<Float, Float, Float> {
        val s = newFit * zoom
        return Triple(cx - ix * s, cy - iy * s, s)
    }
}

/**
 * 라이트박스의 확대 보기(2.1.0, 2.2.1 다듬음).
 * - 두 손가락 핀치(초점 기준), 한 손가락 끌어 옮기기(가장자리 밖은 덜 따라옴), 놓으면 범위 안으로 부드럽게 돌아오기, 플링 관성.
 * - 두 번 탭: 그 자리 2.5배 ↔ 맞춤. 두 번 탭한 손가락을 떼지 않고 위아래로 끌면 한 손 확대·축소(구글 포토처럼, 아래로 = 확대).
 * - 마우스 휠(DeX·마우스): 포인터 기준. 최소 = 맞춤, 최대 = ZoomMath.maxScale(원본 1픽셀이 화면 4픽셀 이상까지).
 * - 맞춤 배율에서 좌우로 밀면 이웃 그림이 손가락을 따라 들어오고(pager.peek), 놓으면 넘어가거나 돌아온다.
 *   위·아래로 밀면 닫기(배경이 점점 투명, onDismissProgress·onDismiss). 탭 한 번은 onTap(막대 숨기기·보이기).
 * - 맞춤은 늘 보이는 칸(setSafeInsets: 시스템 바·가림을 뺀 자리) 안. 크기·여백이 바뀌면(돌림·폴드) 보던 자리를 그대로 둔다.
 * - 왼쪽으로 돌리기(turnLeft)는 보기만 돌린다(원본은 그대로).
 * 움직이는 동안에만 다시 그린다(애니메이션·플링이 끝나면 아무 일도 하지 않는다).
 */
class ZoomImageView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    /** 이전(-1)·다음(+1)으로 넘기기. */
    interface Pager {
        fun canGo(dir: Int): Boolean
        fun go(dir: Int)
        /** 그쪽 이웃의 그림과 돌린 각도(넘기는 동안 옆에 그린다). 없으면 null. */
        fun peek(dir: Int): Pair<Bitmap, Int>? = null
    }

    var pager: Pager? = null
    var onTap: (() -> Unit)? = null
    /** 그림 1픽셀이 화면 1픽셀보다 커지도록 확대함(ZoomMath.overPixel). 그 선을 넘어설 때마다 한 번(라이트박스가 원본을 받는다). */
    var onPixelZoom: (() -> Unit)? = null
    /** 위·아래로 밀어 닫는 정도(0 = 그대로, 1 = 다 밀었음). 배경·막대를 그만큼 흐리게. */
    var onDismissProgress: ((Float) -> Unit)? = null
    /** 충분히 밀고 놓음: 닫는다. */
    var onDismiss: (() -> Unit)? = null

    private var bmp: Bitmap? = null
    /** 보기만 돌린 각도(시계 반대 방향, 0·90·180·270). */
    var turn = 0
        private set
    /** 원본 픽셀 ÷ 지금 그림 픽셀(최대 배율 계산). */
    private var native = 1f
    private var s = 1f
    private var tx = 0f
    private var ty = 0f
    private var fitS = 1f
    private var maxS = 2f
    /** 보이는 칸의 여백(시스템 바·가림). 그림은 여백 뒤까지 그리지만 맞춤·가장자리는 칸 기준. */
    private var insL = 0
    private var insT = 0
    private var insR = 0
    private var insB = 0
    /** 맞춤 배율에서 좌우로 민 거리(넘기기). */
    private var swipe = 0f
    /** 맞춤 배율에서 위아래로 민 거리(닫기). */
    private var drop = 0f
    /** 이번 손짓이 넘기기(가로)인지 세로인지 정했는가(맞춤 배율에서만). 0 = 아직, 1 = 가로, 2 = 세로. */
    private var axis = 0
    private var flinging = false
    private var lastFx = 0f
    private var lastFy = 0f
    private var anim: ValueAnimator? = null
    /** 지금 anim이 넘기기·닫기 애니메이션인지(새 그림이 들어와도 멈추지 않는다). */
    private var swipeAnim = false
    /** 돌리기 애니메이션의 진행(1 = 끝, 그 전에는 옛 방향·크기에서 새 쪽으로). */
    private var turnF = 1f
    private var turnFrom = 1f
    /** 지난번 그릴 때 overPixel이었는지(넘어서는 순간만 알린다). */
    private var overPixel = false
    /** 두 번 탭 뒤 끌어서 한 손 확대 중인지와 그 시작(손가락 자리·배율). */
    private var quick = false
    private var qx = 0f
    private var qy = 0f
    private var qs = 1f
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val m = Matrix()
    private val scroller = OverScroller(context)
    private val vc = ViewConfiguration.get(context)
    private val minFling = vc.scaledMinimumFlingVelocity * 4f
    private val slop = vc.scaledTouchSlop.toFloat()
    private val dp = resources.displayMetrics.density
    /** 이웃 그림과의 틈. */
    private val gap = 24 * dp

    private val zoomed: Boolean get() = s > fitS * 1.01f

    /** 지금 ZoomMath.overPixel인지(그림을 바꾼 직후 이미 크게 확대돼 있었는지 볼 때). */
    val pixelZoomed: Boolean get() = bmp != null && ZoomMath.overPixel(s, fitS)

    private val left0: Float get() = insL.toFloat()
    private val top0: Float get() = insT.toFloat()
    private val right0: Float get() = (width - insR).toFloat()
    private val bottom0: Float get() = (height - insB).toFloat()
    private val availW: Int get() = maxOf(1, width - insL - insR)
    private val availH: Int get() = maxOf(1, height - insT - insB)

    /** 돌린 뒤의 그림 가로세로. */
    private fun ew(b: Bitmap) = if (turn % 180 == 0) b.width else b.height
    private fun eh(b: Bitmap) = if (turn % 180 == 0) b.height else b.width

    private val flingStep = object : Runnable {
        override fun run() {
            if (!scroller.computeScrollOffset()) return
            tx = scroller.currX.toFloat()
            ty = scroller.currY.toFloat()
            invalidate()
            postOnAnimation(this)
        }
    }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
            if (bmp == null || quick) return false
            lastFx = d.focusX
            lastFy = d.focusY
            // 넘기던·닫던 중이면 그만두고 확대
            swipe = 0f
            if (drop != 0f) { drop = 0f; onDismissProgress?.invoke(0f) }
            return true
        }

        override fun onScale(d: ScaleGestureDetector): Boolean {
            // 손가락을 따라 조금 넘거나 모자라게 두었다가 놓으면 범위 안으로 돌아온다.
            val to = (s * d.scaleFactor).coerceIn(fitS * 0.6f, maxS * 1.5f)
            val (nx, ny) = ZoomMath.zoomAt(tx, ty, s, to, d.focusX, d.focusY)
            s = to
            tx = nx + (d.focusX - lastFx)
            ty = ny + (d.focusY - lastFy)
            lastFx = d.focusX
            lastFy = d.focusY
            invalidate()
            return true
        }
    }).apply { isQuickScaleEnabled = false } // 두 번 탭 뒤 끌기는 아래에서 직접(두 번 탭 확대와 겹치지 않게)

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (bmp == null || scaleDetector.isInProgress || e2.pointerCount > 1 || quick) return true
            if (zoomed) {
                val b = bmp ?: return true
                // 가장자리를 넘으면 덜 따라온다(놓으면 되돌아온다).
                tx -= if (outsideX(tx, ew(b) * s)) dx * 0.35f else dx
                ty -= if (outsideY(ty, eh(b) * s)) dy * 0.35f else dy
            } else {
                if (axis == 0) axis = if (abs(dx) >= abs(dy)) 1 else 2
                if (axis == 1) {
                    val next = swipe - dx
                    val dir = if (next < 0) 1 else -1
                    swipe = if (pager?.canGo(dir) == true) next else swipe - dx * 0.3f
                } else {
                    drop -= dy
                    onDismissProgress?.invoke(dropProgress())
                }
            }
            invalidate()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (bmp == null || scaleDetector.isInProgress || quick) return false
            if (!zoomed) {
                if (axis == 2 && drop != 0f) { flinging = true; finishDrop(vy); return true }
                if (swipe == 0f) return false
                flinging = true
                finishSwipe(vx)
                return true
            }
            val b = bmp ?: return false
            val cw = ew(b) * s
            val ch = eh(b) * s
            if (outsideX(tx, cw) || outsideY(ty, ch)) return false
            val minX = if (cw > availW) (right0 - cw).toInt() else tx.toInt()
            val maxX = if (cw > availW) left0.toInt() else tx.toInt()
            val minY = if (ch > availH) (bottom0 - ch).toInt() else ty.toInt()
            val maxY = if (ch > availH) top0.toInt() else ty.toInt()
            scroller.fling(tx.toInt(), ty.toInt(), vx.toInt(), vy.toInt(), minX, maxX, minY, maxY)
            flinging = true
            postOnAnimation(flingStep)
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean = true

        /** 두 번째 탭: 떼면 확대 ↔ 맞춤, 떼지 않고 위아래로 끌면 한 손 확대(끈 거리만큼 2의 거듭제곱으로). */
        override fun onDoubleTapEvent(e: MotionEvent): Boolean {
            val b = bmp ?: return true
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { quick = false; qx = e.x; qy = e.y; qs = s; stopMotion() }
                MotionEvent.ACTION_MOVE -> {
                    if (!quick && abs(e.y - qy) > slop) { quick = true; swipe = 0f }
                    if (quick) {
                        val to = (qs * 2f.pow((e.y - qy) / (160 * dp))).coerceIn(fitS * 0.6f, maxS * 1.5f)
                        val (nx, ny) = ZoomMath.zoomAt(tx, ty, s, to, qx, qy)
                        s = to
                        tx = nx
                        ty = ny
                        invalidate()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (quick) {
                        quick = false
                        lastFx = qx
                        lastFy = qy
                        settle()
                    } else if (zoomed) {
                        animateTo(fitS, ZoomMath.clampIn(0f, ew(b) * fitS, left0, right0), ZoomMath.clampIn(0f, eh(b) * fitS, top0, bottom0))
                    } else {
                        val to = minOf(maxS, fitS * 2.5f)
                        val (nx, ny) = ZoomMath.zoomAt(tx, ty, s, to, e.x, e.y)
                        animateTo(to, ZoomMath.clampIn(nx, ew(b) * to, left0, right0), ZoomMath.clampIn(ny, eh(b) * to, top0, bottom0))
                    }
                }
                MotionEvent.ACTION_CANCEL -> if (quick) { quick = false; settle() }
            }
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            performClick()
            return true
        }
    }).apply { setIsLongpressEnabled(false) } // 누른 채 잠깐 있다가 끌어도 옮겨지게

    /**
     * 그림을 바꾼다. keepView면(같은 사진의 미리보기 → 원본) 지금 보이는 자리·배율·돌린 각도를 그대로 두고 픽셀만 바꾼다.
     * 아니면 turn 각도로 화면에 맞춘다. nativePerBitmap은 원본 픽셀 ÷ 이 그림 픽셀(모르면 1).
     */
    fun setImage(b: Bitmap?, keepView: Boolean, nativePerBitmap: Float = 1f, turn: Int = this.turn) {
        val old = bmp
        bmp = b
        native = nativePerBitmap
        if (b == null) { stopMotion(); invalidate(); return }
        // 배율 기준이 바뀌므로 확대·플링 애니메이션은 멈춘다. 넘기기(새 그림이 들어오는 중)는 그대로 둔다.
        if (!swipeAnim) stopMotion()
        if (keepView && old != null && zoomed && width > 0 && turn == this.turn) {
            val r = ew(old).toFloat() / ew(b)
            s *= r
            computeLimits()
            clampHard()
        } else {
            this.turn = turn
            turnF = 1f
            fitNow()
        }
        invalidate()
    }

    /** 보이는 칸의 여백(시스템 바·가림). 바뀌면 보던 자리를 그대로 두고 다시 맞춘다. */
    fun setSafeInsets(l: Int, t: Int, r: Int, b: Int) {
        if (l == insL && t == insT && r == insR && b == insB) return
        val keep = capture(width, height)
        insL = l; insT = t; insR = r; insB = b
        restore(keep)
    }

    /** 왼쪽(시계 반대)으로 90도 돌린다 — 보기만. 맞춤으로 돌아가며 옛 모양에서 새 모양으로 부드럽게. */
    fun turnLeft() {
        if (bmp == null) return
        stopMotion()
        val oldS = s
        turn = (turn + 90) % 360
        fitNow()
        turnFrom = if (s > 0f) oldS / s else 1f
        val a = ValueAnimator.ofFloat(0f, 1f).setDuration(220)
        a.interpolator = DecelerateInterpolator()
        a.addUpdateListener { turnF = it.animatedValue as Float; invalidate() }
        a.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) { turnF = 1f; if (anim === a) anim = null; invalidate() }
        })
        anim = a
        swipeAnim = false
        turnF = 0f
        a.start()
    }

    /** 화면에 맞춘 배율·가운데 자리로. */
    private fun fitNow() {
        computeLimits()
        val b = bmp ?: return
        s = fitS
        tx = ZoomMath.clampIn(0f, ew(b) * s, left0, right0)
        ty = ZoomMath.clampIn(0f, eh(b) * s, top0, bottom0)
    }

    private fun computeLimits() {
        val b = bmp ?: return
        fitS = ZoomMath.fit(ew(b), eh(b), availW, availH)
        maxS = ZoomMath.maxScale(fitS, native)
    }

    private fun clampHard() {
        val b = bmp ?: return
        s = s.coerceIn(fitS, maxS)
        tx = ZoomMath.clampIn(tx, ew(b) * s, left0, right0)
        ty = ZoomMath.clampIn(ty, eh(b) * s, top0, bottom0)
    }

    private fun outsideX(t: Float, content: Float) = abs(ZoomMath.clampIn(t, content, left0, right0) - t) > 0.5f
    private fun outsideY(t: Float, content: Float) = abs(ZoomMath.clampIn(t, content, top0, bottom0) - t) > 0.5f

    /** 크기·여백이 바뀌기 전의 보던 자리: 칸 가운데의 그림 점과 맞춤 대비 배율(맞춤이었으면 null = 다시 맞춘다). */
    private data class Spot(val ix: Float, val iy: Float, val zoom: Float)

    private fun capture(w: Int, h: Int): Spot? {
        if (bmp == null || !zoomed || w <= 0 || h <= 0) return null
        val cx = insL + (w - insL - insR) / 2f
        val cy = insT + (h - insT - insB) / 2f
        return Spot((cx - tx) / s, (cy - ty) / s, s / fitS)
    }

    private fun restore(spot: Spot?) {
        stopMotion()
        if (spot == null) { fitNow(); invalidate(); return }
        computeLimits()
        val (nx, ny, ns) = ZoomMath.keepCenter(spot.ix, spot.iy, spot.zoom, fitS, left0 + availW / 2f, top0 + availH / 2f)
        tx = nx; ty = ny; s = ns
        clampHard()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        restore(capture(oldw, oldh))
    }

    /** 위아래로 민 정도(0–1): 보이는 높이의 40 %를 밀면 다 민 것. */
    private fun dropProgress(): Float = (abs(drop) / (availH * 0.4f)).coerceIn(0f, 1f)

    override fun onDraw(canvas: Canvas) {
        val b = bmp ?: return
        if (b.isRecycled) return
        val cx = left0 + availW / 2f
        val cy = top0 + availH / 2f
        canvas.save()
        if (drop != 0f) {
            // 닫으려고 미는 동안: 손가락을 따라 내려가며 조금 작아진다
            val k = 1f - 0.25f * dropProgress()
            canvas.translate(0f, drop)
            canvas.scale(k, k, cx, cy)
        }
        if (turnF < 1f) {
            // 돌리는 중: 옛 방향·크기에서 새 쪽으로
            val k = turnFrom + (1f - turnFrom) * turnF
            canvas.scale(k, k, cx, cy)
            canvas.rotate(90f * (1f - turnF), cx, cy)
        }
        drawPicture(canvas, b, turn, tx + swipe, ty, s)
        canvas.restore()
        if (swipe != 0f) {
            // 넘기는 동안 이웃 그림이 손가락을 따라 옆에서 들어온다(맞춤 배율·가운데).
            val dir = if (swipe < 0) 1 else -1
            pager?.peek(dir)?.let { (nb, nt) ->
                if (!nb.isRecycled) {
                    val (w2, h2) = ZoomMath.turned(nb.width, nb.height, nt)
                    val f = ZoomMath.fit(w2, h2, availW, availH)
                    val x = left0 + (availW - w2 * f) / 2f + swipe + dir * (width + gap)
                    val y = top0 + (availH - h2 * f) / 2f
                    drawPicture(canvas, nb, nt, x, y, f)
                }
            }
        }
        // 배율이 바뀌는 길(핀치·두 번 탭·휠·되돌아오기)이 모두 여기를 지난다. 알림은 그리기가 끝난 뒤에.
        val over = ZoomMath.overPixel(s, fitS)
        if (over && !overPixel) post { onPixelZoom?.invoke() }
        overPixel = over
    }

    /** 그림 b를 turn만큼 돌린 상자를 (x, y)에 배율 sc로. */
    private fun drawPicture(canvas: Canvas, b: Bitmap, t: Int, x: Float, y: Float, sc: Float) {
        val (w2, h2) = ZoomMath.turned(b.width, b.height, t)
        m.reset()
        if (t != 0) {
            m.setRotate(-t.toFloat(), b.width / 2f, b.height / 2f)
            m.postTranslate((w2 - b.width) / 2f, (h2 - b.height) / 2f)
        }
        m.postScale(sc, sc)
        m.postTranslate(x, y)
        canvas.drawBitmap(b, m, paint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            // 넘기기·닫기 애니메이션 중에 누르면 그 자리에서 이어 잡지 않고 끝까지 보낸다(반쯤 넘어간 채 남지 않게).
            stopMotion()
            flinging = false
            axis = 0
        }
        scaleDetector.onTouchEvent(e)
        gestures.onTouchEvent(e)
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (!flinging && anim == null) settle() // 두 번 탭 애니메이션 중이면 그대로 둔다
        }
        return true
    }

    /** 탭 한 번(접근성 서비스의 누르기도 같다): 막대 숨기기·보이기. */
    override fun performClick(): Boolean {
        super.performClick()
        onTap?.invoke()
        return true
    }

    /** 마우스 휠·터치패드 스크롤: 포인터 자리를 기준으로 확대·축소(맞춤 ~ 최대 안에서). */
    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        if (e.isFromSource(InputDevice.SOURCE_CLASS_POINTER) && e.actionMasked == MotionEvent.ACTION_SCROLL && bmp != null) {
            val v = e.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (v == 0f) return false
            stopMotion()
            swipe = 0f
            val to = (s * 1.25f.pow(v)).coerceIn(fitS, maxS)
            val (nx, ny) = ZoomMath.zoomAt(tx, ty, s, to, e.x, e.y)
            s = to
            tx = nx
            ty = ny
            clampHard()
            invalidate()
            return true
        }
        return super.onGenericMotionEvent(e)
    }

    /** 손을 뗌: 넘기던·닫던 중이면 마무리, 아니면 배율·위치를 범위 안으로 되돌린다. */
    private fun settle() {
        val b = bmp ?: return
        if (swipe != 0f) { finishSwipe(0f); return }
        if (drop != 0f) { finishDrop(0f); return }
        val to = s.coerceIn(fitS, maxS)
        val (nx, ny) = if (to != s) ZoomMath.zoomAt(tx, ty, s, to, lastFx, lastFy) else tx to ty
        val cx = ZoomMath.clampIn(nx, ew(b) * to, left0, right0)
        val cy = ZoomMath.clampIn(ny, eh(b) * to, top0, bottom0)
        if (to != s || cx != tx || cy != ty) animateTo(to, cx, cy)
    }

    /**
     * 넘기기 마무리: 충분히 밀었거나 그쪽으로 빨리 튕겼으면 이웃 그림이 가운데로 다 들어온 뒤 넘긴다(pager.go가 같은 자리에 그 그림을
     * 맞춤으로 놓으므로 이어진다). 아니면 제자리로.
     */
    private fun finishSwipe(vx: Float) {
        val w = width.toFloat()
        val dir = if (swipe < 0) 1 else -1
        val fast = abs(vx) > minFling && vx * swipe > 0
        val go = (abs(swipe) > w * 0.18f || fast) && pager?.canGo(dir) == true
        if (!go) { animateOffset(true, swipe, 0f) { }; return }
        animateOffset(true, swipe, -dir * (w + gap)) {
            swipe = 0f
            pager?.go(dir)
        }
    }

    /** 닫기 마무리: 충분히 밀었거나 빨리 튕겼으면 그쪽으로 밀려 나가며 닫고, 아니면 제자리로(배경도 다시 짙게). */
    private fun finishDrop(vy: Float) {
        val fast = abs(vy) > minFling && vy * drop > 0
        if (abs(drop) > availH * 0.15f || fast) {
            val to = if (drop > 0) height.toFloat() else -height.toFloat()
            animateOffset(false, drop, to) { onDismiss?.invoke() }
        } else animateOffset(false, drop, 0f) { onDismissProgress?.invoke(0f) }
    }

    /** 넘기기(horizontal) 또는 닫기 거리만 움직인다. then은 끝나거나 멈춰도(stopMotion) 꼭 한 번 부른다. */
    private fun animateOffset(horizontal: Boolean, from: Float, to: Float, then: () -> Unit) {
        anim?.cancel()
        val a = ValueAnimator.ofFloat(from, to).setDuration(if (horizontal) 220 else 200)
        a.interpolator = DecelerateInterpolator()
        a.addUpdateListener {
            val v = it.animatedValue as Float
            if (horizontal) swipe = v else { drop = v; onDismissProgress?.invoke(dropProgress()) }
            invalidate()
        }
        a.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (anim === a) { anim = null; swipeAnim = false }
                if (horizontal) swipe = to else drop = to
                then()
                invalidate()
            }
        })
        anim = a
        swipeAnim = true
        a.start()
    }

    private fun animateTo(s2: Float, tx2: Float, ty2: Float) {
        anim?.cancel()
        val s1 = s
        val x1 = tx
        val y1 = ty
        val a = ValueAnimator.ofFloat(0f, 1f).setDuration(220)
        a.interpolator = DecelerateInterpolator()
        a.addUpdateListener {
            val f = it.animatedValue as Float
            s = s1 + (s2 - s1) * f
            tx = x1 + (tx2 - x1) * f
            ty = y1 + (ty2 - y1) * f
            invalidate()
        }
        a.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) { if (anim === a) anim = null }
        })
        anim = a
        swipeAnim = false
        a.start()
    }

    private fun stopMotion() {
        // 끝으로 건너뛴다. 끝 콜백이 이어 하는 일(넘기기 마무리 → 새 그림)도 끝까지 — 반쯤 넘어간 채로 남지 않게.
        while (true) {
            val a = anim ?: break
            anim = null
            swipeAnim = false
            a.end()
        }
        turnF = 1f
        scroller.forceFinished(true)
        removeCallbacks(flingStep)
    }

    override fun onDetachedFromWindow() {
        stopMotion()
        super.onDetachedFromWindow()
    }
}
