package com.choidev.tailhop

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Editable
import android.text.SpannableString
import android.text.TextUtils
import android.text.TextWatcher
import android.text.format.DateUtils
import android.text.format.Formatter
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
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
import java.util.Calendar

/**
 * 한 PC와의 대화방. 보내기는 보낼 곳 목록(Outbox)에 넣기만 하고 SendService가 보낸다(1.7.0).
 * 아직 못 보낸 건은 대화 끝에 흐린 말풍선(보내는 중 n% / 대기 · n초 뒤 다시 시도 / 실패: 이유)으로 보이고,
 * 탭하거나 길게 누르면 다시 시도·취소를 고른다.
 */
class ChatActivity : AppCompatActivity() {
    companion object { const val EXTRA_IP = "ip" }

    private lateinit var ip: String
    private lateinit var pairState: TextView
    private lateinit var dot: ImageView
    private lateinit var textBox: EditText
    private lateinit var sendBtn: View
    private lateinit var chat: RecyclerView
    private lateinit var empty: View
    private lateinit var emptyIcon: ImageView
    private lateinit var emptyTitle: TextView
    private lateinit var emptySub: TextView
    private lateinit var searchPanel: View
    private lateinit var searchBox: EditText
    private lateinit var kindChips: LinearLayout
    private lateinit var composer: View
    private val adapter = ChatAdapter()
    private val media = MediaAdapter()
    private val ui = Handler(Looper.getMainLooper())

    /** 찾기(2.0.0): 글·파일 이름과 종류(ChatFilter). */
    private var query = ""
    private var kind = ChatFilter.ALL
    /** 사진·영상 모아 보기(격자). */
    private var mediaMode = false
    private val searchRun = Runnable { query = searchBox.text.toString(); reloadChat() }

    private val historyListener: () -> Unit = { ui.post { reloadChat() } }

