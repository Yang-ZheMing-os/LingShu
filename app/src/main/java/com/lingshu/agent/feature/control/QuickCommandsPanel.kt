package com.lingshu.agent.feature.control

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SettingsBrightness
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.lingshu.agent.ui.components.GlassCard
import com.lingshu.agent.ui.components.GlassSectionTitle
import com.lingshu.agent.ui.theme.AccentGlow
import com.lingshu.agent.ui.theme.AccentPrimary
import com.lingshu.agent.ui.theme.GlassBubble
import com.lingshu.agent.ui.theme.GlassBubbleBorder
import com.lingshu.agent.ui.theme.GlassBubbleStrong
import com.lingshu.agent.ui.theme.PrimaryBackground
import com.lingshu.agent.ui.theme.Success
import com.lingshu.agent.ui.theme.TextPrimary
import com.lingshu.agent.ui.theme.TextSecondary
import com.lingshu.agent.ui.theme.TextTertiary
import com.lingshu.agent.ui.theme.Warning
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.google.gson.Gson
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 快捷指令数据模型
 */
data class QuickCommand(
    /** 指令名称（用户可见） */
    val name: String,
    /** 触发词 */
    val triggerWords: List<String>,
    /** 动作列表 */
    val actions: List<QuickAction>,
    /** 创建时间（System.currentTimeMillis） */
    val createdAt: Long,
    /** 使用次数 */
    val usageCount: Int = 0,
    /** 是否是系统预设（不可删除） */
    val isPreset: Boolean = false
)

data class QuickAction(
    val type: String, // "click", "swipe", "input", "launch", "system", "wait"
    val x: Float? = null,
    val y: Float? = null,
    val startX: Float? = null,
    val startY: Float? = null,
    val endX: Float? = null,
    val endY: Float? = null,
    val text: String? = null,
    val packageName: String? = null,
    val duration: Long? = null,
    val delay: Long? = null
)

/**
 * 快捷指令管理器
 */
