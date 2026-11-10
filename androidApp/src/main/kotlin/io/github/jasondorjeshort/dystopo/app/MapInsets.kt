package io.github.jasondorjeshort.dystopo.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * T57: where Compose overlays cover the top of the map, so camera fits that frame something above a sheet
 * (the catalog outlines, [io.github.jasondorjeshort.dystopo.app.packs.fitAbove]) keep it clear of them and
 * `/packs/catalog` can count catalog labels under them. Pixels in the map view's coordinates (both fill the
 * activity's content area). Written on the main thread.
 */
object MapInsets {
    /** The online indicator column at the top left (status chip, Download this region, credit line); null when hidden. */
    @Volatile var onlineChips: FloatArray? = null
        private set

    fun onlineChipsAt(c: LayoutCoordinates) {
        val b = c.boundsInRoot()
        onlineChips = floatArrayOf(b.left, b.top, b.right, b.bottom)
        if (TopChips.onlineRightPx != b.right) TopChips.onlineRightPx = b.right // T91: the track chips wrap before it
        if (TopChips.onlineBottomPx != b.bottom) TopChips.onlineBottomPx = b.bottom // T115: the scale bar stays below it
    }

    fun onlineChipsHidden() {
        onlineChips = null
        if (TopChips.onlineRightPx != 0f) TopChips.onlineRightPx = 0f
        if (TopChips.onlineBottomPx != 0f) TopChips.onlineBottomPx = 0f
    }

    /** True when ([x], [y]) lies in [r] (`left, top, right, bottom`) grown by [marginPx]. */
    fun inside(r: FloatArray?, x: Float, y: Float, marginPx: Float = 0f): Boolean =
        r != null && x >= r[0] - marginPx && x <= r[2] + marginPx && y >= r[1] - marginPx && y <= r[3] + marginPx

    /**
     * T106 (T93's follow-ups): the top edges, in dp from the window's bottom, of the bottom cards that span the map's
     * width: the trip card (`trips/TripControls.kt`) and the update offer (`update/UpdateOverlay.kt`); 0 while hidden.
     * The recentre button rides above them ([bottomCardsTop]) as it does above the route card and the track panels;
     * it hid under the trip card and shared its corner with the update offer in release builds. Compose state.
     */
    var tripCardTop by mutableStateOf(0.dp)
    var updateCardTop by mutableStateOf(0.dp)

    /** T106: the highest of those edges (0 when none shows). */
    val bottomCardsTop: Dp get() = maxOf(tripCardTop, updateCardTop)

    /** T106: the distance of [c]'s top edge from the bottom of the window. */
    fun topFromWindowBottom(c: LayoutCoordinates, density: Density): Dp {
        var r = c
        while (true) r = r.parentLayoutCoordinates ?: break
        return with(density) { (r.size.height - c.positionInRoot().y).toDp() }
    }

    /**
     * T122: the lowest bottom edge, in px from the window top, of what floats across the top of the map: the search
     * column, the online indicator, and at the right the track controls, the Routes chip and the navigation card
     * ([TopChips.topRightBottom]); 0 before any of them was measured. Main thread (Compose state).
     */
    fun topChromeBottomPx(density: Float): Int =
        maxOf(TopChips.topRightBottom.value * density, TopChips.searchBottom.value * density, TopChips.onlineBottomPx).toInt()

    fun json(): Map<String, Any?> = linkedMapOf("tripCardTopDp" to tripCardTop.value, "updateCardTopDp" to updateCardTop.value)
}

/**
 * T122: the padding of a camera fit that frames a route or a trip between the chrome at the top of the map and a card at
 * the bottom (pure, `FitPaddingTest`). Px, in MapLibre's order: left, top, right, bottom. The top clears [chromeBottomPx]
 * (the fixed 96 dp of T49 sat under the Routes chip and the open tracks list) and the bottom clears [cardTopPx] (from the
 * bottom of the map), each by [MARGIN_DP]; with no chrome measured yet the top is [TOP_FALLBACK_DP]. When the two leave
 * less than [MIN_FRAME_DP] between them (font scale 2.0, a tall list open) the top gives way first, down to the status
 * bar's [MIN_TOP_DP], then the bottom, so the route is never fitted into a sliver or past the map.
 */
object FitPadding {
    const val SIDE_DP = 40
    const val MARGIN_DP = 16
    const val TOP_FALLBACK_DP = 96
    const val MIN_TOP_DP = 40
    const val MIN_FRAME_DP = 160

