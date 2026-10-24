package io.github.jasondorjeshort.dystopo.app.controls

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.TopChips
import io.github.jasondorjeshort.dystopo.app.about.AttributionInsets
import io.github.jasondorjeshort.dystopo.app.routing.BottomPanels
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import io.github.jasondorjeshort.dystopo.render.ScaleLength
import io.github.jasondorjeshort.dystopo.render.UnitFormat
import kotlinx.coroutines.delay
import org.maplibre.android.maps.MapLibreMap

/**
 * T101: the scale bar's one option, "both units" (a two-sided ruler: the user's units above the line, the
 * other system below). Kept in the settings file (`scaleBar.both`); Compose state, so the bar and the Map options
 * row follow a change at once. Set by the Map options row and `POST /scalebar?both=on|off` ([ScaleBarRoutes]).
 * T110: synced like the other map choices (`app.scaleBar.both` through
 * [io.github.jasondorjeshort.dystopo.app.sync.SettingsSync]; it is no `MapOptions` key, so a change reloads no style).
 */
object ScaleBarSettings {
    private const val PREFS = "dystopo-settings" // AppSettings' file; the key is this object's own
    private const val KEY_BOTH = io.github.jasondorjeshort.dystopo.app.sync.SettingsSync.SCALE_BAR_BOTH

    private var both by mutableStateOf(false)
    @Volatile private var loaded = false

    /** Whether the bar shows both unit systems (reading this in a composable recomposes it on a change). */
    val bothUnits: Boolean
        get() {
            if (!loaded) load()
            return both
        }

    /** T110: creates the Compose state now; call on the main thread from `Application.onCreate` (sync reads it from other threads). */
    fun prepare() {
        if (!loaded) load()
    }

    /**
     * Main thread. [publish]: write the choice to the synced log (T110; not for a value a sync brought). The log keeps one
     * value per key and nothing is written when it already holds this one.
     */
    fun setBothUnits(on: Boolean, publish: Boolean = true) {
        if (!loaded) load()
        both = on
        runCatching { prefs().edit().putBoolean(KEY_BOTH, on).apply() }
        if (publish) {
            val app = DystopoApp.instance
            Thread({ io.github.jasondorjeshort.dystopo.app.sync.SettingsSync.publishLocal(app, KEY_BOTH) }, "scalebar-publish").start()
        }
    }

    private fun load() {
        loaded = true
        both = runCatching { prefs().getBoolean(KEY_BOTH, false) }.getOrDefault(false)
    }

    private fun prefs() = DystopoApp.instance.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * T101: what the scale bar shows and where, for `GET /scalebar` ([ScaleBarRoutes]). Written on the main thread
 * when the bar is laid out and drawn.
 */
object ScaleBarLayout {
    @Volatile var shown = false
    @Volatile var hiddenBecause: String? = "notComposed"
    @Volatile var zoom = Double.NaN
    @Volatile var lat = Double.NaN

    /**
     * T128 (T101's follow-up): the camera's tilt the bar was fitted at, the ground scale it used (metres per dp) and
     * where that scale was measured: `centre` (no tilt: the Web Mercator rule at the centre latitude, which is the
     * scale at every row) or `row` (tilted: MapLibre's projection along the ruler's own row, [rowPx], since under tilt
     * the scale grows towards the bottom of the screen and the centre row's length was wrong at the bar).
     */
    @Volatile var tilt = Double.NaN
    @Volatile var metersPerDp = Double.NaN
    @Volatile var measuredAt = "centre"

    /** T128: the ruler line's row in window pixels (where a tilted map's scale is measured); NaN until drawn. */
    @Volatile var rowPx = Float.NaN
    @Volatile var units: ContourUnits = ContourUnits.METERS
    @Volatile var night = false
    @Volatile var bars: List<ScaleLength> = emptyList()

    /** Each bar's length as drawn, in device pixels (tick centre to tick centre). */
    @Volatile var drawnPx: List<Float> = emptyList()
    @Volatile var boundsPx: FloatArray? = null
    @Volatile var windowHeightPx = 0f
    @Volatile var density = 1f

