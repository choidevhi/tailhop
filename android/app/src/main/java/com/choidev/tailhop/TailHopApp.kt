package com.choidev.tailhop

import android.app.Application
import androidx.work.Configuration

/**
 * 앱 프로세스(2.3.0). WorkManager(동기화 주기 맞추기, MirrorWork)를 이 설정으로 처음 쓸 때 연다(매니페스트에서 자동 시작을 뺐다).
 * WorkManager가 쓰는 JobScheduler 번호를 OutboxWake의 작업 번호(0x7448_0001)와 겹치지 않는 칸으로 묶는다.
 */
class TailHopApp : Application(), Configuration.Provider {
    override fun onCreate() {
        super.onCreate()
        LocalSpace.init(this)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setJobSchedulerJobIdRange(WORK_IDS_FROM, WORK_IDS_TO).build()

    companion object {
        const val WORK_IDS_FROM = 0x7448_1000
        const val WORK_IDS_TO = 0x7448_FFFF
    }
}
