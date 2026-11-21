package io.github.jasondorjeshort.dystopo.app.aircraft

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jasondorjeshort.dystopo.aircraft.AircraftNow
import io.github.jasondorjeshort.dystopo.aircraft.AircraftText
import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.app.TopChips
import io.github.jasondorjeshort.dystopo.app.control
import io.github.jasondorjeshort.dystopo.app.help.HelpIcon
import io.github.jasondorjeshort.dystopo.app.help.HelpText

/**
 * T311: what the Flights layer shows over the map: the tapped aircraft's card at the bottom, above the credit line (its
 * call sign as the title, then origin > destination, type, altitude, speed, distance, bearing and angle from the phone,
 * the position's age and the credit; the finding's order), and a one-line status under the search box and the online column while the feed
 * has something to say ([AircraftLayer.message]: offline, stopped, the day's limit, waiting). The card's layout id is
 * `aircraftCard` (`OverlayIds.AIRCRAFT_CARD`), the status line's `flightsStatus`.
 */
@Composable
fun AircraftOverlay() {
    @Suppress("UNUSED_VARIABLE") val rev = AircraftLayer.revision
    if (!AircraftLayer.on) return
    val density = LocalDensity.current
    AircraftLayer.message?.let { msg ->
        Box(Modifier.fillMaxSize()) {
            BasicText(
                "${AircraftText.LAYER}: $msg",
                style = TextStyle(color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium),
                modifier = Modifier.align(Alignment.TopStart)
                    .padding(start = 8.dp, top = maxOf(TopChips.searchBottom, with(density) { TopChips.onlineBottomPx.toDp() }) + 6.dp) // under the online column
                    .widthIn(max = 320.dp)
                    .control("flightsStatus")
                    .background(Paper.copy(alpha = 0.95f), RoundedCornerShape(14.dp)).border(1.dp, Line, RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
    val sel = AircraftLayer.selected
    val a = sel?.let { h -> AircraftLayer.drawn.firstOrNull { it.hex == h } }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { io.github.jasondorjeshort.dystopo.app.MapInsets.aircraftCardTop = 0.dp } }
    if (a == null) {
        androidx.compose.runtime.SideEffect { if (io.github.jasondorjeshort.dystopo.app.MapInsets.aircraftCardTop != 0.dp) io.github.jasondorjeshort.dystopo.app.MapInsets.aircraftCardTop = 0.dp }
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val navBar = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
        val credit = io.github.jasondorjeshort.dystopo.app.about.AttributionInsets.bottom
        val bottom = maxOf(navBar + 34.dp, credit + 4.dp)
        // Round 2: the card may take all but the top row's room (it scrolls past that), so a landscape window shows it whole.
        val top = maxOf(TopChips.searchBottom, with(density) { TopChips.onlineBottomPx.toDp() }) + 8.dp
        FlightCard(
            a, onClose = { AircraftLayer.select(null) },
            modifier = Modifier.align(Alignment.BottomCenter).padding(start = 12.dp, end = 12.dp, bottom = bottom).widthIn(max = 520.dp)
                .heightIn(max = (maxHeight - bottom - top).coerceAtLeast(120.dp))
                .control("aircraftCard")
                .onGloballyPositioned { c ->
                    AircraftCardLayout.heightPx = c.size.height
                    // The bottom controls (position buttons, scale bar, readout, the Map | Horizon switch) ride above it.
                    io.github.jasondorjeshort.dystopo.app.MapInsets.topFromWindowBottom(c, density).let { t ->
                        if (t != io.github.jasondorjeshort.dystopo.app.MapInsets.aircraftCardTop) io.github.jasondorjeshort.dystopo.app.MapInsets.aircraftCardTop = t
                    }
                },
            estimatedFrom = AircraftLayer.estimatedFrom.takeIf { AircraftLayer.selected == a.hex }, // T314: the map draws the dotted estimate
        )
    }
}

/**
 * T311: one aircraft's card, for any caller to host (the map hosts it in [AircraftOverlay]; another screen may host it
 * over itself): the call sign as the title with the help icon and Close ([onClose]), then [AircraftText.card]'s lines in
 * the units and airport-name settings, the credit last. [modifier] places and sizes it (it scrolls inside what it is given).
 * T314: [estimatedFrom], given by a host that draws the dotted estimate (the map), adds "Dotted: estimated path from SEA".
 * No dependency on the map's layout.
 */
@Composable
fun FlightCard(aircraft: AircraftNow, onClose: () -> Unit, modifier: Modifier = Modifier, estimatedFrom: io.github.jasondorjeshort.dystopo.aircraft.Airport? = null) {
    val lines = AircraftText.card(aircraft, AppSettings.units, AppSettings.AIRCRAFT_AIRPORTS.value, AircraftPoller.credit, estimatedFrom)
    Column(
        modifier
            .background(Paper, RoundedCornerShape(12.dp)).border(1.dp, Line, RoundedCornerShape(12.dp))
            .verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(aircraft.title, Modifier.weight(1f), style = TextStyle(color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold))
            HelpIcon("flights.card")
            BasicText(
                "Close",
                style = TextStyle(color = Accent, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).clickable(onClick = onClose)
                    .semantics { contentDescription = "Close the flight card" }.padding(horizontal = 8.dp, vertical = 14.dp),
            )
        }
        for ((i, l) in lines.withIndex()) {
            val first = i == 0
            val credit = i == lines.lastIndex
            BasicText(
                l,
                style = TextStyle(
                    color = if (credit || l == AircraftText.ROUTE_HEDGE || l.startsWith(AircraftText.ESTIMATED_PREFIX)) Muted else Ink,
                    fontSize = if (first) 15.sp else if (credit) 11.sp else 13.sp,
                    fontWeight = if (first) FontWeight.SemiBold else FontWeight.Normal,
                ),
            )
        }
    }
}

/** T311: what the card last measured (for `GET /aircraft`). */
object AircraftCardLayout {
    @Volatile var heightPx = 0
}

/** T311: the card's help text (`HelpRegistryTest` reads it from this file, beside its icon). */
internal val AIRCRAFT_HELP: List<HelpText> = listOf(
    HelpText(
        "flights.card", "Flights", "The map's flight card",
        needs = "an aircraft tapped on the map (Settings › Map options › Flight tracking on)",
        show = { AircraftLayer.drawn.minByOrNull { it.distanceM ?: 0.0 }?.let { AircraftLayer.select(it.hex) } },
    ) {
        // T316: shorter, so the hollow and gone times can follow the refresh setting (1.5 and 2.5 intervals, at least 30 and 60 s).
        "Aircraft from volunteer receivers. A route from schedule data shows only when the aircraft fits it. Track: solid " +
            "as seen, dotted estimated, dashed ahead. Hollow: position ${AircraftText.staleWords(AppSettings.AIRCRAFT_REFRESH_S.value)} " +
            "(${AircraftText.refreshLabel(AppSettings.AIRCRAFT_REFRESH_S.value)} refresh). " +
            "Heights are above sea level, or pressure altitude without a GPS geoid height."
    },
)

private val Ink = Color(0xFF2B2420)
private val Muted = Color(0xFF6B5A4A)
private val Paper = Color(0xFFFFFCF5)
private val Accent = Color(0xFF7A4E2D)
private val Line = Color(0xFFE2D8C6)
