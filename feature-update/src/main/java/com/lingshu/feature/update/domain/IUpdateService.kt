package com.lingshu.feature.update.domain

import com.lingshu.core.common.error.Result
import java.io.File

interface IUpdateService {
    suspend fun checkForUpdate(): Result<UpdateInfo>
    suspend fun downloadUpdate(updateInfo: UpdateInfo, onProgress: (Int) -> Unit): Result<File>
    suspend fun installUpdate(apkFile: File): Result<Unit>
    suspend fun getCurrentVersion(): String
    suspend fun verifyMd5(file: File, expectedMd5: String): Boolean
}
