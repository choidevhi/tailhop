package com.choidev.tailhop

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import android.util.Rational
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlin.math.roundToInt

/**
 * 앱 안 영상 재생(2.2.0, 2.2.1 다듬음): 라이트박스의 재생 버튼으로 연다. 2.2 허브(hello caps "store")에서만 — 범위 요청(§17.3)이 필요하다.
 * 영상은 CloudVideo가 cloud_get 범위로 메모리에만 받는다(디스크 캐시 없음). 허브의 play(MPEG-TS, H.264+AAC)가 있으면 그것을,
 * 없으면 원본(mp4 등)을 재생한다 — 둘 다 ProgressiveMediaSource + DefaultExtractorsFactory.
 *
 * 2.2.1:
 * - 영상은 시스템 바·가림을 뺀 칸 안에만(넘치지 않게, 몰입 중에도 칸이 흔들리지 않게 바의 자리를 늘 뺀다). 맞춤/채우기 바꾸기.
 * - 직접 만든 조작(앱 모양): 재생/멈춤, 탐색 막대(받아 둔 범위), 지금/전체 시간, ±10초(버튼·두 번 탭, 반복 탭 누적), 속도(0.5–2배),
 *   음소거, 전체 화면(몰입), 화면 방향(시스템 따름 → 자동 회전 → 가로 → 세로), 가로 영상을 세로로 보면 "가로로 보기".
 * - 가로로 끌면 탐색(미리 시간), 오른쪽 세로로 끌면 음량, 왼쪽 세로로 끌면 밝기(이 창만).
 * - 이어 보기: 같은 파일을 다시 열면 마지막 자리부터(ResumeBook — 키는 경로의 HMAC, 이름은 두지 않는다).
 * - 이전·다음 영상(그 폴더 목록 순서), 끝나면 멈춘다. 오류(허브 끊김 등)면 이유와 다시 시도.
 * - 화면 속 화면(Android 8+, 홈을 누르면). 창은 캡처 막기(FLAG_SECURE, §18.4) — 화면 속 화면도 캡처에서 가려진다.
 * 화면이 가려지면(onStop) 재생기를 놓고 연결을 끊으며, 돌아오면 그 자리부터 다시 받는다. 돌려도 재생은 이어진다(configChanges).
 */
@OptIn(UnstableApi::class)
class VideoActivity : AppCompatActivity(), VideoGestures.Listener {
    /** 재생 목록의 한 편: 허브 경로, 이름, 딸린 버전(CloudVariants.PLAY 또는 null = 원본). */
    data class Entry(val path: String, val name: String, val variant: String?)

    companion object {
        private const val E_IP = "ip"
        private const val E_PATHS = "paths"
        private const val E_NAMES = "names"
        private const val E_VARIANTS = "variants"
        private const val E_INDEX = "index"
        private const val S_POS = "pos"
        private const val S_PLAY = "play"
        private const val S_INDEX = "index"
        private const val PREFS = "video_resume"
        private const val K_BOOK = "book"
        private const val K_SECRET = "secret"
        /** 화면 속 화면의 재생/멈춤 버튼(이 앱 안에서만 받는 방송). */
        private const val ACTION_PIP = "com.choidev.tailhop.VIDEO_PIP"
        private const val HIDE_MS = 3000L
        /** 화면 방향: 시스템 따름 → 자동 회전(회전 잠금을 풀어 기기 방향대로) → 가로 → 세로. */
        private val ORIENTS = intArrayOf(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR,
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT)

        /** entries는 그 폴더의 영상들(목록 순서), index는 지금 열 것. */
        fun start(ctx: Context, ip: String, entries: List<Entry>, index: Int) {
            ctx.startActivity(Intent(ctx, VideoActivity::class.java).putExtra(E_IP, ip)
                .putStringArrayListExtra(E_PATHS, ArrayList(entries.map { it.path }))
                .putStringArrayListExtra(E_NAMES, ArrayList(entries.map { it.name }))
                .putStringArrayListExtra(E_VARIANTS, ArrayList(entries.map { it.variant.orEmpty() }))
                .putExtra(E_INDEX, index))
        }
    }

