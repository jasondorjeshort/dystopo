package io.github.jasondorjeshort.dystopo.app.update

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import io.github.jasondorjeshort.dystopo.app.MapInsets
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jasondorjeshort.dystopo.app.SettingsUi
import kotlin.concurrent.thread

/**
 * T83: the update offer on the map, above the credit line: "Update to <version>" with Install and Not now once a
 * download has been verified ([Updater]), or why an update was refused (checksum or certificate) with OK. Hidden
 * while the settings sheet is open (its Updates row has the same buttons) and after the user answered. T106: also while
 * a trip, the Routes list or a planned route's card is open; it reports its top edge (`MapInsets.updateCardTop`), above
 * which the recentre button, the scale bar, the long-press card (with the other track panels) and the first-run card sit.
 */
@Composable
fun UpdateOverlay() {
    // T106: it waits while a trip, the Routes list or a planned route's card is open (cards the user works in that span
    // the bottom: stacked, the map was gone at font scale 2.0) and comes back when they close; the long-press card and
    // the first-run card stack above it instead.
    val waits = io.github.jasondorjeshort.dystopo.app.trips.Trips.editing != null || io.github.jasondorjeshort.dystopo.app.trips.Trips.listOpen ||
        io.github.jasondorjeshort.dystopo.app.routing.RouteSession.phase != "idle"
    val shown = !waits && Updater.initialized() && !SettingsUi.visible && Updater.state.let { s ->
        s.phase == Updater.Phase.READY || s.phase == Updater.Phase.INSTALLING || (s.phase == Updater.Phase.REFUSED && s.tag != null && s.tag != Updater.acknowledgedTag)
    }
    // T106 (a T93 follow-up): the offer reports its top edge while it shows, so the recentre button rides above it
    // instead of sharing its corner (release builds; debug builds never check by themselves).
    if (!shown) {
        SideEffect { if (MapInsets.updateCardTop != 0.dp) MapInsets.updateCardTop = 0.dp }
        return
    }
    val s = Updater.state
    val refusedShown = s.phase == Updater.Phase.REFUSED && s.tag != null && s.tag != Updater.acknowledgedTag
    val density = LocalDensity.current
    DisposableEffect(Unit) { onDispose { MapInsets.updateCardTop = 0.dp } }
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = 12.dp, end = 12.dp, bottom = 34.dp).widthIn(max = 420.dp)
                .onGloballyPositioned { c -> MapInsets.topFromWindowBottom(c, density).let { if (it != MapInsets.updateCardTop) MapInsets.updateCardTop = it } }
                .background(Paper, RoundedCornerShape(12.dp)).border(1.dp, Line, RoundedCornerShape(12.dp)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (refusedShown) {
                BasicText(s.message ?: "Update refused", style = TextStyle(color = Red, fontSize = 14.sp, fontWeight = FontWeight.Medium))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button("OK", true) { Updater.acknowledge() } }
            } else {
                BasicText("Update to ${s.versionName ?: s.tag}", style = TextStyle(color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold))
                BasicText(
                    if (s.phase == Updater.Phase.INSTALLING) "Opening the installer..." else "Downloaded and checked (signature and checksum). Your tracks, trips, packs and settings stay.",
                    style = TextStyle(color = Muted, fontSize = 12.sp),
                )
                if (s.phase == Updater.Phase.READY) {
                    s.message?.takeIf { it.startsWith("Install failed") }?.let { BasicText(it, style = TextStyle(color = Red, fontSize = 12.sp)) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button("Install", true) { thread(name = "dystopo-update-install") { Updater.install() } }
                        Button("Not now", false) { Updater.decline() }
                    }
                }
            }
        }
    }
}

@Composable
internal fun Button(label: String, primary: Boolean, onClick: () -> Unit) {
    BasicText(
        label,
        softWrap = false,
        style = TextStyle(color = if (primary) Paper else Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium),
        modifier = Modifier
            .background(if (primary) Accent else Color(0xEEFFFCF5), RoundedCornerShape(16.dp))
            .border(1.dp, if (primary) Accent else Line, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

internal val Ink = Color(0xFF2B2420)
internal val Muted = Color(0xFF6B5A4A)
internal val Paper = Color(0xFFFFFCF5)
internal val Accent = Color(0xFF7A4E2D)
internal val Line = Color(0xFFE2D8C6)
internal val Red = Color(0xFFB0302A)
