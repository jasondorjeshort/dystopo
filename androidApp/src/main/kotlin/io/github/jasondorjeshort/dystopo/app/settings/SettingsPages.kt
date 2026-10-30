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
import io.github.jasondorjeshort.dystopo.app.help.HelpIcon
import io.github.jasondorjeshort.dystopo.app.help.HelpText
import io.github.jasondorjeshort.dystopo.app.packs.PacksUi
import io.github.jasondorjeshort.dystopo.app.tracking.BatteryExemption
import io.github.jasondorjeshort.dystopo.app.tracking.RecordingProfiles
import io.github.jasondorjeshort.dystopo.model.Activity
import io.github.jasondorjeshort.dystopo.maplibre.MapLibreStyle
import io.github.jasondorjeshort.dystopo.render.ContourUnits
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
                    BasicText(SettingsLayout.summary(g, AppSettings.cacheBudgetMb), style = TextStyle(color = Muted, fontSize = 12.sp)) // T115: Data and storage names the budget
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
        SettingsRow.UNITS -> Setting(row.label, if (AppSettings.unitPreference.units == null) "from your region until you choose" else null, help = "settings.units") {
            Option("Feet", units == ContourUnits.FEET) { change(SettingsRoutes.Parsed.Ok(null, true, ContourUnits.FEET, null)) }
            Option("Metres", units == ContourUnits.METERS) { change(SettingsRoutes.Parsed.Ok(null, true, ContourUnits.METERS, null)) }
        }
        SettingsRow.TEXT_SIZE -> {
            val scale = AppSettings.textScale
            Setting(row.label, null, help = "settings.textSize") {
                Option("-", false) { change(SettingsRoutes.Parsed.Ok(stepScale(scale, -1), false, null, null)) }
                BasicText(String.format(Locale.US, "%.1fx", scale), Modifier.padding(horizontal = 6.dp, vertical = 8.dp), style = TextStyle(color = Ink, fontSize = 14.sp))
                Option("+", false) { change(SettingsRoutes.Parsed.Ok(stepScale(scale, 1), false, null, null)) }
            }
        }
        SettingsRow.ROUTE_SNAP -> {
            // T57: how far a long-pressed route start or end may be from the nearest trail or road.
            val snap = AppSettings.routeSnapM
            Setting(row.label, "how far a route's start or end may be from a trail or road", help = "settings.routeSnap") {
                for (m in SNAP_CHOICES) {
                    Option(io.github.jasondorjeshort.dystopo.render.UnitFormat.distance(m.toDouble(), units), snap == m) {
                        change(SettingsRoutes.Parsed.Ok(null, false, null, null, routeSnapM = m))
                    }
                }
            }
        }
        SettingsRow.NAV_SPEECH -> {
            // T126: spoken turn directions (nav/NavSpeech), off by default.
            val on = AppSettings.navSpeech
            Setting(row.label, "says each turn aloud as the card shows it", help = "settings.navSpeech") {
                Option("On", on) { change(SettingsRoutes.Parsed.Ok(null, false, null, null, navSpeech = true)) }
                Option("Off", !on) { change(SettingsRoutes.Parsed.Ok(null, false, null, null, navSpeech = false)) }
            }
        }
        SettingsRow.NAV_STALE -> {
            // T91: the navigation thresholds (T65's constants).
            val stale = AppSettings.navStaleS
            Setting(row.label, "an older fix is not used, and the bar says the fix is lost", help = "settings.navStale") {
                for (s in STALE_CHOICES) Option("$s s", stale == s) { change(SettingsRoutes.Parsed.Ok(null, false, null, null, navStaleS = s)) }
            }
        }
        SettingsRow.NAV_SNOOZE -> {
            val snoozeMin = AppSettings.navSnoozeMin
            Setting(row.label, "how long the off-route banner's Snooze holds the alert", help = "settings.navSnooze") {
                for (m in SNOOZE_CHOICES) Option("$m min", snoozeMin == m) { change(SettingsRoutes.Parsed.Ok(null, false, null, null, navSnoozeMin = m)) }
            }
        }
        // T163: the turn-around clock's deadline and margin (ADR 0044: the researched defaults, sunset and 30 minutes).
        SettingsRow.TURN_DEADLINE -> {
            val d = io.github.jasondorjeshort.dystopo.nav.DeadlineSetting.parse(AppSettings.turnDeadline) ?: io.github.jasondorjeshort.dystopo.nav.DeadlineSetting.SUNSET
            val typed = d.kind == io.github.jasondorjeshort.dystopo.nav.DeadlineKind.TIME
            Setting(row.label, "what the navigation card's turn-back time counts to", help = "settings.turnDeadline") {
                Option("Sunset", d.kind == io.github.jasondorjeshort.dystopo.nav.DeadlineKind.SUNSET) { change(SettingsRoutes.Parsed.Ok(null, false, null, null, turnDeadline = "sunset")) }
                Option("Civil twilight", d.kind == io.github.jasondorjeshort.dystopo.nav.DeadlineKind.CIVIL) { change(SettingsRoutes.Parsed.Ok(null, false, null, null, turnDeadline = "civil")) }
                Option("A time I type", typed) { if (!typed) change(SettingsRoutes.Parsed.Ok(null, false, null, null, turnDeadline = AppSettings.turnTypedTime)) }
                if (typed) TypedValue(d.value, "HH:MM", KeyboardType.Number) { raw ->
                    val v = io.github.jasondorjeshort.dystopo.nav.DeadlineSetting.parse(raw)?.takeIf { it.kind == io.github.jasondorjeshort.dystopo.nav.DeadlineKind.TIME }
                        ?: return@TypedValue "a time like 18:30"
                    change(SettingsRoutes.Parsed.Ok(null, false, null, null, turnDeadline = v.value))
                    null
                }
            }
        }
        SettingsRow.TURN_MARGIN -> {
            val margin = AppSettings.turnMarginMin
            val presets = io.github.jasondorjeshort.dystopo.nav.TurnAround.MARGIN_PRESETS
            Setting(row.label, "how long before the deadline to be back out", help = "settings.turnMargin") {
                for (m in presets) Option("$m min", margin == m) { change(SettingsRoutes.Parsed.Ok(null, false, null, null, turnMarginMin = m)) }
                TypedValue(if (margin in presets) "" else margin.toString(), "other", KeyboardType.Number, suffix = "min") { raw ->
                    val r = io.github.jasondorjeshort.dystopo.nav.TurnAround.MARGIN_RANGE
                    val v = raw.trim().toIntOrNull()?.takeIf { it in r } ?: return@TypedValue "${r.first} to ${r.last} minutes"
                    change(SettingsRoutes.Parsed.Ok(null, false, null, null, turnMarginMin = v))
                    null
                }
            }
        }
        SettingsRow.SYNC_STATUS -> Setting(row.label, "the last sync of tracks and routes, what it moved and what is still waiting", help = "settings.syncStatus") {
            Option("${row.label} ›", false) {
                leaveFor { io.github.jasondorjeshort.dystopo.app.sync.SyncUi.visible = true; io.github.jasondorjeshort.dystopo.app.sync.SyncUi.refresh() }
            }
        }
        SettingsRow.SHARE -> Setting(row.label, "sync tracks and routes with a nearby phone, and send or receive maps", help = "settings.share") {
            Option("${row.label} ›", false) { leaveFor { io.github.jasondorjeshort.dystopo.app.share.ShareUi.visible = true } }
        }
        SettingsRow.UPDATES -> io.github.jasondorjeshort.dystopo.app.update.UpdateRow() // T83: version, Check for updates, Install
        SettingsRow.DIAGNOSTICS -> DiagnosticsRow() // T143: memory now and the last session's, Copy report
        SettingsRow.REPORTS -> io.github.jasondorjeshort.dystopo.app.reports.ReportRow() // T145: crashes, freezes and kills, Open GitHub issue
        SettingsRow.WARMUP -> {
            val warmup = AppSettings.warmup
            Setting(row.label, "prepares contours in the background after a pack opens", help = "settings.warmup") {
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
        SettingsRow.PACE -> {
            // T164: route times fitted to the user's tracks; a refit when the track list changed (only with a trail network loaded).
            val app = io.github.jasondorjeshort.dystopo.app.DystopoApp.instance
            androidx.compose.runtime.LaunchedEffect(Unit) { io.github.jasondorjeshort.dystopo.app.routing.PaceSettings.refitSoon(app, "settings") }
            val standard = AppSettings.paceStandard
            val pace = io.github.jasondorjeshort.dystopo.app.routing.PaceSettings
            Setting(row.label, pace.rowText(units), help = "settings.pace") {
                Option(pace.YOUR_PACE, !standard) { change(SettingsRoutes.Parsed.Ok(null, false, null, null, paceStandard = false)) }
                Option(pace.USE_STANDARD, standard) { change(SettingsRoutes.Parsed.Ok(null, false, null, null, paceStandard = true)) }
                Option(pace.RESET, false) { Thread { pace.reset(app) }.start() }
            }
        }
        // T172: recording per activity; the four choice rows edit the remembered activity's profile.
        SettingsRow.TRACK_ACTIVITY -> {
            val a = AppSettings.trackActivity
            Setting(row.label, "what Record starts with, and the activity the rows below set", help = "settings.trackActivity") {
                for (x in Activity.entries) Option(RecordingProfiles.label(x), a == x) { change(SettingsRoutes.Parsed.Ok(null, false, null, null, trackActivity = x)) }
            }
        }
        SettingsRow.TRACK_ACCURACY -> {
            val p = AppSettings.trackProfile()
            Setting(row.label, "${RecordingProfiles.label(p.activity)}: fixes less accurate than this are kept but marked, and left out of the distance", help = "settings.trackAccuracy") {
                for (m in RecordingProfiles.ACCURACY_CHOICES) {
                    Option(m?.let { io.github.jasondorjeshort.dystopo.render.UnitFormat.distance(it, units) } ?: "Off", p.accuracyBoundM == m) {
                        change(SettingsRoutes.Parsed.Ok(null, false, null, null, trackEdit = RecordingProfiles.Edit(accuracyGiven = true, accuracyM = m)))
                    }
                }
            }
        }
        SettingsRow.TRACK_AUTO_PAUSE -> {
            val p = AppSettings.trackProfile()
            val hint = "${RecordingProfiles.label(p.activity)}: holds the recording while you stand still and goes on when you move" +
                if (p.activity.suggestsAutoPause && !p.autoPause) " (suggested for this activity)" else ""
            Setting(row.label, hint, help = "settings.trackAutoPause") {
                Option("On", p.autoPause) { change(SettingsRoutes.Parsed.Ok(null, false, null, null, trackEdit = RecordingProfiles.Edit(autoPause = true))) }
                Option("Off", !p.autoPause) { change(SettingsRoutes.Parsed.Ok(null, false, null, null, trackEdit = RecordingProfiles.Edit(autoPause = false))) }
            }
        }
        SettingsRow.TRACK_FALLBACK -> {
            val p = AppSettings.trackProfile()
            Setting(row.label, "${RecordingProfiles.label(p.activity)}: while the GPS is silent, rough positions from mobile networks and Wi-Fi are recorded and marked", help = "settings.trackFallback") {
                Option("On", p.networkFallback) { change(SettingsRoutes.Parsed.Ok(null, false, null, null, trackEdit = RecordingProfiles.Edit(networkFallback = true))) }
                Option("Off", !p.networkFallback) { change(SettingsRoutes.Parsed.Ok(null, false, null, null, trackEdit = RecordingProfiles.Edit(networkFallback = false))) }
            }
        }
        SettingsRow.TRACK_INTERVAL -> {
            val p = AppSettings.trackProfile()
            Setting(row.label, "${RecordingProfiles.label(p.activity)}: how often a point is recorded", help = "settings.trackInterval") {
                for (s in RecordingProfiles.INTERVAL_CHOICES_S) {
                    Option(if (s < 60) "$s s" else "${s / 60} min", p.intervalMs == s * 1000L) {
                        change(SettingsRoutes.Parsed.Ok(null, false, null, null, trackEdit = RecordingProfiles.Edit(intervalMs = s * 1000L)))
                    }
                }
            }
        }
        SettingsRow.TRACK_BATTERY -> {
            val context = LocalContext.current
            val exempt = remember(SettingsUi.revision) { BatteryExemption.exempt(context) }
            Setting(row.label, if (exempt) "not limited: recording goes on with the screen off" else "Android may stop a long recording to save battery", help = "settings.trackBattery") {
                Option("Battery settings ›", false) { BatteryExemption.openSettings(context) }
            }
        }
        // T161: the live readout beside the scale bar; the entry rows edit the remembered activity's options.
        SettingsRow.READOUT_SPEED -> ReadoutEntryRow(row, io.github.jasondorjeshort.dystopo.readout.ReadoutEntry.SPEED, change, help = "settings.readoutSpeed")
        SettingsRow.READOUT_CLIMB -> ReadoutEntryRow(row, io.github.jasondorjeshort.dystopo.readout.ReadoutEntry.CLIMB, change, help = "settings.readoutClimb")
        SettingsRow.READOUT_COURSE -> ReadoutEntryRow(row, io.github.jasondorjeshort.dystopo.readout.ReadoutEntry.COURSE, change, help = "settings.readoutCourse")
        SettingsRow.READOUT_ELEVATION -> ReadoutEntryRow(row, io.github.jasondorjeshort.dystopo.readout.ReadoutEntry.ELEVATION, change, help = "settings.readoutElevation")
        SettingsRow.READOUT_BAROMETER -> {
            @Suppress("UNUSED_VARIABLE") val rev = SettingsUi.revision
            val on = AppSettings.readoutBarometer
            val present = remember { io.github.jasondorjeshort.dystopo.app.readout.Barometer.present }
            Setting(row.label, if (present) "steadies the elevation and the climb on foot, by bike and by boat" else "this phone has no barometer: the elevation comes from GPS and the map", help = "settings.readoutBarometer") {
                Option("On", on) { change(SettingsRoutes.Parsed.Ok(null, false, null, null, readoutBarometer = true)) }
                Option("Off", !on) { change(SettingsRoutes.Parsed.Ok(null, false, null, null, readoutBarometer = false)) }
            }
        }
    }
}

