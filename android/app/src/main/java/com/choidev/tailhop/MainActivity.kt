package com.choidev.tailhop

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

/** 홈: 페어링된 기기 목록(채팅 목록처럼), 수신 켜기/끄기, 언어. */
class MainActivity : AppCompatActivity() {
    private lateinit var list: RecyclerView
    private lateinit var empty: View
    private lateinit var svcState: TextView
    private lateinit var receiveRow: View
    private lateinit var receiveSwitch: SwitchCompat
    private val adapter = DeviceAdapter()
    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var busy = false

    private val historyListener: () -> Unit = { ui.post { reload() } }

    private val scan = registerForActivityResult(ScanContract()) { r -> r.contents?.let { onScanned(it) } }

    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        ReceiverService.start(this)
    }

    private val ticker = object : Runnable {
        override fun run() { updateService(); ui.postDelayed(this, 1500) }
    }

    private val onSwitch = { _: android.widget.CompoundButton, on: Boolean -> setReceiving(on, null) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applySystemBarInsets()
        Notifs.ensureChannels(this)
        list = findViewById(R.id.devices)
        empty = findViewById(R.id.empty)
        svcState = findViewById(R.id.svcState)
        receiveRow = findViewById(R.id.receiveRow)
        receiveSwitch = findViewById(R.id.receiveSwitch)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        findViewById<View>(R.id.addRow).setOnClickListener { startScan() }
        findViewById<View>(R.id.qrBtn).setOnClickListener { startScan() }
        findViewById<View>(R.id.moreBtn).setOnClickListener { showMenu(it) }
        receiveRow.setOnClickListener { receiveSwitch.toggle() }
        receiveSwitch.setOnCheckedChangeListener(onSwitch)
        History.addListener(historyListener)
        requestPerms()
    }

    override fun onDestroy() { History.removeListener(historyListener); super.onDestroy() }
    override fun onResume() {
        super.onResume()
        // 정해 둔 수신 중지 시간이 지났으면(알람이 늦거나 막혔어도) 여기서 다시 켠다.
        if (Receiving.isOn(this) && ReceiverService.state == ReceiverService.State.OFF) ReceiverService.start(this)
        reload(); ui.post(ticker)
    }
    override fun onPause() { super.onPause(); ui.removeCallbacks(ticker) }

    private fun requestPerms() {
        val need = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 33) need += Manifest.permission.POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT <= 28) need += Manifest.permission.WRITE_EXTERNAL_STORAGE
        val missing = need.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) ReceiverService.start(this) else perms.launch(missing.toTypedArray())
    }

    private fun startScan() {
        scan.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt(getString(R.string.scan_prompt)).setBeepEnabled(false).setOrientationLocked(true))
    }

    private fun showMenu(anchor: View) {
        val m = PopupMenu(this, anchor)
        m.menu.add(0, 1, 0, R.string.menu_language)
        if (Store.isPaired(this)) {
            if (Receiving.isOn(this)) m.menu.add(0, 2, 1, R.string.menu_pause_hour)
            else m.menu.add(0, 3, 1, R.string.menu_resume)
        }
        m.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> chooseLanguage()
                2 -> setReceiving(false, Receiving.HOUR_MS)
                3 -> setReceiving(true, null)
            }
            true
        }
        m.show()
    }

    private fun chooseLanguage() {
        val tags = Lang.TAGS
        val names = tags.map { Lang.displayName(this, it) }.toTypedArray()
        val current = tags.indexOf(Lang.chosen(this)).coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle(R.string.menu_language)
            .setSingleChoiceItems(names, current) { d, which ->
                d.dismiss()
                if (which != current) Lang.choose(this, tags[which]) // 화면은 AppCompat이 새 언어로 다시 만든다
            }.setNegativeButton(R.string.cancel, null).show()
    }

    /** 수신 켜기/끄기. 끄면 수신 서비스(대기 포트·상시 알림)를 멈춘다. 보내기는 계속 된다. */
    private fun setReceiving(on: Boolean, durationMs: Long?) {
        if (on) {
            Receiving.resume(this)
            Toast.makeText(this, R.string.toast_resumed, Toast.LENGTH_SHORT).show()
        } else {
            Receiving.pause(this, durationMs)
            Toast.makeText(this, R.string.toast_paused, Toast.LENGTH_SHORT).show()
        }
        updateService()
    }

    private fun reload() {
        val devices = Store.all(this)
        val all = History.all(this)
        val first = devices.firstOrNull()?.pcIp
        adapter.rows = devices.map { d ->
            d to all.firstOrNull { it.dev == d.pcIp || (it.dev == null && d.pcIp == first) }
        }.sortedByDescending { it.second?.ts ?: 0L }
        adapter.notifyDataSetChanged()
        val none = devices.isEmpty()
        empty.visibility = if (none) View.VISIBLE else View.GONE
        list.visibility = if (none) View.GONE else View.VISIBLE
        findViewById<View>(R.id.devHeader).visibility = if (none) View.GONE else View.VISIBLE
        receiveRow.visibility = if (none) View.GONE else View.VISIBLE
        updateService()
    }

    private fun updateService() {
        val on = Receiving.isOn(this)
        svcState.text = if (Store.isPaired(this)) ReceiverService.statusText(this) else ""
        svcState.setTextColor(ContextCompat.getColor(this, if (on) R.color.secondary else R.color.orange))
        if (receiveSwitch.isChecked != on) {
            receiveSwitch.setOnCheckedChangeListener(null)
            receiveSwitch.isChecked = on
            receiveSwitch.setOnCheckedChangeListener(onSwitch)
        }
    }

    private fun onScanned(contents: String) {
        val info = try { Protocol.parsePairUri(contents.trim()) } catch (e: ProtocolException) {
            toast(Errors.text(this, e)); return
        }
        if (busy) return
        busy = true
        toast(getString(R.string.pairing_in_progress))
        Thread {
            try {
                val pending = Net.pair(info) // 아직 저장하지 않는다
                runOnUiThread { busy = false; askConfirmCode(pending) }
            } catch (e: Exception) {
                runOnUiThread { busy = false; toast(getString(R.string.failed, Errors.text(this, e))) }
            }
        }.start()
    }

    /**
     * 페어링 2단계: PC 화면의 확인 코드와 같다고 고를 때만 저장한다. QR만으로는 그 기기가 내 PC인지 알 수 없다
     * (같은 tailnet의 다른 기기가 보여 준 QR일 수도 있다). 다르면 S를 버리고 PC에 취소를 알린다.
     */
    private fun askConfirmCode(pending: PendingPair) {
        val p = pending.pairing
        AlertDialog.Builder(this).setTitle(R.string.pair_confirm_title)
            .setMessage(getString(R.string.pair_confirm_message, p.pcName, p.pcIp, pending.code))
            .setCancelable(false)
            .setPositiveButton(R.string.pair_confirm_yes) { _, _ ->
                if (!pending.confirm { Store.save(applicationContext, it) }) return@setPositiveButton
                ReceiverService.stop(this)
                reload()
                ui.postDelayed({ ReceiverService.start(this) }, 500)
                toast(getString(R.string.pair_saved, p.pcName))
                openChat(p.pcIp)
            }
            .setNegativeButton(R.string.pair_confirm_no) { _, _ ->
                pending.cancel { copy ->
                    Thread {
                        try { Net.sendUnpair(copy) } catch (e: Exception) { } finally { copy.secret.fill(0) }
                    }.start()
                }
                toast(getString(R.string.pair_cancelled))
            }.show()
    }

    private fun openChat(ip: String) {
        Store.setCurrent(this, ip)
        startActivity(Intent(this, ChatActivity::class.java).putExtra(ChatActivity.EXTRA_IP, ip))
    }

    private fun deviceMenu(p: Pairing) {
        AlertDialog.Builder(this).setTitle(p.pcName)
            .setItems(arrayOf(getString(R.string.menu_show_code), getString(R.string.menu_unpair))) { _, which ->
                if (which == 0) showConfirmCode(this, p) else confirmUnpair(this, p) { reload() }
            }.show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private inner class DeviceAdapter : RecyclerView.Adapter<DeviceAdapter.VH>() {
        var rows: List<Pair<Pairing, HistoryItem?>> = emptyList()
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.name)
            val preview: TextView = v.findViewById(R.id.preview)
            val time: TextView = v.findViewById(R.id.time)
        }
        override fun getItemCount() = rows.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_device, parent, false))
        override fun onBindViewHolder(h: VH, pos: Int) {
            val (p, last) = rows[pos]
            val ctx = h.itemView.context
            h.name.text = p.pcName
            h.preview.text = if (last == null) ctx.getString(R.string.device_preview_empty, p.pcIp) else {
                val body = if (last.isText) (last.text ?: "").take(120).replace('\n', ' ')
                else last.name ?: ctx.getString(R.string.file_default)
                if (last.incoming) body else ctx.getString(R.string.preview_me, body)
            }
            // 파일이면 앞에 작은 파일 아이콘(벡터, 글자색으로 칠함)
            val fileIcon = if (last != null && !last.isText) fileIcon(ctx, h.preview.currentTextColor) else null
            h.preview.setCompoundDrawablesRelative(fileIcon, null, null, null)
            h.time.text = last?.let { Lang.shortTime(ctx, it.ts) } ?: ""
            h.itemView.setOnClickListener { openChat(p.pcIp) }
            h.itemView.setOnLongClickListener { deviceMenu(p); true }
        }
    }

    companion object {
        fun fileIcon(ctx: Context, color: Int): android.graphics.drawable.Drawable? {
            val d = androidx.appcompat.content.res.AppCompatResources.getDrawable(ctx, R.drawable.ic_doc)?.mutate() ?: return null
            val px = (14 * ctx.resources.displayMetrics.density).toInt()
            d.setBounds(0, 0, px, px)
            d.setTint(color)
            return d
        }

        fun showConfirmCode(ctx: Context, p: Pairing) {
            AlertDialog.Builder(ctx).setTitle(R.string.confirm_code_title)
                .setMessage(ctx.getString(R.string.confirm_code_message, p.pcName, p.pcIp, p.confirmCode))
                .setPositiveButton(R.string.ok, null).show()
        }

        fun confirmUnpair(ctx: Context, p: Pairing, done: () -> Unit) {
            AlertDialog.Builder(ctx).setTitle(ctx.getString(R.string.unpair_title, p.pcName))
                .setMessage(R.string.unpair_message)
                .setPositiveButton(R.string.unpair_confirm) { _, _ ->
                    History.clearDevice(ctx, p.pcIp)
                    Store.remove(ctx, p.pcIp)
                    ReceiverService.stop(ctx)
                    if (Store.isPaired(ctx)) ReceiverService.start(ctx)
                    done()
                }.setNegativeButton(R.string.cancel, null).show()
        }
    }
}
