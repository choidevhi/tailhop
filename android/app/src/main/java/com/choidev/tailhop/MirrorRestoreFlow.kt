package com.choidev.tailhop

import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.util.Collections

/**
 * 이전 사본을 허브로 복원(§19.5): 지문·화면 잠금으로 연 뒤, 저장소가 열려 있고 동기화를 아는 허브 PC를 골라 모든 파일을 원래 경로로
 * cloud_put(§12, 없는 폴더는 허브가 만든다)으로 하나씩 올린다. 같은 이름이 있으면 허브가 번호를 붙인다. 파일 없이 남은 폴더는 만들기만 한다.
 * 다 올리면 올린 맨 위 항목들을 sync_set on으로 다시 동기화 대상으로 만든다.
 * 사본은 메모리에서 조각씩 풀며 바로 보낸다(평문을 디스크·캐시에 쓰지 않는다). 진행률·취소가 있는 대화 상자(캡처 막기) 하나로 돈다 —
 * 포그라운드 서비스·wakelock을 쓰지 않으니 화면을 켜 두고 기다리면 된다.
 */
class MirrorRestoreFlow(private val act: AppCompatActivity, private val archive: MirrorArchive.Info, private val after: () -> Unit) {
    private val ui = Handler(Looper.getMainLooper())
    private val app = act.applicationContext

    fun start() {
        MirrorUnlock.ensure(act) { ok -> if (ok && !act.isDestroyed) prepare() }
    }

    private fun toast(text: String) { if (!act.isDestroyed) Toast.makeText(act, text, Toast.LENGTH_LONG).show() }

    /** 사본을 풀어 계획을 세우고 지금 열린 허브를 찾는다(작업 스레드). */
    private fun prepare() {
        toast(act.getString(R.string.sync_restore_checking))
        Thread({
            val plan = try { MirrorRestore.plan(Mirror.openArchive(app, archive.id).entries()) } catch (e: Exception) {
                ui.post { toast(act.getString(R.string.sync_restore_failed, Errors.text(act, e))) }
                return@Thread
            }
            val hubs = if (plan.files.isEmpty() && plan.dirs.isEmpty()) emptyList() else openHubs()
            ui.post {
                if (act.isDestroyed) return@post
                when {
                    plan.files.isEmpty() && plan.dirs.isEmpty() -> toast(act.getString(R.string.sync_restore_empty))
                    hubs.isEmpty() -> toast(act.getString(R.string.sync_restore_no_hub))
                    hubs.size == 1 -> confirm(hubs[0], plan)
                    else -> AlertDialog.Builder(act).setTitle(R.string.sync_restore_pick)
                        .setItems(hubs.map { it.pcName }.toTypedArray()) { _, i -> confirm(hubs[i], plan) }
                        .setNegativeButton(R.string.cancel, null).showSecure()
                }
            }
        }, "tailhop-restore-prepare").start()
    }

    /** 페어링한 PC 중 지금 닿고, 저장소가 열려 있고(store "open"), caps에 "sync"·"xfer"가 있는 것(동시에 물어 본다). */
    private fun openHubs(): List<Pairing> {
        val pcs = Store.all(app)
        val ok = Collections.synchronizedSet(HashSet<String>())
        val threads = pcs.map { p ->
            Thread({
                val h = try { Net.hello(p, null, null) } catch (e: Exception) { null }
                if (h != null && h.store == HubStore.OPEN && "sync" in h.caps && h.xfer) ok += p.pcIp
            }, "tailhop-restore-hello")
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(HELLO_WAIT_MS) }
        return pcs.filter { it.pcIp in ok }
    }

    private fun confirm(p: Pairing, plan: MirrorRestore.Plan) {
        AlertDialog.Builder(act).setTitle(R.string.sync_restore)
            .setMessage(act.getString(R.string.sync_restore_confirm, plan.files.size, Formatter.formatShortFileSize(act, plan.bytes), p.pcName))
            .setPositiveButton(R.string.sync_restore) { _, _ -> MirrorUnlock.ensure(act) { ok -> if (ok && !act.isDestroyed) run(p, plan) } }
            .setNegativeButton(R.string.cancel, null).showSecure()
    }

