package io.github.jasondorjeshort.dystopo.app.settings

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.app.SettingsRoutes
import io.github.jasondorjeshort.dystopo.app.SettingsUi
import io.github.jasondorjeshort.dystopo.app.firstrun.FirstRun
import io.github.jasondorjeshort.dystopo.app.firstrun.Screens
import io.github.jasondorjeshort.dystopo.app.firstrun.Sources
import io.github.jasondorjeshort.dystopo.app.packs.PacksUi
import io.github.jasondorjeshort.dystopo.maplibre.MapLibreStyle
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.concurrent.thread

/**
 * T103: the settings sheet's list of groups. Each row is the group's title and a line naming what it holds;
 * a tap opens the group's page ([GroupPage]). [selected] marks the open page when list and page sit side by side.
 */
@Composable
internal fun GroupList(selected: SettingsGroup?, onOpen: (SettingsGroup) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (g in SettingsGroup.entries) {
            Row(
                Modifier.fillMaxWidth()
                    .background(if (g == selected) Selected else Color.Transparent, RoundedCornerShape(8.dp))
                    .clickable { onOpen(g) }
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    BasicText(g.title, style = TextStyle(color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
                    BasicText(g.summary, style = TextStyle(color = Muted, fontSize = 12.sp))
                }
                BasicText("›", Modifier.padding(start = 8.dp), style = TextStyle(color = Muted, fontSize = 20.sp))
            }
        }
    }
}

/** T103: one group's rows, in [SettingsLayout.rows] order (the same list `GET /settings/layout` returns). */
@Composable
internal fun GroupPage(group: SettingsGroup, change: (SettingsRoutes.Parsed.Ok) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        for (row in SettingsLayout.rows(group)) RowContent(row, change)
    }
}

/** Closes the sheet and opens another screen (the Storage screen, Offline areas, the sync sheet, Share). */
private fun leaveFor(open: () -> Unit) {
    SettingsUi.visible = false
    open()
}

