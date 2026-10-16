package com.surveil.camera.capture

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs

/**
 * Detects how the phone is mounted from gravity: returns the counter-clockwise device rotation
 * (0 = upright portrait, 90 = landscape with the top to the left, 180, 270). A new orientation is
 * reported only after it has been stable for a few seconds; lying flat keeps the last value.
 */
class OrientationProbe(context: Context, private val onStableChange: (Int) -> Unit) : SensorEventListener {
    private val prefs = context.getSharedPreferences("orientation", Context.MODE_PRIVATE)
    private val sm = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var registered = false

    @Volatile var rotation: Int? = null
        private set
    private var candidate: Int? = null
    private var candidateSinceMs = 0L

    fun start() {
        if (registered || sensor == null) return
        registered = sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    fun stop() {
        if (!registered) return
        sm.unregisterListener(this)
        registered = false
    }

    /** Waits briefly for the first reading; falls back to the last orientation seen (phone lying flat). */
    fun awaitRotation(timeoutMs: Long): Int? {
        val end = System.currentTimeMillis() + timeoutMs
        while (rotation == null && System.currentTimeMillis() < end) Thread.sleep(50)
        return rotation ?: prefs.getInt(KEY_LAST, -1).takeIf { it >= 0 }
    }

    override fun onSensorChanged(e: SensorEvent) {
        val x = e.values[0]
        val y = e.values[1]
        val z = e.values[2]
        val g = maxOf(abs(x), abs(y))
        if (g < 5f || abs(z) > 8.5f) return // lying flat: orientation undefined
        val r = when {
            abs(y) >= abs(x) -> if (y > 0) 0 else 180
            else -> if (x > 0) 90 else 270
        }
        val now = System.currentTimeMillis()
        if (rotation == null) {
            rotation = r
            candidate = r
            prefs.edit().putInt(KEY_LAST, r).apply()
            return
        }
        if (r != candidate) {
            candidate = r
            candidateSinceMs = now
        } else if (r != rotation && now - candidateSinceMs > STABLE_MS) {
            rotation = r
            prefs.edit().putInt(KEY_LAST, r).apply()
            onStableChange(r)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        private const val STABLE_MS = 3000L
        private const val KEY_LAST = "last"

        fun fromSetting(value: String): Int? = when (value) {
            "portrait" -> 0
            "landscape" -> 90
            "portrait_flip" -> 180
            "landscape_flip" -> 270
            else -> null
        }
    }
}
