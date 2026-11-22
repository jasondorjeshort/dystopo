package io.github.jasondorjeshort.dystopo.app.horizon

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jasondorjeshort.dystopo.aircraft.AircraftNow
import io.github.jasondorjeshort.dystopo.app.aircraft.AircraftPoller
import io.github.jasondorjeshort.dystopo.app.aircraft.FlightCard
import io.github.jasondorjeshort.dystopo.app.gnss.Button
import io.github.jasondorjeshort.dystopo.app.gnss.Gnss
import io.github.jasondorjeshort.dystopo.app.gnss.GnssPalette
import io.github.jasondorjeshort.dystopo.app.gnss.GnssText
import io.github.jasondorjeshort.dystopo.app.gnss.LegendItem
import io.github.jasondorjeshort.dystopo.app.gnss.Mark
import io.github.jasondorjeshort.dystopo.app.gnss.MarkIcon
import io.github.jasondorjeshort.dystopo.app.help.HelpIcon
import io.github.jasondorjeshort.dystopo.dem.PeakView
import io.github.jasondorjeshort.dystopo.horizon.HorizonLayer
import io.github.jasondorjeshort.dystopo.horizon.HorizonLayers
import io.github.jasondorjeshort.dystopo.horizon.PeakRim
import io.github.jasondorjeshort.dystopo.horizon.PlaceLine
import io.github.jasondorjeshort.dystopo.render.ContourUnits

// T325: the parts of the Horizon view's pane, cut from `HorizonScreen()` with no change in what they draw, so a second
// sky view can host them around its own plot. Each takes plain arguments; the state they read and write is HorizonUi's,
// the one open state of the sky views. The sections of the scrolling area are in SkyViewSections.kt, the effects in
// SkyViewEffects.kt.

/** T317: the moved place line's tint: the sun's amber, a fill and a stronger edge, legible on the day and the night paper. */
private fun movedFill(night: Boolean): Color = if (night) Color(0xFF4A3412) else Color(0xFFFBE2AE)
private fun movedEdge(night: Boolean): Color = if (night) Color(0xFFE6A84E) else Color(0xFFB8641A)

/**
 * T317: where the view stands, always shown: "Your location" beside a dot in the link colour at the fix; at a chosen point
 * the line ("At Mount Rainier, 7.6 km NNW of you", "12 km SSE of you") bold on an amber band with "Back to here", so a moved
 * view is never read as the phone's own. A point within 50 m of the fix reads "Your location" but keeps "Back to here".
 * T325: [place] is `HorizonPlace.line` for the view's point; [showBack] whether the view stands at a chosen point.
 */
@Composable
fun PlaceRow(place: PlaceLine, showBack: Boolean, pal: GnssPalette, night: Boolean) {
    if (!showBack) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("horizonPlaceLine"), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.Canvas(Modifier.size(10.dp)) { drawCircle(pal.link) }
            BasicText(place.text, Modifier.padding(start = 8.dp), style = TextStyle(color = pal.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
        }
        return
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
            .background(if (place.moved) movedFill(night) else pal.card, RoundedCornerShape(10.dp))
            .border(2.dp, if (place.moved) movedEdge(night) else pal.line, RoundedCornerShape(10.dp))
            .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(place.text, Modifier.weight(1f).testTag("horizonPlaceLine"), style = TextStyle(color = pal.ink, fontSize = 16.sp, fontWeight = FontWeight.Bold))
        Box(Modifier.padding(start = 8.dp).testTag("horizonBackToHere")) { Button(HorizonText.BACK, pal, filled = true) { HorizonUi.back() } }
    }
}

/**
 * T325: the heading row that stays while the pane scrolls: the view's [title] and the help pop-up [helpKey] (a key of the
 * help registry), then [trailing] at the row's end.
 */
@Composable
fun SkyViewHeading(title: String, helpKey: String, pal: GnssPalette, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            BasicText(title, Modifier.weight(1f, fill = false), style = TextStyle(color = pal.ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold))
            HelpIcon(helpKey)
        }
        trailing()
    }
}

/**
 * The peak a tap picked, under the heading (it stays while the list scrolls); a tap on its line or Back lets it go. T321:
 * "View from here" beside the line, a target of its own (a button at least 48 dp tall), stands the view on the peak; above
 * font scale 1.3 it goes under the line, at its end (beside it, at 2.0 the line wrapped to five lines in a third of the width).
 */
