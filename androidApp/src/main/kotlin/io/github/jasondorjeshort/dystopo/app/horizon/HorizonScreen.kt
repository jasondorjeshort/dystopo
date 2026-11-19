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
import io.github.jasondorjeshort.dystopo.horizon.HorizonLayers
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

    /** Makes the Compose state on the main thread (`MainActivity.onCreate`), before a debug route's thread reads it (T226). */
    fun prepare() {
        open; selected; date; scrollTo
    }

    fun open() {
        GnssUi.resetUi()
        selected = null
        open = true
    }

    fun close() {
        open = false
        selected = null
        scrollTo = null
        GnssUi.resetUi()
        drawn = null
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
    val layers = AppSettings.horizonLayers
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
            HorizonUi.selected != null -> HorizonUi.selected = null
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
    val pos = Gnss.position()
    val h = Gnss.horizonAtPhone()
    val horizon = h?.profile
    val fn = horizon?.let { p -> { az: Double -> p.angleAt(az) } }
    val sats = if (layers.satellites) Gnss.satellites else emptyList()
    val plotted = plotSats(sats)
    val summary = GnssHealth.summarize(sats)
    val heading = GnssUi.headingDeg()
    val rotation = GnssUi.rotationDeg(orientation)
    val now = System.currentTimeMillis()
    val sunNow = pos?.let { io.github.jasondorjeshort.dystopo.nav.Sun.position(it.latitude, it.longitude, now) }
    val sunDay = if (layers.sun && pos != null) HorizonData.sunFor(h?.takeIf { it.profile != null }, pos.latitude, pos.longitude, HorizonUi.date, now) else null
    val peaks = if (layers.peaks && h != null) HorizonData.peaksFor(h) else null
    val selected = HorizonUi.selected
    val picked = peaks?.skyline?.firstOrNull { HorizonUi.keyOf(it) == selected }
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
            val side = minOf(maxWidth, maxHeight)
            val sidePx = with(density) { side.toPx() }
            val sunDrawn = layers.sun && sunNow != null && sunNow.apparentAltitudeDeg >= -0.5
            val description = HorizonText.plotLabel(
                listOfNotNull(
                    horizon?.let { p -> GnssText.horizonLine(p.angleDeg[p.highest], p.bearingDeg(p.highest), p.distanceM[p.highest], h.source, units) },
                    peaks?.labelled?.takeIf { it.isNotEmpty() }?.let { l -> "${HorizonText.PEAKS}: " + l.joinToString(", ") { it.peak.name } },
                    sunNow?.takeIf { layers.sun }?.let { s -> HorizonText.sunNow(s.apparentAltitudeDeg, s.azimuthDeg, fn?.let { f -> s.apparentAltitudeDeg < f(s.azimuthDeg) } ?: false) },
                    if (layers.satellites) "${GnssText.SATELLITES}: ${GnssText.usedOf(summary.used, summary.inView)}" else null,
                ),
            )
            val plot: @Composable () -> Unit = {
                Box(Modifier.size(side)) {
                    SkyPlot(
                        plotted, horizon, rotation, sunNow?.azimuthDeg?.takeIf { layers.sun }, sunNow?.apparentAltitudeDeg?.takeIf { layers.sun }, pal, description,
                        Modifier.fillMaxSize().pointerInput(peaks, rotation, sidePx) {
                            detectTapGestures { tap ->
                                val g = PlotGeometry.of(sidePx, sidePx, density.density, rotation)
                                val near = peaks?.labelled?.minByOrNull { p -> labelAnchor(g, p, peaks.labelled.indexOf(p), density.density).let { a -> hypot((a.x - tap.x).toDouble(), (a.y - tap.y).toDouble()) } }
                                val d = near?.let { p -> labelAnchor(g, p, peaks.labelled.indexOf(p), density.density).let { a -> hypot((a.x - tap.x).toDouble(), (a.y - tap.y).toDouble()) } }
                                HorizonUi.selected = if (near != null && d != null && d < 40 * density.density) HorizonUi.keyOf(near).takeIf { it != HorizonUi.selected } else null
                            }
                        },
                    ) { g ->
                        sunDay?.let { drawSunPath(g, it, pal, night) }
                        peaks?.labelled?.let { drawPeaks(g, it, selected, pal) }
                    }
                }
            }
            val pane: @Composable (Modifier) -> Unit = { m ->
                Column(m) {
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
                        Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Toggles(layers, pal)
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
            SideEffect {
                GnssUi.noteDrawn(plotted, fn, rotation, orientation, heading, horizon != null, sunDrawn, night, layers.satellites)
                HorizonUi.drawn = linkedMapOf(
                    "layers" to layers.json(), "landscape" to landscape, "squareDp" to Math.round(side.value),
                    "windowDp" to listOf(Math.round(maxWidth.value), Math.round(maxHeight.value)), "fontScale" to density.fontScale,
                    "rotationDeg" to Math.round(rotation * 10) / 10.0, "orientation" to orientation.key, "night" to night,
                    "horizonDrawn" to (horizon != null), "satellitesDrawn" to plotted.size, "gnssHeld" to Gnss.held,
                    "peaksDrawn" to (peaks?.labelled?.map { it.peak.name } ?: emptyList<String>()), "peaksListed" to (peaks?.skyline?.size ?: 0),
                    "sunPathDrawn" to (sunDay?.sun?.path?.size ?: 0), "sunHourMarks" to (sunDay?.marks?.size ?: 0), "sunNowDrawn" to sunDrawn,
                    "selected" to picked?.peak?.name, "scrollPx" to scroll.value, "scrollMaxPx" to scroll.maxValue,
                )
            }
        }
    }
}

