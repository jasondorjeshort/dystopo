package io.github.jasondorjeshort.dystopo.app.options

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.app.SettingsUi
import io.github.jasondorjeshort.dystopo.maplibre.ContourLabelDensity
import io.github.jasondorjeshort.dystopo.maplibre.MapOptions
import io.github.jasondorjeshort.dystopo.render.SlopeShading
import io.github.jasondorjeshort.dystopo.tiles.HillshadeMode
import io.github.jasondorjeshort.dystopo.tiles.ShadeTiles
import java.util.Locale
import kotlin.math.roundToInt

/**
 * T47: the map options section of the settings sheet. Every choice goes through [change] as the same
 * key/value pair `POST /settings` takes ([MapOptions.KEYS]), so the sheet and the debug route apply options
 * the same way.
 *
 * T104: the slope shading is off, avalanche slope angle or steepness, with a legend of its bands and the line
 * that it is not an avalanche forecast ([SlopeLegend]). Each row puts its label above its choices and the
 * choices in a [FlowRow], so at a large font scale they wrap instead of squeezing the last one.
 *
 * The options come from [AppSettings.mapOptions], which is not Compose state, and the caller passes the same
 * [change] lambda every time, so without more Compose skips this section when the sheet recomposes after a change:
 * a tapped chip applied its setting while the chips, caption and legend kept showing the old one until the sheet
 * was reopened. Reading [SettingsUi.revision] (bumped after every applied change) here makes the section recompose
 * with the sheet.
 */
