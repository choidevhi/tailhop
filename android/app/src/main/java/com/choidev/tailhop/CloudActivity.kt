package com.choidev.tailhop

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.text.format.DateUtils
import android.text.format.Formatter
import android.util.TypedValue
import android.view.DragEvent
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * PC 클라우드(1.8.0, docs/PROTOCOL.md §12). 처음엔 PC를 고르고(hello가 cloud:true인 PC만 열 수 있다, 나머지는 "클라우드 꺼짐" 등),
 * 고른 PC의 폴더를 본다: 경로(빵 부스러기)·뒤로 가기로 위 폴더, 끝까지 내리면 다음 쪽(next cursor)을 더 불러온다.
 * 받기·올리기는 보낼 곳 목록(Outbox)에 넣어 SendService가 이어받기·재시도와 함께 하고, 이 화면 위쪽 "전송"에 진행이 보인다.
 * 새 폴더·지우기(확인 창)는 바로 요청한다. EXTRA_IP로 열면 그 PC의 맨 위 폴더부터 보인다.
 *
 * 2.0.0: 허브가 hello caps에 "cloud2"를 알리면 격자/목록 바꾸기와 미리보기(cloud_thumb), 찾기·종류 고르기(cloud_search, §15),
 * 잠긴 폴더(§14: PIN으로 열기·잠그기·잠금 풀기·PIN 바꾸기)를 쓴다. 열쇠(token)는 이 프로세스의 메모리에만 있다(VaultTokens).
 *
 * 2.1.0: 사진·영상을 누르면 라이트박스(확대·좌우로 넘기기·받기·지우기). 길게 누르면 다중 선택(끌면 범위, Google 포토처럼),
 * 아래 동작 막대로 옮기기(§16 cloud_move, 허브가 "move"를 알릴 때만)·받기·지우기·더보기(잠그기 등 예전 길게 누르기 메뉴).
 *
 * 2.2.1(§18): 이 창과 대화 상자는 캡처 막기(FLAG_SECURE). 허브가 "move2"를 알리면 잠긴 폴더 안팎·사이로도 옮긴다(옮기기 창에서 잠긴
 * 폴더도 PIN으로 열고 들어간다). 고른 항목을 길게 눌러 끌면 고른 것 전부를 폴더 줄·칸이나 위 경로 조각에 놓아 옮긴다(MoveDrag).
 * "rename"이면 이름 바꾸기(하나는 이름 칸, 여럿은 방식 고르기와 미리보기 — RenameDialog). 강한 잠금(§18.3)으로 잠긴 PC는
 * 클라우드 비밀번호로 열고(저장하지 않는다), PC 줄을 길게 누르면 "지금 잠그기". 돌려도·폴드를 펴도 화면을 다시 만들지 않는다.
 *
 * 2.3.0(§19): 허브가 "sync"를 알리면 고른 것을 "모든 기기에 동기화"·"동기화 빼기"(sync_set), 더보기 메뉴의 "전체 동기화"(sync_all)와
 * "지금 동기화". 대상 항목에는 작은 동기화 표시. 이 기기의 동기화 사본(Mirror)에 같은 경로·크기·수정 시각의 파일이 있으면 사진·영상을
 * 사본에서 연다(메모리로만 푼다). 허브에 닿지 않으면 사본을 읽기 전용으로 보인다("오프라인 사본"). 위 막대에 허브의 용량(§19.1).
 */
class CloudActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_IP = "ip"
        private const val S_IP = "ip"
        private const val S_PATH = "path"
        private const val S_PICKER = "picker"
        private const val K_GRID = "cloudGrid"
        /** 잠그거나 푸는 중인 폴더가 보일 때만, 화면이 보이는 동안만 진행률을 다시 읽는 간격. */
        private const val BUSY_REFRESH_MS = 4000L
        /** 격자 칸 종류(SpanSizeLookup이 1칸으로 센다). */
        private const val T_GRID = 3
        /** 선택 표시만 다시 그리기(미리보기를 다시 붙이지 않게). */
        private const val SEL = "sel"
        /** 이보다 큰 사진은 원본 대신 허브의 큰 미리보기(cloud_thumb 512px)로 본다(메모리로 받으므로). */
        private const val FULL_MAX = 64L * 1024 * 1024
        /** 화면용 버전이 없는 사진을 앞뒤로 미리 받을 때의 원본 크기 한도(가벼운 것만). */
        private const val PREFETCH_MAX = 8L * 1024 * 1024
        /** 정보 창에서 원본의 가로세로·찍은 날짜를 읽으려고 받는 머리(§17.3 범위, 메모리만). */
        private const val HEAD_LEN = 256L * 1024
        /** 끌어다 놓기 중 폴더 강조 다시 그리기. */
        private const val DROP = "drop"
        /** 재생기에 넘기는 영상 목록의 한도(인텐트 크기). */
        private const val VIDEO_LIST_MAX = 400
        /** 이름 바꾸기 미리보기에 그리는 줄 수(나머지는 "외 n개"). */
        private const val PREVIEW_MAX = 100
        /** 동기화 대상을 바꾼 뒤 이 폰이 한 번 더 맞춰 볼 때까지(허브는 30초 모아 알린다, §19.2). */
        private const val SYNC_FOLLOW_MS = 40_000L
    }

    /** 끌어다 놓기(MoveDrag)의 표식: 이 앱 안에서 시작한 옮기기 끌기인지 알아본다. */
    private object MoveDragState

    /** 라이트박스의 한 장: 항목과 그 클라우드 경로(찾기 결과도 전체 경로). */
    private data class Pic(val item: CloudItem, val target: String)

    /** STORE_LOCKED·STORE_MISSING: 2.2 허브의 클라우드는 켜졌지만 저장소가 열리지 않음(§17.2, 그 PC에서 열어야 한다). */
    private enum class PcState { CHECKING, ON, OFF, OLD, UNREACHABLE, STORE_LOCKED, STORE_MISSING }

    /** 목록의 한 줄(격자 모드에서는 Item만 칸, 나머지는 한 줄 전체). */
    private sealed class Row {
        data class Header(val text: String) : Row()
        data class Pc(val p: Pairing, val state: PcState) : Row()
        data class Job(val j: OutboxJob) : Row()
        data class Item(val it: CloudItem) : Row()
        data class Note(val title: String, val sub: String?, val retry: Boolean) : Row()
        object More : Row()
    }

    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var crumbsRow: View
    private lateinit var crumbsScroll: HorizontalScrollView
    private lateinit var crumbs: LinearLayout
    private lateinit var list: RecyclerView
    private lateinit var listCard: View
    private lateinit var empty: View
    private lateinit var emptyIcon: ImageView
    private lateinit var emptyTitle: TextView
    private lateinit var emptySub: TextView
    private lateinit var emptyBtn: View
    private lateinit var newFolderBtn: View
    private lateinit var uploadBtn: View
    private lateinit var viewBtn: ImageView
    private lateinit var searchBar: View
    private lateinit var searchBox: EditText
    private lateinit var searchClear: View
    private lateinit var kindScroll: View
    private lateinit var kindChips: LinearLayout
    private lateinit var layout: GridLayoutManager
    private lateinit var topBar: View
    private lateinit var selBar: View
    private lateinit var selCount: TextView
    private lateinit var actionBar: View
    private lateinit var actMove: View
    private lateinit var actRename: View
    private lateinit var actMore: View
    private lateinit var actSync: View
    private lateinit var moreBtn: View
    private lateinit var offlineBanner: View
    private val adapter = Rows()
    private val drag = DragSelect()
    private val moveDrag = MoveDrag()
    private val ui = Handler(Looper.getMainLooper())
    private val io: ExecutorService = Executors.newFixedThreadPool(3)
    private val vault = VaultTokens.shared

    /** 지금 보는 PC(null = PC 고르기). */
    private var pc: Pairing? = null
    /** PC 고르기에서 들어왔는지(뒤로 가기가 고르기로 돌아간다). */
    private var fromPicker = true
    private var path = ""
    private val pcStates = HashMap<String, PcState>()
    /** PC별 hello caps("cloud2"가 있으면 미리보기·찾기·잠긴 폴더). */
    private val pcCaps = HashMap<String, Set<String>>()
    /** PC별 hello의 저장소 상태(§17.2 "store": new·locked·missing·open, 없으면 없음) — 강한 잠금(§18.3) 열기·잠그기에. */
    private val pcStore = HashMap<String, String>()
    /** hello "strong"(§18.3): 그 허브가 강한 잠금인가("지금 잠그기"는 이때만). */
    private val pcStrong = HashSet<String>()
    private var items: List<CloudItem> = emptyList()
    private var next: Int? = null
    private var loading = false
    private var loadError: String? = null
    /** 찾기 결과가 허브의 한도에 걸려 일부만인지(§15 partial). */
    private var partial = false
    /** 목록 요청 세대: 폴더를 옮기거나 새로 고치면 늘려서 늦게 온 예전 응답을 버린다. */
    private var gen = 0
    /** 이 폴더로 올리는 중인 건(끝나서 목록에서 빠지면 폴더를 새로 읽는다). */
    private var uploadsHere: Set<String> = emptySet()
    private var grid = false
    private var query = ""
    private var kind = FileKinds.ALL
    /** 코드가 찾기 칸을 비울 때 다시 찾지 않게. */
    private var quiet = false
    private var resumed = false
    /** 다중 선택(2.1.0): 고른 항목의 클라우드 경로(targetOf). 비면 선택 모드가 아니다. */
    private val selected = LinkedHashSet<String>()
    private var lightbox: Lightbox<Pic>? = null
    /** 오프라인 사본(§19.3)을 보는 중: 허브에 닿지 않아 이 기기의 사본만 읽기 전용으로. */
    private var offline = false
    /** hello "sync_all"(§19.2): 그 허브의 "전체 동기화"가 켜져 있는지. */
    private val pcSyncAll = HashSet<String>()
    /** 지금 PC의 동기화 사본에서 다 받은 파일(경로 → 항목). 작업 스레드에서 바꾸고 어디서나 읽는다(통째로 갈아 끼운다). */
    @Volatile private var mirror: Map<String, MirrorEntry> = emptyMap()

    private val searching: Boolean get() = query.isNotEmpty() || kind != FileKinds.ALL
    private val cloud2: Boolean get() = pc?.let { "cloud2" in (pcCaps[it.pcIp] ?: emptySet()) } == true
    private val canMove: Boolean get() = pc?.let { "move" in (pcCaps[it.pcIp] ?: emptySet()) } == true
    /** 2.2.1 허브(§18.1): 잠긴 폴더 안팎·사이로도 옮긴다. */
    private val move2: Boolean get() = pc?.let { "move2" in (pcCaps[it.pcIp] ?: emptySet()) } == true
    /** 2.2.1 허브(§18.2): 이름 바꾸기. */
    private val canRename: Boolean get() = pc?.let { "rename" in (pcCaps[it.pcIp] ?: emptySet()) } == true
    /** 2.2 허브(§17): cloud_get 범위(length)·딸린 버전(variant)을 안다 — 앱 안 영상 재생. */
    private val ranges: Boolean get() = pc?.let { "store" in (pcCaps[it.pcIp] ?: emptySet()) } == true
    private val selecting: Boolean get() = selected.isNotEmpty()
    /** 2.3.0 허브(§19.2): 동기화 대상 바꾸기(오프라인 사본에서는 하지 않는다). */
    private val canSync: Boolean get() = !offline && pc?.let { "sync" in (pcCaps[it.pcIp] ?: emptySet()) } == true
    /** 격자·찾기·미리보기 화면을 쓸지(허브가 cloud2이거나, 사본에는 미리보기가 들어 있다). */
    private val richUi: Boolean get() = cloud2 || offline

    /** 맞추기가 끝나면(상태가 바뀜) 사본 목록을 다시 읽는다. 오프라인 사본을 보는 중이면 화면도. */
    private val mirrorListener: () -> Unit = {
        ui.post {
            val p = pc
            if (!isDestroyed && p != null && MirrorStatus.running == null) {
                refreshMirror(p)
                if (offline) load(reset = true, keep = true)
            }
        }
    }

    private val outboxListener: () -> Unit = { ui.post { onJobsChanged() } }

    private val pick = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { u -> try { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) { } }
        if (uris.isNotEmpty()) upload(uris)
    }

    private val ticker = object : Runnable {
        override fun run() {
            // "n초 뒤 다시 시도"·진행률이 흘러가게 전송 줄만 다시 그린다.
            adapter.rows.forEachIndexed { i, r -> if (r is Row.Job) adapter.notifyItemChanged(i) }
            ui.postDelayed(this, 1000)
        }
    }

    /** 찾기 칸: 마지막 글자 뒤 300 ms 동안 더 치지 않으면 찾는다. */
    private val searchRun = Runnable {
        val q = searchBox.text.toString().trim()
        if (q != query) { query = q; load(reset = true, keep = false) }
    }

    /** 잠그거나 푸는 중인 폴더의 진행률(화면이 보이고 그런 폴더가 있을 때만 한 번씩). */
    private val busyRefresh = Runnable { if (resumed && pc != null) load(reset = true, keep = true) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        secureWindow() // 클라우드 안을 보여 주는 창: 캡처·녹화·최근 앱 미리보기에서 가린다(§18.4)
        setContentView(R.layout.activity_cloud)
        applySystemBarInsets()
        title = findViewById(R.id.title)
        subtitle = findViewById(R.id.subtitle)
        crumbsRow = findViewById(R.id.crumbsRow)
        crumbsScroll = findViewById(R.id.crumbsScroll)
        crumbs = findViewById(R.id.crumbs)
        list = findViewById(R.id.list)
        listCard = findViewById(R.id.listCard)
        empty = findViewById(R.id.empty)
        emptyIcon = findViewById(R.id.emptyIcon)
        emptyTitle = findViewById(R.id.emptyTitle)
        emptySub = findViewById(R.id.emptySub)
        emptyBtn = findViewById(R.id.emptyBtn)
        newFolderBtn = findViewById(R.id.newFolderBtn)
        uploadBtn = findViewById(R.id.uploadBtn)
        viewBtn = findViewById(R.id.viewBtn)
        searchBar = findViewById(R.id.searchBar)
        searchBox = findViewById(R.id.searchBox)
        searchClear = findViewById(R.id.searchClear)
        kindScroll = findViewById(R.id.kindScroll)
        kindChips = findViewById(R.id.kindChips)
        topBar = findViewById(R.id.topBar)
        selBar = findViewById(R.id.selBar)
        selCount = findViewById(R.id.selCount)
        actionBar = findViewById(R.id.actionBar)
        actMove = findViewById(R.id.actMove)
        actRename = findViewById(R.id.actRename)
        actMore = findViewById(R.id.actMore)
        actSync = findViewById(R.id.actSync)
        moreBtn = findViewById(R.id.moreBtn)
        offlineBanner = findViewById(R.id.offlineBanner)
        LocalSpace.init(this)

        grid = getSharedPreferences("settings", MODE_PRIVATE).getBoolean(K_GRID, false)
        layout = GridLayoutManager(this, 1)
        layout.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int =
                if (adapter.getItemViewType(position) == T_GRID) 1 else layout.spanCount
        }
        list.layoutManager = layout
        list.adapter = adapter
        list.itemAnimator = null
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) { maybeLoadMore() }
        })
        list.addOnItemTouchListener(drag)
        list.setOnDragListener(moveDrag)

        findViewById<View>(R.id.backBtn).setOnClickListener { goBack() }
        findViewById<View>(R.id.refreshBtn).setOnClickListener {
            val p = pc
            when {
                p == null -> checkPcs()
                offline -> browse(p) // 허브에 다시 닿아 본다(닿지 않으면 사본으로 돌아온다)
                else -> { clearSelection(); load(reset = true, keep = true) }
            }
        }
        newFolderBtn.setOnClickListener { val p = pc; askNewFolder(path) { if (pc === p) load(reset = true, keep = true) } }
        findViewById<View>(R.id.selClose).setOnClickListener { clearSelection() }
        findViewById<View>(R.id.selAll).setOnClickListener { selectAll() }
        actMove.setOnClickListener { moveSelected() }
        actRename.setOnClickListener { renameSelected() }
        findViewById<View>(R.id.actDownload).setOnClickListener { downloadSelected() }
        findViewById<View>(R.id.actDelete).setOnClickListener { deleteSelected() }
        actMore.setOnClickListener { moreSelected() }
        actSync.setOnClickListener { syncSelected() }
        moreBtn.setOnClickListener { hubMenu(it) }
        uploadBtn.setOnClickListener { pick.launch(arrayOf("*/*")) }
        emptyBtn.setOnClickListener { load(reset = true, keep = false) }
        viewBtn.setOnClickListener { setGrid(!grid) }
        searchClear.setOnClickListener { searchBox.setText("") }
        searchBox.setOnEditorActionListener { v, _, _ ->
            ui.removeCallbacks(searchRun)
            searchRun.run()
            getSystemService(android.view.inputmethod.InputMethodManager::class.java)?.hideSoftInputFromWindow(v.windowToken, 0)
            true
        }
        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchClear.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                if (quiet) return
                ui.removeCallbacks(searchRun)
                ui.postDelayed(searchRun, 300)
            }
        })
        buildChips()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { goBack() }
        })
        Outbox.addListener(outboxListener)
        MirrorStatus.addListener(mirrorListener)

        val ip = savedInstanceState?.getString(S_IP) ?: intent.getStringExtra(EXTRA_IP)
        // 클라우드 화면을 엶: 동기화 사본을 맞춘다(허브마다 10분에 한 번까지, §19.3).
        MirrorSync.request(this, ip?.takeIf { Store.get(this, it) != null })
        fromPicker = savedInstanceState?.getBoolean(S_PICKER) ?: (intent.getStringExtra(EXTRA_IP) == null)
        val p = ip?.let { Store.get(this, it) }
        if (p != null) {
            pc = p
            path = savedInstanceState?.getString(S_PATH)?.takeIf { CloudPath.isValid(it) } ?: ""
            ensureCaps(p)
            refreshMirror(p)
            load(reset = true, keep = false)
        } else {
            if (ip != null) fromPicker = true // 그 사이 연결 해제된 PC
            checkPcs()
        }
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pc?.let { outState.putString(S_IP, it.pcIp); outState.putString(S_PATH, path) }
        outState.putBoolean(S_PICKER, fromPicker)
    }

    /**
     * 돌림·폴드 펴기·접기(2.2.1): 화면을 다시 만들지 않는다(매니페스트 configChanges) — 라이트박스·끌기·고른 것이 그대로 남는다.
     * 격자 칸 수만 새 폭에 맞춘다.
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val before = layout.spanCount
        applyLayout()
        if (layout.spanCount != before) adapter.notifyDataSetChanged()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        val p = pc
        if (p != null && Store.get(this, p.pcIp) == null) { finish(); return }
        SendService.resumeIfNeeded(this)
        ui.post(ticker)
        // 가려진 동안 멈춘 미리보기를 다시 받는다(메모리에 있으면 바로 보인다).
        if (p != null) adapter.notifyDataSetChanged()
        scheduleBusyRefresh()
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        ui.removeCallbacks(ticker)
        ui.removeCallbacks(busyRefresh)
        Thumbs.cancelAll() // 보이지 않는 동안 아무 일도 하지 않게
    }

    override fun onDestroy() {
        lightbox?.close()
        drag.stop()
        moveDrag.stop()
        Outbox.removeListener(outboxListener)
        MirrorStatus.removeListener(mirrorListener)
        ui.removeCallbacks(searchRun)
        io.shutdownNow()
        super.onDestroy()
    }

    // ---------- 이동 ----------

    private fun goBack() {
        when {
            selecting -> clearSelection()
            pc != null && searching -> clearSearch(reload = true)
            pc != null && path.isNotEmpty() -> open(CloudPath.parent(path))
            pc != null && fromPicker -> {
                pc = null; offline = false; mirror = emptyMap(); gen++; items = emptyList(); next = null; loadError = null
                clearSelection(); checkPcs(); render()
            }
            else -> finish()
        }
    }

    private fun browse(p: Pairing) {
        pc = p
        offline = false
        path = ""
        clearSearch(reload = false)
        ensureCaps(p)
        refreshMirror(p)
        load(reset = true, keep = false)
    }

    /** 허브에 닿지 않을 때 이 기기의 동기화 사본을 읽기 전용으로 본다(§19.3). */
    private fun browseOffline(p: Pairing) {
        pc = p
        offline = true
        path = ""
        clearSearch(reload = false)
        clearSelection()
        refreshMirror(p)
        load(reset = true, keep = false)
    }

    /** 지금 PC 사본의 다 받은 파일을 다시 읽는다(작업 스레드에서 연다 — Keystore·디스크). */
    private fun refreshMirror(p: Pairing) {
        val app = applicationContext
        io.execute {
            val map = try {
                Mirror.peek(app, p.pcIp)?.entries().orEmpty().asSequence().filter { !it.dir && it.complete }.associateBy { it.path }
            } catch (e: Exception) { null }
            ui.post {
                if (isDestroyed || pc !== p) return@post
                val now = map.orEmpty()
                if (now == mirror) return@post
                mirror = now
                adapter.notifyDataSetChanged() // 사본에서 열 수 있게 된 미리보기를 다시 붙인다
            }
        }
    }

    /** 이 항목을 사본에서 열 수 있으면 그 항목(오프라인이면 경로만, 아니면 같은 크기·수정 시각일 때만, §19.3). */
    private fun localOf(target: String, item: CloudItem): MirrorEntry? {
        val e = mirror[target] ?: return null
        return if (offline || MirrorTree.same(e, item)) e else null
    }

    /** 오프라인 사본으로 볼 수 있는 PC인지(사본 목록 파일이 있는지만 본다). */
    private fun hasCopy(p: Pairing): Boolean = MirrorSettings.isOn(this) && Mirror.exists(this, p.pcIp)

    /** 허브가 알린 용량 "여유 X / 전체 Y"(§19.1). 받은 적이 없으면 null. */
    private fun spaceText(p: Pairing): String? = PeerSpaces.shared.get(p.pcIp)?.space?.let {
        getString(R.string.space_free_total, Formatter.formatShortFileSize(this, it.free), Formatter.formatShortFileSize(this, it.total))
    }

    /** 그 폴더로 간다(규칙을 넘는 깊이·길이는 열지 않는다). 찾기는 비운다. */
    private fun open(dir: String) {
        if (!CloudPath.isValid(dir)) { toast(getString(R.string.err_bad_path)); return }
        path = dir
        clearSearch(reload = false)
        load(reset = true, keep = false)
    }

    private fun clearSearch(reload: Boolean) {
        ui.removeCallbacks(searchRun)
        quiet = true
        searchBox.setText("")
        quiet = false
        val was = searching
        query = ""
        kind = FileKinds.ALL
        updateChips()
        if (reload && was) load(reset = true, keep = false)
    }

    // ---------- PC 고르기 ----------

    /** 페어링된 PC마다 hello로 클라우드가 켜졌는지 묻는다(cloud:true만 열 수 있다). caps도 기억한다. */
    private fun checkPcs() {
        for (p in Store.all(this)) {
            pcStates[p.pcIp] = PcState.CHECKING
            io.execute {
                var failed = false
                val h = try { Net.hello(p, null, null) } catch (e: Exception) { failed = true; null }
                val st = if (failed) PcState.UNREACHABLE else stateOf(h)
                ui.post {
                    if (isDestroyed) return@post
                    applyHello(p, h, st)
                    if (pc == null) render()
                }
            }
        }
        render()
    }

    /** hello 결과 → PC 고르기의 상태. */
    private fun stateOf(h: Xfer.Hello?): PcState = when (HubStore.state(h)) {
        HubCloud.OLD -> PcState.OLD
        HubCloud.ON -> PcState.ON
        HubCloud.OFF -> PcState.OFF
        HubCloud.STORE_LOCKED -> PcState.STORE_LOCKED
        HubCloud.STORE_MISSING -> PcState.STORE_MISSING
    }

    /** hello로 안 것을 기억한다(caps·저장소 상태). */
    private fun applyHello(p: Pairing, h: Xfer.Hello?, st: PcState) {
        pcStates[p.pcIp] = st
        if (h != null) {
            pcCaps[p.pcIp] = h.caps
            h.store?.let { pcStore[p.pcIp] = it } ?: pcStore.remove(p.pcIp)
            if (h.strong) pcStrong += p.pcIp else pcStrong -= p.pcIp
            if (h.syncAll) pcSyncAll += p.pcIp else pcSyncAll -= p.pcIp
        }
    }

    /** 강한 잠금(§18.3)으로 잠긴 허브: 폰에서 클라우드 비밀번호로 열 수 있다(그렇지 않은 잠김은 그 PC에서만). */
    private fun unlockable(ip: String): Boolean =
        "store_unlock" in (pcCaps[ip] ?: emptySet()) && pcStore[ip] == HubStore.LOCKED

    /** "지금 잠그기"를 보일지: 강한 잠금인 허브(hello "strong")의 저장소가 열려 있을 때. */
    private fun lockable(ip: String): Boolean =
        "store_unlock" in (pcCaps[ip] ?: emptySet()) && ip in pcStrong && pcStore[ip] == HubStore.OPEN

    /** 바로 들어온 PC(EXTRA_IP)는 hello를 따로 해 "cloud2"를 안다. 모르면 예전 목록만. */
    private fun ensureCaps(p: Pairing) {
        if (pcCaps.containsKey(p.pcIp)) return
        io.execute {
            val h = try { Net.hello(p, null, null) } catch (e: Exception) { null } ?: return@execute
            ui.post {
                if (isDestroyed) return@post
                pcCaps[p.pcIp] = h.caps
                h.store?.let { pcStore[p.pcIp] = it }
                if (h.strong) pcStrong += p.pcIp else pcStrong -= p.pcIp
                if (h.syncAll) pcSyncAll += p.pcIp else pcSyncAll -= p.pcIp
                if (pc === p) { applyLayout(); render(); updateSelectionUi() }
            }
        }
    }

    private fun pcClicked(p: Pairing, st: PcState) {
        // 허브에 닿지 않거나 저장소가 열리지 않았는데 이 기기에 사본이 있으면: 사본을 읽기 전용으로(§19.3)
        if (noHub(p, st) && hasCopy(p)) { browseOffline(p); return }
        when (st) {
            PcState.ON -> browse(p)
            PcState.OFF -> toast(getString(R.string.cloud_pc_off_hint))
            PcState.OLD -> toast(getString(R.string.err_cloud_unsupported))
            // 강한 잠금(§18.3)으로 잠겼으면 클라우드 비밀번호로 연다. 그 밖의 잠김(새 PC·만들기 전)은 허브 PC에서만(§17.2).
            PcState.STORE_LOCKED -> if (unlockable(p.pcIp)) askStoreUnlock(p) { browse(p) } else toast(getString(R.string.cloud_store_locked_hint))
            PcState.STORE_MISSING -> toast(getString(R.string.cloud_store_missing_hint))
            PcState.UNREACHABLE -> { toast(getString(R.string.err_connect_failed)); checkPcs() }
            PcState.CHECKING -> { }
        }
    }

    /** 허브의 클라우드를 지금 열 수 없는 상태(닿지 않음·저장소 없음·폰에서 열 수 없는 잠김). */
    private fun noHub(p: Pairing, st: PcState): Boolean =
        st == PcState.UNREACHABLE || st == PcState.STORE_MISSING || (st == PcState.STORE_LOCKED && !unlockable(p.pcIp))

    /** PC 줄을 길게 누름: 열기, 강한 잠금이면 "지금 잠그기"(§18.3 store_lock). */
    private fun pcMenu(p: Pairing, st: PcState) {
        val acts = ArrayList<Pair<Int, () -> Unit>>()
        acts += R.string.cloud_open to { pcClicked(p, st) }
        if (st == PcState.ON && lockable(p.pcIp)) acts += R.string.store_lock_now to { lockStore(p) }
        if (acts.size == 1) { pcClicked(p, st); return }
        AlertDialog.Builder(this).setTitle(p.pcName)
            .setItems(acts.map { getString(it.first) }.toTypedArray()) { _, which -> acts[which].second() }
            .showSecure()
    }

    /** 허브 저장소를 지금 잠근다. 잠그면 그 PC는 다시 비밀번호로 열어야 한다(열쇠도 모두 끝난다). */
    private fun lockStore(p: Pairing) {
        io.execute {
            val err = try { Net.storeLock(p); null } catch (e: Exception) { e }
            ui.post {
                if (isDestroyed) return@post
                if (err == null) {
                    pcStates[p.pcIp] = PcState.STORE_LOCKED
                    pcStore[p.pcIp] = HubStore.LOCKED
                    toast(getString(R.string.store_locked_now))
                } else if ((err as? ProtocolException)?.code == "store_state") toast(getString(R.string.store_lock_not_strong))
                else toast(getString(R.string.failed, Errors.text(this, err)))
                if (pc == null) render()
            }
        }
    }

    /**
     * 강한 잠금(§18.3)으로 잠긴 허브를 클라우드 비밀번호로 연다(유리 대화 상자, 캡처 막기). 비밀번호는 이 요청에만 쓰고 두지 않는다
     * (칸도 창을 닫으면 비운다). 틀리면 창 안에 이유, 너무 많이 틀리면(vault_wait) 남은 초를 세며 버튼을 막는다.
     * 열리면 hello를 다시 해 상태를 새로 알고 then(들어가기).
     */
    private fun askStoreUnlock(p: Pairing, then: () -> Unit) {
        val d = resources.displayMetrics.density
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * d).toInt(), (4 * d).toInt(), (24 * d).toInt(), 0)
        }
        box.addView(TextView(this).apply {
            text = getString(R.string.store_unlock_msg, p.pcName)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(ContextCompat.getColor(context, R.color.secondary))
            setPadding(0, 0, 0, (8 * d).toInt())
        })
        val field = EditText(this).apply {
            hint = getString(R.string.store_password_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            typeface = Typeface.DEFAULT
            setSingleLine()
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            filters = arrayOf(android.text.InputFilter.LengthFilter(256))
        }
        box.addView(field)
        val error = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(ContextCompat.getColor(context, R.color.red))
            setPadding(0, (6 * d).toInt(), 0, 0)
            visibility = View.GONE
        }
        box.addView(error)
        val dlg = AlertDialog.Builder(this).setTitle(R.string.store_unlock_title).setView(box)
            .setPositiveButton(R.string.vault_open_btn, null).setNegativeButton(R.string.cancel, null).create().secure()
        var waitUntil = 0L
        lateinit var tick: Runnable
        tick = Runnable {
            val ok = dlg.getButton(AlertDialog.BUTTON_POSITIVE) ?: return@Runnable
            val left = ((waitUntil - System.currentTimeMillis() + 999) / 1000).toInt()
            if (left > 0) {
                ok.isEnabled = false
                error.text = getString(R.string.err_vault_wait_s, left)
                error.visibility = View.VISIBLE
                ui.postDelayed(tick, 1000)
            } else { ok.isEnabled = true; error.visibility = View.GONE }
        }
        dlg.setOnDismissListener { ui.removeCallbacks(tick); field.setText("") }
        dlg.setOnShowListener {
            val ok = dlg.getButton(AlertDialog.BUTTON_POSITIVE)
            ok.setOnClickListener {
                val pw = field.text.toString()
                if (pw.isEmpty()) { error.text = getString(R.string.store_password_empty); error.visibility = View.VISIBLE; return@setOnClickListener }
                ok.isEnabled = false
                error.visibility = View.GONE
                io.execute {
                    var h: Xfer.Hello? = null
                    val e = try {
                        Net.storeUnlock(p, pw)
                        h = try { Net.hello(p, null, null) } catch (x: Exception) { null }
                        null
                    } catch (x: Exception) { x }
                    ui.post {
                        if (isDestroyed) return@post
                        if (e == null) {
                            val hh = h
                            if (hh != null) applyHello(p, hh, stateOf(hh)) else { pcStates[p.pcIp] = PcState.ON; pcStore[p.pcIp] = HubStore.OPEN }
                            dlg.dismiss()
                            if (pcStates[p.pcIp] == PcState.ON) then() else render()
                            return@post
                        }
                        ok.isEnabled = true
                        val retry = if ((e as? ProtocolException)?.code == "vault_wait") Vault.retrySeconds((e as? HubRefusal)?.reply) else null
                        if (retry != null && retry > 0) { waitUntil = System.currentTimeMillis() + retry * 1000L; tick.run() }
                        else { error.text = Errors.text(this, e); error.visibility = View.VISIBLE }
                        field.setText("")
                    }
                }
            }
        }
        dlg.show()
    }

    // ---------- 목록·찾기 ----------

    /**
     * 폴더 목록(또는 찾기 결과)을 읽는다. reset이면 처음 쪽부터(keep이면 받는 동안 지금 목록을 그대로 보여 준다 — 새로 고침),
     * 아니면 next 쪽을 더 읽는다. 잠긴 폴더 안이면 Net이 열쇠를 붙이고, 열쇠가 없거나 만료면(vault_locked) PIN을 다시 묻는다.
     */
    private fun load(reset: Boolean, keep: Boolean = true) {
        val p = pc ?: return
        if (!reset && (loading || next == null || loadError != null)) return
        val cursor = if (reset) 0 else next ?: return
        val g = if (reset) ++gen else gen
        // 다른 폴더·찾기로 바뀌면 선택을 비운다(새로 고침이면 받은 뒤 남은 항목만 남긴다).
        if (reset && !keep) { items = emptyList(); next = null; partial = false; clearSelection() }
        loading = true
        loadError = null
        render()
        val dir = path
        val q = query
        val k = kind
        val search = searching && cloud2
        val off = offline
        val app = applicationContext
        io.execute {
            val result = try {
                when {
                    // 오프라인 사본: 이 기기의 목록에서(한 번에 전부, 쪽 나눔 없음)
                    off -> {
                        val all = Mirror.peek(app, p.pcIp)?.entries().orEmpty()
                        val got = if (q.isNotEmpty() || k != FileKinds.ALL) MirrorTree.search(all, dir, q, k) else MirrorTree.list(all, dir)
                        SearchPage(CloudPage(got, null), false)
                    }
                    search -> Net.cloudSearch(p, dir, q, k, cursor)
                    else -> SearchPage(Net.cloudList(p, dir, cursor), false)
                }
            } catch (e: Exception) {
                ui.post {
                    if (isDestroyed || g != gen) return@post
                    // 허브에 닿지 않는다: 이 기기에 사본이 있으면 그것을 읽기 전용으로 보인다(§19.3).
                    if (reset && !off && (e as? ProtocolException)?.code == "connect_failed" && hasCopy(p)) {
                        loading = false
                        browseOffline(p)
                        return@post
                    }
                    loading = false
                    if (reset) { items = emptyList(); next = null; loadError = Errors.text(this, e) }
                    else toast(getString(R.string.failed, Errors.text(this, e)))
                    render()
                    // 잠긴 폴더 안인데 열쇠가 없다(만료·앱을 다시 켬): 그 폴더를 알면 PIN을 묻고 다시 읽는다.
                    if (reset && (e as? ProtocolException)?.code == "vault_locked") {
                        // 허브가 알려 준 자리(§14.2 mount)를 먼저, 없으면 이 폰이 아는 자리로.
                        (Vault.lockedMount((e as? HubRefusal)?.reply) ?: vault.mountOf(p.pcIp, dir))
                            ?.let { m -> askOpen(m) { load(reset = true, keep = false) } }
                    }
                    // 보는 사이 허브가 다시 잠겼다(강한 잠금 §18.3: Windows 잠금·쉬는 시간): 비밀번호로 열고 다시 읽는다.
                    if (reset && (e as? ProtocolException)?.code == "store_locked" && "store_unlock" in (pcCaps[p.pcIp] ?: emptySet())) {
                        pcStore[p.pcIp] = HubStore.LOCKED
                        vault.dropTokens(p.pcIp) // 다시 잠긴 허브는 열쇠도 모두 잊었다(자리는 남겨 PIN을 다시 묻는다)
                        askStoreUnlock(p) { if (pc === p) load(reset = true, keep = false) }
                    }
                }
                return@execute
            }
            ui.post {
                if (isDestroyed || g != gen) return@post
                loading = false
                val got = result.page.items
                got.filter { it.locked }.forEach { vault.learn(p.pcIp, CloudPath.join(dir, it.path ?: it.name)) }
                items = if (reset) got else items + got
                if (reset && selecting) {
                    val still = items.mapTo(HashSet()) { targetOf(it) }
                    selected.retainAll(still)
                    updateSelectionUi()
                }
                // partial은 훑다가 멈췄다는 뜻이고 다음 쪽(next)과 따로 온다(§15): 한 쪽이라도 멈췄으면 끝에 알린다.
                partial = (if (reset) false else partial) || result.partial
                // 허브가 뒤로 가는 cursor를 주면 더 읽지 않는다(같은 쪽을 맴돌지 않게).
                next = result.page.next?.takeIf { it > cursor }
                render()
                list.post { maybeLoadMore() }
                scheduleBusyRefresh()
            }
        }
    }

    /** 잠그거나 푸는 중인 폴더가 보이면 BUSY_REFRESH_MS 뒤 한 번 다시 읽는다(화면이 보일 때만, 없으면 하지 않는다). */
    private fun scheduleBusyRefresh() {
        ui.removeCallbacks(busyRefresh)
        if (resumed && pc != null && items.any { it.busy }) ui.postDelayed(busyRefresh, BUSY_REFRESH_MS)
    }

    /** 목록 끝 가까이 왔으면(또는 화면이 다 차지 않았으면) 다음 쪽을 읽는다. */
    private fun maybeLoadMore() {
        if (pc == null || loading || next == null) return
        if (CloudGrid.nearEnd(layout.findLastVisibleItemPosition(), adapter.itemCount, 6)) load(reset = false)
    }

    private fun onJobsChanged() {
        if (isDestroyed) return
        val p = pc ?: return
        val ups = Outbox.cloudJobs(this, p.pcIp).filter { it.kind == OutboxJob.CLOUD_UP && it.remote == path && !it.failed }
            .map { it.id }.toSet()
        // 이 폴더로 올리던 건이 끝났다(목록에서 빠짐): 폴더를 새로 읽어 올린 파일이 보이게.
        val finished = uploadsHere.any { it !in ups && Outbox.get(this, it) == null }
        uploadsHere = ups
        if (finished) load(reset = true, keep = true) else render()
    }

    // ---------- 격자·종류 ----------

    private fun setGrid(on: Boolean) {
        grid = on
        getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean(K_GRID, on).apply()
        applyLayout()
        adapter.notifyDataSetChanged()
    }

    /** 격자는 "cloud2" 허브에서만(미리보기가 있을 때). 칸 수는 화면 폭으로. */
    private fun applyLayout() {
        val cols = if (grid && richUi) CloudGrid.columns(resources.configuration.screenWidthDp) else 1
        if (layout.spanCount != cols) layout.spanCount = cols
        viewBtn.setImageResource(if (grid) R.drawable.ic_list else R.drawable.ic_grid)
        viewBtn.contentDescription = getString(if (grid) R.string.cloud_view_list else R.string.cloud_view_grid)
    }

    private fun kindLabel(k: String): Int = when (k) {
        FileKinds.IMAGE -> R.string.kind_image
        FileKinds.VIDEO -> R.string.kind_video
        FileKinds.AUDIO -> R.string.kind_audio
        FileKinds.DOC -> R.string.kind_doc
        FileKinds.ARCHIVE -> R.string.kind_archive
        FileKinds.OTHER -> R.string.kind_other
        else -> R.string.kind_all
    }

    private fun kindIcon(k: String): Int = when (k) {
        FileKinds.IMAGE -> R.drawable.ic_image
        FileKinds.VIDEO -> R.drawable.ic_video
        FileKinds.AUDIO -> R.drawable.ic_music
        FileKinds.DOC -> R.drawable.ic_file_text
        FileKinds.ARCHIVE -> R.drawable.ic_archive
        else -> R.drawable.ic_doc
    }

    /** 종류 칩(shadcn Toggle 모양). 누르면 그 종류로 찾는다(찾을 말이 없어도 이 폴더 아래의 그 종류 전부). */
    private fun buildChips() {
        val d = resources.displayMetrics.density
        for (k in FileKinds.FILTERS) {
            kindChips.addView(TextView(this).apply {
                tag = k
                text = getString(kindLabel(k))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(ContextCompat.getColorStateList(context, R.color.chip_text))
                setBackgroundResource(R.drawable.bg_chip_toggle)
                setPadding((14 * d).toInt(), (7 * d).toInt(), (14 * d).toInt(), (7 * d).toInt())
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { marginEnd = (8 * d).toInt() }
                setOnClickListener {
                    if (kind == k) return@setOnClickListener
                    kind = k
                    updateChips()
                    load(reset = true, keep = false)
                }
            })
        }
        updateChips()
    }

    private fun updateChips() {
        for (i in 0 until kindChips.childCount) kindChips.getChildAt(i).let { it.isSelected = it.tag == kind }
    }

    // ---------- 그리기 ----------

    private fun render() {
        val p = pc
        val rows = ArrayList<Row>()
        // 오프라인 사본은 읽기 전용: 새 폴더·올리기·허브 메뉴를 숨긴다
        newFolderBtn.visibility = if (p == null || offline) View.GONE else View.VISIBLE
        uploadBtn.visibility = if (p == null || offline) View.GONE else View.VISIBLE
        moreBtn.visibility = if (p != null && canSync) View.VISIBLE else View.GONE
        offlineBanner.visibility = if (p != null && offline) View.VISIBLE else View.GONE
        crumbsRow.visibility = if (p == null) View.GONE else View.VISIBLE
        val c2 = p != null && richUi
        searchBar.visibility = if (c2) View.VISIBLE else View.GONE
        kindScroll.visibility = if (c2) View.VISIBLE else View.GONE
        viewBtn.visibility = if (c2) View.VISIBLE else View.GONE
        applyLayout()
        if (p == null) {
            title.text = getString(R.string.cloud_title)
            subtitle.text = getString(R.string.cloud_pick_sub)
            Store.all(this).forEach { rows += Row.Pc(it, pcStates[it.pcIp] ?: PcState.CHECKING) }
            setRows(rows)
            showEmpty(false)
            return
        }
        title.text = p.pcName
        // 허브의 용량(§19.1, hello에서 받았을 때만)
        val space = spaceText(p)
        val sub = if (offline) getString(R.string.offline_banner) else getString(R.string.cloud_title)
        subtitle.text = when {
            loading && items.isEmpty() -> getString(R.string.cloud_loading)
            space != null -> getString(R.string.cloud_file_meta, sub, space)
            else -> sub
        }
        renderCrumbs(p)
        val jobs = Outbox.cloudJobs(this, p.pcIp)
        uploadsHere = jobs.filter { it.kind == OutboxJob.CLOUD_UP && it.remote == path && !it.failed }.map { it.id }.toSet()
        if (jobs.isNotEmpty()) {
            rows += Row.Header(getString(R.string.cloud_transfers))
            jobs.forEach { rows += Row.Job(it) }
            rows += Row.Header(if (path.isEmpty()) p.pcName else CloudPath.name(path))
        }
        items.forEach { rows += Row.Item(it) }
        if (next != null) rows += Row.More
        if (partial && searching && next == null) rows += Row.Note(getString(R.string.cloud_search_partial), null, false)
        val err = loadError
        val nothing = items.isEmpty() && !loading
        val emptyTitleText = getString(if (searching) R.string.search_empty else R.string.cloud_empty)
        // 전송 줄이 없으면 빈 폴더·오류를 가운데 크게, 있으면 목록 안에 한 줄로.
        if (nothing && jobs.isNotEmpty()) {
            rows += if (err != null) Row.Note(getString(R.string.cloud_load_failed), err, true)
            else Row.Note(emptyTitleText, if (searching) null else getString(R.string.cloud_empty_sub), false)
        }
        setRows(rows)
        if (nothing && jobs.isEmpty()) {
            when {
                err != null -> setEmpty(R.drawable.ic_cloud, getString(R.string.cloud_load_failed), err, true)
                searching -> setEmpty(R.drawable.ic_search, emptyTitleText, "", false)
                offline -> setEmpty(R.drawable.ic_cloud, getString(R.string.offline_empty), "", false)
                else -> setEmpty(R.drawable.ic_folder, emptyTitleText, getString(R.string.cloud_empty_sub), false)
            }
            showEmpty(true)
        } else showEmpty(false)
    }

    /** 목록을 바꾼다. 줄이 없으면 유리 카드도 숨긴다(빈 테두리만 남지 않게). */
    private fun setRows(rows: List<Row>) {
        adapter.setData(rows)
        listCard.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun setEmpty(icon: Int, t: String, sub: String, retry: Boolean) {
        emptyIcon.setImageResource(icon)
        emptyTitle.text = t
        emptySub.text = sub
        emptySub.visibility = if (sub.isEmpty()) View.GONE else View.VISIBLE
        emptyBtn.visibility = if (retry) View.VISIBLE else View.GONE
    }

    private fun showEmpty(on: Boolean) { empty.visibility = if (on) View.VISIBLE else View.GONE }

    /** 맨 위(PC 이름)부터 지금 폴더까지. 마지막 조각은 굵게, 앞 조각은 누르면 그 폴더로. 잠긴 폴더 안이면 자물쇠. */
    private fun renderCrumbs(p: Pairing) {
        crumbs.removeAllViews()
        val segs = if (path.isEmpty()) emptyList() else path.split('/')
        val labels = listOf(p.pcName) + segs
        labels.forEachIndexed { i, label ->
            if (i > 0) crumbs.addView(crumbChevron())
            val target = segs.take(i).joinToString("/")
            val locked = i > 0 && vault.mountOf(p.pcIp, target) == target
            val last = i == labels.size - 1
            crumbs.addView(crumbLabel(label, last = last, locked = locked) { open(target) }.also {
                // 고른 것을 끌어 위 폴더 조각에 놓으면 그리로 옮긴다(2.2.1). 지금 폴더(마지막)에는 놓을 일이 없다.
                if (!last) it.setOnDragListener(crumbDrop(target))
            })
        }
        crumbsScroll.post { crumbsScroll.fullScroll(View.FOCUS_RIGHT) }
    }

    /** 빵 부스러기 조각 사이의 꺾쇠(벡터 아이콘). */
    private fun crumbChevron(): View {
        val d = resources.displayMetrics.density
        return ImageView(this).apply {
            setImageResource(R.drawable.ic_chevron_right)
            setColorFilter(ContextCompat.getColor(context, R.color.secondary))
            layoutParams = LinearLayout.LayoutParams((14 * d).toInt(), (14 * d).toInt())
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }

    /** 빵 부스러기 조각 하나. 마지막은 굵게(누를 수 없음), 앞 조각은 누르면 onClick. 잠긴 폴더면 자물쇠. */
    private fun crumbLabel(label: String, last: Boolean, locked: Boolean, onClick: () -> Unit): View {
        val d = resources.displayMetrics.density
        return TextView(this).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(ContextCompat.getColor(context, if (last) R.color.label else R.color.secondary))
            if (last) setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = (200 * d).toInt()
            setPadding((8 * d).toInt(), (6 * d).toInt(), (8 * d).toInt(), (6 * d).toInt())
            if (locked) {
                val lock = ContextCompat.getDrawable(context, R.drawable.ic_lock)?.mutate()
                lock?.setBounds(0, 0, (13 * d).toInt(), (13 * d).toInt())
                lock?.setTint(currentTextColor)
                setCompoundDrawablesRelative(lock, null, null, null)
                compoundDrawablePadding = (4 * d).toInt()
            }
            if (!last) {
                setBackgroundResource(R.drawable.bg_btn_ghost)
                setOnClickListener { onClick() }
            }
        }
    }

    // ---------- 동작 ----------

    /** 항목의 클라우드 경로(찾기 결과는 시작 폴더 기준 path). */
    private fun targetOf(item: CloudItem) = CloudPath.join(path, item.path ?: item.name)

    private fun itemClicked(item: CloudItem) {
        val target = targetOf(item)
        val p = pc ?: return
        when {
            !item.dir && FileKinds.previewable(item.kindOrGuess) -> openLightbox(item)
            // 오프라인 사본은 이 앱 안에서만 연다(평문을 다른 앱·디스크로 내보내지 않는다)
            !item.dir && offline -> toast(getString(R.string.offline_media_only))
            !item.dir -> fileMenu(item, target)
            item.busy -> toast(getString(R.string.err_vault_busy))
            item.stalled -> folderMenu(item, target) // 멈춘 잠그기: 들어갈 수 없다(이어서 잠그기·잠금 풀기만)
            item.locked && !vault.has(p.pcIp, target) -> askOpen(target) { open(target) }
            else -> open(target)
        }
    }

    private fun meta(item: CloudItem): String {
        val date = if (item.mtime > 0) Lang.shortTime(this, item.mtime) else ""
        val first = when {
            item.locked && item.state == "locking" -> getString(R.string.cloud_locking, item.progress ?: 0)
            item.locked && item.state == "unlocking" -> getString(R.string.cloud_unlocking, item.progress ?: 0)
            item.stalled -> getString(R.string.cloud_stalled)
            item.locked -> getString(R.string.cloud_locked)
            item.dir -> getString(R.string.cloud_folder)
            else -> Formatter.formatShortFileSize(this, item.size)
        }
        val base = if (date.isEmpty() || item.busy || item.stalled) first else getString(R.string.cloud_file_meta, first, date)
        // 찾기 결과: 어느 폴더에 있는지(시작 폴더 기준)를 앞에
        val where = item.path?.let { CloudPath.parent(it) }?.takeIf { it.isNotEmpty() }
        return if (where != null) getString(R.string.cloud_file_meta, where, base) else base
    }

    /** 파일: 받기·지우기. */
    private fun fileMenu(item: CloudItem, target: String) {
        AlertDialog.Builder(this).setTitle(item.name).setMessage(meta(item))
            .setPositiveButton(R.string.cloud_download) { _, _ -> download(item, target) }
            .setNeutralButton(R.string.cloud_delete) { _, _ -> confirmDelete(item, target) }
            .setNegativeButton(R.string.cancel, null).showSecure()
    }

    /** 폴더(선택 막대의 더보기): 열기·지우기, cloud2면 잠그기(잠긴 폴더는 잠금 풀기·PIN 바꾸기). */
    private fun folderMenu(item: CloudItem, target: String) {
        val p = pc ?: return
        val acts = ArrayList<Pair<Int, () -> Unit>>()
        if (item.stalled) {
            // 멈춘 잠그기(§14.2): PIN으로 열면 허브가 이어서 잠그고, 잠금 풀기는 되돌린다. 안으로 들어가지는 않는다.
            acts += R.string.cloud_lock_resume to { askOpen(target) { toast(getString(R.string.vault_resumed)); load(reset = true, keep = true) } }
            acts += R.string.cloud_unlock to { askUnlock(item, target) }
        } else {
            acts += R.string.cloud_open to { itemClicked(item) }
            if (cloud2 && !item.locked && vault.mountOf(p.pcIp, target) == null) acts += R.string.cloud_lock to { askLock(item, target) }
            if (item.locked && !item.busy) {
                acts += R.string.cloud_unlock to { askUnlock(item, target) }
                acts += R.string.cloud_repin to { askRepin(item, target) }
            }
        }
        acts += R.string.cloud_delete to {
            // 잠긴 폴더 자체를 지우려면 열쇠가 있어야 한다(§14.2) — 없으면 먼저 연다.
            if (item.locked && !vault.has(p.pcIp, target)) askOpen(target) { confirmDelete(item, target) } else confirmDelete(item, target)
        }
        AlertDialog.Builder(this).setTitle(item.name)
            .setItems(acts.map { getString(it.first) }.toTypedArray()) { _, which -> acts[which].second() }
            .showSecure()
    }

    private fun download(item: CloudItem, target: String) {
        val p = pc ?: return
        Outbox.enqueueCloudDownload(this, p.pcIp, target, item.size)
        SendService.start(this)
        toast(getString(R.string.cloud_download_queued))
    }

    /** 고른 파일들을 지금 폴더로 올릴 목록에 넣는다. 읽기 권한은 위에서 영구로 받아 두었고, 서비스 시작에도 실어 넘긴다. */
    private fun upload(uris: List<Uri>) {
        val p = pc ?: return
        Outbox.enqueueCloudUpload(this, p.pcIp, path, uris.map { it.toString() })
        SendService.start(this, uris)
        toast(getString(R.string.cloud_upload_queued))
    }

    /** 지우기는 늘 확인을 받는다(PC는 휴지통으로 보낸다, 잠긴 폴더는 보관함째 영구히). */
    private fun confirmDelete(item: CloudItem, target: String) {
        val p = pc ?: return
        AlertDialog.Builder(this).setTitle(getString(R.string.cloud_delete_title, item.name))
            .setMessage(if (item.dir) R.string.cloud_delete_folder_msg else R.string.cloud_delete_file_msg)
            .setPositiveButton(R.string.cloud_delete) { _, _ ->
                io.execute {
                    val err = try { Net.cloudDel(p, target); null } catch (e: Exception) { e }
                    ui.post {
                        if (isDestroyed) return@post
                        if (err == null) {
                            if (item.locked) vault.forgetMount(p.pcIp, target)
                            toast(getString(R.string.cloud_deleted))
                        } else toast(getString(R.string.failed, Errors.text(this, err)))
                        if (pc === p) load(reset = true, keep = true)
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null).showSecure()
    }

    /** 새 폴더 이름을 묻고 parent 아래에 만든다. done(만든 경로)은 실패해도 부른다(실패면 null, 이유는 알림으로). */
    private fun askNewFolder(parent: String, done: (String?) -> Unit) {
        val p = pc ?: return
        val d = resources.displayMetrics.density
        val field = EditText(this).apply {
            hint = getString(R.string.cloud_folder_name)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
        }
        val box = FrameLayout(this).apply {
            setPadding((22 * d).toInt(), (8 * d).toInt(), (22 * d).toInt(), 0)
            addView(field)
        }
        AlertDialog.Builder(this).setTitle(R.string.cloud_new_folder).setView(box)
            .setPositiveButton(R.string.cloud_create) { _, _ ->
                val name = field.text.toString().trim()
                val target = CloudPath.join(parent, name)
                if (!CloudPath.isSegment(name) || !CloudPath.isValid(target)) { toast(getString(R.string.cloud_bad_name)); return@setPositiveButton }
                io.execute {
                    val err = try { Net.cloudMkdir(p, target); null } catch (e: Exception) { e }
                    ui.post {
                        if (isDestroyed) return@post
                        if (err != null) toast(getString(R.string.failed, Errors.text(this, err)))
                        done(if (err == null) target else null)
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null).showSecure()
    }

    // ---------- 잠긴 폴더(§14) ----------

    /**
     * PIN 입력 창(유리 대화 상자). 칸마다 hint, 첫 칸은 recoveryToggle이면 "복구 코드로 하기"로 바꿀 수 있다.
     * check가 null을 돌려주면 work를 따로 스레드에서 돌리고(버튼은 잠시 막음), 성공하면 닫고 done, 실패하면 창 안에 이유를 보인다.
     */
    private fun pinForm(
        title: String, message: String, hints: List<Int>, recoveryToggle: Boolean, positive: Int,
        check: (List<String>, Boolean) -> String?, work: (List<String>, Boolean) -> Unit, done: () -> Unit,
    ) {
        val d = resources.displayMetrics.density
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * d).toInt(), (4 * d).toInt(), (24 * d).toInt(), 0)
        }
        val msg = TextView(this).apply {
            text = message
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(ContextCompat.getColor(context, R.color.secondary))
            setPadding(0, 0, 0, (8 * d).toInt())
        }
        box.addView(msg)
        val pinType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        val fields = hints.map { h ->
            EditText(this).apply {
                hint = getString(h)
                inputType = pinType
                typeface = Typeface.DEFAULT // 비밀번호 칸은 기본이 고정폭이라 안내 글이 어색하다
                setSingleLine()
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                filters = arrayOf(android.text.InputFilter.LengthFilter(100))
            }.also { box.addView(it) }
        }
        val error = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(ContextCompat.getColor(context, R.color.red))
            setPadding(0, (6 * d).toInt(), 0, 0)
            visibility = View.GONE
        }
        box.addView(error)
        var recovery = false
        if (recoveryToggle) {
            box.addView(TextView(this).apply {
                text = getString(R.string.vault_use_recovery)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(ContextCompat.getColor(context, R.color.label))
                paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
                setPadding(0, (12 * d).toInt(), 0, (4 * d).toInt())
                setOnClickListener {
                    recovery = !recovery
                    text = getString(if (recovery) R.string.vault_use_pin else R.string.vault_use_recovery)
                    fields[0].setText("")
                    fields[0].hint = getString(if (recovery) R.string.vault_recovery_hint else hints[0])
                    fields[0].inputType = if (recovery) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
                        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS else pinType
                    fields[0].typeface = Typeface.DEFAULT
                    if (hints.size == 1) msg.text = getString(if (recovery) R.string.vault_open_recovery_msg else R.string.vault_open_msg)
                    error.visibility = View.GONE
                }
            })
        }
        val dlg = AlertDialog.Builder(this).setTitle(title).setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton(positive, null).setNegativeButton(R.string.cancel, null).create().secure()
        dlg.setOnShowListener {
            val ok = dlg.getButton(AlertDialog.BUTTON_POSITIVE)
            ok.setOnClickListener {
                val values = fields.map { it.text.toString() }
                val bad = check(values, recovery)
                if (bad != null) { error.text = bad; error.visibility = View.VISIBLE; return@setOnClickListener }
                ok.isEnabled = false
                error.visibility = View.GONE
                val rec = recovery
                io.execute {
                    val e = try { work(values, rec); null } catch (x: Exception) { x }
                    ui.post {
                        if (isDestroyed) return@post
                        ok.isEnabled = true
                        if (e == null) { dlg.dismiss(); done() }
                        else { error.text = Errors.text(this, e); error.visibility = View.VISIBLE }
                    }
                }
            }
        }
        dlg.show()
    }

    /** 지금 PIN·복구 코드 칸 검사(창 안에 보일 이유, 맞으면 null). 맞는지는 허브가 본다. */
    private fun firstOk(v: String, recovery: Boolean): String? =
        if (recovery) (if (Vault.normalizeRecovery(v) == null) getString(R.string.err_vault_pin) else null)
        else if (!Vault.pinEntered(v)) getString(R.string.err_vault_pin) else null

    private fun newPinOk(a: String, b: String): String? = when {
        !Vault.pinOk(a) -> getString(R.string.vault_pin_bad)
        a != b -> getString(R.string.vault_pin_mismatch)
        else -> null
    }

    /** 잠긴 폴더 열기(cloud_open): 열쇠를 받아 이 프로세스에 둔다. */
    private fun askOpen(mount: String, then: () -> Unit) {
        val p = pc ?: return
        pinForm(getString(R.string.vault_open_title, CloudPath.name(mount)), getString(R.string.vault_open_msg),
            listOf(R.string.vault_pin_hint), recoveryToggle = true, positive = R.string.vault_open_btn,
            check = { v, rec -> firstOk(v[0], rec) },
            work = { v, rec -> Net.cloudOpen(p, mount, if (rec) null else v[0], if (rec) Vault.normalizeRecovery(v[0]) else null) },
            done = { if (pc === p) then() })
    }

    /** 잠그기: PIN 두 번 → 허브가 복구 코드를 한 번만 돌려준다 → 보여 주고 목록을 다시 읽는다(잠그는 중 n%). */
    private fun askLock(item: CloudItem, target: String) {
        val p = pc ?: return
        var code: String? = null
        pinForm(getString(R.string.vault_lock_title, item.name), getString(R.string.vault_lock_msg),
            listOf(R.string.vault_pin_hint, R.string.vault_pin_again_hint), recoveryToggle = false, positive = R.string.cloud_lock,
            check = { v, _ -> newPinOk(v[0], v[1]) },
            work = { v, _ -> code = Net.cloudLock(p, target, v[0]) },
            done = {
                vault.learn(p.pcIp, target)
                showRecovery(code ?: "") {
                    toast(getString(R.string.vault_locking_started))
                    if (pc === p) load(reset = true, keep = true)
                }
            })
    }

    private fun askUnlock(item: CloudItem, target: String) {
        val p = pc ?: return
        pinForm(getString(R.string.vault_unlock_title, item.name), getString(R.string.vault_unlock_msg),
            listOf(R.string.vault_pin_hint), recoveryToggle = false, positive = R.string.cloud_unlock,
            check = { v, rec -> firstOk(v[0], rec) },
            work = { v, _ -> Net.cloudUnlock(p, target, v[0]) },
            done = {
                toast(getString(R.string.vault_unlocking_started))
                if (pc === p) load(reset = true, keep = true)
            })
    }

    private fun askRepin(item: CloudItem, target: String) {
        val p = pc ?: return
        pinForm(getString(R.string.vault_repin_title, item.name), getString(R.string.vault_open_msg),
            listOf(R.string.vault_current_pin_hint, R.string.vault_new_pin_hint, R.string.vault_pin_again_hint),
            recoveryToggle = true, positive = R.string.cloud_repin,
            check = { v, rec -> firstOk(v[0], rec) ?: newPinOk(v[1], v[2]) },
            work = { v, rec ->
                Net.cloudRepin(p, target, if (rec) null else v[0], if (rec) Vault.normalizeRecovery(v[0]) else null, v[1])
            },
            done = { toast(getString(R.string.vault_repin_done)) })
    }

    /**
     * 복구 코드를 한 번만 보여 준다(§14.1). 실수로 닫지 않게 바깥을 눌러도 닫히지 않고, "적어 두었습니다"를 눌러야 닫힌다.
     * 복사는 창을 닫지 않는다. 클립보드에는 민감한 내용으로 표시해 넣는다(Android 13+는 미리보기를 가린다).
     */
    private fun showRecovery(code: String, then: () -> Unit) {
        val d = resources.displayMetrics.density
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * d).toInt(), (4 * d).toInt(), (24 * d).toInt(), 0)
        }
        box.addView(TextView(this).apply {
            text = getString(R.string.vault_recovery_msg)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(ContextCompat.getColor(context, R.color.label))
        })
        box.addView(TextView(this).apply {
            text = Vault.displayRecovery(code)
            setTextIsSelectable(true)
            gravity = Gravity.CENTER
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextColor(ContextCompat.getColor(context, R.color.label))
            setBackgroundResource(R.drawable.bg_thumb)
            setPadding((12 * d).toInt(), (14 * d).toInt(), (12 * d).toInt(), (14 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = (14 * d).toInt() }
        })
        val dlg = AlertDialog.Builder(this).setTitle(R.string.vault_recovery_title).setView(box).setCancelable(false)
            .setPositiveButton(R.string.vault_saved_it, null).setNeutralButton(R.string.vault_copy, null).create().secure()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val clip = ClipData.newPlainText("TailHop", code)
                clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
                getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
                Toast.makeText(this, R.string.clip_copied, Toast.LENGTH_SHORT).show()
            }
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { dlg.dismiss(); then() }
        }
        dlg.show()
    }

    // ---------- 다중 선택(2.1.0) ----------

    /** 누름: 선택 모드면 고르기·빼기, 아니면 열기(사진·영상은 라이트박스). */
    private fun tapped(item: CloudItem) {
        if (selecting) toggle(targetOf(item)) else itemClicked(item)
    }

    /**
     * 길게 누름(2.2.1): 고르기 모드에서 이미 고른 항목이면 고른 것 전부를 끌어 폴더에 놓아 옮긴다(허브가 "move"를 알 때, MoveDrag).
     * 그 밖에는 지금처럼 고르고 끌면 범위 고르기(DragSelect).
     */
    private fun longPressed(v: View, item: CloudItem) {
        if (offline) return // 오프라인 사본은 읽기 전용(고를 일이 없다)
        val key = targetOf(item)
        if (selecting && key in selected && moveDrag.start(v)) return
        drag.start(v, key)
    }

    /** 선택을 바꾸고 바뀐 줄만 다시 그린다. 선택 모드가 켜지거나 꺼지면 모든 줄의 체크 원을 다시 그린다. */
    private fun changeSelection(block: () -> Unit) {
        val before = selected.toSet()
        val was = selecting
        block()
        if (was != selecting) adapter.selectionModeChanged()
        else adapter.selectionChanged((before - selected) + (selected - before))
        updateSelectionUi()
    }

    private fun toggle(key: String) = changeSelection { if (!selected.remove(key)) selected += key }

    private fun setSelection(next: Set<String>) = changeSelection { selected.clear(); selected.addAll(next) }

    private fun selectAll() = changeSelection { items.forEach { selected += targetOf(it) } }

    private fun clearSelection() {
        if (selecting) changeSelection { selected.clear() }
    }

    /**
     * 위 막대 ↔ 선택 막대, 아래 동작 막대. 옮기기는 허브가 "move"를, 이름 바꾸기는 "rename"을 알릴 때만, 더보기는 하나만 골랐을 때만.
     */
    private fun updateSelectionUi() {
        val on = selecting
        topBar.visibility = if (on) View.INVISIBLE else View.VISIBLE
        selBar.visibility = if (on) View.VISIBLE else View.GONE
        actionBar.visibility = if (on) View.VISIBLE else View.GONE
        if (!on) return
        selCount.text = getString(R.string.select_count, selected.size)
        actMove.visibility = if (canMove) View.VISIBLE else View.GONE
        actRename.visibility = if (canRename) View.VISIBLE else View.GONE
        actSync.visibility = if (canSync) View.VISIBLE else View.GONE
        actMore.visibility = if (selected.size == 1) View.VISIBLE else View.GONE
    }

    /** 고른 항목(목록 순서). */
    private fun selectedItems(): List<Pic> = items.mapNotNull { val t = targetOf(it); if (t in selected) Pic(it, t) else null }

    /** 받기: 파일만 받을 목록에 넣는다(폴더는 뺀다). */
    private fun downloadSelected() {
        val p = pc ?: return
        val sel = selectedItems()
        val files = sel.filter { !it.item.dir }
        if (files.isEmpty()) { toast(getString(R.string.cloud_download_files_only)); return }
        files.forEach { Outbox.enqueueCloudDownload(this, p.pcIp, it.target, it.item.size) }
        SendService.start(this)
        toast(getString(if (files.size < sel.size) R.string.cloud_download_n_skipped else R.string.cloud_download_n, files.size))
        clearSelection()
    }

    /** 지우기: 확인 창 뒤 하나씩 지우고 결과를 한 번에 알린다(PC는 휴지통으로, 잠긴 폴더는 열쇠가 있어야 한다). */
    private fun deleteSelected() {
        val p = pc ?: return
        val sel = selectedItems()
        if (sel.isEmpty()) return
        val one = sel.singleOrNull()
        val title = if (one != null) getString(R.string.cloud_delete_title, one.item.name) else getString(R.string.cloud_delete_n_title, sel.size)
        val msg = when {
            one == null -> R.string.cloud_delete_n_msg
            one.item.dir -> R.string.cloud_delete_folder_msg
            else -> R.string.cloud_delete_file_msg
        }
        AlertDialog.Builder(this).setTitle(title).setMessage(msg)
            .setPositiveButton(R.string.cloud_delete) { _, _ ->
                clearSelection()
                io.execute {
                    var ok = 0
                    val fails = ArrayList<String>()
                    for (x in sel) {
                        try {
                            Net.cloudDel(p, x.target)
                            ok++
                            if (x.item.locked) vault.forgetMount(p.pcIp, x.target)
                        } catch (e: Exception) { fails += Errors.text(this, e) }
                    }
                    ui.post {
                        if (isDestroyed) return@post
                        val reasons = fails.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key to it.value }
                        toast(summary(ok, R.string.cloud_deleted_n, fails.size, reasons, R.string.cloud_not_deleted))
                        if (pc === p) load(reset = true, keep = true)
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null).showSecure()
    }

    /** 더보기(하나만 골랐을 때): 예전 길게 누르기 메뉴 — 파일은 받기·지우기, 폴더는 열기·잠그기·잠금 풀기·PIN 바꾸기·지우기. */
    private fun moreSelected() {
        val x = selectedItems().singleOrNull() ?: return
        clearSelection()
        if (x.item.dir) folderMenu(x.item, x.target) else fileMenu(x.item, x.target)
    }

    // ---------- 모든 기기 동기화(2.3.0, §19.2) ----------

    /** 고른 것을 모든 기기 동기화 대상에 넣거나 뺀다(고르는 창). */
    private fun syncSelected() {
        val p = pc ?: return
        val sel = selectedItems()
        if (!canSync || sel.isEmpty()) return
        val paths = sel.map { it.target }
        AlertDialog.Builder(this).setTitle(getString(R.string.select_count, sel.size))
            .setItems(arrayOf(getString(R.string.sync_add), getString(R.string.sync_remove))) { _, which -> doSyncSet(p, paths, which == 0) }
            .showSecure()
    }

    /**
     * sync_set을 보내고(500개씩) 결과를 알린다("n개 넣음/뺌", 안 된 것은 이유별 개수 — 잠긴 폴더는 sync_vault 문장).
     * 잠긴 폴더의 열쇠가 끝났으면 PIN을 묻고 다시. 허브는 30초 모아 기기들에 알리지만, 이 폰도 조금 뒤 한 번 맞춰 본다.
     */
    private fun doSyncSet(p: Pairing, paths: List<String>, on: Boolean) {
        io.execute {
            val r = try { Net.syncSet(p, paths, on) } catch (e: Exception) {
                ui.post {
                    if (isDestroyed) return@post
                    toast(getString(R.string.failed, Errors.text(this, e)))
                    if ((e as? ProtocolException)?.code == "vault_locked") {
                        (Vault.lockedMount((e as? HubRefusal)?.reply) ?: vault.mountOf(p.pcIp, paths[0]))
                            ?.let { m -> askOpen(m) { doSyncSet(p, paths, on) } }
                    }
                }
                return@execute
            }
            ui.post {
                if (isDestroyed) return@post
                val reasons = CloudMove.failureCounts(MoveResult(emptyList(), r.failed))
                    .map { (code, n) -> Errors.text(this, ProtocolException(code)) to n }
                toast(summary(r.changed, if (on) R.string.sync_added_n else R.string.sync_removed_n, r.failed.size, reasons,
                    R.string.sync_not_changed))
                clearSelection()
                if (pc === p) load(reset = true, keep = true)
                MirrorSync.requestLater(this, p.pcIp, SYNC_FOLLOW_MS)
            }
        }
    }

    /** 위 막대의 더보기(허브가 "sync"를 알릴 때): 전체 동기화(켜짐 표시는 hello의 sync_all), 지금 동기화. */
    private fun hubMenu(anchor: View) {
        val p = pc ?: return
        if (!canSync) return
        val m = PopupMenu(this, anchor)
        m.menu.add(0, 1, 0, R.string.sync_all).apply { isCheckable = true; isChecked = p.pcIp in pcSyncAll }
        m.menu.add(0, 2, 1, R.string.sync_now)
        m.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> setSyncAll(p, p.pcIp !in pcSyncAll)
                2 -> if (!MirrorSettings.isOn(this)) toast(getString(R.string.sync_is_off)) else {
                    MirrorSync.request(this, p.pcIp, MirrorSync.Why.MANUAL)
                    toast(getString(R.string.sync_started))
                }
            }
            true
        }
        m.show()
    }

    private fun setSyncAll(p: Pairing, on: Boolean) {
        io.execute {
            val err = try { Net.syncAll(p, on); null } catch (e: Exception) { e }
            ui.post {
                if (isDestroyed) return@post
                if (err != null) { toast(getString(R.string.failed, Errors.text(this, err))); return@post }
                if (on) pcSyncAll += p.pcIp else pcSyncAll -= p.pcIp
                toast(getString(if (on) R.string.sync_all_on_done else R.string.sync_all_off_done))
                if (pc === p) load(reset = true, keep = true)
                MirrorSync.requestLater(this, p.pcIp, SYNC_FOLLOW_MS)
            }
        }
    }

    /** "n개 했음" + 실패가 있으면 "m개 못 함: 이유 (k), …"(많은 이유부터). */
    private fun summary(ok: Int, okRes: Int, failed: Int, reasons: List<Pair<String, Int>>, failRes: Int): String {
        val parts = ArrayList<String>()
        if (ok > 0 || failed == 0) parts += getString(okRes, ok)
        if (failed > 0) parts += getString(failRes, failed,
            reasons.joinToString(", ") { (why, n) -> if (n > 1) getString(R.string.reason_count, why, n) else why })
        return parts.joinToString("\n")
    }

    /**
     * 길게 눌러 고르고 손을 떼지 않고 끌면 처음 항목부터 손가락 아래 항목까지 고른다(Google 포토처럼).
     * 길게 누르기가 시작되면 RecyclerView보다 먼저 손짓을 가로채(스크롤 대신) 범위를 바꾸고, 위·아래 끝 가까이에서는 저절로 내린다.
     * 끄는 동안에만 한 프레임에 한 번 돈다.
     */
    private inner class DragSelect : RecyclerView.OnItemTouchListener {
        private var active = false
        private var anchor = RecyclerView.NO_POSITION
        private var base: Set<String> = emptySet()
        private var x = 0f
        private var y = 0f

        private val autoScroll = object : Runnable {
            override fun run() {
                if (!active) return
                val d = resources.displayMetrics.density
                val dy = CloudSelect.autoScroll(y, list.height, 64 * d, (16 * d).toInt())
                if (dy != 0 && list.canScrollVertically(dy)) { list.scrollBy(0, dy); update() }
                list.postOnAnimation(this)
            }
        }

        /** 길게 누른 항목(v)을 고르고 끌기를 시작한다. 이미 고른 것들은 그대로 둔다. */
        fun start(v: View, key: String) {
            val pos = list.getChildAdapterPosition(v)
            if (pos == RecyclerView.NO_POSITION) return
            base = selected.toSet()
            anchor = pos
            x = v.left + v.width / 2f
            y = v.top + v.height / 2f
            active = true
            setSelection(base + key)
            list.postOnAnimation(autoScroll)
        }

        fun stop() {
            active = false
            list.removeCallbacks(autoScroll)
        }

        override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
            if (!active) return false
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL ||
                e.actionMasked == MotionEvent.ACTION_DOWN) { stop(); return false }
            return true // 움직임부터는 이 손짓을 가져온다(눌린 줄에는 CANCEL이 간다)
        }

        override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> { x = e.x; y = e.y; update() }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> stop()
            }
        }

        override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}

        private fun update() {
            if (list.width <= 0 || list.height <= 0) return
            val child = list.findChildViewUnder(x.coerceIn(0f, list.width - 1f), y.coerceIn(0f, list.height - 1f)) ?: return
            val pos = list.getChildAdapterPosition(child)
            if (pos == RecyclerView.NO_POSITION) return
            setSelection(CloudSelect.range(base, adapter.keys, anchor, pos))
        }
    }

    // ---------- 라이트박스(2.1.0) ----------

    /** 사진·영상을 전체 화면으로. 지금 목록(찾기 결과 포함)의 사진·영상만 좌우로 넘긴다. */
    private fun openLightbox(item: CloudItem) {
        val p = pc ?: return
        val pics = items.filter { !it.dir && FileKinds.previewable(it.kindOrGuess) }.map { Pic(it, targetOf(it)) }
        val start = pics.indexOfFirst { it.item == item }
        if (start < 0) return
        lightbox?.close()
        val lb = Lightbox(this, pics, start, if (offline) mirrorPics(p) else cloudPics(p, cloud2, ranges))
        lb.onClosed = { if (lightbox === lb) lightbox = null }
        lightbox = lb
        lb.show()
    }

    /**
     * 클라우드 사진의 라이트박스 원천. 먼저 격자·목록의 미리보기(메모리)를 보이고, 크게 받아(진행률) 바꾼다(CloudView.first).
     * 2.2 허브(§17.4)는 사진의 screen(긴 변 2048)을 먼저 받고, 100 %보다 크게 확대하면 원본(64 MB 이하)으로 바꾼다. 영상은 poster.
     * 딸린 버전이 없으면 예전처럼 원본(64 MB 이하) 또는 허브의 큰 미리보기(512px). 잠긴 폴더 안 것도 디스크에 쓰지 않는다(메모리만).
     * 2.2.1: 앞뒤 한 장은 가벼운 것(화면용·poster·미리보기, 8 MB 이하 원본)만 미리 받는다. 정보 창의 원본 가로세로·찍은 날짜는
     * 원본을 받았으면 그것에서, 아니면 원본의 머리(256 KiB, §17.3 범위)만 메모리로 받아 읽는다.
     */
    private fun cloudPics(p: Pairing, c2: Boolean, ranges: Boolean) = object : Lightbox.Source<Pic> {
        override fun key(t: Pic) = t.target
        override fun name(t: Pic) = t.item.name
        override fun video(t: Pic) = t.item.kindOrGuess == FileKinds.VIDEO

        override fun quick(t: Pic): Bitmap? =
            Thumbs.cached(Thumbs.cloudKey(p.pcIp, t.target, t.item, CloudThumb.GRID_PX))
                ?: Thumbs.cached(Thumbs.cloudKey(p.pcIp, t.target, t.item, CloudThumb.LIST_PX))

        override fun load(t: Pic, job: Thumbs.Job, progress: (Long, Long) -> Unit): Lightbox.Picture? {
            // 동기화 사본에 같은 파일이 있으면 거기서(§19.3, 데이터를 쓰지 않는다). 풀 수 없으면 허브에서.
            localOf(t.target, t.item)?.let { e -> mirrorPicture(p, e, video(t))?.let { return it } }
            return when (CloudView.first(t.item, video(t), FULL_MAX)) {
                CloudView.First.SCREEN -> variant(t, CloudVariants.SCREEN, job, progress)
                CloudView.First.POSTER -> variant(t, CloudVariants.POSTER, job, progress)
                CloudView.First.ORIGINAL -> loadOriginal(t, job, progress)
                CloudView.First.THUMB -> {
                    // 예전 허브(cloud2 없음)는 미리보기가 없고, 2.1까지의 허브는 잠긴 폴더 안 영상의 미리보기를 만들지 않는다(§15).
                    val noThumb = video(t) && CloudVariants.THUMB !in t.item.variants && vault.mountOf(p.pcIp, t.target) != null
                    if (!c2 || noThumb) null
                    else Pictures.decode(Net.cloudThumb(p, t.target, CloudThumb.MAX_PX, job.token))?.let { Lightbox.Picture.plain(it) }
                }
            }
        }

        override fun prefetch(t: Pic): Boolean {
            if (localOf(t.target, t.item) != null) return video(t) || t.item.size <= PREFETCH_MAX // 사본은 이 폰에서 푼다
            return when (CloudView.first(t.item, video(t), FULL_MAX)) {
                CloudView.First.SCREEN, CloudView.First.POSTER -> true
                CloudView.First.ORIGINAL -> t.item.size <= PREFETCH_MAX
                CloudView.First.THUMB -> c2
            }
        }

        override fun original(t: Pic) = localOf(t.target, t.item) == null && CloudView.canUpgrade(t.item, video(t), FULL_MAX)

        override fun loadOriginal(t: Pic, job: Thumbs.Job, progress: (Long, Long) -> Unit): Lightbox.Picture? {
            localOf(t.target, t.item)?.let { e -> mirrorPicture(p, e, false)?.let { return it } }
            val buf = GrowBuffer(maxOf(1024, t.item.size.toInt()))
            Net.cloudGet(p, t.target, 0, null, null, job.token, open = { head ->
                if (head.size > FULL_MAX) throw ProtocolException("too_large") // 목록을 읽은 뒤 커졌다
                buf
            }, onProgress = progress)
            return if (job.cancelled) null else buf.picture(original = true)
        }

        /** 허브가 만든 딸린 버전(screen·poster, JPEG)을 메모리로 받아 그림으로. */
        private fun variant(t: Pic, role: String, job: Thumbs.Job, progress: (Long, Long) -> Unit): Lightbox.Picture? {
            var buf: GrowBuffer? = null
            Net.cloudGet(p, t.target, 0, null, null, job.token, open = { head ->
                if (head.size > FULL_MAX) throw ProtocolException("too_large")
                GrowBuffer(maxOf(1024, head.size.toInt())).also { buf = it }
            }, onProgress = progress, variant = role)
            return if (job.cancelled) null else buf?.picture(original = false)
        }

        override fun info(t: Pic): Lightbox.Info {
            val parent = CloudPath.parent(t.target)
            val folder = if (parent.isEmpty()) p.pcName else p.pcName + " / " + parent.replace("/", " / ")
            return Lightbox.Info(t.item.name, t.item.size, t.item.mtime, folder)
        }

        /** 사진의 원본 머리만 범위로 받아(2.2 허브, 메모리만) 가로세로·찍은 날짜를 읽는다. 영상·예전 허브는 모른다. */
        override fun details(t: Pic, job: Thumbs.Job): Pictures.Details? {
            if (video(t) || !ranges) return null
            val c = Net.cloudReadRange(p, t.target, 0, minOf(HEAD_LEN, t.item.size), null, token = job.token)
            return if (job.cancelled) null else Pictures.details(c.data)
        }

        override fun bindThumb(t: Pic, view: ImageView, show: (Bitmap) -> Unit) {
            if (wantsThumb(t.item)) bindThumbAt(view, t.target, t.item, CloudThumb.GRID_PX, show) else quick(t)?.let(show)
        }

        override fun actions() = listOf(
            Lightbox.Action<Pic>(R.string.cloud_download, R.drawable.ic_download) { download(it.item, it.target) },
            Lightbox.Action(R.string.cloud_delete, R.drawable.ic_trash) { deleteFromLightbox(p, it) },
        )

        /**
         * 2.2 허브: 앱 안 재생기(VideoActivity, 범위로 메모리에만 받는다, play가 있으면 그것). 이 폴더(찾기 결과)의 영상 목록을
         * 함께 넘겨 이전·다음으로 갈 수 있게 한다. 예전 허브는 범위 요청을 몰라 이 화면에서 재생하지 않는다: 받기를 권한다.
         */
        override fun play(t: Pic) {
            // 사본에 있는 영상은 예전 허브에서도 이 폰에서 재생한다(§19.3)
            if (ranges || localOf(t.target, t.item) != null) { playVideos(p, t); return }
            AlertDialog.Builder(this@CloudActivity).setTitle(t.item.name).setMessage(R.string.lightbox_video_msg)
                .setPositiveButton(R.string.cloud_download) { _, _ -> download(t.item, t.target) }
                .setNegativeButton(R.string.cancel, null).showSecure()
        }
    }

    /**
     * 앱 안 재생기로 연다: 이 폴더(찾기 결과)의 영상 목록을 함께 넘겨 이전·다음으로 갈 수 있게 한다. 사본에 같은 파일이 있는 것은
     * 사본에서 읽는다(local). 인텐트 크기 한도로 지금 것 둘레 VIDEO_LIST_MAX개까지.
     */
    private fun playVideos(p: Pairing, t: Pic) {
        val vids = items.filter { !it.dir && it.kindOrGuess == FileKinds.VIDEO }.map { Pic(it, targetOf(it)) }
        val at = vids.indexOfFirst { it.target == t.target }
        val list = if (at < 0) listOf(t) else vids
        val i = maxOf(0, at)
        val from = (i - VIDEO_LIST_MAX / 2).coerceIn(0, maxOf(0, list.size - VIDEO_LIST_MAX))
        val sub = list.subList(from, minOf(list.size, from + VIDEO_LIST_MAX))
        VideoActivity.start(this, p.pcIp, sub.map { VideoActivity.Entry(it.target, it.item.name,
            CloudView.playVariant(it.item), localOf(it.target, it.item) != null) }, i - from)
    }

    /**
     * 사본의 그림(작업 스레드, 메모리로만 푼다): 영상은 미리보기, 사진은 FULL_MAX 이하면 원본, 크면 미리보기. 풀 수 없으면 null.
     */
    private fun mirrorPicture(p: Pairing, e: MirrorEntry, video: Boolean): Lightbox.Picture? = try {
        val m = Mirror.peek(applicationContext, p.pcIp)
        when {
            m == null -> null
            video || e.size > FULL_MAX -> m.thumb(e)?.let { Pictures.decode(it) }?.let { Lightbox.Picture.plain(it) }
            else -> m.reader(e).use { r -> Pictures.picture(r.readAll(FULL_MAX), original = true) }
        }
    } catch (x: Exception) { null }

    /** 오프라인 사본의 라이트박스 원천: 모두 사본에서(읽기 전용 — 받기·지우기 버튼 없음). 영상은 사본에서 재생한다. */
    private fun mirrorPics(p: Pairing) = object : Lightbox.Source<Pic> {
        override fun key(t: Pic) = t.target
        override fun name(t: Pic) = t.item.name
        override fun video(t: Pic) = t.item.kindOrGuess == FileKinds.VIDEO

        override fun quick(t: Pic): Bitmap? =
            Thumbs.cached(Thumbs.cloudKey(p.pcIp, t.target, t.item, CloudThumb.GRID_PX))
                ?: Thumbs.cached(Thumbs.cloudKey(p.pcIp, t.target, t.item, CloudThumb.LIST_PX))

        override fun load(t: Pic, job: Thumbs.Job, progress: (Long, Long) -> Unit): Lightbox.Picture? =
            localOf(t.target, t.item)?.let { if (job.cancelled) null else mirrorPicture(p, it, video(t)) }

        override fun prefetch(t: Pic): Boolean = video(t) || t.item.size <= PREFETCH_MAX

        override fun info(t: Pic): Lightbox.Info {
            val parent = CloudPath.parent(t.target)
            val folder = if (parent.isEmpty()) p.pcName else p.pcName + " / " + parent.replace("/", " / ")
            return Lightbox.Info(t.item.name, t.item.size, t.item.mtime, folder)
        }

        override fun bindThumb(t: Pic, view: ImageView, show: (Bitmap) -> Unit) {
            if (wantsThumb(t.item)) bindThumbAt(view, t.target, t.item, CloudThumb.GRID_PX, show) else quick(t)?.let(show)
        }

        override fun actions(): List<Lightbox.Action<Pic>> = emptyList()

        override fun play(t: Pic) = playVideos(p, t)
    }

    /** 라이트박스에서 지우기: 확인 뒤 지우고, 되면 그 장을 빼고 다음 것을 보인다(남은 게 없으면 닫힌다). 목록도 새로 읽는다. */
    private fun deleteFromLightbox(p: Pairing, t: Pic) {
        AlertDialog.Builder(this).setTitle(getString(R.string.cloud_delete_title, t.item.name))
            .setMessage(R.string.cloud_delete_file_msg)
            .setPositiveButton(R.string.cloud_delete) { _, _ ->
                io.execute {
                    val err = try { Net.cloudDel(p, t.target); null } catch (e: Exception) { e }
                    ui.post {
                        if (isDestroyed) return@post
                        if (err != null) { toast(getString(R.string.failed, Errors.text(this, err))); return@post }
                        toast(getString(R.string.cloud_deleted))
                        lightbox?.remove(t)
                        if (pc === p) load(reset = true, keep = true)
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null).showSecure()
    }

    // ---------- 옮기기(2.1.0, §16 · 2.2.1 §18.1) ----------

    private fun moveSelected() {
        val p = pc ?: return
        val sel = selectedItems()
        if (!canMove || sel.isEmpty()) return
        val paths = sel.map { it.target }
        MovePicker(p, sel) { dest -> doMove(p, paths, dest) }.show()
    }

    /** 옮길 것들의 잠긴 폴더 규칙 정보(CloudMove.blocked). 잠긴 폴더 자체는 그것이 놓인 자리(부모)의 잠긴 폴더를 본다. */
    private fun sources(p: Pairing, moving: List<Pic>): List<CloudMove.Source> = moving.map {
        val lockedDir = it.item.dir && it.item.locked
        CloudMove.Source(it.target, vault.mountOf(p.pcIp, if (lockedDir) CloudPath.parent(it.target) else it.target), lockedDir)
    }

    /** 도착 폴더가 든 잠긴 폴더 자리(도착이 잠긴 폴더 자체면 그것). */
    private fun destMount(p: Pairing, dest: String, destItem: CloudItem?): String? =
        if (destItem?.dir == true && destItem.locked) dest else vault.mountOf(p.pcIp, dest)

    /** 옮기고 결과를 알린다("n개 옮김", 못 옮긴 것은 이유별 개수). 잠긴 폴더의 열쇠가 끝났으면(출발·도착 모두) PIN을 묻고 다시 한다. */
    private fun doMove(p: Pairing, paths: List<String>, dest: String) {
        val m2 = move2
        io.execute {
            val r = try { Net.cloudMove(p, paths, dest, m2) } catch (e: Exception) {
                ui.post {
                    if (isDestroyed) return@post
                    toast(getString(R.string.failed, Errors.text(this, e)))
                    if ((e as? ProtocolException)?.code == "vault_locked") {
                        (Vault.lockedMount((e as? HubRefusal)?.reply) ?: vault.mountOf(p.pcIp, dest))
                            ?.let { m -> askOpen(m) { doMove(p, paths, dest) } }
                    }
                }
                return@execute
            }
            ui.post {
                if (isDestroyed) return@post
                val reasons = CloudMove.failureCounts(r).map { (code, n) -> Errors.text(this, ProtocolException(code)) to n }
                toast(summary(r.moved.size, R.string.cloud_moved_n, r.failed.size, reasons, R.string.cloud_not_moved))
                clearSelection()
                if (pc === p) load(reset = true, keep = true)
            }
        }
    }

    /**
     * 옮겨 갈 폴더 고르기(유리 대화 상자): 그 허브의 맨 위부터 폴더만 보인다. 빵 부스러기로 위로, 새 폴더, "여기로 옮기기".
     * 옮길 폴더 자신과 그 아래로는 들어가지 못한다(흐리게). 잠긴 폴더는 자물쇠로 보이고 열려면 PIN(askOpen) — 2.2.1 허브("move2")는
     * 잠긴 폴더 안팎·사이로도 옮긴다. 허브가 거부할 곳(CloudMove.blocked: 예전 허브의 잠긴 폴더 안팎, 잠긴 폴더를 다른 잠긴 폴더 안에)과
     * 고른 것들이 모두 이미 있는 폴더에서는 "여기로 옮기기"를 막고 까닭을 보인다.
     */
    private inner class MovePicker(private val p: Pairing, private val moving: List<Pic>, private val done: (String) -> Unit) {
        private val d = resources.displayMetrics.density
        private var dir = ""
        private var gen = 0
        private val paths = moving.map { it.target }
        private val src = sources(p, moving)
        private val crumbRow = LinearLayout(this@CloudActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        private val crumbScroll = HorizontalScrollView(this@CloudActivity).apply {
            isHorizontalScrollBarEnabled = false
            addView(crumbRow, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        private val hint = TextView(this@CloudActivity).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(ContextCompat.getColor(context, R.color.secondary))
            setPadding((4 * d).toInt(), (2 * d).toInt(), (4 * d).toInt(), (4 * d).toInt())
            visibility = View.GONE
        }
        private val rows = LinearLayout(this@CloudActivity).apply { orientation = LinearLayout.VERTICAL }
        private val scroll = ScrollView(this@CloudActivity).apply {
            setBackgroundResource(R.drawable.bg_thumb)
            clipToOutline = true
            addView(rows)
        }
        /** 고른 것들이 모두 한 폴더에 있으면 그 폴더(소문자). */
        private val sameParent = paths.map { CloudPath.parent(it).lowercase(Locale.ROOT) }.distinct().singleOrNull()
        private lateinit var dlg: AlertDialog

        fun show() {
            val box = LinearLayout(this@CloudActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((20 * d).toInt(), (4 * d).toInt(), (20 * d).toInt(), 0)
            }
            box.addView(crumbScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (40 * d).toInt()))
            box.addView(hint)
            val h = minOf((360 * d).toInt(), (resources.displayMetrics.heightPixels * 0.5f).toInt())
            box.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h).apply { topMargin = (6 * d).toInt() })
            dlg = AlertDialog.Builder(this@CloudActivity).setTitle(getString(R.string.move_title, moving.size)).setView(box)
                .setPositiveButton(R.string.move_here, null)
                .setNeutralButton(R.string.cloud_new_folder, null)
                .setNegativeButton(R.string.cancel, null).create().secure()
            dlg.setOnShowListener {
                dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { dlg.dismiss(); done(dir) }
                dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    askNewFolder(dir) { made -> if (dlg.isShowing) { if (made != null) go(made) else load() } }
                }
                go("")
            }
            dlg.show()
        }

        private fun go(to: String) {
            dir = to
            renderCrumbs()
            val vaultBlocked = CloudMove.blocked(src, vault.mountOf(p.pcIp, to), move2, ranges)
            val here = sameParent == to.lowercase(Locale.ROOT)
            dlg.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = !CloudMove.inside(to, paths) && !here && !vaultBlocked
            hint.visibility = if (vaultBlocked) View.VISIBLE else View.GONE
            // 잠긴 폴더 자체를 잠긴 폴더 안에 넣기는 늘 안 되고(§18.1), 그 밖의 안팎 옮기기는 예전 허브만 못 한다.
            if (vaultBlocked) hint.text = getString(if (src.any { it.lockedDir }) R.string.move_vault_into_vault else R.string.move_needs_hub)
            load()
        }

        private fun renderCrumbs() {
            crumbRow.removeAllViews()
            val segs = if (dir.isEmpty()) emptyList() else dir.split('/')
            val labels = listOf(p.pcName) + segs
            labels.forEachIndexed { i, label ->
                if (i > 0) crumbRow.addView(crumbChevron())
                val target = segs.take(i).joinToString("/")
                val locked = i > 0 && vault.mountOf(p.pcIp, target) == target
                crumbRow.addView(crumbLabel(label, last = i == labels.size - 1, locked = locked) { go(target) })
            }
            crumbScroll.post { crumbScroll.fullScroll(View.FOCUS_RIGHT) }
        }

        /** 이 폴더의 하위 폴더(모든 쪽, 많아도 20쪽 = 1만 개까지). */
        private fun load() {
            val g = ++gen
            val at = dir
            rows.removeAllViews()
            rows.addView(row(R.drawable.ic_folder, getString(R.string.cloud_loading), null, enabled = false, onClick = null))
            io.execute {
                val got = ArrayList<CloudItem>()
                val err = try {
                    var cursor = 0
                    for (page in 0 until 20) {
                        val pg = Net.cloudList(p, at, cursor)
                        pg.items.filterTo(got) { it.dir }
                        cursor = pg.next?.takeIf { it > cursor } ?: break
                    }
                    null
                } catch (e: Exception) { e }
                ui.post { if (!isDestroyed && g == gen && dlg.isShowing) render(at, got, err) }
            }
        }

        private fun render(at: String, folders: List<CloudItem>, err: Exception?) {
            rows.removeAllViews()
            if (err != null) {
                rows.addView(row(R.drawable.ic_retry, getString(R.string.cloud_load_failed), Errors.text(this@CloudActivity, err),
                    enabled = true) { load() })
                // 잠긴 폴더 안인데 열쇠가 끝났다: PIN을 다시 묻고 다시 읽는다.
                if ((err as? ProtocolException)?.code == "vault_locked") {
                    (Vault.lockedMount((err as? HubRefusal)?.reply) ?: vault.mountOf(p.pcIp, at))
                        ?.let { m -> askOpen(m) { if (dlg.isShowing) load() } }
                }
                return
            }
            if (folders.isEmpty()) {
                rows.addView(row(R.drawable.ic_folder, getString(R.string.move_no_folders), null, enabled = false, onClick = null))
                return
            }
            for (f in folders) {
                val target = CloudPath.join(at, f.name)
                if (f.locked) vault.learn(p.pcIp, target)
                val blocked = CloudMove.inside(target, paths) || f.busy || f.stalled
                rows.addView(row(if (f.locked) R.drawable.ic_lock else R.drawable.ic_folder, f.name, null, enabled = !blocked) {
                    if (f.locked && !vault.has(p.pcIp, target)) askOpen(target) { if (dlg.isShowing) go(target) } else go(target)
                })
            }
        }

        /** 목록 한 줄(클라우드 목록 줄과 같은 모양). 누를 수 없는 폴더(옮길 것 자신·그 아래, 잠그는 중)는 흐리게. */
        private fun row(icon: Int, title: String, sub: String?, enabled: Boolean, onClick: (() -> Unit)?): View {
            val v = LayoutInflater.from(this@CloudActivity).inflate(R.layout.item_cloud, rows, false)
            v.findViewById<ImageView>(R.id.icon).setImageResource(icon)
            v.findViewById<TextView>(R.id.name).text = title
            v.findViewById<TextView>(R.id.sub).apply { text = sub ?: ""; visibility = if (sub == null) View.GONE else View.VISIBLE }
            val tappable = enabled && onClick != null
            v.findViewById<View>(R.id.trail).visibility = if (tappable && icon != R.drawable.ic_retry) View.VISIBLE else View.GONE
            v.alpha = if (onClick != null && !enabled) 0.4f else 1f
            v.isClickable = tappable
            v.isFocusable = tappable
            if (tappable) v.setOnClickListener { onClick?.invoke() }
            return v
        }
    }

    // ---------- 끌어다 놓기로 옮기기(2.2.1) ----------

    /**
     * 고르기 모드에서 이미 고른 항목을 길게 눌러 끌면 고른 것 전부가 끌린다(View.startDragAndDrop, 이 앱 안에서만 — localState로 알아본다).
     * 목록 위에서는 손가락 아래 폴더 줄·칸에, 위 경로(빵 부스러기) 조각 위에서는 그 폴더에 놓는다. 놓을 수 있는 폴더는 옅게,
     * 손가락 아래 것은 짙게 강조한다. 위·아래 끝 가까이에서는 저절로 내린다(끄는 동안에만 한 프레임에 한 번).
     * 놓으면 옮기기(§16·§18.1)와 같은 길로 옮긴다(잠긴 폴더에 놓았는데 열쇠가 없으면 먼저 PIN).
     */
    private inner class MoveDrag : View.OnDragListener {
        /** 지금 끄는 것(끄는 동안만). */
        var moving: List<Pic>? = null
            private set
        /** 손가락 아래의 놓을 수 있는 폴더(키). */
        var hover: String? = null
            private set
        private var y = -1f

        private val autoScroll = object : Runnable {
            override fun run() {
                if (moving == null) return
                val d = resources.displayMetrics.density
                if (y >= 0f) {
                    val dy = CloudSelect.autoScroll(y, list.height, 56 * d, (14 * d).toInt())
                    if (dy != 0 && list.canScrollVertically(dy)) list.scrollBy(0, dy)
                }
                list.postOnAnimation(this)
            }
        }

        /** 길게 누른 줄(v)에서 끌기를 시작한다. 고른 것이 없거나 옮길 수 없는 허브면 false(부른 쪽이 범위 고르기를 한다). */
        fun start(v: View): Boolean {
            if (!canMove || moving != null) return false
            val sel = selectedItems()
            if (sel.isEmpty()) return false
            moving = sel // 끌기 시작 알림(STARTED)이 오기 전에 — 경로 조각이 놓을 수 있는지 그때 본다
            val ok = try { v.startDragAndDrop(null, Shadow(v, sel.size), MoveDragState, 0) } catch (e: Exception) { false }
            if (!ok) { moving = null; return false }
            v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            adapter.dropChanged()
            list.postOnAnimation(autoScroll)
            return true
        }

        fun stop() {
            if (moving == null) return
            moving = null
            hover = null
            y = -1f
            list.removeCallbacks(autoScroll)
            adapter.dropChanged()
        }

        /**
         * dest 폴더에 놓을 수 있는지: 폴더이고(잠그는 중·멈춤 아님), 옮길 것 자신·그 아래가 아니고, 고른 것이 모두 이미 거기 있는 것이
         * 아니고, 잠긴 폴더 규칙(CloudMove.blocked)에 걸리지 않는다. destItem은 목록의 그 폴더(경로 조각이면 null).
         */
        fun canDrop(dest: String, destItem: CloudItem?): Boolean {
            val m = moving ?: return false
            val p = pc ?: return false
            if (destItem != null && (!destItem.dir || destItem.busy || destItem.stalled)) return false
            val paths = m.map { it.target }
            if (CloudMove.inside(dest, paths)) return false
            if (m.all { CloudPath.parent(it.target).equals(dest, ignoreCase = true) }) return false
            return !CloudMove.blocked(sources(p, m), destMount(p, dest, destItem), move2, ranges)
        }

        /** 놓음: 그 폴더로 옮긴다. 잠긴 폴더(또는 그 안)인데 열쇠가 없으면 먼저 PIN으로 연다. */
        fun dropOn(dest: String, destItem: CloudItem?) {
            val p = pc ?: return
            val m = moving ?: return
            if (!canDrop(dest, destItem)) return
            val paths = m.map { it.target }
            val mount = destMount(p, dest, destItem)
            if (mount != null && !vault.has(p.pcIp, mount)) askOpen(mount) { doMove(p, paths, dest) } else doMove(p, paths, dest)
        }

        /** 목록 안 (x, y) 아래의 놓을 수 있는 폴더. */
        private fun folderAt(x: Float, y: Float): Pair<String, CloudItem>? {
            val child = list.findChildViewUnder(x, y) ?: return null
            val pos = list.getChildAdapterPosition(child)
            val r = adapter.rows.getOrNull(pos) as? Row.Item ?: return null
            val key = targetOf(r.it)
            return if (canDrop(key, r.it)) key to r.it else null
        }

        private fun setHover(k: String?) {
            if (k == hover) return
            val old = hover
            hover = k
            adapter.dropChanged(listOfNotNull(old, k))
        }

        override fun onDrag(v: View, e: DragEvent): Boolean {
            if (e.localState !== MoveDragState) return false
            when (e.action) {
                DragEvent.ACTION_DRAG_STARTED -> return true
                DragEvent.ACTION_DRAG_LOCATION -> { y = e.y; setHover(folderAt(e.x, e.y)?.first) }
                DragEvent.ACTION_DRAG_EXITED -> { y = -1f; setHover(null) }
                DragEvent.ACTION_DROP -> {
                    val f = folderAt(e.x, e.y) ?: return false
                    dropOn(f.first, f.second)
                    return true
                }
                DragEvent.ACTION_DRAG_ENDED -> stop()
            }
            return true
        }
    }

    /** 경로 조각(그 위 폴더)에 놓기: 끄는 동안 놓을 수 있으면 옅게, 손가락이 올라오면 짙게 강조. */
    private fun crumbDrop(target: String) = View.OnDragListener { v, e ->
        if (e.localState !== MoveDragState) return@OnDragListener false
        when (e.action) {
            DragEvent.ACTION_DRAG_STARTED -> moveDrag.canDrop(target, null).also { if (it) v.setBackgroundResource(R.drawable.bg_drop_ok) }
            DragEvent.ACTION_DRAG_ENTERED -> { v.setBackgroundResource(R.drawable.bg_drop_hover); true }
            DragEvent.ACTION_DRAG_EXITED -> { v.setBackgroundResource(R.drawable.bg_drop_ok); true }
            DragEvent.ACTION_DROP -> { moveDrag.dropOn(target, null); true }
            DragEvent.ACTION_DRAG_ENDED -> { v.setBackgroundResource(R.drawable.bg_btn_ghost); true }
            else -> true
        }
    }

    /** 끄는 동안 손가락 아래에 보일 모양: 누른 줄·칸을 줄여 유리 카드 위에 그리고, 오른쪽 위에 고른 개수. */
    private inner class Shadow(v: View, private val count: Int) : View.DragShadowBuilder(v) {
        private val d = resources.displayMetrics.density
        private val sc = minOf(1f, 240 * d / maxOf(1, v.width), 140 * d / maxOf(1, v.height)) * 0.92f
        private val pad = (14 * d).toInt()
        private val card = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(this@CloudActivity, R.color.glassStrong) }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = d
            color = ContextCompat.getColor(this@CloudActivity, R.color.separator)
        }
        private val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(this@CloudActivity, R.color.accent) }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(this@CloudActivity, R.color.onAccent)
            textSize = 12 * d
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }

        override fun onProvideShadowMetrics(size: Point, touch: Point) {
            val w = (view.width * sc).toInt() + pad * 2
            val h = (view.height * sc).toInt() + pad * 2
            size.set(maxOf(1, w), maxOf(1, h))
            touch.set(size.x / 2, size.y / 2)
        }

        override fun onDrawShadow(canvas: Canvas) {
            val w = view.width * sc
            val h = view.height * sc
            val r = 10 * d
            val half = pad / 2f
            canvas.drawRoundRect(half, half, pad + w + half, pad + h + half, r, r, card)
            canvas.drawRoundRect(half, half, pad + w + half, pad + h + half, r, r, edge)
            canvas.save()
            canvas.translate(pad.toFloat(), pad.toFloat())
            canvas.scale(sc, sc)
            view.draw(canvas)
            canvas.restore()
            if (count > 1) {
                val label = if (count > 99) "99+" else count.toString()
                val br = 11 * d
                val cx = pad + w + half - br * 0.4f
                val cy = br + 1
                canvas.drawCircle(cx, cy, br, badge)
                canvas.drawText(label, cx, cy - (text.descent() + text.ascent()) / 2, text)
            }
        }
    }

    // ---------- 이름 바꾸기(2.2.1, §18.2) ----------

    /** 고른 것이 하나면 이름 칸, 여럿이면 방식 고르기와 미리보기. */
    private fun renameSelected() {
        val p = pc ?: return
        val sel = selectedItems()
        if (!canRename || sel.isEmpty()) return
        if (sel.size == 1) renameOne(p, sel[0]) else RenameDialog(p, sel).show()
    }

    /** 미리보기 검사에 쓸 폴더별 이름(지금 목록에서 아는 것). */
    private fun siblings(): Map<String, List<String>> = items.groupBy({ CloudPath.parent(targetOf(it)) }, { it.name })

    /** 미리보기 줄의 문제 → 화면 문장. */
    private fun renameReason(code: String): String = getString(when (code) {
        CloudRename.EMPTY -> R.string.rename_empty
        CloudRename.TOO_LONG -> R.string.rename_too_long
        CloudRename.PART -> R.string.rename_part
        CloudRename.EXISTS -> R.string.err_exists
        CloudRename.DUPLICATE -> R.string.rename_duplicate
        CloudRename.UNCHANGED -> R.string.rename_unchanged
        else -> R.string.rename_bad
    })

    /**
     * 하나 이름 바꾸기: 이름 칸(확장자 앞까지 골라 둔 채로). 칠 때마다 규칙을 검사해 창 안에 이유를 보이고, 맞을 때만 바꾸기를 누를 수 있다.
     */
    private fun renameOne(p: Pairing, x: Pic) {
        val d = resources.displayMetrics.density
        val field = EditText(this).apply {
            setText(x.item.name)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine()
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            filters = arrayOf(android.text.InputFilter.LengthFilter(200))
            setSelection(0, CloudRename.split(x.item.name, x.item.dir).first.length)
        }
        val error = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(ContextCompat.getColor(context, R.color.red))
            setPadding(0, (6 * d).toInt(), 0, 0)
            visibility = View.GONE
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((22 * d).toInt(), (8 * d).toInt(), (22 * d).toInt(), 0)
            addView(field)
            addView(error)
        }
        val sib = siblings()
        val dlg = AlertDialog.Builder(this).setTitle(R.string.rename_title).setView(box)
            .setPositiveButton(R.string.rename_do, null).setNegativeButton(R.string.cancel, null).create().secure()
        fun plan() = CloudRename.plan(listOf(x.target to x.item.dir), sib) { _, _, _ -> field.text.toString() }[0]
        fun check() {
            val pl = plan()
            val bad = pl.problem?.takeIf { it != CloudRename.UNCHANGED }
            error.text = bad?.let { renameReason(it) } ?: ""
            error.visibility = if (bad == null) View.GONE else View.VISIBLE
            dlg.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = pl.problem == null
        }
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { check() }
        })
        dlg.setOnShowListener {
            check()
            field.requestFocus()
            dlg.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pl = plan()
                if (pl.problem != null) return@setOnClickListener
                dlg.dismiss()
                doRename(p, CloudRename.sendable(listOf(pl)))
            }
        }
        dlg.show()
    }

    /** 이름 바꾸기를 보내고(200개씩) 결과를 알린다("n개 바꿈", 못 바꾼 것은 이유별 개수). 잠긴 폴더의 열쇠가 끝났으면 PIN을 묻고 다시. */
    private fun doRename(p: Pairing, todo: List<CloudRename.Item>) {
        if (todo.isEmpty()) return
        io.execute {
            val r = try { Net.cloudRename(p, todo) } catch (e: Exception) {
                ui.post {
                    if (isDestroyed) return@post
                    toast(getString(R.string.failed, Errors.text(this, e)))
                    if ((e as? ProtocolException)?.code == "vault_locked") {
                        (Vault.lockedMount((e as? HubRefusal)?.reply) ?: vault.mountOf(p.pcIp, todo[0].path))
                            ?.let { m -> askOpen(m) { doRename(p, todo) } }
                    }
                }
                return@execute
            }
            ui.post {
                if (isDestroyed) return@post
                val reasons = CloudMove.failureCounts(r).map { (code, n) -> Errors.text(this, ProtocolException(code)) to n }
                toast(summary(r.moved.size, R.string.rename_done_n, r.failed.size, reasons, R.string.rename_failed_n))
                clearSelection()
                if (pc === p) load(reset = true, keep = true)
            }
        }
    }

    /**
     * 여러 개 이름 바꾸기 창(유리 대화 상자): 위에 방식 칩(찾아 바꾸기·앞에 붙이기·뒤에 붙이기·번호 매기기·대소문자), 그 아래 방식별 칸,
     * 맨 아래 미리보기(이전 → 새 이름, 규칙에 어긋나면 빨강과 이유, 그대로면 흐리게). 칠 때마다 다시 계산한다(CloudRename.plan).
     * 확인 버튼에 바꿀 개수가 보이고, 바꿀 것이 없으면 막는다.
     */
    private inner class RenameDialog(private val p: Pairing, private val sel: List<Pic>) {
        private val d = resources.displayMetrics.density
        private var mode = CloudRename.Mode.REPLACE
        private var case = CloudRename.Case.LOWER
        private var matchCase = false
        private val sib = siblings()
        /** 칸에 칠 때마다 조금 뒤 다시 계산(칸들보다 먼저 만든다 — 기본값을 넣을 때도 불린다). */
        private val recalc = Runnable { recompute() }
        private var plan: List<CloudRename.Plan> = emptyList()
        private val box = LinearLayout(this@CloudActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * d).toInt(), (4 * d).toInt(), (20 * d).toInt(), 0)
        }
        private val modeChips = LinearLayout(this@CloudActivity).apply { orientation = LinearLayout.HORIZONTAL }
        private val fields = LinearLayout(this@CloudActivity).apply { orientation = LinearLayout.VERTICAL }
        private val find = field(R.string.rename_find_hint)
        private val replace = field(R.string.rename_replace_hint)
        private val text = field(R.string.rename_text_hint)
        private val pattern = field(R.string.rename_pattern_hint).apply { setText(getString(R.string.rename_pattern_default)) }
        private val start = field(R.string.rename_start_hint).apply { inputType = InputType.TYPE_CLASS_NUMBER; setText("1") }
        private val digits = field(R.string.rename_digits_hint).apply { inputType = InputType.TYPE_CLASS_NUMBER; setText("3") }
        private val summaryView = TextView(this@CloudActivity).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(ContextCompat.getColor(context, R.color.secondary))
            setPadding(0, (10 * d).toInt(), 0, (6 * d).toInt())
        }
        private val preview = LinearLayout(this@CloudActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12 * d).toInt(), (6 * d).toInt(), (12 * d).toInt(), (6 * d).toInt())
        }
        private val previewScroll = ScrollView(this@CloudActivity).apply {
            setBackgroundResource(R.drawable.bg_thumb)
            clipToOutline = true
            addView(preview)
        }
        private lateinit var dlg: AlertDialog

        private fun field(hint: Int) = EditText(this@CloudActivity).apply {
            this.hint = getString(hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine()
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            filters = arrayOf(android.text.InputFilter.LengthFilter(150))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { ui.removeCallbacks(recalc); ui.postDelayed(recalc, 120) }
            })
        }

        /** shadcn Toggle 모양의 칩(종류 칩과 같은 모양). */
        private fun chip(label: Int, selected: Boolean, onClick: () -> Unit) = TextView(this@CloudActivity).apply {
            text = getString(label)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(ContextCompat.getColorStateList(context, R.color.chip_text))
            setBackgroundResource(R.drawable.bg_chip_toggle)
            setPadding((12 * d).toInt(), (6 * d).toInt(), (12 * d).toInt(), (6 * d).toInt())
            isSelected = selected
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginEnd = (6 * d).toInt() }
            setOnClickListener { onClick() }
        }

        fun show() {
            box.addView(HorizontalScrollView(this@CloudActivity).apply {
                isHorizontalScrollBarEnabled = false
                addView(modeChips)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = (6 * d).toInt() })
            box.addView(fields)
            box.addView(summaryView)
            val h = minOf((260 * d).toInt(), (resources.displayMetrics.heightPixels * 0.34f).toInt())
            box.addView(previewScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h))
            buildModes()
            buildFields()
            dlg = AlertDialog.Builder(this@CloudActivity).setTitle(getString(R.string.rename_n_title, sel.size))
                .setView(ScrollView(this@CloudActivity).apply { addView(box) })
                .setPositiveButton(R.string.rename_do, null).setNegativeButton(R.string.cancel, null).create().secure()
            dlg.setOnShowListener {
                recompute()
                dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    recompute()
                    val todo = CloudRename.sendable(plan)
                    if (todo.isEmpty()) return@setOnClickListener
                    dlg.dismiss()
                    doRename(p, todo)
                }
            }
            dlg.setOnDismissListener { ui.removeCallbacks(recalc) }
            dlg.show()
        }

        private fun buildModes() {
            modeChips.removeAllViews()
            val modes = listOf(CloudRename.Mode.REPLACE to R.string.rename_mode_replace, CloudRename.Mode.PREFIX to R.string.rename_mode_prefix,
                CloudRename.Mode.SUFFIX to R.string.rename_mode_suffix, CloudRename.Mode.NUMBER to R.string.rename_mode_number,
                CloudRename.Mode.CASE to R.string.rename_mode_case)
            for ((m, label) in modes) modeChips.addView(chip(label, m == mode) {
                if (mode != m) { mode = m; buildModes(); buildFields(); recompute() }
            })
        }

        /** 방식별 칸. 칸은 다시 만들지 않고 붙였다 뗀다(친 글이 남게). 먼저 옛 부모(안쪽 줄)에서 떼어 둔다. */
        private fun buildFields() {
            fields.removeAllViews()
            for (v in listOf(find, replace, text, pattern, start, digits)) (v.parent as? ViewGroup)?.removeView(v)
            when (mode) {
                CloudRename.Mode.REPLACE -> {
                    fields.addView(find)
                    fields.addView(replace)
                    fields.addView(LinearLayout(this@CloudActivity).apply {
                        setPadding(0, (6 * d).toInt(), 0, 0)
                        addView(chip(R.string.rename_match_case, matchCase) { matchCase = !matchCase; buildFields(); recompute() })
                    })
                }
                CloudRename.Mode.PREFIX, CloudRename.Mode.SUFFIX -> fields.addView(text)
                CloudRename.Mode.NUMBER -> {
                    fields.addView(pattern)
                    fields.addView(LinearLayout(this@CloudActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        addView(start, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = (8 * d).toInt() })
                        addView(digits, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    })
                    fields.addView(TextView(this@CloudActivity).apply {
                        text = getString(R.string.rename_pattern_help)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                        setTextColor(ContextCompat.getColor(context, R.color.secondary))
                        setPadding((4 * d).toInt(), (2 * d).toInt(), 0, 0)
                    })
                }
                CloudRename.Mode.CASE -> fields.addView(LinearLayout(this@CloudActivity).apply {
                    setPadding(0, (4 * d).toInt(), 0, 0)
                    for ((c, label) in listOf(CloudRename.Case.LOWER to R.string.rename_case_lower,
                        CloudRename.Case.UPPER to R.string.rename_case_upper, CloudRename.Case.TITLE to R.string.rename_case_title)) {
                        addView(chip(label, c == case) { if (case != c) { case = c; buildFields(); recompute() } })
                    }
                })
            }
        }

        private fun rule(): CloudRename.Rule = CloudRename.Rule(
            mode = mode, find = find.text.toString(), replace = replace.text.toString(), matchCase = matchCase,
            text = text.text.toString(), pattern = pattern.text.toString(),
            start = start.text.toString().toIntOrNull()?.coerceIn(0, 9_999_999) ?: 1,
            digits = digits.text.toString().toIntOrNull()?.coerceIn(1, CloudRename.MAX_DIGITS) ?: 1, case = case,
        )

        private fun recompute() {
            val r = rule()
            plan = CloudRename.plan(sel.map { it.target to it.item.dir }, sib) { old, dir, i -> CloudRename.apply(r, old, dir, i) }
            val ok = plan.count { it.problem == null }
            val same = plan.count { it.problem == CloudRename.UNCHANGED }
            val bad = plan.size - ok - same
            summaryView.text = getString(R.string.rename_summary, ok, same, bad)
            preview.removeAllViews()
            val shown = plan.take(PREVIEW_MAX)
            for (pl in shown) preview.addView(previewRow(pl))
            if (plan.size > shown.size) preview.addView(TextView(this@CloudActivity).apply {
                text = getString(R.string.rename_more, plan.size - shown.size)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(ContextCompat.getColor(context, R.color.secondary))
                setPadding(0, (6 * d).toInt(), 0, (2 * d).toInt())
            })
            if (::dlg.isInitialized) dlg.getButton(AlertDialog.BUTTON_POSITIVE)?.let { b ->
                b.isEnabled = ok > 0
                b.text = if (ok > 0) getString(R.string.rename_do_n, ok) else getString(R.string.rename_do)
            }
        }

        /** 미리보기 한 줄: 이전 이름(흐리게) → 새 이름. 문제가 있으면 빨강과 이유, 그대로면 흐리게. */
        private fun previewRow(pl: CloudRename.Plan): View = LinearLayout(this@CloudActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (5 * d).toInt(), 0, (5 * d).toInt())
            val red = ContextCompat.getColor(context, R.color.red)
            val sec = ContextCompat.getColor(context, R.color.secondary)
            addView(TextView(context).apply {
                text = pl.old
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(sec)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
            })
            addView(TextView(context).apply {
                text = getString(R.string.rename_arrow, pl.new)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(when (pl.problem) {
                    null -> ContextCompat.getColor(context, R.color.label)
                    CloudRename.UNCHANGED -> sec
                    else -> red
                })
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.MIDDLE
            })
            pl.problem?.takeIf { it != CloudRename.UNCHANGED }?.let { code ->
                addView(TextView(context).apply {
                    text = renameReason(code)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(red)
                })
            }
        }
    }

    // ---------- 전송 ----------

    /** 전송 줄을 누름: 마지막 실패 이유와 다시 시도·취소. 보내는 중이면 취소만. */
    private fun jobMenu(j: OutboxJob) {
        val b = AlertDialog.Builder(this).setTitle(SendService.what(this, j))
        if (j.error != null) b.setMessage(SendService.reason(this, j))
        if (j.state != OutboxJob.SENDING) {
            b.setPositiveButton(if (j.failed) R.string.cloud_retry else R.string.outbox_retry_now) { _, _ -> SendService.retry(this, j.id) }
        }
        b.setNegativeButton(if (j.failed) R.string.outbox_remove else R.string.cloud_job_cancel) { _, _ -> SendService.cancel(this, j.id) }
        b.setNeutralButton(R.string.close, null)
        b.showSecure()
    }

    private fun jobStatus(j: OutboxJob): String {
        val now = System.currentTimeMillis()
        val up = j.kind == OutboxJob.CLOUD_UP
        return when {
            j.failed -> getString(R.string.cloud_job_failed, SendService.reason(this, j))
            j.state == OutboxJob.SENDING -> getString(if (up) R.string.cloud_job_uploading else R.string.cloud_job_downloading,
                if (j.size > 0) (Outbox.progressOf(j.id) * 100 / j.size).toInt().coerceIn(0, 100) else 0)
            j.attempts > 0 && j.nextAt > now -> getString(R.string.cloud_job_retry_in,
                DateUtils.formatElapsedTime((j.nextAt - now + 999) / 1000))
            else -> getString(if (up) R.string.cloud_job_queued_up else R.string.cloud_job_queued_down)
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    // ---------- 미리보기 ----------

    /**
     * 미리보기를 물어볼 항목인지: "cloud2" 허브의 사진·영상 파일. 예전 허브는 잠긴 폴더 안에서 사진만 만든다(§15).
     * 2.2 허브는 영상도 미리 만들어 둔다(§17.4) — 항목의 variants에 thumb이 있으면 잠긴 폴더 안 영상도 묻는다.
     */
    private fun wantsThumb(item: CloudItem): Boolean {
        if (item.dir || !FileKinds.previewable(item.kindOrGuess)) return false
        val p = pc ?: return false
        // 2.3.0: 사본에 미리보기가 있으면 거기서(오프라인 사본은 그것뿐)
        val local = localOf(targetOf(item), item)
        if (offline || local?.tobj != null) return local?.tobj != null
        if (!cloud2) return false
        return item.kindOrGuess == FileKinds.IMAGE || CloudVariants.THUMB in item.variants || vault.mountOf(p.pcIp, targetOf(item)) == null
    }

    private fun bindThumb(view: ImageView, item: CloudItem, px: Int, onBitmap: (Bitmap) -> Unit) =
        bindThumbAt(view, targetOf(item), item, px, onBitmap)

    /** 미리보기를 칸에 붙인다(경로를 이미 아는 곳 — 라이트박스 필름 띠). 메모리·캐시에 있으면 바로, 없으면 허브에서. */
    private fun bindThumbAt(view: ImageView, target: String, item: CloudItem, px: Int, onBitmap: (Bitmap) -> Unit) {
        val p = pc ?: return
        val key = Thumbs.cloudKey(p.pcIp, target, item, px)
        val inVault = vault.mountOf(p.pcIp, target) != null
        val app = applicationContext
        val local = localOf(target, item)?.takeIf { it.tobj != null }
        val off = offline
        Thumbs.bind(view, key, { job ->
            when {
                local != null -> Thumbs.mirror(app, p.pcIp, local, px, job)
                off -> null
                else -> Thumbs.cloud(app, p, target, px, key, inVault, job)
            }
        }, onBitmap)
    }

    // ---------- 목록 어댑터 ----------

    private inner class Rows : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var rows: List<Row> = emptyList()
            private set

        private val tHeader = 0
        private val tRow = 1
        private val tMore = 2

        /** 위치별 선택 키(항목 줄만, 나머지는 null) — 끌어서 고르기의 범위 계산에. */
        var keys: List<String?> = emptyList()
            private set
        private var positions: Map<String, Int> = emptyMap()

        fun setData(r: List<Row>) {
            rows = r
            keys = r.map { (it as? Row.Item)?.let { i -> targetOf(i.it) } }
            positions = HashMap<String, Int>().also { m -> keys.forEachIndexed { i, k -> if (k != null) m[k] = i } }
            notifyDataSetChanged()
        }

        /** 선택 모드가 켜지거나 꺼짐: 모든 줄의 선택 표시를 다시 그린다(미리보기는 그대로). */
        fun selectionModeChanged() = notifyItemRangeChanged(0, itemCount, SEL)

        /** 이 키들의 줄만 선택 표시를 다시 그린다. */
        fun selectionChanged(changed: Collection<String>) {
            for (k in changed) positions[k]?.let { notifyItemChanged(it, SEL) }
        }

        /** 끌어다 놓기가 시작·끝남(모든 줄) 또는 손가락 아래 폴더가 바뀜(그 줄들만): 놓을 수 있는 폴더 강조를 다시 그린다. */
        fun dropChanged(changed: Collection<String>? = null) {
            if (changed == null) notifyItemRangeChanged(0, itemCount, DROP)
            else for (k in changed) positions[k]?.let { notifyItemChanged(it, DROP) }
        }

        /** 놓을 수 있는 폴더 강조(끄는 동안만): 옅은 테두리, 손가락 아래면 짙게. 덮개(foreground)로 그려 선택 표시와 겹치지 않게. */
        fun bindDrop(holder: RecyclerView.ViewHolder, item: CloudItem) {
            val key = targetOf(item)
            val v = holder.itemView
            v.foreground = when {
                moveDrag.moving == null || !moveDrag.canDrop(key, item) -> null
                moveDrag.hover == key -> ContextCompat.getDrawable(v.context, R.drawable.bg_drop_hover)
                else -> ContextCompat.getDrawable(v.context, R.drawable.bg_drop_ok)
            }
        }

        inner class RowVH(v: View) : RecyclerView.ViewHolder(v) {
            val icon: ImageView = v.findViewById<ImageView>(R.id.icon).also { it.clipToOutline = true }
            val name: TextView = v.findViewById(R.id.name)
            val sub: TextView = v.findViewById(R.id.sub)
            val trail: ImageView = v.findViewById(R.id.trail)
            val check: ImageView = v.findViewById(R.id.check)
            val sync: ImageView = v.findViewById(R.id.sync)
        }

        inner class GridVH(v: View) : RecyclerView.ViewHolder(v) {
            val thumb: ImageView = v.findViewById(R.id.thumb)
            val icon: ImageView = v.findViewById(R.id.icon)
            val badge: ImageView = v.findViewById(R.id.badge)
            val name: TextView = v.findViewById(R.id.name)
            val sub: TextView = v.findViewById(R.id.sub)
            val selDim: View = v.findViewById(R.id.selDim)
            val check: ImageView = v.findViewById(R.id.check)
            val syncBadge: ImageView = v.findViewById(R.id.syncBadge)
        }

        inner class TextVH(val text: TextView) : RecyclerView.ViewHolder(text)

        override fun getItemCount() = rows.size

        override fun getItemViewType(position: Int) = when (rows[position]) {
            is Row.Header -> tHeader
            Row.More -> tMore
            is Row.Item -> if (layout.spanCount > 1) T_GRID else tRow
            else -> tRow
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val d = parent.resources.displayMetrics.density
            return when (viewType) {
                tRow -> RowVH(LayoutInflater.from(parent.context).inflate(R.layout.item_cloud, parent, false))
                T_GRID -> GridVH(LayoutInflater.from(parent.context).inflate(R.layout.item_cloud_grid, parent, false))
                tHeader -> TextVH(TextView(parent.context).apply {
                    // 홈의 "기기" 머리와 같은 모양
                    setPadding((20 * d).toInt(), (18 * d).toInt(), (20 * d).toInt(), (6 * d).toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(ContextCompat.getColor(context, R.color.secondary))
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                })
                else -> TextVH(TextView(parent.context).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, (16 * d).toInt(), 0, (16 * d).toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setTextColor(ContextCompat.getColor(context, R.color.secondary))
                    text = context.getString(R.string.cloud_load_more)
                    layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                })
            }
        }

        override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
            when (holder) {
                is RowVH -> Thumbs.unbind(holder.icon)
                is GridVH -> Thumbs.unbind(holder.thumb)
            }
        }

        /** 줄 앞 아이콘 칸을 기본(벡터 아이콘, 안쪽 여백·글자색 칠)으로 되돌린다. */
        private fun iconTile(v: ImageView, res: Int) {
            val pad = (8 * v.resources.displayMetrics.density).toInt()
            v.setPadding(pad, pad, pad, pad)
            v.scaleType = ImageView.ScaleType.FIT_CENTER
            v.imageTintList = ContextCompat.getColorStateList(v.context, R.color.label)
            v.setImageResource(res)
        }

        private fun itemIcon(item: CloudItem): Int = when {
            item.dir && item.locked -> R.drawable.ic_lock
            item.dir -> R.drawable.ic_folder
            else -> kindIcon(item.kindOrGuess)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
            val r = rows[position]
            if (payloads.isNotEmpty() && payloads.all { it == SEL || it == DROP }) {
                if (r is Row.Item) {
                    if (SEL in payloads) bindSel(holder, r.it)
                    if (DROP in payloads) bindDrop(holder, r.it)
                }
                return
            }
            onBindViewHolder(holder, position)
        }

        /** 선택 표시: 목록 줄은 끝의 체크 원과 옅은 덧칠(activated), 격자 칸은 체크 원과 어두운 덮개·테두리. */
        private fun bindSel(holder: RecyclerView.ViewHolder, item: CloudItem) {
            val on = targetOf(item) in selected
            when (holder) {
                is RowVH -> {
                    holder.itemView.isActivated = on
                    holder.check.visibility = if (selecting) View.VISIBLE else View.GONE
                    holder.check.setImageResource(if (on) R.drawable.sel_on else R.drawable.sel_off)
                    holder.trail.visibility = if (!selecting && item.dir) View.VISIBLE else View.GONE
                }
                is GridVH -> {
                    holder.selDim.visibility = if (on) View.VISIBLE else View.GONE
                    holder.check.visibility = if (selecting) View.VISIBLE else View.GONE
                    holder.check.setImageResource(if (on) R.drawable.sel_on else R.drawable.sel_off_photo)
                }
            }
            holder.itemView.contentDescription = if (selecting)
                getString(if (on) R.string.select_on_desc else R.string.select_off_desc, item.name) else null
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val r = rows[position]
            if (r is Row.Header) { (holder as TextVH).text.text = r.text; return }
            if (holder is GridVH && r is Row.Item) { bindGrid(holder, r.it); return }
            if (holder !is RowVH) return
            val ctx = holder.itemView.context
            holder.itemView.alpha = 1f
            holder.sub.setTextColor(ContextCompat.getColor(ctx, R.color.secondary))
            holder.trail.visibility = View.GONE
            holder.check.visibility = View.GONE
            holder.sync.visibility = View.GONE
            holder.itemView.isActivated = false
            holder.itemView.contentDescription = null
            holder.itemView.setOnLongClickListener(null)
            holder.itemView.foreground = null
            Thumbs.unbind(holder.icon)
            when (r) {
                is Row.Pc -> {
                    iconTile(holder.icon, R.drawable.ic_laptop)
                    holder.name.text = r.p.pcName
                    // 허브에 닿지 않지만 이 기기에 사본이 있으면 열 수 있다(오프라인 사본, §19.3)
                    val copy = noHub(r.p, r.state) && hasCopy(r.p)
                    val state = if (copy && r.state == PcState.UNREACHABLE) getString(R.string.cloud_pc_offline_copy) else getString(when (r.state) {
                        PcState.CHECKING -> R.string.cloud_pc_checking
                        PcState.ON -> R.string.cloud_pc_on
                        PcState.OFF -> R.string.cloud_pc_off
                        PcState.OLD -> R.string.err_cloud_unsupported
                        PcState.UNREACHABLE -> R.string.cloud_pc_unreachable
                        PcState.STORE_LOCKED -> if (unlockable(r.p.pcIp)) R.string.cloud_pc_store_strong else R.string.cloud_pc_store_locked
                        PcState.STORE_MISSING -> R.string.cloud_pc_store_missing
                    })
                    // 그 PC가 알린 용량(§19.1)을 한 줄 더
                    holder.sub.text = spaceText(r.p)?.let { state + "\n" + it } ?: state
                    val on = r.state == PcState.ON
                    val open = on || (r.state == PcState.STORE_LOCKED && unlockable(r.p.pcIp)) || copy
                    holder.itemView.alpha = if (open || r.state == PcState.CHECKING) 1f else 0.55f
                    holder.trail.visibility = if (open) View.VISIBLE else View.GONE
                    holder.itemView.setOnClickListener { pcClicked(r.p, r.state) }
                    holder.itemView.setOnLongClickListener { pcMenu(r.p, r.state); true }
                }
                is Row.Job -> {
                    val j = r.j
                    iconTile(holder.icon, if (j.kind == OutboxJob.CLOUD_UP) R.drawable.ic_cloud_upload else R.drawable.ic_download)
                    holder.name.text = SendService.what(ctx, j)
                    holder.sub.text = jobStatus(j)
                    if (j.failed) holder.sub.setTextColor(ContextCompat.getColor(ctx, R.color.red))
                    holder.itemView.alpha = if (j.failed || j.state == OutboxJob.SENDING) 1f else 0.7f
                    holder.itemView.setOnClickListener { jobMenu(j) }
                    holder.itemView.setOnLongClickListener { jobMenu(j); true }
                }
                is Row.Item -> {
                    val item = r.it
                    iconTile(holder.icon, itemIcon(item))
                    if (wantsThumb(item)) bindThumb(holder.icon, item, CloudThumb.LIST_PX) { bmp ->
                        holder.icon.setPadding(0, 0, 0, 0)
                        holder.icon.scaleType = ImageView.ScaleType.FIT_CENTER // 자르지 않고 전체를(2.1.0)
                        holder.icon.imageTintList = null
                        holder.icon.setImageBitmap(bmp)
                    }
                    holder.name.text = item.name
                    holder.sub.text = meta(item)
                    // 모든 기기 동기화 대상(§19.2). 오프라인 사본은 모두 그것이라 표시하지 않는다
                    holder.sync.visibility = if (item.sync && !offline) View.VISIBLE else View.GONE
                    holder.itemView.setOnClickListener { tapped(item) }
                    holder.itemView.setOnLongClickListener { v -> longPressed(v, item); true }
                    bindSel(holder, item)
                    bindDrop(holder, item)
                }
                is Row.Note -> {
                    iconTile(holder.icon, if (r.retry) R.drawable.ic_retry else R.drawable.ic_search)
                    holder.name.text = r.title
                    holder.sub.text = r.sub ?: ""
                    holder.sub.visibility = if (r.sub.isNullOrEmpty()) View.GONE else View.VISIBLE
                    holder.itemView.setOnClickListener { if (r.retry) load(reset = true, keep = false) }
                    return
                }
                else -> { }
            }
            holder.sub.visibility = View.VISIBLE
        }

        private fun bindGrid(h: GridVH, item: CloudItem) {
            Thumbs.unbind(h.thumb)
            h.thumb.setImageDrawable(null)
            h.icon.visibility = View.VISIBLE
            h.icon.setImageResource(itemIcon(item))
            h.badge.visibility = View.GONE
            h.syncBadge.visibility = if (item.sync && !offline) View.VISIBLE else View.GONE
            when {
                item.dir && item.locked -> { } // 자물쇠 아이콘 자체가 표시
                item.kindOrGuess == FileKinds.VIDEO -> { h.badge.setImageResource(R.drawable.ic_video); h.badge.visibility = View.VISIBLE }
            }
            if (wantsThumb(item)) bindThumb(h.thumb, item, CloudThumb.GRID_PX) { bmp ->
                h.thumb.setImageBitmap(bmp)
                h.icon.visibility = View.GONE
            }
            h.name.text = item.name
            h.sub.text = meta(item)
            h.itemView.setOnClickListener { tapped(item) }
            h.itemView.setOnLongClickListener { v -> longPressed(v, item); true }
            bindSel(h, item)
            bindDrop(h, item)
        }
    }
}
