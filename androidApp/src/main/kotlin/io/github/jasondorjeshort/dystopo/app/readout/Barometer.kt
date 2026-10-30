package io.github.jasondorjeshort.dystopo.app.readout

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import io.github.jasondorjeshort.dystopo.app.DystopoApp

/**
 * T161: the phone's barometer (`Sensor.TYPE_PRESSURE`) for the live readout, read only while [LiveReadout] wants it (the
 * strip shown in the foreground, the barometer switch on, an activity that uses it, climb or elevation shown). One
 * sample a second, batched by the sensor hub up to [BATCH_US] (the readout idea's research: the sensor draws a few µA,
 * the cost is waking the CPU), delivered on a thread of its own, never the main thread. Each sample's time is the
 * sensor's own, turned into wall-clock milliseconds, so a batch keeps its spacing.
 */
object Barometer {
    private const val SAMPLE_US = 1_000_000
    private const val BATCH_US = 4_000_000

    private val manager: SensorManager? get() = runCatching { DystopoApp.instance.getSystemService(Context.SENSOR_SERVICE) as SensorManager }.getOrNull()
    private val sensor: Sensor? by lazy { runCatching { manager?.getDefaultSensor(Sensor.TYPE_PRESSURE) }.getOrNull() }
    private val handler by lazy { Handler(HandlerThread("dystopo-barometer").apply { start() }.looper) }

    /** Whether the phone has a barometer at all. */
    val present: Boolean get() = sensor != null

    @Volatile var listening = false
        private set
    @Volatile var samples = 0L
        private set
    @Volatile var starts = 0L
        private set
    @Volatile var lastHPa: Double? = null
        private set
    @Volatile var lastAtWallMs = 0L
        private set

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val hPa = event.values.firstOrNull()?.toDouble() ?: return
            // The event's time is on the elapsed-realtime clock; a batched sample is older than now by as much.
            val wallMs = System.currentTimeMillis() - (SystemClock.elapsedRealtimeNanos() - event.timestamp) / 1_000_000
            samples++
            lastHPa = hPa
            lastAtWallMs = wallMs
            LiveReadout.onSensorPressure(wallMs, hPa)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    /** Starts or stops the listener; any thread. */
    @Synchronized
    fun want(on: Boolean) {
        val s = sensor ?: return
        if (on == listening) return
        val m = manager ?: return
        if (on) {
            listening = runCatching { m.registerListener(listener, s, SAMPLE_US, BATCH_US, handler) }.getOrDefault(false)
            if (listening) starts++
        } else {
            runCatching { m.unregisterListener(listener) }
            listening = false
        }
    }

    fun json(): Map<String, Any?> = linkedMapOf(
        "present" to present,
        "name" to sensor?.name,
        "listening" to listening,
        "starts" to starts,
        "samples" to samples,
        "lastHPa" to lastHPa?.let { Math.round(it * 1000) / 1000.0 },
        "lastAgeMs" to if (lastAtWallMs == 0L) null else System.currentTimeMillis() - lastAtWallMs,
        "samplingUs" to SAMPLE_US, "maxBatchUs" to BATCH_US,
        "fifoMaxEvents" to sensor?.fifoMaxEventCount,
    )
}
