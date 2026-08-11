package com.lingshu.agent.feature.proactive

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 主动关怀数据层仓储
 *
 * 负责存储和查询以下三类数据：
 * 1. 关怀记录（CareRecord / CareRecordEntry）
 * 2. 解锁记录（用于行为触发分析）
 * 3. 负面情绪历史（用于记忆触发跟进）
 *
 * 采用 SharedPreferences + 内存缓存的混合存储：
 * - 轻量级状态（冷却时间、每日计数、最后时间戳）使用 SharedPreferences
 * - 列表数据（关怀历史、解锁历史、负面情绪历史）使用 JSON 序列化存入 SP，同时内存缓存一份供快速查询
 */
@Singleton
class ProactiveCareRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gson: Gson = Gson()
) {
    // ==================== SP Key 常量 ====================

    companion object {
        private const val PREFS_NAME = "proactive_care_prefs"

        private const val KEY_LAST_CARE_TIME = "last_care_time"
        private const val KEY_LAST_CARE_DATE = "last_care_date"
        private const val KEY_TODAY_COUNT = "today_count"

        private const val KEY_UNLOCK_RECORDS_JSON = "unlock_records_json"
        private const val KEY_NEGATIVE_MOOD_HISTORY_JSON = "negative_mood_history_json"
        private const val KEY_CARE_HISTORY_JSON = "care_history_json"

        private const val KEY_LAST_NEGATIVE_MOOD_TIME = "last_negative_mood_time"
        private const val KEY_LAST_NEGATIVE_MOOD_DESC = "last_negative_mood_desc"
    }

    // ==================== 内部存储 ====================

    /** SharedPreferences 实例 */
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ==================== 响应式流（供 ViewModel 订阅） ====================

    private val _todayCareCount = MutableStateFlow(0)
    val todayCareCountFlow: StateFlow<Int> = _todayCareCount

    private val _lastCareTimestamp = MutableStateFlow<Long?>(null)
    val lastCareTimestampFlow: StateFlow<Long?> = _lastCareTimestamp

    private val _lastCareTriggerType = MutableStateFlow<TriggerType?>(null)
    val lastCareTriggerTypeFlow: StateFlow<TriggerType?> = _lastCareTriggerType

    private val _careHistory = MutableStateFlow<List<CareRecordEntry>>(emptyList())
    val careHistoryFlow: StateFlow<List<CareRecordEntry>> = _careHistory

    /** 关怀记录内存缓存（最多保留 500 条） */
    private val careHistoryCache = mutableListOf<CareRecordEntry>()

    /** 负面情绪历史内存缓存 */
    private val negativeMoodHistoryCache = mutableListOf<NegativeMoodRecord>()

    // ==================== 初始化 ====================

    init {
        // 启动时从 SP 反序列化到内存缓存
        loadCareHistoryFromPrefs()
        loadNegativeMoodHistoryFromPrefs()
    }

    // ==================== 冷却 / 每日上限 ====================

    /**
     * 获取上次关怀的时间戳
     * @return 上次关怀时间（毫秒），从未关怀过返回 0
     */
    fun getLastCareTime(): Long = prefs.getLong(KEY_LAST_CARE_TIME, 0L)

    /**
     * 设置上次关怀时间（同时重置每日计数的日期标记）
     * 注：通常关怀触发成功后由 Service 层调用 [recordCareTriggered]，不需要直接调用此方法
     */
    fun setLastCareTime(time: Long = System.currentTimeMillis()) {
        val todayStr = getTodayString()
        val savedDate = prefs.getString(KEY_LAST_CARE_DATE, "")
        if (todayStr != savedDate) {
            prefs.edit().putInt(KEY_TODAY_COUNT, 0).apply()
        }
        prefs.edit()
            .putLong(KEY_LAST_CARE_TIME, time)
            .putString(KEY_LAST_CARE_DATE, todayStr)
            .apply()
    }

    /**
     * 获取今日已触发的关怀次数
     * @return 今日次数，如果日期变更自动返回 0
     */
    fun getTodayCareCount(): Int {
        val todayStr = getTodayString()
        val savedDate = prefs.getString(KEY_LAST_CARE_DATE, "")
        return if (todayStr == savedDate) {
            prefs.getInt(KEY_TODAY_COUNT, 0)
        } else {
            0
        }
    }

    /**
     * 今日关怀计数 +1
     * 注：通常由 [recordCareTriggered] 内部调用
     */
    fun incrementTodayCareCount() {
        val current = getTodayCareCount()
        prefs.edit().putInt(KEY_TODAY_COUNT, current + 1).apply()
    }

    /**
     * 获取距离上次关怀的分钟数
     * @return 从未关怀返回 Int.MAX_VALUE
     */
    fun getMinutesSinceLastCare(): Int {
        val last = getLastCareTime()
        if (last == 0L) return Int.MAX_VALUE
        return ((System.currentTimeMillis() - last) / 60000L).toInt()
    }

    // ==================== 按触发类型的上次触发时间（规格书 P5 间隔控制） ====================

    /** 获取指定触发类型的上次触发时间戳 */
    fun getLastTriggerTime(type: TriggerType): Long {
        return prefs.getLong("last_trigger_${type.name}", 0L)
    }

    /** 记录指定触发类型的触发时间 */
    fun setLastTriggerTime(type: TriggerType, time: Long = System.currentTimeMillis()) {
        prefs.edit().putLong("last_trigger_${type.name}", time).apply()
    }

    /** 获取指定触发类型距离上次触发的分钟数 */
    fun getMinutesSinceLastTrigger(type: TriggerType): Int {
        val last = getLastTriggerTime(type)
        if (last == 0L) return Int.MAX_VALUE
        return ((System.currentTimeMillis() - last) / 60000L).toInt()
    }

    // ==================== 解锁记录 ====================

    /**
     * 记录一次手机解锁事件（由 ScreenUnlockReceiver 调用）
     */
    fun recordUnlock() {
        val now = System.currentTimeMillis()
        val existing = getUnlockRecords()
        val todayMs = getTodayStartMs()
        // 只保留今天的解锁记录，避免 SP 无限膨胀
        val kept = existing.filter { it >= todayMs } + now
        saveUnlockRecords(kept)
    }

    /**
     * 获取所有解锁记录时间戳（按时间升序）
     */
    fun getUnlockRecords(): List<Long> {
        val raw = prefs.getString(KEY_UNLOCK_RECORDS_JSON, null) ?: return emptyList()
        return runCatching {
            val type = object : TypeToken<List<Long>>() {}.type
            gson.fromJson<List<Long>>(raw, type)
        }.getOrDefault(emptyList())
    }

    private fun saveUnlockRecords(list: List<Long>) {
        prefs.edit()
            .putString(KEY_UNLOCK_RECORDS_JSON, gson.toJson(list))
            .apply()
    }

    /**
     * 今日凌晨 0 点至今的解锁次数
     */
    fun getUnlockCountSinceMorning(): Int {
        val todayStart = getTodayStartMs()
        return getUnlockRecords().count { it >= todayStart }
    }

    /**
     * 最近一次解锁的时间戳（从未解锁返回 0）
     */
    fun getLastUnlockTime(): Long {
        return getUnlockRecords().maxOrNull() ?: 0L
    }

    // ==================== 负面情绪历史 ====================

    /**
     * 负面情绪记录
     */
    data class NegativeMoodRecord(
        val timestamp: Long = System.currentTimeMillis(),
        val description: String? = null,
        val intensity: Int = 5 // 1~10，情绪负面程度
    )

    /**
     * 记录一次检测到的负面情绪
     * @param description 本次情绪的描述（可选）
     * @param intensity 负面程度 1~10，默认 5
     */
    fun recordNegativeMood(
        description: String? = null,
        intensity: Int = 5
    ) {
        val record = NegativeMoodRecord(
            timestamp = System.currentTimeMillis(),
            description = description,
            intensity = intensity.coerceIn(1, 10)
        )
        // 更新快速查询时间戳和描述
        prefs.edit()
            .putLong(KEY_LAST_NEGATIVE_MOOD_TIME, record.timestamp)
            .putString(KEY_LAST_NEGATIVE_MOOD_DESC, description ?: "")
            .apply()

        // 追加到历史缓存，最多保留 200 条
        negativeMoodHistoryCache.add(0, record)
        while (negativeMoodHistoryCache.size > 200) {
            negativeMoodHistoryCache.removeAt(negativeMoodHistoryCache.size - 1)
        }
        saveNegativeMoodHistoryToPrefs()
    }

    /**
     * 获取上次检测到负面情绪距今的天数
     * 从未检测返回 Int.MAX_VALUE
     */
    fun getDaysSinceLastNegativeMood(): Int {
        val last = prefs.getLong(KEY_LAST_NEGATIVE_MOOD_TIME, 0L)
        if (last == 0L) return Int.MAX_VALUE
        return ((System.currentTimeMillis() - last) / 86400000L).toInt()
    }

    /**
     * 上次负面情绪的时间戳（毫秒），未记录返回 null
     */
    fun getLastNegativeMoodTimestamp(): Long? {
        val last = prefs.getLong(KEY_LAST_NEGATIVE_MOOD_TIME, 0L)
        return if (last == 0L) null else last
    }

    /**
     * 上次负面情绪的描述（可空）
     */
    fun getLastNegativeMoodDescription(): String? {
        val desc = prefs.getString(KEY_LAST_NEGATIVE_MOOD_DESC, "")
        return if (desc.isNullOrBlank()) null else desc
    }

    /**
     * 获取完整的负面情绪历史（按时间倒序）
     */
    fun getNegativeMoodHistory(limit: Int = 100): List<NegativeMoodRecord> {
        return negativeMoodHistoryCache.take(limit)
    }

    // ==================== 关怀历史 ====================

    /**
     * 记录一次已成功触发的关怀
     *
     * 此方法会：
     * 1. 更新 lastCareTime（冷却起始点）
     * 2. 递增今日计数（每日上限用）
     * 3. 写入 careHistory 列表（供 UI 查询）
     *
     * @param triggerType   详细触发类型（ProactiveConfig 中定义的 TriggerType）
     * @param triggerReason 触发原因描述
     * @param careContent   实际生成并发送的关怀文本
     * @param triggerData   附加触发数据（如心率、久坐时长等）
     * @return 新记录的 ID
     */
    fun recordCareTriggered(
        triggerType: TriggerType,
        triggerReason: String,
        careContent: String,
        triggerData: Map<String, Any> = emptyMap()
    ): String {
        val now = System.currentTimeMillis()
        val entry = CareRecordEntry(
            id = now.toString(),
            triggerType = ProactiveTrigger.fromDetailType(triggerType),
            content = careContent,
            sentAt = now,
            userReaction = ProactiveTriggers.CareFeedback.UNHANDLED
        )

        // 写入内存缓存，最多保留 500 条
        careHistoryCache.add(0, entry)
        while (careHistoryCache.size > 500) {
            careHistoryCache.removeAt(careHistoryCache.size - 1)
        }

        // 同步冷却/计数
        setLastCareTime(now)
        incrementTodayCareCount()

        // 持久化
        saveCareHistoryToPrefs()

        return entry.id
    }

    /**
     * 更新关怀记录的用户反馈（用户点击/滑掉通知时调用）
     */
    fun updateCareFeedback(
        recordId: String,
        feedback: ProactiveTriggers.CareFeedback
    ): Boolean {
        val index = careHistoryCache.indexOfFirst { it.id == recordId }
        if (index < 0) return false
        careHistoryCache[index] = careHistoryCache[index].copy(userReaction = feedback)
        saveCareHistoryToPrefs()
        return true
    }

    /**
     * 查询关怀历史（按时间倒序）
     * @param limit 返回条数上限，默认 100 条
     */
    fun getCareHistory(limit: Int = 100): List<CareRecordEntry> {
        return careHistoryCache.take(limit)
    }

    /**
     * 查询指定触发大类的历史
     */
    fun getCareHistoryByType(
        type: TriggerTypeCategory,
        limit: Int = 50
    ): List<CareRecordEntry> {
        return careHistoryCache
            .filter { it.triggerType == type }
            .take(limit)
    }

    // ==================== Legacy 兼容（旧的 CareRecord 数据类） ====================

    /**
     * 旧版 CareRecord（与 7.x 之前代码兼容）
     */
    data class CareRecord(
        val id: Long = System.currentTimeMillis(),
        val triggerType: TriggerType,
        val message: String,
        val timestamp: Long = System.currentTimeMillis(),
        val responded: Boolean = false
    )

    /**
     * 添加一条旧版 CareRecord（兼容旧代码调用）
     */
    fun addCareRecord(record: CareRecord) {
        // 映射到新版并写入
        recordCareTriggered(
            triggerType = record.triggerType,
            triggerReason = "Legacy CareRecord 导入",
            careContent = record.message
        )
    }

    /**
     * 获取旧版 CareRecord 列表（兼容旧代码调用）
     */
    fun getRecentRecords(limit: Int = 100): List<CareRecord> {
        return careHistoryCache
            .take(limit)
            .map {
                CareRecord(
                    id = it.id.toLongOrNull() ?: it.sentAt,
                    triggerType = TriggerType.RANDOM, // 简化兼容
                    message = it.content,
                    timestamp = it.sentAt,
                    responded = it.userReaction == ProactiveTriggers.CareFeedback.INTERACTED
                )
            }
    }

    // ==================== 持久化辅助 ====================

    private fun loadCareHistoryFromPrefs() {
        val raw = prefs.getString(KEY_CARE_HISTORY_JSON, null) ?: return
        runCatching {
            val type = object : TypeToken<List<CareRecordEntry>>() {}.type
            val list: List<CareRecordEntry> = gson.fromJson(raw, type)
            careHistoryCache.clear()
            careHistoryCache.addAll(list)
        }
    }

    private fun saveCareHistoryToPrefs() {
        prefs.edit()
            .putString(KEY_CARE_HISTORY_JSON, gson.toJson(careHistoryCache))
            .apply()
    }

    private fun loadNegativeMoodHistoryFromPrefs() {
        val raw = prefs.getString(KEY_NEGATIVE_MOOD_HISTORY_JSON, null) ?: return
        runCatching {
            val type = object : TypeToken<List<NegativeMoodRecord>>() {}.type
            val list: List<NegativeMoodRecord> = gson.fromJson(raw, type)
            negativeMoodHistoryCache.clear()
            negativeMoodHistoryCache.addAll(list)
        }
    }

    private fun saveNegativeMoodHistoryToPrefs() {
        prefs.edit()
            .putString(KEY_NEGATIVE_MOOD_HISTORY_JSON, gson.toJson(negativeMoodHistoryCache))
            .apply()
    }

    // ==================== 工具 ====================

    private fun getTodayString(): String {
        val cal = Calendar.getInstance()
        return "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.MONTH) + 1}-${cal.get(Calendar.DAY_OF_MONTH)}"
    }

    private fun getTodayStartMs(): Long {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    /**
     * 清空所有存储（调试/隐私清除）
     */
    fun clearAll() {
        careHistoryCache.clear()
        negativeMoodHistoryCache.clear()
        _todayCareCount.value = 0
        _lastCareTimestamp.value = null
        _lastCareTriggerType.value = null
        _careHistory.value = emptyList()
        prefs.edit().clear().apply()
    }

    // ==================== ViewModel 桥接方法 ====================

    fun recordScreenUnlock() = recordUnlock()

    fun clearCareHistory() {
        careHistoryCache.clear()
        _careHistory.value = emptyList()
        saveCareHistoryToPrefs()
    }
}
