package io.github.jasondorjeshort.dystopo.app.horizon

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.testTag
import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.app.ClockFormat
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.SettingsRoutes
import io.github.jasondorjeshort.dystopo.app.SettingsUi
import io.github.jasondorjeshort.dystopo.app.controls.Heading
import io.github.jasondorjeshort.dystopo.app.firstrun.Screens
import io.github.jasondorjeshort.dystopo.app.gnss.Button
import io.github.jasondorjeshort.dystopo.app.gnss.Gnss
import io.github.jasondorjeshort.dystopo.app.gnss.GnssPalette
import io.github.jasondorjeshort.dystopo.app.gnss.GnssText
import io.github.jasondorjeshort.dystopo.app.gnss.GnssUi
import io.github.jasondorjeshort.dystopo.app.gnss.LegendItem
import io.github.jasondorjeshort.dystopo.app.gnss.Mark
import io.github.jasondorjeshort.dystopo.app.gnss.MarkIcon
import io.github.jasondorjeshort.dystopo.app.gnss.OrientationRow
import io.github.jasondorjeshort.dystopo.app.gnss.PlotGeometry
import io.github.jasondorjeshort.dystopo.app.gnss.SatellitesSection
import io.github.jasondorjeshort.dystopo.app.gnss.SkyPlot
import io.github.jasondorjeshort.dystopo.app.gnss.plotSats
import io.github.jasondorjeshort.dystopo.app.help.HelpIcon
import io.github.jasondorjeshort.dystopo.app.help.HelpText
import io.github.jasondorjeshort.dystopo.app.settings.SettingsGroup
import io.github.jasondorjeshort.dystopo.dem.PeakView
import io.github.jasondorjeshort.dystopo.gnss.GnssHealth
import io.github.jasondorjeshort.dystopo.gnss.SkyPlotOrientation
import io.github.jasondorjeshort.dystopo.horizon.HorizonLayer
import io.github.jasondorjeshort.dystopo.horizon.HorizonLabels
import io.github.jasondorjeshort.dystopo.horizon.HorizonLayers
import io.github.jasondorjeshort.dystopo.horizon.HorizonPlace
import io.github.jasondorjeshort.dystopo.horizon.PlaceLine
import io.github.jasondorjeshort.dystopo.horizon.PeakRim
import io.github.jasondorjeshort.dystopo.horizon.RimBox
import io.github.jasondorjeshort.dystopo.horizon.RimGeometry
import io.github.jasondorjeshort.dystopo.horizon.RimPt
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import io.github.jasondorjeshort.dystopo.render.UnitFormat
import java.time.LocalDate
import kotlin.math.hypot

/**
 * T308: whether the Horizon view is open (Settings › Navigation and tracking › "Horizon view ›", `POST /horizon/ui?open=1`
 * or `POST /gnss/ui?open=1` in debug builds), the peak a tap picked, the day the Sun layer shows, a scroll a debug route
 * asked for, and what the view last drew ([drawn], `GET /horizon` `drawn`).
 */
object HorizonUi {
    var open by mutableStateOf(false)
        private set

    /** The peak a tap on its label or its row picked (its name and bearing), or null. */
    var selected by mutableStateOf<String?>(null)

    /** The day the Sun layer shows: null for today at the point (debug: `POST /horizon?date=`). */
    var date by mutableStateOf<LocalDate?>(null)

    /** A scroll of the control area a debug route asked for (px from its top), done once. */
    var scrollTo by mutableStateOf<Int?>(null)

    @Volatile var drawn: Map<String, Any?>? = null

    /**
     * T309: where the view's "Map | Horizon" switch was last laid out (left, top, right, bottom), or null. T315: px in the
     * Compose root, as the map's elements are in `GET /layout`, so the two rectangles compare as they are.
     */
    @Volatile var switchPx: List<Int>? = null

    /** The rim's names as last drawn (the plot's taps read them). */
    @Volatile var names: List<PlacedName> = emptyList()

    /** T309: the keys of the names last drawn, as Compose state (the list's bold rows read it; written from the draw only on a change). */
    var namedKeys by mutableStateOf<Set<String>>(emptySet())

    /** T312: the flight a tap on its dot, its name or its row picked (its hex code), whose card shows; or null. */
    var selectedFlight by mutableStateOf<String?>(null)

    /** T312: the flights as last drawn: each dot, its name's box when named (the plot's taps and `drawn` read them). */
    @Volatile var flightMarks: List<FlightMark> = emptyList()

    /**
     * T317: the point the view is drawn for in place of the fix ("Sky from here" on the long-press card, `POST /horizon/ui?lat=&lon=`),
     * or null at the fix. In memory only (the app reopens at the fix), never snapped back on a timer; "Back to here", Close
     * and the switch's "Horizon" (which opens at the fix) let it go.
     */
    var point by mutableStateOf<io.github.jasondorjeshort.dystopo.horizon.HorizonPoint?>(null)
        private set

    /** Makes the Compose state on the main thread (`MainActivity.onCreate`), before a debug route's thread reads it (T226). */
    fun prepare() {
        open; selected; date; scrollTo; namedKeys; selectedFlight; point
    }

    fun open() {
        GnssUi.resetUi()
        selected = null
        point = null // T317: the switch and the settings row open at the fix
        open = true
    }

    /** T317: opens the view at [p] (main thread): the satellites and flights hidden, the place line saying where it stands. */
    fun openAt(p: io.github.jasondorjeshort.dystopo.horizon.HorizonPoint) {
        GnssUi.resetUi()
        selected = null
        selectedFlight = null
        flightMarks = emptyList()
        point = p
        open = true
    }

    /**
     * T317: "Sky from here" for a press at [lat], [lon]: the named feature within [radiusM] looked up off the main thread,
     * then the view opened there ([openAt]); [then] after it opened (main thread).
     */
    fun openFrom(lat: Double, lon: Double, radiusM: Double, then: () -> Unit = {}) {
        val app = DystopoApp.instance
        Thread({
            val p = HorizonData.pointFor(lat, lon, radiusM)
            app.map.onMain {
                openAt(p)
                then()
            }
        }, "dystopo-horizon-point").apply { isDaemon = true }.start()
    }

    /** T317: "Back to here": the view returns to the phone's fix (main thread). */
    fun back() {
        selected = null
        point = null
        scrollTo = 0
    }

    fun close() {
        open = false
        point = null // T317
        selected = null
        selectedFlight = null // T312
        flightMarks = emptyList()
        io.github.jasondorjeshort.dystopo.app.aircraft.AircraftPoller.release(HorizonData.FLIGHTS_OWNER)
        scrollTo = null
        GnssUi.resetUi()
        drawn = null
        switchPx = null
    }

