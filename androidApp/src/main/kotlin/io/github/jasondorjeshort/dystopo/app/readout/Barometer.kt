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
 * T161: the phone's barometer (`Sensor.TYPE_PRESSURE`), read only while someone wants it. One sample a second, batched
 * by the sensor hub up to [BATCH_US] (the readout idea's research: the sensor draws a few µA, the cost is waking the
 * CPU), delivered on a thread of its own, never the main thread. Each sample's time is the sensor's own, turned into
 * wall-clock milliseconds, so a batch keeps its spacing.
 *
 * T178: [want] takes a [Reason]: [Reason.READOUT] while [LiveReadout] wants it (the strip shown in the foreground, the
 * barometer switch on, an activity that uses it, climb or elevation shown) and [Reason.TREND] while a track records
 * ([PressureWatch], from `tracking/TrackingService`: the pressure trend and its warning, with the screen off too). The
 * listener runs while any reason holds. With the trend among them the **wake-up** pressure sensor is preferred when the
 * phone has one (a non-wake-up sensor's FIFO in suspend is a ring buffer that overwrites old samples); the listener is
 * registered again when that choice changes. Each registration tells the trend ([PressureWatch.onListenerStart]) so the
 * sensor's first seconds are dropped. Samples go to the readout while it wants them and to the trend always (it keeps
 * them only while recording).
 */
object Barometer {
    private const val SAMPLE_US = 1_000_000
    private const val BATCH_US = 4_000_000

    /** Who wants the barometer. */
    enum class Reason(val code: String) { READOUT("readout"), TREND("trend") }

    private val manager: SensorManager? get() = runCatching { DystopoApp.instance.getSystemService(Context.SENSOR_SERVICE) as SensorManager }.getOrNull()
    private val sensor: Sensor? by lazy { runCatching { manager?.getDefaultSensor(Sensor.TYPE_PRESSURE) }.getOrNull() }
    /** T178: the wake-up variant, when the phone has one (null on most emulators). */
    private val wakeSensor: Sensor? by lazy { runCatching { manager?.getDefaultSensor(Sensor.TYPE_PRESSURE, true) }.getOrNull() }
    private val handler by lazy { Handler(HandlerThread("dystopo-barometer").apply { start() }.looper) }

    /** Whether the phone has a barometer at all. */
    val present: Boolean get() = sensor != null || wakeSensor != null

    private val reasons = java.util.EnumSet.noneOf(Reason::class.java)
    /** The sensor registered now, or null. */
    @Volatile private var registered: Sensor? = null
    @Volatile private var readoutWanted = false

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
            if (readoutWanted) LiveReadout.onSensorPressure(wallMs, hPa)
            PressureWatch.onSensorPressure(wallMs, hPa) // T178
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    /** T161's call: the readout's reason on or off. */
    fun want(on: Boolean) = want(Reason.READOUT, on)

    /** T178: [reason] on or off; the listener runs while any reason holds. Any thread. */
    @Synchronized
    fun want(reason: Reason, on: Boolean) {
        if (on) reasons += reason else reasons -= reason
        readoutWanted = Reason.READOUT in reasons
        val wanted = when {
            reasons.isEmpty() -> null
            Reason.TREND in reasons -> wakeSensor ?: sensor
            else -> sensor ?: wakeSensor
        }
        if (wanted === registered) return
        val m = manager ?: return
        if (registered != null) {
            runCatching { m.unregisterListener(listener) }
            registered = null
            listening = false
        }
        if (wanted != null) {
            listening = runCatching { m.registerListener(listener, wanted, SAMPLE_US, BATCH_US, handler) }.getOrDefault(false)
            if (listening) {
                registered = wanted
                starts++
                PressureWatch.onListenerStart(System.currentTimeMillis())
            }
        }
    }

    fun json(): Map<String, Any?> = linkedMapOf(
        "present" to present,
        "name" to (registered ?: sensor ?: wakeSensor)?.name,
        "listening" to listening,
        "reasons" to synchronized(this) { reasons.map { it.code } }, // T178
        "wakeUp" to (registered?.isWakeUpSensor ?: false), // T178: the wake-up sensor is the one registered
        "wakeUpAvailable" to (wakeSensor != null),
        "starts" to starts,
        "samples" to samples,
        "lastHPa" to lastHPa?.let { Math.round(it * 1000) / 1000.0 },
        "lastAgeMs" to if (lastAtWallMs == 0L) null else System.currentTimeMillis() - lastAtWallMs,
        "samplingUs" to SAMPLE_US, "maxBatchUs" to BATCH_US,
        "fifoMaxEvents" to (registered ?: sensor)?.fifoMaxEventCount,
    )
}