    private val pick = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { u -> try { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) { } }
        if (uris.isNotEmpty()) sendFiles(uris)
    }

    private val ticker = object : Runnable {
        override fun run() {
            updateHeader()
            // "n초 뒤 다시 시도"가 흘러가게 대기 말풍선만 다시 그린다.
            if (adapter.pending.isNotEmpty()) adapter.notifyItemRangeChanged(adapter.items.size, adapter.pending.size)
            ui.postDelayed(this, 1000)
        }
    }

    private fun pairing(): Pairing? = Store.get(this, ip)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ip = intent.getStringExtra(EXTRA_IP) ?: run { finish(); return }
        setContentView(R.layout.activity_chat)
        applySystemBarInsets()
        pairState = findViewById(R.id.pairState)
        dot = findViewById(R.id.dot)
        textBox = findViewById(R.id.textBox)
        sendBtn = findViewById(R.id.sendBtn)
        chat = findViewById(R.id.chat)
        empty = findViewById(R.id.empty)
        emptyIcon = findViewById(R.id.emptyIcon)
        emptyTitle = findViewById(R.id.emptyTitle)
        emptySub = findViewById(R.id.emptySub)
        searchPanel = findViewById(R.id.searchPanel)
        searchBox = findViewById(R.id.searchBox)
        kindChips = findViewById(R.id.kindChips)
        composer = findViewById(R.id.composer)

        chat.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        chat.adapter = adapter
        chat.itemAnimator = null

        findViewById<View>(R.id.backBtn).setOnClickListener { goBack() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { goBack() }
        })
        findViewById<View>(R.id.searchBtn).setOnClickListener { openSearch() }
        findViewById<View>(R.id.searchClose).setOnClickListener { closeSearch() }
        findViewById<View>(R.id.mediaBtn).setOnClickListener { setMedia(!mediaMode) }
        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { ui.removeCallbacks(searchRun); ui.postDelayed(searchRun, 250) }
        })
        searchBox.setOnEditorActionListener { v, _, _ ->
            ui.removeCallbacks(searchRun)
            searchRun.run()
            getSystemService(android.view.inputmethod.InputMethodManager::class.java)?.hideSoftInputFromWindow(v.windowToken, 0)
            true
        }
        buildChips()
        findViewById<View>(R.id.moreBtn).setOnClickListener { showMenu(it) }
        findViewById<View>(R.id.plusBtn).setOnClickListener { pick.launch(arrayOf("*/*")) }
        textBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { updateSendEnabled() }
        })
        sendBtn.setOnClickListener {
            val t = textBox.text.toString()
            val p = pairing() ?: return@setOnClickListener
            if (t.isBlank()) return@setOnClickListener
            if (t.toByteArray(Charsets.UTF_8).size > Protocol.MAX_TEXT) {
                toast(getString(R.string.failed, getString(R.string.err_text_too_long))); return@setOnClickListener
            }
            Outbox.enqueueText(this, p.pcIp, t)
            SendService.start(this)
            textBox.setText("")
        }
        History.addListener(historyListener)
        Outbox.addListener(historyListener)
    }

    override fun onDestroy() {
        History.removeListener(historyListener)
        Outbox.removeListener(historyListener)
        ui.removeCallbacks(searchRun)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (pairing() == null) { finish(); return }
        Store.setCurrent(this, ip)
        SendService.resumeIfNeeded(this)
        updateHeader(); reloadChat(); updateSendEnabled(); ui.post(ticker)
    }
    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(ticker)
        Thumbs.cancelAll() // 보이지 않는 동안 미리보기 일을 하지 않게
    }

    // ---------- 찾기·모아 보기(2.0.0) ----------

    private fun goBack() {
        when {
            mediaMode -> setMedia(false)
            searchPanel.visibility == View.VISIBLE -> closeSearch()
            else -> finish()
        }
    }

    private fun openSearch() {
        if (mediaMode) setMedia(false)
        searchPanel.visibility = View.VISIBLE
        searchBox.requestFocus()
        getSystemService(android.view.inputmethod.InputMethodManager::class.java)?.showSoftInput(searchBox, 0)
    }

    private fun closeSearch() {
        ui.removeCallbacks(searchRun)
        searchBox.setText("")
        ui.removeCallbacks(searchRun)
        query = ""
        kind = ChatFilter.ALL
        updateChips()
        searchPanel.visibility = View.GONE
        getSystemService(android.view.inputmethod.InputMethodManager::class.java)?.hideSoftInputFromWindow(searchBox.windowToken, 0)
        reloadChat()
    }

    private fun kindLabel(k: String) = when (k) {
        ChatFilter.TEXT -> R.string.chat_kind_text
        ChatFilter.MEDIA -> R.string.chat_kind_media
        ChatFilter.FILE -> R.string.chat_kind_file
        else -> R.string.kind_all
    }

    /** 종류 칩(클라우드 화면과 같은 shadcn Toggle 모양). */
    private fun buildChips() {
        val d = resources.displayMetrics.density
        for (k in ChatFilter.KINDS) {
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
                setOnClickListener { kind = k; updateChips(); reloadChat() }
            })
        }
        updateChips()
    }

    private fun updateChips() {
        for (i in 0 until kindChips.childCount) kindChips.getChildAt(i).let { it.isSelected = it.tag == kind }
    }

    /** 모아 보기: 이 대화의 사진·영상을 격자로(미리보기는 이 폰의 파일에서 만든다). 입력창·찾기는 숨긴다. */
    private fun setMedia(on: Boolean) {
        mediaMode = on
        if (on) {
            searchPanel.visibility = View.GONE
            chat.layoutManager = GridLayoutManager(this, CloudGrid.columns(
                (resources.displayMetrics.widthPixels / resources.displayMetrics.density).toInt()))
            chat.adapter = media
        } else {
            Thumbs.cancelAll()
            chat.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
            chat.adapter = adapter
            if (ChatFilter.active(query, kind)) searchPanel.visibility = View.VISIBLE
        }
        composer.visibility = if (on) View.GONE else View.VISIBLE
        // 모아 보기 격자는 유리 판 위에(칸의 회색 글자가 배경 오로라 위에 바로 놓이지 않게)
        val d = resources.displayMetrics.density
        (chat.layoutParams as ViewGroup.MarginLayoutParams).setMargins(
            if (on) (12 * d).toInt() else 0, if (on) (8 * d).toInt() else 0, if (on) (12 * d).toInt() else 0, if (on) (12 * d).toInt() else 0)
        chat.requestLayout()
        if (on) chat.setBackgroundResource(R.drawable.bg_glass) else chat.background = null
        chat.clipToOutline = on
        val pad = (if (on) 6 * d else 0f).toInt()
        chat.setPadding(pad, if (on) pad else (4 * d).toInt(), pad, if (on) pad else (8 * d).toInt())
        findViewById<ImageView>(R.id.mediaBtn).setImageResource(if (on) R.drawable.ic_bubble else R.drawable.ic_image)
        findViewById<View>(R.id.mediaBtn).contentDescription = getString(if (on) R.string.chat_back_to_chat else R.string.chat_media)
        updateHeader()
        reloadChat()
    }

    private fun setEmpty(icon: Int, title: Int, sub: Int?) {
        emptyIcon.setImageResource(icon)
        emptyTitle.setText(title)
        emptySub.visibility = if (sub == null) View.GONE else View.VISIBLE
        if (sub != null) emptySub.setText(sub)
    }

    private fun showMenu(anchor: View) {
        val p = pairing() ?: return
        val red = ContextCompat.getColor(this, R.color.red)
        fun redTitle(s: String) = SpannableString(s).apply { setSpan(ForegroundColorSpan(red), 0, s.length, 0) }
        val m = PopupMenu(this, anchor)
        m.menu.add(0, 4, 0, getString(R.string.menu_cloud_open))
        m.menu.add(0, 1, 1, getString(R.string.menu_show_code))
        m.menu.add(0, 2, 2, redTitle(getString(R.string.menu_clear)))
        m.menu.add(0, 3, 3, redTitle(getString(R.string.menu_unpair)))
        m.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                4 -> MainActivity.openCloud(this, p.pcIp)
                1 -> MainActivity.showConfirmCode(this, p)
                2 -> AlertDialog.Builder(this).setMessage(R.string.clear_message)
                    .setPositiveButton(R.string.clear_confirm) { _, _ -> History.clearDevice(this, ip) }
                    .setNegativeButton(R.string.cancel, null).show()
                3 -> MainActivity.confirmUnpair(this, p) { finish() }
            }
            true
        }
        m.show()
    }

    private fun updateSendEnabled() { sendBtn.isEnabled = textBox.text.isNotBlank() }

    private fun updateHeader() {
        val p = pairing() ?: return
        findViewById<TextView>(R.id.pcName).text = p.pcName
        // 이 폰의 수신 상태(보내기는 수신 중지와 상관없이 된다)
        val ok = Receiving.isOn(this) && ReceiverService.state == ReceiverService.State.LISTENING
        pairState.text = if (mediaMode) getString(R.string.chat_media)
        else if (ok) getString(R.string.chat_connected)
        else getString(R.string.chat_connected_status, ReceiverService.statusText(this))
        dot.setImageResource(if (ok) R.drawable.dot_green else R.drawable.dot_gray)
    }

    private fun reloadChat() {
        if (mediaMode) {
            media.items = ChatFilter.media(History.forDevice(this, ip))
            media.notifyDataSetChanged()
            val none = media.items.isEmpty()
            if (none) setEmpty(R.drawable.ic_image, R.string.chat_media_empty, null)
            empty.visibility = if (none) View.VISIBLE else View.GONE
            chat.visibility = if (none) View.GONE else View.VISIBLE
            return
        }
        val filtering = ChatFilter.active(query, kind)
        val items = History.forDevice(this, ip).reversed().let { all -> if (filtering) all.filter { ChatFilter.matches(it, query, kind) } else all }
        // 거르는 동안에는 아직 못 보낸 건을 숨긴다(찾은 기록만 보이게).
        val pending = if (filtering) emptyList() else Outbox.forDevice(this, ip)
        val total = items.size + pending.size
        if (filtering) setEmpty(R.drawable.ic_search, R.string.search_empty, null)
        else setEmpty(R.drawable.ic_bubble, R.string.chat_empty_title, R.string.chat_empty_sub)
        val atBottom = !chat.canScrollVertically(1)
        val grew = total > adapter.itemCount
        adapter.name = pairing()?.pcName ?: "PC"
        adapter.items = items
        adapter.pending = pending
        adapter.notifyDataSetChanged()
        if (total > 0 && (grew || atBottom)) chat.scrollToPosition(total - 1)
        empty.visibility = if (total == 0) View.VISIBLE else View.GONE
        chat.visibility = if (total == 0) View.GONE else View.VISIBLE
    }

    /** 고른 파일들을 보낼 곳 목록에 넣는다. 읽기 권한은 위에서 영구로 받아 두었고, 서비스 시작에도 실어 넘긴다. */
    private fun sendFiles(uris: List<Uri>) {
        val p = pairing() ?: return
        Outbox.enqueueFiles(this, p.pcIp, uris.map { it.toString() })
        SendService.start(this, uris)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun open(item: HistoryItem) {
        if (!item.incoming) return // 보낸 파일(다른 앱의 URI)은 다시 열어 주지 않는다
        val uri = item.uri?.let { Uri.parse(it) } ?: return
        if (uri.scheme != "content") return
        try {
            val type = contentResolver.getType(uri) ?: "*/*"
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, type)
            // 읽기 권한은 이 앱이 받아 저장한 파일(MediaStore Downloads 또는 이 앱의 FileProvider)에만 넘긴다.
            if (uri.authority == MediaStore.AUTHORITY || uri.authority == "$packageName.files")
                view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(view)
        } catch (e: Exception) {
            toast(getString(R.string.cant_open_file))
        }
    }

    private fun copy(item: HistoryItem) {
        // 긴 글은 기록에 앞부분만 있으므로 전체를 파일에서 읽어 복사한다.
        Clip.copy(this, History.fullText(this, item))
    }

    /** 못 보낸 건의 상태 한 줄. */
    private fun pendingStatus(j: OutboxJob): String {
        val now = System.currentTimeMillis()
        return when {
            j.failed -> getString(R.string.outbox_failed, SendService.reason(this, j))
            j.state == OutboxJob.SENDING -> getString(R.string.outbox_sending,
                if (j.size > 0) (Outbox.progressOf(j.id) * 100 / j.size).toInt().coerceIn(0, 100) else 0)
            j.attempts > 0 && j.nextAt > now -> getString(R.string.outbox_retry_in,
                DateUtils.formatElapsedTime((j.nextAt - now + 999) / 1000))
            else -> getString(R.string.outbox_queued)
        }
    }

    /** 못 보낸 건을 누름: 마지막 실패 이유와 다시 시도·취소. 보내는 중이면 취소만. */
    private fun pendingMenu(j: OutboxJob) {
        val b = AlertDialog.Builder(this).setTitle(SendService.what(this, j))
        if (j.error != null) b.setMessage(SendService.reason(this, j))
        if (j.state != OutboxJob.SENDING) {
            b.setPositiveButton(if (j.failed) R.string.outbox_send_again else R.string.outbox_retry_now) { _, _ ->
                SendService.retry(this, j.id)
            }
        }
        b.setNegativeButton(if (j.failed) R.string.outbox_remove else R.string.outbox_cancel) { _, _ -> SendService.cancel(this, j.id) }
        b.setNeutralButton(R.string.close, null)
        b.show()
    }

    /** 사진·영상 모아 보기의 격자(클라우드 격자와 같은 칸). 누르면 대화방에서처럼 연다(받은 파일만). */
    private inner class MediaAdapter : RecyclerView.Adapter<MediaAdapter.VH>() {
        var items: List<HistoryItem> = emptyList()

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val thumb: ImageView = v.findViewById(R.id.thumb)
            val icon: ImageView = v.findViewById(R.id.icon)
            val badge: ImageView = v.findViewById(R.id.badge)
            val name: TextView = v.findViewById(R.id.name)
            val sub: TextView = v.findViewById(R.id.sub)
        }

        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_cloud_grid, parent, false))

        override fun onViewRecycled(holder: VH) { Thumbs.unbind(holder.thumb) }

        override fun onBindViewHolder(h: VH, pos: Int) {
            val it = items[pos]
            val video = ChatFilter.isVideo(it)
            Thumbs.unbind(h.thumb)
            h.thumb.setImageDrawable(null)
            h.icon.visibility = View.VISIBLE
            h.icon.setImageResource(if (video) R.drawable.ic_video else R.drawable.ic_image)
            h.badge.visibility = if (video) View.VISIBLE else View.GONE
            h.badge.setImageResource(R.drawable.ic_video)
            h.name.text = it.name ?: getString(R.string.file_default)
            h.sub.text = Lang.shortTime(h.itemView.context, it.ts)
            val uri = it.uri
            if (uri != null) {
                val px = CloudThumb.GRID_PX
                val app = applicationContext
                Thumbs.bind(h.thumb, Thumbs.localKey(uri, px), { job -> Thumbs.local(app, Uri.parse(uri), video, px, job) }) { bmp ->
                    h.thumb.setImageBitmap(bmp)
                    h.icon.visibility = View.GONE
                }
            }
            h.itemView.setOnClickListener { _ -> if (it.incoming) open(it) else toast(getString(R.string.sent_file_to_pc)) }
        }
    }

    private inner class ChatAdapter : RecyclerView.Adapter<ChatAdapter.VH>() {
        var items: List<HistoryItem> = emptyList()
        /** 대화 끝에 붙는 아직 못 보낸 건(넣은 순서). */
        var pending: List<OutboxJob> = emptyList()
        var name = "PC"

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val date: TextView = v.findViewById(R.id.date)
            val who: TextView = v.findViewById(R.id.who)
            val bubble: LinearLayout = v.findViewById(R.id.bubble)
            val fileIcon: ImageView = v.findViewById(R.id.fileIcon)
            val body: TextView = v.findViewById(R.id.body)
            val sub: TextView = v.findViewById(R.id.sub)
            val time: TextView = v.findViewById(R.id.time)
        }

        override fun getItemCount() = items.size + pending.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_bubble, parent, false))

        private fun sameDay(a: Long, b: Long): Boolean {
            val c1 = Calendar.getInstance().apply { timeInMillis = a }
            val c2 = Calendar.getInstance().apply { timeInMillis = b }
            return c1.get(Calendar.YEAR) == c2.get(Calendar.YEAR) && c1.get(Calendar.DAY_OF_YEAR) == c2.get(Calendar.DAY_OF_YEAR)
        }

        /**
         * 말풍선 모양(1.9.0 유리 디자인). out이면 오른쪽, primary면 내가 보낸 말풍선(shadcn primary 면·onAccent 글자),
         * 아니면 유리 말풍선(받은 것·아직 못 보낸 것). 부 글자(크기·상태) 색을 돌려준다.
         */
        private fun outStyle(h: VH, out: Boolean, primary: Boolean): Int {
            val ctx = h.itemView.context
            val dm = ctx.resources.displayMetrics
            val gravity = if (out) Gravity.END else Gravity.START
            (h.bubble.layoutParams as LinearLayout.LayoutParams).gravity = gravity
            (h.time.layoutParams as LinearLayout.LayoutParams).gravity = gravity
            h.bubble.setBackgroundResource(if (primary) R.drawable.bg_bubble_out else R.drawable.bg_bubble_in)
            h.bubble.setPadding((14 * dm.density).toInt(), (9 * dm.density).toInt(), (14 * dm.density).toInt(), (9 * dm.density).toInt())
            val fg = ContextCompat.getColor(ctx, if (primary) R.color.onAccent else R.color.label)
            h.body.setTextColor(fg)
            h.fileIcon.setBackgroundResource(if (primary) R.drawable.bg_icon_tile_on_accent else R.drawable.bg_icon_tile)
            h.fileIcon.imageTintList = android.content.res.ColorStateList.valueOf(fg)
            return ContextCompat.getColor(ctx, if (primary) R.color.onAccentSub else R.color.secondary)
        }

        override fun onBindViewHolder(h: VH, pos: Int) {
            if (pos >= items.size) { bindPending(h, pending[pos - items.size]); return }
            val it = items[pos]
            val prev = items.getOrNull(pos - 1)
            val next = items.getOrNull(pos + 1)
            val ctx = h.itemView.context
            val out = !it.incoming
            val dm = ctx.resources.displayMetrics
            h.bubble.alpha = 1f
            h.sub.maxLines = Int.MAX_VALUE

            val newDay = prev == null || !sameDay(prev.ts, it.ts)
            h.date.visibility = if (newDay) View.VISIBLE else View.GONE
            h.date.text = Lang.dayHeader(ctx, it.ts)
            val firstInGroup = newDay || prev!!.incoming != it.incoming || it.ts - prev.ts > 60_000
            h.who.visibility = if (!out && firstInGroup) View.VISIBLE else View.GONE
            h.who.text = name

            val lastInGroup = next == null || next.incoming != it.incoming ||
                next.ts - it.ts > 60_000 || !sameDay(next.ts, it.ts)
            h.time.visibility = if (lastInGroup) View.VISIBLE else View.GONE
            h.time.text = Lang.time(ctx, it.ts)

            h.sub.setTextColor(outStyle(h, out, out))
            val maxW = (dm.widthPixels * (if (out) 0.75f else 0.85f)).toInt()
            h.body.maxWidth = maxW - (if (it.isText) 0 else (76 * dm.density).toInt())

            if (it.isText) {
                h.fileIcon.visibility = View.GONE
                h.sub.visibility = View.GONE
                // 목록에는 긴 글의 앞부분만 있다(메모리·그리기 비용이 글 길이와 상관없이 묶인다).
                h.body.text = if (it.textLen > (it.text?.length ?: 0))
                    (it.text ?: "") + ctx.getString(R.string.long_text_more, it.textLen) else it.text ?: ""
                h.body.maxLines = 60
                h.body.ellipsize = null
                h.bubble.setOnClickListener { _ -> copy(it) }
                h.bubble.setOnLongClickListener { _ -> copy(it); true }
            } else {
                h.fileIcon.visibility = View.VISIBLE
                h.sub.visibility = View.VISIBLE
                h.body.text = it.name ?: ctx.getString(R.string.file_default)
                h.body.maxLines = 2
                h.body.ellipsize = TextUtils.TruncateAt.MIDDLE
                val size = Formatter.formatShortFileSize(ctx, it.size)
                h.sub.text = if (out) size else ctx.getString(R.string.tap_to_open, size)
                h.bubble.setOnClickListener { _ -> if (out) toast(ctx.getString(R.string.sent_file_to_pc)) else open(it) }
                h.bubble.setOnLongClickListener(null)
            }
        }

        /**
         * 아직 못 보낸 건: 오른쪽 유리 말풍선(보낸 말풍선의 primary 면과 달라 보이게) + 상태 줄(실패면 빨강).
         * 예전처럼 흐리게(alpha) 하지 않는다 — 유리 위에서 흐리면 상태 글자 대비가 4.5:1 아래로 떨어진다.
         */
        private fun bindPending(h: VH, j: OutboxJob) {
            val ctx = h.itemView.context
            val dm = ctx.resources.displayMetrics
            h.date.visibility = View.GONE
            h.who.visibility = View.GONE
            h.time.visibility = View.GONE
            val subColor = outStyle(h, true, false)
            h.bubble.alpha = 1f
            h.body.maxWidth = (dm.widthPixels * 0.75f).toInt() - (if (j.isText) 0 else (76 * dm.density).toInt())
            h.fileIcon.visibility = if (j.isText) View.GONE else View.VISIBLE
            if (j.isText) {
                h.body.text = (j.text ?: "").take(HistoryCodec.TEXT_INLINE)
                h.body.maxLines = 10
                h.body.ellipsize = TextUtils.TruncateAt.END
            } else {
                h.body.text = j.name ?: ctx.getString(R.string.file_default)
                h.body.maxLines = 2
                h.body.ellipsize = TextUtils.TruncateAt.MIDDLE
            }
            h.sub.visibility = View.VISIBLE
            h.sub.maxLines = 3
            h.sub.text = pendingStatus(j)
            h.sub.setTextColor(if (j.failed) ContextCompat.getColor(ctx, R.color.red) else subColor)
            h.bubble.setOnClickListener { _ -> pendingMenu(j) }
            h.bubble.setOnLongClickListener { _ -> pendingMenu(j); true }
        }
    }
}
