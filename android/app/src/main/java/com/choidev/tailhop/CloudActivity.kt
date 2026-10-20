package com.choidev.tailhop

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
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
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * PC 클라우드(1.8.0, docs/PROTOCOL.md §12). 처음엔 PC를 고르고(hello가 cloud:true인 PC만 열 수 있다, 나머지는 "클라우드 꺼짐" 등),
 * 고른 PC의 폴더를 본다: 경로(빵 부스러기)·뒤로 가기로 위 폴더, 끝까지 내리면 다음 쪽(next cursor)을 더 불러온다.
 * 받기·올리기는 보낼 곳 목록(Outbox)에 넣어 SendService가 이어받기·재시도와 함께 하고, 이 화면 위쪽 "전송"에 진행이 보인다.
 * 새 폴더·지우기(확인 창)는 바로 요청한다. EXTRA_IP로 열면 그 PC의 맨 위 폴더부터 보인다.
 */
class CloudActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_IP = "ip"
        private const val S_IP = "ip"
        private const val S_PATH = "path"
        private const val S_PICKER = "picker"
    }

    private enum class PcState { CHECKING, ON, OFF, OLD, UNREACHABLE }

    /** 목록의 한 줄. */
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
    private val adapter = Rows()
    private val ui = Handler(Looper.getMainLooper())
    private val io: ExecutorService = Executors.newFixedThreadPool(3)

    /** 지금 보는 PC(null = PC 고르기). */
    private var pc: Pairing? = null
    /** PC 고르기에서 들어왔는지(뒤로 가기가 고르기로 돌아간다). */
    private var fromPicker = true
    private var path = ""
    private val pcStates = HashMap<String, PcState>()
    private var items: List<CloudItem> = emptyList()
    private var next: Int? = null
    private var loading = false
    private var loadError: String? = null
    /** 목록 요청 세대: 폴더를 옮기거나 새로 고치면 늘려서 늦게 온 예전 응답을 버린다. */
    private var gen = 0
    /** 이 폴더로 올리는 중인 건(끝나서 목록에서 빠지면 폴더를 새로 읽는다). */
    private var uploadsHere: Set<String> = emptySet()

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cloud)
        applySystemBarInsets()
        title = findViewById(R.id.title)
        subtitle = findViewById(R.id.subtitle)
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

        val lm = LinearLayoutManager(this)
        list.layoutManager = lm
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
        val p = pc
        if (p != null && Store.get(this, p.pcIp) == null) { finish(); return }
        SendService.resumeIfNeeded(this)
        ui.post(ticker)
    }

    override fun onPause() { super.onPause(); ui.removeCallbacks(ticker) }

    override fun onDestroy() {
        Outbox.removeListener(outboxListener)
        io.shutdownNow()
        super.onDestroy()
    }

    // ---------- 이동 ----------

    private fun goBack() {
        when {
            pc != null && path.isNotEmpty() -> open(CloudPath.parent(path))
            pc != null && fromPicker -> { pc = null; gen++; items = emptyList(); next = null; loadError = null; checkPcs(); render() }
            else -> finish()
        }
    }

    private fun browse(p: Pairing) {
        pc = p
        path = ""
        load(reset = true, keep = false)
    }

    /** 그 폴더로 간다(규칙을 넘는 깊이·길이는 열지 않는다). */
    private fun open(dir: String) {
        if (!CloudPath.isValid(dir)) { toast(getString(R.string.err_bad_path)); return }
        path = dir
        load(reset = true, keep = false)
    }

    // ---------- PC 고르기 ----------

    /** 페어링된 PC마다 hello로 클라우드가 켜졌는지 묻는다(cloud:true만 열 수 있다). */
    private fun checkPcs() {
        for (p in Store.all(this)) {
            pcStates[p.pcIp] = PcState.CHECKING
            io.execute {
                val st = try {
                    val h = Net.hello(p, null, null)
                    when {
                        h == null || "cloud" !in h.caps -> PcState.OLD
                        h.cloud -> PcState.ON
                        else -> PcState.OFF
                    }
                } catch (e: Exception) { PcState.UNREACHABLE }
                ui.post { if (!isDestroyed) { pcStates[p.pcIp] = st; if (pc == null) render() } }
            }
        }
        render()
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

    // ---------- 목록 ----------

    /**
     * 폴더 목록을 읽는다. reset이면 처음 쪽부터(keep이면 받는 동안 지금 목록을 그대로 보여 준다 — 새로 고침),
     * 아니면 next 쪽을 더 읽는다.
     */
    private fun load(reset: Boolean, keep: Boolean = true) {
        val p = pc ?: return
        if (!reset && (loading || next == null || loadError != null)) return
        val cursor = if (reset) 0 else next ?: return
        val g = if (reset) ++gen else gen
        if (reset && !keep) { items = emptyList(); next = null }
        loading = true
        loadError = null
        render()
        val dir = path
        io.execute {
            val page = try { Net.cloudList(p, dir, cursor) } catch (e: Exception) {
                ui.post {
                    if (isDestroyed || g != gen) return@post
                    loading = false
                    if (reset) { items = emptyList(); next = null; loadError = Errors.text(this, e) }
                    else toast(getString(R.string.failed, Errors.text(this, e)))
                    render()
                }
                return@execute
            }
            ui.post {
                if (isDestroyed || g != gen) return@post
                loading = false
                items = if (reset) page.items else items + page.items
                // 허브가 뒤로 가는 cursor를 주면 더 읽지 않는다(같은 쪽을 맴돌지 않게).
                next = page.next?.takeIf { it > cursor }
                render()
                list.post { maybeLoadMore() }
            }
        }
    }

    /** 목록 끝 가까이 왔으면(또는 화면이 다 차지 않았으면) 다음 쪽을 읽는다. */
    private fun maybeLoadMore() {
        if (pc == null || loading || next == null) return
        val lm = list.layoutManager as? LinearLayoutManager ?: return
        if (lm.findLastVisibleItemPosition() >= adapter.itemCount - 6) load(reset = false)
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

    // ---------- 그리기 ----------

    private fun render() {
        val p = pc
        val rows = ArrayList<Row>()
        newFolderBtn.visibility = if (p == null) View.GONE else View.VISIBLE
        uploadBtn.visibility = if (p == null) View.GONE else View.VISIBLE
        crumbsScroll.visibility = if (p == null) View.GONE else View.VISIBLE
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
        val err = loadError
        val nothing = items.isEmpty() && !loading
        // 전송 줄이 없으면 빈 폴더·오류를 가운데 크게, 있으면 목록 안에 한 줄로.
        if (nothing && jobs.isNotEmpty()) {
            rows += if (err != null) Row.Note(getString(R.string.cloud_load_failed), err, true)
            else Row.Note(getString(R.string.cloud_empty), getString(R.string.cloud_empty_sub), false)
        }
        setRows(rows)
        if (nothing && jobs.isEmpty()) {
            if (err != null) setEmpty(R.drawable.ic_cloud, getString(R.string.cloud_load_failed), err, true)
            else setEmpty(R.drawable.ic_folder, getString(R.string.cloud_empty), getString(R.string.cloud_empty_sub), false)
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
        emptyBtn.visibility = if (retry) View.VISIBLE else View.GONE
    }

    private fun showEmpty(on: Boolean) { empty.visibility = if (on) View.VISIBLE else View.GONE }

    /** 맨 위(PC 이름)부터 지금 폴더까지. 마지막 조각은 굵게, 앞 조각은 누르면 그 폴더로. */
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
            crumbs.addView(TextView(this).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(ContextCompat.getColor(context, if (last) R.color.label else R.color.secondary))
                if (last) setTypeface(typeface, android.graphics.Typeface.BOLD)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                maxWidth = (200 * d).toInt()
                setPadding((8 * d).toInt(), (6 * d).toInt(), (8 * d).toInt(), (6 * d).toInt())
                if (!last) {
                    setBackgroundResource(R.drawable.bg_row_press)
                    setOnClickListener { open(target) }
                }
            })
        }
        crumbsScroll.post { crumbsScroll.fullScroll(View.FOCUS_RIGHT) }
    }

    // ---------- 동작 ----------

    private fun itemClicked(item: CloudItem) {
        val target = CloudPath.join(path, item.name)
        if (item.dir) open(target) else fileMenu(item, target)
    }

    private fun meta(item: CloudItem): String {
        val date = if (item.mtime > 0) Lang.shortTime(this, item.mtime) else ""
        val first = if (item.dir) getString(R.string.cloud_folder) else Formatter.formatShortFileSize(this, item.size)
        return if (date.isEmpty()) first else getString(R.string.cloud_file_meta, first, date)
    }

    /** 파일: 받기·지우기. 폴더(길게 누름): 열기·지우기. */
    private fun fileMenu(item: CloudItem, target: String) {
        AlertDialog.Builder(this).setTitle(item.name).setMessage(meta(item))
            .setPositiveButton(R.string.cloud_download) { _, _ -> download(item, target) }
            .setNeutralButton(R.string.cloud_delete) { _, _ -> confirmDelete(item, target) }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun folderMenu(item: CloudItem, target: String) {
        AlertDialog.Builder(this).setTitle(item.name)
            .setItems(arrayOf(getString(R.string.cloud_open), getString(R.string.cloud_delete))) { _, which ->
                if (which == 0) open(target) else confirmDelete(item, target)
            }.show()
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

    /** 지우기는 늘 확인을 받는다(PC는 휴지통으로 보낸다). */
    private fun confirmDelete(item: CloudItem, target: String) {
        val p = pc ?: return
        AlertDialog.Builder(this).setTitle(getString(R.string.cloud_delete_title, item.name))
            .setMessage(if (item.dir) R.string.cloud_delete_folder_msg else R.string.cloud_delete_file_msg)
            .setPositiveButton(R.string.cloud_delete) { _, _ ->
                io.execute {
                    val err = try { Net.cloudDel(p, target); null } catch (e: Exception) { e }
                    ui.post {
                        if (isDestroyed) return@post
                        if (err == null) toast(getString(R.string.cloud_deleted))
                        else toast(getString(R.string.failed, Errors.text(this, err)))
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

    // ---------- 목록 어댑터 ----------

    private inner class Rows : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var rows: List<Row> = emptyList()

        private val tHeader = 0
        private val tRow = 1
        private val tMore = 2

        inner class RowVH(v: View) : RecyclerView.ViewHolder(v) {
            val icon: ImageView = v.findViewById(R.id.icon)
            val name: TextView = v.findViewById(R.id.name)
            val sub: TextView = v.findViewById(R.id.sub)
            val trail: ImageView = v.findViewById(R.id.trail)
        }

        inner class TextVH(val text: TextView) : RecyclerView.ViewHolder(text)

        override fun getItemCount() = rows.size

        override fun getItemViewType(position: Int) = when (rows[position]) {
            is Row.Header -> tHeader
            Row.More -> tMore
            else -> tRow
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val d = parent.resources.displayMetrics.density
            return when (viewType) {
                tRow -> RowVH(LayoutInflater.from(parent.context).inflate(R.layout.item_cloud, parent, false))
                tHeader -> TextVH(TextView(parent.context).apply {
                    // 홈의 "기기" 머리와 같은 모양
                    setPadding((20 * d).toInt(), (18 * d).toInt(), (20 * d).toInt(), (6 * d).toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
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

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val r = rows[position]
            if (r is Row.Header) { (holder as TextVH).text.text = r.text; return }
            if (holder !is RowVH) return
            val ctx = holder.itemView.context
            holder.itemView.alpha = 1f
            holder.sub.setTextColor(ContextCompat.getColor(ctx, R.color.secondary))
            holder.trail.visibility = View.GONE
            holder.itemView.setOnLongClickListener(null)
            when (r) {
                is Row.Pc -> {
                    holder.icon.setImageResource(R.drawable.ic_laptop)
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
                    holder.icon.setImageResource(if (j.kind == OutboxJob.CLOUD_UP) R.drawable.ic_cloud_upload else R.drawable.ic_download)
                    holder.name.text = SendService.what(ctx, j)
                    holder.sub.text = jobStatus(j)
                    if (j.failed) holder.sub.setTextColor(ContextCompat.getColor(ctx, R.color.red))
                    holder.itemView.alpha = if (j.failed || j.state == OutboxJob.SENDING) 1f else 0.7f
                    holder.itemView.setOnClickListener { jobMenu(j) }
                    holder.itemView.setOnLongClickListener { jobMenu(j); true }
                }
                is Row.Item -> {
                    val item = r.it
                    holder.icon.setImageResource(if (item.dir) R.drawable.ic_folder else R.drawable.ic_doc)
                    holder.name.text = item.name
                    holder.sub.text = meta(item)
                    holder.trail.visibility = if (item.dir) View.VISIBLE else View.GONE
                    holder.itemView.setOnClickListener { itemClicked(item) }
                    holder.itemView.setOnLongClickListener {
                        val target = CloudPath.join(path, item.name)
                        if (item.dir) folderMenu(item, target) else fileMenu(item, target)
                        true
                    }
                }
                is Row.Note -> {
                    holder.icon.setImageResource(if (r.retry) R.drawable.ic_retry else R.drawable.ic_folder)
                    holder.name.text = r.title
                    holder.sub.text = r.sub ?: ""
                    holder.itemView.setOnClickListener { if (r.retry) load(reset = true, keep = false) }
                }
                else -> { }
            }
        }
    }
}
