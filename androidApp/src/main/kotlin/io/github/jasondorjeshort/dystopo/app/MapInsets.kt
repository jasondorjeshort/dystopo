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
