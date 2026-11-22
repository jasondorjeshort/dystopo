package io.github.jasondorjeshort.dystopo.app.firstrun

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.SettingsUi
import io.github.jasondorjeshort.dystopo.app.control
import io.github.jasondorjeshort.dystopo.app.online.OnlineService
import io.github.jasondorjeshort.dystopo.app.packs.PackManager
import io.github.jasondorjeshort.dystopo.app.packs.PacksUi
import java.util.Locale

private val Ink = Color(0xFF2B2420)
private val Muted = Color(0xFF6B5A4A)
private val Paper = Color(0xFFFFFCF5)
private val Accent = Color(0xFF7A4E2D)
private val Line = Color(0xFFE2D8C6)
private val Red = Color(0xFFB0302A)
private val Link = Color(0xFF2C7BB6)

private class CardUi(val noPack: Boolean, val server: String, val progress: String?, val importing: Boolean, val direct: Boolean, val notice: String? = null)

/**
 * T79: the first-run card over the map while no pack is installed: what is missing and the ways to get a map, each
 * with its sentence and one of them filled ([firstRunChoices], T220). The choices scroll when the font scale outgrows
 * the screen; the progress, the message and the two links stay in view under them. Hidden while the pack list or the settings sheet is open, and after "Not now"
 * (then [fallback], the plain no-pack line, shows when the map is empty). Drawn by one line in the map screen,
 * which also hosts the file picker ([ImportPickerHost]).
 *
 * T320: one of the layout rule's bottom cards (`OverlayLayout`, `MapLayout`): placed under the whole top block (Search,
 * the chips, the online column) with the scale bar, the readout and the position buttons standing on it; its body scrolls
 * in the height the rule gives it, and what still does not fit is hidden by the rule's ladder with its reason.
 */
