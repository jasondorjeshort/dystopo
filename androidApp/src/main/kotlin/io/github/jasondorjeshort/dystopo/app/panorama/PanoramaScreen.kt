package io.github.jasondorjeshort.dystopo.app.panorama

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import io.github.jasondorjeshort.dystopo.app.gnss.SatellitesSection
import io.github.jasondorjeshort.dystopo.app.gnss.plotSats
import io.github.jasondorjeshort.dystopo.app.horizon.FlightCardHost
import io.github.jasondorjeshort.dystopo.app.horizon.FlightsSection
import io.github.jasondorjeshort.dystopo.app.horizon.HorizonData
import io.github.jasondorjeshort.dystopo.app.horizon.SunSection
import io.github.jasondorjeshort.dystopo.app.horizon.pickedFlight
import io.github.jasondorjeshort.dystopo.gnss.GnssHealth
import io.github.jasondorjeshort.dystopo.horizon.HorizonLayers
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.app.SettingsUi
import io.github.jasondorjeshort.dystopo.app.controls.AnchoredViewSwitch
import io.github.jasondorjeshort.dystopo.app.controls.ViewSwitch
import io.github.jasondorjeshort.dystopo.app.controls.ViewSwitchColours
import io.github.jasondorjeshort.dystopo.app.gnss.Gnss
import io.github.jasondorjeshort.dystopo.app.gnss.GnssPalette
import io.github.jasondorjeshort.dystopo.app.gnss.GnssText
import io.github.jasondorjeshort.dystopo.app.help.HelpText
import io.github.jasondorjeshort.dystopo.app.horizon.HorizonText
import io.github.jasondorjeshort.dystopo.app.horizon.HorizonUi
import io.github.jasondorjeshort.dystopo.app.horizon.LayerToggles
import io.github.jasondorjeshort.dystopo.app.horizon.PeaksSection
import io.github.jasondorjeshort.dystopo.app.horizon.PickedPeak
import io.github.jasondorjeshort.dystopo.app.horizon.PlaceRow
import io.github.jasondorjeshort.dystopo.app.horizon.SkyViewEffects
import io.github.jasondorjeshort.dystopo.app.horizon.SkyViewHeading
import io.github.jasondorjeshort.dystopo.app.horizon.SkyViewHolds
import io.github.jasondorjeshort.dystopo.app.horizon.TerrainLine
import io.github.jasondorjeshort.dystopo.horizon.HorizonLayer
import io.github.jasondorjeshort.dystopo.horizon.HorizonPlace
import io.github.jasondorjeshort.dystopo.horizon.PanoramaSweep
import io.github.jasondorjeshort.dystopo.horizon.SkyView
import io.github.jasondorjeshort.dystopo.render.UnitFormat

/** T326: the Panorama's words (ADR 0031: shown in the task's report; no other product named). */
object PanoramaStrings {
    const val TITLE = "Panorama view"
    const val WORKING = "Working out the skyline..."
    const val ZOOM_IN = "Zoom in"
    const val ZOOM_OUT = "Zoom out"
    const val TURN_LEFT = "Turn left"
    const val TURN_RIGHT = "Turn right"
    const val LOOK_UP = "Look up"
    const val LOOK_DOWN = "Look down"
    const val PLOT_LABEL = "Panorama"
    const val PLACE_LABEL = "Panorama from"

    /** The plot's spoken label after the place line: "Facing NNW 347°, 60° across". */
    fun facing(bearingDeg: Double, fovDeg: Double): String =
        "Facing ${GnssText.compassPoint(bearingDeg)} ${Math.round(bearingDeg) % 360}°, ${Math.round(fovDeg)}° across"

