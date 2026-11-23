package io.github.jasondorjeshort.dystopo.app.controls

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jasondorjeshort.dystopo.app.MapChrome
import io.github.jasondorjeshort.dystopo.app.control
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.horizon.HorizonUi
import io.github.jasondorjeshort.dystopo.app.search.SearchMarker
import io.github.jasondorjeshort.dystopo.app.tracking.LongPressMenu
import io.github.jasondorjeshort.dystopo.horizon.HorizonPlace
import io.github.jasondorjeshort.dystopo.horizon.SkyView
import io.github.jasondorjeshort.dystopo.horizon.SwitchAction
import io.github.jasondorjeshort.dystopo.horizon.SwitchFrom
import io.github.jasondorjeshort.dystopo.ui.OverlayLayout

/**
 * T309 (the owner, 2026-11-20: "Yeah, I want this as a real button."): the switch between the map view and the Horizon
 * view. "Horizon" opens the view, "Map" closes it (as Back does, after letting go of a pick and a chosen point). Debug: `POST /horizon/switch?to=map|horizon`.
 *
 * T315 (the owner: the toggle "in the same place in every view"): one placement for every view, defined here. The anchor
 * is the window's bottom-left corner: [EDGE_DP] in from the left inset and up from the bottom inset ([anchorInsets]: the
 * system bars and the display cutout, as the map's layout rule reads them), the same size (each segment at least
 * [HEIGHT_DP] tall, its text at map chrome's capped font scale, [MapChrome]), the segments in the order [SEGMENTS]. The
 * map's layout rule reserves the same rectangle (`ui/OverlayLayout.kt`, [OverlayLayout.switchRect]): the credit line
 * stands beside it and the rest of the map's lower controls above it ([floorDp]). The Horizon view draws it at the same
 * anchor and pads its scroll area and, in landscape, its plot clear of it ([reserveDp]).
 *
 * T326: three segments, "Map | Horizon | Panorama" ([SEGMENTS], [SkyView]); the anchor grew to the right. What a tap does
 * is [HorizonPlace.switchAction]'s rule: "Map" closes either sky view, the map to a sky view opens it at the map's selection
 * ([openAtSelection]), and between the two sky views the place and a picked peak stay. The credit line stands beside the
 * switch while its text fits whole there, else above it across the window (`about/AttributionLine.kt`, wave 45's (h)).
 * Debug: `POST /horizon/switch?to=map|horizon|panorama`.
 *
 * T344: four segments, "Map | Horizon | Panorama | List" (the List view, `app/skylist/`: the Panorama's pane as a page of
 * its own). The segments' padding is 12 dp a side so the four fit a 411 dp window upright at map chrome's capped scale; the
 * credit line follows the switch's width by the same rule (`OverlayLayout.creditAbove`). From the List, "Panorama" shows
 * the Panorama as it was left when the List was opened from it ([io.github.jasondorjeshort.dystopo.app.skylist.SkyList.keepsAim]).
 * Debug: `POST /horizon/switch?to=list`.
 */
object ViewSwitch {
    const val MAP = "Map"
    const val HORIZON = "Horizon"
    const val PANORAMA = "Panorama"
    const val LIST = "List" // T344

    /** T315: the segments in order, left to right; T326: the three views; T344: and the List. */
    val SEGMENTS: List<SkyView> = listOf(SkyView.MAP, SkyView.HORIZON, SkyView.PANORAMA, SkyView.LIST)

    /** Its height (dp): each segment's least, which its one line of text at map chrome's capped font scale stays under. */
    const val HEIGHT_DP = 48

    /** T315: the anchor's distance in from the left inset and up from the bottom inset (dp); the layout rule's [OverlayLayout.SWITCH_EDGE]. */
    const val EDGE_DP = OverlayLayout.SWITCH_EDGE

    /** The group's spoken label; T326: naming the three; T344: the four. */
    const val LABEL = "Map, horizon, panorama or list view"

    /** T344: each segment's padding at its sides (dp; 16 before the fourth). */
    const val SEGMENT_PAD_DP = 12

    /** Test tags of the segments (the device tour taps them by their nodes' bounds). */
    const val TAG_MAP = "horizonSwitch.map"
    const val TAG_HORIZON = "horizonSwitch.horizon"
    const val TAG_PANORAMA = "horizonSwitch.panorama"
    const val TAG_LIST = "horizonSwitch.list" // T344

