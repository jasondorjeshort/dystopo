package io.github.jasondorjeshort.dystopo.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jasondorjeshort.dystopo.maplibre.MapLibreStyle
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import java.util.Locale
import kotlin.concurrent.thread

/** Whether the settings sheet is open (the Settings chip, or `POST /settings/ui?open=1` in debug builds). */
object SettingsUi {
    var visible by mutableStateOf(false)

    /** Bumped after a change is applied, so the sheet re-reads [AppSettings]. */
    var revision by mutableStateOf(0)
    var busy by mutableStateOf(false)
}

/**
 * The settings sheet at the bottom of the map: units (feet or metres; the choice is what PLANNING › Units
 * calls the user preference, after which nothing re-seeds it), text size, and the background contour
 * warm-up. Changes go through the same [SettingsRoutes.apply] as `POST /settings`.
 */
@Composable
fun SettingsSheet() {
    if (!SettingsUi.visible) return
    BackHandler { SettingsUi.visible = false }
    @Suppress("UNUSED_VARIABLE") val rev = SettingsUi.revision // recompose after apply
    val units = AppSettings.units
    val scale = AppSettings.textScale
    val warmup = AppSettings.warmup

    fun change(parsed: SettingsRoutes.Parsed.Ok) {
        if (SettingsUi.busy) return
        SettingsUi.busy = true
        thread(name = "dystopo-settings-ui") {
            try {
                SettingsRoutes(DystopoApp.instance).apply(parsed)
            } finally {
                SettingsUi.busy = false
                SettingsUi.revision++
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(8.dp)
                .heightIn(max = 600.dp) // T47: scrolls with the map options section
                .background(Paper, RoundedCornerShape(12.dp)).border(1.dp, Line, RoundedCornerShape(12.dp))
                .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                BasicText("Settings", Modifier.weight(1f), style = TextStyle(color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold))
                if (SettingsUi.busy) BasicText("applying...", Modifier.padding(end = 12.dp), style = TextStyle(color = Muted, fontSize = 12.sp))
                Option("Close", false) { SettingsUi.visible = false }
            }
            Setting("Units", if (AppSettings.unitPreference.units == null) "from your region until you choose" else null) {
                Option("Feet", units == ContourUnits.FEET) { change(SettingsRoutes.Parsed.Ok(null, true, ContourUnits.FEET, null)) }
                Option("Metres", units == ContourUnits.METERS) { change(SettingsRoutes.Parsed.Ok(null, true, ContourUnits.METERS, null)) }
            }
            Setting("Text size", null) {
                Option("-", false) { change(SettingsRoutes.Parsed.Ok(stepScale(scale, -1), false, null, null)) }
                BasicText(String.format(Locale.US, "%.1fx", scale), Modifier.width(48.dp).padding(top = 8.dp), style = TextStyle(color = Ink, fontSize = 14.sp))
                Option("+", false) { change(SettingsRoutes.Parsed.Ok(stepScale(scale, 1), false, null, null)) }
            }
            Setting("Contour warm-up", "prepares contours in the background after a pack opens") {
                Option("On", warmup) { change(SettingsRoutes.Parsed.Ok(null, false, null, true)) }
                Option("Off", !warmup) { change(SettingsRoutes.Parsed.Ok(null, false, null, false)) }
            }
            // T57: how far a long-pressed route start or end may be from the nearest trail or road.
            val snap = AppSettings.routeSnapM
            Setting("Route snap", "how far a route's start or end may be from a trail or road") {
                for (m in SNAP_CHOICES) {
                    Option(io.github.jasondorjeshort.dystopo.render.UnitFormat.distance(m.toDouble(), units), snap == m) {
                        change(SettingsRoutes.Parsed.Ok(null, false, null, null, routeSnapM = m))
                    }
                }
            }
            io.github.jasondorjeshort.dystopo.app.options.MapOptionsSection { change(SettingsRoutes.Parsed.Ok(null, false, null, null, it)) } // T47
        }
    }
}

/** T57: the snap distances the sheet offers (any value in [AppSettings.ROUTE_SNAP_RANGE] through `/settings`). */
private val SNAP_CHOICES = listOf(300, 500, 1000)

private fun stepScale(scale: Double, dir: Int): Double =
    (Math.round((scale + 0.1 * dir) * 10) / 10.0).coerceIn(MapLibreStyle.MIN_TEXT_SCALE, MapLibreStyle.MAX_TEXT_SCALE)

@Composable
private fun Setting(label: String, hint: String?, options: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicText(label, Modifier.width(120.dp), style = TextStyle(color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium))
            options()
        }
        hint?.let { BasicText(it, style = TextStyle(color = Muted, fontSize = 11.sp)) }
    }
}

@Composable
private fun Option(label: String, selected: Boolean, onClick: () -> Unit) {
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

private val Ink = Color(0xFF2B2420)
private val Muted = Color(0xFF6B5A4A)
private val Paper = Color(0xFFFFFCF5)
private val Accent = Color(0xFF7A4E2D)
private val Line = Color(0xFFE2D8C6)
