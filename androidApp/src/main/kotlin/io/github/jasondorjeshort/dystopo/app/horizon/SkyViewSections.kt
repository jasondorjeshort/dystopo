package io.github.jasondorjeshort.dystopo.app.horizon

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jasondorjeshort.dystopo.app.ClockFormat
import io.github.jasondorjeshort.dystopo.app.gnss.Gnss
import io.github.jasondorjeshort.dystopo.app.gnss.GnssPalette
import io.github.jasondorjeshort.dystopo.app.gnss.GnssText
import io.github.jasondorjeshort.dystopo.app.help.HelpIcon
import io.github.jasondorjeshort.dystopo.horizon.PeakRim
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import io.github.jasondorjeshort.dystopo.render.UnitFormat
import java.time.LocalDate

// T325: the sections of the sky view's scrolling area, each on its own, cut from HorizonScreen.kt with no change in what
// they draw (the Satellites section is `app/gnss/`'s SatellitesSection). A host shows a section for each layer it offers.
// Each takes its help key as `help` (the registry's test finds a text by `help = "key"` in the file that declares it,
// the host's: HORIZON_HELP's `horizon.peaks`, `horizon.sun` and `horizon.flights` are HorizonScreen.kt's).

/** T308: the sun's path colour: a warm amber, legible on the day and the night paper (the plot's path and the Sun section's legend). */
internal fun sunColour(night: Boolean): Color = if (night) Color(0xFFE6A84E) else Color(0xFFB8641A)

/** T312: the emergency ring's colour (the shape carries the meaning; the red adds to it). */
internal val EMERGENCY = Color(0xFFD0021B)

@Composable
private fun SectionHead(title: String, pal: GnssPalette, extra: String? = null, help: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        BasicText(title, style = TextStyle(color = pal.ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold))
        extra?.let { BasicText("  $it", style = TextStyle(color = pal.muted, fontSize = 14.sp)) }
        help()
    }
}

/**
 * T309: the peaks in view or just out of sight: how many, then each (name, height, distance, bearing) closest first; a tap
 * picks one ([HorizonUi.selected], the key [HorizonUi.keyOf]), bold while the plot names it ([HorizonUi.namedKeys]).
 * T325: [h] the horizon the peaks were found on, [peaks] those found, [selected] the picked key. T326: [rangeM] the host's
 * reach, as the count's line says it (the Horizon view's 30 km, the Panorama's view range).
 */
@Composable
fun PeaksSection(
    h: Gnss.HorizonState?, peaks: HorizonData.Peaks?, selected: String?, pal: GnssPalette, units: ContourUnits, help: String,
    rangeM: Double = io.github.jasondorjeshort.dystopo.dem.Horizon.DEFAULT_RANGE_M,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionHead(HorizonText.PEAKS, pal) { HelpIcon(help) }
        val line = when {
            h == null -> HorizonText.PEAKS_WORKING
            h.profile == null -> HorizonText.PEAKS_NO_TERRAIN
            peaks == null -> HorizonText.PEAKS_WORKING
            peaks.reason == HorizonText.PEAKS_NONE -> HorizonText.peaksNone(units, rangeM)
            peaks.reason != null -> peaks.reason
            else -> HorizonText.peaksCount(peaks.candidates.size, units, rangeM)
        }
        BasicText(line, style = TextStyle(color = pal.ink, fontSize = 14.sp))
        val named = HorizonUi.namedKeys
        for (p in peaks?.candidates.orEmpty()) {
            val key = HorizonUi.keyOf(p)
            val chosen = key == selected
            Column(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { this.selected = chosen }
                    .clickable(role = Role.Button) { HorizonUi.selected = if (chosen) null else key }.padding(vertical = 4.dp),
            ) {
                BasicText(
                    p.peak.name, style = TextStyle(color = if (chosen) pal.link else pal.ink, fontSize = 15.sp, fontWeight = if (key in named || chosen) FontWeight.Bold else FontWeight.Normal),
                )
                BasicText(
                    "${UnitFormat.elevation(p.eleM, units)} · ${UnitFormat.distance(p.distanceM, units)} · ${GnssText.compassPoint(p.bearingDeg)} ${Math.round(p.bearingDeg) % 360}°" +
                        if (PeakRim.inSight(p)) "" else " · ${HorizonText.JUST_OUT_OF_SIGHT}",
                    style = TextStyle(color = pal.muted, fontSize = 13.sp),
                )
            }
        }
    }
}

/**
 * T312: the flights on the plot, closest first (as the Peaks list): how many, or the feed's state; then each: a dot as the
 * plot draws it (an emergency's ring too), the label (bold when picked), and distance, bearing, angle, altitude in the
 * user's units, the kind and an emergency's line; a tap opens its card ([HorizonUi.selectedFlight], [selected] its hex).
 */
