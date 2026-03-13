package com.yuxiang.aiphoto.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.roundToInt

class DeviceTiltMonitor(context: Context) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)

    @Volatile
    var currentRollDegrees: Float? = null
        private set

    private var started = false

    fun start() {
        if (started || rotationSensor == null) return
        started = sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_UI)
    }

    fun stop() {
        if (!started) return
        sensorManager.unregisterListener(this)
        started = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        SensorManager.getOrientation(rotationMatrix, orientation)
        val rollDeg = Math.toDegrees(orientation[2].toDouble()).toFloat()
        currentRollDegrees = normalizeRoll(rollDeg)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun normalizeRoll(rollDeg: Float): Float {
        var normalized = rollDeg
        if (normalized > 90f) normalized -= 180f
        if (normalized < -90f) normalized += 180f
        return (normalized * 10f).roundToInt() / 10f
    }
}

