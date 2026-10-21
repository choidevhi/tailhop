package com.choidev.tailhop

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
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
    private val updateListener: () -> Unit = { ui.post { updateRow() } }

    private val scan = registerForActivityResult(ScanContract()) { r -> r.contents?.let { onScanned(it) } }

    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        ReceiverService.start(this)
    }

    /** 기기 줄의 용량(§19.1)을 마지막으로 그린 때의 기록 판. */
    private var spacesShown = -1

    private val ticker = object : Runnable {
        override fun run() {
            updateService()
            // PC들이 hello로 알린 용량이 바뀌었으면 기기 줄을 다시 그린다
            if (PeerSpaces.shared.version != spacesShown) { spacesShown = PeerSpaces.shared.version; adapter.notifyDataSetChanged() }
            ui.postDelayed(this, 1500)
        }
    }

    private val onSwitch = { _: android.widget.CompoundButton, on: Boolean -> setReceiving(on, null) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applySystemBarInsets()
        Notifs.ensureChannels(this)
        LocalSpace.init(this)
        MirrorWork.schedule(this) // 기기 쪽 동기화의 주기 맞추기(§19.3, 이미 있으면 그대로)
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
        findViewById<View>(R.id.cloudRow).setOnClickListener { startActivity(Intent(this, CloudActivity::class.java)) }
        receiveRow.setOnClickListener { receiveSwitch.toggle() }
        receiveSwitch.setOnCheckedChangeListener(onSwitch)
        findViewById<View>(R.id.updateRow).setOnClickListener { Updater.found(this)?.let { startUpdate(it) } }
        History.addListener(historyListener)
        Updater.addListener(updateListener)
        requestPerms()
    }

    override fun onDestroy() {
        History.removeListener(historyListener)
        Updater.removeListener(updateListener)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        // 정해 둔 수신 중지 시간이 지났으면(알람이 늦거나 막혔어도) 여기서 다시 켠다.
        if (Receiving.isOn(this) && ReceiverService.state == ReceiverService.State.OFF) ReceiverService.start(this)
        SendService.resumeIfNeeded(this) // 멈춰 있던 보내기(시간 제한·프로세스 종료)를 이어서
        MirrorSync.request(this) // 앱을 엶: 동기화 사본을 맞춘다(허브마다 10분에 한 번까지, §19.3)
        reload(); ui.post(ticker)
        // 업데이트(1.9.0): 6시간에 한 번 조용히 확인. 설치 허용 설정에서 돌아왔으면 이어서 설치한다.
        updateRow()
        if (Store.isPaired(this)) Updater.autoCheckIfDue(this) { ui.post { updateRow() } }
        val waiting = pendingUpdate
        if (waiting != null && packageManager.canRequestPackageInstalls()) { pendingUpdate = null; runUpdate(waiting) }
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
            if (!batteryExempt(this)) m.menu.add(0, 4, 2, R.string.menu_background)
            // 클라우드 모드(1.8.0): 켜면 PC가 이 폰의 Download/TailHop/Cloud를 쓴다. 모으기는 켜져 있을 때만 의미가 있어 그때만 보인다.
            m.menu.add(0, 5, 3, R.string.menu_cloud_host).apply { isCheckable = true; isChecked = CloudMode.isOn(this@MainActivity) }
            if (CloudMode.isOn(this)) m.menu.add(0, 6, 4, R.string.menu_cloud_collect)
                .apply { isCheckable = true; isChecked = CloudMode.collectSetting(this@MainActivity) }
            m.menu.add(0, 7, 5, R.string.menu_update_check)
            m.menu.add(0, 8, 6, R.string.menu_sync)
        }
        m.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> chooseLanguage()
                2 -> setReceiving(false, Receiving.HOUR_MS)
                3 -> setReceiving(true, null)
                4 -> requestBatteryExemption(this)
                5 -> setCloud(!CloudMode.isOn(this))
                6 -> setCollect(!CloudMode.collectSetting(this))
                7 -> checkUpdate()
                8 -> SyncSettings(this).show()
            }
            true
        }
        m.show()
    }

    // ---------- 업데이트(1.9.0, docs/PROTOCOL.md §13) ----------

    /** 페어링한 PC 중 배포 PC가 새 버전을 알렸으면 홈 맨 위에 한 줄. */
    private fun updateRow() {
        val f = Updater.found(this)
        val row = findViewById<View>(R.id.updateRow)
        row.visibility = if (f == null) View.GONE else View.VISIBLE
        if (f == null) return
        findViewById<TextView>(R.id.updateTitle).text = getString(R.string.update_banner, f.offer.version)
        findViewById<TextView>(R.id.updateSub).text = getString(R.string.update_banner_sub, f.pc.pcName)
    }

    /** 메뉴의 "업데이트 확인": 모든 PC에 묻고 결과를 알린다(새 버전이면 설치할지 묻는다). */
    private fun checkUpdate() {
        toast(getString(R.string.update_checking))
        val app = applicationContext
        Thread {
            val (found, any) = try { Updater.check(app) } catch (e: Exception) { null to false }
            ui.post {
                if (isDestroyed) return@post
                updateRow()
                when {
                    found != null -> AlertDialog.Builder(this).setTitle(getString(R.string.update_found_title, found.offer.version))
                        .setMessage(getString(R.string.update_found_msg, found.pc.pcName, found.offer.version,
                            android.text.format.Formatter.formatShortFileSize(this, found.offer.size)))
                        .setPositiveButton(R.string.update_install) { _, _ -> startUpdate(found) }
                        .setNegativeButton(R.string.battery_later, null).show()
                    any -> toast(getString(R.string.update_latest, BuildConfig.VERSION_NAME))
                    else -> toast(getString(R.string.update_check_failed))
                }
            }
        }.start()
    }

    /** 설치 허용 설정에서 돌아오면 이어서 설치할 업데이트. */
    private var pendingUpdate: Updater.Found? = null

    /**
     * 업데이트 시작. "출처를 알 수 없는 앱 설치"를 허용하지 않았으면 먼저 짧게 설명하고 이 앱의 설정 화면으로 보낸다
     * (돌아오면 onResume에서 이어서 설치한다).
     */
    private fun startUpdate(f: Updater.Found) {
        if (packageManager.canRequestPackageInstalls()) { runUpdate(f); return }
        AlertDialog.Builder(this).setTitle(R.string.update_perm_title).setMessage(R.string.update_perm_msg)
            .setPositiveButton(R.string.update_perm_open) { _, _ ->
                pendingUpdate = f
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                } catch (e: Exception) { pendingUpdate = null; toast(getString(R.string.update_failed, getString(R.string.err_update_install))) }
            }
            .setNegativeButton(R.string.cancel, null).show()
    }

    /** 받기(진행률) → 크기·SHA-256·서명 확인 → PackageInstaller. 확인 화면·결과는 시스템과 UpdateReceiver가 맡는다. */
    private fun runUpdate(f: Updater.Found) {
        val d = resources.displayMetrics.density
        val label = TextView(this).apply { text = getString(R.string.update_downloading, 0); textSize = 15f }
        val bar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((22 * d).toInt(), (12 * d).toInt(), (22 * d).toInt(), 0)
            addView(label)
            addView(bar)
        }
        val token = SendToken()
        val dlg = AlertDialog.Builder(this).setTitle(getString(R.string.update_found_title, f.offer.version)).setView(box)
            .setCancelable(false).setNegativeButton(R.string.cancel) { _, _ -> token.cancel() }.show()
        val app = applicationContext
        Thread {
            try {
                val apk = Updater.download(app, f, token) { got, total ->
                    val pct = if (total > 0) (got * 100 / total).toInt().coerceIn(0, 100) else 0
                    ui.post { bar.progress = pct; label.text = getString(R.string.update_downloading, pct) }
                }
                ui.post { label.text = getString(R.string.update_verifying); bar.isIndeterminate = true }
                Updater.verify(app, apk)
                ui.post { label.text = getString(R.string.update_installing) }
                Updater.install(app, apk)
                ui.post { if (!isDestroyed) dlg.dismiss() }
            } catch (e: Exception) {
                ui.post {
                    if (isDestroyed) return@post
                    dlg.dismiss()
                    if (!token.cancelled) toast(getString(R.string.update_failed, Errors.text(this, e)))
                    updateRow()
                }
            }
        }.start()
    }

    /** 이 폰을 클라우드로 쓰기. 받기 서비스가 요청을 받으므로 수신을 멈춘 동안에는 PC가 볼 수 없다고 알린다. */
    private fun setCloud(on: Boolean) {
        CloudMode.setOn(this, on)
        val msg = if (!on) getString(R.string.toast_cloud_off)
        else if (Receiving.isOn(this)) getString(R.string.toast_cloud_on)
        else getString(R.string.toast_cloud_on) + "\n" + getString(R.string.cloud_needs_receiving)
        toast(msg)
    }

    private fun setCollect(on: Boolean) {
        CloudMode.setCollect(this, on)
        toast(getString(if (on) R.string.toast_collect_on else R.string.toast_collect_off))
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
        findViewById<View>(R.id.devCard).visibility = list.visibility // 빈 유리 카드가 남지 않게
        findViewById<View>(R.id.devHeader).visibility = if (none) View.GONE else View.VISIBLE
        receiveRow.visibility = if (none) View.GONE else View.VISIBLE
        findViewById<View>(R.id.cloudRow).visibility = if (none) View.GONE else View.VISIBLE
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
                MirrorWork.schedule(this)
                ReceiverService.stop(this)
                reload()
                ui.postDelayed({ ReceiverService.start(this) }, 500)
                toast(getString(R.string.pair_saved, p.pcName))
                // 배터리 질문을 먼저 보이고(한 번만), 닫으면 대화방을 연다.
                if (!askBatteryOnce { openChat(p.pcIp) }) openChat(p.pcIp)
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

    /**
     * 페어링한 뒤 한 번만: 배터리 최적화에서 빼 달라고 부드럽게 묻는다. 빼 두면 앱을 열지 않아도 받기 서비스가 계속 돌고,
     * PC가 켜졌을 때 백그라운드에서 보내기 서비스를 다시 시작할 수 있다. 거절해도 메뉴에서 다시 고를 수 있다.
     */
    private fun askBatteryOnce(then: () -> Unit): Boolean {
        if (batteryExempt(this)) return false
        val sp = getSharedPreferences("settings", MODE_PRIVATE)
        if (sp.getBoolean(K_BATTERY_ASKED, false)) return false
        sp.edit().putBoolean(K_BATTERY_ASKED, true).apply()
        AlertDialog.Builder(this).setTitle(R.string.battery_title)
            .setMessage(R.string.battery_message)
            // 대화방을 먼저 열고 그 위에 시스템 확인 창을 띄운다.
            .setPositiveButton(R.string.battery_allow) { _, _ -> then(); requestBatteryExemption(this) }
            .setNegativeButton(R.string.battery_later) { _, _ -> then() }
            .setOnCancelListener { then() }
            .show()
        return true
    }

    private fun openChat(ip: String) {
        Store.setCurrent(this, ip)
        startActivity(Intent(this, ChatActivity::class.java).putExtra(ChatActivity.EXTRA_IP, ip))
    }

    private fun deviceMenu(p: Pairing) {
        AlertDialog.Builder(this).setTitle(p.pcName)
            .setItems(arrayOf(getString(R.string.menu_cloud_open), getString(R.string.menu_show_code), getString(R.string.menu_unpair))) { _, which ->
                when (which) {
                    0 -> openCloud(this, p.pcIp)
                    1 -> showConfirmCode(this, p)
                    else -> confirmUnpair(this, p) { reload() }
                }
            }.show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private inner class DeviceAdapter : RecyclerView.Adapter<DeviceAdapter.VH>() {
        var rows: List<Pair<Pairing, HistoryItem?>> = emptyList()
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.name)
            val preview: TextView = v.findViewById(R.id.preview)
            val time: TextView = v.findViewById(R.id.time)
            val space: TextView = v.findViewById(R.id.space)
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
            // 그 PC가 hello로 알린 용량(§19.1, 받았을 때만)
            val sp = PeerSpaces.shared.get(p.pcIp)?.space
            h.space.visibility = if (sp == null) View.GONE else View.VISIBLE
            if (sp != null) h.space.text = ctx.getString(R.string.space_free_total,
                android.text.format.Formatter.formatShortFileSize(ctx, sp.free), android.text.format.Formatter.formatShortFileSize(ctx, sp.total))
            h.itemView.setOnClickListener { openChat(p.pcIp) }
            h.itemView.setOnLongClickListener { deviceMenu(p); true }
        }
    }

    companion object {
        private const val K_BATTERY_ASKED = "batteryAsked"

        fun batteryExempt(ctx: Context): Boolean =
            ctx.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(ctx.packageName) == true

        /**
         * 배터리 최적화 제외 요청(시스템 확인 창). 이 앱은 스토어가 아니라 직접 설치하는 앱이고, 페어링한 내 기기의 연결을
         * 기다리는 것이 핵심 기능이라 직접 묻는다. 창을 띄울 수 없는 기기는 배터리 최적화 목록 화면을 연다.
         */
        @SuppressLint("BatteryLife")
        fun requestBatteryExemption(ctx: Context) {
            val ask = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.packageName))
            try { ctx.startActivity(ask) } catch (e: Exception) {
                try { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (x: Exception) { }
            }
        }

        fun fileIcon(ctx: Context, color: Int): android.graphics.drawable.Drawable? {
            val d = androidx.appcompat.content.res.AppCompatResources.getDrawable(ctx, R.drawable.ic_doc)?.mutate() ?: return null
            val px = (14 * ctx.resources.displayMetrics.density).toInt()
            d.setBounds(0, 0, px, px)
            d.setTint(color)
            return d
        }

        /** 그 PC의 클라우드를 바로 연다(맨 위 폴더). */
        fun openCloud(ctx: Context, ip: String) {
            ctx.startActivity(Intent(ctx, CloudActivity::class.java).putExtra(CloudActivity.EXTRA_IP, ip))
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
                    SendService.forgetDevice(ctx, p.pcIp)
                    val app = ctx.applicationContext
                    // 받다 만 조각 파일과 그 PC의 동기화 사본(§19.3)도 지운다
                    if (MirrorStatus.running == p.pcIp) MirrorSync.cancelRunning()
                    Thread { Transfers.forgetPeer(app, p.pcIp); Mirror.delete(app, p.pcIp) }.start()
                    PeerSpaces.shared.forget(p.pcIp)
                    Store.remove(ctx, p.pcIp)
                    MirrorWork.schedule(ctx)
                    ReceiverService.stop(ctx)
                    if (Store.isPaired(ctx)) ReceiverService.start(ctx)
                    done()
                }.setNegativeButton(R.string.cancel, null).show()
        }
    }
}
