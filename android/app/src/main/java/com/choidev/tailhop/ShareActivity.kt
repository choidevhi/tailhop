package com.choidev.tailhop

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * 공유 대상. 다른 앱이 보낸 텍스트/파일을 PC로 보내기 전에 **반드시 확인 창**을 띄운다(받을 기기와 내용 요약).
 * 사용자가 "보내기"를 눌러야만 보낸다 — 다른 앱이 TailHop을 시켜 몰래 PC로 보내지 못하게(받은 글은 PC 클립보드에 복사된다).
 * 수신 중지와 상관없이 보낸다.
 */
class ShareActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = intent
        val uris = ArrayList<Uri>()
        var text: String? = null
        try {
            when (intent.action) {
                Intent.ACTION_SEND -> {
                    stream(intent)?.let { uris.add(it) }
                    if (uris.isEmpty()) text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                }
                Intent.ACTION_SEND_MULTIPLE -> uris.addAll(streams(intent))
            }
        } catch (e: Exception) { }
        // 다른 앱이 우리 앱 내부 파일(provider)을 가리키게 하는 것을 막는다.
        uris.removeAll { !isForeignContent(it) }
        if (uris.isEmpty() && text.isNullOrEmpty()) { toast(getString(R.string.share_nothing)); finish(); return }
        val devices = Store.all(this)
        if (devices.isEmpty()) { toast(getString(R.string.share_pair_first)); finish(); return }
        confirm(devices, text, uris)
    }

    /** content: 이고, authority가 이 앱의 provider가 아니면 true. `0@<authority>` 같은 사용자 번호 접두도 떼고 비교한다. */
    private fun isForeignContent(uri: Uri): Boolean {
        if (!"content".equals(uri.scheme, ignoreCase = true)) return false
        val authority = uri.authority
        if (Protocol.isOwnAuthority(authority, listOf("$packageName.files"))) return false
        // 우리 앱의 다른 provider(라이브러리가 넣은 것 포함)도 막는다.
        val owner = try {
            @Suppress("DEPRECATION")
            packageManager.resolveContentProvider(authority!!.substringAfterLast('@'), 0)?.packageName
        } catch (e: Exception) { null }
        return owner != packageName
    }

    private fun clean(s: String): String {
        val sb = StringBuilder()
        s.codePoints().forEach { if (it == '\n'.code || !Protocol.isInvisible(it)) sb.appendCodePoint(it) }
        return sb.toString()
    }

    private fun summary(text: String?, uris: List<Uri>): String {
        if (text != null) {
            val preview = if (text.length > 300) text.take(300) + "…" else text
            return getString(R.string.share_confirm_text, clean(preview))
        }
        val names = uris.take(5).map { u ->
            val n = try { Net.fileMeta(this, u).name } catch (e: Exception) { u.lastPathSegment ?: "file" }
            Protocol.sanitizeFilename(n)
        }
        val more = if (uris.size > names.size) "\n…" else ""
        return resources.getQuantityString(R.plurals.share_confirm_files, uris.size, uris.size) +
            "\n" + names.joinToString("\n") { "· $it" } + more
    }

    private fun confirm(devices: List<Pairing>, text: String?, uris: List<Uri>) {
        val d = resources.displayMetrics.density
        val pad = (22 * d).toInt()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, (8 * d).toInt(), pad, 0) }
        box.addView(TextView(this).apply {
            this.text = summary(text, uris)
            maxLines = 10
            ellipsize = TextUtils.TruncateAt.END
            textSize = 15f
        })
        box.addView(TextView(this).apply {
            this.text = getString(R.string.share_confirm_target)
            textSize = 13f
            setPadding(0, (16 * d).toInt(), 0, (4 * d).toInt())
        })
        val current = Store.load(this)?.pcIp
        val group = RadioGroup(this)
        val ids = devices.map { dev ->
            val rb = RadioButton(this).apply { id = View.generateViewId(); this.text = getString(R.string.share_device_row, dev.pcName, dev.pcIp) }
            group.addView(rb)
            rb.id
        }
        group.check(ids[devices.indexOfFirst { it.pcIp == current }.coerceAtLeast(0)])
        box.addView(group)
        val scroll = ScrollView(this).apply { addView(box) }

        AlertDialog.Builder(this).setTitle(R.string.share_confirm_title)
            .setView(scroll)
            .setPositiveButton(R.string.share_send) { _, _ ->
                val target = devices[ids.indexOf(group.checkedRadioButtonId).coerceAtLeast(0)]
                send(target, text, uris)
            }
            .setNegativeButton(R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun send(target: Pairing, text: String?, uris: List<Uri>) {
        Store.setCurrent(this, target.pcIp) // 사용자가 고른 기기를 다음 기본값으로
        toast(getString(R.string.share_sending))
        val app = applicationContext
        val res = this // 토스트 문장은 이 화면의 언어로
        Thread {
            val msg = try {
                if (text != null) Net.sendText(app, text, target) else uris.forEach { Net.sendFile(app, it, null, target) }
                if (text != null) res.getString(R.string.share_text_sent)
                else res.resources.getQuantityString(R.plurals.share_files_sent, uris.size, uris.size)
            } catch (e: Exception) { res.getString(R.string.share_failed, Errors.text(res, e)) }
            runOnUiThread { toast(msg); finish() }
        }.start()
    }

    private fun toast(s: String) = Toast.makeText(applicationContext, s, Toast.LENGTH_LONG).show()

    @Suppress("DEPRECATION")
    private fun stream(i: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else i.getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun streams(i: Intent): List<Uri> =
        (if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)).orEmpty().filterNotNull()
}
