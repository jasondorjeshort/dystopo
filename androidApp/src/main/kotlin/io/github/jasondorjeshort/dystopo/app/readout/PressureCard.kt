package io.github.jasondorjeshort.dystopo.app.readout

import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.app.DystopoApp
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
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.PropertyFactory
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
 * - T196, **the way down**: on a raise [WayDownWatch] asks `shared`'s `nav/WayDown` whether the walker is on a ridge
 *   and, if so, how long the walk to lower ground is along the way back and the route ahead; the card adds the line and
 *   "Show the way down", and the spoken clause follows the warning's line (`NavSpeech.sayWayDown`). Recomputed as each
 *   minute closes while the warning is up (the card's line follows the walker; nothing more is spoken).
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
    /** The start of the last closed minute evaluated (the count stays put once the oldest minutes are dropped). */
    private var lastMinutes = -1L
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

    /**
     * T204 (T187's note): the card's bottom edge (window pixels) as Compose state, null with no card: the readout bar
     * keeps below it (the scale bar's layout takes it as the bar's ceiling, so the bar leaves entries out, or hides
     * with `noRoomBelowWarningCard`, rather than lying under the warning).
     */
    var cardBottomPx by mutableStateOf<Float?>(null)
        private set

    internal fun setCardBottom(px: Float?) { if (cardBottomPx != px) cardBottomPx = px }

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
        val n = trend.minutes().lastOrNull()?.startMs ?: -1L
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
            FallAlert.Event.NONE -> if (why == "minute" && alert.active && card != null) WayDownWatch.compute("minute") // T196
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
        // T196: on a ridge, the way down: the card's line and the spoken clause queued right after the warning's line.
        val clause = WayDownWatch.compute("raise")?.let { io.github.jasondorjeshort.dystopo.nav.WayDownText.spoken(it) }
        WayDownWatch.spokenText = clause
        if (clause != null) {
            val said = runCatching { io.github.jasondorjeshort.dystopo.app.nav.NavSpeech.sayWayDown(clause) }.getOrNull()
            spoken = ArrayList(spoken.takeLast(19) + linkedMapOf("atMs" to System.currentTimeMillis(), "text" to clause, "said" to (said != null), "muted" to (said?.muted ?: false), "wayDown" to true))
        }
        bump("raises")
        Log.i(TAG, "pressure-fall warning: ${c.body}")
    }

    private fun clear() {
        main.post { card = null }
        WayDownWatch.clear() // T196
        bump("clears")
        Log.i(TAG, "pressure-fall warning cleared")
    }

    /** The card's Dismiss (main thread): the card goes; the warning stays raised (it is not repeated). */
    fun dismiss() {
        card = null
        WayDownWatch.clear() // T196: the drawn stretch goes with the card
        dismissedAtMs = System.currentTimeMillis()
        bump("dismissals")
    }

    /**
     * `POST /readout/trend`: [fixture] (one of [PressureFixtures.NAMES]) or a CSV [csv] ([PressureSeries.parseCsv])
     * replaces the series, moved to end now; [clear] forgets it and the warning's memory and returns to live input.
     * Answers [json].
     */
    fun feedSynthetic(fixture: String?, csv: String?, clear: Boolean): Map<String, Any?> = call {
        load()
        if (clear) {
            synthetic = null
            syntheticStation = null
            trend.clear()
            alert.restore(false, null, null) // the warning's memory too, so the next series starts clean
            main.post { card = null }
            WayDownWatch.clear() // T196
            lastMinutes = -1L
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
            lastMinutes = trend.minutes().lastOrNull()?.startMs ?: -1L
            evaluate("synthetic")
        }
        applySampling()
        persist()
        jsonNow() // on the worker already: json() would wait on itself
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
            lastMinutes = trend.minutes().lastOrNull()?.startMs ?: -1L
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

    /** T196: `POST /readout/trend?wayDown=1`: the way down computed again now (no new warning, nothing spoken); answers [json]. */
    fun recomputeWayDown(): Map<String, Any?> = call {
        load()
        WayDownWatch.compute("debug")
        jsonNow()
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
                "dismissedAtMs" to dismissedAtMs, "boundsPx" to cardBoundsPx?.takeIf { c != null }?.map { Math.round(it) }, // T192: none once the card is gone
                "layout" to WarningCardLayout.json().takeIf { c != null }), // T212: its room, collapse and scroll
            "spoken" to spoken.toList(),
            "wayDown" to WayDownWatch.json(), // T196
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
 * T178: the pressure-fall warning's card: one quiet card under the chrome at the top of the map (the search box, the
 * chips at the right, the navigation card while navigating), the title, what was measured with the hedge, the nearby
 * station's own tendency when known, and Dismiss. T204: the readout bar keeps below it ([PressureWatch.cardBottomPx]),
 * leaving entries out as it must, so the two never overlap (T187's note: at font scale 2.0 the card reached under it).
 * Nothing when no warning is raised or after Dismiss.
 *
 * T212 (the research `cards-at-large-font-scales`: a collapsed summary with an expand tap is the answer to a card too
 * tall for its room): the title (the severity) and the advice line ("Weather may turn worse; check your plans.") are
 * always shown, the actions (Show the way down, Dismiss) always in view under them. The detail (what was measured, the
 * station's tendency, the way-down lines) shows in place when the whole card fits its room; when it does not, the card
 * starts collapsed with "Show details", which expands the detail in place, scrolling between the advice and the actions
 * ("Hide details" folds it again). The room is measured ([WarningCardLayout]): from under the top chrome down to the
 * scale bar's line on the left (the bar and the readout strip's elevation, `BottomPanels.mapRowDp`), and below the
 * map's compass when it reaches into that band ([CompassView.boundsPx]), so the card never covers the scale bar or the
 * compass at any font or display size.
 */
@Composable
fun PressureCard() {
    // T192: the card's bounds leave with the card (the recovery fixture's clear, Dismiss), so `GET /readout`
    // `trend.card.boundsPx` never reports a card that is gone.
    val c = PressureWatch.card
    if (c == null) {
        androidx.compose.runtime.SideEffect { PressureWatch.cardBoundsPx = null; PressureWatch.setCardBottom(null); WarningCardLayout.gone() }
        return
    }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { PressureWatch.cardBoundsPx = null; PressureWatch.setCardBottom(null); WarningCardLayout.gone() } }
    val density = androidx.compose.ui.platform.LocalDensity.current
    // Under everything that floats across the top (the search column, the online indicator, the chips at the right and
    // the navigation card; Compose state, so the card follows them).
    val topPx = io.github.jasondorjeshort.dystopo.app.MapInsets.topChromeBottomPx(density.density)
    val chromeDp = if (topPx > 0) topPx / density.density else 72f
    // T212: the compass (MapLibre's own, top right while the map is turned) when it reaches below the chrome: the card
    // starts below it. Polled with the card's other inputs: the view is not Compose state.
    var compass by androidx.compose.runtime.remember { mutableStateOf(CompassView.boundsPx()) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            io.github.jasondorjeshort.dystopo.app.Foreground.delay(500)
            val now = CompassView.boundsPx()
            if (!now.contentEquals(compass)) compass = now
        }
    }
    var expandedByUser by androidx.compose.runtime.remember(c.raisedAtMs) { mutableStateOf<Boolean?>(null) }
    var detailPx by androidx.compose.runtime.remember(c.raisedAtMs) { mutableStateOf(-1) }
    val scroll = androidx.compose.foundation.rememberScrollState()
    androidx.compose.foundation.layout.BoxWithConstraints(androidx.compose.ui.Modifier.fillMaxSize()) {
        val panels = io.github.jasondorjeshort.dystopo.app.routing.BottomPanels
        val shownPanels = if (panels.trackPanelsTop > panels.trackPanelsBottom) panels.trackPanelsTop else 0.dp
        val floor = maxOf(io.github.jasondorjeshort.dystopo.app.about.AttributionInsets.bottom, panels.routeCardTop, shownPanels, io.github.jasondorjeshort.dystopo.app.MapInsets.bottomCardsTop)
        val bottomPad = if (floor > 0.dp) floor + 6.dp else with(density) { androidx.compose.foundation.layout.WindowInsets.navigationBars.getBottom(this).toDp() } + 32.dp
        // The scale bar's line above the bottom chrome: mapRowDp less its top gap (6 dp), which the bottom pad already is.
        // T212 (the verifier's note): with a planning card up the two cards share the band, the warning above: it leaves
        // the planning card its least while warned (one scrolling line) at the bottom and the scale bar's plate between.
        val planningUp = panels.routeCardTop > 0.dp
        val floor0 = maxOf(io.github.jasondorjeshort.dystopo.app.about.AttributionInsets.bottom, shownPanels, io.github.jasondorjeshort.dystopo.app.MapInsets.bottomCardsTop)
        val bottomPad0 = if (floor0 > 0.dp) floor0 + 6.dp else with(density) { androidx.compose.foundation.layout.WindowInsets.navigationBars.getBottom(this).toDp() } + 28.dp
        val bottomLimit = if (planningUp) maxHeight - bottomPad0 - panels.warnedLeastDp(density) - panels.barRowDp(density)
        else maxHeight - bottomPad - (panels.mapRowDp(density) - 6.dp)
        val sideDp = 12.dp
        val widthDp = minOf(maxWidth - sideDp * 2, 420.dp)
        val leftDp = (maxWidth - widthDp) / 2
        val chromeTop = chromeDp.dp + 8.dp
        val compassDp = compass?.let { b -> FloatArray(4) { b[it] / density.density } }
        val bands = io.github.jasondorjeshort.dystopo.app.routing.CardBands
        val top = bands.warningTop(chromeTop.value, compassDp, leftDp.value, (leftDp + widthDp).value, bottomLimit.value).dp
        val compassMoves = top > chromeTop
        val room = (bottomLimit - top).coerceAtLeast(0.dp)
        val padV = 10.dp
        val layout = WarningCardLayout.layout
        // The whole card fits when its header, its detail as last measured whole and its actions do.
        val fits = with(density) {
            bands.warningFits(if (detailPx < 0) null else detailPx.toDp().value, layout.headerPx.toDp().value, layout.footerPx.toDp().value, (4.dp * 2 + padV * 2).value, room.value)
        } == true
        val expanded = expandedByUser ?: (detailPx < 0 || fits)
        val collapsible = !fits || expandedByUser != null
        androidx.compose.runtime.SideEffect {
            WarningCardLayout.roomDp = room.value; WarningCardLayout.topDp = top.value; WarningCardLayout.bottomLimitDp = bottomLimit.value
            WarningCardLayout.compassMoved = compassMoves; WarningCardLayout.expanded = expanded; WarningCardLayout.fits = fits
            WarningCardLayout.collapsible = collapsible; WarningCardLayout.detailDp = if (detailPx < 0) null else detailPx / density.density
        }
        // The title, the advice and the actions always show: with less room than they take (a planning card up at font
        // scale 2.0) the card keeps their height and reaches over what is below rather than cutting them (the warning first).
        val summary = with(density) { (layout.headerPx + layout.footerPx).toDp() } + 4.dp * 2
        val cap = maxOf(room - padV * 2, summary)
        androidx.compose.runtime.SideEffect { WarningCardLayout.overRoom = cap > room - padV * 2 }
        io.github.jasondorjeshort.dystopo.app.routing.CappedCard(
            cap = cap, layout = layout, scroll = scroll, description = WARNING_DETAILS, paper = PaperOpaque, spacing = 4.dp, pinned = true,
            modifier = Modifier
                .padding(top = top, start = leftDp)
                .width(widthDp)
                .onGloballyPositioned { l -> l.boundsInRoot().let { b -> PressureWatch.cardBoundsPx = floatArrayOf(b.left, b.top, b.right, b.bottom); PressureWatch.setCardBottom(b.bottom) } }
                .background(Plate, RoundedCornerShape(8.dp))
                .padding(horizontal = 14.dp, vertical = padV),
            header = {
                BasicText(c.title, style = TextStyle(color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold))
                BasicText(PressureText.ADVICE, style = TextStyle(color = Ink, fontSize = 14.sp))
            },
            body = {
                if (expanded) {
                    Column(
                        Modifier.onSizeChanged { if (it.height != detailPx) detailPx = it.height },
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        BasicText(measuredLine(c.body), style = TextStyle(color = Ink, fontSize = 14.sp))
                        c.station?.let { BasicText(it, style = TextStyle(color = Muted, fontSize = 13.sp)) }
                        WayDownLines() // T196: on a ridge, the walk to lower ground
                    }
                } else androidx.compose.runtime.SideEffect { WayDownWatch.linesBoundsPx = null }
            },
            footer = {
                androidx.compose.foundation.layout.FlowRow(
                    Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (collapsible) {
                        val label = if (expanded) HIDE_DETAILS else SHOW_DETAILS
                        BasicText(
                            label,
                            Modifier
                                .onGloballyPositioned { l -> WarningCardLayout.toggleBoundsPx = l.boundsInRoot().let { b -> floatArrayOf(b.left, b.top, b.right, b.bottom) } }
                                .clickable(role = Role.Button) { expandedByUser = !expanded }
                                .semantics { contentDescription = label }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            style = TextStyle(color = Accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                        )
                    } else androidx.compose.runtime.SideEffect { WarningCardLayout.toggleBoundsPx = null }
                    WayDownAction() // T196
                    BasicText(
                        "Dismiss",
                        Modifier
                            .clickable(role = Role.Button) { PressureWatch.dismiss() }
                            .semantics { contentDescription = "Dismiss the pressure warning" }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        style = TextStyle(color = Accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                    )
                }
            },
        )
    }
}

/** T212: the card's body less its closing advice, which the card shows on its own line under the title. */
internal fun measuredLine(body: String): String = body.removeSuffix(" " + PressureText.ADVICE).removeSuffix(PressureText.ADVICE).trim()

/** T212: the warning card's expand and fold actions, and its scrolling detail's content description. */
internal const val SHOW_DETAILS = "Show details"
internal const val HIDE_DETAILS = "Hide details"
internal const val WARNING_DETAILS = "Pressure warning details"

/**
 * T212: the warning card as last laid out, for `GET /readout` `trend.card.layout`: its room (dp, from [topDp] under the
 * chrome or the compass down to [bottomLimitDp], the scale bar's line), whether the compass moved it down, whether the
 * whole card [fits] (else it starts collapsed), [expanded], the detail's whole height, the toggle's bounds (px) and the
 * header, scrolling detail and actions ([layout]).
 */
internal object WarningCardLayout {
    @Volatile var roomDp = 0f
    @Volatile var topDp = 0f
    @Volatile var bottomLimitDp = 0f
    @Volatile var compassMoved = false
    @Volatile var fits = true
    @Volatile var expanded = true
    @Volatile var collapsible = false
    @Volatile var overRoom = false
    @Volatile var detailDp: Float? = null
    @Volatile var toggleBoundsPx: FloatArray? = null
    val layout = io.github.jasondorjeshort.dystopo.app.routing.CardLayout()

    fun gone() { toggleBoundsPx = null }

    fun json(): Map<String, Any?> = linkedMapOf(
        "roomDp" to roomDp, "topDp" to topDp, "bottomLimitDp" to bottomLimitDp, "compassMoved" to compassMoved,
        "fits" to fits, "expanded" to expanded, "collapsible" to collapsible, "detailDp" to detailDp, "overRoom" to overRoom,
        "toggle" to if (collapsible) (if (expanded) HIDE_DETAILS else SHOW_DETAILS) else null,
        "toggleBoundsPx" to toggleBoundsPx?.takeIf { collapsible }?.map { Math.round(it) },
        "compassPx" to CompassView.json(),
    ) + layout.json()
}

/**
 * T212: MapLibre's compass on the map (its own view, top right, shown while the map is turned from north): its bounds in
 * window pixels while it is drawn, else null. Read by the warning card's placement and `GET /camera` › `heading.compass`
 * `boundsPx` (`controls/Heading.compassJson`). Main thread; null off it.
 */
object CompassView {
    fun boundsPx(): FloatArray? {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) return null
        val map = runCatching { DystopoApp.instance.map.mapView }.getOrNull() ?: return null
        val v = find(map) ?: return null
        if (!v.isShown || v.alpha <= 0f || v.width == 0) return null
        val at = IntArray(2)
        v.getLocationInWindow(at)
        return floatArrayOf(at[0].toFloat(), at[1].toFloat(), (at[0] + v.width).toFloat(), (at[1] + v.height).toFloat())
    }

    /** The bounds from any thread (on the main thread through the map's handler, at most 2 s). */
    fun json(): List<Int>? = runCatching { DystopoApp.instance.map.onMain { boundsPx() } }.getOrNull()?.map { Math.round(it) }

    private fun find(g: android.view.ViewGroup): android.view.View? {
        for (i in 0 until g.childCount) {
            val v = g.getChildAt(i)
            if (v is org.maplibre.android.maps.widgets.CompassView) return v
            if (v is android.view.ViewGroup) find(v)?.let { return it }
        }
        return null
    }
}

