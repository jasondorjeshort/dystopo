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
import android.hardware.display.DisplayManager
import android.view.Display
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

    /** T163: the turn-around clock's two alerts, counted by kind: `margin` (one beep, one buzz) and `turn` (the off-route double). */
    @Volatile var turnAlerts = 0
        private set

    fun turnAround(kind: String, sound: Boolean, vibrate: Boolean) {
        lastAlert = "turnAround:$kind"
        turnAlerts++
        if (kind == "turn") {
            if (sound) beep(ToneGenerator.TONE_PROP_BEEP2, 500)
            if (vibrate) buzz(longArrayOf(0, 250, 150, 250))
        } else {
            if (sound) beep(ToneGenerator.TONE_PROP_BEEP, 300)
            if (vibrate) buzz(longArrayOf(0, 400))
        }
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

    fun json(): Map<String, Any?> = linkedMapOf("tones" to tones, "vibrations" to vibrations, "lastAlert" to lastAlert, "lastError" to lastError, "turnAround" to turnAlerts)

    fun release() {
        runCatching { tone?.release() }
    }
}

/**
 * The compass (T61): the rotation-vector sensor's azimuth (magnetic, corrected for how the screen is
 * turned) into [Compass], which adds the WMM declination and smooths it. T100: the app's one compass
 * (controls/Heading), running only while the map is in facing and the app is in the foreground; the azimuth is
 * where the phone points however it is held (see [onSensorChanged]).
 */
class HeadingSensor(context: Context, val compass: Compass) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    /**
     * T163: the screen's rotation from the display service, which any context may ask. The window service asked from the
     * application context (as before) is a StrictMode context-use violation on every reading (T150: 228 per tour).
     */
    private val displays = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val sensor: Sensor? = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val rot = FloatArray(9)

    @Volatile var running = false
        private set
    @Volatile var lastReadingAt = 0L
        private set
    @Volatile var accuracy: Int? = null
        private set
    /** T100: the screen's tilt from lying flat (0) to upright (90), degrees, at the last reading. */
    @Volatile var tiltDeg: Double? = null
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
        val rotation = displays.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0
        // The top of the screen in the phone's own axes, as the display is turned.
        val (ux, uy) = when (rotation) {
            Surface.ROTATION_90 -> -1f to 0f
            Surface.ROTATION_180 -> 0f to -1f
            Surface.ROTATION_270 -> 1f to 0f
            else -> 0f to 1f
        }
        // T100: rot maps the phone's axes to east, north, up. Where the phone points is the top of the screen when it
        // lies flat and the back when it is held upright; tilted between, the level parts of both point the same way,
        // so their sum is steady at any tilt (the azimuth of the top edge alone swings wildly near upright).
        val e = rot[0] * ux + rot[1] * uy - rot[2]
        val n = rot[3] * ux + rot[4] * uy - rot[5]
        tiltDeg = Math.toDegrees(Math.acos(rot[8].toDouble().coerceIn(-1.0, 1.0)))
        if (e * e + n * n < 1e-6f) return
        compass.onMagnetic(Math.toDegrees(Math.atan2(e.toDouble(), n.toDouble())))
        lastReadingAt = SystemClock.elapsedRealtime()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        this.accuracy = accuracy
    }

    fun json(): Map<String, Any?> = linkedMapOf(
        "available" to available, "running" to running, "fresh" to fresh, "accuracy" to accuracy, "tiltDeg" to tiltDeg?.let(::r1),
        "readings" to compass.readings, "magneticDeg" to compass.magneticHeadingDeg?.let(::r1),
        "trueDeg" to compass.trueHeadingDeg?.let(::r1), "declinationDeg" to compass.declinationDeg?.let { Math.round(it * 100) / 100.0 },
    )
}

internal fun r1(v: Double) = Math.round(v * 10) / 10.0
