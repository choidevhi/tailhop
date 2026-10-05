package com.choidev.tailhop

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast

/** 부팅 후 페어링돼 있고 수신을 켜 두었으면 수신 서비스 시작. 인텐트의 다른 내용은 신뢰하지 않는다. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            ReceiverService.start(context)
        }
    }
}

/** 텍스트 알림의 "복사" (exported=false, 자체 PendingIntent로만 호출). */
class CopyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val item = History.find(context, id) ?: return
        if (!item.isText) return
        Clip.copy(Lang.wrap(context), History.fullText(context, item))
        context.getSystemService(NotificationManager::class.java).cancel(intent.getIntExtra(EXTRA_NID, 0))
    }

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_NID = "nid"
    }
}

/** 수신 알림의 "수신 중지" (exported=false). */
class PauseReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Receiving.pause(context)
        val ctx = Lang.wrap(context)
        Toast.makeText(ctx, ctx.getString(R.string.toast_paused), Toast.LENGTH_SHORT).show()
    }
}

/** 정해 둔 수신 중지 시간이 끝남(알람, exported=false). 백그라운드라 서비스를 못 켜면 탭해서 켜는 알림을 띄운다. */
class ResumeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Receiving.pausedUntil(context) != null) return // 다른 시각으로 다시 멈췄다
        if (!Receiving.isOn(context)) return // 기한 없이 멈췄다
        if (!Receiving.resume(context) && Store.isPaired(context)) Notifs.resumePrompt(Lang.wrap(context))
    }
}

object Clip {
    fun copy(ctx: Context, text: String) {
        val cm = ctx.getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("TailHop", text))
        Toast.makeText(ctx, ctx.getString(R.string.clip_copied), Toast.LENGTH_SHORT).show()
    }
}
