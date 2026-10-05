package com.choidev.tailhop

import android.content.Context
import android.net.Uri
import android.os.Build
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

    private fun connect(host: String, port: Int): Socket {
        // IP 리터럴만 허용(DNS 조회 없음).
        if (!Protocol.isTailscaleIp(host)) throw ProtocolException("not_ts_host")
        val s = Socket()
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

    private fun paired(ctx: Context) = Store.load(ctx) ?: throw ProtocolException("pair_first")

    fun sendText(ctx: Context, text: String, target: Pairing? = null) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > Protocol.MAX_TEXT) throw ProtocolException("text_too_long")
        if (text.isEmpty()) throw ProtocolException("text_empty")
        val p = target ?: paired(ctx)
        val h = header("text").put("text", text).toString().toByteArray(Charsets.UTF_8)
        if (h.size > Protocol.MAX_FRAME - Protocol.TAG) throw ProtocolException("text_too_long")
        connect(p.pcIp, p.pcPort).use { s ->
            val ch = msgChannel(p, s)
            ch.send(h, last = false)
            ch.send(ByteArray(0), last = true)
            readReply(ch)
        }
        History.add(ctx, HistoryItem(Protocol.newId(), System.currentTimeMillis(), false, true, text, null, bytes.size.toLong(), null, p.pcIp))
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

    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var n = 0
        while (n < buf.size) {
            val r = input.read(buf, n, buf.size - n)
            if (r < 0) break
            n += r
        }
        return n
    }

    /** 파일을 1 MiB 단위로 스트리밍해 보낸다(전체를 메모리에 올리지 않음). */
    fun sendFile(ctx: Context, uri: Uri, onProgress: ((Long, Long) -> Unit)? = null, target: Pairing? = null) {
        val meta = fileMeta(ctx, uri)
        if (meta.size > Protocol.MAX_FILE) throw ProtocolException("file_too_large")
        val p = target ?: paired(ctx)
        val input = ctx.contentResolver.openInputStream(uri) ?: throw ProtocolException("file_open")
        input.use { src ->
            connect(p.pcIp, p.pcPort).use { s ->
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
                    onProgress?.invoke(sent, meta.size)
                    if (nxtLen == 0) break
                    val t = cur; cur = nxt; nxt = t; curLen = nxtLen
                }
                readReply(ch)
            }
        }
        History.add(ctx, HistoryItem(Protocol.newId(), System.currentTimeMillis(), false, false, null,
            meta.name, meta.size, uri.toString(), p.pcIp))
    }
}
