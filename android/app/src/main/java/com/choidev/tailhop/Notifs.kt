package com.choidev.tailhop

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicInteger

object Notifs {
    const val CH_SERVICE = "service"
    const val CH_RECEIVED = "received"
    const val ID_SERVICE = 1
    private val nextId = AtomicInteger(100)

    const val ID_RESUME = 2
    const val ID_SEND = 3
    const val CH_SEND = "send"
    const val CH_SEND_FAILED = "send_failed"

    /** 채널 이름은 지금 언어로(언어를 바꾸면 다음 호출 때 이름이 바뀐다). */
    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_SERVICE, ctx.getString(R.string.notif_channel_service), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_RECEIVED, ctx.getString(R.string.notif_channel_received), NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CH_SEND, ctx.getString(R.string.notif_channel_send), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_SEND_FAILED, ctx.getString(R.string.notif_channel_send_failed), NotificationManager.IMPORTANCE_DEFAULT))
    }

    /**
     * 보내기 서비스의 상시 알림 하나: 지금 보내는 건의 진행률, 또는 대기 중인 건 수. "모두 취소"로 남은 보내기를 지운다.
     * percent가 null이면 진행 막대 없이 문장만.
     */
    fun sending(ctx: Context, text: String, percent: Int?): Notification {
        val open = PendingIntent.getActivity(ctx, ID_SEND, Intent(ctx, MainActivity::class.java), flags())
        val cancel = PendingIntent.getBroadcast(ctx, ID_SEND, Intent(ctx, SendCancelReceiver::class.java), flags())
        val b = NotificationCompat.Builder(ctx, CH_SEND)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("TailHop")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, ctx.getString(R.string.send_cancel_all), cancel)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (percent != null) b.setProgress(100, percent, false)
        return b.build()
    }

    /**
     * 끝내 보내지 못한 건(다시 시도하지 않는 실패·24시간 포기). 탭하면 그 PC의 대화방에서 다시 보낼 수 있다.
     * 클라우드 올리기·가져오기(1.8.0)는 그 PC의 클라우드 화면을 연다(전송 목록에서 다시 시도한다).
     */
    fun sendFailed(ctx: Context, dev: String, pcName: String, what: String, reason: String, cloud: Boolean = false) {
        if (!canPost(ctx)) return
        ensureChannels(ctx)
        val nid = nextId.incrementAndGet()
        val chat = (if (cloud) Intent(ctx, CloudActivity::class.java).putExtra(CloudActivity.EXTRA_IP, dev)
        else Intent(ctx, ChatActivity::class.java).putExtra(ChatActivity.EXTRA_IP, dev)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(ctx, nid, chat, flags())
        val body = ctx.getString(R.string.send_failed_text, what, reason)
        val n = NotificationCompat.Builder(ctx, CH_SEND_FAILED)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(ctx.getString(R.string.send_failed_title, pcName))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try { ctx.getSystemService(NotificationManager::class.java).notify(nid, n) } catch (e: SecurityException) { }
    }

    private fun flags() = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    fun service(ctx: Context, text: String): Notification {
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), flags())
        val pause = PendingIntent.getBroadcast(ctx, 1, Intent(ctx, PauseReceiver::class.java), flags())
        return NotificationCompat.Builder(ctx, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("TailHop")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, ctx.getString(R.string.notif_pause), pause)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun canPost(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun text(ctx: Context, historyId: String, from: String, text: String) {
        if (!canPost(ctx)) return
        val nid = nextId.incrementAndGet()
        val copy = Intent(ctx, CopyReceiver::class.java).putExtra(CopyReceiver.EXTRA_ID, historyId).putExtra(CopyReceiver.EXTRA_NID, nid)
        val pi = PendingIntent.getBroadcast(ctx, nid, copy, flags())
        val preview = if (text.length > 200) text.take(200) + "…" else text
        val n = NotificationCompat.Builder(ctx, CH_RECEIVED)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(ctx.getString(R.string.notif_text_title, from))
            .setContentText(preview)
            .setStyle(NotificationCompat.BigTextStyle().bigText(preview))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(pi)
            .addAction(0, ctx.getString(R.string.notif_copy), pi)
            .setAutoCancel(true)
            .build()
        try { ctx.getSystemService(NotificationManager::class.java).notify(nid, n) } catch (e: SecurityException) { }
    }

    /** 정해 둔 수신 중지가 끝났는데 백그라운드라 서비스를 못 켰을 때: 탭하면 앱을 열어 다시 켠다. */
    fun resumePrompt(ctx: Context) {
        if (!canPost(ctx)) return
        ensureChannels(ctx)
        val open = PendingIntent.getActivity(ctx, ID_RESUME, Intent(ctx, MainActivity::class.java), flags())
        val n = NotificationCompat.Builder(ctx, CH_RECEIVED)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(ctx.getString(R.string.notif_resume_title))
            .setContentText(ctx.getString(R.string.notif_resume_text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try { ctx.getSystemService(NotificationManager::class.java).notify(ID_RESUME, n) } catch (e: SecurityException) { }
    }

    /** 받은 파일. cloud면 클라우드 맨 위에 모은 파일(설정 "받은 파일도 클라우드에 모으기")이라 저장 위치 문장이 다르다. */
    fun file(ctx: Context, historyId: String, from: String, name: String, uri: Uri, mime: String, cloud: Boolean = false) {
        if (!canPost(ctx)) return
        val nid = nextId.incrementAndGet()
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(ctx, nid, view, flags())
        val n = NotificationCompat.Builder(ctx, CH_RECEIVED)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(ctx.getString(R.string.notif_file_title, from))
            .setContentText(ctx.getString(if (cloud) R.string.notif_file_text_cloud else R.string.notif_file_text, name))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try { ctx.getSystemService(NotificationManager::class.java).notify(nid, n) } catch (e: SecurityException) { }
    }

    /** PC 클라우드에서 가져오기를 끝냄(1.8.0). 탭하면 연다. 대화 기록에는 넣지 않는다(대화가 아니다). */
    fun cloudDownloaded(ctx: Context, pcName: String, name: String, uri: Uri, mime: String) {
        if (!canPost(ctx)) return
        ensureChannels(ctx)
        val nid = nextId.incrementAndGet()
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(ctx, nid, view, flags())
        val n = NotificationCompat.Builder(ctx, CH_RECEIVED)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(ctx.getString(R.string.notif_cloud_got_title, pcName))
            .setContentText(ctx.getString(R.string.notif_file_text, name))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try { ctx.getSystemService(NotificationManager::class.java).notify(nid, n) } catch (e: SecurityException) { }
    }
}
