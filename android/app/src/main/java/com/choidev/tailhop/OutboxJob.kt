package com.choidev.tailhop

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * 보낼 곳 목록(outbox)의 한 건(docs/PROTOCOL.md §11.3). 텍스트 또는 파일(content URI) 하나를 PC 한 대(IP)에 보낸다.
 * xfer는 이 건을 다시 보낼 때도 같은 값을 쓴다(상대가 중복을 무시하고 조각을 이어 받게). 파일이 바뀌면 새 xfer.
 * 1.8.0: 클라우드 올리기(kind cloud_up, 파일 → PC 클라우드의 remote 폴더)와 가져오기(cloud_down, PC 클라우드의 remote 파일 →
 * Download/TailHop)도 같은 목록·재시도 규칙으로 돈다. 대화방에는 보이지 않고 클라우드 화면의 전송 목록에 보인다.
 * 안드로이드 의존성 없음(JVM 단위 테스트).
 */
data class OutboxJob(
    val id: String,
    val dev: String,
    val isText: Boolean,
    val text: String? = null,
    val uri: String? = null,
    /** 파일 이름·크기·수정 시각. 넣을 때는 모를 수 있고(-1, null) 보내는 서비스가 채운다. */
    val name: String? = null,
    val size: Long = -1,
    val mtime: Long? = null,
    val xfer: String,
    val created: Long,
    /** 실패한 횟수(재시도 간격을 정한다). */
    val attempts: Int = 0,
    val nextAt: Long = created,
    val state: String = WAITING,
    /** 마지막 실패의 오류 코드(Errors)와, 코드로 나타낼 수 없는 상대의 문장. */
    val error: String? = null,
    val detail: String? = null,
    /** 30일(Retry.KEEP_MS) 동안 못 보내 포기했는지(실패 이유 문장이 달라진다). */
    val gaveUp: Boolean = false,
    /** 무엇을 하는 건인지(TEXT·FILE·CLOUD_UP·CLOUD_DOWN). */
    val kind: String = if (isText) TEXT else FILE,
    /** 클라우드: 올릴 폴더(cloud_up, ""=맨 위) 또는 가져올 파일 경로(cloud_down). */
    val remote: String? = null,
    /**
     * cloud_down: 받다 만 조각 위치(PartFiles). 있으면 size·mtime은 그 조각을 만들 때 허브가 알린 값이고, 이어 받을 때 함께 보낸다.
     */
    val part: String? = null,
    /** cloud_down: cloud_changed로 처음부터 다시 받은 적이 있는지(한 번만 다시 받는다). */
    val restarted: Boolean = false,
) {
    val failed: Boolean get() = state == FAILED
    val isCloud: Boolean get() = kind == CLOUD_UP || kind == CLOUD_DOWN

    /**
     * 보내는 줄: PC마다 대화(텍스트·파일) 줄과 클라우드 줄을 따로 돌린다. 큰 클라우드 가져오기가 대화방 보내기를 막지 않게
     * (한 줄 안에서는 넣은 순서대로, §11.3).
     */
    val lane: String get() = if (isCloud) "$dev|cloud" else dev

    companion object {
        const val WAITING = "waiting"
        const val SENDING = "sending"
        const val FAILED = "failed"

        const val TEXT = "text"
        const val FILE = "file"
        const val CLOUD_UP = "cloud_up"
        const val CLOUD_DOWN = "cloud_down"
    }
}

/** outbox.json ↔ 목록. 깨진 항목은 건너뛴다. */
object OutboxCodec {
    fun encode(list: List<OutboxJob>): String {
        val arr = JSONArray()
        list.forEach { j ->
            arr.put(JSONObject().apply {
                put("id", j.id); put("dev", j.dev); put("t", j.isText)
                j.text?.let { put("text", it) }
                j.uri?.let { put("uri", it) }
                j.name?.let { put("name", it) }
                put("size", j.size)
                j.mtime?.let { put("mtime", it) }
                put("xfer", j.xfer); put("created", j.created); put("attempts", j.attempts); put("next", j.nextAt)
                put("state", j.state)
                j.error?.let { put("error", it) }
                j.detail?.let { put("detail", it) }
                if (j.gaveUp) put("gaveUp", true)
                // 1.7.0 목록과 같게 읽히도록 대화 건에는 kind를 적지 않는다.
                if (j.isCloud) put("kind", j.kind)
                j.remote?.let { put("remote", it) }
                j.part?.let { put("part", it) }
                if (j.restarted) put("restarted", true)
            })
        }
        return arr.toString()
    }