private val PaperOpaque = Color(0xFFF6F1E4)

private val Ink = Color(0xFF2B2420)
private val Muted = Color(0xFF6B5A4A)
private val Accent = Color(0xFF8A4B14)
private val Plate = Color(0xF2F6F1E4)

// ---- T196: the way down after the warning (shared `nav/WayDown`, the survey's words in `WayDownText`) ----

/**
 * T196: after the pressure-fall warning, the quickest way off exposed ground. On a raise (and as each minute closes
 * while the warning is up) [compute] reads the walker's last fix ([LiveReadout.lastFix]), the way back (the
 * recording's retrace line from `nav/WayBackClock.line`, else the navigated route behind the walker) and, while
 * navigating, the route ahead, and runs `shared`'s `nav/WayDown` over the DEM pack under the fix read at z14 (T195's
 * ridge rule wants that zoom). On a ridge the card adds "25 min down to lower ground along the way back" (the quicker
 * direction first, the other as "or 40 min along the route") and "Show the way down", which draws the quicker stretch
 * on the map ([setDrawn]: a dashed line from the walker to the lower ground and a dot at its end; the camera fits it
 * when it is not all in view). Below a ridge, without a fix or without a DEM nothing is added and the warning stays as
 * it is. No setting: it rides on `pressureFallWarning` (ADR 0044). `GET /readout` and `POST /readout/trend` report
 * [json] as `wayDown`; `POST /readout/trend?wayDown=1` recomputes it and `?wayDownShow=1|0` presses the action.
 */