    /** Turns [layer] [on] (stored and synced as a setting, off the main thread), then redraws. */
    fun setLayer(layer: HorizonLayer, on: Boolean, then: () -> Unit = {}) {
        val app = DystopoApp.instance
        Thread({
            SettingsRoutes(app).apply(SettingsRoutes.Parsed.Ok.of(AppSettings.horizonLayer(layer), on))
            app.map.onMain { SettingsUi.revision++ }
            then()
        }, "dystopo-horizon-layer").start()
    }

    /** The key a peak is picked by: its name and its bearing to a tenth of a degree (two peaks may share a name). */
    fun keyOf(p: PeakView): String = "${p.peak.name}@${Math.round(p.bearingDeg * 10)}"
}

/** T308: the sun's path colour on the plot: a warm amber, legible on the day and the night paper. */
private fun sunColour(night: Boolean): Color = if (night) Color(0xFFE6A84E) else Color(0xFFB8641A)

/**
 * T308: the Horizon view, the map view's counterpart (the owner, 2026-11-19): the sky around the point as a square plot
 * with its circle ([SkyPlot]: the land's skyline, rings, N E S W, turned by the "Sky plot" setting), fixed at the top in
 * portrait and at the left in landscape, its side `min(width, height)` of the window inside the system bars; the rest of
 * the screen a heading with Close that stays, over an area that scrolls: the layer toggles (Satellites, Peaks, Sun; each
 * a synced setting, all on by default), the plot's orientation and terrain line, then each layer's content: the peaks on
 * the skyline, the sun's day, and the satellites (T307's summary, "Check my GPS" and list, last because the list is long).
 * While open and started it holds the compass for "Follow the phone"; it holds the GNSS only while Satellites is on (off,
 * the position is the map's own fix). A second each it reads everything again.
 */
