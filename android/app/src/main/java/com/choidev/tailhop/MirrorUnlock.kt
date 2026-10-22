package com.choidev.tailhop

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog

/**
 * 사본 열기(§19.3 2.3.1): 시스템 생체 인증 창(지문 등 강한 생체 또는 화면 잠금 PIN·패턴·비밀번호)을 띄우고, 통과하면 MirrorLock을 연다.
 * 인증은 시스템이 하고 이 앱은 결과만 받는다(시도 횟수 제한도 시스템·하드웨어가 한다). androidx.biometric 없이 플랫폼 API로:
 * Android 11+ BiometricPrompt(강한 생체 | 화면 잠금), 10은 BiometricPrompt(화면 잠금 허용), 8–9는 시스템 화면 잠금 확인 화면.
 * 이미 열려 있고 Keystore 인증 시간(5분) 안이면 묻지 않는다. 기기 열쇠가 무효가 되었으면 사본을 지우고 이유를 알린다.
 */
object MirrorUnlock {
    private val ui = Handler(Looper.getMainLooper())
    private val waiting = ArrayList<(Boolean) -> Unit>()
    private var busy = false

    /** done(true) = 열림(읽어도 된다). UI 스레드에서 부르고 UI 스레드로 답한다. 이미 묻는 중이면 그 결과를 같이 받는다. */
    fun ensure(act: ComponentActivity, done: (Boolean) -> Unit) {
        waiting += done
        if (busy) return
        busy = true
        val app = act.applicationContext
        Thread({
            DeviceMirrorKeys.init(app)
            val st = DeviceMirrorKeys.state()
            ui.post {
                when {
                    st == DeviceMirrorKeys.State.INVALID -> {
                        MirrorSync.cancelRunning()
                        Thread({
                            Mirror.resetAll(app)
                            ui.post { finish(false); if (!act.isDestroyed) MirrorNoticeUi.show(act) }
                        }, "tailhop-mirror-reset").start()
                    }
                    st == DeviceMirrorKeys.State.NONE -> { toast(act, R.string.err_mirror_setup); finish(false) }
                    st == DeviceMirrorKeys.State.OPEN && MirrorLock.isOpen -> finish(true)
                    act.isFinishing || act.isDestroyed -> finish(false)
                    else -> prompt(act) { ok ->
                        if (!ok) { finish(false); return@prompt }
                        MirrorLock.opened()
                        Thread({
                            val now = DeviceMirrorKeys.state()
                            ui.post {
                                if (now == DeviceMirrorKeys.State.OPEN) finish(true)
                                else { MirrorLock.lock(); toast(act, R.string.err_mirror_locked); finish(false) }
                            }
                        }, "tailhop-mirror-check").start()
                    }
                }
            }
        }, "tailhop-mirror-state").start()
    }

    private fun finish(ok: Boolean) {
        busy = false
        val all = ArrayList(waiting)
        waiting.clear()
        all.forEach { it(ok) }
    }

    private fun toast(act: Activity, res: Int) { if (!act.isDestroyed) Toast.makeText(act, res, Toast.LENGTH_LONG).show() }

    private fun prompt(act: ComponentActivity, cb: (Boolean) -> Unit) {
        val title = act.getString(R.string.mirror_unlock_title)
        val sub = act.getString(R.string.mirror_unlock_sub)
        if (Build.VERSION.SDK_INT >= 29) {
            val b = BiometricPrompt.Builder(act).setTitle(title).setSubtitle(sub).setConfirmationRequired(false)
            if (Build.VERSION.SDK_INT >= 30) {
                b.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            } else {
                @Suppress("DEPRECATION")
                b.setDeviceCredentialAllowed(true)
            }
            var answered = false
            val once = { ok: Boolean -> if (!answered) { answered = true; cb(ok) } }
            try {
                b.build().authenticate(CancellationSignal(), act.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { once(true) }
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { once(false) }
                    // onAuthenticationFailed: 한 번 틀림 — 창은 그대로 다시 받는다
                })
            } catch (e: Exception) { once(false) }
            return
        }
        // Android 8–9: 시스템 화면 잠금 확인 화면(지문을 받는 기기도 있다)
        val km = act.getSystemService(KeyguardManager::class.java)
        @Suppress("DEPRECATION")
        val intent: Intent? = km?.createConfirmDeviceCredentialIntent(title, sub)
        if (intent == null) { cb(false); return }
        var launcher: ActivityResultLauncher<Intent>? = null
        launcher = act.activityResultRegistry.register("tailhop_mirror_unlock", ActivityResultContracts.StartActivityForResult()) { res ->
            launcher?.unregister()
            cb(res.resultCode == Activity.RESULT_OK)
        }
        try { launcher.launch(intent) } catch (e: Exception) { launcher.unregister(); cb(false) }
    }
}

/** 쌓인 사본 소식(MirrorNotice)을 유리 대화 상자 하나로 보인다(캡처 막기). */
object MirrorNoticeUi {
    fun show(act: Activity) {
        val codes = MirrorNotice.take(act)
        if (codes.isEmpty()) return
        AlertDialog.Builder(act).setTitle(R.string.mirror_notice_title)
            .setMessage(codes.joinToString("\n\n") { MirrorNotice.text(act, it) })
            .setPositiveButton(R.string.ok, null).showSecure()
    }
}
