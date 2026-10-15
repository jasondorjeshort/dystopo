package io.github.jasondorjeshort.dystopo.app.firstrun

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.SettingsUi
import io.github.jasondorjeshort.dystopo.app.online.OnlineService
import io.github.jasondorjeshort.dystopo.app.packs.PackManager
import io.github.jasondorjeshort.dystopo.app.packs.PacksUi
import kotlinx.coroutines.delay
import java.util.Locale

private val Ink = Color(0xFF2B2420)
private val Muted = Color(0xFF6B5A4A)
private val Paper = Color(0xFFFFFCF5)
private val Accent = Color(0xFF7A4E2D)
private val Line = Color(0xFFE2D8C6)
private val Red = Color(0xFFB0302A)

private class CardUi(val noPack: Boolean, val server: String, val progress: String?, val importing: Boolean, val direct: Boolean, val notice: String? = null)

/**
 * T79: the first-run card over the map while no pack is installed: what is missing and the three ways to
 * get a map ([FirstRun]). Hidden while the pack list or the settings sheet is open, and after "Not now"
 * (then [fallback], the plain no-pack line, shows when the map is empty). Drawn by one line in the map screen,
 * which also hosts the file picker ([ImportPickerHost]).
 */
@Composable
fun FirstRunCard(fallback: String?) {
    ImportPickerHost()
    val app = DystopoApp.instance
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(700)
            tick++
        }
    }
    val ui = remember(tick) {
        val region = OnlineService.get(app).region
        val r = region.json()
        val manager = PackManager.get(app)
        val imp = manager.importState
        val progress = when {
            manager.importRunning -> "Importing: ${mb((imp["bytesDone"] as? Number)?.toLong() ?: 0L)}" + ((imp["bytesTotal"] as? Number)?.toLong()?.let { " of ${mb(it)}" } ?: "")
            region.running -> String.format(Locale.US, "%s %s: %.0f%%", if (r["state"] != "building") "Downloading" else if (OnlineService.get(app).isDirect) "Building on this phone" else "Building on the server", r["name"], (r["percent"] as? Number)?.toDouble() ?: 0.0)
            r["state"] == "failed" -> "Download failed: ${r["error"]}"
            FirstRun.aroundMe == "locating" -> "Finding your location..."
            manager.downloads.activeCount() > 0 -> "Downloading a pack..."
            else -> null
        }
        OnlineService.get(app).let { o -> CardUi(FirstRun.noPack(app), AppSettings.server, progress, manager.importRunning, o.isDirect, if (o.isDirect) o.direct.notice() else null) }
    }
    // T106 (a T95 follow-up): a trip, a planned route or navigation owns the bottom of the map; the card, drawn after
    // them, covered the trip card after a relaunch with no pack (online, or a trip synced in). It comes back when they close.
    val cardsBelow = cardsOwnTheBottom(
        io.github.jasondorjeshort.dystopo.app.trips.Trips.editing != null || io.github.jasondorjeshort.dystopo.app.trips.Trips.listOpen,
        io.github.jasondorjeshort.dystopo.app.routing.RouteSession.phase, io.github.jasondorjeshort.dystopo.app.nav.NavSession.active,
    )
    val show = ui.noPack && !FirstRun.dismissed && !PacksUi.visible && !SettingsUi.visible && !cardsBelow
    FirstRunCardState.visible = show
    FirstRunCardState.yieldedTo = if (ui.noPack && !FirstRun.dismissed && cardsBelow) "bottomCards" else null
    Box(Modifier.fillMaxSize()) {
        if (!show) {
            if (ui.noPack && fallback != null && !PacksUi.visible && !SettingsUi.visible && !cardsBelow) {
                BasicText(
                    fallback,
                    style = TextStyle(color = Ink, fontSize = 16.sp, textAlign = TextAlign.Center),
                    modifier = Modifier.align(Alignment.Center).padding(24.dp).background(Color(0xEEFFFCF5), RoundedCornerShape(8.dp)).padding(16.dp),
                )
            }
            return@Box
        }
        // T106: above the update offer when one shows (it covered the card's lower buttons).
        val above = io.github.jasondorjeshort.dystopo.app.MapInsets.updateCardTop
        Column(
            Modifier.align(Alignment.BottomCenter)
                .let { m -> if (above > 0.dp) m.padding(start = 12.dp, end = 12.dp, bottom = above + 8.dp) else m.navigationBarsPadding().padding(start = 12.dp, end = 12.dp, bottom = 36.dp) }
                .widthIn(max = 460.dp).fillMaxWidth()
                .background(Paper, RoundedCornerShape(14.dp)).border(1.dp, Line, RoundedCornerShape(14.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BasicText("No map on this phone yet", style = TextStyle(color = Ink, fontSize = 19.sp, fontWeight = FontWeight.Bold))
            BasicText(
                when {
                    ui.server.isNotEmpty() -> "Server: ${ui.server}"
                    ui.direct && ui.notice != null -> ui.notice
                    ui.direct -> "The map comes straight from public sources (OpenStreetMap via OpenFreeMap, Copernicus, USGS and national surveys). Download the area around you to use it offline. A Dystopo server in ${Screens.SETTINGS} › ${Screens.SOURCES} is faster."
                    else -> "No server set. Set one in ${Screens.SETTINGS} › ${Screens.SOURCES} to download maps, or import pack files you have."
                },
                style = TextStyle(color = Muted, fontSize = 13.sp),
            )
            CardButton("Download the map around me", primary = true) { FirstRun.message = null; FirstRun.downloadAroundMe() }
            if (ui.direct || ui.server.isNotEmpty()) CardButton("Show the map around me") { FirstRun.message = null; FirstRun.showAroundMe() } // T80
            if (ui.server.isNotEmpty() || !ui.direct) CardButton("Choose from server") { FirstRun.message = null; FirstRun.chooseFromServer() }
            CardButton("Import pack files") { FirstRun.message = null; ImportPicker.request() }
            ui.progress?.let { BasicText(it, style = TextStyle(color = if (it.startsWith("Download failed")) Red else Accent, fontSize = 13.sp)) }
            FirstRun.message?.let { BasicText(it, style = TextStyle(color = if (it.contains("failed") || it.contains("permission")) Red else Accent, fontSize = 13.sp)) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                BasicText(Screens.SETTINGS, Modifier.clickable { SettingsUi.open(io.github.jasondorjeshort.dystopo.app.settings.SettingsLayout.SERVER_GROUP) }.padding(vertical = 4.dp), style = TextStyle(color = Color(0xFF2C7BB6), fontSize = 14.sp, fontWeight = FontWeight.Bold))
                BasicText("Not now", Modifier.clickable { FirstRun.dismissed = true }.padding(vertical = 4.dp), style = TextStyle(color = Muted, fontSize = 14.sp))
            }
        }
    }
}

@Composable
private fun CardButton(label: String, primary: Boolean = false, onClick: () -> Unit) {
    BasicText(
        label,
        style = TextStyle(color = if (primary) Paper else Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center),
        modifier = Modifier.fillMaxWidth()
            .background(if (primary) Accent else Color(0xFFF4EDE0), RoundedCornerShape(10.dp))
            .border(1.dp, if (primary) Accent else Line, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    )
}

private fun mb(bytes: Long) = String.format(Locale.US, "%.1f MB", bytes / 1048576.0)

/**
 * T106: whether the bottom of the map belongs to another card the first-run card must not cover: the trip card or the
 * Routes list ([trip]), a planned route's card (route [routePhase] other than `idle`), or navigation ([navigating]). Pure, tested.
 */
internal fun cardsOwnTheBottom(trip: Boolean, routePhase: String, navigating: Boolean): Boolean = trip || routePhase != "idle" || navigating