@Composable
fun HorizonScreen() {
    if (!HorizonUi.open) return
    val owner = LocalLifecycleOwner.current
    var started by remember { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_START) started = true
            if (e == Lifecycle.Event.ON_STOP) started = false
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    var tick by remember { mutableStateOf(0) }
    @Suppress("UNUSED_VARIABLE") val rev = SettingsUi.revision
    // T312: the Flights layer and its button only while Flight tracking (T311's setting) is on.
    val tracking = AppSettings.AIRCRAFT.value
    // T317: at a chosen point the Satellites and Flights layers do not draw (the phone's own sky), their settings untouched.
    val point = HorizonUi.point
    val moved = point != null
    val layers = HorizonPlace.layers(AppSettings.horizonLayers.effective(tracking), moved)
    val skyRange = AppSettings.skyRange
    DisposableEffect(started) {
        Heading.setGnssWanted(started)
        onDispose { Heading.setGnssWanted(false) }
    }
    DisposableEffect(started, layers.satellites) {
        Gnss.hold("view", started && layers.satellites)
        onDispose { Gnss.hold("view", false) }
    }
    BackHandler {
        when {
            GnssUi.confirmReset -> GnssUi.confirmReset = false
            GnssUi.menuOpen -> GnssUi.menuOpen = false
            HorizonUi.selectedFlight != null -> HorizonUi.selectedFlight = null
            HorizonUi.selected != null -> HorizonUi.selected = null
            HorizonUi.point != null -> HorizonUi.back() // T317: Back first returns to the fix, as "Back to here"
            else -> HorizonUi.close()
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            io.github.jasondorjeshort.dystopo.app.Foreground.delay(1000)
            tick++
        }
    }
    @Suppress("UNUSED_VARIABLE") val t = tick
    val night = AppSettings.mapOptions.night
    val pal = remember(night) { GnssPalette(night) }
    // Light status-bar icons over the night paper while the view is open (the activity keeps dark ones for the light map).
    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(night) {
        val window = (view.context as? android.app.Activity)?.window
        val bars = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, view) }
        val was = bars?.isAppearanceLightStatusBars
        bars?.isAppearanceLightStatusBars = !night
        onDispose { if (was != null) bars.isAppearanceLightStatusBars = was }
    }
    val units = AppSettings.units
    val orientation = AppSettings.skyPlot
    val fix = Gnss.position()
    // T317: the point the view describes (the chosen one, else the fix), and the place line saying which.
    val pos = HorizonPlace.source(point, fix?.latitude, fix?.longitude)
    val place = HorizonPlace.line(point, fix?.latitude, fix?.longitude, units)
    val h = pos?.let { Gnss.horizonAt(it.first, it.second) }
    val horizon = h?.profile
    val fn = horizon?.let { p -> { az: Double -> p.angleAt(az) } }
    val sats = if (layers.satellites) Gnss.satellites else emptyList()
    val plotted = plotSats(sats)
    val summary = GnssHealth.summarize(sats)
    val heading = GnssUi.headingDeg()
    val rotation = GnssUi.rotationDeg(orientation)
    val now = System.currentTimeMillis()
    val sunNow = pos?.let { io.github.jasondorjeshort.dystopo.nav.Sun.position(it.first, it.second, now) }
    val sunDay = if (layers.sun && pos != null) HorizonData.sunFor(h?.takeIf { it.profile != null }, pos.first, pos.second, HorizonUi.date, now) else null
    val peaks = if (layers.peaks && h != null) HorizonData.peaksFor(h) else null
    // T312: the poll's centre is the phone's position rounded to 0.1 degree (the poller rounds; a new key only when it moves that far).
    val centre = fix?.let { Math.round(it.latitude * 10) to Math.round(it.longitude * 10) }
    DisposableEffect(started, layers.flights, centre, skyRange) {
        if (started && layers.flights && fix != null) {
            io.github.jasondorjeshort.dystopo.app.aircraft.AircraftPoller.request(HorizonData.FLIGHTS_OWNER, fix.latitude, fix.longitude, HorizonData.radiusNm(skyRange))
        }
        onDispose { io.github.jasondorjeshort.dystopo.app.aircraft.AircraftPoller.release(HorizonData.FLIGHTS_OWNER) }
    }
    val flights = if (layers.flights) HorizonData.flightsFor(h, fix, skyRange, now) else null
    val pickedFlight = HorizonUi.selectedFlight?.let { hex -> io.github.jasondorjeshort.dystopo.app.aircraft.AircraftPoller.view.value.aircraft.firstOrNull { it.hex == hex } }
    val selected = HorizonUi.selected
    val picked = peaks?.candidates?.firstOrNull { HorizonUi.keyOf(it) == selected }
    val scroll = rememberScrollState()
    HorizonUi.scrollTo?.let { px ->
        LaunchedEffect(px) {
            scroll.scrollTo(px.coerceAtMost(scroll.maxValue))
            HorizonUi.scrollTo = null
        }
    }
    val density = LocalDensity.current
    Box(Modifier.fillMaxSize().background(pal.paper).pointerInput(Unit) { detectTapGestures { } }) {
        BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().displayCutoutPadding()) {
            val landscape = maxWidth > maxHeight
            // T315: the "Map | Horizon" switch stands at its anchor in the window's bottom-left corner (`controls/ViewSwitch.kt`),
            // the same place as on the map. In landscape the plot, which fills the left, ends above it; in portrait the scroll
            // area runs under it and ends with room for it, so its last row clears the switch at the end of the scroll.
            val switchW = io.github.jasondorjeshort.dystopo.app.controls.ViewSwitch.widthDp.dp
            val switchRoom = io.github.jasondorjeshort.dystopo.app.controls.ViewSwitch.reserveDp.dp + SWITCH_GAP
            val side = if (landscape) minOf(maxWidth, maxHeight - switchRoom) else minOf(maxWidth, maxHeight)
            val paneOverSwitch = !landscape || side < switchW + io.github.jasondorjeshort.dystopo.app.controls.ViewSwitch.EDGE_DP.dp + SWITCH_GAP
            val sunDrawn = layers.sun && sunNow != null && sunNow.apparentAltitudeDeg >= -0.5
            val description = HorizonText.plotLabel(
                listOfNotNull(
                    "${HorizonText.PLACE_LABEL}: ${place.text}", // T317: where the plot stands, first
                    horizon?.let { p -> GnssText.horizonLine(p.angleDeg[p.highest], p.bearingDeg(p.highest), p.distanceM[p.highest], h.source, units) },
                    peaks?.ranked?.takeIf { it.isNotEmpty() }?.let { l -> "${HorizonText.PEAKS}: " + l.take(5).joinToString(", ") { it.label.name } },
                    sunNow?.takeIf { layers.sun }?.let { s -> HorizonText.sunNow(s.apparentAltitudeDeg, s.azimuthDeg, fn?.let { f -> s.apparentAltitudeDeg < f(s.azimuthDeg) } ?: false) },
                    flights?.let { f -> "${HorizonText.FLIGHTS}: " + (f.list.take(5).joinToString(", ") { it.label }.ifEmpty { "0" }) },
                    if (layers.satellites) "${GnssText.SATELLITES}: ${GnssText.usedOf(summary.used, summary.inView)}" else null,
                ),
            )
            val plot: @Composable () -> Unit = {
                Box(Modifier.size(side)) {
                    SkyPlot(
                        plotted, horizon, rotation, sunNow?.azimuthDeg?.takeIf { layers.sun }, sunNow?.apparentAltitudeDeg?.takeIf { layers.sun }, pal, description,
                        Modifier.fillMaxSize().pointerInput(Unit) {
                            detectTapGestures { tap ->
                                // The nearest name drawn (its box, or within 24 dp of it) picks its peak; a tap elsewhere lets it go.
                                val hit = HorizonUi.names.minByOrNull { n -> hypot((n.box.center.x - tap.x).toDouble(), (n.box.center.y - tap.y).toDouble()) }
                                    ?.takeIf { n -> n.box.inflate(24 * density.density).contains(tap) }
                                // T312: a flight's dot (within 24 dp) or its name; the nearer of a flight and a peak name wins.
                                val reach = 24 * density.density
                                val flight = HorizonUi.flightMarks.map { m -> m to m.distanceTo(tap) }.filter { (m, d) -> d <= reach || m.box?.contains(tap) == true }.minByOrNull { it.second }
                                val peakD = hit?.let { n -> hypot((n.box.center.x - tap.x).toDouble(), (n.box.center.y - tap.y).toDouble()).toFloat() }
                                if (flight != null && (peakD == null || flight.second <= peakD)) {
                                    HorizonUi.selectedFlight = flight.first.hex.takeIf { it != HorizonUi.selectedFlight }
                                } else {
                                    HorizonUi.selected = hit?.let { HorizonUi.keyOf(it.peak) }?.takeIf { it != HorizonUi.selected }
                                    if (hit == null) HorizonUi.selectedFlight = null
                                }
                            }
                        },
                        extra = { g -> sunDay?.let { drawSunPath(g, it, pal, night) } },
                        // T309: the names placed by rank and density, the picked peak first (a row of the list may pick one the plot did not name).
                        // T312: the flights' tails and dots, then the names in two passes (peaks as before, flights around them).
                        over = { g -> drawNames(g, peaks, picked, selected, sunDay, flights, HorizonUi.selectedFlight, pal) },
                    )
                    // T312: the feed's credit while the Flights layer draws, in the square's lower left corner (outside the circle).
                    flights?.let { f ->
                        BasicText(
                            f.credit, Modifier.align(Alignment.BottomStart).padding(start = 6.dp, bottom = 4.dp).fillMaxWidth(0.45f).testTag("horizonFlightsCredit"),
                            // Round 2: sized in dp like the plot's own labels (at font scale 2.0 a 10 sp line wrapped onto the plot), two lines at most.
                            style = TextStyle(color = pal.muted, fontSize = with(density) { 10.dp.toSp() }, lineHeight = with(density) { 12.dp.toSp() }),
                            maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            val pane: @Composable (Modifier) -> Unit = { m ->
                Column(m) {
                    // T317: the place line, just below the horizon (the plot) in portrait and at the pane's top in landscape,
                    // always shown; at a chosen point tinted apart with "Back to here", so another place's sky is never read as yours.
                    PlaceRow(place, moved, pal, night)
                    // The heading stays while the rest scrolls: the title, its help and Close.
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            BasicText(HorizonText.TITLE, Modifier.weight(1f, fill = false), style = TextStyle(color = pal.ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold))
                            HelpIcon("horizon.view")
                        }
                        Button(GnssText.CLOSE, pal) { HorizonUi.close() }
                    }
                    picked?.let { p -> PickedPeak(p, pal, units) }
                    Column(
                        Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(start = 16.dp, end = 16.dp, bottom = if (paneOverSwitch) maxOf(16.dp, switchRoom) else 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Toggles(layers, HorizonLayers.buttons(tracking), HorizonPlace.disabled(moved), pal)
                        if (moved) BasicText(HorizonText.MOVED_LAYERS, Modifier.testTag("horizonMovedLayers"), style = TextStyle(color = pal.muted, fontSize = 13.sp))
                        OrientationRow(orientation, pal)
                        if (orientation == SkyPlotOrientation.PHONE && heading == null) BasicText(GnssText.NORTH_UP_NO_COMPASS, style = TextStyle(color = pal.muted, fontSize = 13.sp))
                        BasicText(
                            when {
                                horizon != null -> GnssText.horizonLine(horizon.angleDeg[horizon.highest], horizon.bearingDeg(horizon.highest), horizon.distanceM[horizon.highest], h.source, units)
                                h != null -> h.reason ?: GnssText.NO_TERRAIN
                                pos == null -> GnssText.NO_POSITION
                                else -> GnssText.HORIZON_WORKING
                            },
                            style = TextStyle(color = pal.ink, fontSize = 13.sp),
                        )
                        if (horizon != null) LegendItem(pal, GnssText.LEGEND_TERRAIN) { MarkIcon(Mark.CIRCLE, null, pal.ink, band = pal.band) }
                        // T312: Flights before Peaks: a short live list, where the Peaks list runs to dozens of rows.
                        flights?.let { FlightsSection(it, fix != null, HorizonUi.selectedFlight, pal, units) }
                        if (layers.peaks) PeaksSection(h, peaks, selected, pal, units)
                        if (layers.sun) SunSection(pos != null, sunDay, sunNow, fn, pal, night, tick)
                        if (layers.satellites) SatellitesSection(sats, plotted, summary, fn, pal, units, tick) { tick++ }
                        if (layers.on.isEmpty()) BasicText(HorizonText.NO_LAYERS, style = TextStyle(color = pal.muted, fontSize = 14.sp))
                    }
                }
            }
            if (landscape) Row(Modifier.fillMaxSize()) {
                plot()
                pane(Modifier.weight(1f).fillMaxHeight().padding(top = 8.dp))
            } else Column(Modifier.fillMaxSize()) {
                plot()
                pane(Modifier.weight(1f).fillMaxWidth())
            }
            // T312: T311's flight card, hosted here (the view is drawn above the map, so the map's card would open underneath):
            // over the content pane's lower part, beside or under the square, so the dot it names stays in view.
            pickedFlight?.let { a ->
                io.github.jasondorjeshort.dystopo.app.aircraft.FlightCard(
                    a, { HorizonUi.selectedFlight = null },
                    (if (landscape) Modifier.align(Alignment.BottomEnd).width(maxWidth - side) else Modifier.align(Alignment.BottomCenter).fillMaxWidth())
                        .heightIn(max = (if (landscape) maxHeight else maxHeight - side) * 0.9f).padding(8.dp)
                        .padding(bottom = if (landscape) 0.dp else switchRoom - 8.dp).testTag("horizonFlightCard"), // T315: above the switch in portrait
                )
            }
            SideEffect {
                GnssUi.noteDrawn(plotted, fn, rotation, orientation, heading, horizon != null, sunDrawn, night, layers.satellites)
                HorizonUi.drawn = linkedMapOf(
                    "layers" to layers.json(), "landscape" to landscape, "squareDp" to Math.round(side.value),
                    "windowDp" to listOf(Math.round(maxWidth.value), Math.round(maxHeight.value)), "fontScale" to density.fontScale,
                    "rotationDeg" to Math.round(rotation * 10) / 10.0, "orientation" to orientation.key, "night" to night,
                    "horizonDrawn" to (horizon != null), "satellitesDrawn" to plotted.size, "gnssHeld" to Gnss.held,
                    "peaksDrawn" to HorizonUi.names.map { it.peak.peak.name }, "namesDrawn" to HorizonUi.names.size, "peaksListed" to (peaks?.candidates?.size ?: 0),
                    "hollowDrawn" to HorizonUi.names.filter { !PeakRim.inSight(it.peak) }.map { it.peak.peak.name },
                    "leadersDrawn" to HorizonUi.names.filter { it.leader != null }.map { it.peak.peak.name },
                    "sunPathDrawn" to (sunDay?.sun?.path?.size ?: 0), "sunHourMarks" to (sunDay?.marks?.size ?: 0), "sunNowDrawn" to sunDrawn,
                    "selected" to picked?.peak?.name, "scrollPx" to scroll.value, "scrollMaxPx" to scroll.maxValue,
                    "switchPx" to HorizonUi.switchPx, "switchSelected" to "horizon",
                    // T312
                    "flightTracking" to tracking, "buttons" to HorizonLayers.buttons(tracking).map { it.key }, "skyRange" to skyRange.key,
                    "flightsDrawn" to HorizonUi.flightMarks.map { it.hex }, "flightNames" to HorizonUi.flightMarks.filter { it.box != null }.map { it.label },
                    "flightsHollow" to HorizonUi.flightMarks.filter { it.hollow }.map { it.hex }, "flightsEmergency" to HorizonUi.flightMarks.filter { it.emergency }.map { it.hex },
                    "flightTailPoints" to HorizonUi.flightMarks.sumOf { it.tailPoints }, "flightsListed" to (flights?.list?.size ?: 0),
                    "selectedFlight" to pickedFlight?.hex, "flightCard" to (pickedFlight != null), "creditShown" to flights?.credit,
                    // T315: what the switch's place costs the view: the square's side and whether the pane runs under the switch.
                    "paneOverSwitch" to paneOverSwitch, "switchRoomDp" to Math.round(switchRoom.value),
                    // T317: the point and the place line as drawn.
                    "point" to point?.let { p -> linkedMapOf("lat" to p.lat, "lon" to p.lon, "name" to p.name) }, "placeLine" to place.text,
                    "placeKind" to place.kind, "moved" to place.moved, "backShown" to moved,
                    "disabledButtons" to HorizonPlace.disabled(moved).map { it.key },
                )
            }
        }
        // T315: the "Map | Horizon" switch at its anchor (the map's own place for it), Horizon selected, over the content.
        io.github.jasondorjeshort.dystopo.app.controls.AnchoredViewSwitch(
            true, io.github.jasondorjeshort.dystopo.app.controls.ViewSwitchColours(pal.card, pal.ink, pal.line),
            Modifier.testTag(io.github.jasondorjeshort.dystopo.app.controls.ViewSwitch.TAG_ON_VIEW),
        ) { r, _ -> HorizonUi.switchPx = r.map { Math.round(it) } }
    }
}

/** T315: the room between the switch's top and what stands above it on the Horizon view (the plot in landscape, the last row). */
private val SWITCH_GAP = 8.dp

/** A rim name as last drawn: the peak, the text's baseline centre and its box (px), for the tap and `drawn`; T309: its leader line. */
class PlacedName(val peak: PeakView, val anchor: Offset, val box: androidx.compose.ui.geometry.Rect, val leader: Pair<Offset, Offset>? = null)

/**
 * T309: the rim's names, placed by [HorizonLabels.place] (rank first, the density gap, no name over another, a ring label,
 * an hour label or the sun's arc), each with its triangle at its peak's apparent angle: solid in sight, hollow just out of
 * sight. The picked peak goes first, so a row picked in the list is named too. A name set away from its triangle has a
 * thin leader line to it (the ink at reduced alpha, 1 dp); each name sits on a plate of the sky's colour (T309: the stroke
 * halo before it showed as a ghost "M" where "Mount Rainier" crossed the skyline band's edge).
 */
private fun DrawScope.drawNames(
    g: PlotGeometry, peaks: HorizonData.Peaks?, picked: PeakView?, selected: String?, sunDay: HorizonData.SunDay?,
    flights: HorizonData.Flights?, pickedFlight: String?, pal: GnssPalette,
) {
    val text = android.graphics.Paint().apply { isAntiAlias = true; textSize = 12.dp.toPx(); color = pal.ink.toArgb(); textAlign = android.graphics.Paint.Align.CENTER }
    val rim = RimGeometry(g.c.x.toDouble(), g.c.y.toDouble(), g.radius.toDouble(), g.rotationDeg, density.toDouble())
    // What the names keep off: the ring labels (SkyPlot's, 12 dp) and, with the Sun layer, its hour labels (10 dp) and arc.
    val ringPaint = android.graphics.Paint().apply { textSize = 12.dp.toPx() }
    val rfm = ringPaint.fontMetrics
    val blocked = ArrayList<RimBox>()
    for ((el, label) in listOf(0.0 to "0°", 30.0 to "30°", 60.0 to "60°")) blocked += rim.ringLabel(el, ringPaint.measureText(label).toDouble(), rfm.ascent.toDouble(), rfm.descent.toDouble())
    val lines = ArrayList<List<RimPt>>()
    sunDay?.let { day ->
        val hourPaint = android.graphics.Paint().apply { textSize = 10.dp.toPx() }
        val hfm = hourPaint.fontMetrics
        val cal = java.util.Calendar.getInstance()
        for (m in day.marks) {
            cal.timeInMillis = m.timeMs
            blocked += rim.hourLabel(m.altitudeDeg, m.azimuthDeg, hourPaint.measureText(cal.get(java.util.Calendar.HOUR_OF_DAY).toString()).toDouble(), hfm.ascent.toDouble(), hfm.descent.toDouble())
        }
        lines += day.sun.path.map { rim.at(it.altitudeDeg, it.azimuthDeg) }
    }
    val fm = text.fontMetrics
    val first = picked?.let { p -> peaks?.scoreOf(p)?.label }
    // T312: the flights first under everything: their tails and dots; then the names in two passes (HorizonLabels.placeLayers).
    val flightList = flights?.list.orEmpty()
    val flightRanked = HorizonLabels.rank(io.github.jasondorjeshort.dystopo.horizon.HorizonFlights.labels(flightList))
    val firstFlight = flightRanked.firstOrNull { (it.label.ref as io.github.jasondorjeshort.dystopo.horizon.SkyFlight).hex == pickedFlight }?.label
    // Round 2: flight names also keep off N E S W and the north mark (the second pass only; peak names stay where they were).
    val compassPaint = android.graphics.Paint().apply { textSize = (RimGeometry.COMPASS_DP * density).toFloat(); isFakeBoldText = true }
    val cfm = compassPaint.fontMetrics
    val compass = rim.compassBoxes({ compassPaint.measureText(it).toDouble() }, cfm.ascent.toDouble(), cfm.descent.toDouble())
    val layers = HorizonLabels.placeLayers(
        rim, peaks?.ranked.orEmpty(), flightRanked, { text.measureText(it).toDouble() }, fm.ascent.toDouble(), fm.descent.toDouble(), blocked, lines, first, firstFlight,
        aircraftBlocked = compass,
    )
    drawFlights(g, flightList, layers.aircraft, pickedFlight, text, pal)
    val placed = layers.peaks
    val names = placed.map { n ->
        PlacedName(
            n.label.ref as PeakView, Offset(n.x.toFloat(), n.y.toFloat()),
            androidx.compose.ui.geometry.Rect(n.box.left.toFloat(), n.box.top.toFloat(), n.box.right.toFloat(), n.box.bottom.toFloat()),
            n.leader?.let { l -> Offset(l[0].x.toFloat(), l[0].y.toFloat()) to Offset(l[1].x.toFloat(), l[1].y.toFloat()) },
        )
    }
    HorizonUi.names = names
    val keys = names.map { HorizonUi.keyOf(it.peak) }.toSet()
    if (keys != HorizonUi.namedKeys) HorizonUi.namedKeys = keys
    val s = 5.dp.toPx()
    // The leader lines first, from just past the triangle to the name's box.
    for (n in names) n.leader?.let { (a, b) ->
        val len = kotlin.math.hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble()).toFloat().coerceAtLeast(1f)
        val from = Offset(a.x + (b.x - a.x) / len * s * 1.3f, a.y + (b.y - a.y) / len * s * 1.3f)
        drawLine(pal.ink.copy(alpha = 0.55f), from, b, 1.dp.toPx())
    }
    for (n in names) {
        val p = n.peak
        val at = g.at(p.angleDeg.coerceAtLeast(0.0), p.bearingDeg)
        val tri = Path().apply { moveTo(at.x, at.y - s); lineTo(at.x + s, at.y + s * 0.7f); lineTo(at.x - s, at.y + s * 0.7f); close() }
        val colour = if (HorizonUi.keyOf(p) == selected) pal.link else pal.ink
        if (PeakRim.inSight(p)) drawPath(tri, colour)
        else {
            drawPath(tri, pal.sky)
            drawPath(tri, colour, style = Stroke(1.5.dp.toPx()))
        }
    }
    for (n in names) {
        val chosen = HorizonUi.keyOf(n.peak) == selected
        text.isFakeBoldText = chosen
        text.color = (if (chosen) pal.link else pal.ink).toArgb()
        drawRoundRect(pal.sky.copy(alpha = 0.85f), n.box.topLeft, n.box.size, androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
        drawContext.canvas.nativeCanvas.drawText(n.peak.peak.name, n.anchor.x, n.anchor.y, text)
    }
}

private fun DrawScope.drawSunPath(g: PlotGeometry, day: HorizonData.SunDay, pal: GnssPalette, night: Boolean) {
    val path = day.sun.path
    if (path.size < 2) return
    val colour = sunColour(night)
    val solid = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round)
    val dashed = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())))
    // Runs of samples on the same side of the skyline, each drawn as one line (solid in view, dashed behind the terrain),
    // each run reaching the first sample of the next so the arc has no break.
    var i = 0
    while (i < path.size - 1) {
        val clear = path[i].clear
        var j = i
        while (j < path.size - 1 && path[j].clear == clear) j++
        val line = Path()
        for (k in i..j) {
            val p = g.at(path[k].altitudeDeg, path[k].azimuthDeg)
            if (k == i) line.moveTo(p.x, p.y) else line.lineTo(p.x, p.y)
        }
        drawPath(line, if (clear) colour else pal.muted, style = if (clear) solid else dashed)
        i = j
    }
    val label = android.graphics.Paint().apply { isAntiAlias = true; textSize = 10.dp.toPx(); color = pal.muted.toArgb(); textAlign = android.graphics.Paint.Align.CENTER }
    val cal = java.util.Calendar.getInstance()
    for (m in day.marks) {
        val p = g.at(m.altitudeDeg, m.azimuthDeg)
        drawCircle(if (m.clear) colour else pal.muted, 3.dp.toPx(), p)
        cal.timeInMillis = m.timeMs
        drawContext.canvas.nativeCanvas.drawText(cal.get(java.util.Calendar.HOUR_OF_DAY).toString(), p.x, p.y - 6.dp.toPx(), label)
    }
}