@Composable
fun FirstRunCard(fallback: String?) {
    ImportPickerHost()
    val app = DystopoApp.instance
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            io.github.jasondorjeshort.dystopo.app.Foreground.delay(700) // T150: paused in the background
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
            manager.downloads.activeCount() > 0 -> "Downloading a map..."
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
    LaunchedEffect(tick, show) { if (show) FirstRun.refreshEstimate(app) } // T220: the size in the first choice's sentence
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
        // T320: the layout rule places and sizes the card (`OverlayLayout`, a bottom card under the whole top block, the
        // lower controls standing on it) through its control node; the paddings here only hold it for the first frame,
        // before the rule has measured it: under the search box (T220), above the update offer (T106) and the switch (T315).
        // T326: the switch's floor, or the credit line's top where it stands above the switch (choice h).
        val floor = if (io.github.jasondorjeshort.dystopo.app.controls.ViewSwitch.floorDp > 0f) io.github.jasondorjeshort.dystopo.app.about.AttributionInsets.bottom else 0.dp
        val above = maxOf(io.github.jasondorjeshort.dystopo.app.MapInsets.updateCardTop, floor)
        val scroll = rememberScrollState()
        FirstRunCardState.scroll = scroll
        Column(
            Modifier.align(Alignment.BottomCenter)
                .let { m -> io.github.jasondorjeshort.dystopo.app.TopChips.searchBottom.let { top -> if (top > 0.dp) m.padding(top = top + 4.dp) else m.statusBarsPadding() } }
                .let { m -> if (above > 0.dp) m.padding(start = 12.dp, end = 12.dp, bottom = above + 8.dp) else m.navigationBarsPadding().padding(start = 12.dp, end = 12.dp, bottom = 36.dp) }
                .control("firstRunCard") // T238; T320: within the rule's width and height, so its width limits come after
                .widthIn(max = 460.dp).fillMaxWidth()
                .background(Paper, RoundedCornerShape(14.dp)).border(1.dp, Line, RoundedCornerShape(14.dp))
                .pointerInput(Unit) { detectTapGestures { } } // T220: a touch on its paper stays out of the map
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // T320: below its least (a phone on its side at a large font scale) the progress, the message and the links
            // scroll with the choices, so every action stays reachable.
            val allScroll = FirstRunCardUiState.allScroll
            val footer: @Composable () -> Unit = {
                ui.progress?.let { BasicText(it, style = TextStyle(color = if (it.startsWith("Download failed")) Red else Accent, fontSize = 13.sp)) }
                FirstRun.message?.let { BasicText(it, style = TextStyle(color = if (it.contains("failed") || it.contains("permission")) Red else Accent, fontSize = 13.sp)) }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        BasicText(Screens.SETTINGS, Modifier.clickable { SettingsUi.open(io.github.jasondorjeshort.dystopo.app.settings.SettingsLayout.SERVER_GROUP) }.padding(vertical = 4.dp), style = TextStyle(color = Link, fontSize = 14.sp, fontWeight = FontWeight.Bold))
                        BasicText("Not now", Modifier.clickable { FirstRun.dismissed = true }.padding(vertical = 4.dp), style = TextStyle(color = Muted, fontSize = 14.sp))
                    }
                    BasicText(NOT_NOW_LINE, style = TextStyle(color = Muted, fontSize = 12.sp))
                }
            }
            Column(
                Modifier.weight(1f, fill = false)
                    .layout { m, c -> m.measure(c).let { p -> FirstRunCardState.bodyPx = p.height; layout(p.width, p.height) { p.place(0, 0) } } } // T320: the body's height for the rule
                    .verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                BasicText("No map on this phone yet", Modifier.onGloballyPositioned { FirstRunCardState.headTop(it.positionInRoot().y) }, style = TextStyle(color = Ink, fontSize = 19.sp, fontWeight = FontWeight.Bold))
                BasicText(firstRunBody(ui.server, ui.direct, ui.notice), style = TextStyle(color = Muted, fontSize = 13.sp))
                val choices = firstRunChoices(ui.server, ui.direct, FirstRun.estimateBytes, AppSettings.units)
                val firstFilled = choices.firstOrNull { it.weight == ChoiceWeight.FILLED }
                choices.forEach { c ->
                    ChoiceBlock(c, c === firstFilled) {
                        FirstRun.message = null
                        when (c.id) {
                            "download" -> FirstRun.downloadAroundMe()
                            "look" -> FirstRun.showAroundMe() // T80
                            "server" -> FirstRun.chooseFromServer()
                            else -> ImportPicker.request()
                        }
                    }
                }
                if (allScroll) footer()
            }
            if (!allScroll) footer()
        }
    }
}

/** T220: a choice and its sentence: a filled or outlined button, or a text action. */
@Composable
private fun ChoiceBlock(choice: FirstRunChoice, measured: Boolean, onClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        if (choice.weight == ChoiceWeight.TEXT) {
            BasicText(choice.label, Modifier.clickable(onClick = onClick).padding(vertical = 4.dp), style = TextStyle(color = Link, fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
        } else {
            val filled = choice.weight == ChoiceWeight.FILLED
            BasicText(
                choice.label,
                style = TextStyle(color = if (filled) Paper else Accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center),
                modifier = Modifier.fillMaxWidth()
                    .let { m -> if (measured) m.onGloballyPositioned { FirstRunCardState.actionBottom(it.positionInRoot().y + it.size.height) } else m } // T323
                    .background(if (filled) Accent else Paper, RoundedCornerShape(10.dp))
                    .border(1.dp, Accent, RoundedCornerShape(10.dp))
                    .clickable(onClick = onClick)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            )
        }
        BasicText(choice.sentence, style = TextStyle(color = Muted, fontSize = 13.sp))
    }
}

private fun mb(bytes: Long) = String.format(Locale.US, "%.1f MB", bytes / 1048576.0)

/**
 * T106: whether the bottom of the map belongs to another card the first-run card must not cover: the trip card or the
 * Routes list ([trip]), a planned route's card (route [routePhase] other than `idle`), or navigation ([navigating]). Pure, tested.
 */
internal fun cardsOwnTheBottom(trip: Boolean, routePhase: String, navigating: Boolean): Boolean = trip || routePhase != "idle" || navigating