/** Where a rim name is drawn: inward from its peak, every other one a line farther in, so neighbours do not touch. */
private fun labelAnchor(g: PlotGeometry, p: PeakView, index: Int, density: Float): Offset {
    val at = g.at(p.angleDeg.coerceAtLeast(0.0), p.bearingDeg)
    val dx = g.c.x - at.x
    val dy = g.c.y - at.y
    val len = hypot(dx.toDouble(), dy.toDouble()).toFloat().coerceAtLeast(1f)
    val inward = (16 + (index % 2) * 15) * density
    return Offset(at.x + dx / len * inward, at.y + dy / len * inward)
}

private fun DrawScope.drawPeaks(g: PlotGeometry, labelled: List<PeakView>, selected: String?, pal: GnssPalette) {
    val text = android.graphics.Paint().apply { isAntiAlias = true; textSize = 12.dp.toPx(); color = pal.ink.toArgb(); textAlign = android.graphics.Paint.Align.CENTER }
    val halo = android.graphics.Paint(text).apply { style = android.graphics.Paint.Style.STROKE; strokeWidth = 3.dp.toPx(); color = pal.sky.toArgb() }
    labelled.forEachIndexed { i, p ->
        val at = g.at(p.angleDeg.coerceAtLeast(0.0), p.bearingDeg)
        val s = 5.dp.toPx()
        val tri = Path().apply { moveTo(at.x, at.y - s); lineTo(at.x + s, at.y + s * 0.7f); lineTo(at.x - s, at.y + s * 0.7f); close() }
        val chosen = HorizonUi.keyOf(p) == selected
        drawPath(tri, if (chosen) pal.link else pal.ink)
        val a = labelAnchor(g, p, i, density)
        text.isFakeBoldText = chosen
        halo.isFakeBoldText = chosen
        text.color = (if (chosen) pal.link else pal.ink).toArgb()
        val y = a.y + 4.dp.toPx()
        drawContext.canvas.nativeCanvas.drawText(p.peak.name, a.x, y, halo)
        drawContext.canvas.nativeCanvas.drawText(p.peak.name, a.x, y, text)
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

/** The layer toggles: one row of toggle buttons (the owner's words), wrapping at large text. */
@Composable
private fun Toggles(layers: HorizonLayers, pal: GnssPalette) {
    @OptIn(ExperimentalLayoutApi::class)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
            BasicText("${HorizonText.LAYERS}:", style = TextStyle(color = pal.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium))
        }
        for (l in HorizonLayer.entries) {
            val on = layers[l]
            Box(
                Modifier.heightIn(min = 48.dp).semantics { this.selected = on }.clickable(role = Role.Switch) { HorizonUi.setLayer(l, !on) },
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    (if (on) "✓ " else "") + l.label,
                    style = TextStyle(color = if (on) pal.paper else pal.ink, fontSize = 14.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium),
                    modifier = Modifier.background(if (on) pal.ink else pal.card, RoundedCornerShape(16.dp)).border(1.dp, if (on) pal.ink else pal.line, RoundedCornerShape(16.dp))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
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
        BasicText(HorizonText.peakLine(p.peak.name, p.eleM, p.distanceM, p.bearingDeg, units), Modifier.weight(1f), style = TextStyle(color = pal.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
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

/** The peaks on the skyline: how many, then each (name, height, distance, bearing) clockwise from north; a tap picks one. */
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
            else -> HorizonText.peaksCount(peaks.skyline.size, peaks.labelled.size, units)
        }
        BasicText(line, style = TextStyle(color = pal.ink, fontSize = 14.sp))
        val named = peaks?.labelled?.map { HorizonUi.keyOf(it) }?.toSet() ?: emptySet()
        for (p in peaks?.skyline.orEmpty()) {
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
                    "${UnitFormat.elevation(p.eleM, units)} · ${UnitFormat.distance(p.distanceM, units)} · ${GnssText.compassPoint(p.bearingDeg)} ${Math.round(p.bearingDeg) % 360}°",
                    style = TextStyle(color = pal.muted, fontSize = 13.sp),
                )
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
                "within ${HorizonText.range(units)}. Show turns the layers on and off: the satellites, the peaks on the skyline and the sun's path."
        },
        HelpText("horizon.peaks", HorizonText.PEAKS, "$placed › ${HorizonText.PEAKS}", show = show) { units ->
            "The named peaks of your offline map that stand on the skyline within ${HorizonText.range(units)}. The plot names the highest in each " +
                "30 degrees of the rim; the list has them all. Tap a name or a row for its height, distance and bearing."
        },
        HelpText("horizon.sun", HorizonText.SUN, "$placed › ${HorizonText.SUN}", show = show) { _ ->
            "Today's path of the sun, with the hours, dashed where the terrain hides it. The times are when the top of the " +
                "sun clears the terrain and goes behind it, as the turn-back time uses; the flat horizon's are for comparison."
        },
    )
}
