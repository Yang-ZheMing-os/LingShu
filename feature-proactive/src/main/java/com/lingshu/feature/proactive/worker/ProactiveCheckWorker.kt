package com.lingshu.feature.proactive.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.lingshu.core.common.log.LingShuLog
import com.lingshu.feature.proactive.domain.IProactiveService
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

@HiltWorker
class ProactiveCheckWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val proactiveService: IProactiveService
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            LingShuLog.i(TAG, "========== worker 执行开始 ==========")
            val result = proactiveService.checkAndNotify()
            LingShuLog.i(TAG, "worker 执行结束, result=$result")
            if (result.isSuccess) {
                Result.success()
            } else {
                Result.retry()
            }
        } catch (e: Exception) {
            LingShuLog.e(TAG, "worker 抛出异常", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "ProactiveWorker"
        private const val WORK_NAME = "proactive_check_work"

        fun start(context: Context) {
            val workRequest = PeriodicWorkRequestBuilder<ProactiveCheckWorker>(
                15, TimeUnit.MINUTES
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                workRequest
            )
            LingShuLog.i(TAG, "Worker 已入队（UPDATE，周期 15min），WORK_NAME=$WORK_NAME")

            // 自检：1.5s 后读 WorkInfo 确认是否真的被系统接受（失败会是 BLOCKED/CANCELLED）
            val wm = WorkManager.getInstance(context)
            wm.getWorkInfosForUniqueWork(WORK_NAME).addListener({
                runCatching {
                    val infos = wm.getWorkInfosForUniqueWork(WORK_NAME).get()
                    infos.forEach { info ->
                        val state = info.state
                        when (state) {
                            WorkInfo.State.ENQUEUED,
                            WorkInfo.State.RUNNING ->
                                LingShuLog.i(TAG, "Worker 已被 WorkManager 接受: state=$state runAttemptCount=${info.runAttemptCount}")
                            WorkInfo.State.BLOCKED,
                            WorkInfo.State.CANCELLED,
                            WorkInfo.State.FAILED ->
                                LingShuLog.w(TAG, "Worker 异常 state=$state，检查 HiltWorkManagerInitializer 是否已注册")
                            else ->
                                LingShuLog.d(TAG, "Worker state=$state")
                        }
                    }
                }.onFailure { e ->
                    LingShuLog.w(TAG, "Worker 自检读 WorkInfo 失败", e)
                }
            }, { it.run() })
        }

        fun stop(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            LingShuLog.i(TAG, "Worker 已取消: $WORK_NAME")
        }

        /** 测试用：立即触发一次检查（不等 15min 周期） */
        fun fireNow(context: Context) {
            val oneShot = androidx.work.OneTimeWorkRequestBuilder<ProactiveCheckWorker>().build()
            WorkManager.getInstance(context).enqueue(oneShot)
            LingShuLog.i(TAG, "已触发一次性立即检查（不等周期）")
        }
    }
}
