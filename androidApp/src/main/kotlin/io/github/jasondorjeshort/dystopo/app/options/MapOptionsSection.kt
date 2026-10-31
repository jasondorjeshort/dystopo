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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.app.SettingsUi
import io.github.jasondorjeshort.dystopo.app.help.HelpIcon
import io.github.jasondorjeshort.dystopo.app.help.HelpText
import io.github.jasondorjeshort.dystopo.app.settings.SettingsGroup
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
        OptionRow("Hillshade", null, help = "map.hillshade") {
            for (m in HillshadeMode.entries) {
                Chip(m.key.replaceFirstChar { it.uppercase() }, o.hillshade == m) { change(mapOf("hillshade" to m.key)) }
            }
        }
        if (o.hillshade != HillshadeMode.OFF) {
            OptionRow("Intensity", null, help = "map.intensity") {
                Stepper(
                    String.format(Locale.US, "%d%%", Math.round(o.hillshadeIntensity * 100)), 56,
                    { change(mapOf("hillshadeIntensity" to step(o.hillshadeIntensity, -0.1))) },
                    { change(mapOf("hillshadeIntensity" to step(o.hillshadeIntensity, 0.1))) },
                )
            }
        }
        OptionRow("Slope shading", SAFETY_LINE, help = "map.slope") {
            Chip("Off", o.slope == SlopeShading.OFF) { change(mapOf("slope" to SlopeShading.OFF.key)) }
            Chip("Avalanche", o.slope == SlopeShading.AVALANCHE) { change(mapOf("slope" to SlopeShading.AVALANCHE.key)) }
            Chip("Steepness", o.slope == SlopeShading.STEEPNESS) { change(mapOf("slope" to SlopeShading.STEEPNESS.key)) }
        }
        if (o.slope != SlopeShading.OFF) SlopeLegend(o.slope)
        // Terrain overlays worked out on the phone for a moment or a point: T188's shade first (the viewshed, T182, after it).
        SunShadeRow()
        val choices = MapOptions.intervals(units)
        OptionRow("Contours", "fixed interval from map zoom ${MapOptions.FIXED_INTERVAL_FROM_ZOOM}; coarser below", help = "map.contours") {
            Chip("Auto", o.contourInterval == null) { change(mapOf("contourInterval" to "auto")) }
            val i = o.contourInterval?.let { choices.indexOf(it) } ?: -1
            Stepper(
                o.contourInterval?.let { MapOptions.fmt(it) + " " + units.symbol } ?: "-", 64,
                { change(mapOf("contourInterval" to MapOptions.fmt(choices[if (i <= 0) choices.size - 1 else i - 1]))) },
                { change(mapOf("contourInterval" to MapOptions.fmt(choices[if (i < 0 || i >= choices.size - 1) 0 else i + 1]))) },
                "<", ">",
            )
        }
        OptionRow("Contour labels", null, help = "map.contourLabels") {
            for (d in ContourLabelDensity.entries) {
                Chip(d.key.replaceFirstChar { it.uppercase() }, o.contourLabels == d) { change(mapOf("contourLabels" to d.key)) }
            }
        }
        OptionRow("Layers", "tap to show or hide", help = "map.layers") {
            Chip("Trails", o.trails) { change(mapOf("trails" to !o.trails)) }
            Chip("Water", o.water) { change(mapOf("water" to !o.water)) }
            Chip("Land cover", o.landcover) { change(mapOf("landcover" to !o.landcover)) }
            Chip("POIs", o.pois) { change(mapOf("pois" to !o.pois)) }
            Chip("Boundaries", o.boundaries) { change(mapOf("boundaries" to !o.boundaries)) }
        }
        // T174: the streams derived from the elevation grid, beside the layers; off unless turned on.
        OptionRow("Drainage", "streams worked out from the terrain, where an offline area has them", help = "map.drainage") {
            Chip("On", o.drainage) { change(mapOf("drainage" to "on")) }
            Chip("Off", !o.drainage) { change(mapOf("drainage" to "off")) }
        }
        OptionRow("Night", "dark base, dimmed relief", help = "map.night") {
            Chip("On", o.night) { change(mapOf("night" to "on")) }
            Chip("Off", !o.night) { change(mapOf("night" to "off")) }
        }
        // T101: the scale bar lower left on the map; a device setting of its own (POST /scalebar?both=on|off).
        val both = io.github.jasondorjeshort.dystopo.app.controls.ScaleBarSettings.bothUnits
        OptionRow("Scale bar", "both units adds the other system below the line, as on printed maps", help = "map.scaleBar") {
            Chip("One unit", !both) { io.github.jasondorjeshort.dystopo.app.controls.ScaleBarSettings.setBothUnits(false) }
            Chip("Both units", both) { io.github.jasondorjeshort.dystopo.app.controls.ScaleBarSettings.setBothUnits(true) }
        }
    }
}

