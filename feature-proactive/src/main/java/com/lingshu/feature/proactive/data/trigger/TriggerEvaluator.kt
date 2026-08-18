package com.lingshu.feature.proactive.data.trigger

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.lingshu.core.common.log.LingShuLog
import com.lingshu.feature.proactive.domain.QuietHours
import com.lingshu.feature.proactive.domain.TriggerType
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Calendar
import java.util.concurrent.atomic.AtomicReference

class TriggerEvaluator(private val context: Context) {

    companion object {
        private const val TAG = "ProactiveEval"
        // 和风天气代码 3xx=雨、4xx=雪、1xx=多云风、0xx=晴；触发提醒：雪/雨/雷暴
        private val RAINY_CODE_PREFIXES: Set<String> = setOf("3", "4")
        // 301-318 / 401-457 都是雨雪，雷暴前缀也在 3xx
        private const val CACHE_TTL_MS = 3600_000L
    }

    data class WeatherCache(
        val key: String,
        val location: String,
        val rainy: Boolean,
        val expiresAtMs: Long
    )

    private val weatherCache = AtomicReference<WeatherCache?>(null)

    private val sensorManager: SensorManager? by lazy {
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    }

    private var currentLightLux = Float.MAX_VALUE
    private var currentHeartRate = 0f
    private var currentHrv = 0f
    private var isWalking = false
    private var sedentaryStartTime = 0L

    private val lightListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            currentLightLux = event.values[0]
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private val heartRateListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            currentHeartRate = event.values[0]
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    init {
        registerSensors()
    }