object WayDownWatch {
    const val TAG = "DystopoWayDown"
    const val SOURCE = "dystopo-way-down"
    const val CASING = "dystopo-way-down-casing"
    const val LINE = "dystopo-way-down-line"
    const val END = "dystopo-way-down-end"

    /** What the card shows: its [lines] (empty below a ridge) and whether the stretch is drawn. */
    data class Ui(val lines: List<String>, val drawn: Boolean)

    var ui by mutableStateOf<Ui?>(null)
        private set

    @Volatile var result: io.github.jasondorjeshort.dystopo.nav.WayDownResult? = null
        private set
    @Volatile private var reason: String? = null
    @Volatile private var why: String? = null
    @Volatile private var inputs: Map<String, Any?>? = null
    @Volatile private var computedAtMs = 0L
    @Volatile private var computeMs = 0L
    @Volatile private var computes = 0
    @Volatile var drawn = false
        private set
    @Volatile private var fitted = 0
    /** The clause said with the last warning (null when there was none). */
    @Volatile var spokenText: String? = null
    @Volatile var actionBoundsPx: FloatArray? = null
    @Volatile var linesBoundsPx: FloatArray? = null

    private val main by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    /** One sampler per DEM pack, read under a lock (its tile cache is not thread-safe). */
    private val samplers = java.util.WeakHashMap<io.github.jasondorjeshort.dystopo.app.Pack, io.github.jasondorjeshort.dystopo.routing.ElevationSampler>()

