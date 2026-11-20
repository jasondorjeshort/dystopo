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
 * the position's age and the credit; the finding's order), and a one-line status under the search box while the feed
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
                    .padding(start = 8.dp, top = TopChips.searchBottom + 6.dp)
                    .widthIn(max = 320.dp)
                    .control("flightsStatus")
                    .background(Paper.copy(alpha = 0.95f), RoundedCornerShape(14.dp)).border(1.dp, Line, RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
    val sel = AircraftLayer.selected ?: return
    val a = AircraftLayer.drawn.firstOrNull { it.hex == sel } ?: return
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val navBar = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
        val credit = io.github.jasondorjeshort.dystopo.app.about.AttributionInsets.bottom
        val bottom = maxOf(navBar + 34.dp, credit + 4.dp)
        FlightCard(
            a, onClose = { AircraftLayer.select(null) },
            Modifier.align(Alignment.BottomCenter).padding(start = 12.dp, end = 12.dp, bottom = bottom).widthIn(max = 420.dp)
                .heightIn(max = maxHeight * 0.6f)
                .control("aircraftCard")
                .onGloballyPositioned { c -> AircraftCardLayout.heightPx = c.size.height },
        )
    }
}

/**
 * T311: one aircraft's card, for any caller to host (the map hosts it in [AircraftOverlay]; another screen may host it
 * over itself): the call sign as the title with the help icon and Close ([onClose]), then [AircraftText.card]'s lines in
 * the units and airport-name settings, the credit last. [modifier] places and sizes it (it scrolls inside what it is given).
 * No dependency on the map's layout.
 */
@Composable
fun FlightCard(aircraft: AircraftNow, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val lines = AircraftText.card(aircraft, AppSettings.units, AppSettings.AIRCRAFT_AIRPORTS.value, AircraftPoller.credit)
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
                    color = if (credit || l == AircraftText.ROUTE_HEDGE) Muted else Ink,
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
        "Aircraft from a volunteer receiver network. The route comes from schedule data on this phone and shows only when " +
            "the aircraft fits it. Hollow: its position is over 30 seconds old; after a minute it goes. Heights are above sea " +
            "level, or pressure altitude when your GPS gives no geoid height."
    },
)

private val Ink = Color(0xFF2B2420)
private val Muted = Color(0xFF6B5A4A)
private val Paper = Color(0xFFFFFCF5)
private val Accent = Color(0xFF7A4E2D)
private val Line = Color(0xFFE2D8C6)