    /**
     * 목록을 읽는다. 보내는 중(SENDING)으로 남은 건은 프로세스가 끝나며 멈춘 것이므로 바로 다시 보낼 대기로 돌린다.
     */
    fun parse(json: String?, now: Long, own: Collection<String> = OWN_AUTHORITIES): List<OutboxJob> {
        val arr = try { JSONArray(json ?: return emptyList()) } catch (e: Exception) { return emptyList() }
        val out = ArrayList<OutboxJob>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            try {
                val isText = o.getBoolean("t")
                val kind = o.optString("kind", if (isText) OutboxJob.TEXT else OutboxJob.FILE)
                val j = OutboxJob(
                    id = o.getString("id"), dev = o.getString("dev"), isText = isText,
                    text = if (o.has("text")) o.getString("text") else null,
                    uri = if (o.has("uri")) o.getString("uri") else null,
                    name = if (o.has("name")) o.getString("name") else null,
                    size = o.optLong("size", -1),
                    mtime = if (o.has("mtime")) o.getLong("mtime") else null,
                    xfer = o.getString("xfer"), created = o.getLong("created"),
                    attempts = o.optInt("attempts", 0), nextAt = o.optLong("next", now),
                    state = o.optString("state", OutboxJob.WAITING),
                    error = if (o.has("error")) o.getString("error") else null,
                    detail = if (o.has("detail")) o.getString("detail") else null,
                    gaveUp = o.optBoolean("gaveUp", false),
                    kind = kind,
                    remote = if (o.has("remote")) o.getString("remote") else null,
                    part = if (o.has("part")) o.getString("part") else null,
                    restarted = o.optBoolean("restarted", false),
                )
                if (!Xfer.isValid(j.xfer) || !wellFormed(j, own)) continue
                out += if (j.state == OutboxJob.SENDING || j.state !in STATES) j.copy(state = OutboxJob.WAITING, nextAt = now) else j
            } catch (e: Exception) { }
        }
        return out
    }

    private val STATES = setOf(OutboxJob.WAITING, OutboxJob.SENDING, OutboxJob.FAILED)

    /** kind마다 꼭 있어야 하는 값. 클라우드 경로는 §12.1 규칙에 맞아야 한다(가져올 파일은 맨 위가 아니어야). */
    private fun wellFormed(j: OutboxJob, own: Collection<String>): Boolean = when (j.kind) {
        OutboxJob.TEXT -> j.isText && j.text != null
        OutboxJob.FILE -> !j.isText && isSourceUri(j.uri, own)
        OutboxJob.CLOUD_UP -> !j.isText && isSourceUri(j.uri, own) && j.remote != null && CloudPath.isValid(j.remote)
        OutboxJob.CLOUD_DOWN -> !j.isText && j.remote != null && j.remote.isNotEmpty() && CloudPath.isValid(j.remote) &&
            (j.part == null || isPartLoc(j.part))
        else -> false
    }

    /** 빠진 건들의 파일 URI 중 남은 건이 쓰지 않는 것(영구 읽기 권한을 돌려줄 것, H4). */
    fun urisToRelease(gone: List<OutboxJob>, remaining: List<OutboxJob>): Set<String> {
        val inUse = remaining.mapNotNull { it.uri }.toSet()
        return gone.mapNotNull { it.uri }.filter { it !in inUse }.toSet()
    }

    /** 이 앱의 provider(FileProvider 등) authority. 보낼 파일이 이 앱 내부 파일을 가리키지 못하게. */
    val OWN_AUTHORITIES: List<String> = listOf(BuildConfig.APPLICATION_ID + ".files")

    private val CONTENT_RE = Regex("content://([^/?#]+)(?:[/?#].*)?")
    private val MEDIA_PART_RE = Regex("content://media/[a-z0-9_]+/downloads/[0-9]+")

    /**
     * 보낼 파일(H2): content URI이고 이 앱 자신의 provider가 아니어야 한다(`0@<authority>` 같은 사용자 번호 접두도 떼고 비교 —
     * Protocol.isOwnAuthority). 목록 파일이 손상·변조돼도 내부 파일을 PC로 보내지 않게.
     */
    fun isSourceUri(uri: String?, own: Collection<String> = OWN_AUTHORITIES): Boolean {
        val m = CONTENT_RE.matchEntire(uri ?: return false) ?: return false
        return !Protocol.isOwnAuthority(m.groupValues[1], own)
    }

    /**
     * 클라우드 가져오기의 조각 위치(H2): MediaStore Downloads 항목 URI(API 29+) 또는 Download/TailHop 아래의 `.part` 절대 경로(26–28).
     * 실제로 지우고 열 때는 PartFiles가 한 번 더 확인한다.
     */
    fun isPartLoc(loc: String): Boolean {
        if (MEDIA_PART_RE.matches(loc)) return true
        if (!loc.startsWith("/") || !loc.endsWith(".part") || !loc.contains("/Download/TailHop/")) return false
        return loc.split('/').none { it == "." || it == ".." }
    }
}