    private fun sampler(lat: Double, lon: Double): io.github.jasondorjeshort.dystopo.routing.ElevationSampler? {
        val packs = DystopoApp.instance.tiles.packs
        val dem = packs.demPacks.firstOrNull { d -> d.header.let { h -> lon in h.minLon..h.maxLon && lat in h.minLat..h.maxLat } } ?: packs.dem ?: return null
        return synchronized(samplers) {
            samplers.getOrPut(dem) {
                val pyramid = io.github.jasondorjeshort.dystopo.tiles.DemPyramid(dem.header.minZoom, dem.header.maxZoom, io.github.jasondorjeshort.dystopo.app.TileService.demDecoder(), cacheTiles = 24) { z, x, y -> dem.tile(z, x, y) }
                val s = io.github.jasondorjeshort.dystopo.routing.DemElevation(pyramid, zoom = 14)
                val lock = Any()
                io.github.jasondorjeshort.dystopo.routing.ElevationSampler { la, lo -> synchronized(lock) { s.elevation(la, lo) } }
            }
        }
    }

    /** Computes the way down now (on the pressure worker) and updates the card; null without a fix or a DEM. */
    fun compute(because: String): io.github.jasondorjeshort.dystopo.nav.WayDownResult? {
        val t0 = SystemClock.elapsedRealtime()
        why = because
        computes++
        computedAtMs = System.currentTimeMillis()
        val r = runCatching { find() }.onFailure { Log.w(TAG, "way down failed", it); reason = "error: ${it.message}" }.getOrNull()
        computeMs = SystemClock.elapsedRealtime() - t0
        result = r
        val lines = r?.let { io.github.jasondorjeshort.dystopo.nav.WayDownText.cardLines(it) }.orEmpty()
        main.post {
            if (lines.isEmpty() && drawn) setDrawn(false)
            ui = Ui(lines, drawn && lines.isNotEmpty())
            if (drawn) setDrawn(true) // the stretch follows the walker
        }
        r?.let { Log.i(TAG, "way down ($because): exposed ${it.exposed}, TPI ${it.tpiM}, ${lines.joinToString(" / ")} in $computeMs ms") }
        return r
    }

