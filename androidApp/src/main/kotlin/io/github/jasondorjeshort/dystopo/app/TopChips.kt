package io.github.jasondorjeshort.dystopo.app

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * T65: the right-hand chip column under the top row (search, Settings, Packs) stacks instead of hiding:
 * the track controls (the Tracks chip and, when open, the tracks list) start below the navigation card
 * while navigating, and the Routes chip ([routesChip], used by the trips UI) sits just below the track
 * controls, so neither the open tracks list nor the navigation card covers it. Edges are measured from
 * the window top (status bar included), 0 when not shown.
 */
object TopChips {
    /** Bottom edge of the navigation stats card. */
    var navCardBottom by mutableStateOf(0.dp)

    /** Top and bottom edges of the track controls column. */
    var trackColumnTop by mutableStateOf(0.dp)
    var trackColumnBottom by mutableStateOf(0.dp)

    /** Bottom edge of [c] (or its top with [top]) from the window top. */
    fun edge(c: LayoutCoordinates, density: Density, top: Boolean = false): Dp =
        with(density) { (c.positionInRoot().y + if (top) 0f else c.size.height.toFloat()).toDp() }

    /** Where the track controls start: below the Packs button, or below the navigation card while it shows. */
    fun trackColumn(): Modifier =
        if (navCardBottom > 0.dp) Modifier.padding(top = navCardBottom + GAP) else Modifier.statusBarsPadding().padding(top = TRACKS_TOP)

    /** The Routes chip's place (top-right): just below the track controls once they have been measured. */
    fun routesChip(): Modifier =
        if (trackColumnBottom > 0.dp) Modifier.padding(end = 8.dp, top = trackColumnBottom + GAP) else Modifier.statusBarsPadding().padding(end = 8.dp, top = ROUTES_TOP)

    fun json(): Map<String, Any?> = linkedMapOf(
        "navCardBottomDp" to navCardBottom.value, "trackColumnTopDp" to trackColumnTop.value, "trackColumnBottomDp" to trackColumnBottom.value,
        "routesChipTopDp" to if (trackColumnBottom > 0.dp) (trackColumnBottom + GAP).value else null,
        // The track controls clear the navigation card when they start below its bottom edge.
        "tracksClearOfNavCard" to (navCardBottom == 0.dp || trackColumnTop >= navCardBottom),
    )

    private val GAP = 6.dp
    private val TRACKS_TOP = 64.dp
    private val ROUTES_TOP = 112.dp
}
