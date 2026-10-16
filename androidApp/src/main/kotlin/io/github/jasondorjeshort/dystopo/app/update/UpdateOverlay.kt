package io.github.jasondorjeshort.dystopo.app.update

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import io.github.jasondorjeshort.dystopo.app.MapInsets
import io.github.jasondorjeshort.dystopo.app.TopChips
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jasondorjeshort.dystopo.app.SettingsUi
import kotlin.concurrent.thread

/**
 * T110: where the update offer sits, for `GET /update` (`overlay`): shown or why not, whether it is the navigation form,
 * its top edge from the window top and the navigation card's bottom edge (dp), and `clearOfNavCard`. Main thread writes.
 */
object UpdateOverlayLayout {
    @Volatile var shown = false
    @Volatile var hiddenBecause: String? = "notComposed"
    @Volatile var duringNavigation = false
    @Volatile var topDp: Float? = null
    @Volatile var maxHeightDp: Float? = null

    fun hide(reason: String) {
        shown = false
        hiddenBecause = reason
        topDp = null
        maxHeightDp = null
    }

    fun json(): Map<String, Any?> {
        val nav = TopChips.navCardBottom.value
        val top = topDp
        return linkedMapOf(
            "shown" to shown, "hiddenBecause" to if (shown) null else hiddenBecause, "duringNavigation" to duringNavigation,
            "topDp" to top?.let { Math.round(it * 10) / 10.0 }, "maxHeightDp" to maxHeightDp?.let { Math.round(it * 10) / 10.0 },
            "navCardBottomDp" to nav,
            // The offer's top edge is below the navigation card's bottom edge (or no navigation card shows).
            "clearOfNavCard" to if (!shown || top == null) null else nav == 0f || top >= nav,
        )
    }
}