@Composable
private fun RowContent(row: SettingsRow, change: (SettingsRoutes.Parsed.Ok) -> Unit) {
    val units = AppSettings.units
    when (row) {
        SettingsRow.SOURCES -> SourcesSection() // T79: first on the first page, so a fresh install finds the server field at once
        SettingsRow.STORAGE -> Setting(row.label, "packs, online tiles and contours on this phone, with their budgets") { // T74
            Option("${Screens.STORAGE} ›", false) { leaveFor { io.github.jasondorjeshort.dystopo.app.storage.StorageUi.visible = true } }
        }
        SettingsRow.OFFLINE_AREAS -> Setting(row.label, "areas kept on this phone for use with no signal: their size, pin, update or remove") {
            Option("${row.label} ›", false) { leaveFor { io.github.jasondorjeshort.dystopo.app.areas.Areas.screenOpen = true } }
        }
        SettingsRow.MAP_OPTIONS -> io.github.jasondorjeshort.dystopo.app.options.MapOptionsSection { change(SettingsRoutes.Parsed.Ok(null, false, null, null, it)) } // T47, placed whole
        SettingsRow.UNITS -> Setting(row.label, if (AppSettings.unitPreference.units == null) "from your region until you choose" else null) {
            Option("Feet", units == ContourUnits.FEET) { change(SettingsRoutes.Parsed.Ok(null, true, ContourUnits.FEET, null)) }
            Option("Metres", units == ContourUnits.METERS) { change(SettingsRoutes.Parsed.Ok(null, true, ContourUnits.METERS, null)) }
        }
        SettingsRow.TEXT_SIZE -> {
            val scale = AppSettings.textScale
            Setting(row.label, null) {
                Option("-", false) { change(SettingsRoutes.Parsed.Ok(stepScale(scale, -1), false, null, null)) }
                BasicText(String.format(Locale.US, "%.1fx", scale), Modifier.padding(horizontal = 6.dp, vertical = 8.dp), style = TextStyle(color = Ink, fontSize = 14.sp))
                Option("+", false) { change(SettingsRoutes.Parsed.Ok(stepScale(scale, 1), false, null, null)) }
            }
        }
        SettingsRow.ROUTE_SNAP -> {
            // T57: how far a long-pressed route start or end may be from the nearest trail or road.
            val snap = AppSettings.routeSnapM
            Setting(row.label, "how far a route's start or end may be from a trail or road") {
                for (m in SNAP_CHOICES) {
                    Option(io.github.jasondorjeshort.dystopo.render.UnitFormat.distance(m.toDouble(), units), snap == m) {
                        change(SettingsRoutes.Parsed.Ok(null, false, null, null, routeSnapM = m))
                    }
                }
            }
        }
        SettingsRow.NAV_STALE -> {
            // T91: the navigation thresholds (T65's constants).
            val stale = AppSettings.navStaleS
            Setting(row.label, "an older fix is not used, and the bar says the fix is lost") {
                for (s in STALE_CHOICES) Option("$s s", stale == s) { change(SettingsRoutes.Parsed.Ok(null, false, null, null, navStaleS = s)) }
            }
        }
        SettingsRow.NAV_SNOOZE -> {
            val snoozeMin = AppSettings.navSnoozeMin
            Setting(row.label, "how long the off-route banner's Snooze holds the alert") {
                for (m in SNOOZE_CHOICES) Option("$m min", snoozeMin == m) { change(SettingsRoutes.Parsed.Ok(null, false, null, null, navSnoozeMin = m)) }
            }
        }
        SettingsRow.SYNC_STATUS -> Setting(row.label, "the last sync of tracks and routes, what it moved and what is still waiting") {
            Option("${row.label} ›", false) {
                leaveFor { io.github.jasondorjeshort.dystopo.app.sync.SyncUi.visible = true; io.github.jasondorjeshort.dystopo.app.sync.SyncUi.refresh() }
            }
        }
        SettingsRow.SHARE -> Setting(row.label, "sync tracks and routes with a nearby phone, and send or receive maps") {
            Option("${row.label} ›", false) { leaveFor { io.github.jasondorjeshort.dystopo.app.share.ShareUi.visible = true } }
        }
        SettingsRow.UPDATES -> io.github.jasondorjeshort.dystopo.app.update.UpdateRow() // T83: version, Check for updates, Install
        SettingsRow.WARMUP -> {
            val warmup = AppSettings.warmup
            Setting(row.label, "prepares contours in the background after a pack opens") {
                Option("On", warmup) { change(SettingsRoutes.Parsed.Ok(null, false, null, true)) }
                Option("Off", !warmup) { change(SettingsRoutes.Parsed.Ok(null, false, null, false)) }
            }
        }
        SettingsRow.CREDITS -> {
            val context = LocalContext.current
            Setting(row.label, "where the map, the elevation and the labels come from, and their licenses") {
                Option("Map data and sources ›", false) {
                    leaveFor { context.startActivity(Intent(context, io.github.jasondorjeshort.dystopo.app.about.AboutActivity::class.java)) }
                }
            }
        }
    }
}

