package com.lingshu.feature.mod.domain

import com.lingshu.core.common.error.Result
import java.io.File

interface IModService {
    suspend fun installMod(file: File): Result<Mod>
    suspend fun enableMod(modId: String): Result<Unit>
    suspend fun disableMod(modId: String): Result<Unit>
    suspend fun uninstallMod(modId: String): Result<Unit>
    fun listMods(): List<Mod>
    suspend fun fetchModList(): Result<List<ModInfo>>
    suspend fun downloadMod(modId: String): Result<File>
}
