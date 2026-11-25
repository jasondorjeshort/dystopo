package io.github.jasondorjeshort.dystopo.app.firstrun

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.SettingsUi
import io.github.jasondorjeshort.dystopo.app.help.HelpIcon
import io.github.jasondorjeshort.dystopo.app.help.HelpText
import io.github.jasondorjeshort.dystopo.app.online.DirectPrefs
import io.github.jasondorjeshort.dystopo.app.online.OnlineDetail
import io.github.jasondorjeshort.dystopo.app.online.OnlineService
import io.github.jasondorjeshort.dystopo.app.settings.SettingsGroup
import kotlin.concurrent.thread

/**
 * T80: the rows of Settings › Sources about the phone's own source reading (no server set): mobile data on or off
 * (on by default since T83: nothing is gated on Wi-Fi, the limit is the brake) and the per-session download limit. The same settings are
 * `directOnMobileData` and `directSessionMb` in `POST /settings`.
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun DirectSourcesRows() {
    val app = DystopoApp.instance
    var mobile by remember { mutableStateOf(DirectPrefs.onMobileData(app)) }
    var mb by remember { mutableStateOf(DirectPrefs.sessionMb(app)) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicText("With no server the phone reads the public sources itself: OpenStreetMap via OpenFreeMap, Copernicus, USGS and national surveys. A Dystopo server is faster.", style = TextStyle(color = Muted, fontSize = 11.sp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) { // T103: wraps at font scale 2.0; T109: choices centred on the help icon
            BasicText("Mobile data", Modifier.align(Alignment.CenterVertically), style = TextStyle(color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium))
            HelpIcon("direct.mobileData", Modifier.align(Alignment.CenterVertically)) // T109
            for ((label, value) in listOf("Use" to true, "Off" to false)) {
                Choice(label, mobile == value) {
                    mobile = value
                    thread(name = "dystopo-direct-setting") { OnlineService.get(app).setDirect(null, value) }
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) { // T103: wraps at font scale 2.0; T109: choices centred on the help icon
            BasicText("Limit per session", Modifier.align(Alignment.CenterVertically), style = TextStyle(color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium))
            HelpIcon("direct.sessionLimit", Modifier.align(Alignment.CenterVertically)) // T109
            for (value in (DirectPrefs.SESSION_MB_CHOICES + mb).distinct().sorted()) {
                Choice(if (value >= 1000) "${value / 1000} GB" else "$value MB", mb == value) {
                    mb = value
                    thread(name = "dystopo-direct-setting") { OnlineService.get(app).setDirect(value, null) }
                }
            }
        }
        OnlineDetailRow() // T341
        DownloadCapRow() // T369
    }
}

// T369
/**
 * T369: the public vector source's cap on one download, said where the phone's own source reading is set (the rule of its
 * terms, not a setting, ADR 0044): one line with its help ([DOWNLOAD_CAP_HELP]).
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun DownloadCapRow() {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        BasicText(DOWNLOAD_CAP_LINE, Modifier.align(Alignment.CenterVertically), style = TextStyle(color = Muted, fontSize = 12.sp))
        HelpIcon("direct.downloadCap", Modifier.align(Alignment.CenterVertically))
    }
}

/** T369: the row's line and its help text (ADR 0031: the coordinator's words, listed for the owner). */
internal const val DOWNLOAD_CAP_TITLE = "Downloads for offline"
internal const val DOWNLOAD_CAP_LINE = "Downloads for offline: at most 3,000 map tiles and 50 MB each"
internal const val DOWNLOAD_CAP_HELP =
    "With no server set, each download for offline use takes at most 3,000 map tiles and 50 MB from the public map " +
        "source, as its terms ask. A larger area is refused: make it smaller, choose less detail, or set a server. A " +
        "trip waits for a tap on Download for offline; the tiles ahead while you navigate are saved by themselves."
// end T369

// T341
/**
 * T341 (ADR 0066): "More detail from online", the per-device setting `onlineDetail` ([OnlineDetail]; `POST /settings
 * onlineDetail=1|0`), beside Mobile data; shown in Settings › Sources whether or not a server is set
 * (`settings/SettingsPages.kt`). Its reach today is the world overview's zooms.
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun OnlineDetailRow() {
    val app = DystopoApp.instance
    var on by remember { mutableStateOf(OnlineDetail.on) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        BasicText(ONLINE_DETAIL_LABEL, Modifier.align(Alignment.CenterVertically), style = TextStyle(color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium))
        HelpIcon("direct.onlineDetail", Modifier.align(Alignment.CenterVertically))
        for ((label, value) in listOf("On" to true, "Off" to false)) {
            Choice(label, on == value) {
                on = value
                thread(name = "dystopo-direct-setting") { OnlineDetail.set(app, value) }
            }
        }
    }
}

/** T341: the row's words and its help text (ADR 0066's label; ADR 0031: listed for the owner). */
internal const val ONLINE_DETAIL_LABEL = "More detail from online"
internal const val ONLINE_DETAIL_HELP =
    "Where the network has more detail than what this phone holds, download it. With the phone's own sources this counts " +
        "toward Mobile data and its limit. Off: the phone shows what it holds, and the network is asked only where nothing " +
        "here covers the view."
// end T341

/**
 * T109: the help pop-ups of the two rows above, by the key each row's [HelpIcon] names; a row removed here takes its
 * text with it. The rows show only while no server is set ([needs]); `POST /help/open` opens Data and storage.
 */
internal val DIRECT_SOURCES_HELP: List<HelpText> = run {
    val place = "${Screens.SETTINGS} › ${SettingsGroup.DATA.title} › ${Screens.SOURCES}" // T115: › as in messages
    val show = { SettingsUi.open(SettingsGroup.DATA) }
    listOf(
        HelpText("direct.mobileData", "Mobile data", "$place › Mobile data", needs = "no server set", show = show) {
            "With no server set, the phone downloads map data from the public sources itself as you move the map. Use " +
                "lets it do so over mobile data as well as Wi-Fi; Off downloads only on Wi-Fi. The limit per session " +
                "caps how much it takes either way."
        },
        HelpText("direct.sessionLimit", "Limit per session", "$place › Limit per session", needs = "no server set", show = show) {
            "The most map data the phone downloads from the public sources from opening the app until you close it. " +
                "A first look at new ground can take 75 to 100 MB. At the limit the map says so and keeps what it has: " +
                "download the area for offline use, or raise the limit."
        },
        HelpText("direct.onlineDetail", ONLINE_DETAIL_LABEL, "$place › $ONLINE_DETAIL_LABEL", show = show) { ONLINE_DETAIL_HELP }, // T341
        HelpText("direct.downloadCap", DOWNLOAD_CAP_TITLE, "$place › $DOWNLOAD_CAP_TITLE", needs = "no server set", show = show) { DOWNLOAD_CAP_HELP }, // T369
    )
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    BasicText(
        label,
        softWrap = false,
        style = TextStyle(color = if (selected) Paper else Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium),
        modifier = Modifier
            .background(if (selected) Accent else Color(0xEEFFFCF5), RoundedCornerShape(16.dp))
            .border(1.dp, if (selected) Accent else Line, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

private val Ink = Color(0xFF2B2420)
private val Muted = Color(0xFF6B5A4A)
private val Paper = Color(0xFFFFFCF5)
private val Accent = Color(0xFF7A4E2D)
private val Line = Color(0xFFE2D8C6)