    private fun run(p: Pairing, plan: MirrorRestore.Plan) {
        val d = act.resources.displayMetrics.density
        val label = TextView(act).apply { text = act.getString(R.string.sync_restore_starting); textSize = 15f }
        val bar = ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((22 * d).toInt(), (12 * d).toInt(), (22 * d).toInt(), 0)
            addView(label)
            addView(bar)
        }
        val token = SendToken()
        val dlg = AlertDialog.Builder(act).setTitle(act.getString(R.string.sync_restore_title, p.pcName)).setView(box)
            .setCancelable(false).setNegativeButton(R.string.cancel) { _, _ -> token.cancel() }.create().secure()
        dlg.show()
        val total = plan.bytes
        val count = plan.files.size
        fun progress(i: Int, bytes: Long) = ui.post {
            if (act.isDestroyed) return@post
            label.text = act.getString(R.string.sync_restore_progress, i, count,
                Formatter.formatShortFileSize(act, bytes), Formatter.formatShortFileSize(act, total))
            bar.progress = if (total > 0) (bytes * 1000 / total).toInt().coerceIn(0, 1000) else 0
        }
        Thread({
            var done = 0
            var failed = 0
            var lastCode: String? = null
            var fatal: Exception? = null
            var syncFail: Exception? = null
            val tops = LinkedHashSet<String>()
            try {
                Mirror.openArchive(app, archive.id).snapshot().use { snap ->
                    var base = 0L
                    for ((i, f) in plan.files.withIndex()) {
                        if (token.cancelled) break
                        progress(i, base)
                        try {
                            val at = base
                            val reply = Net.sendStreamXfer({ snap.open(f) }, Net.FileMeta(CloudPath.name(f.path), f.size), p, Xfer.newXfer(), 0,
                                token, { sent, _ -> progress(i, at + sent) }, cloudDir = CloudPath.parent(f.path))
                            tops += MirrorRestore.top(f.path, reply.opt("name") as? String)
                            done++
                        } catch (e: Exception) {
                            if (token.cancelled) break
                            val code = (e as? ProtocolException)?.code
                            // 허브에 닿지 않음·허브가 잠김 등은 모두 멈춘다. 그 파일만의 거부(이름 등)는 건너뛰고 잇는다.
                            if (code == null || code in FATAL) { fatal = e; break }
                            failed++
                            lastCode = code
                        }
                        base += f.size
                    }
                    progress(count, base)
                }
                if (!token.cancelled && fatal == null) {
                    for (dir in plan.dirs) {
                        try { Net.cloudMkdir(p, dir); tops += MirrorRestore.top(dir, null) } catch (e: ProtocolException) { failed++; lastCode = e.code }
                    }
                    if (tops.isNotEmpty()) try { Net.syncSet(p, tops.toList(), true) } catch (e: Exception) { syncFail = e }
                }
            } catch (e: Exception) { if (fatal == null) fatal = e }
            ui.post {
                if (act.isDestroyed) return@post
                dlg.dismiss()
                val msg = when {
                    token.cancelled -> act.getString(R.string.sync_restore_cancelled, done)
                    fatal != null -> act.getString(R.string.sync_restore_stopped, done, Errors.text(act, fatal!!))
                    failed > 0 -> act.getString(R.string.sync_restore_partial, done, failed, Errors.text(act, ProtocolException(lastCode ?: "storage")))
                    else -> act.getString(R.string.sync_restore_done, done, p.pcName)
                }
                val more = syncFail?.let { "\n\n" + act.getString(R.string.sync_restore_sync_failed, Errors.text(act, it)) } ?: ""
                AlertDialog.Builder(act).setTitle(R.string.sync_restore).setMessage(msg + more).setPositiveButton(R.string.ok, null).showSecure()
                after()
            }
        }, "tailhop-restore").start()
    }

    companion object {
        private const val HELLO_WAIT_MS = 15_000L
        /** 하나라도 나면 복원을 멈추는 오류(허브에 닿지 않음·허브 저장소가 닫힘·디스크 부족·사본이 잠김). */
        private val FATAL = setOf("connect_failed", "not_ts_host", "pc_forgot", "disconnected", "unreadable_reply", "bad_reply",
            "auth_failed", "store_locked", "store_missing", "cloud_off", "cloud_unsupported", "disk_full", "mirror_locked")
    }
}