/**
 * T312: a flight as last drawn: its [hex], its dot's centre, its name's [box] when named, whether [hollow] or an
 * [emergency], its [tailPoints] (for the tap and `GET /horizon` `drawn`).
 */
class FlightMark(val hex: String, val label: String, val dot: Offset, val box: androidx.compose.ui.geometry.Rect?, val hollow: Boolean, val emergency: Boolean, val tailPoints: Int) {
    fun distanceTo(p: Offset): Float = minOf(
        (dot - p).getDistance(),
        box?.let { b -> if (b.contains(p)) 0f else Float.MAX_VALUE } ?: Float.MAX_VALUE,
    )
}

/** T312: T311's one altitude colour scale (metres above EGM2008), linear between its stops, as the map's trails. */
internal fun altitudeColour(m: Double?): Color {
    val stops = io.github.jasondorjeshort.dystopo.aircraft.AircraftText.ALTITUDE_COLOURS
    fun c(hex: String) = Color(android.graphics.Color.parseColor(hex))
    m ?: return c(io.github.jasondorjeshort.dystopo.aircraft.AircraftText.UNKNOWN_COLOUR)
    if (m <= stops.first().first) return c(stops.first().second)
    for (i in 0 until stops.size - 1) {
        val (a, ca) = stops[i]
        val (b, cb) = stops[i + 1]
        if (m <= b) return androidx.compose.ui.graphics.lerp(c(ca), c(cb), ((m - a) / (b - a)).toFloat())
    }
    return c(stops.last().second)
}

