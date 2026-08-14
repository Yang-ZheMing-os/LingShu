package com.lingshu.feature.proactive.data.trigger

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.lingshu.core.common.log.LingShuLog
import com.lingshu.feature.proactive.domain.QuietHours
import com.lingshu.feature.proactive.domain.TriggerType
import java.util.Calendar

class TriggerEvaluator(private val context: Context) {

    companion object {
        private const val TAG = "ProactiveEval"
    }

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

    fun evaluate(triggers: Map<TriggerType, Boolean>): TriggerType? {
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
                TriggerType.RAINY_DAY -> false.also { LingShuLog.d(TAG, "RAINY_DAY: 未接入天气API，恒false") }
                TriggerType.MEMORY -> false.also { LingShuLog.d(TAG, "MEMORY: 由外部模块处理，恒false") }
                TriggerType.RANDOM -> checkRandomTrigger().also { LingShuLog.d(TAG, "RANDOM: 5%采样命中? $it") }
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

    private fun checkRandomTrigger(): Boolean {
        // Random care trigger - 5% base probability
        return kotlin.random.Random.nextFloat() < 0.05f
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
