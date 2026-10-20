package com.choidev.tailhop

import android.Manifest
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.MediaScannerConnection
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.MediaStore
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * 포그라운드 수신 서비스. 자기 Tailscale IPv4:47101에만 bind 하고 페어링된 PC IP만 받는다.
 * 수신 중지(Receiving)면 시작하지 않고, 켜져 있으면 멈춘다 — 대기 소켓과 상시 알림이 함께 사라진다. 보내기는 이 서비스와 상관없다.
 *
 * 형식은 specialUse다(1.7.0). dataSync는 Android 15+에서 하루 6시간으로 묶이고(시간이 다 되면 onTimeout으로 멈춤)
 * BOOT_COMPLETED에서 시작할 수도 없어, 앱을 열기 전까지 말없이 못 받는 일이 생겼다.
 * Tailscale 주소가 바뀌었는지는 네트워크 콜백으로 안다(15초마다 깨던 것을 없앰). 5분마다 한 번만 따로 확인한다.
 */
class ReceiverService : Service() {
    @Volatile private var running = false
    @Volatile private var server: ServerSocket? = null
    /** 지금 bind 한 주소(바뀌면 대기 소켓을 닫아 다시 bind). */
    @Volatile private var boundIp: Inet4Address? = null
    /** 네트워크가 바뀌어 주소를 다시 볼 일이 생김(소켓을 닫은 이유가 오류가 아님). */
    @Volatile private var netDirty = false
    private val netLock = Object()
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var timer: ScheduledExecutorService? = null
    private var thread: Thread? = null
    private val slots = Semaphore(MAX_CONN)
    /** PC IP별 지금 연결 수(H5). */
    private val perPeer = HashMap<String, Int>()
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
            ServiceCompat.startForeground(this, Notifs.ID_SERVICE, n, foregroundType())
        } catch (e: Exception) {
            stopSelf(); return START_NOT_STICKY
        }
        if (Store.load(this) == null || !Receiving.isOn(this)) { stopSelf(); return START_NOT_STICKY }
        if (!running) {
            running = true
            timer = Executors.newSingleThreadScheduledExecutor()
            watchNetwork()
            thread = Thread({ loop() }, "tailhop-accept").also { it.start() }
        }
        return START_STICKY
    }

    /** specialUse는 하루 시간 제한이 없어 불리지 않는다. 다른 형식의 제한이 오면 멈춘다(멈추지 않으면 앱이 강제 종료된다). */
    override fun onTimeout(startId: Int, fgsType: Int) { stopSelf() }

    override fun onDestroy() {
        running = false
        try { server?.close() } catch (e: Exception) { }
        thread?.interrupt()
        synchronized(netLock) { netLock.notifyAll() }
        netCallback?.let { cb ->
            try { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } catch (e: Exception) { }
        }
        netCallback = null
        timer?.shutdownNow()
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

    /**
     * 어떤 네트워크든 생기거나 없어지거나 주소가 바뀌면 Tailscale 주소를 다시 본다. VPN(Tailscale)도 받도록
     * NOT_VPN·INTERNET 조건을 뺀다. Tailscale이 주소를 붙이는 때가 콜백보다 조금 늦을 수 있어 3초 뒤 한 번 더 본다.
     */
    private fun watchNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val req = NetworkRequest.Builder()
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = netChanged()
            override fun onLost(network: Network) = netChanged()
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = netChanged()
        }
        try { cm.registerNetworkCallback(req, cb); netCallback = cb } catch (e: Exception) { }
    }

    private fun netChanged() {
        recheckAddress()
        try { timer?.schedule({ recheckAddress() }, 3, TimeUnit.SECONDS) } catch (e: Exception) { }
    }

    /** 주소가 바뀌었으면 대기 소켓을 닫아(accept가 풀림) 새 주소로 다시 bind 하게 한다. 꺼져 있던 동안의 대기도 깨운다. */
    private fun recheckAddress() {
        if (!running) return
        val now = Net.tailscaleIp()
        val bound = boundIp
        if (now == bound) return
        netDirty = true
        if (bound != null) try { server?.close() } catch (e: Exception) { }
        synchronized(netLock) { netLock.notifyAll() }
    }

    /** 네트워크가 바뀔 때까지(또는 ms가 지날 때까지) 기다린다. */
    private fun waitNet(ms: Long) {
        synchronized(netLock) {
            if (!netDirty && running) try { netLock.wait(ms) } catch (e: InterruptedException) { }
        }
    }

    private fun loop() {
        Transfers.prune(this) // 24시간 지난 조각 정리
        while (running) {
            netDirty = false
            val ip = Net.tailscaleIp()
            if (ip == null) {
                setState(State.TAILSCALE_OFF)
                waitNet(SAFETY_RECHECK_MS); continue
            }
            try {
                ServerSocket().use { ss ->
                    server = ss
                    ss.reuseAddress = true
                    ss.bind(InetSocketAddress(ip, Protocol.PHONE_PORT), 8)
                    boundIp = ip
                    // accept는 연결이 올 때까지 잔다. 주소가 바뀌면 네트워크 콜백이 소켓을 닫는다. 이 시간은 안전용 확인뿐.
                    ss.soTimeout = SAFETY_RECHECK_MS.toInt()
                    setState(State.LISTENING, "${ip.hostAddress}:${Protocol.PHONE_PORT}")
                    if (Net.tailscaleIp() != ip) return@use // bind 하는 사이 바뀜
                    while (running) {
                        val sock = try { ss.accept() } catch (e: SocketTimeoutException) {
                            Transfers.prune(this)
                            if (Net.tailscaleIp() != ip) break else continue
                        }
                        handOff(sock)
                    }
                }
            } catch (e: Exception) {
                // 콜백이 주소가 바뀌어 닫은 것이면 바로 다시 bind, 아니면 잠시 뒤 다시.
                if (running && !netDirty) { setState(State.BIND_FAILED); sleepQuietly(10_000) }
            } finally { server = null; boundIp = null }
        }
    }

    private fun handOff(sock: Socket) {
        val remote = sock.inetAddress?.address
        // 아무것도 읽기 전에 상대 IP 검사: 페어링된 PC 중 하나여야 하고, 그 PC의 키를 쓴다.
        val pairing = remote?.let { Store.byAddress(this, it) }
        if (pairing == null) {
            closeQuietly(sock); return
        }
        // 전체 4개에 더해 PC 하나는 동시에 2개까지(H5) — 한 PC가 연결을 잡아 다른 PC의 보내기를 막지 않게.
        if (!perPeerAcquire(pairing.pcIp)) { closeQuietly(sock); return }
        if (!slots.tryAcquire()) { perPeerRelease(pairing.pcIp); closeQuietly(sock); return }
        Thread({
            try { handle(sock, pairing) } catch (e: Exception) { } finally {
                closeQuietly(sock); slots.release(); perPeerRelease(pairing.pcIp)
            }
        }, "tailhop-conn").start()
    }

    private fun perPeerAcquire(ip: String): Boolean = synchronized(perPeer) {
        val n = perPeer[ip] ?: 0
        if (n >= MAX_CONN_PER_PEER) false else { perPeer[ip] = n + 1; true }
    }

    private fun perPeerRelease(ip: String) {
        synchronized(perPeer) {
            val n = (perPeer[ip] ?: 1) - 1
            if (n <= 0) perPeer.remove(ip) else perPeer[ip] = n
        }
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
            if (header.opt("v") != 1 || type !in TYPES) throw ProtocolException("unsupported")
            guard.checkAndAdd(header.opt("id"), header.opt("ts"))
            // 인증된 메시지가 왔다 = 그 PC가 켜져 있다. 그 PC로 보낼 대기 건을 바로 깨운다(§11.3).
            SendService.wake(this, pairing.pcIp)
            val from = Protocol.cleanDeviceName(header.optString("from", "")).ifEmpty { pairing.pcName }
            val reply = when (type) {
                "hello" -> hello(ch, header, pairing.pcIp)
                "text" -> { receiveText(ch, header, from, pairing.pcIp); OK }
                "file" -> { receiveFile(ch, header, from, pairing.pcIp, sock); OK }
                else -> cloud(ch, header, type as String, pairing.pcIp, sock) ?: return // cloud_get은 데이터까지 직접 보냈다
            }
            ch.send(reply.toString().toByteArray(), last = true)
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

    /** §11.1 hello: 아는 확장과, xfer·size를 주면 받다 만 조각 길이(have)·이미 받았는지(done). 상태를 바꾸지 않는다. */
    private fun hello(ch: Channel, header: JSONObject, dev: String): JSONObject {
        val (body, last) = ch.recv()
        if (body.isNotEmpty() || !last) throw ProtocolException("bad_header")
        // xfer·size는 선택이다. 형식이 틀리면 거부하지 않고 조각이 없는 것으로 답한다(PC와 같다).
        val xfer = Xfer.field(header, "xfer")
        val size = Xfer.jsonLong(Xfer.field(header, "size"))?.takeIf { it in 0..Protocol.MAX_FILE }
        val cloud = CloudMode.isOn(this)
        if (!Xfer.isValid(xfer) || size == null) return Xfer.helloReply(0, false, cloud)
        val (have, done) = Transfers.helloState(this, dev, xfer as String, size)
        return Xfer.helloReply(have, done, cloud)
    }

    /** 헤더 뒤에 본문 없이 빈 last=1 프레임 하나만 오는 요청(hello와 같은 규칙). */
    private fun emptyBody(ch: Channel) {
        val (body, last) = ch.recv()
        if (body.isNotEmpty() || !last) throw ProtocolException("bad_header")
    }

    /**
     * §12 클라우드 요청(이 폰이 허브). 꺼져 있으면 cloud_off. cloud_get은 머리·데이터 프레임을 직접 보내고 null을 돌려준다.
     * 폴더 안 위치·이름 검사는 CloudPath(§12.1), 저장소는 CloudStore가 한다.
     */
    private fun cloud(ch: Channel, header: JSONObject, type: String, dev: String, sock: Socket): JSONObject? {
        // 올리기·가져오기는 클라우드 모드를 끄면 바로 끊는다(H6). 먼저 적고 나서 켜져 있는지 본다 — 그 사이에 꺼도 놓치지 않게.
        val long = type == "cloud_put" || type == "cloud_get"
        if (long) CLOUD_SOCKS += sock
        try {
            if (!CloudMode.isOn(this)) throw ProtocolException("cloud_off")
            return cloudRequest(ch, header, type, dev, sock)
        } finally {
            if (long) CLOUD_SOCKS -= sock
        }
    }

    private fun cloudRequest(ch: Channel, header: JSONObject, type: String, dev: String, sock: Socket): JSONObject? {
        if (type == "cloud_put") return cloudPut(ch, header, dev, sock)
        emptyBody(ch)
        return when (type) {
            "cloud_list" -> {
                val dir = CloudPath.field(header)
                val cursor = CloudListing.cursor(header)
                CloudListing.reply(CloudListing.page(CloudStore.list(this, dir), cursor))
            }
            "cloud_get" -> { cloudGet(ch, header); null }
            "cloud_del" -> { CloudStore.delete(this, CloudPath.field(header)); OK }
            else -> { CloudStore.mkdir(this, CloudPath.field(header)); OK } // cloud_mkdir
        }
    }

    /**
     * cloud_get: 먼저 `{"ok","size","mtime"}`(last=0), 이어서 offset부터 끝까지(마지막 데이터 프레임 last=1).
     * 머리를 보낸 뒤에는 거부 응답을 보낼 수 없다(상대는 데이터로 읽는다) — 실패하면 IOException으로 그냥 끊는다.
     * 보내는 사이 파일이 줄었을 때도 last 없이 끊는다. 상대는 size·mtime을 주고 다시 물어 cloud_changed를 받는다.
     */
    private fun cloudGet(ch: Channel, header: JSONObject) {
        val req = CloudWire.parseGet(header)
        val f = CloudStore.file(this, req.path)
        CloudWire.checkGet(req, f.head)
        val src = try { f.streamAt(req.offset) } catch (e: Exception) { throw ProtocolException("not_found") }
        src.use { s ->
            ch.send(CloudWire.head(f.head.size, f.head.mtime), last = false)
            try {
                var left = f.head.size - req.offset
                if (left == 0L) { ch.send(ByteArray(0), last = true); return }
                val buf = ByteArray(Protocol.CHUNK)
                while (left > 0) {
                    val want = minOf(left, buf.size.toLong()).toInt()
                    var n = 0
                    while (n < want) {
                        val r = s.read(buf, n, want - n)
                        if (r < 0) throw IOException("shrank")
                        n += r
                    }
                    left -= n
                    ch.send(buf, last = left == 0L, len = n)
                }
            } catch (e: Exception) { throw IOException("cloud_get aborted", e) }
        }
    }

    /** cloud_put: §11.2 file과 같고 dir(없으면 맨 위)에 넣는다. xfer·트레일러는 필수. 응답에 실제 저장된 이름. */
    private fun cloudPut(ch: Channel, header: JSONObject, dev: String, sock: Socket): JSONObject {
        val (size, rawName) = fileInfo(header)
        val xfer = Xfer.field(header, "xfer")
        if (!Xfer.isValid(xfer)) throw ProtocolException("bad_xfer")
        val dir = CloudPath.field(header, "dir")
        val offset = Xfer.offset(header, size)
        if (freeBytes() < size - offset + DISK_RESERVE) throw ProtocolException("disk_full")
        CloudStore.ensureDir(this, dir)
        val name = CloudPath.storableName(Protocol.sanitizeFilename(rawName))
        val (_, finalName) = receiveXferPart(ch, header, dev, sock, xfer as String, size, name, CloudStore.partDir(dir),
            PartialRec.CLOUD_PUT)
        return JSONObject().put("ok", true).put("name", finalName)
    }

    /** file·cloud_put 헤더의 size(0..4 GiB 정수)와 name(문자열). 어기면 bad_file_info. */
    private fun fileInfo(header: JSONObject): Pair<Long, String> {
        val sizeAny = header.opt("size")
        val size = (sizeAny as? Number)?.takeIf { sizeAny is Int || sizeAny is Long }?.toLong()
        val rawName = header.opt("name") as? String
        if (size == null || size < 0 || size > Protocol.MAX_FILE || rawName == null) throw ProtocolException("bad_file_info")
        return size to rawName
    }

    private fun receiveText(ch: Channel, header: JSONObject, from: String, dev: String) {
        val text = header.opt("text") as? String
        if (text == null || text.toByteArray(Charsets.UTF_8).size > Protocol.MAX_TEXT) throw ProtocolException("bad_text")
        val xfer = Xfer.field(header, "xfer")
        if (xfer != null && !Xfer.isValid(xfer)) throw ProtocolException("bad_xfer")
        val (body, last) = ch.recv()
        if (body.isNotEmpty() || !last) throw ProtocolException("bad_text_format")
        val id = Protocol.newId()
        synchronized(TEXT_LOCK) {
            // 응답이 사라져 PC가 같은 xfer로 다시 보낸 글: 다시 기록·알림하지 않고 ok만(§11.2).
            if (xfer != null && Transfers.isDone(this, dev, xfer as String)) return
            History.add(this, HistoryItem(id, System.currentTimeMillis(), true, true, text, null,
                text.toByteArray(Charsets.UTF_8).size.toLong(), null, dev))
            if (xfer != null) Transfers.finish(this, dev, xfer as String) // 답하기 전에: 응답이 사라져도 두 번 받지 않게
        }
        Notifs.text(this, id, from, text)
    }

    /** 대화방 파일. "받은 파일도 클라우드에 모으기"(1.8.0)가 켜져 있으면 클라우드 맨 위에 둔다(기록·알림은 그대로). */
    private fun receiveFile(ch: Channel, header: JSONObject, from: String, dev: String, sock: Socket) {
        val (size, rawName) = fileInfo(header)
        val dest = if (CloudMode.collects(this)) CloudStore.partDir("") else ""
        val cloud = dest.isNotEmpty()
        val name = Protocol.sanitizeFilename(rawName).let { if (cloud) CloudPath.storableName(it) else it }
        val xfer = Xfer.field(header, "xfer")
        if (xfer != null) {
            if (!Xfer.isValid(xfer)) throw ProtocolException("bad_xfer")
            val (uri, finalName) = receiveXferPart(ch, header, dev, sock, xfer as String, size, name, dest, PartialRec.FILE)
            val id = Protocol.newId()
            History.add(this, HistoryItem(id, System.currentTimeMillis(), true, false, null, finalName, size, uri.toString(), dev))
            Notifs.file(this, id, from, finalName, uri, PartFiles.mime(finalName), cloud)
            return
        }
        // xfer 없는 예전 방식(§4): 실패하면 지운다.
        if (freeBytes() < size + DISK_RESERVE) throw ProtocolException("disk_full")
        val sink = if (Build.VERSION.SDK_INT >= 29) MediaStoreSink(this, name, dest) else LegacySink(this, name, dest)
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
            // A5: 클라우드에 모은 파일의 실제 이름이 목록 규칙에 맞지 않으면(어느 PC에서도 보이지 않음) 지우고 거부한다.
            if (cloud && !CloudPath.isListable(sink.finalName)) { sink.discard(); throw ProtocolException("storage") }
            val id = Protocol.newId()
            History.add(this, HistoryItem(id, System.currentTimeMillis(), true, false, null, sink.finalName, size, uri.toString(), dev))
            Notifs.file(this, id, from, sink.finalName, uri, sink.mime, cloud)
        } finally {
            if (!ok) sink.abort()
        }
    }

    /**
     * §11.2: offset부터 받아 이어 쓰고, 트레일러의 SHA-256으로 조각 전체를 확인한다.
     * 끊기거나 시간 초과면 조각을 남기고(24시간), 규칙 위반·해시 불일치·저장 실패면 지운다.
     * 같은 조각은 한 연결만 쓴다: 같은 PC가 같은 xfer로 새로 연결하면(앞 연결이 끊긴 걸 상대가 먼저 알았다) 앞 연결을 닫고 이어받는다.
     * dest는 받을 폴더(Download/TailHop 아래, 대화방 ""·클라우드 "Cloud/…"), kind는 메시지 종류(PartialRec.FILE·CLOUD_PUT) —
     * 이어받을 때 둘 다 같아야 한다(A3). 공개한 (URI, 실제 이름)을 돌려준다 — 기록·알림은 부른 쪽이.
     */
    private fun receiveXferPart(ch: Channel, header: JSONObject, dev: String, sock: Socket,
                                xfer: String, size: Long, name: String, dest: String, kind: String): Pair<Uri, String> {
        val offset = Xfer.offset(header, size)
        if (freeBytes() < size - offset + DISK_RESERVE) throw ProtocolException("disk_full")
        return XferLocks.exclusive(TransferBook.key(dev, xfer), sock) {
            val md = Xfer.sha256()
            val loc: String
            val out: OutputStream
            if (offset > 0) {
                val rec = Transfers.claim(this, dev, xfer, size, dest, kind) ?: throw ProtocolException("resume_lost")
                loc = rec.loc
                out = try { PartFiles.openAt(this, loc, offset, md) } catch (e: ProtocolException) {
                    Transfers.drop(this, dev, xfer, loc); throw e
                }
            } else {
                loc = PartFiles.create(this, name, dest)
                Transfers.begin(this, dev, xfer, size, loc, name, dest, kind)
                out = try { PartFiles.openAt(this, loc, 0, md) } catch (e: ProtocolException) {
                    Transfers.drop(this, dev, xfer, loc); throw ProtocolException("storage")
                }
            }
            try {
                receiveParts(ch, out, md, offset, size)
            } catch (e: Exception) {
                if (isNetDrop(e)) Transfers.keep(this, dev, xfer, loc) else Transfers.drop(this, dev, xfer, loc)
                throw e
            }
            // H3: 공개하기 전에 장부에서 조각을 뗀다. 공개와 finish 사이에 죽어도 장부가 공개된 파일을 가리켜 prune이 지우지 않게.
            Transfers.release(this, dev, xfer, loc)
            val committed = try { PartFiles.commit(this, loc, name) } catch (e: Exception) {
                Transfers.drop(this, dev, xfer, loc) // 아직 대기 중인 조각만 지운다(PartFiles.delete)
                throw if (e is ProtocolException) e else ProtocolException("storage")
            }
            // A5: 클라우드에 둔 파일의 실제 이름(저장소가 번호를 붙였을 수 있다)이 목록 규칙에 맞지 않으면 어느 PC에서도
            // 보이지도 지워지지도 않는다 — 지우고 storage로 거부한다.
            if (dest.isNotEmpty() && !CloudPath.isListable(committed.second)) {
                PartFiles.unpublish(this, committed.first, committed.second, dest)
                Transfers.drop(this, dev, xfer, loc)
                throw ProtocolException("storage")
            }
            Transfers.finish(this, dev, xfer)
            committed
        }
    }

    /** 데이터 프레임(last=0, 비어 있으면 안 됨)을 끝까지 쓰고 트레일러(last=1)의 해시와 맞춘다. */
    private fun receiveParts(ch: Channel, out: OutputStream, md: MessageDigest, offset: Long, size: Long) {
        var got = offset
        var trailer: ByteArray? = null
        out.use { o ->
            while (true) {
                val (data, last) = ch.recv()
                if (last) { trailer = data; break }
                if (data.isEmpty()) throw ProtocolException("bad_xfer")
                got += data.size
                if (got > size) throw ProtocolException("file_too_big")
                try { o.write(data) } catch (e: IOException) { throw storageError() }
                md.update(data)
            }
            try { o.flush() } catch (e: IOException) { throw storageError() }
        }
        if (got != size) throw ProtocolException("file_incomplete")
        val sha = Xfer.parseTrailer(trailer ?: throw ProtocolException("bad_xfer"))
        if (!MessageDigest.isEqual(sha.toByteArray(), Xfer.hex(md.digest()).toByteArray())) throw ProtocolException("hash_mismatch")
    }

    /** 저장 중 쓰기 실패: 공간이 거의 없으면 disk_full, 아니면 storage. */
    private fun storageError() = ProtocolException(if (freeBytes() < 2L * Protocol.CHUNK) "disk_full" else "storage")

    /** 끊김·시간 초과(조각을 남길 실패)인지. 규칙 위반(ProtocolException)·태그 실패·저장 실패는 아니다. */
    private fun isNetDrop(e: Throwable): Boolean = when (e) {
        is ProtocolException -> e.code == "disconnected"
        is IOException -> true // SocketTimeoutException, 연결 리셋, 다른 연결이 이어받으려고 닫음
        else -> false
    }

    private abstract class Sink(val name: String) {
        val mime: String = PartFiles.mime(name)
        abstract val out: OutputStream
        abstract var finalName: String
        abstract fun commit(): Uri
        abstract fun abort()
        /** 공개한 뒤 지운다(A5: 이름이 목록 규칙에 맞지 않는 클라우드 파일). */
        abstract fun discard()
    }

    /** API 29+: MediaStore.Downloads, IS_PENDING=1 로 받고 검증 후 공개. 실패하면 삭제. relDir은 Download/TailHop 아래 폴더. */
    private class MediaStoreSink(val ctx: Context, name: String, relDir: String) : Sink(name) {
        private val uri: Uri
        override val out: OutputStream
        override var finalName = name

        init {
            if (Build.VERSION.SDK_INT < 29) throw IllegalStateException()
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, PartFiles.mediaDir(relDir))
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
        override fun discard() = abort()
    }

    /** API 26–28: 공용 Download/TailHop(또는 그 아래 relDir), `.part`로 받고 검증 후 이름 변경. */
    private class LegacySink(val ctx: Context, name: String, relDir: String) : Sink(name) {
        private val dir: File
        private var target: File
        private val part: File
        override val out: OutputStream
        override var finalName = name

        init {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
                throw ProtocolException("storage_permission")
            dir = if (relDir.isEmpty()) PartFiles.legacyDir() else File(PartFiles.legacyDir(), relDir)
            if (!dir.isDirectory && !dir.mkdirs()) throw ProtocolException("storage_folder")
            synchronized(LegacyNames.LOCK) {
                target = LegacyNames.unique(dir, name)
                part = File(dir, target.name + ".part")
                out = java.nio.file.Files.newOutputStream(part.toPath(), java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE)
            }
        }

        override fun commit(): Uri {
            synchronized(LegacyNames.LOCK) {
                if (target.exists()) target = LegacyNames.unique(dir, target.name)
                if (!part.renameTo(target)) throw ProtocolException("storage")
            }
            finalName = target.name
            MediaScannerConnection.scanFile(ctx, arrayOf(target.absolutePath), arrayOf(mime), null)
            return FileProvider.getUriForFile(ctx, ctx.packageName + ".files", target)
        }

        override fun abort() { part.delete() }
        override fun discard() { target.delete() }
    }

    /**
     * 조각 하나는 한 연결만 쓴다. 새 연결이 오면 앞 연결의 소켓을 닫아(그쪽은 끊김으로 보고 조각을 남긴 뒤 잠금을 푼다)
     * 기다렸다가 이어 쓴다. 앞 연결은 상대가 이미 버린 연결이다(같은 PC가 같은 xfer로 다시 왔다).
     */
    private object XferLocks {
        private class Entry { val lock = ReentrantLock(); var sock: Socket? = null; var users = 0 }
        private val map = HashMap<String, Entry>()

        fun <T> exclusive(key: String, sock: Socket, block: () -> T): T {
            val (e, old) = synchronized(map) {
                val e = map.getOrPut(key) { Entry() }
                e.users++
                val old = e.sock
                e.sock = sock
                e to old
            }
            try {
                if (old != null) closeQuietly(old)
                if (!e.lock.tryLock(15, TimeUnit.SECONDS)) throw IOException("busy") // 응답 없이 끊음 → 상대가 다시 시도
                try { return block() } finally { e.lock.unlock() }
            } finally {
                synchronized(map) {
                    if (e.sock === sock) e.sock = null
                    if (--e.users == 0) map.remove(key)
                }
            }
        }
    }

    enum class State { OFF, LISTENING, TAILSCALE_OFF, BIND_FAILED }

    private fun freeBytes(): Long = PartFiles.freeBytes()

    companion object {
        const val MAX_CONN = 4
        /**
         * PC 하나의 동시 연결 상한(H5). PC는 대화방 줄·클라우드 줄을 함께 보내고 클라우드 화면이 목록도 물으므로 3개까지 둔다.
         * 한 PC가 전체 슬롯(4개)을 다 잡지 못하게 한다.
         */
        const val MAX_CONN_PER_PEER = 3
        /** 지금 이어지는 클라우드 올리기·가져오기 연결(H6, 클라우드 모드를 끄면 닫는다). */
        private val CLOUD_SOCKS: MutableSet<Socket> = java.util.concurrent.ConcurrentHashMap.newKeySet()

        /** 클라우드 모드를 껐다: 이어지던 cloud_put·cloud_get 연결을 끊는다(받던 조각은 CloudMode가 버린다). */
        fun closeCloudConnections() { CLOUD_SOCKS.toList().forEach { closeQuietly(it) } }
        /** 파일을 받은 뒤에도 남겨 둘 여유(PC와 같은 64 MiB). */
        const val DISK_RESERVE = 64L * 1024 * 1024
        /** 네트워크 콜백을 놓쳤을 때를 위한 안전용 주소 확인 간격. */
        private const val SAFETY_RECHECK_MS = 5 * 60_000L
        private val TYPES = setOf("text", "file", "hello", "cloud_list", "cloud_get", "cloud_put", "cloud_del", "cloud_mkdir")
        private val OK = JSONObject().put("ok", true)
        /** 같은 xfer의 글이 동시에 두 번 와도 한 번만 기록한다. */
        private val TEXT_LOCK = Any()
        @Volatile var state: State = State.OFF
        @Volatile var address: String = ""

        /**
         * startForeground에 줄 형식. API 34+는 manifest와 같은 specialUse를 꼭 줘야 한다.
         * API 29–33에는 specialUse 상수가 없다(0x40000000은 34에서 생김). 이때 0(NONE)을 준다 — 그 버전의 AMS는
         * "준 형식이 manifest 형식의 부분집합인가"만 보고(0 & x == 0이라 늘 통과) 형식별 권한 검사는 34부터다.
         * API 26–28은 형식 인자가 없다(ServiceCompat이 두 인자 startForeground를 부른다).
         */
        fun foregroundType(): Int =
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0

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
