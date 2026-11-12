package com.reach.app.spatial

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock

/**
 * Camera heading from the phone's fused rotation sensor. Prefers the game rotation vector (gyro + accelerometer, no
 * magnetometer: steady indoors, with an arbitrary but fixed "north" that a relative turn does not need); falls back to
 * the magnetometer-based rotation vector. Measured on the Galaxy A15 5G: both exist (LSM6DSL gyro). Timestamps are
 * uptimeMillis, the same clock as camera frames.
 */
class HeadingSensor(context: Context, private val onHeading: (Float, Long) -> Unit) : SensorEventListener {
    private val sm = context.getSystemService(SensorManager::class.java)
    private val sensor = sm?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) ?: sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val r = FloatArray(9)
    /** For diagnostics: which sensor, or why there is none. */
    val description get() = sensor?.let { "${it.name} (type ${it.type})" } ?: "no rotation sensor"

    fun start() { sensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) } }
    fun stop() { sm?.unregisterListener(this) }

    override fun onSensorChanged(e: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(r, e.values)
        cameraHeadingDeg(r)?.let { onHeading(it, SystemClock.uptimeMillis()) }
    }

    override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
}
