package io.github.jasondorjeshort.dystopo.app.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.app.control
import io.github.jasondorjeshort.dystopo.app.tracking.ActivityPicker
import io.github.jasondorjeshort.dystopo.app.tracking.RecordingProfiles
import io.github.jasondorjeshort.dystopo.app.tracking.TrackingUiState
import io.github.jasondorjeshort.dystopo.model.Activity
import io.github.jasondorjeshort.dystopo.nav.TurnActivity
import io.github.jasondorjeshort.dystopo.routing.TravelMode

/**
 * T263 (the idea `2026-11-07-the-activity-is-the-apps-mode-not-a-track-setting.md`; the survey `tracks-manager.md`,
 * point 5: one app-wide mode, as OsmAnd's profile): the route planner's travel mode for a new route, from the app's
 * current activity ([AppSettings.trackActivity]). Pure (`ActivityModeTest`).
 */
object ActivityMode {
    /**
     * The mode a new route opens in for [a]: the way-back clock's mapping ([TurnActivity.mode]: on foot Hike, which the
     * planner then reads as Walk or Hike by the route's share in town (T276), a bike Bike), driving Drive, and on foot for
     * the rest (a route planned from a train, a boat or a plane is the walk at the other end). A change on the route card
     * holds for that route (`routing/RouteSession.kt`); the next new route starts from the activity again.
     */
    fun routeMode(a: Activity): TravelMode = TurnActivity.mode(a) ?: if (a == Activity.ROAD) TravelMode.DRIVE else TravelMode.HIKE
}

/** T263: the activity the chip shows when not recording, kept up with [AppSettings.trackActivity] (a sync, a settings row). */
internal object ActivityChipUiState {
    var current by mutableStateOf(AppSettings.trackActivity)
}

/**
 * T263: the activity chip on the map, under the Routes chip (the layout rule places it, `ui/OverlayLayout.kt`): it names
 * the current activity ([AppSettings.trackActivity]), or while recording the recording's (which the picker changes along
 * with the current one), and a tap opens the picker under the track bar ([ActivityPicker.openForMode]). Compose state on
 * the main thread.
 */
object ActivityChip {
    /** What the chip names now: the recording's activity while one runs, else [current]. */
    fun shown(): Activity = TrackingUiState.ui.let { u -> if (u.recording) u.activeActivity ?: ActivityChipUiState.current else ActivityChipUiState.current }

    /** The chip's word: the activity's label (ADR 0031: the owner's to adjust). */
    fun label(a: Activity): String = RecordingProfiles.label(a)

    /** Its spoken label. */
    fun description(a: Activity, recording: Boolean): String =
        (if (recording) "Recording's activity: " else "Activity: ") + label(a) + ". Change the activity"

    /** `GET /tracks/picker`'s `chip`. Main thread. */
    fun json(): Map<String, Any?> {
        val recording = TrackingUiState.ui.recording
        val a = shown()
        return linkedMapOf(
            "activity" to a.code, "label" to label(a), "description" to description(a, recording), "recording" to recording,
            "current" to AppSettings.trackActivity.code, "routeMode" to ActivityMode.routeMode(AppSettings.trackActivity).label,
            "askOnRecord" to AppSettings.recordAskActivity,
        )
    }
}

/** T263: the chip ([ActivityChip]); composed over the map beside the track controls, drawn where the layout rule puts it. */
@Composable
fun ActivityChipOverlay() {
    LaunchedEffect(Unit) {
        while (true) {
            val a = AppSettings.trackActivity
            if (a != ActivityChipUiState.current) ActivityChipUiState.current = a
            io.github.jasondorjeshort.dystopo.app.Foreground.delay(500) // T150: paused in the background
        }
    }
    // The picker the chip opened goes with the track bar when the bar is hidden (Hide, Back).
    if (ActivityPicker.target == ActivityPicker.MODE && !io.github.jasondorjeshort.dystopo.app.tracking.TrackBar.open) {
        LaunchedEffect(Unit) { ActivityPicker.close() }
    }
    val recording = TrackingUiState.ui.recording
    val a = ActivityChip.shown()
    io.github.jasondorjeshort.dystopo.app.MapChrome { // T91: capped font scale, as the other chips
        Box(Modifier.fillMaxSize()) {
            BasicText(
                ActivityChip.label(a),
                softWrap = false,
                style = TextStyle(color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                modifier = Modifier.align(Alignment.TopEnd).padding(end = 8.dp)
                    .control("activityChip") // the node first, then the semantics and the tap (T243)
                    .semantics { contentDescription = ActivityChip.description(a, recording) }
                    .background(Paper, RoundedCornerShape(16.dp))
                    .border(1.dp, if (recording) RecordRed else Line, RoundedCornerShape(16.dp))
                    .clickable(onClickLabel = "Change the activity") {
                        if (ActivityPicker.openedByChip) ActivityPicker.close() else ActivityPicker.openForMode()
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

private val Ink = Color(0xFF2B2420)
private val Paper = Color(0xEEFFFCF5)
private val Line = Color(0xFFB9A88F)
private val RecordRed = Color(0xFFC62828)