    fun of(chromeBottomPx: Int, cardTopPx: Int, mapHeightPx: Int, density: Float): IntArray {
        fun px(dp: Int) = (dp * density).toInt()
        val side = px(SIDE_DP)
        var top = if (chromeBottomPx > 0) chromeBottomPx + px(MARGIN_DP) else px(TOP_FALLBACK_DP)
        var bottom = cardTopPx + px(MARGIN_DP)
        if (mapHeightPx > 0) {
            val frame = px(MIN_FRAME_DP)
            if (mapHeightPx - top - bottom < frame) top = maxOf(px(MIN_TOP_DP), mapHeightPx - bottom - frame)
            if (mapHeightPx - top - bottom < frame) bottom = maxOf(0, mapHeightPx - top - frame)
        }
        return intArrayOf(side, top, side, bottom)
    }
}

/**
 * T265: the one camera fit of the route card, the trip editor and the track card: the whole line between the chrome at the
 * top and a card at the bottom ([FitPadding]), the short way round in longitude ([io.github.jasondorjeshort.dystopo.gpx.TrackDrawing.shortWayBox]),
 * so a line across the antimeridian is framed over the ocean it crosses rather than over the continents it left (T256's
 * follow-up: the view sat over another continent). MapLibre takes a box whose east edge is past 180. The fit's padding
 * is dropped from the camera when it lands (T114), so later moves centre on the screen. Main thread.
 */
object CameraFit {
    /**
     * Fits [points] (lat, lon) above a card whose top is [cardTopDp] from the window's bottom, over [ms]; [onPaddingDropped]
     * runs when the padding is dropped. The fit's report (`bounds` west, south, east, north with east past 180 the short
     * way across the seam, `shortWay`, `paddingPx`, `chromeBottomPx`, `mapHeightPx`, `clearOfChrome`), or null with no
     * map or no point.
     */
    fun fit(points: List<Pair<Double, Double>>, cardTopDp: Float, ms: Int, onPaddingDropped: () -> Unit = {}): Map<String, Any?>? {
        val app = DystopoApp.instance
        val m = app.map.map ?: return null
        val box = io.github.jasondorjeshort.dystopo.gpx.TrackDrawing.shortWayBox(points) ?: return null
        val (w, s, e, n) = box.toList()
        val d = app.resources.displayMetrics.density
        val chrome = MapInsets.topChromeBottomPx(d)
        val cardPx = (cardTopDp * d).toInt()
        val mapH = app.map.mapView?.height ?: 0
        val pad = FitPadding.of(chrome, cardPx, mapH, d)
        val update = if (n - s < 1e-6 && e - w < 1e-6) org.maplibre.android.camera.CameraUpdateFactory.newLatLng(org.maplibre.android.geometry.LatLng(s, normalize(w)))
        else org.maplibre.android.camera.CameraUpdateFactory.newLatLngBounds(org.maplibre.android.geometry.LatLngBounds.from(n, e, s, w), pad[0], pad[1], pad[2], pad[3])
        m.animateCamera(update, ms, object : org.maplibre.android.maps.MapLibreMap.CancelableCallback {
            override fun onCancel() { if (dropPadding(m)) onPaddingDropped() }
            override fun onFinish() { if (dropPadding(m)) onPaddingDropped() }
        })
        return linkedMapOf(
            "bounds" to listOf(w, s, e, n), "shortWay" to (e > 180.0), "paddingPx" to pad.toList(),
            "chromeBottomPx" to chrome, "mapHeightPx" to mapH, "clearOfChrome" to (pad[1] >= chrome),
        )
    }

    /** The same view without a fit's padding (MapLibre keeps it on the camera otherwise); true when there was one. */
    fun dropPadding(m: org.maplibre.android.maps.MapLibreMap): Boolean {
        val c = m.cameraPosition
        if (c.padding?.any { it != 0.0 } != true) return false
        val v = DystopoApp.instance.map.mapView ?: return false
        val centre = m.projection.fromScreenLocation(android.graphics.PointF(v.width / 2f, v.height / 2f))
        m.moveCamera(org.maplibre.android.camera.CameraUpdateFactory.newCameraPosition(org.maplibre.android.camera.CameraPosition.Builder(c).target(centre).padding(0.0, 0.0, 0.0, 0.0).build()))
        return true
    }

    private fun normalize(lon: Double): Double = ((lon + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
}