/** T312: the emergency ring's colour (the shape carries the meaning; the red adds to it). */
private val EMERGENCY = Color(0xFFD0021B)

/**
 * T312: the Flights layer on the plot: each flight's tail (its last 90 s, thin, in its altitude colour; dashed while
 * hollow), its dot (filled in the altitude colour with an ink edge; hollow, the sky's colour inside an altitude-coloured
 * ring, when hidden by the terrain or stale), a red ring around an emergency's dot, a link-coloured ring around the picked
 * one, then the names [named] placed (on a plate, muted when hollow). Writes [HorizonUi.flightMarks].
 */
private fun DrawScope.drawFlights(g: PlotGeometry, list: List<io.github.jasondorjeshort.dystopo.horizon.SkyFlight>, named: List<HorizonLabels.Placed>, picked: String?, text: android.graphics.Paint, pal: GnssPalette) {
    val r = 4.5.dp.toPx()
    val boxes = named.associate { n -> (n.label.ref as io.github.jasondorjeshort.dystopo.horizon.SkyFlight).hex to n }
    val marks = ArrayList<FlightMark>()
    for (f in list) {
        if (f.tail.size < 2) continue
        val colour = altitudeColour(f.aircraft.altitudeM)
        val path = Path()
        for ((i, p) in f.tail.withIndex()) {
            val at = g.at(p.elevationDeg, p.bearingDeg)
            if (i == 0) path.moveTo(at.x, at.y) else path.lineTo(at.x, at.y)
        }
        drawPath(
            path, colour.copy(alpha = 0.7f),
            style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, pathEffect = if (f.hollow) PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())) else null),
        )
    }
    for (f in list) {
        val at = g.at(f.elevationDeg, f.bearingDeg)
        val colour = altitudeColour(f.aircraft.altitudeM)
        val emergency = f.aircraft.report.emergency != null
        if (f.hollow) {
            drawCircle(pal.sky, r, at)
            drawCircle(colour, r - 1.dp.toPx(), at, style = Stroke(2.dp.toPx()))
        } else {
            drawCircle(colour, r, at)
            drawCircle(pal.ink, r, at, style = Stroke(1.dp.toPx()))
        }
        if (emergency) drawCircle(EMERGENCY, r + 4.dp.toPx(), at, style = Stroke(2.dp.toPx()))
        if (f.hex == picked) drawCircle(pal.link, r + 7.5.dp.toPx(), at, style = Stroke(2.dp.toPx()))
        val n = boxes[f.hex]
        marks += FlightMark(
            f.hex, f.label, at, n?.let { androidx.compose.ui.geometry.Rect(it.box.left.toFloat(), it.box.top.toFloat(), it.box.right.toFloat(), it.box.bottom.toFloat()) },
            f.hollow, emergency, f.tail.size,
        )
    }
    for (n in named) {
        val f = n.label.ref as io.github.jasondorjeshort.dystopo.horizon.SkyFlight
        n.leader?.let { l -> drawLine(pal.ink.copy(alpha = 0.45f), Offset(l[0].x.toFloat(), l[0].y.toFloat()), Offset(l[1].x.toFloat(), l[1].y.toFloat()), 1.dp.toPx()) }
        drawRoundRect(pal.sky.copy(alpha = 0.85f), Offset(n.box.left.toFloat(), n.box.top.toFloat()), androidx.compose.ui.geometry.Size((n.box.right - n.box.left).toFloat(), (n.box.bottom - n.box.top).toFloat()), androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
        text.isFakeBoldText = f.hex == picked
        text.color = (if (f.hex == picked) pal.link else if (f.hollow) pal.muted else pal.ink).toArgb()
        drawContext.canvas.nativeCanvas.drawText(n.label.name, n.x.toFloat(), n.y.toFloat(), text)
    }
    text.isFakeBoldText = false
    text.color = pal.ink.toArgb()
    HorizonUi.flightMarks = marks
}