/** 재시도 규칙(§11.3). */
object Retry {
    /** 1–5번째 실패 뒤 기다릴 시간. 그 뒤로는 5분마다. */
    private val BACKOFF_MS = longArrayOf(3_000, 10_000, 30_000, 60_000, 120_000)
    const val LATER_MS = 300_000L

    /**
     * 보낼 건을 포기하기까지(2.2.0, 30일 — 사용자가 취소할 때까지 거의 늘 남는다). 받는 쪽의 이어받기 조각 보관(Xfer.KEEP_MS, 24시간)과는
     * 따로다: 하루가 지나 다시 보내면 상대에 조각이 없어 처음부터 보낼 뿐이다(§11.3 resume_lost 없이 have=0).
     */
    const val KEEP_MS = 30L * 24 * 3600 * 1000

    /**
     * 다시 시도하는 실패의 코드: 연결 실패·끊김(응답 전 끊김은 pc_forgot으로 보인다), 이어받기 조각 없음, 해시 불일치,
     * 보내는 중 파일이 바뀜(다음 시도에서 바뀐 것을 보고 새 xfer로 처음부터 보낸다, PC와 같다).
     * 나머지 코드는 상대가 이유를 대고 거부했거나(§10), 이 폰의 파일·페어링 문제다.
     */
    private val RETRY_CODES = setOf("connect_failed", "disconnected", "pc_forgot", "resume_lost", "hash_mismatch",
        "file_changed")

    /** attempts번 실패한 뒤 기다릴 시간(ms). */
    fun delayMs(attempts: Int): Long = if (attempts in 1..BACKOFF_MS.size) BACKOFF_MS[attempts - 1] else LATER_MS

    /** 소켓 오류(시간 초과·연결 끊김)와 RETRY_CODES만 다시 시도한다. */
    fun isRetryable(e: Throwable): Boolean = when (e) {
        is ProtocolException -> e.code in RETRY_CODES
        is IOException -> true
        else -> false
    }

    /** 한 번 실패한 뒤의 상태: 다시 시도할 수 없거나 만든 지 30일(KEEP_MS)이 지났으면 실패, 아니면 다음 시도 시각을 정한다. */
    fun afterFailure(job: OutboxJob, now: Long, retryable: Boolean, code: String, detail: String?): OutboxJob {
        val n = job.attempts + 1
        return when {
            !retryable -> job.copy(state = OutboxJob.FAILED, attempts = n, error = code, detail = detail, gaveUp = false)
            now - job.created >= KEEP_MS ->
                job.copy(state = OutboxJob.FAILED, attempts = n, error = code, detail = detail, gaveUp = true)
            else -> job.copy(state = OutboxJob.WAITING, attempts = n, nextAt = now + delayMs(n), error = code, detail = detail)
        }
    }

    /**
     * cloud_down이 cloud_changed를 받음(이어 받는 사이 허브의 파일이 바뀜, §12.2): 처음 한 번은 조각을 버리고 바로 처음부터
     * (조각 정보·크기·시각을 지운다 — 다음 요청은 size·mtime 없이 offset 0), 두 번째면 실패로 둔다(계속 바뀌는 파일).
     * 버릴 조각 파일은 부른 쪽이 지운다.
     */
    fun afterChanged(job: OutboxJob, now: Long): OutboxJob {
        val n = job.attempts + 1
        return if (!job.restarted) job.copy(state = OutboxJob.WAITING, attempts = n, nextAt = now, part = null, size = -1,
            mtime = null, restarted = true, error = "cloud_changed", detail = null)
        else job.copy(state = OutboxJob.FAILED, attempts = n, part = null, size = -1, mtime = null, error = "cloud_changed",
            detail = null, gaveUp = false)
    }

    /** 사용자가 다시 보내기: 처음 넣은 것처럼(30일을 새로 센다). 같은 xfer를 써서 상대에 남은 조각을 이어 쓴다. */
    fun restart(job: OutboxJob, now: Long): OutboxJob = job.copy(state = OutboxJob.WAITING, attempts = 0, nextAt = now,
        created = now, error = null, detail = null, gaveUp = false, restarted = false)