    fun tagOf(v: SkyView): String = when (v) {
        SkyView.MAP -> TAG_MAP
        SkyView.HORIZON -> TAG_HORIZON
        SkyView.PANORAMA -> TAG_PANORAMA
        SkyView.LIST -> TAG_LIST
    }

    /** T326: the view showing now: the map while no sky view is open, else the one [HorizonUi.view] names. */
    val showing: SkyView get() = if (HorizonUi.open) HorizonUi.view else SkyView.MAP

    /** The Horizon view's own copy of the switch (a plain tag: the map's layout does not place it). */
    const val TAG_ON_VIEW = "horizonViewSwitch"

    /** T315: the switch's size as last laid out in either view (dp; both are the same control at the same capped scale). */
    var widthDp by mutableStateOf(150f)
        private set
    var heightDp by mutableStateOf(HEIGHT_DP.toFloat())
        private set

    /**
     * T315: while the map's switch is composed, its top from the bottom of the window (the Compose root), dp; 0 while it is
     * not. The map's cards and panels stand on the higher of this and the credit line (`about/AttributionLine.kt`).
     */
    var floorDp by mutableStateOf(0f)
        private set

    /** T315: while the map's switch is composed, how far right of its own start the credit line starts (dp); 0 while it is not. */
    var creditStartDp by mutableStateOf(0f)
        private set

    /** T315: the room the switch takes from the bottom of the inset area: its height and the anchor's edge (dp). */
    val reserveDp: Float get() = heightDp + EDGE_DP

    /** T315: the insets the anchor stands inside: the system bars and the display cutout at the left and the bottom. */
    val anchorInsets: WindowInsets
        @Composable get() = WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Left + WindowInsetsSides.Bottom)

    /** Makes the Compose state on the main thread (`MainActivity.onCreate`), before any composition or route reads it (T226). */
    fun prepare() {
        widthDp; heightDp; floorDp; creditStartDp
    }

    /**
     * Selects a segment: the Horizon view opened or closed. Main thread. T321: "Horizon" opens the view at the map's
     * selected place when there is one ([openAtSelection]); a selection made inside the view does not count (the view is
     * open already, so this returns first).
     */
    fun select(to: SkyView) {
        val from = showing
        val action = HorizonPlace.switchAction(from, to)
        // T326: an opening of the Panorama (from the map or from the Horizon view): the aim at opening (choice c). T344: from
        // the List opened from the Panorama, the Panorama as it was left.
        val list = io.github.jasondorjeshort.dystopo.app.skylist.SkyList
        if (to == SkyView.PANORAMA && (action == SwitchAction.OPEN_AT_SELECTION || action == SwitchAction.KEEP_PLACE) && !(from == SkyView.LIST && list.keepsAim())) {
            io.github.jasondorjeshort.dystopo.app.panorama.PanoramaUi.opened()
        }
        if (to == SkyView.LIST && action != SwitchAction.NONE) list.entered(from) // T344
        when (action) {
            SwitchAction.NONE -> {}
            SwitchAction.CLOSE -> HorizonUi.close()
            SwitchAction.OPEN_AT_SELECTION -> {
                HorizonUi.show(to)
                openAtSelection()
            }
            SwitchAction.KEEP_PLACE -> HorizonUi.show(to)
        }
    }

    /** T321: what the last "Horizon" from the switch stood on (`POST /horizon/switch` answers it), or null before one. */
    @Volatile var lastFrom: SwitchFrom? = null
        private set

    /**
     * T321 (the owner, 2026-11-21: "If we select a different location on the map and switch to horizon view we want the
     * horizon from THAT location, not ours"): opens the view at the map's selection by [HorizonPlace.switchFrom]'s rule:
     * an open long-press card (as its "Sky from here": on a named peak within a fingertip, that peak, named; the card
     * closes), else a search result's marker (named; it stays on the map), else the fix. The one call a third segment (the
     * Panorama view) makes too. Main thread.
     */
    fun openAtSelection() {
        val marker = SearchMarker.selection(DystopoApp.instance.map.map?.style)
        val from = HorizonPlace.switchFrom(LongPressMenu.point != null, marker)
        lastFrom = from
        when (from) {
            SwitchFrom.LONG_PRESS -> LongPressMenu.skyFromHere(via = "switch")
            SwitchFrom.SEARCH -> HorizonUi.openAt(marker!!)
            SwitchFrom.FIX -> HorizonUi.open()
        }
    }

    internal fun measured(w: Float, h: Float) {
        if (w > 0f && w != widthDp) widthDp = w
        if (h > 0f && h != heightDp) heightDp = h
    }

    internal fun mapAt(floor: Float, creditStart: Float) {
        if (floor != floorDp) floorDp = floor
        if (creditStart != creditStartDp) creditStartDp = creditStart
    }
}

