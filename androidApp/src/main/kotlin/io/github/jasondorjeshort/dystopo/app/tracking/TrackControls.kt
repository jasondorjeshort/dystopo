package io.github.jasondorjeshort.dystopo.app.tracking

import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.app.control
import io.github.jasondorjeshort.dystopo.app.controlCore
import io.github.jasondorjeshort.dystopo.render.UnitFormat
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.routing.ImportText
import java.io.File
import java.util.Locale

private val Ink = Color(0xFF2B2420)
private val Paper = Color(0xEEFFFCF5)
private val RecordRed = Color(0xFFC62828)
private val Link = Color(0xFF2C7BB6)
private val Magenta = Color(0xFFD01C8B)
private val SheetPaper = Color(0xFFFFFCF5)

/**
 * The track controls on the map (T31, T40; T253: Hide, then Record, or Pause, then Resume or Finish) with the live distance, point
 * count and time; a Waypoint chip, always shown (T91: at the current fix, recording or not; a long-press
 * on the map offers one too, [LongPressMenu]); the "Tracks" chip, which opens the Tracks sheet ([TracksSheet]); the
 * waypoint sheet (rename or delete; opened by tapping a waypoint on the map); and the elevation profile panel. Also binds the [TrackOverlay] to the map view. T180: the recording panel has
 * the way back ([WayBackLine]): the turn-around clock, where the way back goes, and "The way back starts here".
 */
@Composable
fun TrackControls() {
    // A full-size box with no input handling of its own: touches outside the controls reach the map.
    Box(Modifier.fillMaxSize()) { Controls() }
}