/**
 * T161: one readout entry's row for the remembered activity (T172's, named in the hint): shown or not, then for a
 * shown entry the speed's unit (km/h or mph, pace, and knots for boat and flying), the smoothing and the ±.
 */
@Composable
private fun ReadoutEntryRow(row: SettingsRow, e: io.github.jasondorjeshort.dystopo.readout.ReadoutEntry, change: (SettingsRoutes.Parsed.Ok) -> Unit, help: String) {
    @Suppress("UNUSED_VARIABLE") val rev = SettingsUi.revision // a skippable composable: recompose after apply
    val rs = io.github.jasondorjeshort.dystopo.app.readout.ReadoutSettings
    val a = AppSettings.trackActivity
    val o = AppSettings.readoutOptions(a)[e]
    fun edit(v: String) = change(SettingsRoutes.Parsed.Ok(null, false, null, null, readoutEdits = mapOf(e to v)))
    val what = when (e) {
        io.github.jasondorjeshort.dystopo.readout.ReadoutEntry.SPEED -> "your speed or pace beside the scale bar"
        io.github.jasondorjeshort.dystopo.readout.ReadoutEntry.CLIMB -> "how fast you climb or descend, left out when level"
        io.github.jasondorjeshort.dystopo.readout.ReadoutEntry.COURSE -> "the direction you travel, held while you stand"
        io.github.jasondorjeshort.dystopo.readout.ReadoutEntry.ELEVATION -> "your height above sea level"
    }
    Setting(row.label, "${RecordingProfiles.label(a)}: $what", help = help) {
        Option("On", o.shown) { edit("shown=1") }
        Option("Off", !o.shown) { edit("shown=0") }
        if (o.shown) {
            if (rs.offersPace(e)) {
                Option(if (AppSettings.units == ContourUnits.FEET) "mph" else "km/h", !o.pace && !(o.knots && rs.offersKnots(a, e))) { edit("pace=0;knots=0") }
                Option("Pace", o.pace) { edit("pace=1;knots=0") }
                if (rs.offersKnots(a, e)) Option("Knots", o.knots && !o.pace) { edit("pace=0;knots=1") }
            }
            BasicText("Smoothing", Modifier.padding(vertical = 8.dp), style = TextStyle(color = Muted, fontSize = 13.sp))
            for (d in io.github.jasondorjeshort.dystopo.readout.Damping.entries) {
                Option(d.code.replaceFirstChar { it.uppercase() }, o.damping == d) { edit("damping=${d.code}") }
            }
            Option("±", o.plusMinus) { edit(if (o.plusMinus) "pm=0" else "pm=1") }
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
    LaunchedEffect(Unit) { while (true) { io.github.jasondorjeshort.dystopo.app.Foreground.delay(500); tick++ } } // T150: paused in the background
    val testLine = remember(tick, SettingsUi.revision) { Sources.summary() }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { // T109: the heading and its help
            BasicText(Screens.SOURCES, Modifier.weight(1f, fill = false), style = TextStyle(color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold))
            HelpIcon("settings.sources")
        }
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

/**
 * T109: the help pop-ups of the rows on these pages, by the key each row's [HelpIcon] names (`help = "settings.…"` in
 * its [Setting], and the Sources heading); a row removed here takes its text with it. Each opens its group's page for
 * `POST /help/open`. Rows that link to a screen named for what it holds (Storage, Offline areas, Credits) and Updates
 * have none.
 */
internal val SETTINGS_HELP: List<HelpText> = run {
    fun page(g: SettingsGroup): () -> Unit = { SettingsUi.open(g) }
    fun at(g: SettingsGroup, row: String) = "${Screens.SETTINGS} › ${g.title} › $row" // T115: groups are sections (Screens)
    listOf(
        HelpText("settings.sources", "Server", at(SettingsGroup.DATA, Screens.SOURCES), show = page(SettingsGroup.DATA)) {
            "A Dystopo server (your own computer, or one you trust) sends map tiles where no pack covers the view, " +
                "packs to download, and Download the map around me. Test checks an address; Save keeps it. Plain http " +
                "works only on your home network, https anywhere. With no server, the phone reads the public sources itself."
        },
        HelpText("settings.units", "Units", at(SettingsGroup.DISPLAY, "Units"), show = page(SettingsGroup.DISPLAY)) { units ->
            "Feet or metres for elevations and contours everywhere in the app; long distances read in " +
                "${if (units == ContourUnits.FEET) "miles" else "kilometres"}. Until you choose, the units come from the " +
                "region of your first map or GPS fix, and they do not change when you travel."
        },
        HelpText("settings.textSize", "Text size", at(SettingsGroup.DISPLAY, "Text size"), show = page(SettingsGroup.DISPLAY)) {
            "Makes the map's labels and markers larger or smaller. Menus and sheets follow the phone's own font size " +
                "setting instead."
        },
        HelpText("settings.routeSnap", "Route snap", at(SettingsGroup.NAVIGATION, "Route snap"), show = page(SettingsGroup.NAVIGATION)) {
            "A route follows trails and roads, so its start and end move to the nearest one. Route snap is how far they " +
                "may move: a point farther off is refused, and the card names the nearest trail and how far away it is."
        },
        HelpText("settings.navSpeech", "Spoken directions", at(SettingsGroup.NAVIGATION, "Navigation: Spoken directions"), show = page(SettingsGroup.NAVIGATION)) { units ->
            "While you navigate a route, the phone says each turn aloud once, as the card shows it, with the distance " +
                "in your units: \"In ${if (units == ContourUnits.FEET) "500 ft" else "150 m"}, turn left onto the trail\". Other audio is turned down while it speaks. It " +
                "uses the phone's own text-to-speech voice, which works with no signal once it is installed."
        },
        HelpText("settings.navStale", "GPS fix stale after", at(SettingsGroup.NAVIGATION, "Navigation: GPS fix stale after"), show = page(SettingsGroup.NAVIGATION)) {
            "While you navigate, a position older than this is not used: the bar says the fix is lost rather than " +
                "measuring from where you were. Raise it if your phone's GPS updates slowly, under heavy tree cover for example."
        },
        HelpText("settings.navSnooze", "Snooze", at(SettingsGroup.NAVIGATION, "Navigation: Snooze"), show = page(SettingsGroup.NAVIGATION)) {
            "When you leave the route while navigating, a banner warns you. Its Snooze button silences the warning for " +
                "this long, while you take a detour on purpose for example."
        },
        // T163: the turn-around clock's two rows.
        HelpText("settings.turnDeadline", "Turn back by", at(SettingsGroup.NAVIGATION, "Navigation: Turn back by"), show = page(SettingsGroup.NAVIGATION)) {
            "While you navigate a loop or an out-and-back, the card says when to turn back to be out by this. Sunset is " +
                "for a level horizon. Civil twilight ends about half an hour later and relies on a headlamp: under trees " +
                "a trail is dark before it ends. Or type a time, for a pickup or a last ferry."
        },
        HelpText("settings.turnMargin", "Turn-back margin", at(SettingsGroup.NAVIGATION, "Navigation: Turn-back margin"), show = page(SettingsGroup.NAVIGATION)) {
            "How long before the deadline you want to be out. The turn-back time leaves this much spare, and the card " +
                "turns amber this long before it. 30 minutes covers a slow descent; raise it in steep or unfamiliar ground."
        },
        HelpText("settings.syncStatus", "Sync status", at(SettingsGroup.SYNC, "Sync status"), show = page(SettingsGroup.SYNC)) {
            "Tracks, waypoints, routes and your settings sync with your server when one is set, and with the phones " +
                "you share with. This shows when the last sync ran, what it sent and received, and what is still waiting."
        },
        HelpText("settings.share", "Share", at(SettingsGroup.SYNC, Screens.SHARE), show = page(SettingsGroup.SYNC)) {
            "Syncs tracks and routes with a phone nearby and sends or receives maps over the same Wi-Fi, no internet " +
                "needed: one phone shows a code and a QR code, the other scans or types it. Via server reaches a phone " +
                "elsewhere through your server."
        },
        HelpText("settings.warmup", "Contour warm-up", at(SettingsGroup.ABOUT, "Contour warm-up"), show = page(SettingsGroup.ABOUT)) {
            "After a pack opens, the phone prepares its contour lines in the background, so the map draws faster as you " +
                "move around. Turn it off to save battery; contours are then made when they are first shown."
        },
        // T164
        HelpText("settings.pace", "Route times", at(SettingsGroup.NAVIGATION, "Route times"), show = page(SettingsGroup.NAVIGATION)) { units ->
            "Route times use a standard estimate until you have ${io.github.jasondorjeshort.dystopo.routing.PaceFits.MIN_TRACKS} hikes over " +
                "${io.github.jasondorjeshort.dystopo.render.UnitFormat.distance(io.github.jasondorjeshort.dystopo.routing.PaceFits.MIN_METRES, units)} on mapped trails, " +
                "then your own pace on flat, up and down, from your recent hikes (rides for bike routes), stops left out. Reset " +
                "forgets it and counts only tracks from now on."
        },
        // T172: the recording rows
        HelpText("settings.trackActivity", "Activity", at(SettingsGroup.NAVIGATION, "Recording: Activity"), show = page(SettingsGroup.NAVIGATION)) {
            "Record asks how you travel and remembers the answer for next time. Each activity keeps its own accuracy limit, " +
                "auto-pause, network location and interval: the rows below change the settings of the activity chosen here."
        },
        HelpText("settings.trackAccuracy", "Accuracy limit", at(SettingsGroup.NAVIGATION, "Recording: Accuracy limit"), show = page(SettingsGroup.NAVIGATION)) {
            "Every position is kept with its accuracy. Those less accurate than the limit are marked: they still show where " +
                "you were, but they do not add to the distance or the climb. Off counts every position, which suits flying."
        },
        HelpText("settings.trackAutoPause", "Auto-pause", at(SettingsGroup.NAVIGATION, "Recording: Auto-pause"), show = page(SettingsGroup.NAVIGATION)) {
            "When on, the recording holds by itself while you stand still and goes on when you move again, so a stop at a " +
                "light or a long lunch adds no wandering points. The pause is kept in the track as a gap."
        },
        HelpText("settings.trackFallback", "Network location", at(SettingsGroup.NAVIGATION, "Recording: Network location"), show = page(SettingsGroup.NAVIGATION)) {
            "When the GPS goes quiet, in a building or a deep valley, the phone's rough position from mobile networks and " +
                "Wi-Fi is recorded instead, marked as less accurate, until the GPS answers again. Off leaves a gap instead."
        },
        HelpText("settings.trackInterval", "Interval", at(SettingsGroup.NAVIGATION, "Recording: Interval"), show = page(SettingsGroup.NAVIGATION)) {
            "How often a point is recorded. Every second follows each bend; a longer interval makes smaller tracks for long " +
                "drives or flights. It saves little battery, since the GPS stays on either way."
        },
        HelpText("settings.trackBattery", "Battery", at(SettingsGroup.NAVIGATION, "Recording: Battery"), show = page(SettingsGroup.NAVIGATION)) {
            "Android may stop an app working with the screen off to save battery. In the list this opens, choose Dystopo and " +
                "allow it unrestricted battery use. Some phones also have their own setting for sleeping apps under Battery or Apps."
        },
        // T161: the readout rows
        HelpText("settings.readoutSpeed", "Speed", at(SettingsGroup.NAVIGATION, "Readout: Speed"), show = page(SettingsGroup.NAVIGATION)) { units ->
            "Your speed from the GPS over the last few seconds (more with more smoothing), or a dash while you stand. Pace is " +
                "the time per ${if (units == ContourUnits.FEET) "mile" else "kilometre"}; boats and aircraft can use knots. Following " +
                "a route on foot or by bike, it is your speed along the route. Each activity keeps its own; these rows set the one Record uses."
        },
        HelpText("settings.readoutClimb", "Climb", at(SettingsGroup.NAVIGATION, "Readout: Climb"), show = page(SettingsGroup.NAVIGATION)) { units ->
            "How fast you are going up or down, per hour on the ground (${if (units == ContourUnits.FEET) "ft/h" else "m/h"}) and per " +
                "minute when flying. It lags a few seconds so it does not jump with each step, and it is left out when you are " +
                "on the level or the reading is too uncertain to tell."
        },
        HelpText("settings.readoutCourse", "Course", at(SettingsGroup.NAVIGATION, "Readout: Course"), show = page(SettingsGroup.NAVIGATION)) {
            "The direction you are moving over the ground, in degrees from true north, from the GPS. It stays at the last " +
                "direction while you stand still, since the GPS cannot tell a direction then."
        },
        HelpText("settings.readoutElevation", "Elevation", at(SettingsGroup.NAVIGATION, "Readout: Elevation"), show = page(SettingsGroup.NAVIGATION)) {
            "Your height above sea level, from the GPS, the elevation map on foot, and the barometer when the phone has one. " +
                "± shows how far off it may be. The GPS alone can be off by tens of metres in a canyon."
        },
        HelpText("settings.readoutBarometer", "Barometer", at(SettingsGroup.NAVIGATION, "Readout: Barometer"), show = page(SettingsGroup.NAVIGATION)) { units ->
            "Air pressure falls by about 1 hPa every ${if (units == ContourUnits.FEET) "27 ft" else "8 m"} you climb. On foot, by bike " +
                "and by boat the readout uses the phone's barometer, checked against the GPS, for a steady elevation and climb; " +
                "not in a car, train or aircraft, whose air does not follow the height. Off uses the GPS and the elevation map."
        },
    )
}

/** T57: the snap distances the sheet offers (any value in [AppSettings.ROUTE_SNAP_RANGE] through `/settings`). */
private val SNAP_CHOICES = listOf(300, 500, 1000)
private val STALE_CHOICES = listOf(15, 30, 60, 120) // T91
private val SNOOZE_CHOICES = listOf(5, 10, 30)

/**
 * T163: a value typed into a row (the deadline's time, a margin outside the presets): a small field and Set. [submit]
 * applies the text and returns null, or returns what is wrong with it, shown under the field.
 */
@Composable
private fun TypedValue(initial: String, placeholder: String, keyboard: KeyboardType, suffix: String? = null, submit: (String) -> String?) {
    var text by remember(initial) { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                text, { text = it; error = null },
                singleLine = true,
                textStyle = TextStyle(color = Ink, fontSize = 14.sp),
                keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { error = submit(text) }),
                decorationBox = { inner ->
                    Box {
                        if (text.isEmpty()) BasicText(placeholder, style = TextStyle(color = Muted, fontSize = 14.sp))
                        inner()
                    }
                },
                modifier = Modifier.widthIn(min = 64.dp, max = 96.dp).background(Color.White, RoundedCornerShape(6.dp)).border(1.dp, Line, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 8.dp),
            )
            suffix?.let { BasicText(it, Modifier.padding(horizontal = 6.dp), style = TextStyle(color = Ink, fontSize = 14.sp)) }
            Option("Set", false) { error = submit(text) }
        }
        error?.let { BasicText(it, style = TextStyle(color = Color(0xFFB0302A), fontSize = 12.sp)) }
    }
}

private fun stepScale(scale: Double, dir: Int): Double =
    (Math.round((scale + 0.1 * dir) * 10) / 10.0).coerceIn(MapLibreStyle.MIN_TEXT_SCALE, MapLibreStyle.MAX_TEXT_SCALE)

/**
 * A row: its label, the hint under it, then its choices. T103: the choices wrap onto further lines instead of
 * running off the right edge (the label used to sit in a fixed column to their left, which at font scale 2.0
 * pushed "1 km" and "120 s" out of the sheet). T109: [help] is the key of the row's help pop-up ([SETTINGS_HELP]);
 * its question mark sits right after the label, clear of the choices.
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun Setting(label: String, hint: String?, help: String? = null, options: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(label, Modifier.weight(1f, fill = false), style = TextStyle(color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium))
            help?.let { HelpIcon(it) }
        }
        hint?.let { BasicText(it, style = TextStyle(color = Muted, fontSize = 11.sp)) }
        // T109: 6 dp more under a help icon, so its 48 dp target stays clear of the choices' own touch targets.
        FlowRow(Modifier.padding(top = if (help != null) 8.dp else 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { options() }
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
