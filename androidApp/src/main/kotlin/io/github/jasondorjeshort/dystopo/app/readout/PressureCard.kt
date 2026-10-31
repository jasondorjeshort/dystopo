package io.github.jasondorjeshort.dystopo.app.readout

import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.Foreground
import io.github.jasondorjeshort.dystopo.app.controls.ScaleBarLayout
import io.github.jasondorjeshort.dystopo.app.trips.Trips
import io.github.jasondorjeshort.dystopo.gpx.GpxFormat
import io.github.jasondorjeshort.dystopo.readout.FallAlert
import io.github.jasondorjeshort.dystopo.readout.HeightSource
import io.github.jasondorjeshort.dystopo.readout.PressureFixtures
import io.github.jasondorjeshort.dystopo.readout.PressureSeries
import io.github.jasondorjeshort.dystopo.readout.PressureText
import io.github.jasondorjeshort.dystopo.readout.PressureTrend
import io.github.jasondorjeshort.dystopo.readout.StationTemperature
import io.github.jasondorjeshort.dystopo.readout.TrendBand
import io.github.jasondorjeshort.dystopo.readout.TrendMinute
import io.github.jasondorjeshort.dystopo.readout.TrendResult
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import io.github.jasondorjeshort.dystopo.render.UnitRegions
import io.github.jasondorjeshort.dystopo.store.LocationFix
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * T178: the barometer's trend and the pressure-fall warning on the phone, around `shared`'s [PressureTrend] (the method,
 * its fixtures and its interval are documented there) and [FallAlert] (raise once, clear under half, no repeat within 3 h
 * unless the band worsens). One trend for the app, fed on a thread of its own:
 *
 * - **Sampling** only while a track records: `tracking/TrackingService` calls [setRecording], which asks the [Barometer]
 *   for [Barometer.Reason.TREND] (1 Hz, 4 s batches, the wake-up sensor when the phone has one; the screen may be off)
 *   while the barometer switch is on (`readoutBarometer`) and the phone has one. The barometer's samples arrive through
 *   [onSensorPressure], a replay's pressure column through [onReplayPressure] (while it flows the sensor is ignored, as
 *   the readout does), the recorder's accepted fixes through [onFix] (one line in `tracking/Tracking.onFix`): each fix's
 *   height is the active DEM pack's at the fix ([Trips.demSampler]; bare earth in the United States, a surface model
 *   elsewhere, `UnitRegions.isUnitedStates`), else the fix's own GNSS height.
 * - **Evaluation** each time a minute closes, with the chosen weather station's temperature when one is known
 *   ([PressureSource.stationTemperature]; the station is fetched only while the readout strip shows in the foreground, so a
 *   recording with the screen off uses the last report, which the trend ages).
 * - **The warning**: on a raise, the quiet card ([PressureCard], dismissable) and one spoken line through
 *   `nav/NavSpeech.sayPressure` under the spoken-directions setting; on a clear the card goes. The bar's arrow beside
 *   the elevation ([arrow]) once the 1-hour check has data.
 * - **Kept across restarts**: the minutes and the warning's state in [FILE] in the app's files (per device, never synced),
 *   rewritten as each minute closes; read once at the first use.
 * - **Debug** (`controls/ScaleBarRoutes`): `GET /readout` › `trend` is [json]; `POST /readout/trend` feeds a synthetic
 *   series ([feedSynthetic]: a named fixture or a CSV body), ending now; live input is ignored while it is loaded.
 */
object PressureWatch {
    const val TAG = "DystopoPressure"
    const val FILE = "pressure-trend.csv"

