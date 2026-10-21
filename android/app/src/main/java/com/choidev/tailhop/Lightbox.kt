package com.choidev.tailhop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.text.format.Formatter
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * 라이트박스에 보일 그림 만들기(2.1.0). 긴 변은 MAX_SIDE 이하로 줄여 읽는다(메모리·GPU 한도).
 * API 28+는 ImageDecoder(EXIF 회전 반영, HEIC 등), 그 아래는 BitmapFactory를 2의 거듭제곱으로 줄여 읽고 EXIF 회전만 따로 돌린다.
 * 읽을 수 없거나 메모리가 모자라면 null. 디스크에는 아무것도 쓰지 않는다(잠긴 폴더 안 파일도 메모리에서만).
 * 2.2.1: 원본의 가로세로 픽셀(보이는 방향)과 찍은 날짜(EXIF)도 함께 읽는다(Picture·Details) — 정보 창과 최대 배율에.
 */
object Pictures {
    const val MAX_SIDE = 4096

    /** 원본의 가로세로(보이는 방향)와 찍은 날짜(EXIF DateTimeOriginal, ms, 모르면 null). */
    data class Details(val w: Int, val h: Int, val taken: Long?)

    /** 받은 바이트(앞 len바이트)에서. */
    fun decode(buf: ByteArray, len: Int = buf.size): Bitmap? = picture(buf, len, false)?.bmp

    /** 이 폰의 파일(content URI)에서. */
    fun decode(ctx: Context, uri: Uri): Bitmap? = picture(ctx, uri)?.bmp