    // T330: Follow (wave 46's choices (a), (g); the wording is the coordinator's and the owner's to change, ADR 0031).
    const val STEERING = "Panorama steering"
    const val STEERING_LINE = "how the Panorama view turns: by dragging it, or with the phone as you point it"
    const val STEERING_NO_SENSOR = "this phone has no motion sensor to follow, so the Panorama view turns by dragging"
    const val STEERING_HELP = "Drag turns the Panorama view with your finger. Follow phone turns it with the phone, as you " +
        "point it at the land, and keeps the screen on while it does. The view's Follow phone button switches between the two."
    const val FOLLOW_PHONE = "Follow phone"
    const val FOLLOWING = "Following the phone"

    /** T330: the tilt in the plot's spoken label in Follow: "12° up", "5° down", "level" (under half a degree). */
    fun tilt(tiltDeg: Double): String {
        val t = Math.round(tiltDeg)
        return when {
            t > 0 -> "$t° up"
            t < 0 -> "${-t}° down"
            else -> "level"
        }
    }

    /** T330: the plot's spoken label after the place line in Follow: "Following the phone; Facing NNW 347°, 12° up, 60° across". */
    fun following(bearingDeg: Double, tiltDeg: Double, fovDeg: Double): String =
        "$FOLLOWING; Facing ${GnssText.compassPoint(bearingDeg)} ${Math.round(bearingDeg) % 360}°, ${tilt(tiltDeg)}, ${Math.round(fovDeg)}° across"
    // end T330

    // T331: the heading correction's actions and button (wave 46's choice (f)); its line is HeadingCorrection.line, the
    // weak-compass line CompassHint.LINE (choice (h)).
    const val ADJUST_LEFT = "Adjust compass left"
    const val ADJUST_RIGHT = "Adjust compass right"
    const val RESET = "Reset"
    // end T331
}

/**
 * T326: the Panorama view (ADR 0064, pass two's first half; wave 45's choices (a) to (j)): the third segment of the switch,
 * a lens looking one way ([PanoramaPlot]: the skylines to the view range drawn far to near, the level line, the bearing
 * tape, the peaks named) over the Horizon view's own pane, hosted from its parts (`app/horizon/SkyViewParts.kt`,
 * `SkyViewSections.kt`): the place line with "Back to here", the heading "Panorama view" with its help, a picked peak's
 * line with "View from here", "Show:" with the Peaks toggle alone, the terrain line, the Peaks list. The effects are
 * [SkyViewEffects]' (the compass held for the opening's bearing, Back's ladder: a pick, a chosen point, then the view
 * closes). No Close button: the switch's "Map" and Back leave.
 *
 * The layout (choice (e)): upright, the plot the window's full width and three quarters of that tall, at most 45 percent
 * of the window's height, the pane under it; on its side, the plot at the left (the window's width less the pane, which
 * is the wider of 320 dp and 40 percent of the width) and its full height less the switch's room, the pane at the right.
 * Where the pane's list would have under 96 dp left, only the place line stays fixed and the heading and a picked peak's
 * line scroll with the list.
 */
