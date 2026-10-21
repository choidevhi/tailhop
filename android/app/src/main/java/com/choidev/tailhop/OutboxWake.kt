package com.choidev.tailhop

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * 보내기 서비스가 쉬는 동안의 깨우기(2.2.0, Idle). 상대 PC가 꺼져 있어 기다리기만 할 때는 dataSync 포그라운드 서비스를 끄고,
 * 대신 이 작업(platform JobScheduler: 네트워크 연결 조건 + 15분 주기, 시스템이 다른 일과 몰아 깨운다)을 걸어 둔다.
 * 작업은 기다리는 PC에 hello를 한 번 보내 보고(HelloPing), 닿으면 그 PC 줄을 깨운다(SendService.wake). 보낼 것이 없어지면 취소한다.
 */
object OutboxWake {
    private const val JOB_ID = 0x7448_0001
    private const val PERIOD_MS = 15L * 60 * 1000

    /** 걸어 둔다(이미 있으면 그대로 — 다시 걸면 주기가 처음부터 다시 센다). */
    fun schedule(ctx: Context) {
        try {
            val js = ctx.getSystemService(JobScheduler::class.java) ?: return
            if (js.getPendingJob(JOB_ID) != null) return
            js.schedule(JobInfo.Builder(JOB_ID, ComponentName(ctx, OutboxWakeJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(PERIOD_MS)
                .setPersisted(true) // 다시 켜도 남는다(RECEIVE_BOOT_COMPLETED)
                .build())
        } catch (e: Exception) { } // 못 걸어도 상대의 hello·네트워크 바뀜·앱 열기가 깨운다
    }

    fun cancel(ctx: Context) {
        try { ctx.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID) } catch (e: Exception) { }
    }

    /** 보낼 것이 없으면 취소한다(취소·연결 해제·다 보냄 뒤). */
    fun sync(ctx: Context) { if (!Outbox.hasActive(ctx)) cancel(ctx) }
}

/** OutboxWake의 작업: 30일 지난 건을 포기로 두고, 기다리는 PC에 닿아 보고, 쉬는 서비스를 켤 일이 있으면 켠다. */
class OutboxWakeJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val app = applicationContext
        Thread({
            try {
                Outbox.expire(app, System.currentTimeMillis()).forEach { SendService.notifyFailed(app, it) }
                if (!Outbox.hasActive(app)) OutboxWake.cancel(app)
                else {
                    HelloPing.probeWaiting(app)
                    SendService.resumeIfNeeded(app)
                }
            } catch (e: Exception) { } finally { jobFinished(params, false) }
        }, "tailhop-outbox-wake").start()
        return true
    }

    /** 시스템이 멈춤(조건이 사라짐): 다음 주기에 다시 한다. 진행 중인 hello는 곧 끝난다. */
    override fun onStopJob(params: JobParameters): Boolean = false
}

/**
 * PC들에 hello 한 번(2.2.0): 이 폰의 받기 서비스가 켜지거나 네트워크가 바뀌면(Tailscale 연결) 페어링된 PC마다 보낸다 — PC의 보낼 곳 목록이
 * 이 폰이 닿는 것을 알고 바로 보내게. 닿으면 이 폰에서 그 PC로 기다리던 건도 깨운다. 몇 초 모아 한 번, PC마다 최소 60초 간격,
 * 실패는 조용히(백그라운드 스레드).
 */
object HelloPing {
    private const val GAP_MS = 60_000L
    private const val BATCH_MS = 3_000L
    private val gate = PingGate(GAP_MS)
    private val ui = Handler(Looper.getMainLooper())
    private var queued = false

    fun soon(ctx: Context) {
        val app = ctx.applicationContext
        synchronized(this) { if (queued) return; queued = true }
        ui.postDelayed({
            synchronized(this) { queued = false }
            Thread({ ping(app, null) }, "tailhop-hello").start()
        }, BATCH_MS)
    }

    /** 이 폰에서 보낼 건이 상대가 꺼져 기다리는 PC에만(OutboxWake, 부른 스레드에서). */
    fun probeWaiting(ctx: Context) {
        val devs = Outbox.all(ctx).filter { Idle.offline(it) }.map { it.dev }.toSet()
        if (devs.isNotEmpty()) ping(ctx, devs)
    }

    /** only가 null이면 페어링된 모든 PC. 이 폰에 Tailscale 주소가 없으면 하지 않는다(닿을 수 없다). */
    private fun ping(ctx: Context, only: Set<String>?) {
        if (Net.tailscaleIp() == null) return
        for (p in Store.all(ctx)) {
            if (only != null && p.pcIp !in only) continue
            if (!gate.take(p.pcIp, System.currentTimeMillis())) continue
            try {
                Net.hello(p, null, null) // 예전 PC는 null(hello를 모름) — 그래도 닿았다
                SendService.wake(ctx, p.pcIp)
            } catch (e: Exception) { }
        }
    }
}
