package io.github.jasondorjeshort.dystopo.app.nav

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import io.github.jasondorjeshort.dystopo.nav.Compass

/**
 * The audible and tactile part of an off-route alert (T61): a short double beep on the notification
 * stream and, when enabled, a double buzz; a single soft beep when back on route. Counted, so tests
 * can see that an alert fired (the emulator plays tones into the void and has no vibrator).
 */
class NavAlerts(private val context: Context) {
    @Volatile var tones = 0
        private set
    @Volatile var vibrations = 0
        private set
    @Volatile var lastAlert: String? = null
        private set
    @Volatile var lastError: String? = null
        private set

    private val tone: ToneGenerator? by lazy {
        runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90) }.onFailure { lastError = "tone: ${it.message}" }.getOrNull()
    }

    fun offRoute(sound: Boolean, vibrate: Boolean) {
        lastAlert = "offRoute"
        if (sound) beep(ToneGenerator.TONE_PROP_BEEP2, 500)
        if (vibrate) buzz(longArrayOf(0, 250, 150, 250))
    }

    fun backOnRoute(sound: Boolean) {
        lastAlert = "backOnRoute"
        if (sound) beep(ToneGenerator.TONE_PROP_ACK, 200)
    }

    private fun beep(kind: Int, ms: Int) {
        val t = tone ?: return
        runCatching { t.startTone(kind, ms) }.onSuccess { tones++ }.onFailure { lastError = "tone: ${it.message}" }
    }

    private fun buzz(pattern: LongArray) {
        runCatching {
            val v: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (!v.hasVibrator()) {
                lastError = "no vibrator"
                return
            }
            v.vibrate(VibrationEffect.createWaveform(pattern, -1))
            vibrations++
        }.onFailure { lastError = "vibrate: ${it.message}"; Log.w(NavSession.TAG, "vibrate failed", it) }
    }

    fun json(): Map<String, Any?> = linkedMapOf("tones" to tones, "vibrations" to vibrations, "lastAlert" to lastAlert, "lastError" to lastError)

    fun release() {
        runCatching { tone?.release() }
    }
}

/**
 * The compass (T61): the rotation-vector sensor's azimuth (magnetic, corrected for how the screen is
 * turned) into [Compass], which adds the WMM declination and smooths it. Runs only while navigating.
 */
class HeadingSensor(private val context: Context, val compass: Compass) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val rot = FloatArray(9)
    private val remapped = FloatArray(9)
    private val orientation = FloatArray(3)

    @Volatile var running = false
        private set
    @Volatile var lastReadingAt = 0L
        private set
    @Volatile var accuracy: Int? = null
        private set

    val available: Boolean get() = sensor != null

    fun start() {
        if (running || sensor == null) return
        running = manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
    }

    fun stop() {
        if (!running) return
        manager.unregisterListener(this)
        running = false
    }

    /** True when a reading arrived in the last few seconds. */
    val fresh: Boolean get() = running && lastReadingAt != 0L && SystemClock.elapsedRealtime() - lastReadingAt < 3_000

    override fun onSensorChanged(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rot, event.values)
        @Suppress("DEPRECATION")
        val rotation = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
        val (x, y) = when (rotation) {
            Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
        }
        SensorManager.remapCoordinateSystem(rot, x, y, remapped)
        SensorManager.getOrientation(remapped, orientation)
        compass.onMagnetic(Math.toDegrees(orientation[0].toDouble()))
        lastReadingAt = SystemClock.elapsedRealtime()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        this.accuracy = accuracy
    }

    fun json(): Map<String, Any?> = linkedMapOf(
        "available" to available, "running" to running, "fresh" to fresh, "accuracy" to accuracy,
        "readings" to compass.readings, "magneticDeg" to compass.magneticHeadingDeg?.let(::r1),
        "trueDeg" to compass.trueHeadingDeg?.let(::r1), "declinationDeg" to compass.declinationDeg?.let { Math.round(it * 100) / 100.0 },
    )
}

internal fun r1(v: Double) = Math.round(v * 10) / 10.0
