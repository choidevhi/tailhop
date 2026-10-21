package com.choidev.tailhop

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
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
        if (reply.opt("ok") != true) throw refusal(reply)
        return reply
    }

    /** 거부 응답 → 예외. PC 1.5+는 고정 코드를 함께 보낸다 → 이 폰의 언어로. 예전 PC는 한국어 문장만 → 그대로 보여 준다. */
    private fun refusal(reply: JSONObject): ProtocolException {
        val code = reply.opt("code") as? String
        // 아는 코드는 응답 전체를 함께 둔다(vault_wait의 retry 같은 덧붙은 값, §14.2).
        if (Errors.knows(code)) return HubRefusal(code!!, reply)
        val err = reply.optString("error", "").take(200)
        return if (err.isNotEmpty()) ProtocolException("pc_refused_reason", err) else ProtocolException("pc_refused")
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

    /** 본문 없는 요청(헤더 + 빈 last=1 프레임) 하나를 보내고 응답을 읽는다. */
    private fun request(p: Pairing, h: JSONObject, token: SendToken? = null): JSONObject =
        connect(p.pcIp, p.pcPort, token).use { s ->
            val ch = msgChannel(p, s)
            ch.send(h.toString().toByteArray(Charsets.UTF_8), last = false)
            ch.send(ByteArray(0), last = true)
            token?.tick()
            readReply(ch)
        }

    /**
     * 클라우드 요청(§12)의 공통 거부 해석: 1.5–1.7 PC는 클라우드 요청을 unsupported로 거부한다 →
     * "이 PC는 클라우드를 지원하지 않음"(이 폰 안에서만 쓰는 코드)으로 바꾼다. 1.4 이하는 코드 없이 한국어 문장으로 거부해 그대로 보인다.
     */
    private inline fun <T> cloudCall(block: () -> T): T = try { block() } catch (e: ProtocolException) {
        throw when {
            e.code == "unsupported" -> ProtocolException("cloud_unsupported")
            // 2.2 허브(§17.6)의 storage = 저장소 암호문이 손상됨. 이 폰의 storage(파일을 저장하지 못함)와 문장이 달라 따로 둔다.
            e.code == "storage" && e is HubRefusal -> HubRefusal("store_damaged", e.reply)
            else -> e
        }
    }

    /** §12.2 cloud_list: 폴더 path의 한 쪽(cursor부터). */
    /**
     * 잠긴 폴더(§14.2) 안을 가리키는 요청이면 그 폴더의 열쇠(token)를 헤더에 붙인다. 열쇠는 이 프로세스의 메모리에만 있다
     * (VaultTokens.shared). 허브가 vault_locked로 거부하면(열쇠가 없거나 만료) 그 열쇠를 버린다 — 화면이 PIN을 다시 묻는다.
     */
    private inline fun <T> vaultCall(p: Pairing, path: String, h: JSONObject, block: (JSONObject) -> T): T {
        VaultTokens.shared.tokenFor(p.pcIp, path)?.let { h.put("token", it) }
        return try { cloudCall { block(h) } } catch (e: ProtocolException) {
            forgetOnVaultLocked(p, path, e)
            throw e
        }
    }

    /** vault_locked 거부: 허브가 알려 준 잠긴 폴더 자리(§14.2)를 먼저 믿고, 없으면 이 폰이 아는 자리로. 그 열쇠는 버린다. */
    private fun forgetOnVaultLocked(p: Pairing, path: String, e: ProtocolException) {
        if (e.code != "vault_locked") return
        val m = Vault.lockedMount((e as? HubRefusal)?.reply) ?: VaultTokens.shared.mountOf(p.pcIp, path)
        if (m != null) { VaultTokens.shared.learn(p.pcIp, m); VaultTokens.shared.drop(p.pcIp, m) }
    }

    fun cloudList(p: Pairing, path: String, cursor: Int, token: SendToken? = null): CloudPage {
        val h = header("cloud_list").put("path", path)
        if (cursor > 0) h.put("cursor", cursor)
        return vaultCall(p, path, h) { CloudListing.parse(request(p, it, token)) }
    }

    fun cloudMkdir(p: Pairing, path: String) { vaultCall(p, path, header("cloud_mkdir").put("path", path)) { request(p, it) } }

    fun cloudDel(p: Pairing, path: String) { vaultCall(p, path, header("cloud_del").put("path", path)) { request(p, it) } }

    /**
     * §16 cloud_move: 여러 항목을 dest 폴더로 옮긴다. 한 요청에 CloudMove.MAX_PATHS개까지라 더 많으면 나눠 보내고 결과를 합친다.
     * 잠긴 폴더 안끼리 옮길 때는 dest 기준 열쇠를 붙인다(같은 보관함 안에서만 옮길 수 있다). 항목별 실패는 결과의 failed에 있다.
     */
    /**
     * 2.2.1 허브(caps "move2", §18.1)는 잠긴 폴더 안팎·사이로도 옮긴다: 도착 열쇠는 "token"에, 출발 항목들의 열쇠는 "tokens"에(16개까지).
     * 옮긴 것 가운데 잠긴 폴더(또는 그것을 품은 폴더)가 있으면 그 열쇠·자리를 새 자리로 옮겨 기억한다(허브는 열쇠를 그대로 둔다).
     */
    fun cloudMove(p: Pairing, paths: List<String>, dest: String, move2: Boolean = false): MoveResult = CloudMove.run(paths) { chunk ->
        val h = header("cloud_move")
        CloudMove.fields(chunk, dest).let { f -> f.keys().forEach { k -> h.put(k, f.get(k)) } }
        vaultCall(p, dest, h) { hh ->
            if (move2) {
                val src = CloudMove.sourceTokens(chunk, hh.opt("token") as? String) { VaultTokens.shared.tokenFor(p.pcIp, it) }
                if (src.isNotEmpty()) hh.put("tokens", JSONArray(src))
            }
            CloudMove.parse(request(p, hh))
        }
    }.also { r -> r.moved.forEach { VaultTokens.shared.retarget(p.pcIp, it.path, CloudPath.join(dest, it.name)) } }

    /**
     * §18.2 cloud_rename: 200개씩 나눠 보낸다. 잠긴 폴더 안의 항목이면 그 열쇠들을(첫 것은 "token", 둘 이상이면 모두 "tokens"에) 붙인다.
     * 바뀐 것이 잠긴 폴더(또는 그것을 품은 폴더)면 열쇠·자리를 새 이름으로 옮겨 기억한다.
     */
    fun cloudRename(p: Pairing, items: List<CloudRename.Item>): MoveResult = CloudRename.run(items) { chunk ->
        val h = header("cloud_rename").put("items", CloudRename.itemsJson(chunk))
        val toks = CloudMove.sourceTokens(chunk.map { it.path }, null) { VaultTokens.shared.tokenFor(p.pcIp, it) }
        if (toks.isNotEmpty()) h.put("token", toks[0])
        if (toks.size > 1) h.put("tokens", JSONArray(toks))
        try { cloudCall { CloudRename.parse(request(p, h)) } } catch (e: ProtocolException) {
            forgetOnVaultLocked(p, chunk[0].path, e)
            throw e
        }
    }.also { r -> r.moved.forEach { VaultTokens.shared.retarget(p.pcIp, it.path, CloudPath.join(CloudPath.parent(it.path), it.name)) } }

    /**
     * §18.3 store_unlock: 강한 잠금으로 잠긴 허브 저장소를 클라우드 비밀번호로 연다. 비밀번호는 이 요청에만 쓰고 어디에도 두지 않는다.
     * 틀리면 store_password, 너무 많이 틀리면 vault_wait(retry초).
     */
    fun storeUnlock(p: Pairing, password: String) {
        cloudCall { request(p, header("store_unlock").put("password", password)) }
    }

    /** §18.3 store_lock: 허브 저장소를 바로 잠근다(강한 잠금일 때만, 아니면 store_state). 이 폰이 가진 잠긴 폴더 열쇠도 끝난다. */
    fun storeLock(p: Pairing) {
        cloudCall { request(p, header("store_lock")) }
        VaultTokens.shared.dropTokens(p.pcIp)
    }

    /** §15 cloud_search: path 폴더 아래에서 이름에 query가 든 것(kind로 거름). 잠긴 폴더 안에서 찾으면 그 열쇠를 붙인다. */
    fun cloudSearch(p: Pairing, path: String, query: String, kind: String, cursor: Int, token: SendToken? = null): SearchPage {
        val h = header("cloud_search")
        CloudSearch.fields(path, query, kind, cursor).let { f -> f.keys().forEach { k -> h.put(k, f.get(k)) } }
        return vaultCall(p, path, h) { CloudSearch.parse(request(p, it, token)) }
    }

    /**
     * §15 cloud_thumb: 파일의 JPEG 미리보기(긴 변 px). 첫 프레임(mime·w·h, last=0) 뒤 데이터 프레임(256 KiB 이하).
     * 만들 수 없는 파일은 not_found. token(SendToken)을 취소하면 소켓을 닫아 바로 끝난다(목록에서 스크롤해 지나감).
     */
    fun cloudThumb(p: Pairing, path: String, px: Int, token: SendToken? = null): ByteArray {
        val h = header("cloud_thumb").put("path", path).put("px", CloudThumb.px(px))
        return vaultCall(p, path, h) { hh ->
            connect(p.pcIp, p.pcPort, token).use { s ->
                val ch = msgChannel(p, s)
                ch.send(hh.toString().toByteArray(Charsets.UTF_8), last = false)
                ch.send(ByteArray(0), last = true)
                token?.tick()
                val (raw, last) = try { ch.recv() } catch (e: ProtocolException) { throw e } catch (e: IOException) {
                    throw ProtocolException("pc_forgot")
                }
                when (val first = CloudThumb.parseFirst(raw, last)) {
                    is CloudThumb.First.Refused -> throw refusal(first.reply)
                    is CloudThumb.First.Ok -> CloudThumb.readData(ch) { token?.tick() }
                }
            }
        }
    }

    /** §14.2 cloud_lock: 폴더를 잠근다. 허브가 바로 복구 코드를 돌려주고 뒤에서 암호화한다. 복구 코드는 이때 한 번만 받는다. */
    fun cloudLock(p: Pairing, path: String, pin: String): String =
        cloudCall { Vault.parseRecovery(request(p, header("cloud_lock").put("path", path).put("pin", pin))) }

    /** §14.2 cloud_open: PIN이나 복구 코드로 열쇠를 받아 이 프로세스에 둔다. */
    fun cloudOpen(p: Pairing, path: String, pin: String?, recovery: String?): String {
        val h = header("cloud_open").put("path", path)
        if (recovery != null) h.put("recovery", recovery) else h.put("pin", pin)
        val t = cloudCall { Vault.parseToken(request(p, h)) }
        VaultTokens.shared.put(p.pcIp, path, t)
        return t
    }

    /** §14.2 cloud_unlock: 잠금을 풀어 평문 폴더로 되돌린다(허브가 뒤에서). */
    fun cloudUnlock(p: Pairing, path: String, pin: String) {
        cloudCall { request(p, header("cloud_unlock").put("path", path).put("pin", pin)) }
        VaultTokens.shared.forgetMount(p.pcIp, path)
    }

    /** §14.2 cloud_repin: 지금 PIN이나 복구 코드로 새 PIN을 정한다. */
    fun cloudRepin(p: Pairing, path: String, pin: String?, recovery: String?, newPin: String) {
        val h = header("cloud_repin").put("path", path).put("new_pin", newPin)
        if (recovery != null) h.put("recovery", recovery) else h.put("pin", pin)
        cloudCall { request(p, h) }
        VaultTokens.shared.drop(p.pcIp, path) // PIN을 바꾸면 그 보관함의 열쇠는 모두 끝난다(§14.2)
    }

    /**
     * §12.2 cloud_get: offset부터 받는다. 이어받을 때(조각이 있을 때)는 조각을 만들 때의 size·mtime을 함께 보내 허브가
     * 그 사이 바뀌었는지 보게 한다(바뀌면 cloud_changed). open은 허브가 알린 머리(크기·수정 시각)를 받고 쓸 곳을 돌려준다 —
     * 조각을 만들거나 이어 쓸 위치로 연다. 다 받으면 머리를 돌려준다. 끊기면 쓴 데까지 남는다(부른 쪽이 다시 시도).
     */
    /**
     * 2.2 허브(§17.3): length를 주면 offset부터 그만큼만 받고(끝은 CloudRange.end), variant를 주면 원본 대신 그 딸린 버전을 받는다.
     * 예전 허브는 length를 모른다(끝까지 보낸다 → file_too_big) — hello caps에 "store"가 있을 때만 준다.
     */
    fun cloudGet(p: Pairing, path: String, offset: Long, size: Long?, mtime: Long?, token: SendToken? = null,
                 open: (CloudWire.Head) -> OutputStream, onProgress: ((Long, Long) -> Unit)? = null,
                 length: Long? = null, variant: String? = null): CloudWire.Head {
        val hh = header("cloud_get")
        CloudRange.fields(path, offset, length, variant, size, mtime).let { f -> f.keys().forEach { k -> hh.put(k, f.get(k)) } }
        return vaultCall(p, path, hh) { h -> connect(p.pcIp, p.pcPort, token).use { s ->
            val ch = msgChannel(p, s)
            ch.send(h.toString().toByteArray(Charsets.UTF_8), last = false)
            ch.send(ByteArray(0), last = true)
            token?.tick()
            val (raw, last) = try { ch.recv() } catch (e: ProtocolException) { throw e } catch (e: IOException) {
                throw ProtocolException("pc_forgot") // readReply와 같은 이유(연결은 됐는데 말없이 끊음)
            }
            val head = when (val first = CloudWire.parseFirst(raw, last)) {
                is CloudWire.First.Refused -> throw refusal(first.reply)
                is CloudWire.First.Ok -> first.head
            }
            // 허브가 알린 크기가 이어 받을 위치보다 작다 = 그 사이 바뀌었다(size를 주지 않은 첫 요청에서만 생길 수 있다).
            val end = CloudRange.end(offset, length, head.size)
            open(head).use { out ->
                CloudWire.readData(ch, offset, end, out) { got ->
                    token?.tick()
                    onProgress?.invoke(got, end)
                }
            }
            head
        } }
    }

    /**
     * §17.3 범위를 메모리로 받는다(영상 재생·건너뛰기): offset부터 length(CloudRange.MAX_LEN 이하)만. 디스크에 쓰지 않는다.
     * size·mtime은 앞서 받은 머리(같은 객체가 그대로인지 허브가 본다, 바뀌었으면 cloud_changed). 돌려준 머리의 size는 객체 전체 크기.
     */
    fun cloudReadRange(p: Pairing, path: String, offset: Long, length: Long, variant: String?, size: Long? = null,
                       mtime: Long? = null, token: SendToken? = null): CloudChunk {
        require(length in 0..CloudRange.MAX_LEN)
        var sink: CloudRange.Sink? = null
        val head = cloudGet(p, path, offset, size, mtime, token, open = { head ->
            CloudRange.Sink((CloudRange.end(offset, length, head.size) - offset).toInt()).also { sink = it }
        }, length = length, variant = variant)
        return CloudChunk(head, sink?.bytes ?: ByteArray(0))
    }

    /**
     * §13.2 update_get: 배포 PC에서 Android APK를 받는다. 첫 프레임(버전·크기·SHA-256)을 받으면 open으로 쓸 곳을 받고, 파일 전체를
     * 데이터 프레임으로 받는다(크기는 CloudWire.readData가 확인, SHA-256은 부른 쪽이 Updates.checkSha로). 첫 프레임을 돌려준다.
     */
    fun updateGet(p: Pairing, token: SendToken? = null, open: (UpdateHead) -> OutputStream,
                  onProgress: ((Long, Long) -> Unit)? = null): UpdateHead {
        val h = header("update_get").put("target", "android")
        connect(p.pcIp, p.pcPort, token).use { s ->
            val ch = msgChannel(p, s)
            ch.send(h.toString().toByteArray(Charsets.UTF_8), last = false)
            ch.send(ByteArray(0), last = true)
            token?.tick()
            val (raw, last) = try { ch.recv() } catch (e: ProtocolException) { throw e } catch (e: IOException) {
                throw ProtocolException("pc_forgot")
            }
            val head = when (val first = Updates.parseFirst(raw, last)) {
                is Updates.First.Refused -> throw refusal(first.reply)
                is Updates.First.Ok -> first.head
            }
            open(head).use { out ->
                CloudWire.readData(ch, 0, head.size, out) { got ->
                    token?.tick()
                    onProgress?.invoke(got, head.size)
                }
            }
            return head
        }
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
     * cloudDir가 있으면 클라우드 올리기(§12.2 cloud_put, 같은 형식에 `dir`만 더함)다. 상대의 응답을 돌려준다(cloud_put은 저장된 name).
     */
    fun sendFileXfer(ctx: Context, uri: Uri, meta: FileMeta, p: Pairing, xfer: String, offset: Long,
                     token: SendToken? = null, onProgress: ((Long, Long) -> Unit)? = null, cloudDir: String? = null): JSONObject {
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
                val h = header(if (cloudDir != null) "cloud_put" else "file").put("name", meta.name.take(255)).put("size", meta.size)
                    .put("xfer", xfer).put("offset", offset)
                if (cloudDir != null) {
                    h.put("dir", cloudDir)
                    // 잠긴 폴더(§14) 안으로 올리면 그 열쇠를 붙인다.
                    VaultTokens.shared.tokenFor(p.pcIp, cloudDir)?.let { h.put("token", it) }
                }
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
                return if (cloudDir != null) cloudCall { readReply(ch) } else readReply(ch)
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