/**
 * T79: Settings › Sources: the Dystopo server (online tiles where no pack covers the view, the pack catalog,
 * "Download the map around me"). Save applies it like `POST /settings?server=` (validated; plain http only to a
 * home-network address, [io.github.jasondorjeshort.dystopo.app.firstrun.CleartextPolicy]) and then tests it;
 * Test fetches the catalog of what is typed and shows the pack count or the error; "Packs ›" opens the pack
 * list with the server's catalog. T103: the first row of Settings › Data and storage.
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun SourcesSection() {
    var text by remember { mutableStateOf(AppSettings.server) }
    var error by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(500); tick++ } }
    val testLine = remember(tick, SettingsUi.revision) { Sources.summary() }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicText(Screens.SOURCES, style = TextStyle(color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold))
        BasicText("Dystopo server, for example http://192.168.1.20:8080. Plain http only on your home network; https anywhere.", style = TextStyle(color = Muted, fontSize = 11.sp))
        BasicTextField(
            text, { text = it; error = null },
            singleLine = true,
            textStyle = TextStyle(color = Ink, fontSize = 14.sp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done, autoCorrect = false),
            decorationBox = { inner ->
                Box {
                    if (text.isEmpty()) BasicText("http://host:port", style = TextStyle(color = Muted, fontSize = 14.sp))
                    inner()
                }
            },
            modifier = Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(6.dp)).border(1.dp, Line, RoundedCornerShape(6.dp)).padding(10.dp),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Option("Save", false) {
                if (!SettingsUi.busy) {
                    SettingsUi.busy = true
                    val value = text
                    thread(name = "dystopo-settings-server") {
                        try {
                            error = FirstRun.setServer(value)
                            if (error == null) {
                                text = AppSettings.server
                                if (AppSettings.server.isNotEmpty()) Sources.test(AppSettings.server)
                            }
                        } finally {
                            SettingsUi.busy = false
                            SettingsUi.revision++
                        }
                    }
                }
            }
            Option("Test", false) { val value = text; thread(name = "dystopo-server-test") { Sources.test(value) } }
            Option(Screens.PACKS + " ›", false) {
                SettingsUi.visible = false
                PacksUi.catalogOpen = true
                PacksUi.fitRequest++
                PacksUi.visible = true
            }
        }
        val saved = AppSettings.server
        BasicText(if (saved.isEmpty()) "Saved: none (offline only)" else "Saved: $saved", style = TextStyle(color = Muted, fontSize = 12.sp))
        error?.let { BasicText(it, style = TextStyle(color = Color(0xFFB0302A), fontSize = 12.sp)) }
        testLine?.let { BasicText(it, style = TextStyle(color = if (it.startsWith("Test failed")) Color(0xFFB0302A) else Accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)) }
        FirstRun.message?.let { BasicText(it, style = TextStyle(color = Accent, fontSize = 12.sp)) }
        if (saved.isEmpty()) io.github.jasondorjeshort.dystopo.app.firstrun.DirectSourcesRows() // T80: mobile data, limit per session
    }
}

/** T57: the snap distances the sheet offers (any value in [AppSettings.ROUTE_SNAP_RANGE] through `/settings`). */
private val SNAP_CHOICES = listOf(300, 500, 1000)
private val STALE_CHOICES = listOf(15, 30, 60, 120) // T91
private val SNOOZE_CHOICES = listOf(5, 10, 30)

private fun stepScale(scale: Double, dir: Int): Double =
    (Math.round((scale + 0.1 * dir) * 10) / 10.0).coerceIn(MapLibreStyle.MIN_TEXT_SCALE, MapLibreStyle.MAX_TEXT_SCALE)

/**
 * A row: its label, the hint under it, then its choices. T103: the choices wrap onto further lines instead of
 * running off the right edge (the label used to sit in a fixed column to their left, which at font scale 2.0
 * pushed "1 km" and "120 s" out of the sheet).
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun Setting(label: String, hint: String?, options: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BasicText(label, style = TextStyle(color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium))
        hint?.let { BasicText(it, style = TextStyle(color = Muted, fontSize = 11.sp)) }
        FlowRow(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { options() }
    }
}

@Composable
internal fun Option(label: String, selected: Boolean, onClick: () -> Unit) {
    BasicText(
        label,
        softWrap = false, // T57: "0.62 mi" on one line
        style = TextStyle(color = if (selected) Paper else Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium),
        modifier = Modifier
            .background(if (selected) Accent else Color(0xEEFFFCF5), RoundedCornerShape(16.dp))
            .border(1.dp, if (selected) Accent else Line, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/** A link in the sheet's header ("‹ Settings"). */
@Composable
internal fun HeaderLink(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    BasicText(label, modifier.clickable(onClick = onClick).padding(vertical = 6.dp).widthIn(min = 48.dp), style = TextStyle(color = LinkBlue, fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
}

internal val Ink = Color(0xFF2B2420)
internal val Muted = Color(0xFF6B5A4A)
internal val Paper = Color(0xFFFFFCF5)
internal val Accent = Color(0xFF7A4E2D)
internal val Line = Color(0xFFE2D8C6)
private val Selected = Color(0xFFF1E6D3)
private val LinkBlue = Color(0xFF2C7BB6)