@Composable
fun FlightsSection(f: HorizonData.Flights, havePosition: Boolean, selected: String?, pal: GnssPalette, units: ContourUnits, help: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionHead(HorizonText.FLIGHTS, pal) { HelpIcon(help) }
        val line = when {
            !havePosition -> HorizonText.FLIGHTS_NO_POSITION
            f.list.isNotEmpty() -> HorizonText.flightsCount(f.list.size, f.range, units)
            f.message != null -> f.message
            else -> HorizonText.flightsNone(f.range, units)
        }
        BasicText(line, style = TextStyle(color = pal.ink, fontSize = 14.sp))
        // A feed that is offline, stopped or limited says so above the rows it still holds.
        if (f.list.isNotEmpty() && f.message != null) BasicText(f.message, style = TextStyle(color = pal.muted, fontSize = 13.sp))
        for (s in f.list) {
            val chosen = s.hex == selected
            val emergency = s.aircraft.report.emergency
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { this.selected = chosen }
                    .clickable(role = Role.Button) { HorizonUi.selectedFlight = if (chosen) null else s.hex }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.foundation.Canvas(Modifier.size(24.dp)) {
                    val c = Offset(size.width / 2, size.height / 2)
                    val r = 4.5.dp.toPx()
                    val colour = altitudeColour(s.aircraft.altitudeM)
                    if (s.hollow) {
                        drawCircle(pal.sky, r, c)
                        drawCircle(colour, r - 1.dp.toPx(), c, style = Stroke(2.dp.toPx()))
                    } else {
                        drawCircle(colour, r, c)
                        drawCircle(pal.ink, r, c, style = Stroke(1.dp.toPx()))
                    }
                    if (emergency != null) drawCircle(EMERGENCY, r + 4.dp.toPx(), c, style = Stroke(2.dp.toPx()))
                }
                Column(Modifier.weight(1f).padding(start = 6.dp)) {
                    BasicText(
                        s.label + (emergency?.let { " · ${it.line} (${it.squawk})" } ?: ""),
                        style = TextStyle(color = if (chosen) pal.link else pal.ink, fontSize = 15.sp, fontWeight = if (chosen || emergency != null) FontWeight.Bold else FontWeight.Normal),
                    )
                    BasicText(
                        listOfNotNull(
                            HorizonText.kind(s.aircraft.report.kind),
                            HorizonText.flightLine(s.distanceM, s.bearingDeg, s.elevationDeg, HorizonData.altitudeText(s, units), s.hidden, s.aircraft.ageS.takeIf { s.stale }, units),
                        ).joinToString(" · "),
                        style = TextStyle(color = pal.muted, fontSize = 13.sp),
                    )
                }
            }
        }
    }
}

/**
 * The sun's day: where it is now, when it clears and leaves the terrain (and any gaps), and the flat horizon's times.
 * T325: [havePosition] whether the view has a point, [day] the sun's day there ([HorizonData.sunFor]), [now] the sun now,
 * [fn] the horizon's angle at a bearing (null: no terrain), [tick] the view's clock (read so the section is read again).
 */
@Composable
fun SunSection(
    havePosition: Boolean, day: HorizonData.SunDay?, now: io.github.jasondorjeshort.dystopo.nav.Sun.Position?, fn: ((Double) -> Double)?, pal: GnssPalette, night: Boolean, tick: Int, help: String,
    /** T327: the sun now's line as a row (48 dp) whose tap calls this while the sun is up (the Panorama's target); null: a plain line. */
    onNow: (() -> Unit)? = null,
    /** T327: the sun is the host's target (the row reads selected, in the link colour). */
    nowChosen: Boolean = false,
) {
    @Suppress("UNUSED_VARIABLE") val t = tick
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val dateText = day?.let { d ->
            val date = LocalDate.of(d.sun.year, d.sun.month, d.sun.day)
            if (HorizonUi.date == null) HorizonText.TODAY else date.format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.getDefault()))
        }
        SectionHead(HorizonText.SUN, pal, dateText) { HelpIcon(help) }
        if (!havePosition || day == null) {
            BasicText(HorizonText.SUN_NO_POSITION, style = TextStyle(color = pal.ink, fontSize = 14.sp))
            return@Column
        }
        val style = TextStyle(color = pal.ink, fontSize = 14.sp)
        val c = { ms: Long -> ClockFormat.text(ms) }
        if (HorizonUi.date == null && now != null) {
            val line = HorizonText.sunNow(now.apparentAltitudeDeg, now.azimuthDeg, fn?.let { it(now.azimuthDeg) > now.apparentAltitudeDeg } ?: false)
            if (onNow != null && HorizonText.sunUp(now.apparentAltitudeDeg)) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { this.selected = nowChosen }.clickable(role = Role.Button) { onNow() }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText(line, Modifier.weight(1f), style = style.copy(color = if (nowChosen) pal.link else pal.ink, fontWeight = if (nowChosen) FontWeight.Bold else FontWeight.Normal))
                }
            } else BasicText(line, style = style)
        }
        val td = day.sun.terrain
        if (day.sun.onTerrain) {
            when {
                td.alwaysUp -> BasicText(HorizonText.SUN_ALL_DAY, style = style)
                td.sunlit.isEmpty() -> BasicText(HorizonText.SUN_NEVER, style = style)
                else -> {
                    BasicText(HorizonText.terrainTimes(td.sunriseMs?.let(c), td.sunsetMs?.let(c)), style = style.copy(fontWeight = FontWeight.SemiBold))
                    for (g in td.gaps) BasicText(HorizonText.gap(c(g.span.startMs), c(g.span.endMs), g.bearingDeg), style = style)
                    BasicText(HorizonText.sunMinutes(td.sunMinutes), style = style)
                }
            }
        } else BasicText(HorizonText.SUN_FLAT_ONLY, style = TextStyle(color = pal.muted, fontSize = 13.sp))
        BasicText(HorizonText.flat(td.flat.sunriseMs?.let(c), td.flat.sunsetMs?.let(c)), style = style)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            androidx.compose.foundation.Canvas(Modifier.width(28.dp).heightIn(min = 14.dp)) {
                drawLine(sunColour(night), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2.5.dp.toPx(), cap = StrokeCap.Round)
            }
            BasicText(HorizonText.SUN_LEGEND, Modifier.weight(1f), style = TextStyle(color = pal.muted, fontSize = 13.sp))
        }
    }
}
