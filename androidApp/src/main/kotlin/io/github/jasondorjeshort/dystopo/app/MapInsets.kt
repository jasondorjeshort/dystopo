package io.github.jasondorjeshort.dystopo.app

import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot

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
    }

    fun onlineChipsHidden() {
        onlineChips = null
        if (TopChips.onlineRightPx != 0f) TopChips.onlineRightPx = 0f
    }

    /** True when ([x], [y]) lies in [r] (`left, top, right, bottom`) grown by [marginPx]. */
    fun inside(r: FloatArray?, x: Float, y: Float, marginPx: Float = 0f): Boolean =
        r != null && x >= r[0] - marginPx && x <= r[2] + marginPx && y >= r[1] - marginPx && y <= r[3] + marginPx
}
