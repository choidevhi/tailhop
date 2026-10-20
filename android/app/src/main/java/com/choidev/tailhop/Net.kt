package com.choidev.tailhop

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

object Net {
    /** 이 기기의 Tailscale IPv4(100.64.0.0/10). 없으면 null. */
    fun tailscaleIp(): Inet4Address? = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { Protocol.isTailscaleIp(it.address) }
    } catch (e: Exception) { null }

    fun deviceName(): String = Protocol.cleanDeviceName(Build.MODEL ?: "Android").ifEmpty { "Android" }

    private fun connect(host: String, port: Int, token: SendToken? = null): Socket {
        // IP 리터럴만 허용(DNS 조회 없음).
        if (!Protocol.isTailscaleIp(host)) throw ProtocolException("not_ts_host")
        val s = Socket()
        token?.attach(s)
        try {
            s.connect(InetSocketAddress(InetAddress.getByName(host), port), 10_000)
            s.soTimeout = Protocol.SOCKET_TIMEOUT_MS
            s.tcpNoDelay = true
        } catch (e: IOException) {
            s.close(); throw ProtocolException("connect_failed")
        }
        return s
    }

    private fun readReply(ch: Channel): JSONObject {
        val (raw, last) = try { ch.recv() } catch (e: ProtocolException) { throw e } catch (e: IOException) {
            // 연결은 됐는데 PC가 말없이 끊음 = PC가 이 폰의 페어링을 잃었을 가능성이 큼(PC 재설치·데이터 삭제 등).
            throw ProtocolException("pc_forgot")
        }
        if (!last) throw ProtocolException("bad_reply")
        val reply = try { JSONObject(String(raw, Charsets.UTF_8)) } catch (e: Exception) {
            throw ProtocolException("unreadable_reply")
        }
        if (reply.opt("ok") != true) {
            // PC 1.5+는 고정 코드를 함께 보낸다 → 이 폰의 언어로. 예전 PC는 한국어 문장만 → 그대로 보여 준다.
            val code = reply.opt("code") as? String
            if (Errors.knows(code)) throw ProtocolException(code!!)
            val err = reply.optString("error", "").take(200)
            throw if (err.isNotEmpty()) ProtocolException("pc_refused_reason", err) else ProtocolException("pc_refused")
        }
        return reply
    }

    private fun header(type: String): JSONObject = JSONObject()
        .put("v", 1).put("type", type).put("id", Protocol.newId())
        .put("ts", System.currentTimeMillis()).put("from", deviceName())

    /**
     * 페어링 1단계: 새 S를 만들어 QR의 PC에 보낸다. 저장하지 않는다 — 사용자가 확인 코드를 대조해
     * PendingPair.confirm을 고른 뒤에만 Store.save 한다(docs/PROTOCOL.md §6).
     */
    fun pair(info: Protocol.PairInfo): PendingPair {
        val secret = Protocol.randomBytes(32)
        val pairKey = Protocol.deriveKey(info.key, Protocol.INFO_PAIR)
        connect(info.host, info.port).use { s ->
            val ch = Protocol.openClient(BufferedInputStream(s.getInputStream()), BufferedOutputStream(s.getOutputStream()),
                Protocol.KIND_PAIR, pairKey)
            val h = header("pair").put("secret", Protocol.b64uEncode(secret)).put("port", Protocol.PHONE_PORT)
            ch.send(h.toString().toByteArray(Charsets.UTF_8), last = false)
            ch.send(ByteArray(0), last = true)
            val reply = readReply(ch)
            val name = Protocol.cleanDeviceName(reply.optString("name", "")).ifEmpty { info.name }
            return PendingPair(Pairing(info.host, info.port, name, secret))
        }
    }

    /**
     * 페어링 취소 알림(확인 코드가 다르다고 골랐을 때): `type:"unpair"` 메시지. PC 1.5+는 그 페어링과 기록을 지우고,
     * 예전 PC는 지원하지 않는 메시지라며 거부한다(무시). 실패해도 괜찮다 — 폰은 어차피 저장하지 않았다.
     */
    fun sendUnpair(p: Pairing) {
        connect(p.pcIp, p.pcPort).use { s ->
            val ch = msgChannel(p, s)
            ch.send(header("unpair").toString().toByteArray(Charsets.UTF_8), last = false)
            ch.send(ByteArray(0), last = true)
            readReply(ch)
        }
    }

    private fun msgChannel(p: Pairing, s: Socket): Channel =
        Protocol.openClient(BufferedInputStream(s.getInputStream()), BufferedOutputStream(s.getOutputStream(), 64 * 1024),
            Protocol.KIND_MSG, Protocol.deriveKey(p.secret, Protocol.INFO_PHONE_TO_PC))

    /** 보내기 전 능력·이어 보낼 위치 확인(§11.1). 예전 기기(hello를 모름)면 null — 예전 방식으로만 보낸다. */
    fun hello(p: Pairing, xfer: String?, size: Long?, token: SendToken? = null): Xfer.Hello? {
        val h = header("hello")
        if (xfer != null && size != null) h.put("xfer", xfer).put("size", size)
        val reply = try {
            connect(p.pcIp, p.pcPort, token).use { s ->
                val ch = msgChannel(p, s)
                ch.send(h.toString().toByteArray(Charsets.UTF_8), last = false)
                ch.send(ByteArray(0), last = true)
                token?.tick()
                readReply(ch)
            }
        } catch (e: ProtocolException) {
            // 1.6 이하 PC는 unsupported로, 1.4 이하는 코드 없이 한국어 문장으로 거부한다.
            if (e.code == "unsupported" || e.code == "pc_refused" || e.code == "pc_refused_reason") return null
            throw e
        }
        return Xfer.parseHello(reply)
    }

    /** 텍스트 한 건. xfer는 상대가 hello에서 "xfer"를 알렸을 때만(상대가 중복을 무시한다). 기록은 부른 쪽이 남긴다. */
    fun sendText(p: Pairing, text: String, xfer: String?, token: SendToken? = null) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > Protocol.MAX_TEXT) throw ProtocolException("text_too_long")
        if (text.isEmpty()) throw ProtocolException("text_empty")
        val hj = header("text").put("text", text)
        if (xfer != null) hj.put("xfer", xfer)
        val h = hj.toString().toByteArray(Charsets.UTF_8)
        if (h.size > Protocol.MAX_FRAME - Protocol.TAG) throw ProtocolException("text_too_long")
        connect(p.pcIp, p.pcPort, token).use { s ->
            val ch = msgChannel(p, s)
            ch.send(h, last = false)
            ch.send(ByteArray(0), last = true)
            token?.tick()
            readReply(ch)
        }
    }

    data class FileMeta(val name: String, val size: Long)

    fun fileMeta(ctx: Context, uri: Uri): FileMeta {
        var name: String? = null
        var size: Long? = null
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        } catch (e: Exception) { }
        if (size == null) size = try {
            ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length.takeIf { l -> l >= 0 } }
        } catch (e: Exception) { null }
        return FileMeta(name ?: uri.lastPathSegment ?: "file", size ?: throw ProtocolException("size_unknown"))
    }

    /**
     * 파일의 수정 시각(ms). 문서 provider는 last_modified, MediaStore는 date_modified(초). 알 수 없으면 null —
     * 그때는 크기만으로 바뀌었는지 본다(§11.3).
     */
    fun lastModified(ctx: Context, uri: Uri): Long? {
        val cols = listOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED to 1L, MediaStore.MediaColumns.DATE_MODIFIED to 1000L)
        for ((col, scale) in cols) {
            try {
                ctx.contentResolver.query(uri, arrayOf(col), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val i = c.getColumnIndex(col)
                        if (i >= 0 && !c.isNull(i)) return c.getLong(i) * scale
                    }
                }
            } catch (e: Exception) { } // 그 열이 없는 provider는 예외를 던지기도 한다
        }
        return null
    }

    /** 보낼 파일을 연다. 공유한 앱의 권한이 끝났거나 파일이 지워졌으면 file_gone(다시 시도하지 않는다). */
    fun openSource(ctx: Context, uri: Uri): InputStream = try {
        ctx.contentResolver.openInputStream(uri) ?: throw ProtocolException("file_gone")
    } catch (e: ProtocolException) { throw e } catch (e: Exception) { throw ProtocolException("file_gone") }

    private fun readFully(input: InputStream, buf: ByteArray, max: Int = buf.size): Int {
        var n = 0
        while (n < max) {
            val r = input.read(buf, n, max - n)
            if (r < 0) break
            n += r
        }
        return n
    }

    /**
     * 예전 방식(§4 file, xfer 없음): 1 MiB 단위로 스트리밍하고 마지막 데이터 프레임에 last=1. 끊기면 상대는 전부 버린다.
     * hello를 모르는 PC(1.6 이하)에만 쓴다.
     */
    fun sendFileLegacy(ctx: Context, uri: Uri, meta: FileMeta, p: Pairing, token: SendToken? = null,
                       onProgress: ((Long, Long) -> Unit)? = null) {
        if (meta.size > Protocol.MAX_FILE) throw ProtocolException("file_too_large")
        openSource(ctx, uri).use { src ->
            connect(p.pcIp, p.pcPort, token).use { s ->
                val ch = msgChannel(p, s)
                val h = header("file").put("name", meta.name.take(255)).put("size", meta.size)
                ch.send(h.toString().toByteArray(Charsets.UTF_8), last = false)
                var cur = ByteArray(Protocol.CHUNK)
                var nxt = ByteArray(Protocol.CHUNK)
                var curLen = readFully(src, cur)
                var sent = 0L
                while (true) {
                    val nxtLen = if (curLen > 0) readFully(src, nxt) else 0
                    sent += curLen
                    // 알린 크기와 다르면 last 없이 끊어 PC가 버리게 한다.
                    if (sent > meta.size || (nxtLen == 0 && sent != meta.size))
                        throw ProtocolException("file_changed")
                    ch.send(cur, last = nxtLen == 0, len = curLen)
                    token?.tick()
                    onProgress?.invoke(sent, meta.size)
                    if (nxtLen == 0) break
                    val t = cur; cur = nxt; nxt = t; curLen = nxtLen
                }
                readReply(ch)
            }
        }
    }

    /**
     * §11.2: offset부터 데이터 프레임(모두 last=0, 빈 프레임 없음)을 보내고 끝에 파일 전체의 SHA-256 트레일러를 last=1로 붙인다.
     * 상대가 이미 가진 앞부분(offset 바이트)은 이 폰에서 읽어 해시만 이어 간다 — 연결하기 전에 해서 상대가 기다리다 끊지 않게.
     * 보내는 중 파일이 바뀌면 트레일러 없이 끊는다(상대는 조각만 남긴다).
     */
    fun sendFileXfer(ctx: Context, uri: Uri, meta: FileMeta, p: Pairing, xfer: String, offset: Long,
                     token: SendToken? = null, onProgress: ((Long, Long) -> Unit)? = null) {
        if (meta.size > Protocol.MAX_FILE) throw ProtocolException("file_too_large")
        if (offset < 0 || offset > meta.size) throw ProtocolException("bad_xfer")
        val md = Xfer.sha256()
        val buf = ByteArray(Protocol.CHUNK)
        openSource(ctx, uri).use { src ->
            var left = offset
            while (left > 0) {
                val n = readFully(src, buf, minOf(left, buf.size.toLong()).toInt())
                if (n == 0) throw ProtocolException("file_changed")
                md.update(buf, 0, n)
                left -= n
                token?.tick()
                if (token?.cancelled == true) throw ProtocolException("disconnected")
            }
            connect(p.pcIp, p.pcPort, token).use { s ->
                val ch = msgChannel(p, s)
                val h = header("file").put("name", meta.name.take(255)).put("size", meta.size)
                    .put("xfer", xfer).put("offset", offset)
                ch.send(h.toString().toByteArray(Charsets.UTF_8), last = false)
                var sent = offset
                while (true) {
                    val n = readFully(src, buf)
                    if (n == 0) break
                    sent += n
                    if (sent > meta.size) throw ProtocolException("file_changed")
                    md.update(buf, 0, n)
                    ch.send(buf, last = false, len = n)
                    token?.tick()
                    onProgress?.invoke(sent, meta.size)
                }
                if (sent != meta.size) throw ProtocolException("file_changed")
                ch.send(Xfer.trailer(md.digest()), last = true)
                token?.tick()
                readReply(ch)
            }
        }
    }
}

/**
 * 보내기 한 번의 취소·감시 손잡이. cancel()은 열린 소켓을 닫아 막혀 있는 쓰기·읽기를 바로 끝낸다.
 * lastActivity는 SendService가 오래 멈춘 연결(상대가 사라져 TCP 쓰기가 막힘)을 끊는 데 쓴다.
 */
class SendToken {
    @Volatile var cancelled = false
        private set
    @Volatile private var socket: Socket? = null
    @Volatile var lastActivity = System.currentTimeMillis()
        private set

    fun attach(s: Socket) {
        socket = s
        tick()
        if (cancelled) close()
    }

    fun tick() { lastActivity = System.currentTimeMillis() }

    /** 사용자가 취소: 결과를 버린다. */
    fun cancel() { cancelled = true; close() }

    /** 감시가 끊음: 실패(다시 시도)로 처리된다. */
    fun close() { try { socket?.close() } catch (e: Exception) { } }
}

