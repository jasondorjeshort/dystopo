package io.github.jasondorjeshort.dystopo.app.nav

import android.util.Log
import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.readout.PressureSource
import io.github.jasondorjeshort.dystopo.app.routing.WaterText
import io.github.jasondorjeshort.dystopo.nav.NavPath
import io.github.jasondorjeshort.dystopo.nav.TurnAround
import io.github.jasondorjeshort.dystopo.routing.HotDay
import io.github.jasondorjeshort.dystopo.routing.ShadeAlong
import io.github.jasondorjeshort.dystopo.routing.ShadeAlongResult
import io.github.jasondorjeshort.dystopo.routing.ShadeSpoken
import io.github.jasondorjeshort.dystopo.routing.ShadeText
import io.github.jasondorjeshort.dystopo.routing.TravelMode
import java.util.concurrent.Executors

/**
 * T194: terrain shade along the line while navigating ([ShadeAlong], `shared`): worked out from the walker's progress and
 * the clock when the first fix places the walker and again every [RECOMPUTE_MS] (the ticker of [NavSession] asks), one at
 * a time on a thread of its own below normal priority, over the DEM pack under the walker
 * ([io.github.jasondorjeshort.dystopo.app.options.ShadeService.pyramidAt]); a recompute reuses the terrain read before when
 * its windows still hold every ray. Never stored.
 *
 * - **The card's line** ([cardLine], [ShadeText.cardLine]): "Terrain shade in 400 m for 10 min", "In terrain shade for 12
 *   min more", "No terrain shade ahead", whatever the setting; nothing while the sun is down or before the first answer.
 * - **The spoken line** ([speak], [NavSpeech.sayShade]): once per sunny stretch of more than [ShadeText.LONG_SUN_MIN]
 *   minutes after terrain shade, at the water line's lead distance ([WaterText.leadM]), under `shadeSpoken`: never, on hot
 *   days ([HotDay]: the chosen weather station's temperature, [PressureSource.stationTemperature], lowered to the walker's
 *   height on the line at 6.5 C a km, at 25 C or more; no temperature, or one over 3 hours old, is not a hot day) or always.
 *   A due line held back by the setting or a cool day is not remembered, so it can still be said within the lead.
 * - **Debug** (`NavRoutes`: `GET /nav` `shadeAlong`, `GET|POST /nav/shade`): a clock offset for the computation ([clockOffsetMs],
 *   so an emulator in the evening can walk at noon) and a synthetic temperature at the walker's height ([syntheticTempC]);
 *   both stay until cleared.
 *
 * Why 10 minutes: the sun moves 2.5 degrees in that time and the pace estimate drifts from the walker's real progress; a
 * recompute from where the walker is and the time it is keeps the next stretch within a few tens of metres, at the cost of
 * one ray per 25 m of the line left (under a second on the phone for a few kilometres).
 */
object NavShade {
    const val TAG = "DystopoShade"

