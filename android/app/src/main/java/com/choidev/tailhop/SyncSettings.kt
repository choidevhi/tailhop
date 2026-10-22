package com.choidev.tailhop

import android.text.format.Formatter
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat

/**
 * 홈 메뉴의 "이 기기 동기화"(2.3.0, §19.3): 유리 대화 상자 하나에
 * - "이 기기에 동기화 받기"(끄면 확인 뒤 지금 사본을 모두 지운다 — 이전 사본은 남는다), "모바일 데이터로도 동기화",
 * - 허브(페어링한 PC)마다 사본 사용량(파일 수·디스크), 마지막 동기화 시각·상태, "지금 동기화"·"사본 지우기",
 * - 2.3.1(§19.5) 이전 사본: 허브 이름·파일 수·크기·보관한 날, "허브로 복원"·"지우기".
 * 파일 이름·경로는 보이지 않는다(개수와 크기만 — 장부에서 지문 없이 읽는다). 상태는 맞추기가 진행되는 동안 저절로 바뀐다(MirrorStatus).
 */
class SyncSettings(private val act: AppCompatActivity) {
    private val d = act.resources.displayMetrics.density
    private val hubs = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    private val olds = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    /** 허브 IP → (사용량 줄, 상태 줄). */
    private val lines = HashMap<String, Pair<TextView, TextView>>()
    private val listener: () -> Unit = { act.runOnUiThread { refreshStatus() } }
    private lateinit var dlg: AlertDialog