/**
 * T83: the update offer on the map, above the credit line: "Update to <version>" with Install and Not now once a
 * download has been verified ([Updater]), or why an update was refused (checksum or certificate) with OK. Hidden
 * while the settings sheet is open (its Updates row has the same buttons) and after the user answered. T106: also while
 * a trip, the Routes list or a planned route's card is open; it reports its top edge (`MapInsets.updateCardTop`), above
 * which the recentre button, the scale bar, the long-press card (with the other track panels) and the first-run card sit.
 *
 * T110: while navigating, installing would replace the running app and end the session, so the offer asks: "After
 * navigation" (the install waits for the session to end and then starts by itself, [Updater.waitingForNavigation]),
 * "Install now" or "Not now"; and it never reaches up to the navigation card at the top: its height is capped to the
 * space between that card and the bottom (it scrolls inside), and with too little space it waits like for a trip.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UpdateOverlay() {
    // T106: it waits while a trip, the Routes list or a planned route's card is open (cards the user works in that span
    // the bottom: stacked, the map was gone at font scale 2.0) and comes back when they close; the long-press card and
    // the first-run card stack above it instead.
    // T110: a planned route's card is hidden while navigating (navigation started from it keeps the plan), so it does not
    // make the offer wait then.
    val navigating = io.github.jasondorjeshort.dystopo.app.nav.NavSession.ui != null // Compose state: follows nav start and stop
    val waits = io.github.jasondorjeshort.dystopo.app.trips.Trips.editing != null || io.github.jasondorjeshort.dystopo.app.trips.Trips.listOpen ||
        (io.github.jasondorjeshort.dystopo.app.routing.RouteSession.phase != "idle" && !navigating)
    val shown = !waits && Updater.initialized() && !SettingsUi.visible && Updater.state.let { s ->
        s.phase == Updater.Phase.READY || s.phase == Updater.Phase.INSTALLING || (s.phase == Updater.Phase.REFUSED && s.tag != null && s.tag != Updater.acknowledgedTag)
    }
    // T106 (a T93 follow-up): the offer reports its top edge while it shows, so the recentre button rides above it
    // instead of sharing its corner (release builds; debug builds never check by themselves).
    DisposableEffect(Unit) { onDispose { MapInsets.updateCardTop = 0.dp; UpdateOverlayLayout.hide("notComposed") } }
    if (!shown) {
        SideEffect {
            if (MapInsets.updateCardTop != 0.dp) MapInsets.updateCardTop = 0.dp
            UpdateOverlayLayout.hide(if (waits) "tripOrRouteOpen" else if (SettingsUi.visible) "settingsOpen" else "nothingToOffer")
        }
        return
    }
    val s = Updater.state
    val refusedShown = s.phase == Updater.Phase.REFUSED && s.tag != null && s.tag != Updater.acknowledgedTag
    val navBottom = TopChips.navCardBottom
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // T110: the offer's bottom edge sits above the credit line (whose height grows with the font scale: at 2.0 its two
        // lines reached above the old fixed 34 dp and the offer covered the first), and its height is capped to the room
        // between that edge and the navigation card's bottom edge.
        val navBar = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
        val credit = io.github.jasondorjeshort.dystopo.app.about.AttributionInsets.bottom
        val bottom = maxOf(navBar + 34.dp, credit + 4.dp)
        val room = if (navigating && navBottom > 0.dp) maxHeight - bottom - navBottom - 8.dp else maxHeight
        if (navigating && room < MIN_ROOM) {
            SideEffect {
                if (MapInsets.updateCardTop != 0.dp) MapInsets.updateCardTop = 0.dp
                UpdateOverlayLayout.hide("noRoomBelowNavCard")
            }
            return@BoxWithConstraints
        }
        Column(
            Modifier.align(Alignment.BottomCenter).padding(start = 12.dp, end = 12.dp, bottom = bottom).widthIn(max = 420.dp)
                .heightIn(max = room)
                .onGloballyPositioned { c ->
                    MapInsets.topFromWindowBottom(c, density).let { if (it != MapInsets.updateCardTop) MapInsets.updateCardTop = it }
                    UpdateOverlayLayout.topDp = with(density) { c.positionInRoot().y.toDp().value }
                    UpdateOverlayLayout.maxHeightDp = room.value
                    UpdateOverlayLayout.duringNavigation = navigating
                    UpdateOverlayLayout.shown = true
                    UpdateOverlayLayout.hiddenBecause = null
                }
                .background(Paper, RoundedCornerShape(12.dp)).border(1.dp, Line, RoundedCornerShape(12.dp))
                .verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (refusedShown) {
                BasicText(s.message ?: "Update refused", style = TextStyle(color = Red, fontSize = 14.sp, fontWeight = FontWeight.Medium))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button("OK", true) { Updater.acknowledge() } }
            } else {
                BasicText("Update to ${s.versionName ?: s.tag}", style = TextStyle(color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold))
                val waiting = Updater.waitingForNavigation
                BasicText(
                    when {
                        s.phase == Updater.Phase.INSTALLING -> "Opening the installer..."
                        waiting -> "Installs when navigation ends."
                        navigating -> "Installing closes the map and ends navigation."
                        else -> "Downloaded and checked (signature and checksum). Your tracks, trips, packs and settings stay."
                    },
                    style = TextStyle(color = Muted, fontSize = 12.sp),
                )
                if (s.phase == Updater.Phase.READY) {
                    s.message?.takeIf { it.startsWith("Install failed") }?.let { BasicText(it, style = TextStyle(color = Red, fontSize = 12.sp)) }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        when {
                            navigating && !waiting -> {
                                Button("After navigation", true) { thread(name = "dystopo-update-install") { Updater.install() } }
                                Button("Install now", false) { thread(name = "dystopo-update-install") { Updater.install(now = true) } }
                            }
                            waiting -> Button("Install now", false) { thread(name = "dystopo-update-install") { Updater.install(now = true) } }
                            else -> Button("Install", true) { thread(name = "dystopo-update-install") { Updater.install() } }
                        }
                        Button("Not now", false) { Updater.decline() }
                    }
                }
            }
        }
    }
}

/** T110: the least room below the navigation card in which the offer still shows while navigating. */
private val MIN_ROOM = 96.dp

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