@Composable
fun PanoramaScreen() {
    if (!HorizonUi.open || HorizonUi.view != SkyView.PANORAMA) return
    @Suppress("UNUSED_VARIABLE") val rev = SettingsUi.revision
    @Suppress("UNUSED_VARIABLE") val data = PanoramaData.revision
    val tracking = AppSettings.AIRCRAFT.value
    val point = HorizonUi.point
    val moved = point != null
    val layers = HorizonPlace.layers(AppSettings.horizonLayers.effective(tracking), moved)
    val night = AppSettings.mapOptions.night
    val pal = remember(night) { GnssPalette(night) }
    val units = AppSettings.units
    val fix = Gnss.position()
    val skyRange = AppSettings.skyRange
    // The compass for the opening's bearing (choice c); T327: the GNSS held while Satellites draws and the flights' poller
    // asked (owner `horizon`) while Flights draws, as the Horizon view holds them (the one open state, choice i); both off
    // at a chosen point (HorizonPlace.layers). Back lets go of the target first (PanoramaTargets.Back).
    // T330 (choice (a)): Follow when the setting says so and the phone has an attitude sensor (a synced follow acts as Drag
    // on a phone with none); then the attitude is held instead of the heading sensor Drag's opening takes.
    val follow = PanoramaSteering.follows(AppSettings.panoramaSteering, io.github.jasondorjeshort.dystopo.app.controls.Heading.attitudeAvailable)
    val clock = SkyViewEffects(
        SkyViewHolds(compass = !follow, satellites = layers.satellites, flightsAt = fix?.takeIf { layers.flights }?.let { it.latitude to it.longitude }, skyRange = skyRange), night,
        back = { PanoramaUi.apply(PanoramaTargets.Back) },
    )
    FollowEffects(follow, clock.started)
    val tick = clock.tick
    val pos = HorizonPlace.source(point, fix?.latitude, fix?.longitude)
    val place = HorizonPlace.line(point, fix?.latitude, fix?.longitude, units)
    val rangeM = AppSettings.viewRangeKm * 1000.0
    val density = LocalDensity.current
    val scroll = rememberScrollState()
    HorizonUi.scrollTo?.let { px ->
        LaunchedEffect(px) {
            scroll.scrollTo(px.coerceAtMost(scroll.maxValue))
            HorizonUi.scrollTo = null
        }
    }
    // The heights the choice (e) rule reads (px): the pane, the place line, the heading, a picked peak's line.
    var paneH by remember { mutableStateOf(0) }
    var placeH by remember { mutableStateOf(0) }
    var headingH by remember { mutableStateOf(0) }
    var pickedH by remember { mutableStateOf(0) }
    // T331 (choice (h)): the weak-compass line under the plot, and its height (px) the plot gives up on its side.
    val hint = PanoramaFollow.hintShown && follow
    var hintH by remember { mutableStateOf(0) }
    Box(Modifier.fillMaxSize().background(pal.paper).pointerInput(Unit) { detectTapGestures { } }.testTag("panoramaView")) {
        BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().displayCutoutPadding()) {
            val landscape = maxWidth > maxHeight
            val switchW = ViewSwitch.widthDp.dp
            val switchRoom = ViewSwitch.reserveDp.dp + 8.dp
            val paneW = if (landscape) maxOf(320.dp, maxWidth * 0.4f).coerceAtMost(maxWidth * 0.6f) else maxWidth
            val plotW = if (landscape) maxWidth - paneW else maxWidth
            val hintRoom = if (hint) with(density) { hintH.toDp() } else 0.dp
            val plotH = if (landscape) maxHeight - switchRoom - hintRoom else minOf(maxWidth * 0.75f, maxHeight * 0.45f)
            val paneOverSwitch = !landscape || plotW < switchW + ViewSwitch.EDGE_DP.dp + 8.dp
            // The place the view stands on, swept to the view range at the steps the zoom asks for (choice f).
            val aim = PanoramaUi.aim
            val wPx = with(density) { plotW.toPx() }.toDouble()
            val steps = PanoramaSweep.steps(aim?.fovDeg ?: io.github.jasondorjeshort.dystopo.horizon.PanoramaAim.OPENING_FOV_DEG, wPx)
            val work = pos?.let { PanoramaData.request(it.first, it.second, rangeM, steps, point == null) }
            val h = work?.state
            val peaks = if (layers.peaks) work?.peaks else null
            val selected = HorizonUi.selected
            val picked = peaks?.candidates?.firstOrNull { HorizonUi.keyOf(it) == selected }
            val pickedLabel = picked?.let { p -> peaks.scoreOf(p)?.label }
            // T327: the Sun, Satellites and Flights, as the Horizon view reads them, over the Panorama's own skyline (the view range).
            val nowMs = PanoramaUi.sunAtMs ?: System.currentTimeMillis()
            val fn = h?.profile?.let { pr -> { az: Double -> pr.angleAt(az) } }
            val sunNow = if (layers.sun && pos != null) io.github.jasondorjeshort.dystopo.nav.Sun.position(pos.first, pos.second, nowMs) else null
            val sunDay = if (layers.sun && pos != null) HorizonData.sunFor(h?.takeIf { it.profile != null }, pos.first, pos.second, HorizonUi.date, nowMs) else null
            val sats = if (layers.satellites) Gnss.satellites else emptyList()
            val plotted = plotSats(sats)
            val summary = GnssHealth.summarize(sats)
            val flights = if (layers.flights) HorizonData.flightsFor(h, fix, skyRange, System.currentTimeMillis()) else null
            val flight = pickedFlight()
            val lens = LensLayers(sunDay?.takeIf { pos != null }, sunNow, plotted, layers.satellites, flights, HorizonUi.selectedFlight, night)
            // T327: the target follows the picks (a row, a name, the pick's ×, the card's Close, Back), and its direction now.
            val target = PanoramaUi.targets.target
            PanoramaUi.targetDir = when (target?.kind) {
                TargetKind.PEAK -> work?.peaks?.candidates?.firstOrNull { HorizonUi.keyOf(it) == target.id }?.let { it.angleDeg to it.bearingDeg }
                TargetKind.SUN -> (sunNow ?: pos?.let { io.github.jasondorjeshort.dystopo.nav.Sun.position(it.first, it.second, nowMs) })?.let { it.apparentAltitudeDeg to it.azimuthDeg }
                TargetKind.FLIGHT -> flights?.list?.firstOrNull { it.hex == target.id }?.let { it.elevationDeg to it.bearingDeg }
                TargetKind.SATELLITE -> Gnss.satellites.firstOrNull { it.id == target.id }?.let { it.elevationDeg to it.azimuthDeg }
                null -> null
            }
            val listRoom = with(density) { (paneH - placeH - headingH - (if (picked != null) pickedH else 0)).toDp() }
            val headingScrolls = paneH > 0 && listRoom < 96.dp
            PanoramaUi.headingScrolls = headingScrolls
            val plot: @Composable () -> Unit = {
                Box(Modifier.size(plotW, plotH)) {
                    PanoramaPlot(plotW, plotH, pal, night, work, peaks?.ranked.orEmpty(), pickedLabel, place.text, wanted = steps, lensLayers = lens)
                    // T327: the feed's credit while the Flights layer draws, in the plot's lower left (the zoom buttons are at its right).
                    flights?.let { f ->
                        BasicText(
                            f.credit,
                            // On a plate: the plot's lower left is the near ground, where muted text alone did not read.
                            Modifier.align(Alignment.BottomStart).padding(start = 6.dp, bottom = 6.dp).widthIn(max = plotW * 0.45f)
                                .background(pal.card.copy(alpha = 0.88f), androidx.compose.foundation.shape.RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp)
                                .testTag("panoramaFlightsCredit"),
                            style = TextStyle(color = pal.muted, fontSize = with(density) { 10.dp.toSp() }, lineHeight = with(density) { 12.dp.toSp() }),
                            maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            val heading: @Composable () -> Unit = {
                Box(Modifier.onGloballyPositioned { headingH = it.size.height }) { SkyViewHeading(PanoramaStrings.TITLE, help = "panorama.view", pal = pal) }
                if (picked != null) Box(Modifier.onGloballyPositioned { pickedH = it.size.height }) { PickedPeak(picked, pal, units) }
            }
            val pane: @Composable (Modifier) -> Unit = { m ->
                Column(m.onGloballyPositioned { paneH = it.size.height }) {
                    Box(Modifier.onGloballyPositioned { placeH = it.size.height }) { PlaceRow(place, moved, pal, night) }
                    if (!headingScrolls) heading()
                    Column(
                        Modifier.weight(1f).fillMaxWidth()
                            .onGloballyPositioned { c ->
                                // The list's viewport (before the scroll), as the screen shows it.
                                val at = c.positionInRoot()
                                PanoramaUi.listPx = listOf(at.x, at.y, at.x + c.size.width, at.y + c.size.height).map { Math.round(it) }
                            }
                            .verticalScroll(scroll).testTag("panoramaList")
                            .padding(bottom = if (paneOverSwitch) maxOf(16.dp, switchRoom) else 16.dp),
                    ) {
                        if (headingScrolls) heading()
                        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            // T327: the four toggles, the Horizon view's own synced settings; at a chosen point Satellites and Flights disabled.
                            LayerToggles(layers, HorizonLayers.buttons(tracking), HorizonPlace.disabled(moved), moved, pal)
                            TerrainLine(h ?: work?.reason?.let { Gnss.HorizonState(pos!!.first, pos.second, null, it, null, null, 0) }, pos, units, pal)
                            // The sections in the Horizon view's order; a row's tap makes its thing the target and turns the view to it.
                            flights?.let { FlightsSection(it, fix != null, HorizonUi.selectedFlight, pal, units, help = "panorama.flights") }
                            if (layers.peaks) PeaksSection(h, peaks, selected, pal, units, help = "panorama.peaks", rangeM = rangeM)
                            if (layers.sun) SunSection(
                                pos != null, sunDay, sunNow, fn, pal, night, tick, help = "panorama.sun",
                                onNow = { PanoramaUi.apply(PanoramaTargets.SunTapped(sunNow?.let { HorizonText.sunUp(it.apparentAltitudeDeg) } == true)) },
                                nowChosen = target?.kind == TargetKind.SUN,
                            )
                            if (layers.satellites) SatellitesSection(
                                sats, plotted, summary, fn, pal, units, tick, bump = { clock.bump() },
                                onRow = { s -> PanoramaUi.apply(PanoramaTargets.SatelliteTapped(s.id, HorizonText.satelliteName(s))) },
                                chosen = target?.takeIf { it.kind == TargetKind.SATELLITE }?.id,
                            )
                            if (layers.on.isEmpty()) BasicText(HorizonText.NO_LAYERS, style = TextStyle(color = pal.muted, fontSize = 14.sp))
                        }
                    }
                }
            }
            // T331 (choice (h)): one quiet line under the plot while the compass reads weak in Follow.
            val hintLine: @Composable () -> Unit = {
                if (hint) BasicText(
                    CompassHint.LINE,
                    Modifier.width(plotW).padding(horizontal = 12.dp, vertical = 4.dp).testTag("panoramaCompassHint")
                        .onGloballyPositioned { c ->
                            hintH = c.size.height + with(density) { 8.dp.roundToPx() }
                            val at = c.positionInRoot()
                            PanoramaUi.hintPx = listOf(at.x, at.y, at.x + c.size.width, at.y + c.size.height).map { Math.round(it) }
                        },
                    style = TextStyle(color = pal.muted, fontSize = 13.sp),
                ) else PanoramaUi.hintPx = null
            }
            if (landscape) Row(Modifier.fillMaxSize()) {
                Column(Modifier.width(plotW).fillMaxHeight()) { plot(); hintLine() }
                pane(Modifier.width(paneW).fillMaxHeight().padding(top = 8.dp))
            } else Column(Modifier.fillMaxSize()) {
                plot()
                hintLine()
                pane(Modifier.weight(1f).fillMaxWidth())
            }
            // T331 (T326's note): the strip under the status bar over the plot carries the plot's sky on upward (it was paper).
            val topInset = androidx.compose.foundation.layout.WindowInsets.statusBars.getTop(density)
            if (topInset > 0) {
                val tints = remember(night) { PanoramaTints(night) }
                androidx.compose.foundation.Canvas(
                    Modifier.offset { androidx.compose.ui.unit.IntOffset(0, -topInset) }.size(plotW, with(density) { topInset.toDp() }).testTag("panoramaSkyStrip"),
                ) {
                    val a = PanoramaUi.aim
                    val (w, h, dp) = PanoramaUi.plotSize ?: Triple(size.width.toDouble(), 0.0, this.density.toDouble())
                    drawRect(skyBrush(a, tints, a?.lens(w, h, dp), w, h, dy = size.height))
                }
            }
            // T327: T311's flight card, as the Horizon view hosts it: over the pane's lower part, beside or under the plot.
            flight?.let { a ->
                FlightCardHost(
                    a,
                    (if (landscape) Modifier.align(Alignment.BottomEnd).width(paneW) else Modifier.align(Alignment.BottomCenter).fillMaxWidth())
                        .heightIn(max = (if (landscape) maxHeight else maxHeight - plotH) * 0.9f).padding(8.dp)
                        .padding(bottom = if (landscape) 0.dp else switchRoom - 8.dp),
                )
            }
            // T327: the target follows HorizonUi's picks, whatever changed them (a row, a name, the pick's ×, the card's Close, Back).
            val sel = HorizonUi.selected
            val selFlight = HorizonUi.selectedFlight
            LaunchedEffect(sel) { if (sel != PanoramaUi.targets.peak) PanoramaUi.apply(PanoramaTargets.PeakPicked(sel)) }
            LaunchedEffect(selFlight) {
                if (selFlight != PanoramaUi.targets.flight) PanoramaUi.apply(PanoramaTargets.FlightPicked(selFlight, flights?.list?.firstOrNull { it.hex == selFlight }?.label))
            }
            SideEffect {
                PanoramaUi.drawn = linkedMapOf(
                    "landscape" to landscape, "windowDp" to listOf(Math.round(maxWidth.value), Math.round(maxHeight.value)), "fontScale" to density.fontScale,
                    "plotDp" to listOf(Math.round(plotW.value), Math.round(plotH.value)), "paneDp" to Math.round(paneW.value),
                    "paneOverSwitch" to paneOverSwitch, "headingScrolls" to headingScrolls, "listRoomDp" to Math.round(listRoom.value),
                    "night" to night, "layers" to layers.json(), "selected" to picked?.peak?.name,
                    "buttons" to HorizonLayers.buttons(tracking).map { it.key }, "disabledButtons" to HorizonPlace.disabled(moved).map { it.key },
                    "flightCard" to (flight != null), "selectedFlight" to flight?.hex, "creditShown" to flights?.credit,
                    "point" to point?.let { p -> linkedMapOf("lat" to p.lat, "lon" to p.lon, "name" to p.name) }, "placeLine" to place.text,
                    "moved" to place.moved, "backShown" to moved, "rangeKm" to rangeM / 1000, "stepsWanted" to steps,
                    "scrollPx" to scroll.value, "scrollMaxPx" to scroll.maxValue,
                )
            }
        }
        // T327: a turn to the target asked for (a row, a name, the arrow, an opening with a kept pick), animated over 300 ms
        // or at once with the system's animations off; it waits (up to 10 s) for the target's direction and the aim.
        val turns = PanoramaUi.turnRequests
        val context = androidx.compose.ui.platform.LocalContext.current
        LaunchedEffect(turns) {
            if (turns == 0) return@LaunchedEffect
            val until = android.os.SystemClock.elapsedRealtime() + 10_000
            while ((PanoramaUi.targetDir == null || PanoramaUi.aim == null || PanoramaUi.plotSize == null) && android.os.SystemClock.elapsedRealtime() < until) kotlinx.coroutines.delay(100)
            val animate = android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
            PanoramaUi.runTurn(animate)
        }
        // The switch at its anchor, Panorama selected, over the content (T315's one placement).
        AnchoredViewSwitch(SkyView.PANORAMA, ViewSwitchColours(pal.card, pal.ink, pal.line), Modifier.testTag(ViewSwitch.TAG_ON_VIEW)) { r, _ ->
            PanoramaUi.switchPx = r.map { Math.round(it) }
        }
    }
}

/**
 * T330: Follow's effects while the Panorama is open: [PanoramaFollow.following] written; the attitude source started while
 * following and [started] (the activity in the foreground), stopped otherwise and when the view closes; leaving Follow sets
 * the aim back to Drag's limits at once (choice (e): the roll to zero, the tilt held, the bearing and the field of view
 * kept); the screen kept on while following and no longer (choice (a)).
 */
@Composable
private fun FollowEffects(follow: Boolean, started: Boolean) {
    androidx.compose.runtime.DisposableEffect(follow) {
        PanoramaFollow.following = follow
        if (!follow) PanoramaUi.aim?.takeIf { it.follow }?.let { PanoramaUi.aim = it.toDrag() }
        if (!follow) PanoramaFollow.correct(PanoramaCorrection.LeftFollow) // T331: choice (f)
        PanoramaFollow.evaluateHint()
        onDispose {
            PanoramaFollow.following = false
            PanoramaFollow.correct(PanoramaCorrection.Closed) // T331: the view closed (or left Follow)
            PanoramaFollow.evaluateHint()
        }
    }
    androidx.compose.runtime.DisposableEffect(follow, started) {
        if (follow && started) PanoramaFollow.start() else PanoramaFollow.stop()
        onDispose { PanoramaFollow.stop() }
    }
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(follow) {
        view.keepScreenOn = follow
        PanoramaUi.keepScreenOn = follow
        onDispose {
            view.keepScreenOn = false
            PanoramaUi.keepScreenOn = false
        }
    }
}

/** T326: the Panorama's help pop-ups (one paragraph each, no app named). */
internal val PANORAMA_HELP: List<HelpText> = run {
    val placed = "The map: the switch's ${ViewSwitch.PANORAMA}"
    val show: () -> Unit = { ViewSwitch.select(SkyView.PANORAMA) }
    listOf(
        HelpText("panorama.view", PanoramaStrings.TITLE, placed, show = show) { units ->
            // T331: shortened to make room for Follow phone's sentence (the help's 330-character cap).
            "The skyline one way, as through a lens, ridges to ${UnitFormat.distance(AppSettings.viewRangeKm * 1000.0, units)} " +
                "(your view range) far to near. Drag to turn and tilt; pinch or the buttons zoom. Tap a row or a name to turn to it; " +
                "an edge arrow points to it while out of view. With Follow phone on, point the phone; drag sideways to adjust the " +
                "compass, and Reset undoes it."
        },
        HelpText("panorama.peaks", HorizonText.PEAKS, "$placed, its ${HorizonText.PEAKS} list", show = show) { units ->
            "Named peaks within your view range of ${UnitFormat.distance(AppSettings.viewRangeKm * 1000.0, units)} in view or just out of " +
                "sight. The view names the highest on the skyline, then the closest; zoom in to name more. Tap a name or a row for its line; " +
                "${HorizonText.VIEW_FROM_HERE} stands the view on its top."
        },
        // T327
        HelpText("panorama.sun", HorizonText.SUN, "$placed, its ${HorizonText.SUN} section", show = show) { _ ->
            "Today's path of the sun through the lens, with the hours, dashed where the terrain hides it, and the sun where it is now. " +
                "Tap the line saying where it is now to turn the view to it while it is up."
        },
        HelpText("panorama.flights", HorizonText.FLIGHTS, "$placed, its ${HorizonText.FLIGHTS} list", show = show) { _ ->
            "Each flight in the air is a dot where it is in the sky, with a tail of its last 90 s; hollow when hidden by the terrain " +
                "or not heard lately, a ring for an emergency code. Tap a dot or a row for its card; the view turns to it."
        },
        // end T327
    )
}
