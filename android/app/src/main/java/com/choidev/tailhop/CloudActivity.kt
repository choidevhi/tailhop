package com.choidev.tailhop

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
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
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
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
    }

    private enum class PcState { CHECKING, ON, OFF, OLD, UNREACHABLE }

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
    private val adapter = Rows()
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

    private val searching: Boolean get() = query.isNotEmpty() || kind != FileKinds.ALL
    private val cloud2: Boolean get() = pc?.let { "cloud2" in (pcCaps[it.pcIp] ?: emptySet()) } == true

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

        findViewById<View>(R.id.backBtn).setOnClickListener { goBack() }
        findViewById<View>(R.id.refreshBtn).setOnClickListener { if (pc == null) checkPcs() else load(reset = true, keep = true) }
        newFolderBtn.setOnClickListener { askNewFolder() }
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

        val ip = savedInstanceState?.getString(S_IP) ?: intent.getStringExtra(EXTRA_IP)
        fromPicker = savedInstanceState?.getBoolean(S_PICKER) ?: (intent.getStringExtra(EXTRA_IP) == null)
        val p = ip?.let { Store.get(this, it) }
        if (p != null) {
            pc = p
            path = savedInstanceState?.getString(S_PATH)?.takeIf { CloudPath.isValid(it) } ?: ""
            ensureCaps(p)
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
        Outbox.removeListener(outboxListener)
        ui.removeCallbacks(searchRun)
        io.shutdownNow()
        super.onDestroy()
    }

    // ---------- 이동 ----------

    private fun goBack() {
        when {
            pc != null && searching -> clearSearch(reload = true)
            pc != null && path.isNotEmpty() -> open(CloudPath.parent(path))
            pc != null && fromPicker -> { pc = null; gen++; items = emptyList(); next = null; loadError = null; checkPcs(); render() }
            else -> finish()
        }
    }

    private fun browse(p: Pairing) {
        pc = p
        path = ""
        clearSearch(reload = false)
        ensureCaps(p)
        load(reset = true, keep = false)
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
                var caps: Set<String>? = null
                val st = try {
                    val h = Net.hello(p, null, null)
                    caps = h?.caps
                    when {
                        h == null || "cloud" !in h.caps -> PcState.OLD
                        h.cloud -> PcState.ON
                        else -> PcState.OFF
                    }
                } catch (e: Exception) { PcState.UNREACHABLE }
                ui.post {
                    if (isDestroyed) return@post
                    pcStates[p.pcIp] = st
                    caps?.let { pcCaps[p.pcIp] = it }
                    if (pc == null) render()
                }
            }
        }
        render()
    }

    /** 바로 들어온 PC(EXTRA_IP)는 hello를 따로 해 "cloud2"를 안다. 모르면 예전 목록만. */
    private fun ensureCaps(p: Pairing) {
        if (pcCaps.containsKey(p.pcIp)) return
        io.execute {
            val caps = try { Net.hello(p, null, null)?.caps } catch (e: Exception) { null } ?: return@execute
            ui.post { if (!isDestroyed) { pcCaps[p.pcIp] = caps; if (pc === p) { applyLayout(); render() } } }
        }
    }

    private fun pcClicked(p: Pairing, st: PcState) {
        when (st) {
            PcState.ON -> browse(p)
            PcState.OFF -> toast(getString(R.string.cloud_pc_off_hint))
            PcState.OLD -> toast(getString(R.string.err_cloud_unsupported))
            PcState.UNREACHABLE -> { toast(getString(R.string.err_connect_failed)); checkPcs() }
            PcState.CHECKING -> { }
        }
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
        if (reset && !keep) { items = emptyList(); next = null; partial = false }
        loading = true
        loadError = null
        render()
        val dir = path
        val q = query
        val k = kind
        val search = searching && cloud2
        io.execute {
            val result = try {
                if (search) Net.cloudSearch(p, dir, q, k, cursor) else SearchPage(Net.cloudList(p, dir, cursor), false)
            } catch (e: Exception) {
                ui.post {
                    if (isDestroyed || g != gen) return@post
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
                }
                return@execute
            }
            ui.post {
                if (isDestroyed || g != gen) return@post
                loading = false
                val got = result.page.items
                got.filter { it.locked }.forEach { vault.learn(p.pcIp, CloudPath.join(dir, it.path ?: it.name)) }
                items = if (reset) got else items + got
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
        val cols = if (grid && cloud2) CloudGrid.columns((resources.displayMetrics.widthPixels / resources.displayMetrics.density).toInt()) else 1
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
        newFolderBtn.visibility = if (p == null) View.GONE else View.VISIBLE
        uploadBtn.visibility = if (p == null) View.GONE else View.VISIBLE
        crumbsRow.visibility = if (p == null) View.GONE else View.VISIBLE
        val c2 = p != null && cloud2
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
        subtitle.text = if (loading && items.isEmpty()) getString(R.string.cloud_loading) else getString(R.string.cloud_title)
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
                else -> setEmpty(R.drawable.ic_folder, emptyTitleText, getString(R.string.cloud_empty_sub), false)
            }
            showEmpty(true)
        } else showEmpty(false)
    }

    /** 목록을 바꾼다. 줄이 없으면 유리 카드도 숨긴다(빈 테두리만 남지 않게). */
    private fun setRows(rows: List<Row>) {
        adapter.rows = rows
        adapter.notifyDataSetChanged()
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
        val d = resources.displayMetrics.density
        val segs = if (path.isEmpty()) emptyList() else path.split('/')
        val labels = listOf(p.pcName) + segs
        labels.forEachIndexed { i, label ->
            if (i > 0) crumbs.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_chevron_right)
                setColorFilter(ContextCompat.getColor(context, R.color.secondary))
                layoutParams = LinearLayout.LayoutParams((14 * d).toInt(), (14 * d).toInt())
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
            val last = i == labels.size - 1
            val target = segs.take(i).joinToString("/")
            val locked = i > 0 && vault.mountOf(p.pcIp, segs.take(i).joinToString("/")) == segs.take(i).joinToString("/")
            crumbs.addView(TextView(this).apply {
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
                    setOnClickListener { open(target) }
                }
            })
        }
        crumbsScroll.post { crumbsScroll.fullScroll(View.FOCUS_RIGHT) }
    }

    // ---------- 동작 ----------

    /** 항목의 클라우드 경로(찾기 결과는 시작 폴더 기준 path). */
    private fun targetOf(item: CloudItem) = CloudPath.join(path, item.path ?: item.name)

    private fun itemClicked(item: CloudItem) {
        val target = targetOf(item)
        val p = pc ?: return
        when {
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
            .setNegativeButton(R.string.cancel, null).show()
    }

    /** 폴더(길게 누름): 열기·지우기, cloud2면 잠그기(잠긴 폴더는 잠금 풀기·PIN 바꾸기). */
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
            .show()
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
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun askNewFolder() {
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
                val target = CloudPath.join(path, name)
                if (!CloudPath.isSegment(name) || !CloudPath.isValid(target)) { toast(getString(R.string.cloud_bad_name)); return@setPositiveButton }
                io.execute {
                    val err = try { Net.cloudMkdir(p, target); null } catch (e: Exception) { e }
                    ui.post {
                        if (isDestroyed) return@post
                        if (err != null) toast(getString(R.string.failed, Errors.text(this, err)))
                        if (pc === p) load(reset = true, keep = true)
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null).show()
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
            .setPositiveButton(positive, null).setNegativeButton(R.string.cancel, null).create()
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
            .setPositiveButton(R.string.vault_saved_it, null).setNeutralButton(R.string.vault_copy, null).create()
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
        b.show()
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

    /** 미리보기를 물어볼 항목인지: "cloud2" 허브의 사진·영상 파일(잠긴 폴더 안은 사진만 — 허브가 그것만 만든다, §15). */
    private fun wantsThumb(item: CloudItem): Boolean {
        if (!cloud2 || item.dir || !FileKinds.previewable(item.kindOrGuess)) return false
        val p = pc ?: return false
        return item.kindOrGuess == FileKinds.IMAGE || vault.mountOf(p.pcIp, targetOf(item)) == null
    }

    private fun bindThumb(view: ImageView, item: CloudItem, px: Int, onBitmap: (Bitmap) -> Unit) {
        val p = pc ?: return
        val target = targetOf(item)
        val key = Thumbs.cloudKey(p.pcIp, target, item, px)
        val inVault = vault.mountOf(p.pcIp, target) != null
        val app = applicationContext
        Thumbs.bind(view, key, { job -> Thumbs.cloud(app, p, target, px, key, inVault, job) }, onBitmap)
    }

    // ---------- 목록 어댑터 ----------

    private inner class Rows : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var rows: List<Row> = emptyList()

        private val tHeader = 0
        private val tRow = 1
        private val tMore = 2

        inner class RowVH(v: View) : RecyclerView.ViewHolder(v) {
            val icon: ImageView = v.findViewById<ImageView>(R.id.icon).also { it.clipToOutline = true }
            val name: TextView = v.findViewById(R.id.name)
            val sub: TextView = v.findViewById(R.id.sub)
            val trail: ImageView = v.findViewById(R.id.trail)
        }

        inner class GridVH(v: View) : RecyclerView.ViewHolder(v) {
            val thumb: ImageView = v.findViewById(R.id.thumb)
            val icon: ImageView = v.findViewById(R.id.icon)
            val badge: ImageView = v.findViewById(R.id.badge)
            val name: TextView = v.findViewById(R.id.name)
            val sub: TextView = v.findViewById(R.id.sub)
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

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val r = rows[position]
            if (r is Row.Header) { (holder as TextVH).text.text = r.text; return }
            if (holder is GridVH && r is Row.Item) { bindGrid(holder, r.it); return }
            if (holder !is RowVH) return
            val ctx = holder.itemView.context
            holder.itemView.alpha = 1f
            holder.sub.setTextColor(ContextCompat.getColor(ctx, R.color.secondary))
            holder.trail.visibility = View.GONE
            holder.itemView.setOnLongClickListener(null)
            Thumbs.unbind(holder.icon)
            when (r) {
                is Row.Pc -> {
                    iconTile(holder.icon, R.drawable.ic_laptop)
                    holder.name.text = r.p.pcName
                    holder.sub.text = getString(when (r.state) {
                        PcState.CHECKING -> R.string.cloud_pc_checking
                        PcState.ON -> R.string.cloud_pc_on
                        PcState.OFF -> R.string.cloud_pc_off
                        PcState.OLD -> R.string.err_cloud_unsupported
                        PcState.UNREACHABLE -> R.string.cloud_pc_unreachable
                    })
                    val on = r.state == PcState.ON
                    holder.itemView.alpha = if (on || r.state == PcState.CHECKING) 1f else 0.55f
                    holder.trail.visibility = if (on) View.VISIBLE else View.GONE
                    holder.itemView.setOnClickListener { pcClicked(r.p, r.state) }
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
                        holder.icon.scaleType = ImageView.ScaleType.CENTER_CROP
                        holder.icon.imageTintList = null
                        holder.icon.setImageBitmap(bmp)
                    }
                    holder.name.text = item.name
                    holder.sub.text = meta(item)
                    holder.trail.visibility = if (item.dir) View.VISIBLE else View.GONE
                    holder.itemView.setOnClickListener { itemClicked(item) }
                    holder.itemView.setOnLongClickListener { longPress(item); true }
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
            h.itemView.setOnClickListener { itemClicked(item) }
            h.itemView.setOnLongClickListener { longPress(item); true }
        }

        private fun longPress(item: CloudItem) {
            val target = targetOf(item)
            if (item.dir) folderMenu(item, target) else fileMenu(item, target)
        }
    }
}