    /** 그림 + 원본 픽셀·찍은 날짜. original이면 그 바이트가 원본이다(아니면 화면용·미리보기 — 원본 정보로 쓰지 않는다). */
    fun picture(buf: ByteArray, len: Int = buf.size, original: Boolean = true): Lightbox.Picture? {
        var nativeLong = 0
        val b = guard {
            if (Build.VERSION.SDK_INT >= 28) ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(buf, 0, len).slice())) { d, info, _ ->
                nativeLong = maxOf(info.size.width, info.size.height)
                limit(d, info.size.width, info.size.height)
            } else legacy({ ByteArrayInputStream(buf, 0, len) }) { nativeLong = it }
        } ?: return null
        return Lightbox.Picture.of(b, nativeLong, if (original) taken { ByteArrayInputStream(buf, 0, len) } else null, original)
    }

    /** 이 폰의 파일(content URI)에서 그림 + 원본 정보. */
    fun picture(ctx: Context, uri: Uri): Lightbox.Picture? {
        var nativeLong = 0
        val b = guard {
            if (Build.VERSION.SDK_INT >= 28) ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { d, info, _ ->
                nativeLong = maxOf(info.size.width, info.size.height)
                limit(d, info.size.width, info.size.height)
            } else legacy({ ctx.contentResolver.openInputStream(uri) }) { nativeLong = it }
        } ?: return null
        return Lightbox.Picture.of(b, nativeLong, taken { ctx.contentResolver.openInputStream(uri) }, true)
    }

    /**
     * 원본의 앞부분(머리)만으로 가로세로·찍은 날짜를 읽는다(정보 창: 화면용 그림만 받았을 때). JPEG·PNG는 머리에 크기가 있다.
     * 읽을 수 없으면 null. 메모리에서만.
     */
    fun details(buf: ByteArray, len: Int = buf.size): Details? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(buf, 0, len, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) null else {
            val deg = exifDegrees { ByteArrayInputStream(buf, 0, len) }
            val (w, h) = if (deg % 180 == 0) o.outWidth to o.outHeight else o.outHeight to o.outWidth
            Details(w, h, taken { ByteArrayInputStream(buf, 0, len) })
        }
    } catch (e: Exception) { null }

    private inline fun guard(block: () -> Bitmap?): Bitmap? = try { block() } catch (e: Exception) { null } catch (e: OutOfMemoryError) { null }

    @androidx.annotation.RequiresApi(28)
    private fun limit(d: ImageDecoder, w: Int, h: Int) {
        val m = maxOf(w, h)
        if (m <= MAX_SIDE) return
        val r = MAX_SIDE.toFloat() / m
        d.setTargetSize(maxOf(1, (w * r).roundToInt()), maxOf(1, (h * r).roundToInt()))
    }

    /** API 26–27: 크기만 먼저 읽고 줄여 읽은 뒤 EXIF 방향대로 돌린다(뒤집기는 드물어 하지 않는다). */
    private fun legacy(open: () -> InputStream?, nativeLong: (Int) -> Unit): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open()?.use { BitmapFactory.decodeStream(it, null, o) }
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        nativeLong(maxOf(o.outWidth, o.outHeight))
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / sample > MAX_SIDE) sample *= 2
        val b = open()?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
        val deg = exifDegrees(open)
        if (deg == 0) return b
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(deg.toFloat()) }, true)
    }

    private fun exifDegrees(open: () -> InputStream?): Int = try {
        open()?.use {
            when (android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } ?: 0
    } catch (e: Exception) { 0 }

    /** EXIF의 찍은 날짜("yyyy:MM:dd HH:mm:ss", 시간대 없음 → 이 폰의 시간대로). 없으면 null. */
    private fun taken(open: () -> InputStream?): Long? = try {
        open()?.use { s ->
            val x = android.media.ExifInterface(s)
            val raw = x.getAttribute(android.media.ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: x.getAttribute(android.media.ExifInterface.TAG_DATETIME_DIGITIZED)
            raw?.trim()?.takeIf { it.length >= 19 }?.let { SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(it.take(19))?.time }
        }
    } catch (e: Exception) { null }
}

/** 받은 바이트를 복사하지 않고 그대로 디코더에 넘기는 버퍼(64 MB 원본을 두 벌 들지 않게). */
class GrowBuffer(size: Int) : ByteArrayOutputStream(size) {
    fun decode(): Bitmap? = Pictures.decode(buf, count)

    /** 그림 + 원본 정보(original이면 이 바이트가 원본). */
    fun picture(original: Boolean): Lightbox.Picture? = Pictures.picture(buf, count, original)
}

/**
 * 전체 화면 라이트박스(2.1.0, 2.2.1 다듬음): 검은 바탕에 확대 보기(ZoomImageView), 위 막대(닫기·이름·n / m)와 진행 막대,
 * 아래에 필름 띠(작은 미리보기 가로 줄, 지금 것 강조, 누르면 그리로)와 버튼 막대(source의 버튼 + 정보·왼쪽으로 돌리기).
 * - 맞춤 배율에서 좌우로 밀면 이웃이 손가락을 따라 들어오고, 위·아래로 밀면 닫힌다(배경이 점점 투명). 탭 한 번은 막대와 시스템 바를
 *   함께 숨기기·보이기(몰입), 뒤로 가기는 정보 창 → 닫기.
 * - 그림은 먼저 source.quick(메모리에 있는 미리보기)을 바로 보이고, source.load(작업 스레드)가 끝나면 같은 자리에서 바꾼다.
 *   앞뒤 한 장은 미리 받는다(source.prefetch가 허락하는 것 — 화면용 버전, 다른 스레드). 크게 확대하면 원본으로(source.original).
 * - 다른 그림으로 넘기거나 닫으면 받던 것을 취소한다(Thumbs.Job — 소켓을 닫는다). 큰 그림은 지금 것과 앞뒤 하나씩만 들고 있다.
 * - 창에 FLAG_SECURE(§18.4): 캡처·녹화·최근 앱 미리보기에서 가려진다.
 * 클라우드 화면과 대화방이 같이 쓴다(항목 종류 T와 버튼은 source가 정한다).
 */
class Lightbox<T>(private val act: AppCompatActivity, items: List<T>, start: Int, private val src: Source<T>) {
    /** 라이트박스 한 장: 그림, 원본의 가로세로(보이는 방향, 모르면 그림 크기), 찍은 날짜, 그림이 원본에서 읽은 것인지. */
    class Picture(val bmp: Bitmap, val nativeW: Int, val nativeH: Int, val taken: Long?, val original: Boolean) {
        /** 원본 픽셀 ÷ 그림 픽셀(최대 배율 계산). */
        val nativePerBitmap: Float get() = maxOf(nativeW, nativeH).toFloat() / maxOf(1, maxOf(bmp.width, bmp.height))

        companion object {
            /** 원본의 긴 변(nativeLong, 모르면 0)으로 보이는 방향의 원본 가로세로를 정한다(그림과 같은 비율). */
            fun of(b: Bitmap, nativeLong: Int, taken: Long?, original: Boolean): Picture {
                val long = maxOf(b.width, b.height)
                val r = if (nativeLong > long) nativeLong.toFloat() / long else 1f
                return Picture(b, (b.width * r).roundToInt(), (b.height * r).roundToInt(), taken, original)
            }

            /** 원본 정보 없이(미리보기·영상 장면). */
            fun plain(b: Bitmap) = Picture(b, b.width, b.height, null, false)
        }
    }

    /** 정보 창의 고정 줄: 이름, 크기, 바꾼 날짜(ms), 폴더(없으면 그 줄을 뺀다). */
    data class Info(val name: String, val size: Long?, val modified: Long?, val folder: String?)

    interface Source<T> {
        /** 같은 항목인지 알아볼 키(지운 뒤·캐시). */
        fun key(t: T): String
        fun name(t: T): String
        fun video(t: T): Boolean
        /** 메모리에 이미 있는 작은 그림(UI 스레드, 받지 않는다). 없으면 null. */
        fun quick(t: T): Bitmap?
        /** 크게 볼 그림(작업 스레드). progress(받은, 전체)는 아무 스레드에서나. job이 취소되면 멈추고 null. */
        fun load(t: T, job: Thumbs.Job, progress: (Long, Long) -> Unit): Picture?
        /** 앞뒤 한 장을 미리 받아도 되는지(가벼운 화면용 버전일 때만, 2.2.1). */
        fun prefetch(t: T): Boolean = true
        /** load가 화면용(작게 줄인) 그림을 주고, 100 %보다 크게 확대하면 loadOriginal로 원본을 받을 수 있는지(2.2.0). */
        fun original(t: T): Boolean = false
        /** 원본(작업 스레드, load와 같은 규칙). */
        fun loadOriginal(t: T, job: Thumbs.Job, progress: (Long, Long) -> Unit): Picture? = null
        /** 정보 창의 고정 줄(UI 스레드). */
        fun info(t: T): Info
        /** 원본 정보를 아직 모를 때 원본의 머리만 읽어 알아낸다(작업 스레드, 2.2.1). 모르면 null. */
        fun details(t: T, job: Thumbs.Job): Pictures.Details? = null
        /**
         * 필름 띠 칸에 미리보기를 붙인다(UI 스레드, 기본: 메모리에 있는 것만). 그림이 생기면(바로 또는 나중에) show를 부른다.
         * 칸이 재활용되면 unbindThumb.
         */
        fun bindThumb(t: T, view: ImageView, show: (Bitmap) -> Unit) { quick(t)?.let(show) }
        fun unbindThumb(view: ImageView) { Thumbs.unbind(view) }
        /** 아래 막대 버튼(왼쪽부터). 정보·돌리기는 라이트박스가 뒤에 더한다. */
        fun actions(): List<Action<T>>
        /** 영상 가운데 재생 표시를 누름. */
        fun play(t: T)
    }

    class Action<T>(val label: Int, val icon: Int, val run: (T) -> Unit)

    private val items = items.toMutableList()
    private var index = start.coerceIn(0, maxOf(0, items.size - 1))
    private val ui = Handler(Looper.getMainLooper())
    /** 지금 것과 원본 받기(작업 스레드 하나). */
    private val exec: ExecutorService = Executors.newSingleThreadExecutor()
    /** 앞뒤 미리 받기·정보 읽기(따로 하나 — 지금 것을 막지 않게). */
    private val pre: ExecutorService = Executors.newSingleThreadExecutor()
    /** 크게 읽은 그림(지금 것과 앞뒤 하나만). */
    private val full = HashMap<String, Picture>()
    /** full에 화면용 그림이 있어 크게 확대하면 원본으로 바꿀 항목(2.2.0, Source.original). */
    private val upgradable = HashSet<String>()
    /** 받는 중인 것(키 → 일). 지금 것도 앞뒤 미리 받기도 여기 — 미리 받던 것으로 넘어가면 그대로 이어 기다린다. */
    private val loading = HashMap<String, Thumbs.Job>()
    /** 원본으로 바꾸는 중(지금 것만). */
    private var upJob: Thumbs.Job? = null
    /** 보기만 돌린 각도(키 → 0·90·180·270). */
    private val turns = HashMap<String, Int>()
    /** 정보 창에 읽어 둔 원본 정보(키 → 정보). */
    private val details = HashMap<String, Pictures.Details>()
    private var detailsJob: Thumbs.Job? = null
    private var barsShown = true
    private val dialog = AppCompatDialog(act, R.style.Lightbox)
    // 창에 붙이지 않는 임시 부모: 레이아웃 크기 규칙만 읽는다(대화 상자가 창이 된다).
    private val root: View = LayoutInflater.from(act).inflate(R.layout.lightbox, FrameLayout(act), false)
    private val backdrop = ColorDrawable(Color.BLACK)
    private val zoom: ZoomImageView = root.findViewById(R.id.zoom)
    private val play: View = root.findViewById(R.id.play)
    private val message: TextView = root.findViewById(R.id.message)
    private val topBar: View = root.findViewById(R.id.topBar)
    private val bottomPanel: View = root.findViewById(R.id.bottomPanel)
    private val bottomBar: LinearLayout = root.findViewById(R.id.bottomBar)
    private val strip: RecyclerView = root.findViewById(R.id.lbStrip)
    private val nameView: TextView = root.findViewById(R.id.lbName)
    private val countView: TextView = root.findViewById(R.id.lbCount)
    private val progress: View = root.findViewById(R.id.lbProgress)
    private val infoScrim: View = root.findViewById(R.id.infoScrim)
    private val infoPanel: View = root.findViewById(R.id.infoPanel)
    private val infoRows: LinearLayout = root.findViewById(R.id.infoRows)
    private val stripAdapter = Strip()
    private val d = act.resources.displayMetrics.density

    /** 닫힘(뒤로 가기·닫기·마지막 항목을 지움). */
    var onClosed: (() -> Unit)? = null

    /** 지금 보이는 항목. */
    val current: T? get() = items.getOrNull(index)

    private val currentKey: String? get() = current?.let { src.key(it) }

    init {
        dialog.secure()
        dialog.setContentView(root)
        dialog.setOnDismissListener { cleanup() }
        root.background = backdrop
        root.findViewById<View>(R.id.lbClose).setOnClickListener { close() }
        play.setOnClickListener { current?.let { src.play(it) } }
        zoom.onTap = { setBars(!barsShown) }
        zoom.onPixelZoom = { upgrade() }
        zoom.onDismissProgress = { p -> dimFor(p) }
        zoom.onDismiss = { close() }
        zoom.pager = object : ZoomImageView.Pager {
            override fun canGo(dir: Int) = index + dir in items.indices
            override fun go(dir: Int) { if (index + dir in items.indices) show(index + dir) }
            override fun peek(dir: Int): Pair<Bitmap, Int>? {
                val t = items.getOrNull(index + dir) ?: return null
                val k = src.key(t)
                val b = full[k]?.bmp ?: src.quick(t) ?: return null
                return b to (turns[k] ?: 0)
            }
        }
        progress.pivotX = 0f
        infoScrim.setOnClickListener { hideInfo() }
        infoPanel.setOnClickListener { } // 창 안을 눌러도 닫히지 않게
        root.findViewById<View>(R.id.infoClose).setOnClickListener { hideInfo() }
        strip.layoutManager = LinearLayoutManager(act, LinearLayoutManager.HORIZONTAL, false)
        strip.adapter = stripAdapter
        strip.itemAnimator = null
        buildActions()
        dialog.onBackPressedDispatcher.addCallback(object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (infoScrim.visibility == View.VISIBLE) hideInfo() else close() }
        })
        // 그림은 시스템 바 뒤까지 그리고, 맞춤·막대는 시스템 바·가림을 피한다. 몰입(바를 숨김) 중에도 칸이 흔들리지 않게
        // 보이든 말든 바의 자리(IgnoringVisibility)를 쓴다.
        val side0 = bottomBar.paddingLeft
        val bottom0 = bottomPanel.paddingBottom
        val info0 = infoPanel.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars())
            val cut = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val l = maxOf(bars.left, cut.left)
            val t = maxOf(bars.top, cut.top)
            val r = maxOf(bars.right, cut.right)
            val b = maxOf(bars.bottom, cut.bottom)
            topBar.setPadding(l, t, r, 0)
            bottomPanel.setPadding(l, bottomPanel.paddingTop, r, bottom0 + b)
            bottomBar.setPadding(side0, bottomBar.paddingTop, side0, bottomBar.paddingBottom)
            infoPanel.setPadding(infoPanel.paddingLeft, infoPanel.paddingTop, infoPanel.paddingRight, info0 + b)
            zoom.setSafeInsets(l, t, r, b)
            insets
        }
    }

    fun show() {
        if (items.isEmpty()) return
        dialog.window?.let { w ->
            // 위아래로 밀어 닫을 때 뒤 화면이 비쳐 보이게(창 자체는 투명, 검은 바탕은 root가 그린다)
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setFormat(PixelFormat.TRANSLUCENT)
        }
        dialog.show()
        dialog.window?.let { w ->
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            WindowCompat.setDecorFitsSystemWindows(w, false)
            @Suppress("DEPRECATION") // API 35+에서는 시스템이 늘 투명하게 그린다
            w.statusBarColor = Color.TRANSPARENT
            @Suppress("DEPRECATION")
            w.navigationBarColor = Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= 28) w.attributes = w.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            WindowCompat.getInsetsController(w, w.decorView).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        ViewCompat.requestApplyInsets(root)
        strip.visibility = if (items.size > 1) View.VISIBLE else View.GONE
        show(index)
    }

    fun close() { if (dialog.isShowing) dialog.dismiss() else cleanup() }

    /** 항목을 뺀다(지웠다). 남은 게 없으면 닫고, 아니면 같은 자리(끝이었으면 앞)의 것을 보인다. */
    fun remove(t: T) {
        val k = src.key(t)
        val i = items.indexOfFirst { src.key(it) == k }
        if (i < 0) return
        items.removeAt(i)
        full.remove(k)
        upgradable.remove(k)
        loading.remove(k)?.cancel()
        if (items.isEmpty()) { close(); return }
        if (i < index || index >= items.size) index = maxOf(0, index - 1)
        stripAdapter.notifyItemRemoved(i)
        if (items.size <= 1) strip.visibility = View.GONE
        show(index)
    }

    private fun show(i: Int) {
        val t = items.getOrNull(i) ?: return
        val before = index
        index = i
        upJob?.cancel()
        upJob = null
        detailsJob?.let { tried.remove(it.key); it.cancel() } // 다 읽기 전에 넘겼으면 다음에 다시 읽는다
        detailsJob = null
        hideInfo()
        nameView.text = src.name(t)
        countView.text = act.getString(R.string.lightbox_counter, i + 1, items.size)
        play.visibility = if (src.video(t)) View.VISIBLE else View.GONE
        message.visibility = View.GONE
        if (before in items.indices) stripAdapter.notifyItemChanged(before)
        stripAdapter.notifyItemChanged(i)
        centerStrip(i)
        trim()
        val key = src.key(t)
        val turn = turns[key] ?: 0
        val have = full[key]
        if (have != null) {
            setProgress(null)
            zoom.setImage(have.bmp, keepView = false, nativePerBitmap = have.nativePerBitmap, turn = turn)
        } else {
            val quick = src.quick(t)
            zoom.setImage(quick, keepView = false, turn = turn)
            upgradable.remove(key)
            setProgress(if (loading.containsKey(key)) 0f else null)
            if (!loading.containsKey(key)) fetch(t, key, foreground = true)
        }
        prefetchAround()
    }

    /** 받은 그림이 들어옴(UI 스레드). 지금 것이면 같은 자리에서 바꾸고, 화면용이면 원본으로 바꿀 수 있게 적어 둔다. */
    private fun arrived(t: T, key: String, pic: Picture) {
        full[key] = pic
        trim()
        if (currentKey != key) return
        setProgress(null)
        zoom.setImage(pic.bmp, keepView = true, nativePerBitmap = pic.nativePerBitmap)
        if (src.original(t) && !pic.original) {
            upgradable += key
            // 화면용 그림이 오기 전에 이미 크게 확대해 두었으면 바로 원본으로.
            if (zoom.pixelZoomed) upgrade()
        }
    }

    /**
     * 크게 볼 그림을 받는다(지금 것은 exec, 앞뒤 미리 받기는 pre). 되면 full에 두고 지금 것이면 같은 자리에서 바꾼다.
     * 못 받으면(예외 또는 null) 지금 것일 때만 이유를 보인다. 콜백은 UI 스레드에서, 그 일이 아직 살아 있을 때만.
     */
    private fun fetch(t: T, key: String, foreground: Boolean) {
        val j = Thumbs.Job(key)
        loading[key] = j
        (if (foreground) exec else pre).execute {
            if (j.cancelled) return@execute
            var last = 0L
            val pic = try {
                src.load(t, j) { got, total ->
                    // 진행 막대는 0.1초에 한 번만 다시 그린다.
                    val now = System.currentTimeMillis()
                    if (total > 0 && now - last >= 100) {
                        last = now
                        ui.post { if (loading[key] === j && currentKey == key) setProgress(got.toFloat() / total) }
                    }
                }
            } catch (e: Exception) {
                if (!j.cancelled) ui.post {
                    if (loading[key] !== j) return@post
                    loading.remove(key)
                    if (currentKey == key) failed(src.quick(t) == null, Errors.text(act, e))
                }
                return@execute
            }
            ui.post {
                if (j.cancelled || loading[key] !== j) return@post
                loading.remove(key)
                if (pic == null) { if (currentKey == key) failed(src.quick(t) == null, null); return@post }
                arrived(t, key, pic)
            }
        }
    }

    /** 앞뒤 한 장을 미리 받는다(가벼운 것만, 이미 있거나 받는 중이면 그대로). 멀어진 것의 받기는 멈춘다. */
    private fun prefetchAround() {
        val keep = (index - 1..index + 1).mapNotNull { items.getOrNull(it)?.let { t -> src.key(t) } }.toSet()
        loading.entries.removeAll { (k, j) -> if (k !in keep) { j.cancel(); true } else false }
        for (i in listOf(index + 1, index - 1)) {
            val t = items.getOrNull(i) ?: continue
            val k = src.key(t)
            if (full.containsKey(k) || loading.containsKey(k) || !src.prefetch(t)) continue
            fetch(t, k, foreground = false)
        }
    }

    /**
     * 화면용 그림을 보이는 중에 100 %보다 크게 확대함(ZoomImageView.onPixelZoom): 원본을 받아 같은 자리·배율로 바꾼다.
     * 받는 동안 위 막대에 진행이 보이고, 못 받으면 알리고 다음에 다시 확대할 때 또 해 본다.
     */
    private fun upgrade() {
        val t = current ?: return
        val key = src.key(t)
        if (key !in upgradable || upJob != null || loading.containsKey(key)) return
        upgradable.remove(key)
        val j = Thumbs.Job(key)
        upJob = j
        exec.execute {
            if (j.cancelled) return@execute
            var last = 0L
            val r = try {
                src.loadOriginal(t, j) { got, total ->
                    val now = System.currentTimeMillis()
                    if (total > 0 && now - last >= 100) {
                        last = now
                        ui.post { if (upJob === j) setProgress(got.toFloat() / total) }
                    }
                }
            } catch (e: Exception) {
                if (!j.cancelled) ui.post {
                    if (upJob !== j) return@post
                    upJob = null
                    setProgress(null)
                    // 읽지 못한 그림(null)은 다시 해도 같다. 연결 오류만 다음 확대 때 다시.
                    if (currentKey == key && full.containsKey(key)) upgradable += key
                    Toast.makeText(act, act.getString(R.string.failed, Errors.text(act, e)), Toast.LENGTH_SHORT).show()
                }
                return@execute
            }
            ui.post {
                if (j.cancelled || upJob !== j) return@post
                upJob = null
                setProgress(null)
                if (r == null) return@post // 읽지 못한 원본: 화면용 그림을 그대로 둔다
                full[key] = r
                if (currentKey == key) zoom.setImage(r.bmp, keepView = true, nativePerBitmap = r.nativePerBitmap)
            }
        }
    }

    /** 크게 못 읽음: 미리보기도 없으면 가운데에 이유를 보인다(영상은 재생 표시가 대신한다). */
    private fun failed(nothingShown: Boolean, reason: String?) {
        setProgress(null)
        if (!nothingShown || play.visibility == View.VISIBLE) return
        message.text = if (reason != null) act.getString(R.string.lightbox_failed_reason, reason) else act.getString(R.string.lightbox_failed)
        message.visibility = View.VISIBLE
    }

    /** 지금 것과 앞뒤 하나만 남긴다(큰 그림은 메모리를 많이 쓴다). 미리보기는 Thumbs의 것이라 여기서 놓지 않는다. */
    private fun trim() {
        val keep = (index - 1..index + 1).mapNotNull { items.getOrNull(it)?.let { t -> src.key(t) } }.toSet()
        full.keys.retainAll(keep)
        upgradable.retainAll(full.keys)
    }

    private fun setProgress(f: Float?) {
        if (f == null) { progress.visibility = View.INVISIBLE; return }
        progress.visibility = View.VISIBLE
        progress.scaleX = f.coerceIn(0.02f, 1f)
    }

    /** 위아래로 밀어 닫는 동안: 배경이 점점 투명해지고 막대도 흐려진다. */
    private fun dimFor(p: Float) {
        backdrop.alpha = ((1f - p) * 255).roundToInt().coerceIn(0, 255)
        val a = if (barsShown) (1f - p * 2f).coerceIn(0f, 1f) else 0f
        topBar.alpha = a
        bottomPanel.alpha = a
        play.alpha = (1f - p * 2f).coerceIn(0f, 1f)
    }

    /** 막대 숨기기·보이기. 숨기면 시스템 바도 숨긴다(몰입 — 가장자리를 밀면 잠깐 보인다). */
    private fun setBars(on: Boolean) {
        barsShown = on
        for (v in listOf(topBar, bottomPanel)) {
            v.animate().cancel()
            if (on) { v.visibility = View.VISIBLE; v.animate().alpha(1f).setDuration(150).start() }
            else v.animate().alpha(0f).setDuration(150).withEndAction { if (!barsShown) v.visibility = View.INVISIBLE }.start()
        }
        dialog.window?.let { w ->
            val c = WindowCompat.getInsetsController(w, w.decorView)
            if (on) c.show(WindowInsetsCompat.Type.systemBars()) else c.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun buildActions() {
        val all = src.actions() + listOf(
            Action(R.string.lightbox_info, R.drawable.ic_info) { showInfo(it) },
            Action(R.string.lightbox_rotate, R.drawable.ic_rotate_left) { turnCurrent(it) },
        )
        for (a in all) {
            val btn = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                minimumHeight = (56 * d).toInt()
                setPadding(0, (6 * d).toInt(), 0, (6 * d).toInt())
                setBackgroundResource(R.drawable.bg_lightbox_btn)
                isClickable = true
                isFocusable = true
                contentDescription = act.getString(a.label)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { current?.let { t -> a.run(t) } }
            }
            btn.addView(ImageView(act).apply {
                setImageResource(a.icon)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                layoutParams = LinearLayout.LayoutParams((22 * d).toInt(), (22 * d).toInt())
            })
            btn.addView(TextView(act).apply {
                text = act.getString(a.label)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(Color.WHITE)
                maxLines = 1
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = (4 * d).toInt() }
            })
            bottomBar.addView(btn)
        }
    }

    /** 왼쪽으로 돌리기(보기만 — 원본은 그대로). 이 라이트박스를 닫을 때까지 그 장의 각도를 기억한다. */
    private fun turnCurrent(t: T) {
        zoom.turnLeft()
        turns[src.key(t)] = zoom.turn
    }

    // ---------- 정보 창 ----------

    /**
     * 정보(이름·크기·가로세로 픽셀·찍은 날짜·바꾼 날짜·폴더)를 아래에서 올라오는 창으로. 원본 정보를 아직 모르면(화면용 그림만 받음)
     * source.details로 원본의 머리만 읽어(메모리) 채운다 — 그동안 그 줄은 "읽는 중".
     */
    private fun showInfo(t: T) {
        val key = src.key(t)
        if (known(key) == null && detailsJob == null && key !in tried) {
            val j = Thumbs.Job(key)
            detailsJob = j
            tried += key
            pre.execute {
                val got = try { if (j.cancelled) null else src.details(t, j) } catch (e: Exception) { null }
                ui.post {
                    if (detailsJob !== j) return@post
                    detailsJob = null
                    if (got != null) details[key] = got
                    if (currentKey == key && infoScrim.visibility == View.VISIBLE) renderInfo(t)
                }
            }
        }
        renderInfo(t)
        infoScrim.animate().cancel()
        infoScrim.visibility = View.VISIBLE
        infoPanel.translationY = 40 * d
        infoScrim.alpha = 0f
        infoScrim.animate().alpha(1f).setDuration(150).start()
        infoPanel.animate().translationY(0f).setDuration(180).start()
    }

    /** 원본 정보를 읽어 본 항목(못 읽었으면 다시 묻지 않는다). */
    private val tried = HashSet<String>()

    /** 이미 아는 원본 정보: 원본에서 읽은 그림이 있으면 그것, 따로 읽어 둔 것이 있으면 그것. */
    private fun known(key: String): Pictures.Details? {
        full[key]?.takeIf { it.original }?.let { return Pictures.Details(it.nativeW, it.nativeH, it.taken) }
        return details[key]
    }

    private fun renderInfo(t: T) {
        val info = src.info(t)
        val key = src.key(t)
        val det = known(key)
        val reading = det == null && detailsJob != null
        infoRows.removeAllViews()
        row(R.string.info_name, info.name)
        info.size?.let { row(R.string.info_size, Formatter.formatFileSize(act, it)) }
        when {
            det != null -> row(R.string.info_pixels, act.getString(R.string.info_pixels_value, det.w, det.h,
                String.format(Locale.ROOT, "%.1f", det.w.toLong() * det.h / 1_000_000.0)))
            reading -> row(R.string.info_pixels, act.getString(R.string.info_reading))
        }
        det?.taken?.let { row(R.string.info_taken, dateText(it)) }
        info.modified?.takeIf { it > 0 }?.let { row(R.string.info_modified, dateText(it)) }
        info.folder?.let { row(R.string.info_folder, it) }
    }

    private fun dateText(ms: Long): String = DateUtils.formatDateTime(act, ms,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_WEEKDAY or
            DateUtils.FORMAT_ABBREV_WEEKDAY)

    private fun row(label: Int, value: String) {
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (8 * d).toInt(), 0, (8 * d).toInt())
        }
        box.addView(TextView(act).apply {
            text = act.getString(label)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(0xB3FFFFFF.toInt())
        })
        box.addView(TextView(act).apply {
            text = value
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(Color.WHITE)
            setTextIsSelectable(false)
            setPadding(0, (2 * d).toInt(), 0, 0)
        })
        infoRows.addView(box)
    }

    private fun hideInfo() {
        if (infoScrim.visibility != View.VISIBLE) return
        infoScrim.animate().cancel()
        infoScrim.animate().alpha(0f).setDuration(120).withEndAction { infoScrim.visibility = View.GONE }.start()
    }

    // ---------- 필름 띠 ----------

    /** 띠에서 지금 것이 가운데 오게. */
    private fun centerStrip(i: Int) {
        if (strip.visibility != View.VISIBLE) return
        val lm = strip.layoutManager as? LinearLayoutManager ?: return
        val cell = (STRIP_CELL_DP * d).toInt()
        strip.post { lm.scrollToPositionWithOffset(i, maxOf(0, (strip.width - cell) / 2)) }
    }

    private inner class Strip : RecyclerView.Adapter<Strip.VH>() {
        inner class VH(val frame: FrameLayout, val img: ImageView) : RecyclerView.ViewHolder(frame)

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val cell = (STRIP_CELL_DP * d).toInt()
            val frame = FrameLayout(act).apply {
                layoutParams = RecyclerView.LayoutParams(cell, cell).apply { marginEnd = (4 * d).toInt() }
                setBackgroundResource(R.drawable.bg_strip_cell)
                foreground = androidx.core.content.ContextCompat.getDrawable(act, R.drawable.bg_strip_on)
                clipToOutline = true
                isClickable = true
                isFocusable = true
            }
            val img = ImageView(act).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            frame.addView(img, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            return VH(frame, img)
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val t = items[position]
            src.unbindThumb(h.img)
            h.img.setImageResource(if (src.video(t)) R.drawable.ic_video else R.drawable.ic_image)
            h.img.scaleType = ImageView.ScaleType.CENTER_INSIDE
            h.img.imageAlpha = 110
            src.bindThumb(t, h.img) { bmp ->
                h.img.scaleType = ImageView.ScaleType.CENTER_CROP
                h.img.imageAlpha = 255
                h.img.setImageBitmap(bmp)
            }
            val on = position == index
            h.frame.isSelected = on
            h.frame.alpha = if (on) 1f else 0.62f
            h.frame.contentDescription = src.name(t)
            h.frame.setOnClickListener {
                val p = h.bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION && p != index) show(p)
            }
        }

        override fun onViewRecycled(holder: VH) { src.unbindThumb(holder.img) }
    }

    private var cleaned = false

    private fun cleanup() {
        if (cleaned) return
        cleaned = true
        upJob?.cancel()
        upJob = null
        detailsJob?.cancel()
        loading.values.forEach { it.cancel() }
        loading.clear()
        exec.shutdownNow()
        pre.shutdownNow()
        full.clear()
        upgradable.clear()
        zoom.setImage(null, keepView = false)
        onClosed?.invoke()
    }

    companion object {
        /** 필름 띠 칸 크기(dp). */
        const val STRIP_CELL_DP = 48
    }
}