    private fun find(): io.github.jasondorjeshort.dystopo.nav.WayDownResult? {
        val fix = LiveReadout.lastFix ?: return null.also { reason = "noFix"; inputs = null }
        val dem = sampler(fix.lat, fix.lon) ?: return null.also { reason = "noDem"; inputs = null }
        val nav = io.github.jasondorjeshort.dystopo.app.nav.NavSession.navigator
        val along = nav?.state?.progress
        val recorded = io.github.jasondorjeshort.dystopo.app.nav.WayBackClock.line()
        val wd = io.github.jasondorjeshort.dystopo.nav.WayDown
        val (back, backSource) = when {
            recorded != null -> wd.behind(recorded.first, recorded.second) to "recording"
            nav != null && along != null -> wd.behind(nav.path.points, along) to "route"
            else -> null to null
        }
        val ahead = if (nav != null && along != null) wd.onward(nav.path.points, along) else null
        val pace = io.github.jasondorjeshort.dystopo.nav.TurnPace.of(io.github.jasondorjeshort.dystopo.routing.CostModel(nav?.options?.mode ?: io.github.jasondorjeshort.dystopo.routing.TravelMode.HIKE))
        inputs = linkedMapOf(
            "fix" to linkedMapOf("lat" to fix.lat, "lon" to fix.lon, "time" to fix.time),
            "back" to backSource, "backPoints" to back?.size, "navigating" to (nav != null), "alongM" to along?.let { Math.round(it * 10) / 10.0 },
            "aheadPoints" to ahead?.size,
        )
        reason = null
        return wd.find(dem, fix.lat, fix.lon, back, ahead, pace)
    }

