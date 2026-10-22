package com.choidev.tailhop

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.work.Configuration

/**
 * 앱 프로세스(2.3.0). WorkManager(동기화 주기 맞추기, MirrorWork)를 이 설정으로 처음 쓸 때 연다(매니페스트에서 자동 시작을 뺐다).
 * WorkManager가 쓰는 JobScheduler 번호를 OutboxWake의 작업 번호(0x7448_0001)와 겹치지 않는 칸으로 묶는다.
 * 2.3.1(§19.3): 화면이 꺼지면 바로, 앱의 모든 화면이 5분 넘게 가려져 있으면 사본을 다시 잠근다(MirrorLock) — 따로 깨우거나 묻지 않는다.
 */
class TailHopApp : Application(), Configuration.Provider {
    private var started = 0

    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) MirrorLock.lock()
        }
    }

    override fun onCreate() {
        super.onCreate()
        LocalSpace.init(this)
        DeviceMirrorKeys.init(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { if (started++ == 0) MirrorLock.back() }
            override fun onActivityStopped(activity: Activity) { if (--started <= 0) { started = 0; MirrorLock.away() } }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) { }
            override fun onActivityResumed(activity: Activity) { }
            override fun onActivityPaused(activity: Activity) { }
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) { }
            override fun onActivityDestroyed(activity: Activity) { }
        })
        ContextCompat.registerReceiver(this, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setJobSchedulerJobIdRange(WORK_IDS_FROM, WORK_IDS_TO).build()

    companion object {
        const val WORK_IDS_FROM = 0x7448_1000
        const val WORK_IDS_TO = 0x7448_FFFF
    }
}