/** T315: the anchor (see [ViewSwitch]) on an element of a box that fills the window: the bottom-left corner, inside the insets. */
fun viewSwitchAnchor(scope: BoxScope, insets: WindowInsets): Modifier = with(scope) {
    Modifier.align(Alignment.BottomStart).windowInsetsPadding(insets).padding(start = ViewSwitch.EDGE_DP.dp, bottom = ViewSwitch.EDGE_DP.dp)
}

/** The colours of the switch: the map chips' paper and ink on the map, the view's own palette on the Horizon view. */
class ViewSwitchColours(val paper: Color, val ink: Color, val line: Color)

private val MapColours = ViewSwitchColours(Color(0xEEFFFCF5), Color(0xFF2B2420), Color(0xFFB9A88F))

/** The segments, the selected one filled (ink) with paper text, the others paper with ink text; each at least 48 dp tall. */
@Composable
fun ViewSwitchRow(selected: SkyView, colours: ViewSwitchColours, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier.semantics { contentDescription = ViewSwitch.LABEL }.selectableGroup()
            .clip(shape).background(colours.paper, shape).border(1.dp, colours.line, shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (view in ViewSwitch.SEGMENTS) {
            val label = view.label
            val on = selected == view
            Box(
                Modifier.testTag(ViewSwitch.tagOf(view))
                    .selectable(selected = on, role = Role.Tab) { ViewSwitch.select(view) }
                    .heightIn(min = ViewSwitch.HEIGHT_DP.dp).widthIn(min = 64.dp).background(if (on) colours.ink else Color.Transparent, shape)
                    .padding(horizontal = ViewSwitch.SEGMENT_PAD_DP.dp),
                contentAlignment = Alignment.Center,
            ) {
                // T315: each segment as wide as the wider of its label's two weights whichever is selected, so the switch is
                // the same size in every view (the selected label is bold: at font scale 2.0 the Horizon view's switch came
                // out 4 px wider than the map's).
                for (w in listOf(FontWeight.Bold, FontWeight.Medium)) BasicText(
                    label, Modifier.clearAndSetSemantics { }, softWrap = false,
                    style = TextStyle(color = Color.Transparent, fontSize = 15.sp, fontWeight = w),
                )
                BasicText(
                    label, softWrap = false,
                    style = TextStyle(color = if (on) colours.paper else colours.ink, fontSize = 15.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium),
                )
            }
        }
    }
}

/**
 * T315: the switch at its anchor, in a box over the whole window, at map chrome's capped font scale: the one placement both
 * views use. [tagged] goes on the row after the anchor (the map's [control] node, the view's tag and its report);
 * [onPlaced] gets its rectangle in the Compose root (px: left, top, right, bottom) and the root's height (px).
 */
@Composable
fun AnchoredViewSwitch(selected: SkyView, colours: ViewSwitchColours, tagged: Modifier, onPlaced: (FloatArray, Float) -> Unit = { _, _ -> }) {
    val insets = ViewSwitch.anchorInsets
    val density = LocalDensity.current.density
    MapChrome {
        Box(Modifier.fillMaxSize()) {
            ViewSwitchRow(
                selected, colours,
                viewSwitchAnchor(this, insets).then(tagged).onGloballyPositioned { c ->
                    val at = c.positionInRoot()
                    var root = c
                    while (true) root = root.parentLayoutCoordinates ?: break
                    ViewSwitch.measured(c.size.width / density, c.size.height / density)
                    onPlaced(floatArrayOf(at.x, at.y, at.x + c.size.width, at.y + c.size.height), root.size.height.toFloat())
                },
            )
        }
    }
}

/** The map's switch, Map selected, at the anchor; it sets the map's floor and the credit line's start while composed. */
@Composable
fun MapViewSwitch() {
    val density = LocalDensity.current.density
    DisposableEffect(Unit) { onDispose { ViewSwitch.mapAt(0f, 0f) } }
    AnchoredViewSwitch(SkyView.MAP, MapColours, Modifier.control("horizonSwitch")) { r, rootH -> // the node first (T243)
        ViewSwitch.mapAt((rootH - r[1]) / density, ViewSwitch.EDGE_DP + (r[2] - r[0]) / density)
    }
}
