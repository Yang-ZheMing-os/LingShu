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

        for (triggerType in TriggerType.values()) {
            if (triggers[triggerType] != true) continue
            
            val triggered = when (triggerType) {
                TriggerType.LATE_NIGHT -> checkLateNight(now)
                TriggerType.MEAL_TIME -> checkMealTime(now)
                TriggerType.SEDENTARY -> checkSedentary()
                TriggerType.DARK_WALKING -> checkDarkWalking()
                TriggerType.HEART_RATE -> checkHeartRate()
                TriggerType.STRESS -> checkStress()
                TriggerType.RAINY_DAY -> checkRainyDay()
            }

            if (triggered) {
                LingShuLog.d("Proactive", "Triggered: $triggerType")
                return triggerType
            }
        }
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

        return isLateNight && isScreenOn()
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
