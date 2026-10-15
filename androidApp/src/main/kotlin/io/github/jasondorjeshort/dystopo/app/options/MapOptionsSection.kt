package io.github.jasondorjeshort.dystopo.app.options

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.maplibre.ContourLabelDensity
import io.github.jasondorjeshort.dystopo.maplibre.MapOptions
import io.github.jasondorjeshort.dystopo.tiles.HillshadeMode
import java.util.Locale

/**
 * T47: the map options section of the settings sheet. Every choice goes through [change] as the same
 * key/value pair `POST /settings` takes ([MapOptions.KEYS]), so the sheet and the debug route apply options
 * the same way.
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun MapOptionsSection(change: (Map<String, Any?>) -> Unit) {
    val o = AppSettings.mapOptions
    val units = AppSettings.units
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BasicText("Map", style = TextStyle(color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold))
        OptionRow("Hillshade", null) {
            for (m in HillshadeMode.entries) {
                Chip(m.key.replaceFirstChar { it.uppercase() }, o.hillshade == m) { change(mapOf("hillshade" to m.key)) }
            }
        }
        if (o.hillshade != HillshadeMode.OFF) {
            OptionRow("Intensity", null) {
                Chip("-", false) { change(mapOf("hillshadeIntensity" to step(o.hillshadeIntensity, -0.1))) }
                BasicText(String.format(Locale.US, "%d%%", Math.round(o.hillshadeIntensity * 100)), Modifier.width(48.dp).padding(top = 8.dp), style = TextStyle(color = Ink, fontSize = 14.sp))
                Chip("+", false) { change(mapOf("hillshadeIntensity" to step(o.hillshadeIntensity, 0.1))) }
            }
        }
        OptionRow("Slope angle", "CalTopo bands: yellow 27 to red 45 to blue 60+ degrees") {
            Chip("On", o.slope) { change(mapOf("slope" to "on")) }
            Chip("Off", !o.slope) { change(mapOf("slope" to "off")) }
        }
        val choices = MapOptions.intervals(units)
        OptionRow("Contours", "fixed interval from map zoom ${MapOptions.FIXED_INTERVAL_FROM_ZOOM}; coarser below") {
            Chip("Auto", o.contourInterval == null) { change(mapOf("contourInterval" to "auto")) }
            val i = o.contourInterval?.let { choices.indexOf(it) } ?: -1
            Chip("<", false) { change(mapOf("contourInterval" to MapOptions.fmt(choices[if (i <= 0) choices.size - 1 else i - 1]))) }
            BasicText(
                o.contourInterval?.let { MapOptions.fmt(it) + " " + units.symbol } ?: "-",
                Modifier.width(56.dp).padding(top = 8.dp), style = TextStyle(color = Ink, fontSize = 14.sp),
            )
            Chip(">", false) { change(mapOf("contourInterval" to MapOptions.fmt(choices[if (i < 0 || i >= choices.size - 1) 0 else i + 1]))) }
        }
        OptionRow("Contour labels", null) {
            for (d in ContourLabelDensity.entries) {
                Chip(d.key.replaceFirstChar { it.uppercase() }, o.contourLabels == d) { change(mapOf("contourLabels" to d.key)) }
            }
        }
        OptionRow("Layers", "tap to show or hide") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("Trails", o.trails) { change(mapOf("trails" to !o.trails)) }
                Chip("Water", o.water) { change(mapOf("water" to !o.water)) }
                Chip("Land cover", o.landcover) { change(mapOf("landcover" to !o.landcover)) }
                Chip("POIs", o.pois) { change(mapOf("pois" to !o.pois)) }
                Chip("Boundaries", o.boundaries) { change(mapOf("boundaries" to !o.boundaries)) }
            }
        }
        OptionRow("Night", "dark base, dimmed relief") {
            Chip("On", o.night) { change(mapOf("night" to "on")) }
            Chip("Off", !o.night) { change(mapOf("night" to "off")) }
        }
        // T101: the scale bar lower left on the map; a device setting of its own (POST /scalebar?both=on|off).
        val both = io.github.jasondorjeshort.dystopo.app.controls.ScaleBarSettings.bothUnits
        OptionRow("Scale bar", "both units adds the other system below the line, as on printed maps") {
            Chip("One unit", !both) { io.github.jasondorjeshort.dystopo.app.controls.ScaleBarSettings.setBothUnits(false) }
            Chip("Both units", both) { io.github.jasondorjeshort.dystopo.app.controls.ScaleBarSettings.setBothUnits(true) }
        }
    }
}

private fun step(v: Double, d: Double): String = String.format(Locale.US, "%.1f", (Math.round((v + d) * 10) / 10.0).coerceIn(0.0, 1.0))

@Composable
private fun OptionRow(label: String, hint: String?, options: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            BasicText(label, Modifier.width(110.dp), style = TextStyle(color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium))
            options()
        }
        hint?.let { BasicText(it, style = TextStyle(color = Muted, fontSize = 11.sp)) }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    BasicText(
        label,
        style = TextStyle(color = if (selected) Paper else Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium),
        modifier = Modifier
            .background(if (selected) Accent else Color(0xEEFFFCF5), RoundedCornerShape(16.dp))
            .border(1.dp, if (selected) Accent else Line, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

private val Ink = Color(0xFF2B2420)
private val Muted = Color(0xFF6B5A4A)
private val Paper = Color(0xFFFFFCF5)
private val Accent = Color(0xFF7A4E2D)
private val Line = Color(0xFFE2D8C6)
