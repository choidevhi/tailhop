package com.choidev.tailhop

import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream

/**
 * 암호화 클라우드 저장소(docs/PROTOCOL.md §17, 2.2.0)의 앱 쪽 형식: 허브 저장소 상태(§17.2), cloud_get 범위·딸린 버전(§17.3),
 * 목록 항목의 딸린 버전(§17.4). 안드로이드 의존성 없음(JVM 단위 테스트).
 */
object CloudVariants {
    const val THUMB = "thumb"
    const val SCREEN = "screen"
    const val POSTER = "poster"
    const val PLAY = "play"

    /** 이 앱이 아는 딸린 버전. 모르는 이름은 버린다(허브가 새로 더해도 cloud_get variant로 보내지 않게). */
    val KNOWN: Set<String> = setOf(THUMB, SCREEN, POSTER, PLAY)

    /** 목록·검색 항목의 "variants": 문자열 배열에서 아는 이름만. 배열이 아니면 없음. */
    fun parse(v: Any?): Set<String> {
        val arr = v as? JSONArray ?: return emptySet()
        val out = LinkedHashSet<String>()
        for (i in 0 until arr.length()) (arr.opt(i) as? String)?.takeIf { it in KNOWN }?.let { out += it }
        return out
    }
}

/** 허브의 클라우드가 지금 어떤지(PC 고르기에 보일 상태, §17.2). */
enum class HubCloud { ON, OFF, OLD, STORE_LOCKED, STORE_MISSING }

object HubStore {
    const val NEW = "new"
    const val LOCKED = "locked"
    const val MISSING = "missing"
    const val OPEN = "open"
    private val STATES = setOf(NEW, LOCKED, MISSING, OPEN)

    /** hello 응답의 "store"(아는 값만, 없으면 null). */
    fun parse(reply: JSONObject): String? = (reply.opt("store") as? String)?.takeIf { it in STATES }

    /**
     * hello 결과 → 상태. 클라우드를 모르면(hello 없음·caps에 cloud 없음) OLD, 켜져 있고 열렸으면 ON.
     * 켜졌지만 저장소가 열리지 않았으면 new·locked는 STORE_LOCKED(그 PC에서 비밀번호를 넣거나 만들어야 함), missing은 STORE_MISSING.
     */
    fun state(h: Xfer.Hello?): HubCloud = when {
        h == null || "cloud" !in h.caps -> HubCloud.OLD
        h.cloud -> HubCloud.ON
        h.store == NEW || h.store == LOCKED -> HubCloud.STORE_LOCKED
        h.store == MISSING -> HubCloud.STORE_MISSING
        else -> HubCloud.OFF
    }
}

/** 범위로 받은 한 조각(§17.3): 허브가 알린 머리(그 객체 전체 크기·수정 시각)와 받은 바이트. */
class CloudChunk(val head: CloudWire.Head, val data: ByteArray)

/**
 * cloud_get 범위 요청(§17.3, 2.2 허브 = hello caps "store"). length를 주면 offset부터 그만큼만 오고, 첫 프레임의 size는 늘 전체 크기다.
 * variant를 주면 원본 대신 허브의 딸린 버전(size·mtime 검사도 그 버전 기준).
 */
object CloudRange {
    /** 영상 재생 때 한 번에 받는 최대 바이트(메모리 버퍼 하나). */
    const val MAX_LEN = 4 * 1024 * 1024

    /**
     * 요청 필드. path는 빈 경로가 아닌 규칙(§12.1)에 맞는 것(어기면 bad_path — 허브와 같은 코드), offset·length는 0 이상,
     * variant는 아는 이름(어기면 IllegalArgumentException, 부른 쪽의 실수).
     */
    fun fields(path: String, offset: Long, length: Long?, variant: String?, size: Long?, mtime: Long?): JSONObject {
        if (path.isEmpty() || !CloudPath.isValid(path)) throw ProtocolException("bad_path")
        require(offset >= 0 && (length == null || length >= 0) && (variant == null || variant in CloudVariants.KNOWN))
        val o = JSONObject().put("path", path).put("offset", offset)
        if (length != null) o.put("length", length)
        if (variant != null) o.put("variant", variant)
        if (size != null) o.put("size", size)
        if (mtime != null) o.put("mtime", mtime)
        return o
    }

    /**
     * 받을 데이터의 끝(객체 안 위치, 그 앞까지): length가 없으면 끝까지, 있으면 offset + length와 전체 크기 중 작은 것.
     * 크기가 offset보다 작으면 그 사이 바뀌었다(cloud_changed).
     */
    fun end(offset: Long, length: Long?, size: Long): Long {
        if (size < offset) throw ProtocolException("cloud_changed")
        return if (length == null) size else minOf(size, offset + length)
    }

    /** 범위 데이터를 그대로 담는 버퍼(정해진 크기, 복사 없이). 넘치게 쓰면 file_too_big. */
    class Sink(n: Int) : OutputStream() {
        val bytes = ByteArray(n)
        var count = 0
            private set

        override fun write(b: Int) { write(byteArrayOf(b.toByte()), 0, 1) }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (len > bytes.size - count) throw ProtocolException("file_too_big")
            System.arraycopy(b, off, bytes, count, len)
            count += len
        }
    }
}

/**
 * 라이트박스가 무엇을 먼저 받을지(§17.4). 사진: screen이 있으면 그것(빠름), 원본이 fullMax 이하면 원본, 아니면 미리보기.
 * 영상: poster가 있으면 그것, 아니면 미리보기(512). 재생은 play가 있으면 그것, 아니면 원본.
 */
object CloudView {
    enum class First { SCREEN, ORIGINAL, POSTER, THUMB }

    fun first(item: CloudItem, video: Boolean, fullMax: Long): First = when {
        video -> if (CloudVariants.POSTER in item.variants) First.POSTER else First.THUMB
        CloudVariants.SCREEN in item.variants -> First.SCREEN
        item.size <= fullMax -> First.ORIGINAL
        else -> First.THUMB
    }

    /** screen을 먼저 보인 사진: 100 %보다 크게 확대하면 원본(fullMax 이하일 때)으로 바꿀 수 있다. */
    fun canUpgrade(item: CloudItem, video: Boolean, fullMax: Long): Boolean =
        !video && CloudVariants.SCREEN in item.variants && item.size <= fullMax

    /** 재생에 쓸 딸린 버전(없으면 null = 원본). */
    fun playVariant(item: CloudItem): String? = CloudVariants.PLAY.takeIf { it in item.variants }
}
