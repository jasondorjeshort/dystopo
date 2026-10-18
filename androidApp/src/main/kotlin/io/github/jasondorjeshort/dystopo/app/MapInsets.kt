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

    fun json(): Map<String, Any?> = linkedMapOf("tripCardTopDp" to tripCardTop.value, "updateCardTopDp" to updateCardTop.value)
}
