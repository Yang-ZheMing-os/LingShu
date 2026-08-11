package com.lingshu.feature.proactive.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
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
            LingShuLog.d("Proactive", "ProactiveCheckWorker running")
            val result = proactiveService.checkAndNotify()
            if (result.isSuccess) {
                Result.success()
            } else {
                Result.retry()
            }
        } catch (e: Exception) {
            LingShuLog.e("Proactive", "ProactiveCheckWorker failed", e)
            Result.retry()
        }
    }

    companion object {
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
            LingShuLog.i("Proactive", "ProactiveCheckWorker started")
        }

        fun stop(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            LingShuLog.i("Proactive", "ProactiveCheckWorker stopped")
        }
    }
}