    private lateinit var playerView: PlayerView
    private lateinit var stage: View
    private lateinit var gestures: VideoGestures
    private lateinit var controls: View
    private lateinit var top: View
    private lateinit var bottom: View
    private lateinit var centerRow: View
    private lateinit var nameView: TextView
    private lateinit var countView: TextView
    private lateinit var playBtn: ImageButton
    private lateinit var prevBtn: View
    private lateinit var nextBtn: View
    private lateinit var pipBtn: View
    private lateinit var scrub: ScrubBar
    private lateinit var timeNow: TextView
    private lateinit var timeTotal: TextView
    private lateinit var speedBtn: TextView
    private lateinit var speedScroll: View
    private lateinit var speedPanel: LinearLayout
    private lateinit var muteBtn: ImageButton
    private lateinit var fitBtn: ImageButton
    private lateinit var fullBtn: ImageButton
    private lateinit var landscapeHint: TextView
    private lateinit var seekHint: TextView
    private lateinit var levelHint: View
    private lateinit var levelIcon: ImageView
    private lateinit var levelFill: View
    private lateinit var levelText: TextView
    private lateinit var tapLeft: TextView
    private lateinit var tapRight: TextView
    private lateinit var resumePill: View
    private lateinit var resumeText: TextView
    private lateinit var errorBox: View
    private lateinit var message: TextView
    private lateinit var pairing: Pairing
    private lateinit var book: ResumeBook
    private var entries: List<Entry> = emptyList()
    private var index = 0
    private var player: ExoPlayer? = null
    private var video: CloudVideo? = null
    /** 화면이 가려질 때 기억한 자리·재생 중이었는지(돌아오면 이어서). */
    private var position = 0L
    private var playWhenReady = true
    private var controlsShown = true
    private var immersive = true
    private var fill = false
    private var muted = false
    private var speed = 1f
    private var orient = 0
    private var inPip = false
    private var ended = false
    private var videoW = 0
    private var videoH = 0
    /** 끌어 탐색·탭 탐색의 시작 자리와 지금 고른 자리(놓을 때·멈출 때 옮긴다). */
    private var seekBase = 0L
    private var seekTarget = -1L
    private var scrubbing = false
    /** 음량·밝기 끌기의 시작 값(0–1). */
    private var levelBase = 0f
    private val ui = Handler(Looper.getMainLooper())
    private val audio by lazy { getSystemService(AudioManager::class.java) }

    private val current: Entry? get() = entries.getOrNull(index)

    private val hideRun = Runnable { if (player?.isPlaying == true && !scrubbing) setControls(false) }
    private val hintOff = Runnable { seekHint.visibility = View.GONE; levelHint.visibility = View.GONE }
    private val tapOff = Runnable { tapLeft.visibility = View.GONE; tapRight.visibility = View.GONE }
    private val resumeOff = Runnable { resumePill.visibility = View.GONE }
    /** 두 번 탭 탐색: 탭이 멈추면(TapSeek.idle) 모은 자리로 옮긴다. */
    private val tapCommit = object : Runnable {
        override fun run() {
            if (!gestures.taps.idle(System.currentTimeMillis())) { ui.postDelayed(this, 120); return }
            if (seekTarget >= 0) player?.seekTo(seekTarget)
            seekTarget = -1L
            gestures.taps.reset()
            ui.postDelayed(tapOff, 300)
        }
    }

    /** 보이는 동안 시간·탐색 막대를 흘려 보낸다(조작이 보일 때만, 화면 속 화면에서는 쉰다). */
    private val ticker = object : Runnable {
        override fun run() {
            updateTime()
            if (controlsShown && !inPip) ui.postDelayed(this, 250)
        }
    }

