package com.choidev.tailhop

import android.content.Intent
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
    private val adapter = ChatAdapter()
    private val ui = Handler(Looper.getMainLooper())

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

        chat.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        chat.adapter = adapter
        chat.itemAnimator = null

        findViewById<View>(R.id.backBtn).setOnClickListener { finish() }
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
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (pairing() == null) { finish(); return }
        Store.setCurrent(this, ip)
        SendService.resumeIfNeeded(this)
        updateHeader(); reloadChat(); updateSendEnabled(); ui.post(ticker)
    }
    override fun onPause() { super.onPause(); ui.removeCallbacks(ticker) }

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
        pairState.text = if (ok) getString(R.string.chat_connected)
        else getString(R.string.chat_connected_status, ReceiverService.statusText(this))
        dot.setImageResource(if (ok) R.drawable.dot_green else R.drawable.dot_gray)
    }

    private fun reloadChat() {
        val items = History.forDevice(this, ip).reversed()
        val pending = Outbox.forDevice(this, ip)
        val total = items.size + pending.size
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
