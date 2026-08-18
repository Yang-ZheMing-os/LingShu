package com.lingshu.feature.control.data

import android.content.Context
import com.lingshu.core.common.log.LingShuLog
import com.lingshu.core.common.security.CryptoHelper
import com.lingshu.feature.control.domain.CustomCommand
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 用户自定义指令习惯管理器。
 *
 * - 使用 SharedPreferences 持久化，数据经 [CryptoHelper] 做 AES-256-GCM 加密后落盘；
 * - 内存中维护一份缓存，[resolveAlias] 供 CommandParser 同步调用；
 * - 通过 [commands] StateFlow 暴露数据驱动 UI 更新。
 */
@Singleton
class CustomCommandManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cryptoHelper: CryptoHelper
) {

    companion object {
        private const val TAG = "CustomCmdMgr"
        private const val PREFS_NAME = "lingshu_custom_commands"
        private const val KEY_ENCRYPTED = "encrypted_commands"
    }

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val _commands = MutableStateFlow<List<CustomCommand>>(emptyList())
    val commands: StateFlow<List<CustomCommand>> = _commands.asStateFlow()

    init {
        loadFromDisk()
    }

    /** 同步查找别名对应的目标指令；未命中返回 null。 */
    fun resolveAlias(input: String): String? {
        val cleaned = input.trim().lowercase()
        return _commands.value
            .firstOrNull { it.alias.trim().lowercase() == cleaned }
            ?.target
    }

    fun addCommand(alias: String, target: String) {
        val a = alias.trim()
        val t = target.trim()
        if (a.isEmpty() || t.isEmpty()) return

        val current = _commands.value.toMutableList()
        // 同别名覆盖
        current.removeAll { it.alias.equals(a, ignoreCase = true) }
        current.add(CustomCommand(a, t))
        _commands.value = current
        persist(current)
        LingShuLog.i(TAG, "新增自定义指令: $a -> $t")
    }

    fun removeCommand(alias: String) {
        val current = _commands.value.toMutableList()
        val removed = current.removeAll { it.alias == alias }
        if (removed) {
            _commands.value = current
            persist(current)
            LingShuLog.i(TAG, "删除自定义指令: $alias")
        }
    }

    private fun loadFromDisk() {
        val encrypted = prefs.getString(KEY_ENCRYPTED, null)
        if (encrypted.isNullOrEmpty()) {
            _commands.value = emptyList()
            return
        }
        try {
            val json = cryptoHelper.decrypt(encrypted)
            val arr = JSONArray(json)
            val list = mutableListOf<CustomCommand>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    CustomCommand(
                        alias = obj.getString("alias"),
                        target = obj.getString("target")
                    )
                )
            }
            _commands.value = list
            LingShuLog.i(TAG, "加载 ${list.size} 条自定义指令")
        } catch (e: Exception) {
            LingShuLog.e(TAG, "解密/解析自定义指令失败", e)
            _commands.value = emptyList()
        }
    }

    private fun persist(commands: List<CustomCommand>) {
        try {
            val arr = JSONArray()
            commands.forEach { cmd ->
                arr.put(
                    JSONObject().apply {
                        put("alias", cmd.alias)
                        put("target", cmd.target)
                    }
                )
            }
            val encrypted = cryptoHelper.encrypt(arr.toString())
            prefs.edit().putString(KEY_ENCRYPTED, encrypted).apply()
        } catch (e: Exception) {
            LingShuLog.e(TAG, "加密/保存自定义指令失败", e)
        }
    }
}