    /** The warning cleared or was dismissed (any thread): the lines and the drawn stretch go. */
    fun clear() {
        result = null
        reason = "cleared"
        main.post {
            setDrawn(false)
            ui = null
        }
    }

    /** "Show the way down" / "Hide the way down" (main thread): draws or removes the quicker stretch. */
    fun setDrawn(on: Boolean) {
        val style = DystopoApp.instance.map.map?.style
        val r = result
        val q = r?.quickest?.takeIf { r.exposed }
        if (on && q != null && style != null) {
            val line = org.maplibre.geojson.LineString.fromLngLats(q.stretch.map { org.maplibre.geojson.Point.fromLngLat(it.lon, it.lat) })
            val end = org.maplibre.geojson.Point.fromLngLat(q.lon, q.lat)
            source(style).setGeoJson(
                org.maplibre.geojson.FeatureCollection.fromFeatures(listOf(org.maplibre.geojson.Feature.fromGeometry(line), org.maplibre.geojson.Feature.fromGeometry(end))),
            )
            val first = !drawn
            drawn = true
            if (first) fit(q.stretch)
        } else {
            style?.getSourceAs<org.maplibre.android.style.sources.GeoJsonSource>(SOURCE)?.setGeoJson(org.maplibre.geojson.FeatureCollection.fromFeatures(emptyList()))
            drawn = false
        }
        ui = ui?.copy(drawn = drawn)
    }

