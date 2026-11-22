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
    const val NO_PEAKS = "Peaks are off: the view shows the land's skyline only."

    /** The plot's spoken label after the place line: "Facing NNW 347°, 60° across". */
    fun facing(bearingDeg: Double, fovDeg: Double): String =
        "Facing ${GnssText.compassPoint(bearingDeg)} ${Math.round(bearingDeg) % 360}°, ${Math.round(fovDeg)}° across"
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
    // The compass for the opening's bearing (choice c); no GNSS hold and no flights' poll: those layers are T327's.
    val clock = SkyViewEffects(SkyViewHolds(compass = true, satellites = false, flightsAt = null, skyRange = AppSettings.skyRange), night)
    @Suppress("UNUSED_VARIABLE") val tick = clock.tick
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
    Box(Modifier.fillMaxSize().background(pal.paper).pointerInput(Unit) { detectTapGestures { } }.testTag("panoramaView")) {
        BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().displayCutoutPadding()) {
            val landscape = maxWidth > maxHeight
            val switchW = ViewSwitch.widthDp.dp
            val switchRoom = ViewSwitch.reserveDp.dp + 8.dp
            val paneW = if (landscape) maxOf(320.dp, maxWidth * 0.4f).coerceAtMost(maxWidth * 0.6f) else maxWidth
            val plotW = if (landscape) maxWidth - paneW else maxWidth
            val plotH = if (landscape) maxHeight - switchRoom else minOf(maxWidth * 0.75f, maxHeight * 0.45f)
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
            val listRoom = with(density) { (paneH - placeH - headingH - (if (picked != null) pickedH else 0)).toDp() }
            val headingScrolls = paneH > 0 && listRoom < 96.dp
            PanoramaUi.headingScrolls = headingScrolls
            val plot: @Composable () -> Unit = {
                PanoramaPlot(plotW, plotH, pal, night, work, peaks?.ranked.orEmpty(), pickedLabel, place.text, wanted = steps)
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
                            LayerToggles(layers, listOf(HorizonLayer.PEAKS), emptySet(), false, pal)
                            TerrainLine(h ?: work?.reason?.let { Gnss.HorizonState(pos!!.first, pos.second, null, it, null, null, 0) }, pos, units, pal)
                            if (layers.peaks) PeaksSection(h, peaks, selected, pal, units, help = "panorama.peaks", rangeM = rangeM)
                            else BasicText(PanoramaStrings.NO_PEAKS, style = TextStyle(color = pal.muted, fontSize = 14.sp))
                        }
                    }
                }
            }
            if (landscape) Row(Modifier.fillMaxSize()) {
                Column(Modifier.width(plotW).fillMaxHeight()) { plot() }
                pane(Modifier.width(paneW).fillMaxHeight().padding(top = 8.dp))
            } else Column(Modifier.fillMaxSize()) {
                plot()
                pane(Modifier.weight(1f).fillMaxWidth())
            }
            SideEffect {
                PanoramaUi.drawn = linkedMapOf(
                    "landscape" to landscape, "windowDp" to listOf(Math.round(maxWidth.value), Math.round(maxHeight.value)), "fontScale" to density.fontScale,
                    "plotDp" to listOf(Math.round(plotW.value), Math.round(plotH.value)), "paneDp" to Math.round(paneW.value),
                    "paneOverSwitch" to paneOverSwitch, "headingScrolls" to headingScrolls, "listRoomDp" to Math.round(listRoom.value),
                    "night" to night, "layers" to layers.json(), "selected" to picked?.peak?.name,
                    "point" to point?.let { p -> linkedMapOf("lat" to p.lat, "lon" to p.lon, "name" to p.name) }, "placeLine" to place.text,
                    "moved" to place.moved, "backShown" to moved, "rangeKm" to rangeM / 1000, "stepsWanted" to steps,
                    "scrollPx" to scroll.value, "scrollMaxPx" to scroll.maxValue,
                )
            }
        }
        // The switch at its anchor, Panorama selected, over the content (T315's one placement).
        AnchoredViewSwitch(SkyView.PANORAMA, ViewSwitchColours(pal.card, pal.ink, pal.line), Modifier.testTag(ViewSwitch.TAG_ON_VIEW)) { r, _ ->
            PanoramaUi.switchPx = r.map { Math.round(it) }
        }
    }
}

/** T326: the Panorama's help pop-ups (one paragraph each, no app named). */
internal val PANORAMA_HELP: List<HelpText> = run {
    val placed = "The map: the switch's ${ViewSwitch.PANORAMA}"
    val show: () -> Unit = { ViewSwitch.select(SkyView.PANORAMA) }
    listOf(
        HelpText("panorama.view", PanoramaStrings.TITLE, placed, show = show) { units ->
            "The land's skyline one way, as through a lens: the ridges to ${UnitFormat.distance(AppSettings.viewRangeKm * 1000.0, units)} " +
                "(your view range) drawn far to near, the level line dashed and the bearings along the top. Drag sideways to turn, " +
                "up and down to tilt; pinch or the buttons zoom."
        },
        HelpText("panorama.peaks", HorizonText.PEAKS, "$placed, its ${HorizonText.PEAKS} list", show = show) { units ->
            "Named peaks within your view range of ${UnitFormat.distance(AppSettings.viewRangeKm * 1000.0, units)} in view or just out of " +
                "sight. The view names the highest on the skyline, then the closest; zoom in to name more. Tap a name or a row for its line; " +
                "${HorizonText.VIEW_FROM_HERE} stands the view on its top."
        },
    )
}
