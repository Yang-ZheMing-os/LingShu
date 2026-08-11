package com.lingshu.feature.update.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.lingshu.core.common.error.Result
import com.lingshu.core.common.log.LingShuLog
import com.lingshu.feature.update.domain.IUpdateService
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class UpdateCheckWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val updateService: IUpdateService
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "UpdateCheckWorker"
        const val WORK_NAME = "update_check_work"
        const val KEY_SHOW_NOTIFICATION = "show_notification"
    }

    override suspend fun doWork(): Result {
        LingShuLog.d(TAG, "开始检查更新")

        return try {
            val showNotification = inputData.getBoolean(KEY_SHOW_NOTIFICATION, false)

            when (val result = updateService.checkForUpdate()) {
                is Result.Success -> {
                    val updateInfo = result.data
                    LingShuLog.i(TAG, "发现新版本: ${updateInfo.version}")
                    if (showNotification) {
                    }
                    Result.success()
                }
                is Result.Error -> {
                    LingShuLog.w(TAG, "检查更新失败: ${result.message}")
                    Result.success()
                }
            }
        } catch (e: Exception) {
            LingShuLog.e(TAG, "检查更新任务异常", e)
            Result.retry()
        }
    }
}