    fun show() {
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((22 * d).toInt(), (4 * d).toInt(), (22 * d).toInt(), (8 * d).toInt())
        }
        lateinit var receive: SwitchCompat
        receive = switchRow(box, R.string.sync_receive, R.string.sync_receive_sub, MirrorSettings.isOn(act)) { on ->
            if (on) {
                MirrorSettings.setOn(act, true)
                MirrorSync.request(act)
                rebuild()
            } else askOff { ok -> if (ok) rebuild() else receive.isChecked = true }
        }
        switchRow(box, R.string.sync_mobile, R.string.sync_mobile_sub, MirrorSettings.mobile(act)) { on -> MirrorSettings.setMobile(act, on) }
        box.addView(header(R.string.sync_hubs))
        box.addView(hubs)
        box.addView(header(R.string.sync_archives))
        box.addView(olds)
        rebuild()
        refreshArchives()
        dlg = AlertDialog.Builder(act).setTitle(R.string.menu_sync).setView(ScrollView(act).apply { addView(box) })
            .setPositiveButton(R.string.close, null).create().secure()
        dlg.setOnShowListener { MirrorStatus.addListener(listener) }
        dlg.setOnDismissListener { MirrorStatus.removeListener(listener) }
        dlg.show()
    }

    private fun header(res: Int) = TextView(act).apply {
        text = act.getString(res)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(ContextCompat.getColor(act, R.color.secondary))
        setPadding(0, (18 * d).toInt(), 0, (4 * d).toInt())
    }

    /** 설정 한 줄(제목·설명·스위치). 줄을 눌러도 바뀐다. */
    private fun switchRow(box: LinearLayout, title: Int, sub: Int, checked: Boolean, onChange: (Boolean) -> Unit): SwitchCompat {
        val sw = SwitchCompat(act).apply {
            isChecked = checked
            contentDescription = act.getString(title)
        }
        val row = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = (56 * d).toInt()
            setPadding(0, (6 * d).toInt(), 0, (6 * d).toInt())
            setBackgroundResource(R.drawable.bg_row_press)
            isClickable = true
            setOnClickListener { sw.toggle() }
        }
        row.addView(LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(act).apply {
                text = act.getString(title)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                setTextColor(ContextCompat.getColor(act, R.color.label))
            })
            addView(TextView(act).apply {
                text = act.getString(sub)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(ContextCompat.getColor(act, R.color.secondary))
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(sw, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { marginStart = (8 * d).toInt() })
        sw.setOnCheckedChangeListener { _, on -> onChange(on) }
        box.addView(row)
        return sw
    }

    /** 끄기 확인: 지금 사본을 모두 지운다(이전 사본은 남는다). done(끔). */
    private fun askOff(done: (Boolean) -> Unit) {
        var decided = false
        AlertDialog.Builder(act).setTitle(R.string.sync_off_title).setMessage(R.string.sync_off_msg)
            .setPositiveButton(R.string.sync_off_confirm) { _, _ ->
                decided = true
                MirrorSettings.setOn(act, false)
                done(true)
            }
            .setNegativeButton(R.string.cancel, null)
            .setOnDismissListener { if (!decided) done(false) }
            .showSecure()
    }

    /** 카드 하나(제목·줄들·버튼 줄). */
    private fun card(title: String): LinearLayout {
        val card = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_thumb)
            setPadding((14 * d).toInt(), (10 * d).toInt(), (14 * d).toInt(), (10 * d).toInt())
        }
        card.addView(TextView(act).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setTextColor(ContextCompat.getColor(act, R.color.label))
            maxLines = 1
        })
        return card
    }

    private fun buttons(card: LinearLayout, vararg btns: Pair<Int, () -> Unit>) {
        val row = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (8 * d).toInt(), 0, 0)
        }
        btns.forEachIndexed { i, (label, onClick) ->
            row.addView(button(label, onClick), LinearLayout.LayoutParams(0, (36 * d).toInt(), 1f)
                .apply { if (i < btns.size - 1) marginEnd = (8 * d).toInt() })
        }
        card.addView(row)
    }

    private fun cardParams() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { bottomMargin = (8 * d).toInt() }

    /** 허브 줄들을 다시 만든다. 사용량은 장부를 열어야 해서(디스크·Keystore) 작업 스레드에서 채운다. */
    private fun rebuild() {
        hubs.removeAllViews()
        lines.clear()
        val pcs = Store.all(act)
        if (pcs.isEmpty()) {
            hubs.addView(small(act.getString(R.string.sync_no_hubs)))
            return
        }
        for (p in pcs) {
            val card = card(p.pcName)
            val usage = small(act.getString(R.string.cloud_loading))
            val status = small("")
            card.addView(usage)
            card.addView(status)
            buttons(card,
                R.string.sync_now to {
                    if (!MirrorSettings.isOn(act)) toast(R.string.sync_is_off)
                    else {
                        MirrorSync.request(act, p.pcIp, MirrorSync.Why.MANUAL)
                        toast(R.string.sync_started)
                    }
                },
                R.string.sync_clear to { askClear(p) })
            hubs.addView(card, cardParams())
            lines[p.pcIp] = usage to status
        }
        refreshStatus()
        refreshUsage()
    }

    private fun small(t: String) = TextView(act).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(ContextCompat.getColor(act, R.color.secondary))
        setPadding(0, (2 * d).toInt(), 0, 0)
    }

    private fun button(label: Int, onClick: () -> Unit) = Button(act, null, 0, R.style.Btn_Tinted).apply {
        text = act.getString(label)
        setOnClickListener { onClick() }
    }

    private fun toast(res: Int) = Toast.makeText(act, res, Toast.LENGTH_SHORT).show()

    private fun askClear(p: Pairing) {
        AlertDialog.Builder(act).setTitle(act.getString(R.string.sync_clear_title, p.pcName)).setMessage(R.string.sync_clear_msg)
            .setPositiveButton(R.string.sync_clear) { _, _ ->
                val app = act.applicationContext
                Thread({
                    MirrorSync.whileStopped { Mirror.delete(app, p.pcIp) }
                    act.runOnUiThread { if (!act.isDestroyed) { toast(R.string.sync_cleared); refreshUsage() } }
                }, "tailhop-mirror-clear").start()
            }
            .setNegativeButton(R.string.cancel, null).showSecure()
    }

    /** 사용량(파일 수 · 디스크 크기, 장부에서 — 지문 없이). 사본이 없으면 "사본 없음". */
    private fun refreshUsage() {
        val app = act.applicationContext
        val ips = lines.keys.toList()
        Thread({
            val got = ips.associateWith { ip ->
                try { Mirror.peek(app, ip)?.usage() } catch (e: Exception) { null }
            }
            act.runOnUiThread {
                if (act.isDestroyed) return@runOnUiThread
                for ((ip, u) in got) lines[ip]?.first?.text = if (u == null || u.first == 0) act.getString(R.string.sync_usage_none)
                else act.getString(R.string.sync_usage, u.first, Formatter.formatShortFileSize(act, u.second))
            }
        }, "tailhop-mirror-usage").start()
    }

    /** 마지막 동기화 시각과 상태(맞추는 중이면 남은 수). */
    private fun refreshStatus() {
        if (act.isDestroyed) return
        for ((ip, v) in lines) v.second.text = statusText(ip)
        // 맞추기가 끝나면 사용량도 바뀐다(허브가 바뀌어 이전 사본으로 옮겼을 수도 있다)
        if (MirrorStatus.running == null && ::dlg.isInitialized && dlg.isShowing) { refreshUsage(); refreshArchives() }
    }

    private fun statusText(ip: String): String {
        if (MirrorStatus.running == ip) {
            val n = MirrorStatus.left
            return if (n > 0) act.getString(R.string.sync_running, n) else act.getString(R.string.sync_running_plain)
        }
        val r = MirrorStatus.get(act, ip)
        val last = if (r.okAt > 0) act.getString(R.string.sync_last_ok, Lang.shortTime(act, r.okAt)) else act.getString(R.string.sync_never)
        val why = when (r.code) {
            null, MirrorStatus.OK -> null
            MirrorStatus.OFFLINE -> act.getString(R.string.sync_st_offline)
            MirrorStatus.STORE -> act.getString(R.string.sync_st_store)
            MirrorStatus.UNSUPPORTED -> act.getString(R.string.sync_st_unsupported)
            MirrorStatus.METERED -> act.getString(R.string.sync_st_metered)
            else -> act.getString(R.string.sync_st_failed, Errors.text(act, ProtocolException(r.code)))
        }
        return if (why == null) last else last + "\n" + why
    }

    // ---------- 이전 사본(§19.5) ----------

    /** 이전 사본 카드들(장부만 읽는다 — 지문 없이). */
    private fun refreshArchives() {
        val app = act.applicationContext
        Thread({
            val list = try { Mirror.archives(app) } catch (e: Exception) { emptyList() }
            act.runOnUiThread {
                if (act.isDestroyed) return@runOnUiThread
                olds.removeAllViews()
                if (list.isEmpty()) { olds.addView(small(act.getString(R.string.sync_archives_none))); return@runOnUiThread }
                for (a in list) {
                    val card = card(a.hub ?: act.getString(R.string.sync_archive_unknown))
                    val size = Formatter.formatShortFileSize(act, a.bytes)
                    card.addView(small(if (a.readable) act.getString(R.string.sync_archive_line, a.files, size, Lang.shortTime(act, a.at))
                    else act.getString(R.string.sync_archive_damaged, size)))
                    if (a.readable && a.files > 0) buttons(card,
                        R.string.sync_restore to { MirrorRestoreFlow(act, a) { refreshArchives() }.start() },
                        R.string.sync_archive_delete to { askDeleteArchive(a) })
                    else buttons(card, R.string.sync_archive_delete to { askDeleteArchive(a) })
                    olds.addView(card, cardParams())
                }
            }
        }, "tailhop-mirror-archives").start()
    }

    private fun askDeleteArchive(a: MirrorArchive.Info) {
        AlertDialog.Builder(act).setTitle(act.getString(R.string.sync_archive_delete_title, a.hub ?: act.getString(R.string.sync_archive_unknown)))
            .setMessage(R.string.sync_archive_delete_msg)
            .setPositiveButton(R.string.sync_archive_delete) { _, _ ->
                val app = act.applicationContext
                Thread({
                    Mirror.deleteArchive(app, a.id)
                    act.runOnUiThread { if (!act.isDestroyed) { toast(R.string.sync_archive_deleted); refreshArchives() } }
                }, "tailhop-archive-delete").start()
            }
            .setNegativeButton(R.string.cancel, null).showSecure()
    }
}