@Composable
fun PickedPeak(p: PeakView, pal: GnssPalette, units: ContourUnits) {
    val line: @Composable (Modifier) -> Unit = { m ->
        Row(
            m.heightIn(min = 48.dp).clickable(role = Role.Button) { HorizonUi.selected = null }.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(HorizonText.peakLine(p.peak.name, p.eleM, p.distanceM, p.bearingDeg, units, !PeakRim.inSight(p)), Modifier.weight(1f), style = TextStyle(color = pal.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
            BasicText("×", Modifier.padding(start = 8.dp).semantics { contentDescription = HorizonText.PEAK_CLEAR }, style = TextStyle(color = pal.ink, fontSize = 20.sp))
        }
    }
    val button: @Composable () -> Unit = { Box(Modifier.testTag("horizonViewFromHere")) { Button(HorizonText.VIEW_FROM_HERE, pal) { HorizonUi.viewFrom(p) } } }
    val frame = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).background(pal.card, RoundedCornerShape(10.dp)).border(1.dp, pal.link, RoundedCornerShape(10.dp))
    if (LocalDensity.current.fontScale > 1.3f) {
        Column(frame.padding(end = 6.dp, bottom = 4.dp), horizontalAlignment = Alignment.End) {
            line(Modifier.fillMaxWidth())
            button()
        }
    } else {
        Row(frame.padding(end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            line(Modifier.weight(1f))
            button()
        }
    }
}

/**
 * "Show:" and the layer toggles: one row of toggle buttons (the owner's words), wrapping at large text; T312: [buttons]
 * those the host offers, in order; T317: those in [disabled] muted and not tappable (their settings kept), and with
 * [moved] the sentence saying why under the row. A toggle turns its synced setting ([HorizonUi.setLayer]).
 */
@Composable
fun LayerToggles(layers: HorizonLayers, buttons: List<HorizonLayer>, disabled: Set<HorizonLayer>, moved: Boolean, pal: GnssPalette) {
    @OptIn(ExperimentalLayoutApi::class)
    // Round 2 (T312): 6 dp gaps and 10 dp padding, so "Show:" and the four buttons fit one row at the default font on a
    // 411 dp phone (14 dp and 8 dp wrapped Flights onto a row of its own); larger text still wraps.
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
            BasicText("${HorizonText.LAYERS}:", style = TextStyle(color = pal.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium))
        }
        for (l in buttons) {
            val on = layers[l]
            // T317: at a chosen point Satellites and Flights are disabled: no tick, muted, not tappable (their settings kept).
            val enabled = l !in disabled
            Box(
                Modifier.heightIn(min = 48.dp).semantics { this.selected = on }.clickable(enabled = enabled, role = Role.Switch) { HorizonUi.setLayer(l, !on) },
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    (if (on) "✓ " else "") + l.label,
                    style = TextStyle(color = if (on) pal.paper else if (enabled) pal.ink else pal.muted, fontSize = 14.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium),
                    modifier = Modifier.background(if (on) pal.ink else if (enabled) pal.card else pal.paper, RoundedCornerShape(16.dp))
                        .border(1.dp, if (on) pal.ink else pal.line, RoundedCornerShape(16.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
        }
    }
    if (moved) BasicText(HorizonText.MOVED_LAYERS, Modifier.testTag("horizonMovedLayers"), style = TextStyle(color = pal.muted, fontSize = 13.sp))
}

/**
 * T325: the terrain line: the skyline's highest point (angle, bearing, distance, source) when the horizon is known, else
 * why not (the horizon's reason, no position, or still working), and the skyline band's legend under it while known.
 * [h] the horizon at [pos], the point the view describes (null: no position).
 */
@Composable
fun TerrainLine(h: Gnss.HorizonState?, pos: Pair<Double, Double>?, units: ContourUnits, pal: GnssPalette) {
    val horizon = h?.profile
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
}

/** T325: the flight a tap picked ([HorizonUi.selectedFlight]) as the poller has it now, or null (picked none, or gone from the feed). */
fun pickedFlight(): AircraftNow? = HorizonUi.selectedFlight?.let { hex -> AircraftPoller.view.value.aircraft.firstOrNull { it.hex == hex } }

/**
 * T312: T311's flight card for [a], hosted by the sky view (the view is drawn above the map, so the map's card would open
 * underneath); its Close lets the pick go. T325: [modifier] places it (the host knows where its plot and pane are).
 */
@Composable
fun FlightCardHost(a: AircraftNow, modifier: Modifier) {
    FlightCard(a, { HorizonUi.selectedFlight = null }, modifier.testTag("horizonFlightCard"))
}