    /**
     * 기다리기만 하던 건이 30일을 넘김(2.2.0): 보내기 서비스가 쉬는 동안(Idle)에는 시도가 없어 afterFailure가 불리지 않는다 —
     * 깨우는 작업(OutboxWake)이 대신 포기로 둔다. 실패·보내는 중이거나 아직이면 null.
     */
    fun expire(job: OutboxJob, now: Long): OutboxJob? =
        if (job.state == OutboxJob.WAITING && now - job.created >= KEEP_MS) job.copy(state = OutboxJob.FAILED, gaveUp = true) else null
}

/**
 * 상대가 꺼져 있을 때의 기다림(2.2.0). 줄의 맨 앞 건이 연결 실패로 다음 시도까지 PARK_MS 넘게 기다리면 그 줄 일꾼을 끝내고, 모든 줄이
 * 그렇게 기다리기만 하면 보내기 서비스(dataSync FGS)를 멈춘다. 그동안은 OutboxWake(JobScheduler, 네트워크 연결 + 15분 주기)·상대의 hello·
 * 네트워크 바뀜이 깨운다. 안드로이드 의존성 없음(JVM 단위 테스트).
 */
object Idle {
    const val PARK_MS = 120_000L

    /** 상대에 닿지 못한 실패(상대 꺼짐·Tailscale 끊김). 응답 뒤 끊김(pc_forgot·disconnected)은 상대가 켜져 있었다 — 여기 넣지 않는다. */
    private val OFFLINE_CODES = setOf("connect_failed")

    /** 상대가 꺼져 있어 기다리는 건(대화방에 "상대가 꺼져 있음"). */
    fun offline(j: OutboxJob): Boolean = j.state == OutboxJob.WAITING && j.attempts > 0 && j.error in OFFLINE_CODES

    /** 그 줄의 일꾼을 끝내도 되는지: 맨 앞 건이 상대가 꺼져 다음 시도까지 PARK_MS보다 오래 남았다. */
    fun parked(head: OutboxJob, now: Long): Boolean = offline(head) && head.nextAt - now > PARK_MS

    /**
     * 서비스가 멈추면 사라질 임시 읽기 권한에 기대는 파일 건(공유받은 파일: 공유 화면의 권한을 서비스 시작 요청에 실어 왔다, SendService 설명).
     * 영구 권한(persisted, takePersistableUriPermission)을 가진 것은 괜찮다.
     */
    fun needsGrant(j: OutboxJob, persisted: Set<String>): Boolean =
        !j.failed && (j.kind == OutboxJob.FILE || j.kind == OutboxJob.CLOUD_UP) && j.uri != null && j.uri !in persisted

    /** 서비스를 멈추고 쉬어도 되는지: 남은 건 중 임시 권한에 기대는 것이 없다(있으면 예전처럼 서비스가 기다린다). */
    fun canSleep(jobs: Collection<OutboxJob>, persisted: Set<String>): Boolean = jobs.none { needsGrant(it, persisted) }

    /** 모든 줄의 맨 앞 건이 parked인지(남은 일꾼 없이 쉴 때). */
    fun allParked(heads: Collection<OutboxJob>, now: Long): Boolean = heads.isNotEmpty() && heads.all { parked(it, now) }

    /**
     * 쉬는 서비스를 다시 켤 일이 있는지(앱 열기·네트워크 바뀜·깨우는 작업): 상대가 꺼져 기다리는 것이 아닌 건이 맨 앞에 있다.
     * 꺼져 기다리는 줄은 상대가 닿을 때(hello가 오가면 SendService.wake)만 켠다 — 닿지 않는 PC 때문에 알림을 띄웠다 끄지 않게.
     */
    fun needsService(heads: Collection<OutboxJob>): Boolean = heads.any { !it.failed && !offline(it) }
}

/** PC마다 hello를 보내는 간격(2.2.0, HelloPing): 마지막으로 보낸 지 gapMs가 지났을 때만. 안드로이드 의존성 없음. */
class PingGate(private val gapMs: Long) {
    private val last = HashMap<String, Long>()

    /** 지금 보내도 되면 true(보낸 것으로 적는다). */
    @Synchronized fun take(pc: String, now: Long): Boolean {
        val t = last[pc]
        if (t != null && now - t < gapMs && now >= t) return false
        last[pc] = now
        return true
    }
}