    /** The sensor's samples are ignored this long after a replay's pressure. */
    const val REPLAY_PRECEDENCE_MS = 10_000L

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "dystopo-pressure").apply { isDaemon = true } }
    private val main by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    // Worker thread only.
    private val trend = PressureTrend()
    private val alert = FallAlert()
    private var loaded = false
    private var lastMinutes = -1
    private var syntheticStation: StationTemperature? = null
    private var writtenAtMs = 0L
    private var writeFailures = 0

    @Volatile var recording = false
        private set
    @Volatile private var lastReplayAt = 0L
    @Volatile var result: TrendResult? = null
        private set
    /** The synthetic series loaded (`POST /readout/trend`), or null for live input. */
    @Volatile var synthetic: String? = null
        private set
    @Volatile private var spoken = ArrayList<Map<String, Any?>>()

    private val counts = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private fun bump(k: String) { counts.merge(k, 1L, Long::plus) }

    /** What the card shows (Compose state), or null. */
    class Card(val title: String, val body: String, val station: String?, val raisedAtMs: Long)

    var card by mutableStateOf<Card?>(null)
        private set
    @Volatile var dismissedAtMs: Long? = null
        private set
    @Volatile var cardBoundsPx: FloatArray? = null

    /** The bar's arrow beside the elevation, or null before the 1-hour check has data (or with the barometer switch off). */
    val arrow: String? get() = if (!AppSettings.readoutBarometer) null else result?.arrow
    /** The arrow's words ("falling slowly"), for the bar's description. */
    val arrowLabel: String? get() = if (!AppSettings.readoutBarometer) null else result?.label

    /** A track started or stopped recording (`TrackingService`); samples are taken only between the two. */
    fun setRecording(on: Boolean) {
        recording = on
        bump(if (on) "recordingStarts" else "recordingStops")
        post { load() }
        applySampling()
    }

    /** The barometer switch or the warning's setting changed (`SettingsRoutes`). */
    fun onSettings() {
        applySampling()
        post { evaluate("settings") }
    }

    private fun applySampling() {
        val want = recording && AppSettings.readoutBarometer && synthetic == null
        Barometer.want(Barometer.Reason.TREND, want)
    }

    /** The barometer listener (re)started (any reason): its first seconds are the sensor's spin-up. */
    fun onListenerStart(wallMs: Long) = post { trend.onListenerStart(wallMs) }

    /** A barometer sample ([Barometer]'s thread); kept only while recording, live and not under a replay's pressure. */
    fun onSensorPressure(wallMs: Long, hPa: Double) {
        if (!recording || synthetic != null) return
        if (SystemClock.elapsedRealtime() - lastReplayAt < REPLAY_PRECEDENCE_MS) { bump("sensorIgnoredReplay"); return }
        post { take { trend.onPressure(wallMs, hPa) } }
    }

    /** A replay's pressure column (`LiveReadout.onReplayPressure`), [timeMs] on the fixes' clock. */
    fun onReplayPressure(timeMs: Long, hPa: Double) {
        lastReplayAt = SystemClock.elapsedRealtime()
        if (!recording || synthetic != null) return
        bump("replayPressure")
        post { take { trend.onPressure(timeMs, hPa) } }
    }

    /** One fix the recorder accepted (`tracking/Tracking.onFix`, its thread). */
    fun onFix(fix: LocationFix) {
        if (!recording || synthetic != null) return
        post {
            val dem = runCatching { Trips.demSampler()?.elevation(fix.lat, fix.lon) }.getOrNull()?.takeIf { it.isFinite() }
            val (h, src) = when {
                dem != null -> dem to if (UnitRegions.isUnitedStates(fix.lat, fix.lon)) HeightSource.DEM_BARE_EARTH else HeightSource.DEM_SURFACE
                fix.ele != null -> fix.ele to HeightSource.GNSS
                else -> null to null
            }
            bump(if (src == null) "fixesNoHeight" else "fixes.${src.code}")
            take { trend.onFix(fix.time, h, src, fix.speedMps, fix.lat, fix.lon) }
        }
    }

    /** Runs [block] on the trend and evaluates when a minute closed. */
    private fun take(block: () -> Boolean) {
        load()
        if (!block()) return
        val n = trend.minutes().size
        if (n != lastMinutes) {
            lastMinutes = n
            evaluate("minute")
            persist()
        }
    }

    private fun station(): StationTemperature? = syntheticStation ?: if (synthetic != null) null else PressureSource.stationTemperature()

    /** Evaluates now (worker), updates the warning and acts on a raise or a clear. */
    private fun evaluate(why: String) {
        load()
        val now = trend.latest ?: return run { result = null }
        val r = trend.evaluate(now, AppSettings.pressureFallWarning, station())
        result = r
        bump("evaluations.$why")
        when (alert.update(r)) {
            FallAlert.Event.RAISE -> raise(r)
            FallAlert.Event.CLEAR -> clear()
            FallAlert.Event.NONE -> {}
        }
        LiveReadout.refresh()
    }

    private fun raise(r: TrendResult) {
        val t = r.quoted ?: return
        val inHg = AppSettings.units == ContourUnits.FEET
        val st = if (synthetic != null) null else PressureSource.stationTendency()?.first
        val c = Card(PressureText.TITLE, PressureText.body(t, inHg), st?.let { PressureText.station(it, inHg) }, System.currentTimeMillis())
        dismissedAtMs = null
        main.post { card = c }
        val line = runCatching { io.github.jasondorjeshort.dystopo.app.nav.NavSpeech.sayPressure(PressureText.SPOKEN) }.getOrNull()
        spoken = ArrayList(spoken.takeLast(19) + linkedMapOf("atMs" to System.currentTimeMillis(), "text" to PressureText.SPOKEN, "said" to (line != null), "muted" to (line?.muted ?: false)))
        bump("raises")
        Log.i(TAG, "pressure-fall warning: ${c.body}")
    }

    private fun clear() {
        main.post { card = null }
        bump("clears")
        Log.i(TAG, "pressure-fall warning cleared")
    }

    /** The card's Dismiss (main thread): the card goes; the warning stays raised (it is not repeated). */
    fun dismiss() {
        card = null
        dismissedAtMs = System.currentTimeMillis()
        bump("dismissals")
    }

    /**
     * `POST /readout/trend`: [fixture] (one of [PressureFixtures.NAMES]) or a CSV [csv] ([PressureSeries.parseCsv])
     * replaces the series, moved to end now; [clear] forgets it and returns to live input. Answers [json].
     */
    fun feedSynthetic(fixture: String?, csv: String?, clear: Boolean): Map<String, Any?> = call {
        load()
        if (clear) {
            synthetic = null
            syntheticStation = null
            trend.clear()
            lastMinutes = -1
            result = null
            evaluate("clear")
        } else {
            val now = System.currentTimeMillis()
            val series = when {
                fixture != null -> (PressureFixtures.of(fixture, now) ?: throw IllegalArgumentException("fixture must be one of ${PressureFixtures.NAMES.joinToString(", ")}")).series
                !csv.isNullOrBlank() -> PressureSeries.parseCsv(csv) { GpxFormat.parseTime(it) }.endingAt(now)
                else -> throw IllegalArgumentException("fixture=<name> or a CSV body is required")
            }
            synthetic = fixture ?: "csv"
            syntheticStation = series.station
            trend.clear()
            series.feed(trend)
            lastMinutes = trend.minutes().size
            evaluate("synthetic")
        }
        applySampling()
        persist()
        json()
    }

    /** Reads [FILE] once (worker). */
    private fun load() {
        if (loaded) return
        loaded = true
        val f = file() ?: return
        runCatching {
            if (!f.exists()) return
            val lines = f.readLines()
            val minutes = ArrayList<TrendMinute>()
            for (l in lines) {
                if (l.startsWith("# alert,")) {
                    val c = l.removePrefix("# alert,").split(',')
                    alert.restore(c.getOrNull(0) == "1", c.getOrNull(1)?.toLongOrNull(), c.getOrNull(2)?.let { b -> TrendBand.entries.firstOrNull { it.code == b } })
                } else if (!l.startsWith("#")) TrendMinute.decode(l)?.let { minutes += it }
            }
            val keepFrom = System.currentTimeMillis() - PressureTrend.KEEP_MS
            trend.restore(minutes.filter { it.startMs >= keepFrom })
            lastMinutes = trend.minutes().size
            bump("restoredMinutes")
            Log.i(TAG, "restored ${trend.minutes().size} minutes of pressure")
        }.onFailure { Log.w(TAG, "could not read $FILE", it) }
    }

    private fun file(): File? = runCatching { File(DystopoApp.instance.filesDir, FILE) }.getOrNull()

    /** Rewrites [FILE] (worker): the warning's state and the minutes. */
    private fun persist() {
        val f = file() ?: return
        runCatching {
            val text = buildString {
                append("# alert,${if (alert.active) 1 else 0},${alert.raisedAtMs ?: ""},${alert.raisedBand?.code ?: ""}\n")
                for (m in trend.minutes()) append(m.encode()).append('\n')
            }
            val tmp = File(f.parentFile, "$FILE.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            writtenAtMs = System.currentTimeMillis()
        }.onFailure { writeFailures++; Log.w(TAG, "could not write $FILE", it) }
    }

    /** `GET /readout` › `trend`. */
    fun json(): Map<String, Any?> = runCatching { call { jsonNow() } }.getOrElse { mapOf("error" to (it.message ?: it.javaClass.simpleName)) }

    private fun jsonNow(): Map<String, Any?> {
        load()
        val ms = trend.minutes()
        val last = ms.lastOrNull()
        val st = station()
        val c = card
        return linkedMapOf(
            "setting" to AppSettings.pressureFallWarning.let { linkedMapOf("code" to it.code, "label" to it.label, "thresholdHPa" to it.thresholdHPa) },
            "sampling" to linkedMapOf(
                "recording" to recording, "barometerSwitch" to AppSettings.readoutBarometer, "present" to Barometer.present,
                "trendWanted" to (recording && AppSettings.readoutBarometer && synthetic == null),
            ),
            "synthetic" to synthetic,
            "minutes" to ms.size,
            "firstMinuteMs" to ms.firstOrNull()?.startMs,
            "lastMinute" to last?.let { linkedMapOf("startMs" to it.startMs, "hPa" to it.hPa, "heightM" to it.heightM, "source" to it.source?.code, "speedMps" to it.speedMps, "n" to it.n) },
            "latestMs" to trend.latest,
            "arrow" to arrow, "label" to arrowLabel,
            "result" to result?.json(),
            "alert" to alert.json(),
            "card" to linkedMapOf("shown" to (c != null), "title" to c?.title, "body" to c?.body, "station" to c?.station, "raisedAtMs" to c?.raisedAtMs,
                "dismissedAtMs" to dismissedAtMs, "boundsPx" to cardBoundsPx?.map { Math.round(it) }),
            "spoken" to spoken.toList(),
            "station" to st?.let { linkedMapOf("station" to it.station, "tempC" to it.tempC, "elevationM" to it.elevationM, "ageS" to (System.currentTimeMillis() - it.timeMs) / 1000) },
            "stationTendency" to (if (synthetic != null) null else PressureSource.stationTendency()?.let { linkedMapOf("hPa" to it.first, "from" to it.second) }),
            "persisted" to linkedMapOf("file" to FILE, "writtenAtMs" to writtenAtMs.takeIf { it > 0 }, "writeFailures" to writeFailures),
            "counters" to (trend.counters() + counts).toSortedMap(),
        )
    }

    private fun post(block: () -> Unit) {
        worker.execute {
            try {
                block()
            } catch (e: Exception) {
                Log.w(TAG, "pressure task failed", e)
            }
        }
    }

    private fun <T> call(block: () -> T): T = worker.submit(Callable(block)).get(10, TimeUnit.SECONDS)
}