    /** T115: the bottom edge of the top-left column (search box, online indicator) the bar stays below, dp from the window top. */
    @Volatile var topLeftBottomDp = 0f

    /** T128: the navigation card's bottom edge the bar also stays below, dp from the window top (0 when not navigating). */
    @Volatile var navCardBottomDp = 0f

    /**
     * T110: a hidden bar keeps no fit, bounds or drawn lengths, so nothing reads the last shown bar against a camera that
     * has moved since (`GET /scalebar`'s `readBack` did, T101); the next show fits and draws them again.
     */
    fun hide(reason: String) {
        shown = false
        hiddenBecause = reason
        bars = emptyList()
        drawnPx = emptyList()
        boundsPx = null
        zoom = Double.NaN
        lat = Double.NaN
        tilt = Double.NaN
        metersPerDp = Double.NaN
    }

    /**
     * T110: whether the bar is shown and fitted at [zoom] and [lat] (the live camera), with every bar drawn. T128: and at
     * the live [tilt], measured along its row when tilted.
     */
    fun fittedAt(liveZoom: Double, liveLat: Double, liveTilt: Double = 0.0): Boolean =
        shown && boundsPx != null && bars.isNotEmpty() && drawnPx.size == bars.size &&
            Math.abs(zoom - liveZoom) < 1e-9 && Math.abs(lat - liveLat) < 1e-9 &&
            Math.abs((tilt.takeIf { it.isFinite() } ?: 0.0) - liveTilt) < 1e-6 &&
            (liveTilt < TILT_MIN_DEG || measuredAt == "row")

    fun json(): Map<String, Any?> {
        val b = boundsPx
        val d = density
        val h = windowHeightPx
        val credit = AttributionInsets.bottom.value
        val routeCard = BottomPanels.routeCardTop.value
        val panelsShown = BottomPanels.trackPanelsTop > BottomPanels.trackPanelsBottom
        val trackPanels = if (panelsShown) BottomPanels.trackPanelsTop.value else 0f
        val cards = io.github.jasondorjeshort.dystopo.app.MapInsets.bottomCardsTop.value // T106: the trip card and the update offer
        val bottomDp = if (b != null && h > 0) (h - b[3]) / d else null // the bar's bottom edge from the window bottom
        val visible = shown && b != null
        val drawn = drawnPx
        return linkedMapOf(
            "shown" to visible,
            "hiddenBecause" to if (shown) null else hiddenBecause,
            "bothUnits" to ScaleBarSettings.bothUnits,
            "units" to units.symbol,
            "night" to night,
            "zoom" to zoom.takeIf { it.isFinite() }?.let { round(it, 3) },
            "lat" to lat.takeIf { it.isFinite() }?.let { round(it, 5) },
            "maxWidthDp" to SCALE_BAR_MAX_DP,
            "metersPerDp" to metersPerDp.takeIf { it.isFinite() }?.let { round(it, 4) }, // T128: the scale the bars were fitted with
            "centreMetersPerDp" to if (zoom.isFinite()) round(UnitFormat.metersPerPixel(zoom, lat), 4) else null, // the Web Mercator rule at the centre
            "tilt" to tilt.takeIf { it.isFinite() }?.let { round(it, 2) },
            "measuredAt" to measuredAt, // T128: `centre` (no tilt) or `row` (the ruler's own row, `rowPx`)
            "rowPx" to rowPx.takeIf { it.isFinite() }?.let { Math.round(it) },
            "density" to d,
            "bars" to bars.mapIndexed { i, s ->
                linkedMapOf(
                    "label" to s.label, "value" to s.value, "unit" to s.unit.symbol, "meters" to round(s.meters, 3),
                    "widthDp" to round(s.px, 2), "widthPx" to round(s.px * d, 2), "drawnPx" to drawn.getOrNull(i)?.let { round(it.toDouble(), 2) },
                )
            },
            "boundsPx" to b?.map { Math.round(it) },
            "bottomFromWindowBottomDp" to bottomDp?.let { round(it.toDouble(), 1) },
            "creditTopDp" to credit, "routeCardTopDp" to routeCard, "trackPanelsTopDp" to trackPanels, "bottomCardsTopDp" to cards, // T106
            "topLeftBottomDp" to topLeftBottomDp, // T115: the search box and the online indicator, from the window top
            "navCardBottomDp" to navCardBottomDp, // T128: the navigation card, from the window top (0 when not navigating)
            "clearOfNavCard" to if (!visible || b == null) null else navCardBottomDp <= 0f || b[1] / d >= navCardBottomDp,
            "topDp" to b?.let { round((it[1] / d).toDouble(), 1) }, // T115: the bar's top edge, from the window top
            // Clear: the bar's bottom edge is above the credit line, the route card, the track panels, the trip card and the update offer.
            "clear" to if (!visible || bottomDp == null) null else bottomDp >= credit && bottomDp >= routeCard && bottomDp >= trackPanels && bottomDp >= cards,
        )
    }

