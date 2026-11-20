package io.github.jasondorjeshort.dystopo.app.controls

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jasondorjeshort.dystopo.app.MapChrome
import io.github.jasondorjeshort.dystopo.app.control
import io.github.jasondorjeshort.dystopo.app.horizon.HorizonUi

/**
 * T309 (the owner, 2026-11-20: "Yeah, I want this as a real button."; the survey in
 * `docs/ideas/2026-11-19-the-horizon-view-beside-the-map-view.md`: a two-segment "Map | Horizon" at the bottom): the switch
 * between the map view and the Horizon view. On the map it stands in the left lower column above the scale bar's line,
 * placed by the layout rule (`ui/OverlayLayout.kt`, [io.github.jasondorjeshort.dystopo.ui.OverlayIds.HORIZON_SWITCH]); on
 * the Horizon view the same control is fixed at the bottom of its content pane with Horizon selected. "Horizon" opens the
 * view, "Map" closes it (as Close and Back do). Debug: `POST /horizon/switch?to=map|horizon`.
 */
object ViewSwitch {
    const val MAP = "Map"
    const val HORIZON = "Horizon"

    /** Its height on the map (dp): each segment's least, which its one line of text at map chrome's capped font scale stays under. */
    const val HEIGHT_DP = 48

    /** The group's spoken label. */
    const val LABEL = "Map or horizon view"

    /** Test tags of the two segments (the device tour taps them by their nodes' bounds). */
    const val TAG_MAP = "horizonSwitch.map"
    const val TAG_HORIZON = "horizonSwitch.horizon"

    /** The Horizon view's own copy of the switch (a plain tag: the map's layout does not place it). */
    const val TAG_ON_VIEW = "horizonViewSwitch"

    /** Selects a segment: the Horizon view opened or closed. Main thread. */
    fun select(horizon: Boolean) {
        if (horizon == HorizonUi.open) return
        if (horizon) HorizonUi.open() else HorizonUi.close()
    }
}

/** The colours of the switch: the map chips' paper and ink on the map, the view's own palette on the Horizon view. */
class ViewSwitchColours(val paper: Color, val ink: Color, val line: Color)

private val MapColours = ViewSwitchColours(Color(0xEEFFFCF5), Color(0xFF2B2420), Color(0xFFB9A88F))

/** The two segments, the selected one filled (ink) with paper text, the other paper with ink text; each at least 48 dp tall. */
@Composable
fun ViewSwitchRow(horizon: Boolean, colours: ViewSwitchColours, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier.semantics { contentDescription = ViewSwitch.LABEL }.selectableGroup()
            .clip(shape).background(colours.paper, shape).border(1.dp, colours.line, shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for ((label, isHorizon) in listOf(ViewSwitch.MAP to false, ViewSwitch.HORIZON to true)) {
            val on = horizon == isHorizon
            Box(
                Modifier.testTag(if (isHorizon) ViewSwitch.TAG_HORIZON else ViewSwitch.TAG_MAP)
                    .selectable(selected = on, role = Role.Tab) { ViewSwitch.select(isHorizon) }
                    .heightIn(min = ViewSwitch.HEIGHT_DP.dp).widthIn(min = 64.dp).background(if (on) colours.ink else Color.Transparent, shape)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    label, softWrap = false,
                    style = TextStyle(color = if (on) colours.paper else colours.ink, fontSize = 15.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium),
                )
            }
        }
    }
}

/** The map's switch, Map selected; composed over the map and drawn where the layout rule puts it (map chrome: capped font scale). */
@Composable
fun MapViewSwitch() {
    MapChrome {
        Box(Modifier.fillMaxSize()) {
            ViewSwitchRow(false, MapColours, Modifier.align(Alignment.BottomStart).control("horizonSwitch")) // the node first (T243)
        }
    }
}