@Composable
private fun BoxScope.Controls() {
    val app = DystopoApp.instance
    // T229: the tracker is built on its own thread (Tracking.start); until it is ready this is null and the chips draw from
    // TrackingUiState.ui's defaults. The actions take it with Tracking.of on the threads they start, which wait for it.
    val tracking = TrackingUiState.tracking
    val mapView = app.map.mapView
    DisposableEffect(mapView) {
        mapView?.let { io.github.jasondorjeshort.dystopo.app.options.SunShadeLayer.bind(it) } // T188: the shade layer
        LongPressMenu.install() // T91: a long-press offers a waypoint or a route
        onDispose { io.github.jasondorjeshort.dystopo.app.options.SunShadeLayer.unbind() }
    }
    DisposableEffect(mapView, tracking) { // T229: bound once the tracker is ready
        if (mapView != null) tracking?.overlay?.bind(mapView)
        onDispose { tracking?.overlay?.unbind() }
    }
    val ui = TrackingUiState.ui
    // T49: the track chips fold into one chip, so they cover as little of the map (and its peak labels) as the Packs button.
    // T109: held in [TrackBar], so `POST /help/open?key=tracks.bar` can unfold it.
    var barOpen by TrackBar::open
    // T253: Back hides the unfolded bar (and its panel), as Hide does; composed first in MapScreen, so every sheet's own
    // Back handler, and the Tracks sheet's, comes before it. The recording goes on.
    androidx.activity.compose.BackHandler(enabled = barOpen && !TrackBar.listOpen) {
        if (ActivityPicker.target == ActivityPicker.NEW) ActivityPicker.close() else TrackBar.hide()
    }
    // T81: POST_NOTIFICATIONS (Android 13+), asked once at the first recording; when allowed, the running
    // recording's notification is posted at once. Refused: recording goes on without it.
    val context = androidx.compose.ui.platform.LocalContext.current
    val notificationAsk = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) TrackingUiState.tracking?.repostNotification() // T229: after a recording started, so the tracker is ready
        BatteryExemption.onRecord(context) // T172: after the system's dialog, so the two never stack
    }
    // T172: what a recording started from the activity picker asks, each at most once: the notification permission, then
    // the battery exemption card.
    val onRecordStarted = {
        if (NotificationPrompt.shouldAsk(context)) notificationAsk.launch(NotificationPrompt.PERMISSION) else BatteryExemption.onRecord(context)
    }

    io.github.jasondorjeshort.dystopo.app.MapChrome { Box(Modifier.fillMaxSize()) { // T91: capped font scale
        val density = androidx.compose.ui.platform.LocalDensity.current
        Column(
            // Below the top row (T29), under the navigation card while navigating (T65), the Routes chip under it: T239, where
            // the layout rule puts it (its control node draws it there, app/MapLayout.kt).
            Modifier.align(Alignment.TopEnd).padding(start = 8.dp, end = 8.dp)
                .control("waypointAndTracks") // T238
                .onGloballyPositioned { c -> io.github.jasondorjeshort.dystopo.app.TopChips.trackColumnAt(c, density) },
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // T91: the chips wrap (right-aligned) instead of running under the online indicator on the left; at
            // most the width right of it.
            val screenW = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp
            val onlineRight = with(density) { io.github.jasondorjeshort.dystopo.app.TopChips.onlineRightPx.toDp() }
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            androidx.compose.foundation.layout.FlowRow(
                Modifier.controlCore().widthIn(max = if (onlineRight > 0.dp) (screenW - onlineRight - 16.dp).coerceAtLeast(160.dp) else screenW), // T239: the row the rule fits the bar by
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                itemVerticalAlignment = Alignment.CenterVertically, // T109: the chips centred on the help icon's taller target
            ) {
                // T253: Hide (the old "\u00D7" read as Stop) first, at the row's far edge from the record group, which comes last.
                if (barOpen) HideChip { TrackBar.hide() }
                // T91: a waypoint at the current fix at any time, folded or not, recording or not.
                Chip("Waypoint") { Thread { dropHere(tracker()) }.start() }
                if (!barOpen) {
                    // Folded: one chip; while recording it shows the live distance in red, so a hidden panel visibly records on.
                    Chip(if (ui.recording) "\u25CF " + UnitFormat.distance(ui.distanceM, AppSettings.units) else "Tracks", if (ui.recording) RecordRed else Ink) { barOpen = true }
                } else {
                    Chip("Tracks") { if (TrackBar.listOpen) TrackBar.closeList() else TrackBar.listOpen = true }
                    io.github.jasondorjeshort.dystopo.app.help.HelpIcon("tracks.bar") // T109: record, pause, finish and keep
                    // T253: the record group: Record; while recording Pause; paused Resume and Finish. Two steps end a recording
                    // (the ADR 0039 survey's common practice); Finish saves, so it asks nothing; only Discard asks.
                    for (label in recordGroup(ui.recording, ui.paused, TrackingUiState.starting)) {
                        Chip(label, recordGroupColour(label)) { recordGroupTap(label) }
                    }
                }
            }
            EstimateChip() // T203: the terrain estimate, recording or not
            if (barOpen && !TrackBar.listOpen) ActivityPickerPanel(onRecordStarted) // T172; T219: a track's in the Tracks sheet
            if (barOpen && ui.recording) {
                // T253: a vertical swipe on the panel hides it, as Hide and Back do; the recording goes on. Where the rule leaves
                // the panel less than its height (landscape at font scale 2.0) it scrolls instead, so Discard and the turn-back
                // chip stay reachable; the chips above it (Hide and the record group) never scroll away.
                val scroll = androidx.compose.foundation.rememberScrollState()
                Panel((if (scroll.maxValue > 0) Modifier else Modifier.hideOnSwipe()).verticalScroll(scroll, enabled = scroll.maxValue > 0)) {
                    BasicText(ui.activeName ?: "", style = TextStyle(color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold))
                    // T172: the recording's activity; a tap changes it (the recording then uses that activity's settings).
                    ActivityLine(ui.activeId, ui.activeName, ui.activeActivity)
                    // T260: the gaps bridged on the map, their count and time, after the live stats.
                    GapStatsLine(UnitFormat.distance(ui.distanceM, AppSettings.units) + String.format(Locale.US, "  %d pts  %s", ui.points, duration(ui.durationS)), ui.gapText, 13)
                    RecordingStateLine(ui)
                    if (ui.paused) DiscardLine() // T253: paused only, and it asks first
                    TurnBackChip() // T252: the card off by default, on with this chip
                    WayBackLine() // T180
                }
            }
        }
    } }

    Column(
        // T41: above the credit line (its measured top), so the profile panel never covers it.
        Modifier.align(Alignment.BottomCenter).let { m ->
            // T49: above the route card too; T106: and above the update offer, which covered the long-press card.
            val b = maxOf(io.github.jasondorjeshort.dystopo.app.routing.BottomPanels.trackPanelsFloor, io.github.jasondorjeshort.dystopo.app.MapInsets.updateCardTop)
            if (b > 0.dp) m.padding(start = 8.dp, end = 8.dp, bottom = b + 6.dp) else m.navigationBarsPadding().padding(start = 8.dp, end = 8.dp, bottom = 28.dp)
        }.let { m -> // T49: report where the panels are (GET /route layout)
            val density = androidx.compose.ui.platform.LocalDensity.current
            m.onGloballyPositioned { c ->
                val panels = io.github.jasondorjeshort.dystopo.app.routing.BottomPanels
                val bottom = io.github.jasondorjeshort.dystopo.app.routing.fromWindowBottom(c, density, top = false)
                val top = io.github.jasondorjeshort.dystopo.app.routing.fromWindowBottom(c, density)
                val changed = bottom != panels.trackPanelsBottom || top != panels.trackPanelsTop
                panels.trackPanelsBottom = bottom
                panels.trackPanelsTop = top
                if (changed) io.github.jasondorjeshort.dystopo.app.MapLayout.invalidate() // T239: the lower columns stand on them
            }
        },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BatteryExemptionCard() // T172: once, on the first Record
        LongPressCard() // T91
        io.github.jasondorjeshort.dystopo.app.viewshed.ViewshedSheet() // T182: what can I see from here
        ui.selectedWaypoint?.let { WaypointSheet(it) }
        ui.profile?.let { ProfilePanel(it) }
    }
}

