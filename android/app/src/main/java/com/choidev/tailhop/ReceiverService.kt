package com.choidev.tailhop

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger

/**
 * 포그라운드 수신 서비스. 자기 Tailscale IPv4:47101에만 bind 하고 페어링된 PC IP만 받는다.
 * 수신 중지(Receiving)면 시작하지 않고, 켜져 있으면 멈춘다 — 대기 소켓과 상시 알림이 함께 사라진다. 보내기는 이 서비스와 상관없다.
 */
class ReceiverService : Service() {
    @Volatile private var running = false
    @Volatile private var server: ServerSocket? = null
    private var thread: Thread? = null
    private val slots = Semaphore(MAX_CONN)
    private lateinit var guard: ReplayGuard

    override fun onBind(intent: Intent?): IBinder? = null

    /** Android 12 이하에서도 앱에서 고른 언어로 알림을 만든다(13+는 시스템이 처리). */
    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(Lang.wrap(newBase)) }

    override fun onCreate() {
        super.onCreate()
        guard = ReplayGuard(File(filesDir, "replay.txt"))
        Notifs.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = Notifs.service(this, getString(R.string.status_listening))
        try {
            ServiceCompat.startForeground(this, Notifs.ID_SERVICE, n,
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        } catch (e: Exception) {
            stopSelf(); return START_NOT_STICKY
        }
        if (Store.load(this) == null || !Receiving.isOn(this)) { stopSelf(); return START_NOT_STICKY }
        if (!running) {
            running = true
            thread = Thread({ loop() }, "tailhop-accept").also { it.start() }
        }
        return START_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) { stopSelf() }

    override fun onDestroy() {
        running = false
        try { server?.close() } catch (e: Exception) { }
        thread?.interrupt()
        state = State.OFF
        super.onDestroy()
    }

    private fun setState(s: State, addr: String = "") {
        if (state == s && address == addr) return
        state = s
        address = addr
        if (!running) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(Notifs.ID_SERVICE, Notifs.service(this, statusText(this)))
    }

    private fun loop() {
        while (running) {
            val ip = Net.tailscaleIp()
            if (ip == null) {
                setState(State.TAILSCALE_OFF)
                sleepQuietly(10_000); continue
            }
            try {
                ServerSocket().use { ss ->
                    server = ss
                    ss.reuseAddress = true
                    ss.bind(InetSocketAddress(ip, Protocol.PHONE_PORT), 8)
                    ss.soTimeout = 15_000
                    setState(State.LISTENING, "${ip.hostAddress}:${Protocol.PHONE_PORT}")
                    while (running) {
                        val sock = try { ss.accept() } catch (e: SocketTimeoutException) {
                            // Tailscale 주소가 바뀌거나 꺼졌으면 다시 bind.
                            if (Net.tailscaleIp() != ip) break else continue
                        }
                        handOff(sock)
                    }
                }
            } catch (e: Exception) {
                if (running) { setState(State.BIND_FAILED); sleepQuietly(10_000) }
            } finally { server = null }
        }
    }

    private fun handOff(sock: Socket) {
        val remote = sock.inetAddress?.address
        // 아무것도 읽기 전에 상대 IP 검사: 페어링된 PC 중 하나여야 하고, 그 PC의 키를 쓴다.
        val pairing = remote?.let { Store.byAddress(this, it) }
        if (pairing == null) {
            closeQuietly(sock); return
        }
        if (!slots.tryAcquire()) { closeQuietly(sock); return }
        Thread({
            try { handle(sock, pairing) } catch (e: Exception) { } finally { closeQuietly(sock); slots.release() }
        }, "tailhop-conn").start()
    }

    private fun handle(sock: Socket, pairing: Pairing) {
        sock.soTimeout = Protocol.SOCKET_TIMEOUT_MS
        val input = BufferedInputStream(sock.getInputStream(), 64 * 1024)
        val output = BufferedOutputStream(sock.getOutputStream())
        val prefix = Protocol.readPrefix(input)
        if (prefix[4].toInt() != Protocol.KIND_MSG) throw ProtocolException("phone_no_pair")
        val ch = Protocol.openServer(input, output, prefix, Protocol.deriveKey(pairing.secret, Protocol.INFO_PC_TO_PHONE))
        // 헤더 프레임: 태그 검사 실패는 예외 → 바로 끊음(응답 없음).
        val (raw, last) = ch.recv()
        try {
            if (last) throw ProtocolException("no_body")
            val header = try { JSONObject(String(raw, Charsets.UTF_8)) } catch (e: Exception) {
                throw ProtocolException("bad_header")
            }
            val type = header.opt("type")
            if (header.opt("v") != 1 || (type != "text" && type != "file")) throw ProtocolException("unsupported")
            guard.checkAndAdd(header.opt("id"), header.opt("ts"))
            val from = Protocol.cleanDeviceName(header.optString("from", "")).ifEmpty { pairing.pcName }
            if (type == "text") receiveText(ch, header, from, pairing.pcIp) else receiveFile(ch, header, from, pairing.pcIp)
            ch.send("{\"ok\":true}".toByteArray(), last = true)
        } catch (e: AuthException) {
            throw e // 태그 실패: 응답 없이 즉시 끊음
        } catch (e: ProtocolException) {
            sendError(ch, e)
            throw e
        } catch (e: SecurityException) {
            sendError(ch, ProtocolException("storage_denied")); throw e
        }
    }

    /** 거부 응답: 예전 PC가 그대로 보여 주는 한국어 문장("error")과 PC 1.5+가 번역하는 고정 코드("code"). */
    private fun sendError(ch: Channel, e: ProtocolException) {
        try {
            val body = JSONObject().put("ok", false).put("error", Errors.wireText(this, e)).put("code", e.code)
            ch.send(body.toString().toByteArray(), last = true)
        } catch (x: Exception) { }
    }

    private fun receiveText(ch: Channel, header: JSONObject, from: String, dev: String) {
        val text = header.opt("text") as? String
        if (text == null || text.toByteArray(Charsets.UTF_8).size > Protocol.MAX_TEXT) throw ProtocolException("bad_text")
        val (body, last) = ch.recv()
        if (body.isNotEmpty() || !last) throw ProtocolException("bad_text_format")
        val id = Protocol.newId()
        History.add(this, HistoryItem(id, System.currentTimeMillis(), true, true, text, null,
            text.toByteArray(Charsets.UTF_8).size.toLong(), null, dev))
        Notifs.text(this, id, from, text)
    }

    private fun receiveFile(ch: Channel, header: JSONObject, from: String, dev: String) {
        val sizeAny = header.opt("size")
        val size = (sizeAny as? Number)?.takeIf { sizeAny is Int || sizeAny is Long }?.toLong()
        val rawName = header.opt("name") as? String
        if (size == null || size < 0 || size > Protocol.MAX_FILE || rawName == null) throw ProtocolException("bad_file_info")
        if (freeBytes() < size + DISK_RESERVE) throw ProtocolException("disk_full")
        val name = Protocol.sanitizeFilename(rawName)
        val sink = if (Build.VERSION.SDK_INT >= 29) MediaStoreSink(this, name) else LegacySink(this, name)
        var ok = false
        try {
            var got = 0L
            sink.out.use { out ->
                while (true) {
                    val (data, last) = ch.recv()
                    got += data.size
                    if (got > size) throw ProtocolException("file_too_big")
                    out.write(data)
                    if (last) break
                }
            }
            if (got != size) throw ProtocolException("file_incomplete")
            val uri = sink.commit()
            ok = true
            val id = Protocol.newId()
            History.add(this, HistoryItem(id, System.currentTimeMillis(), true, false, null, sink.finalName, size, uri.toString(), dev))
            Notifs.file(this, id, from, sink.finalName, uri, sink.mime)
        } finally {
            if (!ok) sink.abort()
        }
    }

    private abstract class Sink(val name: String) {
        val mime: String = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
        abstract val out: OutputStream
        abstract var finalName: String
        abstract fun commit(): Uri
        abstract fun abort()
    }

    /** API 29+: MediaStore.Downloads, IS_PENDING=1 로 받고 검증 후 공개. 실패하면 삭제. */
    private class MediaStoreSink(val ctx: Context, name: String) : Sink(name) {
        private val uri: Uri
        override val out: OutputStream
        override var finalName = name

        init {
            if (Build.VERSION.SDK_INT < 29) throw IllegalStateException()
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/TailHop")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                ?: throw ProtocolException("storage")
            out = try { ctx.contentResolver.openOutputStream(uri, "w") ?: throw IOException() } catch (e: Exception) {
                abort(); throw ProtocolException("storage")
            }
        }

        override fun commit(): Uri {
            if (Build.VERSION.SDK_INT >= 29) {
                ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                try {
                    ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) finalName = it.getString(0) ?: finalName
                    }
                } catch (e: Exception) { }
            }
            return uri
        }

        override fun abort() { try { ctx.contentResolver.delete(uri, null, null) } catch (e: Exception) { } }
    }

    /** API 26–28: 공용 Download/TailHop, `.part`로 받고 검증 후 이름 변경. */
    private class LegacySink(val ctx: Context, name: String) : Sink(name) {
        private val dir: File
        private var target: File
        private val part: File
        override val out: OutputStream
        override var finalName = name

        init {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
                throw ProtocolException("storage_permission")
            @Suppress("DEPRECATION")
            dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "TailHop")
            if (!dir.isDirectory && !dir.mkdirs()) throw ProtocolException("storage_folder")
            synchronized(LOCK) {
                target = unique(name)
                part = File(dir, target.name + ".part")
                out = java.nio.file.Files.newOutputStream(part.toPath(), java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE)
            }
        }

        private fun unique(n: String): File {
            var f = File(dir, n); var i = 1
            while (f.exists() || File(dir, f.name + ".part").exists()) { f = File(dir, Protocol.numberedName(n, i++)) }
            return f
        }

        override fun commit(): Uri {
            synchronized(LOCK) {
                if (target.exists()) target = unique(target.name)
                if (!part.renameTo(target)) throw ProtocolException("storage")
            }
            finalName = target.name
            MediaScannerConnection.scanFile(ctx, arrayOf(target.absolutePath), arrayOf(mime), null)
            return FileProvider.getUriForFile(ctx, ctx.packageName + ".files", target)
        }

        override fun abort() { part.delete() }

        companion object { val LOCK = Any() }
    }

    enum class State { OFF, LISTENING, TAILSCALE_OFF, BIND_FAILED }

    /** 공용 저장소(Download가 있는 곳)의 남은 바이트. 알 수 없으면 Long.MAX_VALUE(막지 않음). */
    @Suppress("DEPRECATION")
    private fun freeBytes(): Long = try {
        android.os.StatFs(Environment.getExternalStorageDirectory().path).availableBytes
    } catch (e: Exception) { Long.MAX_VALUE }

    companion object {
        const val MAX_CONN = 4
        /** 파일을 받은 뒤에도 남겨 둘 여유(PC와 같은 64 MiB). */
        const val DISK_RESERVE = 64L * 1024 * 1024
        @Volatile var state: State = State.OFF
        @Volatile var address: String = ""

        /** 화면·알림에 보일 수신 상태(ctx의 언어로). */
        fun statusText(ctx: Context): String {
            Receiving.pausedUntil(ctx)?.let { return ctx.getString(R.string.status_paused_until, Lang.time(ctx, it)) }
            if (!Receiving.isOn(ctx)) return ctx.getString(R.string.status_paused)
            return when (state) {
                State.LISTENING -> ctx.getString(R.string.status_listening_at, address)
                State.TAILSCALE_OFF -> ctx.getString(R.string.status_tailscale_off)
                State.BIND_FAILED -> ctx.getString(R.string.status_bind_failed)
                State.OFF -> ctx.getString(R.string.status_off)
            }
        }

        /** 페어링돼 있고 수신이 켜져 있으면 시작한다. 시작 요청이 받아들여지면 true. */
        fun start(ctx: Context): Boolean {
            if (Store.load(ctx) == null || !Receiving.isOn(ctx)) return false
            return try { ContextCompat.startForegroundService(ctx, Intent(ctx, ReceiverService::class.java)); true } catch (e: Exception) { false }
        }

        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, ReceiverService::class.java)) }

        private fun sleepQuietly(ms: Long) { try { Thread.sleep(ms) } catch (e: InterruptedException) { } }
        private fun closeQuietly(s: Socket) { try { s.close() } catch (e: Exception) { } }
    }
}

object Notifs {
    const val CH_SERVICE = "service"
    const val CH_RECEIVED = "received"
    const val ID_SERVICE = 1
    private val nextId = AtomicInteger(100)

    const val ID_RESUME = 2

    /** 채널 이름은 지금 언어로(언어를 바꾸면 다음 호출 때 이름이 바뀐다). */
    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_SERVICE, ctx.getString(R.string.notif_channel_service), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_RECEIVED, ctx.getString(R.string.notif_channel_received), NotificationManager.IMPORTANCE_DEFAULT))
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

    fun file(ctx: Context, historyId: String, from: String, name: String, uri: Uri, mime: String) {
        if (!canPost(ctx)) return
        val nid = nextId.incrementAndGet()
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(ctx, nid, view, flags())
        val n = NotificationCompat.Builder(ctx, CH_RECEIVED)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(ctx.getString(R.string.notif_file_title, from))
            .setContentText(ctx.getString(R.string.notif_file_text, name))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try { ctx.getSystemService(NotificationManager::class.java).notify(nid, n) } catch (e: SecurityException) { }
    }
}