    /** 화면 속 화면의 재생/멈춤 버튼. */
    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) { togglePlay() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        secureWindow() // 클라우드 영상: 캡처·녹화·화면 공유·최근 앱 미리보기에서 가린다(§18.4)
        val p = intent.getStringExtra(E_IP)?.let { Store.get(this, it) }
        val list = readEntries()
        // 이 앱 안에서만 여는 화면이지만 받은 값은 다시 본다(허브에 보낼 경로·딸린 버전).
        if (p == null || list.isEmpty()) { finish(); return }
        pairing = p
        entries = list
        index = (savedInstanceState?.getInt(S_INDEX) ?: intent.getIntExtra(E_INDEX, 0)).coerceIn(0, list.size - 1)
        book = loadBook()
        savedInstanceState?.let { position = it.getLong(S_POS); playWhenReady = it.getBoolean(S_PLAY, true) }

        setContentView(R.layout.activity_video)
        bindViews()
        wire()
        setupWindow()
        ContextCompat.registerReceiver(this, pipReceiver, IntentFilter(ACTION_PIP), ContextCompat.RECEIVER_NOT_EXPORTED)
        showEntry()
        setControls(true)
    }

    /** 인텐트의 목록: 경로가 규칙에 맞고(빈 경로 아님) 딸린 버전이 PLAY 또는 없음인 것만. */
    private fun readEntries(): List<Entry> {
        val paths = intent.getStringArrayListExtra(E_PATHS) ?: return emptyList()
        val names = intent.getStringArrayListExtra(E_NAMES) ?: return emptyList()
        val vars = intent.getStringArrayListExtra(E_VARIANTS) ?: return emptyList()
        if (paths.size != names.size || paths.size != vars.size) return emptyList()
        return paths.indices.mapNotNull { i ->
            val path = paths[i]
            val v = vars[i].ifEmpty { null }
            if (path.isEmpty() || !CloudPath.isValid(path) || (v != null && v != CloudVariants.PLAY)) null else Entry(path, names[i], v)
        }
    }

    private fun bindViews() {
        playerView = findViewById(R.id.player)
        stage = findViewById(R.id.videoStage)
        gestures = findViewById(R.id.gestures)
        controls = findViewById(R.id.controls)
        top = findViewById(R.id.videoTop)
        bottom = findViewById(R.id.videoBottom)
        centerRow = findViewById(R.id.centerRow)
        nameView = findViewById(R.id.videoName)
        countView = findViewById(R.id.videoCount)
        playBtn = findViewById(R.id.videoPlay)
        prevBtn = findViewById(R.id.videoPrev)
        nextBtn = findViewById(R.id.videoNext)
        pipBtn = findViewById(R.id.videoPip)
        scrub = findViewById(R.id.scrub)
        timeNow = findViewById(R.id.timeNow)
        timeTotal = findViewById(R.id.timeTotal)
        speedBtn = findViewById(R.id.videoSpeed)
        speedScroll = findViewById(R.id.speedScroll)
        speedPanel = findViewById(R.id.speedPanel)
        muteBtn = findViewById(R.id.videoMute)
        fitBtn = findViewById(R.id.videoFit)
        fullBtn = findViewById(R.id.videoFull)
        landscapeHint = findViewById(R.id.landscapeHint)
        seekHint = findViewById(R.id.seekHint)
        levelHint = findViewById(R.id.levelHint)
        levelIcon = findViewById(R.id.levelIcon)
        levelFill = findViewById(R.id.levelFill)
        levelText = findViewById(R.id.levelText)
        tapLeft = findViewById(R.id.tapLeft)
        tapRight = findViewById(R.id.tapRight)
        resumePill = findViewById(R.id.resumePill)
        resumeText = findViewById(R.id.resumeText)
        errorBox = findViewById(R.id.errorBox)
        message = findViewById(R.id.videoMessage)
        levelFill.pivotX = 0f
    }

    private fun wire() {
        gestures.listener = this
        findViewById<View>(R.id.videoClose).setOnClickListener { finish() }
        playBtn.setOnClickListener { togglePlay(); poke() }
        prevBtn.setOnClickListener { go(index - 1) }
        nextBtn.setOnClickListener { go(index + 1) }
        findViewById<View>(R.id.videoRew).setOnClickListener { seekBy(-10_000); poke() }
        findViewById<View>(R.id.videoFwd).setOnClickListener { seekBy(10_000); poke() }
        pipBtn.visibility = if (pipSupported()) View.VISIBLE else View.GONE
        pipBtn.setOnClickListener { enterPip() }
        speedBtn.setOnClickListener { speedScroll.visibility = if (speedScroll.visibility == View.VISIBLE) View.GONE else View.VISIBLE; poke() }
        muteBtn.setOnClickListener { setMuted(!muted); poke() }
        fitBtn.setOnClickListener { setFill(!fill); poke() }
        fullBtn.setOnClickListener { immersive = !immersive; applyImmersive(); poke() }
        findViewById<View>(R.id.videoRotate).setOnClickListener { cycleOrient(); poke() }
        landscapeHint.setOnClickListener { orient = 2; applyOrient(true); poke() }
        landscapeHint.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_rotate_cw, 0, 0, 0)
        androidx.core.widget.TextViewCompat.setCompoundDrawableTintList(landscapeHint, ContextCompat.getColorStateList(this, R.color.video_chip_text))
        findViewById<View>(R.id.resumeRestart).setOnClickListener {
            player?.seekTo(0)
            current?.let { book.remove(pairing.pcIp, it.path); saveBook() }
            resumePill.visibility = View.GONE
        }
        findViewById<View>(R.id.videoRetry).setOnClickListener { retry() }
        scrub.listener = object : ScrubBar.Listener {
            override fun onScrubStart() { scrubbing = true; ui.removeCallbacks(hideRun) }
            override fun onScrub(f: Float) {
                val d = duration()
                if (d > 0) { timeNow.text = VideoMath.time((d * f).toLong()); showHint(VideoMath.time((d * f).toLong()) + " / " + VideoMath.time(d)) }
            }
            override fun onScrubEnd(f: Float) {
                scrubbing = false
                val d = duration()
                if (d > 0) player?.seekTo((d * f).toLong())
                hideHintSoon()
                poke()
            }
        }
        buildSpeeds()
        setFill(false)
        setMuted(false)
    }

    /** 영상은 화면 전체 창에, 칸은 시스템 바·가림을 피해서(몰입 중에도 같은 칸 — 바가 나타나도 영상이 움직이지 않게). */
    private fun setupWindow() {
        val w = window
        WindowCompat.setDecorFitsSystemWindows(w, false)
        if (Build.VERSION.SDK_INT >= 28) w.attributes = w.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        applyImmersive()
        val root = findViewById<View>(R.id.videoRoot)
        val top0 = top.paddingTop
        val bottom0 = bottom.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars())
            val cut = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val l = maxOf(bars.left, cut.left)
            val t = maxOf(bars.top, cut.top)
            val r = maxOf(bars.right, cut.right)
            val b = maxOf(bars.bottom, cut.bottom)
            // 화면 속 화면에서는 여백 없이 창 전체에
            if (inPip) stage.setPadding(0, 0, 0, 0) else stage.setPadding(l, t, r, b)
            top.setPadding(l + (6 * resources.displayMetrics.density).toInt(), top0 + t, r, top.paddingBottom)
            bottom.setPadding(l, bottom.paddingTop, r, bottom0 + b)
            insets
        }
    }

    override fun onStart() {
        super.onStart()
        if (::pairing.isInitialized) startPlayer()
    }

    override fun onStop() {
        if (::pairing.isInitialized) { saveResume(); stopPlayer() }
        super.onStop()
    }

    override fun onDestroy() {
        if (::pairing.isInitialized) try { unregisterReceiver(pipReceiver) } catch (e: Exception) { }
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        player?.let { position = it.currentPosition; playWhenReady = it.playWhenReady }
        outState.putLong(S_POS, position)
        outState.putBoolean(S_PLAY, playWhenReady)
        outState.putInt(S_INDEX, index)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateLandscapeHint()
    }

    // ---------- 재생기 ----------

    private fun startPlayer() {
        if (player != null) return
        val e = current ?: return
        errorBox.visibility = View.GONE
        ended = false
        // 처음 여는 것이면 이어 보기 자리부터(기억한 자리가 있을 때만 알린다).
        if (position == 0L) book.get(pairing.pcIp, e.path)?.let { at ->
            position = at
            resumeText.text = getString(R.string.video_resumed, VideoMath.time(at))
            resumePill.visibility = View.VISIBLE
            ui.removeCallbacks(resumeOff)
            ui.postDelayed(resumeOff, 6000)
        }
        val v = CloudVideo(pairing, e.path, e.variant)
        video = v
        // 주소는 재생기 안에서만 쓰인다(받기는 CloudVideo). 확장자는 추출기를 고르는 순서의 힌트.
        val ext = if (e.variant == CloudVariants.PLAY) "ts" else e.name.substringAfterLast('.', "").lowercase().filter { it.isLetterOrDigit() }
        val uri = Uri.Builder().scheme("tailhop").authority("cloud").appendPath(if (ext.isEmpty()) "video" else "video.$ext").build()
        val source = ProgressiveMediaSource.Factory(v.factory(), DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true))
            .createMediaSource(MediaItem.fromUri(uri))
        val pl = ExoPlayer.Builder(this).build()
        pl.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playerView.keepScreenOn = isPlaying
                updatePlayIcon()
                updatePip()
                if (isPlaying) poke() else { ui.removeCallbacks(hideRun); if (!inPip) setControls(true) }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    // 끝나면 멈춘다(다음 영상으로 넘어가지 않는다). 다 본 것은 이어 보기에서 뺀다.
                    ended = true
                    current?.let { book.remove(pairing.pcIp, it.path); saveBook() }
                    setControls(true)
                }
                if (state == Player.STATE_READY) errorBox.visibility = View.GONE
                updatePlayIcon()
                updateTime()
            }

            override fun onVideoSizeChanged(size: VideoSize) {
                videoW = (size.width * size.pixelWidthHeightRatio).roundToInt()
                videoH = size.height
                updateLandscapeHint()
                updatePip()
            }

            override fun onPlayerError(error: PlaybackException) {
                // 이 앱의 오류 코드(허브의 거부·끊김)가 원인에 있으면 그 문장으로.
                var c: Throwable? = error
                while (c != null && c !is ProtocolException) c = c.cause
                val reason = if (c != null) Errors.text(this@VideoActivity, c) else error.message ?: getString(R.string.unknown_error)
                message.text = getString(R.string.video_failed, reason)
                errorBox.visibility = View.VISIBLE
                position = pl.currentPosition
                setControls(true)
            }
        })
        pl.setMediaSource(source, position)
        pl.playWhenReady = playWhenReady
        pl.setPlaybackSpeed(speed)
        pl.volume = if (muted) 0f else 1f
        pl.prepare()
        playerView.player = pl
        player = pl
        updatePlayIcon()
        ui.post(ticker)
    }

    /** 재생기를 놓는다: 연결을 먼저 끊어(받던 것이 바로 끝나게) 재생기가 오래 기다리지 않게 한다. */
    private fun stopPlayer() {
        val pl = player ?: return
        position = pl.currentPosition
        playWhenReady = pl.playWhenReady
        player = null
        video?.close()
        video = null
        playerView.player = null
        pl.release()
        playerView.keepScreenOn = false
    }

    /** 오류 뒤 다시 시도: 그 자리부터 새 연결로. */
    private fun retry() {
        stopPlayer()
        playWhenReady = true
        startPlayer()
    }

    /** 이전·다음 영상(그 폴더 목록 순서). 지금 자리는 이어 보기에 적어 둔다. */
    private fun go(i: Int) {
        if (i !in entries.indices || i == index) return
        saveResume()
        stopPlayer()
        index = i
        position = 0L
        playWhenReady = true
        videoW = 0
        videoH = 0
        resumePill.visibility = View.GONE
        showEntry()
        startPlayer()
        poke()
    }

    private fun showEntry() {
        val e = current ?: return
        nameView.text = e.name
        countView.text = if (entries.size > 1) getString(R.string.lightbox_counter, index + 1, entries.size) else ""
        countView.visibility = if (entries.size > 1) View.VISIBLE else View.GONE
        prevBtn.isEnabled = index > 0
        prevBtn.alpha = if (index > 0) 1f else 0.35f
        nextBtn.isEnabled = index < entries.size - 1
        nextBtn.alpha = if (index < entries.size - 1) 1f else 0.35f
        prevBtn.visibility = if (entries.size > 1) View.VISIBLE else View.GONE
        nextBtn.visibility = prevBtn.visibility
        updateLandscapeHint()
    }

    private fun duration(): Long = player?.duration?.takeIf { it != C.TIME_UNSET && it > 0 } ?: -1L

    private fun togglePlay() {
        val pl = player ?: return
        when {
            ended || pl.playbackState == Player.STATE_ENDED -> { ended = false; pl.seekTo(0); pl.play() }
            pl.isPlaying -> pl.pause()
            else -> { if (pl.playbackState == Player.STATE_IDLE) pl.prepare(); pl.play() }
        }
        updatePlayIcon()
    }

    private fun updatePlayIcon() {
        val playing = player?.isPlaying == true || (player?.playWhenReady == true && player?.playbackState == Player.STATE_BUFFERING)
        val end = ended
        playBtn.setImageResource(if (playing && !end) R.drawable.ic_pause else if (end) R.drawable.ic_retry else R.drawable.ic_play)
        playBtn.contentDescription = getString(if (playing && !end) R.string.video_pause else R.string.video_play)
        // 재생 표시(삼각형)는 눈으로 가운데에 오게 오른쪽으로 조금
        val d = resources.displayMetrics.density
        playBtn.setPadding(((if (playing || end) 24 else 27) * d).toInt(), (24 * d).toInt(), (24 * d).toInt(), (24 * d).toInt())
    }

    private fun seekBy(ms: Long) {
        val pl = player ?: return
        val d = duration()
        val to = (pl.currentPosition + ms).coerceAtLeast(0L).let { if (d > 0) it.coerceAtMost(d) else it }
        pl.seekTo(to)
        if (ended && ms < 0) ended = false
        updateTime()
    }

    private fun updateTime() {
        val pl = player ?: return
        val d = duration()
        val pos = if (seekTarget >= 0) seekTarget else pl.currentPosition
        if (!scrubbing) {
            timeNow.text = VideoMath.time(pos)
            scrub.played = if (d > 0) pos.toFloat() / d else 0f
        }
        timeTotal.text = if (d > 0) VideoMath.time(d) else "--:--"
        scrub.buffered = if (d > 0) pl.bufferedPosition.toFloat() / d else 0f
        scrub.isEnabled = d > 0
    }

    // ---------- 조작 ----------

    /** 조작 막대 숨기기·보이기(부드럽게). 보일 때 재생 중이면 HIDE_MS 뒤 저절로 숨는다. 몰입이면 시스템 바도 함께. */
    private fun setControls(on: Boolean) {
        if (inPip) return
        controlsShown = on
        controls.animate().cancel()
        if (on) {
            controls.visibility = View.VISIBLE
            controls.animate().alpha(1f).setDuration(150).start()
            ui.removeCallbacks(ticker)
            ui.post(ticker)
            poke()
        } else {
            speedScroll.visibility = View.GONE
            controls.animate().alpha(0f).setDuration(150).withEndAction { if (!controlsShown) controls.visibility = View.INVISIBLE }.start()
        }
        applyImmersive()
    }

    /** 무언가 눌렀다: 숨기기 시계를 다시 맞춘다. */
    private fun poke() {
        ui.removeCallbacks(hideRun)
        if (player?.isPlaying == true) ui.postDelayed(hideRun, HIDE_MS)
    }

    /** 몰입(전체 화면): 시스템 바를 숨긴다(밀면 잠깐 보인다). 끄면 조작이 보일 때 바도 보인다. */
    private fun applyImmersive() {
        val c = WindowCompat.getInsetsController(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (immersive || !controlsShown) c.hide(WindowInsetsCompat.Type.systemBars()) else c.show(WindowInsetsCompat.Type.systemBars())
        fullBtn.setImageResource(if (immersive) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen)
        fullBtn.contentDescription = getString(if (immersive) R.string.video_fullscreen_exit else R.string.video_fullscreen)
    }

    private fun setFill(on: Boolean) {
        fill = on
        playerView.resizeMode = if (on) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
        fitBtn.setImageResource(if (on) R.drawable.ic_fit else R.drawable.ic_fill)
        fitBtn.contentDescription = getString(if (on) R.string.video_fit else R.string.video_fill)
    }

    private fun setMuted(on: Boolean) {
        muted = on
        player?.volume = if (on) 0f else 1f
        muteBtn.setImageResource(if (on) R.drawable.ic_volume_off else R.drawable.ic_volume)
        muteBtn.contentDescription = getString(if (on) R.string.video_unmute else R.string.video_mute)
    }

    /** 속도 칩(0.5–2배, 검은 바탕의 shadcn Toggle). 누르면 그 속도로 바꾸고 판을 닫는다. */
    private fun buildSpeeds() {
        val d = resources.displayMetrics.density
        speedPanel.removeAllViews()
        for (s in VideoMath.SPEEDS) speedPanel.addView(TextView(this).apply {
            text = VideoMath.speed(s)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setTextColor(ContextCompat.getColorStateList(context, R.color.video_chip_text))
            setBackgroundResource(R.drawable.bg_video_chip)
            setPadding((12 * d).toInt(), (6 * d).toInt(), (12 * d).toInt(), (6 * d).toInt())
            isSelected = s == speed
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginEnd = (6 * d).toInt() }
            setOnClickListener {
                speed = s
                player?.setPlaybackSpeed(s)
                buildSpeeds()
                speedScroll.visibility = View.GONE
                poke()
            }
        })
        speedBtn.text = VideoMath.speed(speed)
    }

    /** 화면 방향: 시스템 따름 → 자동 회전(회전 잠금 무시) → 가로 → 세로 → 시스템 따름. 바꿀 때 짧게 알린다. */
    private fun cycleOrient() {
        orient = (orient + 1) % ORIENTS.size
        applyOrient(true)
    }

    private fun applyOrient(say: Boolean) {
        requestedOrientation = ORIENTS[orient]
        if (say) showHint(getString(when (orient) {
            1 -> R.string.video_orient_auto
            2 -> R.string.video_orient_landscape
            3 -> R.string.video_orient_portrait
            else -> R.string.video_orient_system
        }))
        hideHintSoon(1200)
        updateLandscapeHint()
    }

    /** 가로 영상을 세로 화면에서(방향을 따로 정하지 않았을 때) 보면 "가로로 보기"를 권한다. */
    private fun updateLandscapeHint() {
        val wide = videoW > 0 && videoH > 0 && videoW > videoH * 1.1f
        val portrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        landscapeHint.visibility = if (wide && portrait && orient == 0 && !inPip) View.VISIBLE else View.GONE
    }

    private fun showHint(text: String) {
        ui.removeCallbacks(hintOff)
        levelHint.visibility = View.GONE
        seekHint.text = text
        seekHint.visibility = View.VISIBLE
    }

    private fun hideHintSoon(ms: Long = 500) {
        ui.removeCallbacks(hintOff)
        ui.postDelayed(hintOff, ms)
    }

    // ---------- 손짓(VideoGestures) ----------

    override fun onSingleTap() {
        if (speedScroll.visibility == View.VISIBLE) { speedScroll.visibility = View.GONE; return }
        setControls(!controlsShown)
    }

    /** 두 번 탭 탐색: 모은 거리를 그쪽에 보이고, 탭이 멈추면 옮긴다(연결을 여러 번 새로 열지 않게). */
    override fun onTapSeek(totalMs: Long, side: Int) {
        val pl = player ?: return
        if (seekTarget < 0) seekBase = pl.currentPosition
        val d = duration()
        seekTarget = (seekBase + totalMs).coerceAtLeast(0L).let { if (d > 0) it.coerceAtMost(d) else it }
        ui.removeCallbacks(tapOff)
        val label = getString(if (totalMs < 0) R.string.video_back_s else R.string.video_fwd_s, kotlin.math.abs(totalMs) / 1000)
        if (side < 0) { tapLeft.text = label; tapLeft.visibility = View.VISIBLE; tapRight.visibility = View.GONE }
        else { tapRight.text = label; tapRight.visibility = View.VISIBLE; tapLeft.visibility = View.GONE }
        updateTime()
        ui.removeCallbacks(tapCommit)
        ui.postDelayed(tapCommit, 350)
    }

    override fun onSeekDragStart() {
        val pl = player ?: return
        if (duration() <= 0) return
        seekBase = pl.currentPosition
        seekTarget = seekBase
        scrubbing = true
        ui.removeCallbacks(hideRun)
    }

    override fun onSeekDrag(dx: Float, width: Int) {
        if (!scrubbing) return
        val d = duration()
        seekTarget = VideoMath.dragSeek(seekBase, dx, width, d)
        showHint(VideoMath.time(seekTarget) + " / " + VideoMath.time(d) + "\n" + VideoMath.delta(seekTarget - seekBase))
        timeNow.text = VideoMath.time(seekTarget)
        scrub.played = if (d > 0) seekTarget.toFloat() / d else 0f
    }

    override fun onSeekDragEnd(commit: Boolean) {
        if (!scrubbing) return
        scrubbing = false
        if (commit && seekTarget >= 0) player?.seekTo(seekTarget)
        seekTarget = -1L
        hideHintSoon()
        poke()
    }

    override fun onLevelStart(volume: Boolean) {
        levelBase = if (volume) {
            val max = audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 0
            if (max > 0) (audio?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0).toFloat() / max else 0f
        } else brightness()
        showLevel(volume, levelBase)
    }

    override fun onLevelDrag(volume: Boolean, dy: Float, height: Int) {
        val v = VideoMath.dragLevel(levelBase, dy, height)
        if (volume) {
            val max = audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 0
            if (max > 0) {
                try { audio?.setStreamVolume(AudioManager.STREAM_MUSIC, (v * max).roundToInt(), 0) } catch (e: SecurityException) { } // 방해 금지 등
                if (muted && v > 0f) setMuted(false)
            }
        } else window.attributes = window.attributes.apply { screenBrightness = v.coerceAtLeast(0.01f) }
        showLevel(volume, v)
    }

    override fun onLevelEnd(volume: Boolean) { hideHintSoon(700) }

    private fun showLevel(volume: Boolean, v: Float) {
        ui.removeCallbacks(hintOff)
        seekHint.visibility = View.GONE
        levelIcon.setImageResource(if (volume) (if (v <= 0f) R.drawable.ic_volume_off else R.drawable.ic_volume) else R.drawable.ic_sun)
        levelFill.scaleX = v.coerceIn(0f, 1f)
        levelText.text = getString(R.string.video_percent, (v * 100).roundToInt())
        levelHint.contentDescription = getString(if (volume) R.string.video_volume else R.string.video_brightness)
        levelHint.visibility = View.VISIBLE
    }

    /** 지금 창 밝기(0–1). 정하지 않았으면 시스템 밝기(0–255로 보고)에서. */
    private fun brightness(): Float {
        val b = window.attributes.screenBrightness
        if (b >= 0f) return b
        val sys = try { Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f } catch (e: Exception) { 0.5f }
        return sys.coerceIn(0f, 1f)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { togglePlay(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { if (player?.isPlaying != true) togglePlay(); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { if (player?.isPlaying == true) togglePlay(); return true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seekBy(10_000); return true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { seekBy(-10_000); return true }
            KeyEvent.KEYCODE_MEDIA_NEXT -> { go(index + 1); return true }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { go(index - 1); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---------- 이어 보기 ----------

    /** 이 앱만 아는 열쇠(처음 한 번 만든 난수)로 기록을 연다. 경로·이름은 평문으로 두지 않는다. */
    private fun loadBook(): ResumeBook {
        val sp = getSharedPreferences(PREFS, MODE_PRIVATE)
        val secret = sp.getString(K_SECRET, null)?.let { try { Base64.decode(it, Base64.NO_WRAP) } catch (e: Exception) { null } }
            ?.takeIf { it.size == 32 }
            ?: Protocol.randomBytes(32).also { sp.edit().putString(K_SECRET, Base64.encodeToString(it, Base64.NO_WRAP)).remove(K_BOOK).apply() }
        return ResumeBook.parse(sp.getString(K_BOOK, null), secret)
    }

    private fun saveBook() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(K_BOOK, book.encode()).apply()
    }

    /** 지금 자리를 적는다(끝 가까이·처음 몇 초면 ResumeBook이 지운다). */
    private fun saveResume() {
        val pl = player ?: return
        val e = current ?: return
        if (ended) return
        book.put(pairing.pcIp, e.path, pl.currentPosition, duration(), System.currentTimeMillis())
        saveBook()
    }

    // ---------- 화면 속 화면 ----------

    private fun pipSupported(): Boolean = packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    /** 화면 속 화면 설정: 영상 비율, 재생/멈춤 버튼, 들어갈 때 커지는 자리(영상 칸). Android 12+는 재생 중이면 홈에서 저절로 들어간다. */
    private fun pipParams(): PictureInPictureParams {
        val (w, h) = VideoMath.pipRatio(videoW, videoH)
        val playing = player?.isPlaying == true
        val icon = Icon.createWithResource(this, if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        val label = getString(if (playing) R.string.video_pause else R.string.video_play)
        val pi = PendingIntent.getBroadcast(this, 1, Intent(ACTION_PIP).setPackage(packageName), PendingIntent.FLAG_IMMUTABLE)
        val b = PictureInPictureParams.Builder().setAspectRatio(Rational(w, h)).setActions(listOf(RemoteAction(icon, label, label, pi)))
        val hint = Rect()
        if (playerView.getGlobalVisibleRect(hint) && !hint.isEmpty) b.setSourceRectHint(hint)
        if (Build.VERSION.SDK_INT >= 31) b.setAutoEnterEnabled(playing).setSeamlessResizeEnabled(true)
        return b.build()
    }

    /** 재생 상태·영상 비율이 바뀌면 화면 속 화면 설정도(Android 12+는 홈을 누르면 저절로 들어간다). */
    private fun updatePip() {
        if (!pipSupported()) return
        try { setPictureInPictureParams(pipParams()) } catch (e: Exception) { }
    }

    private fun enterPip() {
        if (!pipSupported()) return
        try { enterPictureInPictureMode(pipParams()) } catch (e: Exception) { }
    }

    /** 홈을 누름: 재생 중이면 화면 속 화면으로(Android 8–11. 12+는 setAutoEnterEnabled가 한다). */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT <= 30 && player?.isPlaying == true) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        if (inPip) {
            // 작은 창에는 영상만
            controls.visibility = View.INVISIBLE
            for (v in listOf(seekHint, levelHint, tapLeft, tapRight, resumePill, landscapeHint)) v.visibility = View.GONE
            stage.setPadding(0, 0, 0, 0)
            ui.removeCallbacks(ticker)
        } else {
            // 닫기(X)로 끝냈으면 화면은 이미 멈췄다: 이 화면도 끝낸다. 크게 되돌렸으면 조작을 다시 보인다.
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) { finish(); return }
            ViewCompat.requestApplyInsets(findViewById(R.id.videoRoot))
            setControls(true)
            updateLandscapeHint()
        }
    }
}