    private fun source(style: org.maplibre.android.maps.Style): org.maplibre.android.style.sources.GeoJsonSource {
        style.getSourceAs<org.maplibre.android.style.sources.GeoJsonSource>(SOURCE)?.let { return it }
        val src = org.maplibre.android.style.sources.GeoJsonSource(SOURCE)
        style.addSource(src)
        val isLine = Expression.eq(Expression.geometryType(), Expression.literal("LineString"))
        val isPoint = Expression.eq(Expression.geometryType(), Expression.literal("Point"))
        val round = org.maplibre.android.style.layers.Property.LINE_JOIN_ROUND
        style.addLayer(
            org.maplibre.android.style.layers.LineLayer(CASING, SOURCE).withFilter(isLine).withProperties(
                PropertyFactory.lineColor("#ffffff"), PropertyFactory.lineWidth(9f), PropertyFactory.lineOpacity(0.9f),
                PropertyFactory.lineCap(org.maplibre.android.style.layers.Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(round),
            ),
        )
        style.addLayer(
            org.maplibre.android.style.layers.LineLayer(LINE, SOURCE).withFilter(isLine).withProperties(
                PropertyFactory.lineColor(WAY_DOWN_INK), PropertyFactory.lineWidth(5f), PropertyFactory.lineDasharray(arrayOf(1.5f, 1f)), PropertyFactory.lineJoin(round),
            ),
        )
        style.addLayer(
            org.maplibre.android.style.layers.CircleLayer(END, SOURCE).withFilter(isPoint).withProperties(
                PropertyFactory.circleRadius(7f), PropertyFactory.circleColor(WAY_DOWN_INK), PropertyFactory.circleStrokeColor("#ffffff"), PropertyFactory.circleStrokeWidth(2.5f),
            ),
        )
        return src
    }

    /** Moves the camera to the stretch when it is not all in view below the warning's card (the card hides the map under it). */
    private fun fit(points: List<io.github.jasondorjeshort.dystopo.nav.NavPoint>) {
        val m = DystopoApp.instance.map.map ?: return
        val view = DystopoApp.instance.map.mapView ?: return
        val density = DystopoApp.instance.resources.displayMetrics.density
        val top = ((PressureWatch.cardBoundsPx?.get(3) ?: 0f) + 32 * density).toInt()
        val side = (48 * density).toInt()
        val bottom = view.height - 140 * density
        // In view means below the warning's card (it covers the map under it) and above the bottom bars.
        val inView = runCatching {
            points.all { p -> m.projection.toScreenLocation(org.maplibre.android.geometry.LatLng(p.lat, p.lon)).let { it.x in 0f..view.width.toFloat() && it.y in top.toFloat()..bottom } }
        }.getOrDefault(false)
        if (inView) return
        val b = org.maplibre.android.geometry.LatLngBounds.Builder()
        points.forEach { b.include(org.maplibre.android.geometry.LatLng(it.lat, it.lon)) }
        runCatching {
            m.easeCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLngBounds(b.build(), side, top, side, (140 * density).toInt()), 600)
            fitted++
        }.onFailure { Log.w(TAG, "could not fit the stretch", it) }
    }

