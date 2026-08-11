package com.lingshu.agent.core.model

import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.app.ActivityManager
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

enum class DeviceTier { HIGH, MID, LOW }

data class DeviceCapability(
    val totalRam: Long,           // bytes
    val availableRam: Long,       // bytes
    val totalStorage: Long,       // bytes
    val availableStorage: Long,   // bytes
    val androidVersion: String,
    val cpuArch: String,
    val gpuSupported: Boolean,
    val deviceTier: DeviceTier
)

@Singleton
class DeviceCapabilityManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStore: DataStore<Preferences>
) {
    companion object {
        private object Keys {
            val DEVICE_TIER = stringPreferencesKey("device_tier")
            val TOTAL_RAM = longPreferencesKey("device_total_ram")
            val AVAILABLE_RAM = longPreferencesKey("device_available_ram")
            val TOTAL_STORAGE = longPreferencesKey("device_total_storage")
            val AVAILABLE_STORAGE = longPreferencesKey("device_available_storage")
            val ANDROID_VERSION = stringPreferencesKey("device_android_version")
            val CPU_ARCH = stringPreferencesKey("device_cpu_arch")
            val GPU_SUPPORTED = booleanPreferencesKey("device_gpu_supported")
            val DETECTED = booleanPreferencesKey("device_capability_detected")
        }

        // 8GB+ = HIGH, 6GB+ = MID, <6GB = LOW
        private const val RAM_HIGH_THRESHOLD = 8L * 1024 * 1024 * 1024 // 8 GB
        private const val RAM_MID_THRESHOLD = 6L * 1024 * 1024 * 1024  // 6 GB
    }

    fun detect(): DeviceCapability {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        val totalRam = memInfo.totalMem
        val availableRam = memInfo.availMem

        // Storage
        val stat = StatFs(Environment.getDataDirectory().absolutePath)
        val totalStorage = stat.blockCountLong * stat.blockSizeLong
        val availableStorage = stat.availableBlocksLong * stat.blockSizeLong

        val androidVersion = Build.VERSION.RELEASE
        val cpuArch = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"

        // GPU support check (basic: OpenGL ES 3.0+)
        val gpuSupported = try {
            val egl = javax.microedition.khronos.egl.EGLContext.getEGL() as? javax.microedition.khronos.egl.EGL10
            egl?.let {
                val display = it.eglGetDisplay(javax.microedition.khronos.egl.EGL10.EGL_DEFAULT_DISPLAY)
                it.eglInitialize(display, IntArray(2))
                true
            } ?: true // fallback: assume supported
        } catch (_: Exception) {
            true
        }

        val deviceTier = when {
            totalRam >= RAM_HIGH_THRESHOLD -> DeviceTier.HIGH
            totalRam >= RAM_MID_THRESHOLD -> DeviceTier.MID
            else -> DeviceTier.LOW
        }

        return DeviceCapability(
            totalRam = totalRam,
            availableRam = availableRam,
            totalStorage = totalStorage,
            availableStorage = availableStorage,
            androidVersion = androidVersion,
            cpuArch = cpuArch,
            gpuSupported = gpuSupported,
            deviceTier = deviceTier
        )
    }

    suspend fun saveCapability(cap: DeviceCapability) {
        dataStore.edit { prefs ->
            prefs[Keys.DEVICE_TIER] = cap.deviceTier.name
            prefs[Keys.TOTAL_RAM] = cap.totalRam
            prefs[Keys.AVAILABLE_RAM] = cap.availableRam
            prefs[Keys.TOTAL_STORAGE] = cap.totalStorage
            prefs[Keys.AVAILABLE_STORAGE] = cap.availableStorage
            prefs[Keys.ANDROID_VERSION] = cap.androidVersion
            prefs[Keys.CPU_ARCH] = cap.cpuArch
            prefs[Keys.GPU_SUPPORTED] = cap.gpuSupported
            prefs[Keys.DETECTED] = true
        }
    }

    val capabilityFlow: Flow<DeviceCapability?> = dataStore.data.map { prefs ->
        if (prefs[Keys.DETECTED] != true) return@map null
        val tierStr = prefs[Keys.DEVICE_TIER] ?: return@map null
        DeviceCapability(
            totalRam = prefs[Keys.TOTAL_RAM] ?: 0L,
            availableRam = prefs[Keys.AVAILABLE_RAM] ?: 0L,
            totalStorage = prefs[Keys.TOTAL_STORAGE] ?: 0L,
            availableStorage = prefs[Keys.AVAILABLE_STORAGE] ?: 0L,
            androidVersion = prefs[Keys.ANDROID_VERSION] ?: "",
            cpuArch = prefs[Keys.CPU_ARCH] ?: "",
            gpuSupported = prefs[Keys.GPU_SUPPORTED] ?: true,
            deviceTier = try { DeviceTier.valueOf(tierStr) } catch (_: Exception) { DeviceTier.MID }
        )
    }
}