/**
 * T219: the Tracks sheet, a sheet over the lower part of the map like the Routes list, so the chips above it stay where they
 * are (the audit's finding 8; the survey `route-card-and-lists.md`). The title with the count, Close; Import visible, Sync now,
 * Sync status and Share under one overflow; a row per track with its name, date, distance and activity, one visible action,
 * Go, and Profile, Report and Export GPX in its own overflow. A tap on a row shows the track and closes the sheet, so it can
 * be seen; Go and Profile close it too. `POST /tracks/ui` opens it and either overflow. One line in `MainActivity`.
 */
@Composable
fun TracksSheet() {
    if (!TrackBar.listOpen) return
    // T229: until the tracker is ready the sheet says so under its title (TRACKS_STARTING) and has no actions or rows yet.
    val tracking = TrackingUiState.tracking
    val ui = TrackingUiState.ui
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) Thread { importUri(tracker(), uri) }.start()
    }
    androidx.activity.compose.BackHandler { TrackBar.closeList() }
    val maxHeight = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp * 0.7f
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = maxHeight)
                .background(SheetPaper, RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                .pointerInput(Unit) { detectTapGestures { } } // a tap on its paper stays out of the map and the credit line
                .navigationBarsPadding().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BasicText(tracksTitle(ui.tracks.size), Modifier.weight(1f), style = TextStyle(color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold))
                SheetAction("Close") { TrackBar.closeList() }
            }
            if (tracking == null) {
                BasicText(TRACKS_STARTING, style = TextStyle(color = Ink, fontSize = 14.sp))
                return@Column
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SheetAction("Import") { TrackBar.importOpen = !TrackBar.importOpen }
                if (TrackBar.importOpen) SheetAction("Pick file") { TrackBar.importOpen = false; picker.launch(arrayOf("application/gpx+xml", "application/xml", "text/xml", "application/octet-stream", "*/*")) }
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    io.github.jasondorjeshort.dystopo.app.routing.OverflowDots(MORE_ACTIONS) { TrackBar.moreOpen = !TrackBar.moreOpen }
                }
            }
            if (TrackBar.moreOpen) {
                // T115 (T110's follow-up): the actions wrap whole; in a Row at font scale 2.0 "Share" broke mid-word.
                @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    io.github.jasondorjeshort.dystopo.app.sync.SyncNowAction() // T44
                    io.github.jasondorjeshort.dystopo.app.share.ShareAction() // T54: phone-to-phone sync
                }
            }
            if (TrackBar.importOpen) ImportList(tracking) { TrackBar.importOpen = false }
            ui.message?.let { BasicText(it, style = TextStyle(color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)) }
            if (ActivityPicker.target != null && ActivityPicker.target != ActivityPicker.NEW) ActivityPickerPanel { } // T172: a track's activity
            if (ui.tracks.isEmpty()) BasicText("No tracks yet", style = TextStyle(color = Ink, fontSize = 14.sp))
            LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(ui.tracks, key = { it.id }) { t -> TrackRow(tracking, ui, t) }
            }
        }
    }
}

@Composable
private fun TrackRow(tracking: Tracking, ui: Tracking.UiState, t: io.github.jasondorjeshort.dystopo.store.TrackSummary) {
    val app = DystopoApp.instance
    val open = TrackBar.rowMore == t.id
    val distance = (ui.distances[t.id] ?: ui.distanceM.takeIf { t.id == ui.activeId })?.let { UnitFormat.distance(it, AppSettings.units) }
    Column(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(8.dp)).padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(Modifier.weight(1f)) {
                BasicText(
                    androidx.compose.ui.text.buildAnnotatedString {
                        pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold, fontSize = 15.sp))
                        append((if (t.id == ui.shownId) "● " else "") + t.name)
                        pop()
                        append("\n" + trackRowLine(io.github.jasondorjeshort.dystopo.app.trips.shortDate(t.createdAt), distance, t.recording))
                    },
                    style = TextStyle(color = Ink, fontSize = 13.sp),
                    modifier = Modifier.fillMaxWidth().clickable {
                        Thread { tracking.show(t.id) }.start()
                        TrackBar.closeList()
                    },
                )
                ActivityLine(t.id, t.name, t.activity) // T172: changeable afterwards
                io.github.jasondorjeshort.dystopo.app.sync.SyncConflictNote(t.id) // T51
            }
            // One verb for following, as on the route card (T217); T61: navigate.
            BasicText(
                "Go",
                style = TextStyle(color = Link, fontSize = 14.sp, fontWeight = FontWeight.Bold),
                modifier = Modifier.clickable {
                    Thread { io.github.jasondorjeshort.dystopo.app.nav.NavSession.startTrack(t.id) }.start()
                    TrackBar.closeList()
                }.border(1.dp, Link, RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 7.dp),
            )
            io.github.jasondorjeshort.dystopo.app.routing.OverflowDots("$MORE_ACTIONS for ${t.name}") { TrackBar.rowMore = if (open) null else t.id }
        }
        if (open) {
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End)) {
                SheetAction("Profile") {
                    Thread { tracking.showProfile(t.id) }.start()
                    TrackBar.closeList()
                }
                // T198: the trip report in the print dialog.
                SheetAction("Report") {
                    TrackBar.rowMore = null
                    io.github.jasondorjeshort.dystopo.app.print.SheetPrint.printReport(app, t.id) { why -> why?.let { msg -> Thread { tracking.refreshUi("Report failed: $msg") }.start() } }
                }
                SheetAction(EXPORT_GPX) {
                    TrackBar.rowMore = null
                    io.github.jasondorjeshort.dystopo.app.SaveFile.track(t.id)
                }
            }
        }
    }
}

