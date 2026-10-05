package com.choidev.tailhop

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * 수신 중지 설정. 끄면 수신 서비스(대기 소켓·상시 알림)를 멈춘다. 보내기는 서비스와 상관없이 된다.
 * 앱을 다시 켜거나 기기를 재부팅해도 유지된다. 시간을 정하면 그때 다시 켠다:
 * 알람(정확하지 않은 알람)이 울리면 서비스를 다시 시작하고, 백그라운드 시작이 막히면 "탭하면 다시 받기" 알림을 띄운다.
 * 앱을 열거나 재부팅할 때도 시간이 지났는지 확인한다.
 */
object Receiving {
    private const val PREFS = "settings"
    private const val K_ON = "receiving"
    private const val K_UNTIL = "pauseUntil"
    const val HOUR_MS = 3_600_000L

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 지금 받는 중인지. 정해 둔 중지 시간이 지났으면 여기서 다시 켠다. */
    fun isOn(ctx: Context, now: Long = System.currentTimeMillis()): Boolean {
        val sp = prefs(ctx)
        if (sp.getBoolean(K_ON, true)) return true
        val until = sp.getLong(K_UNTIL, 0L)
        if (until in 1..now) {
            sp.edit().putBoolean(K_ON, true).remove(K_UNTIL).apply()
            return true
        }
        return false
    }

    /** 다시 받을 시각(정했을 때만). */
    fun pausedUntil(ctx: Context): Long? =
        if (isOn(ctx)) null else prefs(ctx).getLong(K_UNTIL, 0L).takeIf { it > 0 }

    /** 수신 중지. durationMs가 있으면 그 뒤 다시 켠다. 서비스를 멈춘다. */
    fun pause(ctx: Context, durationMs: Long? = null) {
        val e = prefs(ctx).edit().putBoolean(K_ON, false)
        if (durationMs != null) e.putLong(K_UNTIL, System.currentTimeMillis() + durationMs) else e.remove(K_UNTIL)
        e.commit()
        alarm(ctx, durationMs?.let { System.currentTimeMillis() + it })
        ReceiverService.stop(ctx)
    }

    /** 수신 다시 시작. 서비스 시작에 성공하면 true(백그라운드에서 막히면 false). */
    fun resume(ctx: Context): Boolean {
        prefs(ctx).edit().putBoolean(K_ON, true).remove(K_UNTIL).commit()
        alarm(ctx, null)
        return ReceiverService.start(ctx)
    }

    private fun alarm(ctx: Context, at: Long?) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(ctx, 0, Intent(ctx, ResumeReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        am.cancel(pi)
        if (at != null) am.set(AlarmManager.RTC, at, pi)
    }
}