/** The layer toggles: one row of toggle buttons (the owner's words), wrapping at large text; T312: [buttons] those shown. */
@Composable
private fun Toggles(layers: HorizonLayers, buttons: List<HorizonLayer>, disabled: Set<HorizonLayer>, pal: GnssPalette) {
    @OptIn(ExperimentalLayoutApi::class)
    // Round 2 (T312): 6 dp gaps and 10 dp padding, so "Show:" and the four buttons fit one row at the default font on a
    // 411 dp phone (14 dp and 8 dp wrapped Flights onto a row of its own); larger text still wraps.
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
            BasicText("${HorizonText.LAYERS}:", style = TextStyle(color = pal.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium))
        }
        for (l in buttons) {
            val on = layers[l]
            // T317: at a chosen point Satellites and Flights are disabled: no tick, muted, not tappable (their settings kept).
            val enabled = l !in disabled
            Box(
                Modifier.heightIn(min = 48.dp).semantics { this.selected = on }.clickable(enabled = enabled, role = Role.Switch) { HorizonUi.setLayer(l, !on) },
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    (if (on) "✓ " else "") + l.label,
                    style = TextStyle(color = if (on) pal.paper else if (enabled) pal.ink else pal.muted, fontSize = 14.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium),
                    modifier = Modifier.background(if (on) pal.ink else if (enabled) pal.card else pal.paper, RoundedCornerShape(16.dp))
                        .border(1.dp, if (on) pal.ink else pal.line, RoundedCornerShape(16.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/** T317: the moved place line's tint: the sun's amber, a fill and a stronger edge, legible on the day and the night paper. */
private fun movedFill(night: Boolean): Color = if (night) Color(0xFF4A3412) else Color(0xFFFBE2AE)
private fun movedEdge(night: Boolean): Color = if (night) Color(0xFFE6A84E) else Color(0xFFB8641A)

/**
 * T317: where the view stands, always shown: "Your location" beside a dot in the link colour at the fix; at a chosen point
 * the line ("At Mount Rainier, 7.6 km NNW of you", "12 km SSE of you") bold on an amber band with "Back to here", so a moved
 * view is never read as the phone's own. A point within 50 m of the fix reads "Your location" but keeps "Back to here".
 */
@Composable
private fun PlaceRow(place: PlaceLine, showBack: Boolean, pal: GnssPalette, night: Boolean) {
    if (!showBack) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("horizonPlaceLine"), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.Canvas(Modifier.size(10.dp)) { drawCircle(pal.link) }
            BasicText(place.text, Modifier.padding(start = 8.dp), style = TextStyle(color = pal.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
        }
        return
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
            .background(if (place.moved) movedFill(night) else pal.card, RoundedCornerShape(10.dp))
            .border(2.dp, if (place.moved) movedEdge(night) else pal.line, RoundedCornerShape(10.dp))
            .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(place.text, Modifier.weight(1f).testTag("horizonPlaceLine"), style = TextStyle(color = pal.ink, fontSize = 16.sp, fontWeight = FontWeight.Bold))
        Box(Modifier.padding(start = 8.dp).testTag("horizonBackToHere")) { Button(HorizonText.BACK, pal, filled = true) { HorizonUi.back() } }
    }
}

/** The peak a tap picked, under the heading (it stays while the list scrolls); a tap on it or Back lets it go. */
@Composable
private fun PickedPeak(p: PeakView, pal: GnssPalette, units: ContourUnits) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).background(pal.card, RoundedCornerShape(10.dp)).border(1.dp, pal.link, RoundedCornerShape(10.dp))
            .clickable(role = Role.Button) { HorizonUi.selected = null }.padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(HorizonText.peakLine(p.peak.name, p.eleM, p.distanceM, p.bearingDeg, units, !PeakRim.inSight(p)), Modifier.weight(1f), style = TextStyle(color = pal.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
        BasicText("×", Modifier.padding(start = 8.dp).semantics { contentDescription = HorizonText.PEAK_CLEAR }, style = TextStyle(color = pal.ink, fontSize = 20.sp))
    }
}

@Composable
private fun SectionHead(title: String, pal: GnssPalette, extra: String? = null, help: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        BasicText(title, style = TextStyle(color = pal.ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold))
        extra?.let { BasicText("  $it", style = TextStyle(color = pal.muted, fontSize = 14.sp)) }
        help()
    }
}

/** T309: the peaks in view or just out of sight: how many, then each (name, height, distance, bearing) closest first; a tap picks one. */
@Composable
private fun PeaksSection(h: Gnss.HorizonState?, peaks: HorizonData.Peaks?, selected: String?, pal: GnssPalette, units: ContourUnits) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionHead(HorizonText.PEAKS, pal) { HelpIcon("horizon.peaks") }
        val line = when {
            h == null -> HorizonText.PEAKS_WORKING
            h.profile == null -> HorizonText.PEAKS_NO_TERRAIN
            peaks == null -> HorizonText.PEAKS_WORKING
            peaks.reason == HorizonText.PEAKS_NONE -> HorizonText.peaksNone(units)
            peaks.reason != null -> peaks.reason
            else -> HorizonText.peaksCount(peaks.candidates.size, units)
        }
        BasicText(line, style = TextStyle(color = pal.ink, fontSize = 14.sp))
        val named = HorizonUi.namedKeys
        for (p in peaks?.candidates.orEmpty()) {
            val key = HorizonUi.keyOf(p)
            val chosen = key == selected
            Column(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { this.selected = chosen }
                    .clickable(role = Role.Button) { HorizonUi.selected = if (chosen) null else key }.padding(vertical = 4.dp),
            ) {
                BasicText(
                    p.peak.name, style = TextStyle(color = if (chosen) pal.link else pal.ink, fontSize = 15.sp, fontWeight = if (key in named || chosen) FontWeight.Bold else FontWeight.Normal),
                )
                BasicText(
                    "${UnitFormat.elevation(p.eleM, units)} · ${UnitFormat.distance(p.distanceM, units)} · ${GnssText.compassPoint(p.bearingDeg)} ${Math.round(p.bearingDeg) % 360}°" +
                        if (PeakRim.inSight(p)) "" else " · ${HorizonText.JUST_OUT_OF_SIGHT}",
                    style = TextStyle(color = pal.muted, fontSize = 13.sp),
                )
            }
        }
    }
}