/** T219: an action on the Tracks sheet: a link's look with a thumb's height. */
@Composable
private fun SheetAction(text: String, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = 44.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        BasicText(text, style = TextStyle(color = Link, fontSize = 14.sp, fontWeight = FontWeight.Bold))
    }
}

/** T219: the Tracks sheet's title: "Tracks · 3" (one text, so the chip's "Tracks" stays the only one by that name). Pure, tested. */
internal fun tracksTitle(count: Int): String = "Tracks · $count"

/** T229: the Tracks sheet before the tracker is ready, and the Record chip while a choice waits for it ([Tracking.once]). */
internal const val TRACKS_STARTING = "Starting…"
internal const val RECORD_STARTING = "Starting"

/** T253: the record group's words (ADR 0031: the owner's to adjust); Hide is the unfolded bar's fold. */
internal const val RECORD = "Record"
internal const val PAUSE = "Pause"
internal const val RESUME = "Resume"
internal const val FINISH = "Finish"
internal const val HIDE = "Hide"
internal const val HIDE_ACTION = "Hide the track controls; a recording goes on"
internal const val DISCARD = "Discard"
internal const val DISCARD_QUESTION = "Discard this recording? The track is deleted, not saved."
internal const val KEEP = "Keep"

/**
 * T253 (T229 before): the record group, last in the unfolded bar: Record, or [RECORD_STARTING] while a recording waits for
 * the tracker; while recording only Pause; paused Resume and Finish. No one-tap stop: ending takes Pause, then Finish (the
 * ADR 0039 survey, `recording-panel.md`). Pure, tested.
 */
internal fun recordGroup(recording: Boolean, paused: Boolean, starting: Boolean): List<String> = when {
    recording && paused -> listOf(RESUME, FINISH)
    recording -> listOf(PAUSE)
    starting -> listOf(RECORD_STARTING)
    else -> listOf(RECORD)
}

/** T253: red while it acts on a recording (Pause, Resume), ink for Record and for Finish, which ends it. */
private fun recordGroupColour(label: String): Color = if (label == PAUSE || label == RESUME) RecordRed else Ink

/** T253: a record-group chip's tap; the recorder's calls on a thread of their own ([tracker] waits there for it). */
private fun recordGroupTap(label: String) {
    when (label) {
        // T172: Record opens the activity picker (the remembered activity marked); a choice starts the recording.
        RECORD -> if (ActivityPicker.target == ActivityPicker.NEW) ActivityPicker.close() else { TrackBar.closeList(); ActivityPicker.openForRecord() }
        PAUSE -> Thread { tracker().pause() }.start()
        RESUME -> { TrackBar.discardAsk = false; Thread { tracker().resume() }.start() }
        FINISH -> { TrackBar.discardAsk = false; Thread { tracker().stop() }.start() } // saves at once: nothing is lost
        else -> {} // T229: "Starting" while a choice waits for the tracker (Tracking.once); a tap then changes nothing
    }
}