    /** `GET /readout` › `wayDown` and `trend` › `wayDown`. */
    fun json(): Map<String, Any?> {
        val r = result
        val u = ui
        val shown = u?.lines?.isNotEmpty() == true
        val style = runCatching { DystopoApp.instance.map.map?.style }.getOrNull()
        val text = io.github.jasondorjeshort.dystopo.nav.WayDownText
        return linkedMapOf(
            "state" to when {
                r == null -> reason ?: "notComputed"
                r.noData -> "noData"
                !r.exposed -> "belowRidge"
                r.exits.isEmpty() -> "noWayDownWithinWindow"
                else -> "exposed"
            },
            "why" to why, "computes" to computes, "computedAtMs" to computedAtMs.takeIf { it > 0 }, "computeMs" to computeMs,
            "inputs" to inputs,
            "result" to r?.json(),
            "card" to linkedMapOf(
                "lines" to u?.lines, "action" to if (shown) (if (u!!.drawn) text.ACTION_HIDE else text.ACTION) else null,
                "linesBoundsPx" to linesBoundsPx?.takeIf { shown }?.map { Math.round(it) },
                "actionBoundsPx" to actionBoundsPx?.takeIf { shown }?.map { Math.round(it) },
            ),
            "drawn" to drawn, "layerInstalled" to (runCatching { style?.getLayer(LINE) != null }.getOrNull() ?: false),
            "stretchPoints" to r?.quickest?.stretch?.size, "cameraFits" to fitted,
            "spoken" to spokenText,
            "help" to text.HELP,
        )
    }
}

/** T196: the card's way-down lines, the quicker direction first, with the help icon (nothing below a ridge). */
@Composable
private fun WayDownLines() {
    val u = WayDownWatch.ui
    if (u == null || u.lines.isEmpty()) {
        androidx.compose.runtime.SideEffect { WayDownWatch.linesBoundsPx = null }
        return
    }
    Row(
        Modifier.fillMaxWidth().onGloballyPositioned { l -> l.boundsInRoot().let { b -> WayDownWatch.linesBoundsPx = floatArrayOf(b.left, b.top, b.right, b.bottom) } },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            u.lines.forEachIndexed { i, l ->
                BasicText(l, style = if (i == 0) TextStyle(color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium) else TextStyle(color = Muted, fontSize = 13.sp))
            }
        }
        io.github.jasondorjeshort.dystopo.app.help.HelpIcon("readout.wayDown")
    }
}

/** T196: "Show the way down" / "Hide the way down" beside Dismiss (nothing below a ridge). */
@Composable
private fun androidx.compose.foundation.layout.RowScope.WayDownAction() {
    val u = WayDownWatch.ui
    if (u == null || u.lines.isEmpty()) {
        androidx.compose.runtime.SideEffect { WayDownWatch.actionBoundsPx = null }
        return
    }
    val text = io.github.jasondorjeshort.dystopo.nav.WayDownText
    val label = if (u.drawn) text.ACTION_HIDE else text.ACTION
    BasicText(
        label,
        Modifier
            .weight(1f, fill = false)
            .onGloballyPositioned { l -> l.boundsInRoot().let { b -> WayDownWatch.actionBoundsPx = floatArrayOf(b.left, b.top, b.right, b.bottom) } }
            .clickable(role = Role.Button) { WayDownWatch.setDrawn(!u.drawn) }
            .semantics { contentDescription = label }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        style = TextStyle(color = Accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    )
}

/** T196: the stretch's colour on the map (a dark blue, apart from the route's and the track's lines). */
private const val WAY_DOWN_INK = "#1f4fa8"

/** T196: the card's help text (`HelpRegistryTest`: declared beside its icon). */
internal val PRESSURE_CARD_HELP: List<io.github.jasondorjeshort.dystopo.app.help.HelpText> = listOf(
    io.github.jasondorjeshort.dystopo.app.help.HelpText(
        "readout.wayDown", "The way down", "The map: pressure warning card, on a ridge", needs = "a pressure warning while on a ridge",
    ) { io.github.jasondorjeshort.dystopo.nav.WayDownText.HELP },
)
// end T196