    private fun round(v: Double, digits: Int): Double {
        var s = 1.0
        repeat(digits) { s *= 10 }
        return Math.round(v * s) / s
    }
}

/** The longest a bar may be, in dp; the nice-length rule makes it 40 to 100% of this. */
const val SCALE_BAR_MAX_DP = 100.0

private data class ScaleView(
    val zoom: Double, val lat: Double, val units: ContourUnits, val night: Boolean,
    /** T128: the camera's tilt, and the ground scale at the ruler's row when tilted (NaN: the centre rule). */
    val tilt: Double = 0.0, val rowMetersPerDp: Double = Double.NaN,
)

/** T128: below this tilt (degrees) the bar uses the Web Mercator rule at the centre, which every row then shares. */
const val TILT_MIN_DEG = 0.5

/**
 * T101: the scale bar, lower left on the map, above the credit line and above whatever bottom panel shows (the
 * route card, the track profile and waypoint panels: [BottomPanels]), so it moves up with them like the
 * recentre button on the right (T106: also above the trip card and the update offer, `MapInsets.bottomCardsTop`), but
 * never up to the search box and the online indicator at the top left (T115: it hides when no room is left below them,
 * `hiddenBecause: noRoomBelowTopLeft`, as the recentre button does below the top-right chips).
 * The length is [UnitFormat.scaleBar] at the camera's zoom and centre latitude in the units preference;
 * with [ScaleBarSettings.bothUnits] it is a two-sided ruler, the user's units above the line and the other
 * system below. Follows the camera during gestures (a camera-move listener) and the units, the night style
 * and the option within half a second. Dark ink on paper, light ink on a dark plate in the night style.
 */
@Composable
fun ScaleBar() {
    val app = DystopoApp.instance
    var view by remember { mutableStateOf<ScaleView?>(null) }
    // T110: out of the composition (the no-pack line shows instead) it says so, rather than keeping the last bar as shown.
    DisposableEffect(Unit) { onDispose { ScaleBarLayout.hide("notComposed") } }
    LaunchedEffect(Unit) {
        var attached: MapLibreMap? = null
        val listener = MapLibreMap.OnCameraMoveListener { attached?.let(::read)?.let { view = it } }
        try {
            while (true) {
                // The map instance arrives after the first composition and changes when the activity is recreated.
                val m = app.map.map
                if (m !== attached) {
                    attached?.removeOnCameraMoveListener(listener)
                    m?.addOnCameraMoveListener(listener)
                    attached = m
                }
                // Also catches camera jumps without move callbacks and changes of units or the night style.
                m?.let(::read)?.let { view = it }
                delay(500)
            }
        } finally {
            attached?.removeOnCameraMoveListener(listener)
        }
    }
    val v = view
    if (v == null) {
        SideEffect { ScaleBarLayout.hide("noCamera") }
        return
    }
    val both = ScaleBarSettings.bothUnits
    val atRow = v.rowMetersPerDp.isFinite() // T128: tilted, measured along the ruler's own row
    val mppDp = if (atRow) v.rowMetersPerDp else UnitFormat.metersPerPixel(v.zoom, v.lat)
    val primary = UnitFormat.scaleBar(mppDp, SCALE_BAR_MAX_DP, v.units)
    val other = if (both) UnitFormat.scaleBar(mppDp, SCALE_BAR_MAX_DP, if (v.units == ContourUnits.FEET) ContourUnits.METERS else ContourUnits.FEET) else null
    if (primary == null) {
        SideEffect { ScaleBarLayout.hide("noScale") }
        return
    }
    val bars = listOfNotNull(primary, other)
    val ink = if (v.night) NightInk else Ink
    val plate = if (v.night) NightPlate else Plate
    val panels = if (BottomPanels.trackPanelsTop > BottomPanels.trackPanelsBottom) BottomPanels.trackPanelsTop else 0.dp
    val floor = maxOf(AttributionInsets.bottom, BottomPanels.routeCardTop, panels, io.github.jasondorjeshort.dystopo.app.MapInsets.bottomCardsTop) // T106
    val density = LocalDensity.current
    val labelStyle = TextStyle(color = ink, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    val widest = bars.maxOf { it.px }
    SideEffect {
        ScaleBarLayout.zoom = v.zoom
        ScaleBarLayout.lat = v.lat
        ScaleBarLayout.tilt = v.tilt
        ScaleBarLayout.metersPerDp = mppDp
        ScaleBarLayout.measuredAt = if (atRow) "row" else "centre"
        ScaleBarLayout.units = v.units
        ScaleBarLayout.night = v.night
        ScaleBarLayout.bars = bars
    }
    // T115 (T110's follow-up): the recentre button's rule on the left. A tall bottom panel (the route card with its
    // directions open at font scale 2.0) pushed the bar up to about 70 px under the online indicator; it now never
    // reaches up to the top-left column (the search box and the online indicator): with no room left it hides.
    val topLeftColumn = with(density) { maxOf(TopChips.searchBottom, TopChips.onlineBottomPx.toDp()) }
    // T128 (T122's follow-up): and below the navigation card, which spans the top of the map. Above the card's 1.5 font
    // scale cap a tall bottom panel pushed the bar up over the card, since only the top-left column bounded it.
    val navCard = TopChips.navCardBottom
    val topLeft = maxOf(topLeftColumn, navCard)
    Layout(
        content = { Column(
            Modifier
                .let { m -> if (floor > 0.dp) m.padding(start = 8.dp, bottom = floor + 6.dp) else m.navigationBarsPadding().padding(start = 8.dp, bottom = 32.dp) }
                .onGloballyPositioned { c ->
                    val b = c.boundsInRoot()
                    var r = c
                    while (true) r = r.parentLayoutCoordinates ?: break
                    ScaleBarLayout.boundsPx = floatArrayOf(b.left, b.top, b.right, b.bottom)
                    ScaleBarLayout.windowHeightPx = r.size.height.toFloat()
                    ScaleBarLayout.density = density.density
                    ScaleBarLayout.shown = true
                    ScaleBarLayout.hiddenBecause = null
                }
                .background(plate, RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 3.dp)
                .semantics { contentDescription = "Scale " + bars.joinToString(" and ") { it.label } },
        ) {
            BasicText(primary.label, style = labelStyle)
            // The ruler: a line from 0 to the longer bar, a tick at 0 across both sides, the user's length ticked
            // upward and, with both units, the other length ticked downward.
            Canvas(Modifier.width((widest + STROKE_DP).dp).height(if (other != null) 14.dp else 8.dp).onGloballyPositioned { c ->
                // T128: the ruler line's row in the window, where a tilted map's scale is measured (`read`)
                val h = c.size.height.toFloat()
                val strokePx = STROKE_DP.toFloat() * density.density
                ScaleBarLayout.rowPx = c.boundsInRoot().top + if (other != null) h / 2 else h - strokePx / 2
            }) {
                val stroke = STROKE_DP.dp.toPx()
                val x0 = stroke / 2
                val lens = bars.map { it.px.dp.toPx() }
                val y = if (other != null) size.height / 2 else size.height - stroke / 2
                val top = stroke / 2
                val bottom = size.height - stroke / 2
                drawLine(ink, Offset(x0, y), Offset(x0 + lens.max(), y), stroke, StrokeCap.Square)
                drawLine(ink, Offset(x0, top), Offset(x0, if (other != null) bottom else y), stroke, StrokeCap.Square)
                drawLine(ink, Offset(x0 + lens[0], top), Offset(x0 + lens[0], y), stroke, StrokeCap.Square)
                if (other != null) drawLine(ink, Offset(x0 + lens[1], y), Offset(x0 + lens[1], bottom), stroke, StrokeCap.Square)
                ScaleBarLayout.drawnPx = lens
            }
            if (other != null) BasicText(other.label, style = labelStyle)
        } },
        modifier = Modifier.fillMaxSize(),
    ) { measurables, constraints ->
        val bar = measurables.first().measure(constraints.copy(minWidth = 0, minHeight = 0))
        val top = constraints.maxHeight - bar.height // the bar's top edge (its padding is below and to its left)
        val room = topLeft <= 0.dp || top >= (topLeft + TOP_LEFT_GAP).roundToPx()
        ScaleBarLayout.topLeftBottomDp = topLeftColumn.value
        ScaleBarLayout.navCardBottomDp = navCard.value
        if (!room) ScaleBarLayout.hide(if (navCard > topLeftColumn) NO_ROOM_BELOW_NAV_CARD else NO_ROOM_BELOW_TOP_LEFT)
        else if (ScaleBarLayout.bars.isEmpty()) { // shown again by a layout pass alone: the fit hide() dropped comes back
            ScaleBarLayout.zoom = v.zoom
            ScaleBarLayout.lat = v.lat
            ScaleBarLayout.tilt = v.tilt
            ScaleBarLayout.metersPerDp = mppDp
            ScaleBarLayout.bars = bars
        }
        layout(constraints.maxWidth, constraints.maxHeight) { if (room) bar.place(0, top) }
    }
}

/** T115: why the bar is hidden when it would reach up to the top-left column (`GET /scalebar` `hiddenBecause`). */
const val NO_ROOM_BELOW_TOP_LEFT = "noRoomBelowTopLeft"

/** T128: why the bar is hidden when it would reach up to the navigation card. */
const val NO_ROOM_BELOW_NAV_CARD = "noRoomBelowNavCard"

/** T115: the least room kept between the bar and the top-left column (the recentre button keeps 8 dp below the chips). */
private val TOP_LEFT_GAP = 8.dp

private fun read(m: MapLibreMap): ScaleView? {
    val c = m.cameraPosition
    val target = c.target ?: return null
    val tilt = c.tilt
    val row = if (tilt >= TILT_MIN_DEG) rowMetersPerDp(m, ScaleBarLayout.rowPx) else Double.NaN
    return ScaleView(c.zoom, target.latitude, AppSettings.units, AppSettings.mapOptions.night, tilt, row)
}

/**
 * T128: the ground scale (metres per dp) along the screen row [rowPx] (window pixels; the map view fills the window's
 * content as the Compose root does), from MapLibre's projection: the ground distance between two points
 * [ROW_SPAN_DP] apart on that row from the bar's left edge. A tilted camera keeps a screen row at one depth, so the
 * scale is the same along it. NaN before the ruler was drawn or when the row is off the map. Main thread.
 */
internal fun rowMetersPerDp(m: MapLibreMap, rowPx: Float): Double {
    if (!rowPx.isFinite()) return Double.NaN
    val density = DystopoApp.instance.resources.displayMetrics.density
    val x0 = 8f * density
    val span = ROW_SPAN_DP * density
    val mpd = runCatching {
        val a = m.projection.fromScreenLocation(android.graphics.PointF(x0, rowPx))
        val b = m.projection.fromScreenLocation(android.graphics.PointF(x0 + span, rowPx))
        a.distanceTo(b) / ROW_SPAN_DP
    }.getOrDefault(Double.NaN)
    return if (mpd.isFinite() && mpd > 0) mpd else Double.NaN
}

private const val ROW_SPAN_DP = 100f

private const val STROKE_DP = 2.0

private val Ink = Color(0xFF2B2420)
private val Plate = Color(0xCCF6F1E4) // the credit line's paper
private val NightInk = Color(0xFFEDE6DA)
private val NightPlate = Color(0xB3201C1A)