/** T253: Hide, with a chevron pointing where the panel goes (up, into the Tracks chip); it never ends a recording. */
@Composable
private fun HideChip(onClick: () -> Unit) {
    Row(
        Modifier.background(Ink, RoundedCornerShape(16.dp)).clickable(onClickLabel = HIDE_ACTION, onClick = onClick)
            .padding(start = 10.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Canvas(Modifier.padding(top = 1.dp).size(12.dp, 8.dp)) {
            val p = Path().apply { moveTo(0f, size.height); lineTo(size.width / 2, 0f); lineTo(size.width, size.height) }
            drawPath(p, Color.White, style = Stroke(width = 2.dp.toPx()))
        }
        BasicText(HIDE, style = TextStyle(color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold))
    }
}

/** T253: a vertical swipe of a thumb's length on the panel hides the bar, as Hide and Back do. */
private fun Modifier.hideOnSwipe(): Modifier = pointerInput(Unit) {
    var dragged = 0f
    detectVerticalDragGestures(
        onDragStart = { dragged = 0f },
        onDragEnd = { if (kotlin.math.abs(dragged) >= 48.dp.toPx()) TrackBar.hide() },
    ) { change, dy -> change.consume(); dragged += dy }
}

/**
 * T253: Discard on the paused panel, in red and apart from the chips; a tap asks first ([DISCARD_QUESTION], Discard or
 * Keep), since it deletes the track. Finish never asks.
 */
@Composable
private fun DiscardLine() {
    if (!TrackBar.discardAsk) {
        Action(DISCARD, RecordRed) { TrackBar.discardAsk = true }
        return
    }
    // Wrapped at the panel's usual width, so the question does not widen the chip column (it pushed the chips down).
    BasicText(DISCARD_QUESTION, Modifier.widthIn(max = 160.dp), style = TextStyle(color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium))
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Action(DISCARD, RecordRed) {
            TrackBar.discardAsk = false
            Thread { val t = tracker(); t.stop()?.let { t.delete(it.id) } }.start()
        }
        Action(KEEP) { TrackBar.discardAsk = false }
    }
}

/** T229: the tracker on a thread the caller started (it waits there until the tracker is ready); never on the main thread. */
private fun tracker(): Tracking = Tracking.of(DystopoApp.instance)

/** T219: a track row's second line: the date, then the distance, or that it is recording. Pure, tested. */
internal fun trackRowLine(date: String, distance: String?, recording: Boolean): String =
    listOfNotNull(date, distance, "recording".takeIf { recording }).joinToString(" · ")

/** T219: the overflows' name for a screen reader and `uiautomator dump` (the same as the route card's, T217). */
internal const val MORE_ACTIONS = "More actions"
internal const val EXPORT_GPX = "Export GPX"

/** T219: the Import list with nothing in it, without the app's private path. */
internal const val IMPORT_LIST_EMPTY = "No GPX files in the app yet. Pick file to choose one on this phone."

/**
 * T159: the recording's state ([Tracking.UiState.state]) as one line under the live stats in the recording panel, the
 * same words as the notification ([RecordingStatus.controlsLine]); nothing while it records normally. Kept to this one
 * line so the activity picker (T172) can sit beside it in the panel.
 */
@Composable
private fun RecordingStateLine(ui: Tracking.UiState) {
    val line = ui.stateLine ?: return
    BasicText(line, style = TextStyle(color = if (ui.state == RecState.PAUSED || ui.state == RecState.AUTO_PAUSED) Ink else RecordRed, fontSize = 12.sp))
}

/**
 * T180: the way back on the recording panel ([io.github.jasondorjeshort.dystopo.app.nav.WayBackClock]): the clock's line
 * in its colour with "Heading back" after the flip, where the way back goes ("Way back to the recording's start", "...
 * to where the drive ended", "... to where you set it"), and the one override, "The way back starts here", which
 * re-anchors it at the current position (per device). Nothing while navigating (the navigation card has its own clock)
 * or before the first fix.
 */
/**
 * T252: the one chip that turns the turn-back card on or off for this recording (off by default: the owner's ruling, and
 * the ADR 0039 survey found no app with such a card always on). Outlined while off, filled with a tick while on; the help
 * icon beside it says what it times. `POST /tracks/wayback?on=0|1` does the same.
 */
@Composable
private fun TurnBackChip() {
    val nav = io.github.jasondorjeshort.dystopo.app.nav.WayBackClock
    val on = nav.chipOn
    Row(verticalAlignment = Alignment.CenterVertically) {
        BasicText(
            (if (on) "✓ " else "") + nav.CHIP,
            style = TextStyle(color = if (on) Color.White else Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold),
            modifier = Modifier
                .background(if (on) Ink else Color.Transparent, RoundedCornerShape(14.dp))
                .border(1.dp, Ink, RoundedCornerShape(14.dp))
                .clickable(onClickLabel = if (on) TURN_BACK_OFF else TURN_BACK_ON) { Thread { nav.setOn(!on) }.start() }
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
        io.github.jasondorjeshort.dystopo.app.help.HelpIcon("tracks.turnBack")
    }
}

/** T252: the chip's action for a screen reader. */
internal const val TURN_BACK_ON = "Show the turn-back time for this recording"
internal const val TURN_BACK_OFF = "Hide the turn-back time"

/** T252: the chip's help text (ADR 0031: the owner's to adjust). */
internal val TURN_BACK_HELP: List<io.github.jasondorjeshort.dystopo.app.help.HelpText> = listOf(
    io.github.jasondorjeshort.dystopo.app.help.HelpText("tracks.turnBack", "Turn-back time", "The map, top right: Tracks, then the recording panel", needs = "recording", show = { TrackBar.open = true }) {
        "Off until you turn it on for a recording. It times the walk or ride back to the recording's start at the pace of " +
            "its activity and says when to turn back to be out before sunset or your deadline in Settings. No time while " +
            "driving, on a train or boat, or flying. Tap the card to see how it is worked out."
    },
)

@Composable
private fun WayBackLine() {
    val w = io.github.jasondorjeshort.dystopo.app.nav.NavUiState.wayBack ?: return
    val (bg, ink) = when (w.colour) {
        "red" -> RecordRed to Color.White
        "amber" -> Color(0xFFFDAE61) to Ink
        else -> Color.Transparent to Ink
    }
    BasicText(
        w.text + if (w.headingBack) "  ·  " + io.github.jasondorjeshort.dystopo.app.nav.WayBackClock.HEADING_BACK else "",
        style = TextStyle(color = ink, fontSize = 13.sp, fontWeight = FontWeight.Bold),
        modifier = Modifier.background(bg, RoundedCornerShape(4.dp)).padding(horizontal = if (bg == Color.Transparent) 0.dp else 4.dp),
    )
    if (!w.clock) { BasicText(w.label, style = TextStyle(color = Ink, fontSize = 12.sp)); return } // T252: why there is no time
    BasicText(w.sourceLine, style = TextStyle(color = Ink, fontSize = 12.sp))
    Action(io.github.jasondorjeshort.dystopo.app.nav.WayBackClock.STARTS_HERE) {
        Thread {
            val ok = tracker().wayBackHere()
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(DystopoApp.instance, if (ok) WAY_BACK_SET else WAY_BACK_NO_POINT, android.widget.Toast.LENGTH_SHORT).show()
            }
        }.start()
    }
}