/**
 * T188: the Shade row ([SunShade]): On and Off (off by default and at each launch); when on, the day (a step either way,
 * a tap on the date for the system's date picker, Today to come back) and the time (a quarter hour either way, and a
 * slider over the day from an hour before sunrise to an hour after sunset at the view's centre). The slider moves the
 * time shown while dragged and the map's shade when let go.
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun SunShadeRow() {
    val on = SunShade.on
    OptionRow("Shade", SHADE_HINT, help = "map.shade") {
        Chip("On", on) { SunShade.turn(true) }
        Chip("Off", !on) { SunShade.turn(false) }
    }
    if (!on) return
    val zone = SunShade.zone()
    val context = androidx.compose.ui.platform.LocalContext.current
    val date = SunShade.date
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Wraps at large font scales (Today drops to a second line).
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("<", false) { SunShade.pickDate(date.minusDays(1)) }
            BasicText(
                SunClock.dateLabel(date),
                Modifier.widthIn(min = 92.dp).clickable {
                    android.app.DatePickerDialog(context, { _, y, m, d -> SunShade.pickDate(java.time.LocalDate.of(y, m + 1, d)) }, date.year, date.monthValue - 1, date.dayOfMonth).show()
                }.padding(vertical = 7.dp),
                style = TextStyle(color = Accent, fontSize = 14.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center), softWrap = false, maxLines = 1,
            )
            Chip(">", false) { SunShade.pickDate(date.plusDays(1)) }
            if (date != SunShade.today()) Chip("Today", false) { SunShade.pickDate(SunShade.today()) }
        }
        var drag by remember { mutableStateOf<Long?>(null) }
        val shown = drag ?: SunShade.instantMs
        val range = SunShade.range
        Stepper(SunClock.clock(shown, zone) + " " + SunClock.zoneName(zone, shown), 92, { SunShade.step(-1) }, { SunShade.step(1) }, "<", ">")
        TimeSlider(range, shown, onMove = { drag = it }, onDone = { drag?.let { SunShade.setInstant(it, "slider") }; drag = null })
        Row(Modifier.fillMaxWidth()) {
            BasicText(SunClock.clock(range.startMs, zone), Modifier.weight(1f), style = TextStyle(color = Muted, fontSize = 11.sp))
            BasicText(SunClock.clock(range.endMs, zone), style = TextStyle(color = Muted, fontSize = 11.sp))
        }
    }
}

/** T188: under the Shade row's chips. */
internal const val SHADE_HINT = "blue where the terrain hides the sun at the time below; night is all shade"

/**
 * T188: a slider over [range] at [value]: a tap or a drag along it calls [onMove] with the instant there (snapped), and
 * the end of the gesture [onDone]. For accessibility it is a range with a set-progress action, as a slider is.
 */