@Singleton
class QuickCommandsManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStore: DataStore<Preferences>
) {
    companion object {
        private val KEY_CUSTOM_COMMANDS = stringPreferencesKey("quick_commands_custom")
        private val PREFIX_USAGE_COUNT = "qc_usage_"
    }

    private val gson = Gson()

    /** 规格书3.1：12条预设硬编码指令 */
    val presetCommands: List<QuickCommand> = listOf(
        QuickCommand(
            name = "打开WiFi",
            triggerWords = listOf("打开WiFi", "开启WiFi", "连WiFi", "打开无线"),
            actions = listOf(QuickAction(type = "system", text = "wifi_on")),
            createdAt = 0L, usageCount = 0, isPreset = true
        ),
        QuickCommand(
            name = "关闭WiFi",
            triggerWords = listOf("关闭WiFi", "关WiFi", "断开WiFi", "关闭无线"),
            actions = listOf(QuickAction(type = "system", text = "wifi_off")),
            createdAt = 0L, usageCount = 0, isPreset = true
        ),
        QuickCommand(
            name = "打开蓝牙",
            triggerWords = listOf("打开蓝牙", "开启蓝牙", "连蓝牙"),
            actions = listOf(QuickAction(type = "system", text = "bluetooth_on")),
            createdAt = 0L, usageCount = 0, isPreset = true
        ),
        QuickCommand(
            name = "打开手电筒",
            triggerWords = listOf("打开手电筒", "手电筒", "开闪光灯", "闪光灯"),
            actions = listOf(QuickAction(type = "system", text = "flashlight_on")),
            createdAt = 0L, usageCount = 0, isPreset = true
        ),
        QuickCommand(
            name = "调节亮度50%",
            triggerWords = listOf("亮度50", "亮度一半", "中等亮度"),
            actions = listOf(QuickAction(type = "system", text = "brightness_50")),
            createdAt = 0L, usageCount = 0, isPreset = true
        ),
        QuickCommand(
            name = "最大亮度",
            triggerWords = listOf("最大亮度", "最亮", "亮度最大"),
            actions = listOf(QuickAction(type = "system", text = "brightness_max")),
            createdAt = 0L, usageCount = 0, isPreset = true
        ),
        QuickCommand(
            name = "静音模式",
            triggerWords = listOf("静音", "关闭声音", "勿扰", "振动模式"),
            actions = listOf(QuickAction(type = "system", text = "volume_mute")),
            createdAt = 0L, usageCount = 0, isPreset = true
        ),
        QuickCommand(
            name = "返回主页",
            triggerWords = listOf("主页", "回主页", "桌面", "回到桌面"),
            actions = listOf(QuickAction(type = "system", text = "home")),
            createdAt = 0L, usageCount = 0, isPreset = true
        ),
        QuickCommand(
            name = "打开通知栏",
            triggerWords = listOf("通知", "通知栏", "下拉通知", "看通知"),
            actions = listOf(QuickAction(type = "system", text = "notifications")),
            createdAt = 0L, usageCount = 0, isPreset = true
        ),
        QuickCommand(
            name = "截屏",
            triggerWords = listOf("截屏", "截图", "截个图"),
            actions = listOf(QuickAction(type = "system", text = "screenshot")),
            createdAt = 0L, usageCount = 0, isPreset = true
        ),
        QuickCommand(
            name = "打开相机",
            triggerWords = listOf("相机", "拍照", "打开相机", "照相"),
            actions = listOf(QuickAction(type = "launch", packageName = "com.android.camera")),
            createdAt = 0L, usageCount = 0, isPreset = true
        ),
        QuickCommand(
            name = "自动旋转",
            triggerWords = listOf("自动旋转", "屏幕旋转", "横屏", "竖屏"),
            actions = listOf(QuickAction(type = "system", text = "auto_rotate")),
            createdAt = 0L, usageCount = 0, isPreset = true
        )
    )

    /** 使用次数响应式流 */
    private val usageCountsFlow: Flow<Map<String, Int>> = dataStore.data.map { prefs ->
        val map = mutableMapOf<String, Int>()
        prefs.asMap().forEach { (key, value) ->
            if (key.name.startsWith(PREFIX_USAGE_COUNT) && value is Int) {
                // 在DataStore Preferences中 key.name 已经是不含前缀的纯key名
                map[key.name] = value
            }
        }
        map
    }

    /** 合并预设和自定义指令，按使用次数降序排列 */
    suspend fun getAllCommands(): List<QuickCommand> {
        val usageMap = usageCountsFlow.first()
        val custom = getCustomCommands()
        val allCommands = presetCommands.map { preset ->
            val key = "${PREFIX_USAGE_COUNT}${preset.name}"
            preset.copy(usageCount = usageMap[key] ?: 0)
        } + custom.map { customCmd ->
            val stored = usageMap.filterKeys { it == "${PREFIX_USAGE_COUNT}${customCmd.name}" }
            customCmd.copy(usageCount = stored.values.firstOrNull() ?: customCmd.usageCount)
        }
        return allCommands.sortedByDescending { it.usageCount }
    }

    /** 增加指令使用次数 */
    suspend fun incrementUsage(commandName: String) {
        val key = "${PREFIX_USAGE_COUNT}$commandName"
        dataStore.edit { prefs ->
            val current = getUsageCountDirect(prefs, key)
            prefs[intPreferencesKey(key)] = current + 1
        }
    }

    private fun getUsageCountDirect(prefs: Preferences, keyName: String): Int {
        return try {
            val allKeys = prefs.asMap().keys
            val matchedKey = allKeys.find { it.name == keyName }
            if (matchedKey != null) prefs[matchedKey] as? Int ?: 0 else 0
        } catch (e: Exception) {
            0
        }
    }

    private suspend fun getCustomCommands(): List<QuickCommand> {
        return try {
            val raw = dataStore.data.map { prefs ->
                prefs[stringPreferencesKey("quick_commands_custom")]
            }.first()
            if (raw.isNullOrBlank()) emptyList()
            else {
                val type = object : com.google.gson.reflect.TypeToken<List<QuickCommand>>() {}.type
                gson.fromJson<List<QuickCommand>>(raw, type)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun saveCustomCommands(commands: List<QuickCommand>) {
        val raw = gson.toJson(commands)
        dataStore.edit { it[stringPreferencesKey("quick_commands_custom")] = raw }
    }

    suspend fun addCustomCommand(command: QuickCommand) {
        val current = getCustomCommands().toMutableList()
        current.add(command)
        saveCustomCommands(current)
    }

    suspend fun removeCustomCommand(commandName: String) {
        val current = getCustomCommands().filter { it.name != commandName }
        saveCustomCommands(current)
    }

    /**
     * 解析系统指令文本并执行对应操作
     */
    suspend fun executeSystemCommand(
        systemController: SystemController,
        accessibilityController: AccessibilityController,
        actionText: String
    ): DeviceActionResult {
        return when (actionText) {
            "wifi_on" -> systemController.setWifiEnabled(true)
            "wifi_off" -> systemController.setWifiEnabled(false)
            "bluetooth_on" -> systemController.setBluetoothEnabled(true)
            "flashlight_on" -> systemController.setFlashlightEnabled(true)
            "brightness_50" -> systemController.setBrightness(128)
            "brightness_max" -> systemController.setBrightness(255)
            "volume_mute" -> systemController.setVolume(VolumeType.MUSIC, 0, false)
            "home" -> {
                val ok = accessibilityController.pressHome()
                DeviceActionResult(ok, "主页", if (ok) "已返回主页" else "请先开启无障碍服务")
            }
            "notifications" -> {
                val ok = accessibilityController.openNotifications()
                DeviceActionResult(ok, "通知栏", if (ok) "已打开通知栏" else "请先开启无障碍服务")
            }
            "screenshot" -> {
                val ok = accessibilityController.pressPower()
                DeviceActionResult(ok, "截屏", if (ok) "已触发截屏" else "截屏需要无障碍服务支持")
            }
            "auto_rotate" -> systemController.setAutoRotateEnabled(!systemController.isAutoRotateEnabled())
            else -> DeviceActionResult(false, "系统指令", "未知系统指令: $actionText")
        }
    }
}

/**
 * 快捷指令面板（Compose）
 */
@Composable
fun QuickCommandsPanel(
    manager: QuickCommandsManager,
    systemController: SystemController,
    accessibilityController: AccessibilityController,
    onResult: (DeviceActionResult) -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // 加载指令列表
    val commands = remember { mutableListOf<QuickCommand>() }
    val loaded = remember { kotlinx.coroutines.flow.MutableStateFlow(false) }

    LaunchedEffect(Unit) {
        commands.clear()
        commands.addAll(manager.getAllCommands())
        loaded.value = true
    }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        GlassSectionTitle(title = "快捷指令")
        Spacer(modifier = Modifier.height(12.dp))

        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(0.dp)
        ) {
            items(commands.size) { index ->
                val cmd = commands[index]
                QuickCommandCard(
                    name = cmd.name,
                    usageCount = cmd.usageCount,
                    isPreset = cmd.isPreset,
                    onClick = {
                        scope.launch {
                            manager.incrementUsage(cmd.name)
                            for (action in cmd.actions) {
                                val result = when (action.type) {
                                    "system" -> manager.executeSystemCommand(
                                        systemController, accessibilityController,
                                        action.text ?: ""
                                    )
                                    "launch" -> systemController.launchApp(
                                        action.packageName ?: ""
                                    )
                                    else -> DeviceActionResult(false, action.type, "暂不支持的操作类型")
                                }
                                onResult(result)
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun QuickCommandCard(
    name: String,
    usageCount: Int,
    isPreset: Boolean,
    onClick: () -> Unit
) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clickable { onClick() },
        shape = RoundedCornerShape(14.dp),
        glowColor = if (usageCount > 0) Success else AccentGlow,
        glowAlpha = if (usageCount > 0) 0.2f else 0.08f,
        padding = PaddingValues(10.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.labelMedium,
                color = TextPrimary,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (usageCount > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "已用${usageCount}次",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextTertiary
                )
            }
            if (isPreset) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "预设",
                    style = MaterialTheme.typography.labelSmall,
                    color = AccentGlow.copy(alpha = 0.6f)
                )
            }
        }
    }
}