/** T180: the toasts of "The way back starts here" (ADR 0031: the owner's to adjust). */
internal const val WAY_BACK_SET = "The way back now starts here"
internal const val WAY_BACK_NO_POINT = "No position recorded yet"

@Composable
private fun ImportList(tracking: Tracking, onDone: () -> Unit) {
    var files by remember { mutableStateOf<List<File>?>(null) }
    LaunchedEffect(Unit) { Thread { files = tracking.importableFiles() }.start() }
    val list = files ?: return
    if (list.isEmpty()) BasicText(IMPORT_LIST_EMPTY, style = TextStyle(color = Ink, fontSize = 12.sp))
    for (f in list.take(8)) {
        BasicText(
            f.name,
            style = TextStyle(color = Link, fontSize = 13.sp),
            modifier = Modifier.clickable {
                Thread { importing(tracking, f.name) { tracking.importFile(f.name) } }.start()
                onDone()
            }.padding(vertical = 4.dp),
        )
    }
}

/**
 * T172: a track's activity as a link ("On foot ›"; "Activity ›" when the track has none, an import for example); a tap
 * opens the activity picker for that track.
 */
@Composable
private fun ActivityLine(id: String?, name: String?, activity: io.github.jasondorjeshort.dystopo.model.Activity?) {
    if (id == null) return
    BasicText(
        (activity?.let { RecordingProfiles.label(it) } ?: "Activity") + " ›",
        style = TextStyle(color = Link, fontSize = 12.sp, fontWeight = FontWeight.Medium),
        modifier = Modifier.clickable { ActivityPicker.openForTrack(id, name ?: "", activity) }.padding(vertical = 2.dp),
    )
}

/** T109: whether the track bar is unfolded (Hide, Tracks, Record or Pause); T219: and the Tracks sheet's state. Compose state, main thread. */
object TrackBar {
    var open by mutableStateOf(false)

    /** T219: the Tracks sheet is open (`POST /tracks/ui?open=1`). */
    var listOpen by mutableStateOf(false)
    var importOpen by mutableStateOf(false)

    /** T219: the sheet's overflow (Sync now, Sync status, Share) is open. */
    var moreOpen by mutableStateOf(false)

    /** T219: the track whose row has its overflow open. */
    var rowMore by mutableStateOf<String?>(null)

    /** T253: Discard's question is up on the paused panel. */
    var discardAsk by mutableStateOf(false)

    fun closeList() {
        listOpen = false
        importOpen = false
        moreOpen = false
        rowMore = null
    }

    /** T253: Hide, Back or a swipe: the bar folds into its one chip; a recording goes on. */
    fun hide() {
        open = false
        discardAsk = false
        closeList()
    }
}

/**
 * T109: the help pop-up of the unfolded track bar, by the key its [io.github.jasondorjeshort.dystopo.app.help.HelpIcon]
 * names; removing the bar's icon removes its text. `POST /help/open` unfolds the bar.
 */
internal val TRACK_BAR_HELP: List<io.github.jasondorjeshort.dystopo.app.help.HelpText> = listOf(
    io.github.jasondorjeshort.dystopo.app.help.HelpText("tracks.bar", "Recording tracks", "The map, top right: Tracks, then the bar", show = { TrackBar.open = true }) {
        "Record starts a track; it goes on with the screen off. " +
            "Pause holds it; then Resume goes on, or Finish ends it and keeps it in the Tracks list; " +
            "Discard deletes it, after asking. Hide, Back or a swipe on the panel puts the bar away; the " +
            "recording goes on, its distance in red on the chip. Waypoint marks where you are."
    },
)