@Composable
private fun TimeSlider(range: SunClock.Range, value: Long, onMove: (Long) -> Unit, onDone: () -> Unit) {
    var width by remember { mutableStateOf(1) }
    val thumb = 22.dp
    val thumbPx = with(androidx.compose.ui.platform.LocalDensity.current) { thumb.toPx() }
    fun at(x: Float): Long = range.at(((x - thumbPx / 2) / (width - thumbPx).coerceAtLeast(1f)).toDouble())
    val f = range.fraction(value).toFloat()
    Box(
        Modifier.fillMaxWidth().height(40.dp)
            .onSizeChanged { width = it.width.coerceAtLeast(1) }
            .pointerInput(range) { detectTapGestures { onMove(at(it.x)); onDone() } }
            .pointerInput(range) {
                detectHorizontalDragGestures(onDragEnd = onDone, onDragCancel = onDone) { change, _ ->
                    change.consume()
                    onMove(at(change.position.x))
                }
            }
            .semantics {
                contentDescription = "Time of day for the shade"
                progressBarRangeInfo = ProgressBarRangeInfo(f, 0f..1f)
                setProgress { v -> onMove(range.at(v.toDouble())); onDone(); true }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.padding(horizontal = thumb / 2).fillMaxWidth().height(4.dp).background(Line, RoundedCornerShape(2.dp)))
        Box(
            Modifier.offset { IntOffset(((width - thumbPx) * f).roundToInt(), 0) }
                .size(thumb).background(Accent, CircleShape).border(2.dp, Paper, CircleShape),
        )
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(legendCaption(shading), Modifier.weight(1f, fill = false), style = TextStyle(color = Muted, fontSize = 12.sp))
            HelpIcon("map.slopeLegend")
        }
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

/**
 * Label above, choices below in a [FlowRow] that wraps at large font scales, then the hint. T109: [help] is the key of
 * the row's help pop-up ([MAP_OPTIONS_HELP]); its question mark sits right after the label, clear of the choices.
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun OptionRow(label: String, hint: String?, help: String? = null, options: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(label, Modifier.weight(1f, fill = false), style = TextStyle(color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium))
            help?.let { HelpIcon(it) }
        }
        // T109: 6 dp more under a help icon, so its 48 dp target stays clear of the chips' own touch targets.
        FlowRow(Modifier.padding(top = if (help != null) 6.dp else 0.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { options() }
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

/**
 * T109: the help pop-ups of the rows above, by the key each row's [HelpIcon] names (`help = "map.…"` in its
 * [OptionRow]); a row removed here takes its text with it. Each opens Settings › Map options for `POST /help/open`.
 */
internal val MAP_OPTIONS_HELP: List<HelpText> = run {
    val place = "${io.github.jasondorjeshort.dystopo.app.firstrun.Screens.SETTINGS} › ${SettingsGroup.MAP.title}" // T115: › as in messages
    val show = { SettingsUi.open(SettingsGroup.MAP) }
    listOf(
        HelpText("map.hillshade", "Hillshade", "$place › Hillshade", show = show) {
            "Shades the terrain as if lit from one side, so ridges and valleys stand out. Single lights it from the " +
                "north-west, as printed maps do; Multi blends lights from several directions, so slopes facing away " +
                "from the light keep their detail. Off leaves the map flat, with contours only."
        },
        HelpText("map.intensity", "Hillshade intensity", "$place › Intensity", needs = "hillshade on", show = show) {
            "How dark the hillshade is. Lower it when the shading makes labels, trails or the slope colours hard to read."
        },
        HelpText("map.slope", "Slope shading", "$place › Slope shading", show = show) {
            "Colours the ground by how steep it is, worked out on this phone from the elevation data. Avalanche marks " +
                "the angles where most avalanches start; Steepness just gets darker as the ground gets steeper. Neither " +
                "is an avalanche forecast: it knows nothing of snow, weather or what lies above you."
        },
        HelpText("map.slopeLegend", "Slope colours", "$place › Slope shading, the legend", needs = "slope shading on", show = show) {
            "Each colour is a band of slope angle in degrees, as the swatches show. The angle is measured over the " +
                "elevation grid at the map's zoom: zoomed out, a short steep step blurs into a gentler slope, so zoom " +
                "in before you judge a slope."
        },
        HelpText("map.shade", "Shade", "$place › Shade", show = show) { // T188
            "Tints the ground blue where the terrain keeps the sun off it at the time shown, ridges far away included, " +
                "worked out on this phone from the elevation data; at night it is all shade. It opens at now while the " +
                "sun is up today, else two hours before sunset; pick another date, or drag the time."
        },
        HelpText("map.contours", "Contours", "$place › Contours", show = show) {
            "Lines of equal elevation, in your units. Auto picks the interval from the zoom. A fixed interval applies " +
                "from map zoom ${MapOptions.FIXED_INTERVAL_FROM_ZOOM} in; zoomed out, coarser lines keep the map readable. " +
                "The thicker index lines carry the elevation labels."
        },
        HelpText("map.contourLabels", "Contour labels", "$place › Contour labels", show = show) {
            "How many elevation labels sit on the thicker contour lines: Sparse, Normal or Dense. A label is only " +
                "drawn where it fits along its line."
        },
        HelpText("map.layers", "Layers", "$place › Layers", show = show) {
            "Shows or hides parts of the map. Trails: paths and tracks. Water: lakes, rivers, streams. Land cover: " +
                "forest, wetland, rock and glacier. POIs: huts, trailheads, campsites, viewpoints, springs, parking. " +
                "Boundaries: parks and wilderness. Roads, peaks, places and contours always show."
        },
        HelpText("map.drainage", "Drainage", "$place › Drainage", show = show) {
            "Stream lines worked out from an offline area's elevation data, drawn lighter than and under the mapped " +
                "streams: solid where water likely runs all year, dashed where part of the year, dots where likely dry. " +
                "They come from the shape of the land alone, so a line may be dry or a stream missing."
        },
        HelpText("map.night", "Night", "$place › Night", show = show) {
            "A dark map for use in the dark: a dark base with dimmed relief, easier on your eyes than a bright screen."
        },
        HelpText("map.scaleBar", "Scale bar", "$place › Scale bar", show = show) {
            "The bar in the lower left shows a round distance at the map's zoom, in your units. Both units adds the " +
                "other system below the line, as on printed maps."
        },
    )
}
