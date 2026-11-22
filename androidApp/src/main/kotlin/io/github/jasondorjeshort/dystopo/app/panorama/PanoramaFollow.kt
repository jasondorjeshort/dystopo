package io.github.jasondorjeshort.dystopo.app.panorama

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.controls.Heading
import io.github.jasondorjeshort.dystopo.horizon.AttitudeFilter
import io.github.jasondorjeshort.dystopo.horizon.PanoramaAim
import io.github.jasondorjeshort.dystopo.horizon.PhoneAttitude
import io.github.jasondorjeshort.dystopo.horizon.Quat
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * T330: the Panorama following the phone (wave 46's choices (a) to (e), (j); ADR 0064): the attitude sensor's samples
 * (`controls/Heading`'s hold, [io.github.jasondorjeshort.dystopo.app.nav.AttitudeSensor]) or a simulated attitude (debug,
 * `POST /panorama?attitude=`) go through the same path: [PhoneAttitude.quat], [AttitudeFilter.step], then the drawn attitude's
 * axes for the display's rotation, turned by the app's declination (`Compass`), become [PanoramaUi.aim] by
 * [PanoramaAim.follow]. While the filter rests the aim is not written, so the view is not rebuilt and the names are placed;
 * while it moves ([moving]) the names keep their offsets as in a drag. Main thread throughout (the sensor delivers there).
 * Nothing of the attitude is kept between sessions.
 */
object PanoramaFollow {
    /** Whether the view follows the phone now (the setting, a sensor, the view open; the screen writes it). Compose state. */
    var following by mutableStateOf(false)

    /** True while the drawn attitude moves (the filter not resting): the names keep their offsets. Compose state. */
    var moving by mutableStateOf(false)

    /** Whether the source is started (the view open in Follow, the app in the foreground). */
    @Volatile var active = false
        private set

    @Volatile var state: AttitudeFilter.State? = null
        private set
    @Volatile var rotationDeg = 0
        private set
    @Volatile var lastRaw: Quat? = null
        private set
    @Volatile var samples = 0L
        private set
    @Volatile var aimWrites = 0L
        private set

    /**
     * A simulated attitude (debug): [q] a raw rotation vector as the sensor gives it, or else true [bearingDeg] (turned
     * by [rateDegS] per second since [startMs]), [tiltDeg] and [rollDeg] made a vector for the display's rotation by
     * [PhoneAttitude.quatOf] (less the declination: the vector's north is magnetic); [noiseDeg] a jitter on each sample.
     */
    data class Sim(
        val q: Quat?, val bearingDeg: Double, val tiltDeg: Double, val rollDeg: Double, val noiseDeg: Double, val rateDegS: Double,
        val startMs: Long = SystemClock.elapsedRealtime(),
    )

    @Volatile var simulated: Sim? = null
        private set

    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val random = java.util.Random(330)

    /** Starts the source (main thread): the sensor's hold and, with a simulated attitude, its 20 ms feed. */
    fun start() {
        if (active) return
        active = true
        state = null
        step = null
        Heading.attitudeSink = { v, rot, t -> if (simulated == null) onSample(v, rot, t) }
        Heading.setAttitudeWanted(true)
        if (simulated != null) feed()
    }

    /** Stops it (main thread): the hold is dropped, the filter forgets (a later start begins at its first sample). */
    fun stop() {
        if (!active) return
        active = false
        Heading.setAttitudeWanted(false)
        Heading.attitudeSink = null
        handler.removeCallbacks(tick)
        state = null
        step = null
        if (moving) moving = false
    }

    /** Sets or clears ([sim] null) the simulated attitude (main thread). */
    fun simulate(sim: Sim?) {
        simulated = sim
        handler.removeCallbacks(tick)
        state = null
        step = null
        if (sim != null && active) feed()
    }

    private val tick = object : Runnable {
        override fun run() {
            val s = simulated ?: return
            if (!active) return
            onSample(simulatedSample(s), displayRotation(), SystemClock.elapsedRealtimeNanos())
            handler.postDelayed(this, SIM_PERIOD_MS)
        }
    }

    private fun feed() {
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    private fun displayRotation(): Int = runCatching {
        val dm = DystopoApp.instance.getSystemService(android.content.Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
        (dm.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.rotation ?: 0) * 90
    }.getOrDefault(0)

    private fun simulatedSample(s: Sim): DoubleArray {
        val base = s.q ?: run {
            val t = (SystemClock.elapsedRealtime() - s.startMs) / 1000.0
            val decl = Heading.compass.declinationDeg ?: 0.0
            PhoneAttitude.quatOf(s.bearingDeg + s.rateDegS * t - decl, s.tiltDeg, s.rollDeg, displayRotation())
        }
        val q = if (s.noiseDeg > 0) jitter(base, s.noiseDeg) else base
        return doubleArrayOf(q.x, q.y, q.z, q.w)
    }

    /** [q] turned by a small random rotation: each axis's angle drawn from a normal of [deg] (as a still phone's shake). */
    private fun jitter(q: Quat, deg: Double): Quat {
        val k = Math.toRadians(deg) / 2
        val d = Quat(random.nextGaussian() * k, random.nextGaussian() * k, random.nextGaussian() * k, 1.0).normalized()
        return Quat(
            q.w * d.x + q.x * d.w + q.y * d.z - q.z * d.y,
            q.w * d.y - q.x * d.z + q.y * d.w + q.z * d.x,
            q.w * d.z + q.x * d.y - q.y * d.x + q.z * d.w,
            q.w * d.w - q.x * d.x - q.y * d.y - q.z * d.z,
        ).normalized()
    }

    /** One sample: the sensor's values, the display's rotation (degrees) and the sample's clock (ns). Main thread. */
    fun onSample(values: DoubleArray, rotation: Int, timeNanos: Long) {
        val q = PhoneAttitude.quat(values) ?: return
        rotationDeg = rotation
        lastRaw = q
        samples++
        val prev = state
        val next = AttitudeFilter.step(prev, q, timeNanos) ?: return
        state = next
        figures(q, prev, next, timeNanos)
        val nowMoving = !next.resting
        if (moving != nowMoving) moving = nowMoving
        // While resting the drawn attitude stays where it rested: nothing is written, so nothing is rebuilt.
        if (following && (prev == null || !(prev.resting && next.resting) || rotation != aimRotation)) writeAim(next.drawn)
    }

    private var aimRotation = -1

    /** The aim for the drawn attitude [q] (main thread), the field of view kept, the bearing held near the poles. */
    fun writeAim(q: Quat) {
        val a = PanoramaUi.aim
        aimRotation = rotationDeg
        PanoramaUi.aim = PanoramaAim.follow(axesOf(q), a?.fovDeg ?: PanoramaAim.OPENING_FOV_DEG, a?.bearingDeg)
        aimWrites++
    }

    /** The lens's axes, true north, for the attitude [q] at the display's rotation now. */
    fun axesOf(q: Quat) = PhoneAttitude.turned(PhoneAttitude.axes(q, rotationDeg), Heading.compass.declinationDeg ?: 0.0)

    /** The aim of the drawn attitude now, or null (no sample yet). */
    fun aimNow(fovDeg: Double): PanoramaAim? = state?.let { PanoramaAim.follow(axesOf(it.drawn), fovDeg, PanoramaUi.aim?.bearingDeg) }

    // The figures of choice (c) (GET /panorama `attitude.figures`): the spread while still, the time to 90% of a quarter turn.
    private val rawWin = ArrayDeque<Quat>()
    private val filtWin = ArrayDeque<Quat>()

    private class Step(val t0: Long, val from: Quat, val to: Quat, val deg: Double)

    private var step: Step? = null

    /** The last step's time to 90 percent of its turn, ms, and its size, degrees. */
    @Volatile var stepTo90Ms: Double? = null
        private set
    @Volatile var stepDeg: Double? = null
        private set

    private fun figures(q: Quat, prev: AttitudeFilter.State?, next: AttitudeFilter.State, t: Long) {
        rawWin.addLast(q)
        filtWin.addLast(next.filtered)
        while (rawWin.size > WINDOW) rawWin.removeFirst()
        while (filtWin.size > WINDOW) filtWin.removeFirst()
        val s = step
        if (s == null && prev != null && prev.filtered.angleDegTo(q) >= STEP_DEG) {
            step = Step(t, prev.filtered, q, prev.filtered.angleDegTo(q))
        } else if (s != null) {
            val done = s.from.angleDegTo(next.filtered)
            if (done >= 0.9 * s.deg) {
                stepTo90Ms = (t - s.t0) / 1e6
                stepDeg = s.deg
                step = null
            } else if ((t - s.t0) / 1e6 > 3000) step = null
        }
    }

    /** The RMS angle of [qs] from their mean, degrees (null under 10). */
    private fun spread(qs: List<Quat>): Double? {
        if (qs.size < 10) return null
        val r = qs.last()
        var x = 0.0; var y = 0.0; var z = 0.0; var w = 0.0
        for (q in qs) {
            val s = if (q dot r < 0) -1.0 else 1.0
            x += s * q.x; y += s * q.y; z += s * q.z; w += s * q.w
        }
        val mean = Quat(x, y, z, w).normalized()
        return sqrt(qs.sumOf { val a = it.angleDegTo(mean); a * a } / qs.size)
    }

    private fun r2(v: Double) = Math.round(v * 100) / 100.0
    private fun r3(v: Double) = Math.round(v * 1000) / 1000.0

    /** `GET /panorama` `attitude` (main thread). */
    fun json(): Map<String, Any?> {
        val raw = lastRaw?.let { PanoramaAim.follow(axesOf(it), PanoramaAim.OPENING_FOV_DEG) }
        val st = state
        val filt = st?.let { PanoramaAim.follow(axesOf(it.filtered), PanoramaAim.OPENING_FOV_DEG) }
        val rs = spread(rawWin.toList())
        val fs = spread(filtWin.toList())
        fun angles(a: PanoramaAim?) = a?.let { linkedMapOf("bearingDeg" to r2(it.bearingDeg), "tiltDeg" to r2(it.tiltDeg), "rollDeg" to r2(it.rollDeg)) }
        val sensor = Heading.attitudeJson()
        return linkedMapOf(
            "running" to Heading.attitudeRunning, "active" to active, "simulated" to (simulated != null),
            "simulation" to simulated?.let { s ->
                linkedMapOf(
                    "q" to s.q?.let { listOf(it.x, it.y, it.z, it.w).map(::r3) }, "bearingDeg" to s.bearingDeg, "tiltDeg" to s.tiltDeg,
                    "rollDeg" to s.rollDeg, "noiseDeg" to s.noiseDeg, "rateDegS" to s.rateDegS,
                )
            },
            "kind" to sensor["kind"], "available" to sensor["available"], "status" to sensor["status"], "rateHz" to sensor["rateHz"],
            "sensorEvents" to sensor["events"], "samples" to samples, "aimWrites" to aimWrites,
            "rotationDeg" to rotationDeg, "declinationDeg" to Heading.compass.declinationDeg?.let(::r2),
            "raw" to angles(raw), "filtered" to angles(filt), "resting" to st?.resting, "tauMs" to st?.tauMs?.let(::r2),
            "figures" to linkedMapOf(
                "windowSamples" to rawWin.size, "rawSpreadDeg" to rs?.let(::r3), "filteredSpreadDeg" to fs?.let(::r3),
                "spreadRatio" to if (rs != null && fs != null && rs > 0) r3(fs / rs) else null,
                "stepTo90Ms" to stepTo90Ms?.let(::r2), "stepDeg" to stepDeg?.let(::r2),
            ),
        )
    }

    /** The simulated feed's period, ms (the sensor's asked rate). */
    const val SIM_PERIOD_MS = 20L

    /** The window the spread is read over, samples (a second at 50 Hz). */
    private const val WINDOW = 50

    /** A sample at least this far from the filtered attitude starts a step (a quarter turn's figure), degrees. */
    private const val STEP_DEG = 30.0
}