/** The Waypoint chip (T91): a waypoint at the current fix, or a short note when there is none yet. Off the main thread. */
internal fun dropHere(tracking: Tracking) {
    if (tracking.dropWaypoint(via = "chip") == null) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(DystopoApp.instance, "No location yet: long-press the map to place a waypoint", android.widget.Toast.LENGTH_LONG).show()
        }
    }
}

/** Reads a picked document and imports it (off the main thread). T219: its line from the start. */
private fun importUri(tracking: Tracking, uri: Uri) {
    val app = DystopoApp.instance
    val file = runCatching {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()
    val shown = file ?: ImportText.THE_FILE
    importing(tracking, shown) {
        val text = app.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: error("cannot open the file")
        try {
            tracking.importGpx(text, file?.substringBeforeLast('.') ?: "Imported track", shown)
        } catch (e: Exception) {
            if (!ImportText.nothingToImport(e)) throw e
            // T219: no track in it: its routes go to Routes, so "has no route or track" is true when it is said.
            val r = io.github.jasondorjeshort.dystopo.app.trips.Trips.import(text)
            tracking.refreshUi(ImportText.routes(r.imported.size, r.skipped.size, shown))
        }
    }
}

/** T219: an import's sentences around [block] ([ImportText]): the start at once, the failures; its end is the import's own. */
private fun importing(tracking: Tracking, file: String, block: () -> Unit) {
    tracking.refreshUi(ImportText.start(file))
    ImportText.hold()
    try {
        block()
    } catch (e: Exception) {
        val nothing = ImportText.nothingToImport(e)
        if (!nothing) android.util.Log.w("DystopoTracks", "import of $file failed", e)
        tracking.refreshUi(if (nothing) ImportText.NOTHING else ImportText.failed(file))
    }
}

@Composable
private fun WaypointSheet(w: io.github.jasondorjeshort.dystopo.store.StoredWaypoint) {
    var name by remember(w.waypoint.id) { mutableStateOf(w.waypoint.name) }
    Panel(Modifier.fillMaxWidth()) {
        BasicText("Waypoint", style = TextStyle(color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Bold))
        BasicTextField(
            name, { name = it },
            singleLine = true,
            textStyle = TextStyle(color = Ink, fontSize = 16.sp),
            modifier = Modifier.fillMaxWidth().border(1.dp, Ink.copy(alpha = 0.4f), RoundedCornerShape(4.dp)).padding(8.dp),
        )
        val wp = w.waypoint
        BasicText(
            String.format(Locale.US, "%.5f, %.5f", wp.lat, wp.lon) + (wp.ele?.let { "  " + UnitFormat.elevation(it, AppSettings.units) } ?: ""),
            style = TextStyle(color = Ink, fontSize = 11.sp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Action("Rename") { val n = name.trim(); if (n.isNotEmpty()) Thread { tracker().renameWaypoint(wp.id, n) }.start() }
            Action("Delete", RecordRed) { Thread { tracker().deleteWaypoint(wp.id) }.start() }
            Action("Close") { Thread { tracker().selectWaypoint(null) }.start() }
        }
    }
}

@Composable
private fun ProfilePanel(p: Tracking.ProfileView) {
    Panel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BasicText(p.name, style = TextStyle(color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold), modifier = Modifier.widthIn(max = 220.dp))
            Action("Close") { Thread { tracker().showProfile(null) }.start() }
        }
        val s = p.stats
        GapStatsLine(
            UnitFormat.distance(s.distanceM, AppSettings.units) +
                (s.ascentM?.let { "  +" + UnitFormat.elevation(it, AppSettings.units) } ?: "") +
                (s.descentM?.let { " / -" + UnitFormat.elevation(it, AppSettings.units) } ?: "") +
                (if (s.minEleM != null && s.maxEleM != null) "  ${UnitFormat.elevationValue(s.minEleM!!, AppSettings.units)} to ${UnitFormat.elevation(s.maxEleM!!, AppSettings.units)}" else "") +
                (if (s.segments > 1) "  ${s.segments} segments" else ""),
            p.gapText, 12, // T260
        )
        val eles = p.samples.mapNotNull { it.eleM }
        if (eles.size < 2) {
            BasicText("No elevation in this track", style = TextStyle(color = Ink, fontSize = 12.sp))
            return@Panel
        }
        val lo = eles.min()
        val hi = eles.max()
        val total = p.samples.last().distanceM.takeIf { it > 0 } ?: 1.0
        Canvas(Modifier.fillMaxWidth().height(110.dp)) {
            val span = (hi - lo).takeIf { it > 1.0 } ?: 1.0
            fun x(d: Double) = (d / total * size.width).toFloat()
            fun y(e: Double) = (size.height - (e - lo) / span * (size.height * 0.9) - size.height * 0.05).toFloat()
            val line = Path()
            val area = Path()
            // T41: one run per track segment, so a segment break is a gap, not a vertical step.
            var runSegment: Int? = null
            var runEndX = 0f
            for (smp in p.samples) {
                val e = smp.eleM ?: continue
                if (runSegment != smp.segment) {
                    if (runSegment != null) { area.lineTo(runEndX, size.height); area.close() }
                    line.moveTo(x(smp.distanceM), y(e))
                    area.moveTo(x(smp.distanceM), size.height)
                    runSegment = smp.segment
                } else line.lineTo(x(smp.distanceM), y(e))
                area.lineTo(x(smp.distanceM), y(e))
                runEndX = x(smp.distanceM)
            }
            area.lineTo(runEndX, size.height)
            area.close()
            drawPath(area, Magenta.copy(alpha = 0.18f))
            drawPath(line, Magenta, style = Stroke(width = 2.5.dp.toPx()))
            drawLine(Ink.copy(alpha = 0.3f), Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            BasicText("0  |  " + UnitFormat.elevation(lo.toDouble(), AppSettings.units) + " low", style = TextStyle(color = Ink, fontSize = 10.sp))
            BasicText(UnitFormat.elevation(hi.toDouble(), AppSettings.units) + " high  |  " + UnitFormat.distance(total.toDouble(), AppSettings.units), style = TextStyle(color = Ink, fontSize = 10.sp))
        }
    }
}

/**
 * T260: a statistics line with the gaps the map bridges with a dashed line ("1 gap, 10 min", [gapText]) after it and the
 * help icon that says what the dash means; the line alone when there is no gap.
 */
@Composable
private fun GapStatsLine(stats: String, gapText: String?, size: Int) {
    if (gapText == null) {
        BasicText(stats, style = TextStyle(color = Ink, fontSize = size.sp))
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        BasicText("$stats  $gapText", style = TextStyle(color = Ink, fontSize = size.sp), modifier = Modifier.weight(1f, fill = false))
        io.github.jasondorjeshort.dystopo.app.help.HelpIcon("tracks.gaps")
    }
}

/** T260: what the dashed line means (ADR 0031: the owner's to adjust). */
internal val TRACK_GAPS_HELP: List<io.github.jasondorjeshort.dystopo.app.help.HelpText> = listOf(
    io.github.jasondorjeshort.dystopo.app.help.HelpText("tracks.gaps", "Gaps in a track", "The map, top right: Tracks, then the recording panel or a track's profile", needs = "a track with a gap", show = { TrackBar.open = true }) {
        "A dashed line joins two parts of a track where nothing was recorded for a while (no GPS fix, a pause, the app " +
            "stopped). It is straight, in the track's colour: the way taken there is not known. The figures give the gaps' " +
            "number and time; the distance leaves them out, and a GPX export keeps each part as its own segment."
    },
)

private fun duration(s: Double?): String {
    if (s == null) return ""
    val t = s.toLong()
    return String.format(Locale.US, "%d:%02d:%02d", t / 3600, t / 60 % 60, t % 60)
}

@Composable
private fun Chip(text: String, color: Color = Ink, onClick: () -> Unit) {
    BasicText(
        text,
        style = TextStyle(color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold),
        modifier = Modifier
            .background(color, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/**
 * T203: the terrain estimate's chip under the top-right chips ([TerrainSource.display]): "Estimated position, about 80 m"
 * in the puck's blue while it estimates, "No position, last known 12 min ago" in grey after the give-up (the time redrawn
 * every 10 s); nothing otherwise. Not tappable: it says what the puck is.
 */
@Composable
private fun EstimateChip() {
    val d = TerrainSource.display ?: return
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    androidx.compose.runtime.LaunchedEffect(d) {
        while (true) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(10_000)
        }
    }
    val text = when (d.state) {
        io.github.jasondorjeshort.dystopo.nav.TerrainState.ESTIMATING -> d.spreadM?.let { TerrainText.estimating(it, AppSettings.units) }
        io.github.jasondorjeshort.dystopo.nav.TerrainState.GAVE_UP -> TerrainText.lost(now - d.atWallMs)
        else -> null
    } ?: return
    val lost = d.state == io.github.jasondorjeshort.dystopo.nav.TerrainState.GAVE_UP
    BasicText(
        text,
        style = TextStyle(color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold),
        modifier = Modifier
            .background(if (lost) Color(0xFF8F959E) else Color(0xFF1A66D9), RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

@Composable
private fun Action(text: String, color: Color = Link, onClick: () -> Unit) {
    BasicText(
        text,
        style = TextStyle(color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold),
        modifier = Modifier.clickable(onClick = onClick).padding(vertical = 4.dp),
    )
}

@Composable
private fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.background(Paper, RoundedCornerShape(8.dp)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        content()
    }
}