/**
 * T178: the pressure-fall warning's card: one quiet card under the search box (and under the navigation card while
 * navigating), the title, what was measured with the hedge, the nearby station's own tendency when known, and Dismiss.
 * Nothing when no warning is raised or after Dismiss.
 */
@Composable
fun PressureCard() {
    val c = PressureWatch.card ?: return
    var topDp by remember { mutableStateOf(maxOf(ScaleBarLayout.topLeftBottomDp, ScaleBarLayout.navCardBottomDp)) }
    LaunchedEffect(c) {
        while (true) {
            topDp = maxOf(ScaleBarLayout.topLeftBottomDp, ScaleBarLayout.navCardBottomDp)
            Foreground.delay(1000)
        }
    }
    Box(Modifier.fillMaxWidth().padding(top = (if (topDp > 0f) topDp else 72f).dp + 8.dp), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .padding(horizontal = 12.dp)
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .onGloballyPositioned { l -> l.boundsInRoot().let { b -> PressureWatch.cardBoundsPx = floatArrayOf(b.left, b.top, b.right, b.bottom) } }
                .background(Plate, RoundedCornerShape(8.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            BasicText(c.title, style = TextStyle(color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold))
            BasicText(c.body, style = TextStyle(color = Ink, fontSize = 14.sp))
            c.station?.let { BasicText(it, style = TextStyle(color = Muted, fontSize = 13.sp)) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                BasicText(
                    "Dismiss",
                    Modifier
                        .clickable(role = Role.Button) { PressureWatch.dismiss() }
                        .semantics { contentDescription = "Dismiss the pressure warning" }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    style = TextStyle(color = Accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                )
            }
        }
    }
}

private val Ink = Color(0xFF2B2420)
private val Muted = Color(0xFF6B5A4A)
private val Accent = Color(0xFF8A4B14)
private val Plate = Color(0xF2F6F1E4)