    /** How often the stretches are worked out again while navigating, ms (see the class comment). */
    const val RECOMPUTE_MS = 10 * 60_000L

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "dystopo-shade-along").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 } }

    @Volatile private var generation = 0
    @Volatile private var path: NavPath? = null
    @Volatile private var timing: TurnAround? = null
    @Volatile private var onReady: (() -> Unit)? = null

    /** The latest answer of this navigation, or null before the first. */
    @Volatile var result: ShadeAlongResult? = null
        private set
    @Volatile private var computing = false
    @Volatile private var computedAtMs = 0L
    @Volatile private var computes = 0
    @Volatile private var lastTotalMs: Long? = null
    @Volatile private var lastError: String? = null
    @Volatile private var noData = false
    @Volatile private var saidUntilM = Double.NEGATIVE_INFINITY
    private val events = ArrayList<Map<String, Any?>>()
    private var heldBack: String? = null

    /** Debug: added to the wall clock for the computation's instant (`POST /nav/shade?at=`); 0 normally. */
    @Volatile var clockOffsetMs = 0L

    /** Debug: the air temperature at the walker's height instead of the station's (`POST /nav/shade?tempC=`). */
    @Volatile var syntheticTempC: Double? = null

    private val app get() = DystopoApp.instance

    /** A navigation of [p] starts ([t] its pace, the turn-around clock's); [ready] runs on this thread when an answer lands. */
    fun begin(p: NavPath, t: TurnAround, ready: () -> Unit) {
        synchronized(this) {
            generation++
            path = p
            timing = t
            onReady = ready
            result = null
            computing = false
            computedAtMs = 0L
            lastError = null
            noData = false
            saidUntilM = Double.NEGATIVE_INFINITY
            heldBack = null
        }
        synchronized(events) { events.clear() }
    }

    fun end() {
        synchronized(this) {
            generation++
            path = null
            timing = null
            onReady = null
            result = null
        }
    }

    /** The computation's instant: the wall clock plus the debug offset. */
    fun now(): Long = System.currentTimeMillis() + clockOffsetMs

    /**
     * Starts a computation from [progressM] when none has run in this navigation, the last is [RECOMPUTE_MS] old, or
     * [force]; never two at once. Non-blocking (the main thread's ticker calls it).
     */
    fun maybeCompute(progressM: Double, force: Boolean = false) {
        val p: NavPath
        val t: TurnAround
        val gen: Int
        synchronized(this) {
            p = path ?: return
            t = timing ?: return
            if (computing) return
            val wall = System.currentTimeMillis()
            if (!force && computedAtMs != 0L && wall - computedAtMs < RECOMPUTE_MS) return
            computing = true
            computedAtMs = wall
            gen = generation
        }
        val start = now()
        worker.execute {
            val t0 = System.nanoTime()
            try {
                val here = p.pointAt(progressM)
                val pyramid = runCatching { app.tiles.shade.pyramidAt(here.lat, here.lon) }.getOrNull()
                if (pyramid == null) {
                    noData = true
                    return@execute
                }
                val r = ShadeAlong.compute(
                    p, t, progressM, start, minOf(pyramid.maxZoom, ShadeAlong.MAX_ZOOM),
                    read = { w -> pyramid.window(w.zoom, w.x0, w.y0, w.width, w.height) }, tileSize = pyramid.tileSize, reuse = result?.terrain,
                )
                if (gen != generation) return@execute
                result = r
                noData = false
                lastError = null
                computes++
                lastTotalMs = (System.nanoTime() - t0) / 1_000_000
                Log.i(TAG, "terrain shade from ${progressM.toInt()} m: ${r.stretches.size} stretches, ${r.samples.size} rays in $lastTotalMs ms (read ${r.stats.readMs} ms, reused ${r.stats.terrainReused})")
                onReady?.invoke()
            } catch (e: Exception) {
                lastError = "${e.javaClass.simpleName}: ${e.message}"
                Log.w(TAG, "terrain shade along the line failed", e)
            } finally {
                synchronized(this) { if (gen == generation) computing = false }
            }
        }
    }

    /** Waits up to [ms] for a running computation (debug routes, off the main thread). */
    fun await(ms: Long) {
        val until = System.currentTimeMillis() + ms
        while (computing && System.currentTimeMillis() < until) Thread.sleep(50)
    }

    /** The card's line at [progressM] (null before the first answer, with the sun down, past the end). */
    fun cardLine(progressM: Double): String? = result?.let { ShadeText.cardLine(it, progressM, AppSettings.units) }

    /** The air temperature at the walker's height and where it came from. */
    class Temperature(val tempC: Double?, val source: String, val stationTempC: Double? = null, val stationEleM: Double? = null, val walkerEleM: Double? = null, val ageMin: Double? = null) {
        fun json(): Map<String, Any?> = linkedMapOf(
            "tempC" to tempC?.let { Math.round(it * 10) / 10.0 }, "source" to source, "stationTempC" to stationTempC,
            "stationEleM" to stationEleM, "walkerEleM" to walkerEleM?.let { Math.round(it * 10) / 10.0 }, "ageMin" to ageMin?.let { Math.round(it * 10) / 10.0 },
        )
    }

    /** At [progressM]: the synthetic temperature, else the station's lapsed to the line's height there; null when unknown or stale. */
    fun temperature(progressM: Double): Temperature {
        syntheticTempC?.let { return Temperature(it, "synthetic") }
        val st = PressureSource.stationTemperature() ?: return Temperature(null, "noStation")
        val age = (System.currentTimeMillis() - st.timeMs) / 60_000.0
        val ele = path?.pointAt(progressM)?.ele
        if (age * 60_000 > HotDay.MAX_AGE_MS) return Temperature(null, "stale", st.tempC, st.elevationM, ele, age)
        if (ele == null) return Temperature(null, "noHeight", st.tempC, st.elevationM, null, age)
        return Temperature(HotDay.atHeight(st.tempC, st.elevationM, ele), "station", st.tempC, st.elevationM, ele, age)
    }

    /** Says the due line once (navigation's worker thread), when the setting and the day allow it. */
    fun speak(progressM: Double, travel: TravelMode) {
        val r = result ?: return
        val setting = AppSettings.shadeSpoken
        if (setting == ShadeSpoken.OFF) return
        val k = ShadeText.dueSpoken(r, progressM, WaterText.leadM(travel), saidUntilM)
        if (k < 0) return
        val temp = temperature(progressM)
        if (!HotDay.allows(setting, temp.tempC)) {
            heldBack = "notHot" // not remembered: a warmer reading within the lead still says it
            return
        }
        heldBack = null
        saidUntilM = r.stretches[k].toM
        val ahead = (r.stretches[k].fromM - progressM).coerceAtLeast(0.0)
        val text = ShadeText.spoken(r, k, ahead, AppSettings.units)
        val said = NavSpeech.sayShade(text)
        synchronized(events) {
            events += linkedMapOf(
                "stretch" to k, "atMs" to System.currentTimeMillis(), "progressM" to r1(progressM), "aheadM" to r1(ahead),
                "fromM" to r1(r.stretches[k].fromM), "minutes" to r1(r.stretches[k].minutes), "tempC" to temp.tempC?.let(::r1), "tempSource" to temp.source,
                "text" to text, "speech" to (said?.let { if (it.muted) "muted" else it.engine } ?: "off"),
            )
        }
        Log.i(TAG, "said: $text")
    }

    /** `GET /nav` `shadeAlong` and `GET /nav/shade` at [progressM] (null before a fix). */
    fun json(progressM: Double?, travel: TravelMode, clock: (Long) -> String): Map<String, Any?> {
        val r = result
        val temp = progressM?.let { temperature(it) }
        val due = if (r != null && progressM != null) ShadeText.dueSpoken(r, progressM, WaterText.leadM(travel), saidUntilM) else -1
        return linkedMapOf(
            "state" to when {
                path == null -> "off"
                r != null -> "ready"
                lastError != null -> "error"
                noData -> "noData"
                computing -> "computing"
                else -> "waiting"
            },
            "spokenSetting" to AppSettings.shadeSpoken.key,
            "card" to progressM?.let { cardLine(it) },
            "recomputeMs" to RECOMPUTE_MS, "computedAtMs" to computedAtMs.takeIf { it != 0L },
            "nextAtMs" to computedAtMs.takeIf { it != 0L }?.plus(RECOMPUTE_MS), "computing" to computing, "computes" to computes,
            "lastTotalMs" to lastTotalMs, "error" to lastError, "clockOffsetMs" to clockOffsetMs, "now" to clock(now()),
            "hotDay" to linkedMapOf(
                "thresholdC" to HotDay.THRESHOLD_C, "lapseCPerKm" to HotDay.LAPSE_C_PER_KM, "maxAgeMin" to HotDay.MAX_AGE_MS / 60_000,
                "hot" to HotDay.hot(temp?.tempC), "allows" to HotDay.allows(AppSettings.shadeSpoken, temp?.tempC),
            ) + (temp?.json() ?: emptyMap()),
            "leadM" to WaterText.leadM(travel), "longSunMin" to ShadeText.LONG_SUN_MIN,
            "due" to due.takeIf { it >= 0 }, "heldBack" to heldBack, "saidUntilM" to saidUntilM.takeIf { it.isFinite() }?.let(::r1),
            "spoken" to synchronized(events) { ArrayList(events) },
            "result" to r?.json(clock),
        )
    }

    private fun r1(v: Double) = Math.round(v * 10) / 10.0
}