    private fun registerSensors() {
        sensorManager?.let { sm ->
            sm.getDefaultSensor(Sensor.TYPE_LIGHT)?.let { sensor ->
                sm.registerListener(lightListener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            }
            sm.getDefaultSensor(Sensor.TYPE_HEART_RATE)?.let { sensor ->
                sm.registerListener(heartRateListener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            }
        }
    }

    fun evaluate(config: com.lingshu.feature.proactive.domain.ProactiveConfig): TriggerType? {
        val triggers = config.triggers
        val now = Calendar.getInstance()
        val h = now.get(Calendar.HOUR_OF_DAY)
        val m = now.get(Calendar.MINUTE)

        for (triggerType in TriggerType.values()) {
            val enabled = triggers[triggerType] == true
            if (!enabled) {
                LingShuLog.v(TAG, "跳过触发器 $triggerType：用户已关闭")
                continue
            }

            val triggered = when (triggerType) {
                TriggerType.LATE_NIGHT -> checkLateNight(now)
                TriggerType.MEAL_TIME -> {
                    val hit = checkMealTime(now)
                    LingShuLog.d(TAG, "MEAL_TIME 检查：当前$h:$m ∈ [早07-09/午11:30-13:30/晚17:30-19:30]? $hit")
                    hit
                }
                TriggerType.SEDENTARY -> {
                    val hit = checkSedentary()
                    val durSec = if (sedentaryStartTime == 0L) 0 else (System.currentTimeMillis() - sedentaryStartTime) / 1000
                    LingShuLog.d(TAG, "SEDENTARY 检查：坐了 ${durSec / 60}分${durSec % 60}秒 (需≥2h)? $hit")
                    hit
                }
                TriggerType.DARK_WALKING -> {
                    val hit = checkDarkWalking()
                    LingShuLog.d(TAG, "DARK_WALKING 检查：lux=${"%.1f".format(currentLightLux)} isWalking=$isWalking? $hit")
                    hit
                }
                TriggerType.HEART_RATE -> {
                    val hit = checkHeartRate()
                    LingShuLog.d(TAG, "HEART_RATE 检查：bpm=$currentHeartRate (异常>100 或 <45)? $hit")
                    hit
                }
                TriggerType.STRESS -> {
                    val hit = checkStress()
                    LingShuLog.d(TAG, "STRESS 检查：hrv=$currentHrv (>0.7=压力大)? $hit")
                    hit
                }
                TriggerType.RAINY_DAY -> {
                    val hit = checkRainyDay(config)
                    LingShuLog.d(TAG, "RAINY_DAY: 今天有雨雪? $hit")
                    hit
                }
                TriggerType.MEMORY -> false.also { LingShuLog.d(TAG, "MEMORY: 由外部模块处理，恒false") }
                TriggerType.RANDOM -> checkRandomTrigger(config.randomTriggerProbability).also { LingShuLog.d(TAG, "RANDOM: ${(config.randomTriggerProbability * 100).toInt()}%采样命中? $it") }
            }

            if (triggered) {
                val nonUrgentTypes = setOf(TriggerType.SEDENTARY, TriggerType.DARK_WALKING, TriggerType.RAINY_DAY, TriggerType.RANDOM)
                if (triggerType in nonUrgentTypes) {
                    if (kotlin.random.Random.nextFloat() > 0.25f) {
                        LingShuLog.i(TAG, "✅$triggerType 命中，但非紧急触发被 75% 概率过滤掉，继续下一个")
                        continue
                    }
                }
                LingShuLog.i(TAG, "🎯 命中触发器: $triggerType（优先级最高，已返回）")
                return triggerType
            }
        }
        LingShuLog.i(TAG, "全部 ${TriggerType.values().size} 个 trigger 遍历完毕，未命中任何一个")
        return null
    }

    fun isInQuietHours(quietHours: QuietHours): Boolean {
        val now = Calendar.getInstance()
        val currentMinute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val startMinute = quietHours.startHour * 60 + quietHours.startMinute
        val endMinute = quietHours.endHour * 60 + quietHours.endMinute

        return if (startMinute <= endMinute) {
            currentMinute in startMinute..endMinute
        } else {
            currentMinute >= startMinute || currentMinute <= endMinute
        }
    }

    private fun checkLateNight(now: Calendar): Boolean {
        val hour = now.get(Calendar.HOUR_OF_DAY)
        val minute = now.get(Calendar.MINUTE)
        val timeInMinutes = hour * 60 + minute

        val lateNightStart = 23 * 60 + 30
        val lateNightEnd = 5 * 60

        val isLateNight = if (lateNightStart > lateNightEnd) {
            timeInMinutes >= lateNightStart || timeInMinutes <= lateNightEnd
        } else {
            timeInMinutes in lateNightStart..lateNightEnd
        }

        val screenOn = isScreenOn()
        if (isLateNight) {
            LingShuLog.i(
                TAG,
                "LATE_NIGHT 时间窗口命中（${hour.toString().padStart(2,'0')}:${minute.toString().padStart(2,'0')} ∈ 23:30~05:00）, " +
                    "screenOn=$screenOn → 不再强绑屏幕亮灭，直接返回 true（修复之前 100% 被过滤的 Bug）"
            )
        }
        // ⚠️ 关键修复：之前必须 && isScreenOn()，导致 WorkManager 后台跑时屏幕必熄 → LATE_NIGHT 永远 false
        // 睡前关怀的目的是「到点就推」，不管用户现在亮不亮屏；他点亮屏幕看通知栏时就能看到。
        return isLateNight
    }

    private fun checkMealTime(now: Calendar): Boolean {
        val hour = now.get(Calendar.HOUR_OF_DAY)
        val minute = now.get(Calendar.MINUTE)
        val timeInMinutes = hour * 60 + minute

        val breakfastStart = 7 * 60
        val breakfastEnd = 9 * 60
        val lunchStart = 11 * 60 + 30
        val lunchEnd = 13 * 60 + 30
        val dinnerStart = 17 * 60 + 30
        val dinnerEnd = 19 * 60 + 30

        return timeInMinutes in breakfastStart..breakfastEnd ||
               timeInMinutes in lunchStart..lunchEnd ||
               timeInMinutes in dinnerStart..dinnerEnd
    }

    private fun checkSedentary(): Boolean {
        if (sedentaryStartTime == 0L) {
            sedentaryStartTime = System.currentTimeMillis()
            return false
        }
        val sedentaryDuration = System.currentTimeMillis() - sedentaryStartTime
        return sedentaryDuration >= 2 * 60 * 60 * 1000L
    }

    fun updateSedentaryState(isMoving: Boolean) {
        if (isMoving) {
            sedentaryStartTime = System.currentTimeMillis()
        }
        isWalking = isMoving
    }

    private fun checkDarkWalking(): Boolean {
        return currentLightLux < 10f && isWalking
    }

    private fun checkHeartRate(): Boolean {
        if (currentHeartRate <= 0f) return false
        return currentHeartRate > 100f || currentHeartRate < 45f
    }

    private fun checkStress(): Boolean {
        return currentHrv > 0.7f
    }

    fun updateHrv(hrv: Float) {
        currentHrv = hrv
    }

    private fun checkRainyDay(): Boolean {
        return false
    }

    private fun checkMemoryTrigger(): Boolean {
        // Memory trigger is handled externally by ProactiveServiceImpl
        // which checks birthdays, anniversaries, and negative emotion follow-ups
        return false
    }

    private fun checkRandomTrigger(probability: Float): Boolean {
        val p = probability.coerceIn(0f, 1f)
        return kotlin.random.Random.nextFloat() < p
    }

    /**
     * Day3-3：雨天提醒
     *  1. qWeatherKey 为空 → 恒不命中（保留旧行为，用户没填 Key 就别浪费 API）
     *  2. 调用和风天气「3日预报」接口，匹配今日是否为雨雪代码；1 小时进程内缓存
     *     接口：https://devapi.qweather.com/v7/weather/3d?location=xxx&key=yyy
     */
    private fun checkRainyDay(config: com.lingshu.feature.proactive.domain.ProactiveConfig): Boolean {
        val key = config.qWeatherKey.trim()
        if (key.isEmpty()) {
            LingShuLog.i(TAG, "RAINY_DAY: 用户未填和风天气 Key，跳过")
            return false
        }
        val location = config.qWeatherLocation.trim().ifEmpty { "auto_ip" }

        val cached = weatherCache.get()
        val nowMs = System.currentTimeMillis()
        if (cached != null && cached.key == key && cached.location == location && nowMs < cached.expiresAtMs) {
            return cached.rainy
        }

        val rainy = runCatching { fetchRainyFromQWeather(key, location) }
            .onFailure { LingShuLog.w(TAG, "RAINY_DAY: API 调用失败，按无雨处理", it) }
            .getOrDefault(false)
        weatherCache.set(WeatherCache(key, location, rainy, nowMs + CACHE_TTL_MS))
        return rainy
    }

    private fun fetchRainyFromQWeather(key: String, location: String): Boolean {
        val loc = URLEncoder.encode(location, "UTF-8")
        val url = URL("https://devapi.qweather.com/v7/weather/3d?location=$loc&key=$key")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 10_000
            requestMethod = "GET"
        }
        val code = conn.responseCode
        if (code != 200) {
            LingShuLog.w(TAG, "RAINY_DAY: 和风天气返回 HTTP $code")
            return false
        }
        val body = conn.inputStream.use { input ->
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText()
        }
        val root = JSONObject(body)
        val status = root.optString("code", "-1")
        if (status != "200") {
            LingShuLog.w(TAG, "RAINY_DAY: 和风天气业务状态码=$status body=${body.take(200)}")
            return false
        }
        val daily = root.optJSONArray("daily") ?: return false
        if (daily.length() == 0) return false
        val today = daily.getJSONObject(0)
        val textDay = today.optString("textDay", "")
        val textNight = today.optString("textNight", "")
        val codeDay = today.optString("iconDay", "")
        val codeNight = today.optString("iconNight", "")
        val rainKeywords = listOf("雨", "雪", "雷")
        val keywordMatch = rainKeywords.any { it in textDay || it in textNight }
        val codeMatch = listOf(codeDay, codeNight).any { c ->
            c.isNotEmpty() && c.first().toString() in RAINY_CODE_PREFIXES
        }
        LingShuLog.i(
            TAG,
            "RAINY_DAY: textDay=$textDay textNight=$textNight codeDay=$codeDay codeNight=$codeNight → keywordMatch=$keywordMatch codeMatch=$codeMatch"
        )
        return keywordMatch || codeMatch
    }

    private fun isScreenOn(): Boolean {
        return try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            powerManager.isInteractive
        } catch (e: Exception) {
            true
        }
    }

    fun destroy() {
        sensorManager?.unregisterListener(lightListener)
        sensorManager?.unregisterListener(heartRateListener)
    }
}