/**
 * T312: the flights on the plot, closest first (as the Peaks list): how many, or the feed's state; then each: a dot as the
 * plot draws it (an emergency's ring too), the label (bold when picked), and distance, bearing, angle, altitude in the
 * user's units, the kind and an emergency's line; a tap opens its card.
 */
@Composable
private fun FlightsSection(f: HorizonData.Flights, havePosition: Boolean, selected: String?, pal: GnssPalette, units: ContourUnits) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionHead(HorizonText.FLIGHTS, pal) { HelpIcon("horizon.flights") }
        val line = when {
            !havePosition -> HorizonText.FLIGHTS_NO_POSITION
            f.list.isNotEmpty() -> HorizonText.flightsCount(f.list.size, f.range, units)
            f.message != null -> f.message
            else -> HorizonText.flightsNone(f.range, units)
        }
        BasicText(line, style = TextStyle(color = pal.ink, fontSize = 14.sp))
        // A feed that is offline, stopped or limited says so above the rows it still holds.
        if (f.list.isNotEmpty() && f.message != null) BasicText(f.message, style = TextStyle(color = pal.muted, fontSize = 13.sp))
        for (s in f.list) {
            val chosen = s.hex == selected
            val emergency = s.aircraft.report.emergency
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { this.selected = chosen }
                    .clickable(role = Role.Button) { HorizonUi.selectedFlight = if (chosen) null else s.hex }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.foundation.Canvas(Modifier.size(24.dp)) {
                    val c = Offset(size.width / 2, size.height / 2)
                    val r = 4.5.dp.toPx()
                    val colour = altitudeColour(s.aircraft.altitudeM)
                    if (s.hollow) {
                        drawCircle(pal.sky, r, c)
                        drawCircle(colour, r - 1.dp.toPx(), c, style = Stroke(2.dp.toPx()))
                    } else {
                        drawCircle(colour, r, c)
                        drawCircle(pal.ink, r, c, style = Stroke(1.dp.toPx()))
                    }
                    if (emergency != null) drawCircle(EMERGENCY, r + 4.dp.toPx(), c, style = Stroke(2.dp.toPx()))
                }
                Column(Modifier.weight(1f).padding(start = 6.dp)) {
                    BasicText(
                        s.label + (emergency?.let { " · ${it.line} (${it.squawk})" } ?: ""),
                        style = TextStyle(color = if (chosen) pal.link else pal.ink, fontSize = 15.sp, fontWeight = if (chosen || emergency != null) FontWeight.Bold else FontWeight.Normal),
                    )
                    BasicText(
                        listOfNotNull(
                            HorizonText.kind(s.aircraft.report.kind),
                            HorizonText.flightLine(s.distanceM, s.bearingDeg, s.elevationDeg, HorizonData.altitudeText(s, units), s.hidden, s.aircraft.ageS.takeIf { s.stale }, units),
                        ).joinToString(" · "),
                        style = TextStyle(color = pal.muted, fontSize = 13.sp),
                    )
                }
            }
        }
    }
}

