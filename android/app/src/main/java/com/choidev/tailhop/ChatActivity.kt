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
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Calendar

/** 한 PC와의 대화방. */
class ChatActivity : AppCompatActivity() {
    companion object { const val EXTRA_IP = "ip" }

    private lateinit var ip: String
    private lateinit var pairState: TextView
    private lateinit var dot: ImageView
    private lateinit var textBox: EditText
    private lateinit var sendBtn: View
    private lateinit var progress: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var chat: RecyclerView
    private lateinit var empty: View
    private val adapter = ChatAdapter()
    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var busy = false

    private val historyListener: () -> Unit = { ui.post { reloadChat() } }

    private val pick = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { u -> try { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) { } }
        if (uris.isNotEmpty()) sendFiles(uris)
    }

    private val ticker = object : Runnable {
        override fun run() { updateHeader(); ui.postDelayed(this, 1500) }
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
        progress = findViewById(R.id.progress)
        progressText = findViewById(R.id.progressText)
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
            runNet {
                Net.sendText(applicationContext, t, p)
                runOnUiThread { textBox.setText("") }
            }
        }
        History.addListener(historyListener)
    }

    override fun onDestroy() { History.removeListener(historyListener); super.onDestroy() }
    override fun onResume() {
        super.onResume()
        if (pairing() == null) { finish(); return }
        Store.setCurrent(this, ip)
        updateHeader(); reloadChat(); updateSendEnabled(); ui.post(ticker)
    }
    override fun onPause() { super.onPause(); ui.removeCallbacks(ticker) }

    private fun showMenu(anchor: View) {
        val p = pairing() ?: return
        val red = ContextCompat.getColor(this, R.color.red)
        fun redTitle(s: String) = SpannableString(s).apply { setSpan(ForegroundColorSpan(red), 0, s.length, 0) }
        val m = PopupMenu(this, anchor)
        m.menu.add(0, 1, 0, getString(R.string.menu_show_code))
        m.menu.add(0, 2, 1, redTitle(getString(R.string.menu_clear)))
        m.menu.add(0, 3, 2, redTitle(getString(R.string.menu_unpair)))
        m.setOnMenuItemClickListener { item ->
            when (item.itemId) {
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

    private fun updateSendEnabled() { sendBtn.isEnabled = !busy && textBox.text.isNotBlank() }

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
        val atBottom = !chat.canScrollVertically(1)
        val grew = items.size > adapter.items.size
        adapter.name = pairing()?.pcName ?: "PC"
        adapter.items = items
        adapter.notifyDataSetChanged()
        if (items.isNotEmpty() && (grew || atBottom)) chat.scrollToPosition(items.size - 1)
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        chat.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun sendFiles(uris: List<Uri>) {
        val p = pairing() ?: return
        runNet {
            uris.forEachIndexed { i, u ->
                Net.sendFile(applicationContext, u, { sent, total ->
                    val pct = if (total > 0) sent * 100 / total else 100
                    runOnUiThread {
                        progressText.visibility = View.VISIBLE
                        progressText.text = getString(R.string.chat_sending_files, i + 1, uris.size, pct.toInt())
                    }
                }, p)
            }
        }
    }

    private fun runNet(work: () -> Unit) {
        if (busy) { toast(getString(R.string.busy)); return }
        busy = true
        updateSendEnabled()
        progress.visibility = View.VISIBLE
        Thread {
            val err = try { work(); null } catch (e: Exception) { getString(R.string.failed, Errors.text(this, e)) }
            runOnUiThread {
                busy = false
                progress.visibility = View.INVISIBLE
                progressText.visibility = View.GONE
                err?.let { toast(it) }
                updateSendEnabled()
            }
        }.start()
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

    private inner class ChatAdapter : RecyclerView.Adapter<ChatAdapter.VH>() {
        var items: List<HistoryItem> = emptyList()
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

        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_bubble, parent, false))

        private fun sameDay(a: Long, b: Long): Boolean {
            val c1 = Calendar.getInstance().apply { timeInMillis = a }
            val c2 = Calendar.getInstance().apply { timeInMillis = b }
            return c1.get(Calendar.YEAR) == c2.get(Calendar.YEAR) && c1.get(Calendar.DAY_OF_YEAR) == c2.get(Calendar.DAY_OF_YEAR)
        }

        override fun onBindViewHolder(h: VH, pos: Int) {
            val it = items[pos]
            val prev = items.getOrNull(pos - 1)
            val next = items.getOrNull(pos + 1)
            val ctx = h.itemView.context
            val out = !it.incoming
            val dm = ctx.resources.displayMetrics

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

            val gravity = if (out) Gravity.END else Gravity.START
            (h.bubble.layoutParams as LinearLayout.LayoutParams).gravity = gravity
            (h.time.layoutParams as LinearLayout.LayoutParams).gravity = gravity
            if (out) {
                h.bubble.setBackgroundResource(R.drawable.bg_bubble_out)
                h.bubble.setPadding((14 * dm.density).toInt(), (9 * dm.density).toInt(), (14 * dm.density).toInt(), (9 * dm.density).toInt())
            } else {
                h.bubble.background = null
                h.bubble.setPadding(0, (2 * dm.density).toInt(), 0, (2 * dm.density).toInt())
            }
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
    }
}