@Composable
fun MapOptionsSection(change: (Map<String, Any?>) -> Unit) {
    @Suppress("UNUSED_VARIABLE") val revision = SettingsUi.revision // recompose after each applied change
    val o = AppSettings.mapOptions
    val units = AppSettings.units
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BasicText("Map", style = TextStyle(color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold))
        OptionRow("Hillshade", null) {
            for (m in HillshadeMode.entries) {
                Chip(m.key.replaceFirstChar { it.uppercase() }, o.hillshade == m) { change(mapOf("hillshade" to m.key)) }
            }
        }
        if (o.hillshade != HillshadeMode.OFF) {
            OptionRow("Intensity", null) {
                Stepper(
                    String.format(Locale.US, "%d%%", Math.round(o.hillshadeIntensity * 100)), 56,
                    { change(mapOf("hillshadeIntensity" to step(o.hillshadeIntensity, -0.1))) },
                    { change(mapOf("hillshadeIntensity" to step(o.hillshadeIntensity, 0.1))) },
                )
            }
        }
        OptionRow("Slope shading", SAFETY_LINE) {
            Chip("Off", o.slope == SlopeShading.OFF) { change(mapOf("slope" to SlopeShading.OFF.key)) }
            Chip("Avalanche", o.slope == SlopeShading.AVALANCHE) { change(mapOf("slope" to SlopeShading.AVALANCHE.key)) }
            Chip("Steepness", o.slope == SlopeShading.STEEPNESS) { change(mapOf("slope" to SlopeShading.STEEPNESS.key)) }
        }
        if (o.slope != SlopeShading.OFF) SlopeLegend(o.slope)
        val choices = MapOptions.intervals(units)
        OptionRow("Contours", "fixed interval from map zoom ${MapOptions.FIXED_INTERVAL_FROM_ZOOM}; coarser below") {
            Chip("Auto", o.contourInterval == null) { change(mapOf("contourInterval" to "auto")) }
            val i = o.contourInterval?.let { choices.indexOf(it) } ?: -1
            Stepper(
                o.contourInterval?.let { MapOptions.fmt(it) + " " + units.symbol } ?: "-", 64,
                { change(mapOf("contourInterval" to MapOptions.fmt(choices[if (i <= 0) choices.size - 1 else i - 1]))) },
                { change(mapOf("contourInterval" to MapOptions.fmt(choices[if (i < 0 || i >= choices.size - 1) 0 else i + 1]))) },
                "<", ">",
            )
        }
        OptionRow("Contour labels", null) {
            for (d in ContourLabelDensity.entries) {
                Chip(d.key.replaceFirstChar { it.uppercase() }, o.contourLabels == d) { change(mapOf("contourLabels" to d.key)) }
            }
        }
        OptionRow("Layers", "tap to show or hide") {
            Chip("Trails", o.trails) { change(mapOf("trails" to !o.trails)) }
            Chip("Water", o.water) { change(mapOf("water" to !o.water)) }
            Chip("Land cover", o.landcover) { change(mapOf("landcover" to !o.landcover)) }
            Chip("POIs", o.pois) { change(mapOf("pois" to !o.pois)) }
            Chip("Boundaries", o.boundaries) { change(mapOf("boundaries" to !o.boundaries)) }
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

/** T104: shown under the slope shading row, whichever shading is chosen. */
internal const val SAFETY_LINE = "Slope shading is not an avalanche forecast."

/** T104: what each shading's colours mean, above its swatches. */
internal fun legendCaption(shading: SlopeShading): String = when (shading) {
    SlopeShading.AVALANCHE -> "Slope angle in degrees. Most avalanches start on 30 to 45° slopes, most often near 38° (red); " +
        "fewer on 25 to 29° (yellow) and above 45° (purple, blue). Avalanches from steeper slopes above can reach gentle ground."
    SlopeShading.STEEPNESS -> "Slope angle in degrees. Darker is steeper; no avalanche meaning."
    SlopeShading.OFF -> ""
}

/**
 * T104: the bands of [shading] as swatches, each its colour at the map's tint opacity over the paper tone (as it
 * looks on the map without hillshade), labelled in degrees.
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun SlopeLegend(shading: SlopeShading) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicText(legendCaption(shading), style = TextStyle(color = Muted, fontSize = 12.sp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (band in shading.bands) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(16.dp).background(Color(swatch(band.rgb)), RoundedCornerShape(3.dp)).border(1.dp, Line, RoundedCornerShape(3.dp)))
                    BasicText(degrees(band.label), style = TextStyle(color = Ink, fontSize = 12.sp), softWrap = false, maxLines = 1)
                }
            }
        }
    }
}

/** A band label in degrees: "25-29" is "25-29°", "60+" is "60°+". */
internal fun degrees(label: String): String = if (label.endsWith("+")) label.removeSuffix("+") + "°+" else "$label°"

/** [rgb] at [ShadeTiles.SLOPE_OPACITY] over the paper tone, opaque ARGB. */
internal fun swatch(rgb: Int): Int {
    val a = ShadeTiles.SLOPE_OPACITY
    fun ch(shift: Int) = (((rgb shr shift) and 0xFF) * a + ((PAPER_RGB shr shift) and 0xFF) * (1 - a)).roundToInt().coerceIn(0, 255)
    return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

/** The map's paper tone under the tints (the devtool's base colour). */
private const val PAPER_RGB = 0xF6F1E4

private fun step(v: Double, d: Double): String = String.format(Locale.US, "%.1f", (Math.round((v + d) * 10) / 10.0).coerceIn(0.0, 1.0))

/** Label above, choices below in a [FlowRow] that wraps at large font scales, then the hint. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun OptionRow(label: String, hint: String?, options: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BasicText(label, style = TextStyle(color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { options() }
        hint?.let { BasicText(it, style = TextStyle(color = Muted, fontSize = 11.sp)) }
    }
}

/** A value between a minus and a plus chip, kept on one line together. */
@Composable
private fun Stepper(value: String, minWidthDp: Int, minus: () -> Unit, plus: () -> Unit, minusLabel: String = "-", plusLabel: String = "+") {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Chip(minusLabel, false, minus)
        BasicText(value, Modifier.widthIn(min = minWidthDp.dp), style = TextStyle(color = Ink, fontSize = 14.sp, textAlign = TextAlign.Center), softWrap = false, maxLines = 1)
        Chip(plusLabel, false, plus)
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    BasicText(
        label,
        style = TextStyle(color = if (selected) Paper else Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium),
        softWrap = false,
        maxLines = 1,
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