/** The sun's day: where it is now, when it clears and leaves the terrain (and any gaps), and the flat horizon's times. */
@Composable
private fun SunSection(havePosition: Boolean, day: HorizonData.SunDay?, now: io.github.jasondorjeshort.dystopo.nav.Sun.Position?, fn: ((Double) -> Double)?, pal: GnssPalette, night: Boolean, tick: Int) {
    @Suppress("UNUSED_VARIABLE") val t = tick
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val dateText = day?.let { d ->
            val date = LocalDate.of(d.sun.year, d.sun.month, d.sun.day)
            if (HorizonUi.date == null) HorizonText.TODAY else date.format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.getDefault()))
        }
        SectionHead(HorizonText.SUN, pal, dateText) { HelpIcon("horizon.sun") }
        if (!havePosition || day == null) {
            BasicText(HorizonText.SUN_NO_POSITION, style = TextStyle(color = pal.ink, fontSize = 14.sp))
            return@Column
        }
        val style = TextStyle(color = pal.ink, fontSize = 14.sp)
        val c = { ms: Long -> ClockFormat.text(ms) }
        if (HorizonUi.date == null && now != null) BasicText(HorizonText.sunNow(now.apparentAltitudeDeg, now.azimuthDeg, fn?.let { it(now.azimuthDeg) > now.apparentAltitudeDeg } ?: false), style = style)
        val td = day.sun.terrain
        if (day.sun.onTerrain) {
            when {
                td.alwaysUp -> BasicText(HorizonText.SUN_ALL_DAY, style = style)
                td.sunlit.isEmpty() -> BasicText(HorizonText.SUN_NEVER, style = style)
                else -> {
                    BasicText(HorizonText.terrainTimes(td.sunriseMs?.let(c), td.sunsetMs?.let(c)), style = style.copy(fontWeight = FontWeight.SemiBold))
                    for (g in td.gaps) BasicText(HorizonText.gap(c(g.span.startMs), c(g.span.endMs), g.bearingDeg), style = style)
                    BasicText(HorizonText.sunMinutes(td.sunMinutes), style = style)
                }
            }
        } else BasicText(HorizonText.SUN_FLAT_ONLY, style = TextStyle(color = pal.muted, fontSize = 13.sp))
        BasicText(HorizonText.flat(td.flat.sunriseMs?.let(c), td.flat.sunsetMs?.let(c)), style = style)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            androidx.compose.foundation.Canvas(Modifier.width(28.dp).heightIn(min = 14.dp)) {
                drawLine(sunColour(night), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2.5.dp.toPx(), cap = StrokeCap.Round)
            }
            BasicText(HorizonText.SUN_LEGEND, Modifier.weight(1f), style = TextStyle(color = pal.muted, fontSize = 13.sp))
        }
    }
}

/** T308: the Horizon view's help pop-ups (one paragraph each, no app named). */
internal val HORIZON_HELP: List<HelpText> = run {
    val placed = "${Screens.SETTINGS} › ${SettingsGroup.NAVIGATION.title} › ${HorizonText.TITLE}"
    val show: () -> Unit = { HorizonUi.open() }
    listOf(
        HelpText("horizon.view", HorizonText.TITLE, placed, show = show) { units ->
            "The sky around you as a circle: its edge the horizon, its centre overhead, the shaded band the land's skyline " +
                "within ${HorizonText.range(units)}. Show turns the layers on and off: the satellites, the peaks in view and the sun's path. " +
                // T317
                "To see another place's sky, long-press the map and pick ${HorizonText.SKY_FROM_HERE}: the line under the plot then " +
                "says where it stands, satellites and flights are off, and ${HorizonText.BACK} returns to where you are."
        },
        HelpText("horizon.peaks", HorizonText.PEAKS, "$placed › ${HorizonText.PEAKS}", show = show) { units ->
            "Named peaks within ${HorizonText.range(units)} in view, or just out of sight (hidden by under " +
                "${UnitFormat.elevation(PeakRim.HIDDEN_M, units)}: a hollow triangle). The plot names the highest on the skyline, then the closest " +
                "and most in view, spaced out where crowded; the list has all, closest first. Tap one for its height, distance and bearing."
        },
        HelpText("horizon.sun", HorizonText.SUN, "$placed › ${HorizonText.SUN}", show = show) { _ ->
            "Today's path of the sun, with the hours, dashed where the terrain hides it. The times are when the top of the " +
                "sun clears the terrain and goes behind it, as the turn-back time uses; the flat horizon's are for comparison."
        },
        // T312
        HelpText("horizon.flights", HorizonText.FLIGHTS, "$placed › ${HorizonText.FLIGHTS}", show = show) { _ ->
            // T317 (T316's follow-up): the stale age scales with the refresh interval, as the tracker's rule does.
            val refreshS = AppSettings.AIRCRAFT_REFRESH_S.value
            "Each flight in the air is a dot where it is in the sky, with a tail of its last 90 s. Hollow: hidden by the " +
                "terrain or its position over ${io.github.jasondorjeshort.dystopo.aircraft.AircraftText.seconds(io.github.jasondorjeshort.dystopo.aircraft.AircraftRules.staleS(refreshS))} old " +
                "(${io.github.jasondorjeshort.dystopo.aircraft.AircraftText.refreshLabel(refreshS)} refresh). " +
                "A ring marks an emergency code. The list has all, closest first; tap a dot or a row for its card. " +
                "${HorizonText.SKY_RANGE} in Settings picks overhead, near or all."
        },
        // end T312
    )
}
