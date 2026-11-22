package io.github.jasondorjeshort.dystopo.app.gnss

import android.os.SystemClock
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.SettingsRoutes
import io.github.jasondorjeshort.dystopo.app.horizon.HorizonUi
import io.github.jasondorjeshort.dystopo.app.controls.Heading
import io.github.jasondorjeshort.dystopo.app.firstrun.Screens
import io.github.jasondorjeshort.dystopo.app.help.HelpIcon
import io.github.jasondorjeshort.dystopo.app.help.HelpText
import io.github.jasondorjeshort.dystopo.app.settings.SettingsGroup
import io.github.jasondorjeshort.dystopo.gnss.Constellation
import io.github.jasondorjeshort.dystopo.gnss.GnssHealth
import io.github.jasondorjeshort.dystopo.gnss.SkyPlotOrientation
import io.github.jasondorjeshort.dystopo.render.UnitFormat
import java.util.Locale

/**
 * T307: the satellites layer's menu and confirmation, and what the plot last drew of it ([drawn], `GET /gnss` `drawn`).
 * T308: the GPS view became the Horizon view ([HorizonUi]); [open] and [close] are its, kept here for `/gnss/ui`.
 */
object GnssUi {
    val open: Boolean get() = HorizonUi.open
    var menuOpen by mutableStateOf(false)
    var confirmReset by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)

    @Volatile var drawn: Map<String, Any?>? = null

    /** Makes the Compose state on the main thread (`MainActivity.onCreate`), before a debug route's thread reads it (T226). */
    fun prepare() {
        menuOpen; confirmReset; message
        HorizonUi.prepare()
    }

    /** Opens the Horizon view (the Settings row, `POST /gnss/ui?open=1`, `POST /horizon/ui?open=1`). */
    fun open() = HorizonUi.open()

    fun close() = HorizonUi.close()

    /** The satellites layer's own state, back to closed (the view opening or closing). */
    internal fun resetUi() {
        menuOpen = false
        confirmReset = false
        message = null
        drawn = null
    }

    /** The heading the plot follows: a simulated one (debug), else the compass while it reads; null otherwise. */
    fun headingDeg(): Double? = Heading.simulated ?: Heading.compass.trueHeadingDeg?.takeIf { Heading.compassFresh }

    /** The plot's turn: the heading in Follow the phone (0 without one), 0 in North up. */
    fun rotationDeg(o: SkyPlotOrientation = AppSettings.skyPlot): Double = if (o == SkyPlotOrientation.PHONE) headingDeg() ?: 0.0 else 0.0

    fun reset(say: (String) -> Unit) {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        Thread({
            val line = if (Gnss.resetAssistance()) GnssText.RESET_DONE else GnssText.RESET_REFUSED
            main.post { say(line) }
        }, "dystopo-gnss-reset").start()
    }

    /** What the plot drew of the satellites (`GET /gnss` `drawn`), written by the Horizon view after each composition. */
    fun noteDrawn(plotted: List<PlotSat>, fn: ((Double) -> Double)?, rotation: Double, orientation: SkyPlotOrientation, heading: Double?, horizonDrawn: Boolean, sunDrawn: Boolean, night: Boolean, on: Boolean) {
        drawn = linkedMapOf(
            "layer" to on,
            "satellites" to plotted.size, "tracked" to plotted.count { it.sat.tracked }, "used" to plotted.count { it.sat.used },
            "behindTerrain" to plotted.count { GnssHealth.behind(it.sat.elevationDeg, it.sat.azimuthDeg, fn) },
            "secondBand" to plotted.count { it.secondBand }, "rotationDeg" to Math.round(rotation * 10) / 10.0,
            "orientation" to orientation.key, "followsHeading" to (orientation == SkyPlotOrientation.PHONE && heading != null),
            "horizonDrawn" to horizonDrawn, "sunDrawn" to sunDrawn, "night" to night,
            "check" to GnssCheck.state.code,
        )
    }
}

/**
 * T307: the satellites, now the Horizon view's Satellites layer (T308) in its scrolling area: the heading with the menu
 * ("Reset assistance data", behind a confirmation), the summary (satellites used and in view, the four best signals, the
 * systems and bands, accuracy, fix age, height from the GPS and the map, speed, course, time to first fix, the time),
 * "Check my GPS" and its verdict, the legend of the satellites' marks and the list, used first then by signal. The
 * GNSS and the check are plain fields, not Compose state: the parts that read them take [tick] (T307's verifier).
 */
@Composable
fun SatellitesSection(sats: List<io.github.jasondorjeshort.dystopo.gnss.Satellite>, plotted: List<PlotSat>, summary: GnssHealth.Summary, fn: ((Double) -> Double)?, pal: GnssPalette, units: io.github.jasondorjeshort.dystopo.render.ContourUnits, tick: Int,
    /** T327: a row's tap (the Panorama turns to that satellite; rows 48 dp tall), and the row [chosen] by its id; null: rows do nothing. */
    onRow: ((io.github.jasondorjeshort.dystopo.gnss.Satellite) -> Unit)? = null, chosen: String? = null,
    bump: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                BasicText(GnssText.SATELLITES, Modifier.weight(1f, fill = false), style = TextStyle(color = pal.ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold))
                HelpIcon("gnss.view")
            }
            Dots(pal) { GnssUi.menuOpen = !GnssUi.menuOpen }
        }
        if (GnssUi.menuOpen) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Button(GnssText.RESET, pal) { GnssUi.menuOpen = false; GnssUi.confirmReset = true }
        }
        if (GnssUi.confirmReset) Column(
            Modifier.fillMaxWidth().background(pal.card, RoundedCornerShape(10.dp)).border(1.dp, pal.line, RoundedCornerShape(10.dp)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BasicText(GnssText.RESET_TITLE, style = TextStyle(color = pal.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold))
            BasicText(GnssText.RESET_BODY, style = TextStyle(color = pal.ink, fontSize = 14.sp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(GnssText.RESET_CONFIRM, pal, filled = true) { GnssUi.confirmReset = false; GnssUi.reset { GnssUi.message = it } }
                Button(GnssText.CANCEL, pal) { GnssUi.confirmReset = false }
            }
        }
        GnssUi.message?.let { BasicText(it, style = TextStyle(color = pal.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)) }
        if (!Gnss.permitted()) BasicText(GnssText.NO_PERMISSION, style = TextStyle(color = pal.ink, fontSize = 14.sp))
        Summary(summary, pal, units, tick)
        CheckSection(pal, tick, bump)
        Legend(plotted.map { it.sat.constellation }.distinct(), pal)
        SatelliteList(sats, fn, pal, onRow, chosen)
    }
}

@Composable
private fun Summary(s: GnssHealth.Summary, pal: GnssPalette, units: io.github.jasondorjeshort.dystopo.render.ContourUnits, tick: Int) {
    @Suppress("UNUSED_VARIABLE") val t = tick // the fix's age, height and time are read again each second
    val f = Gnss.fix()
    val now = SystemClock.elapsedRealtimeNanos()
    val rows = listOf(
        GnssText.L_SATELLITES to if (s.inView == 0) GnssText.NONE_YET else GnssText.usedOf(s.used, s.inView),
        GnssText.L_SIGNAL to (s.best4Mean?.let { "${Math.round(it)} dB-Hz average (" + s.best4.joinToString(", ") { v -> Math.round(v).toString() } + ")" } ?: GnssText.DASH),
        GnssText.L_SYSTEMS to s.constellations.joinToString(", ") { it.label }.ifEmpty { GnssText.DASH },
        GnssText.L_BANDS to (s.bandLine ?: if (s.inView == 0) GnssText.DASH else GnssText.BAND_UNKNOWN),
        GnssText.L_ACCURACY to (f?.takeIf { it.hasAccuracy() }?.let { UnitFormat.distance(it.accuracy.toDouble(), units) } ?: GnssText.NO_FIX),
        GnssText.L_FIX_AGE to (f?.let { GnssText.seconds((now - it.elapsedRealtimeNanos) / 1_000_000) } ?: GnssText.DASH),
        GnssText.L_HEIGHT to GnssText.heights(
            f?.let { if (android.os.Build.VERSION.SDK_INT >= 34 && it.hasMslAltitude()) it.mslAltitudeMeters else if (it.hasAltitude()) it.altitude else null },
            Gnss.horizon?.profile?.groundM?.takeIf { !it.isNaN() }, units,
        ),
        GnssText.L_SPEED to (f?.takeIf { it.hasSpeed() }?.let { GnssText.speed(it.speed.toDouble(), units) } ?: GnssText.DASH),
        GnssText.L_COURSE to (f?.takeIf { it.hasBearing() && it.hasSpeed() && it.speed > 0.5f }?.let { "${Math.round(it.bearing)}° ${GnssText.compassPoint(it.bearing.toDouble())}" } ?: GnssText.DASH),
        GnssText.L_FIRST_FIX to (Gnss.ttffMs?.let { GnssText.seconds(it) } ?: GnssText.DASH),
        GnssText.L_TIME to (f?.let { java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(java.util.Date(it.time)) } ?: GnssText.DASH),
    )
    Column(
        Modifier.fillMaxWidth().background(pal.card, RoundedCornerShape(10.dp)).border(1.dp, pal.line, RoundedCornerShape(10.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // The label column is as wide as its widest label at the user's font scale, so no label breaks inside a word.
        val labelStyle = TextStyle(color = pal.muted, fontSize = 14.sp)
        val measurer = androidx.compose.ui.text.rememberTextMeasurer()
        val density = androidx.compose.ui.platform.LocalDensity.current
        val labelWidth = with(density) { rows.maxOf { measurer.measure(it.first, labelStyle).size.width }.toDp() } + 10.dp
        for ((label, value) in rows) Row(Modifier.fillMaxWidth()) {
            BasicText(label, Modifier.width(labelWidth), style = labelStyle)
            // Wrapped to its text, so a lone dash's node is the dash (the a11y part reads a node's own pixels).
            BasicText(value, Modifier.weight(1f).wrapContentWidth(Alignment.Start), style = TextStyle(color = pal.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium))
        }
    }
}

@Composable
private fun CheckSection(pal: GnssPalette, tick: Int, changed: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val t = tick // the check's state, countdown and verdict are read again each second
    val state = GnssCheck.state
    val last = GnssCheck.last
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state == GnssCheck.State.RUNNING) {
                BasicText(GnssText.checking(GnssCheck.secondsLeft), Modifier.weight(1f, fill = false), style = TextStyle(color = pal.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
                Button(GnssText.STOP, pal) { GnssCheck.stop(); changed() }
            } else {
                Button(if (last == null) GnssText.CHECK else GnssText.CHECK_AGAIN, pal, filled = true) { GnssCheck.start(); changed() }
            }
            HelpIcon("gnss.check")
        }
        if (state == GnssCheck.State.RUNNING) {
            Box(Modifier.fillMaxWidth().height(6.dp).background(pal.line, RoundedCornerShape(3.dp))) {
                Box(Modifier.fillMaxWidth(GnssCheck.elapsedS.toFloat() / GnssCheck.seconds.coerceAtLeast(1)).height(6.dp).background(pal.link, RoundedCornerShape(3.dp)))
            }
        } else if (last == null) {
            BasicText(GnssText.CHECK_HINT, style = TextStyle(color = pal.muted, fontSize = 13.sp))
        }
        if (state != GnssCheck.State.RUNNING && last != null) {
            val v = last.verdict
            if (v == null) {
                BasicText(last.stopped ?: GnssText.STOPPED, style = TextStyle(color = pal.ink, fontSize = 14.sp))
            } else Column(
                Modifier.fillMaxWidth().background(pal.card, RoundedCornerShape(10.dp)).border(2.dp, verdictColour(v.kind, pal), RoundedCornerShape(10.dp)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                BasicText(GnssText.headline(v.kind), style = TextStyle(color = pal.ink, fontSize = 18.sp, fontWeight = FontWeight.Bold))
                BasicText(GnssText.advice(v), style = TextStyle(color = pal.ink, fontSize = 14.sp))
                BasicText(GnssText.figures(v), style = TextStyle(color = pal.muted, fontSize = 13.sp))
            }
        }
    }
}

private fun verdictColour(k: GnssHealth.Kind, pal: GnssPalette): Color = when (k) {
    GnssHealth.Kind.HEALTHY -> Cn0Ramp.of(42.0)
    GnssHealth.Kind.WEAK -> Cn0Ramp.of(28.0)
    GnssHealth.Kind.FAULT -> pal.ink
}

@Composable
internal fun OrientationRow(o: SkyPlotOrientation, pal: GnssPalette) {
    @OptIn(ExperimentalLayoutApi::class)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
            BasicText("${io.github.jasondorjeshort.dystopo.app.settings.SettingsRow.SKY_PLOT.label}:", style = TextStyle(color = pal.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium))
        }
        for ((v, label) in SKY_PLOT_CHOICES) Choice(label, o == v, pal) {
            Thread({ SettingsRoutes(DystopoApp.instance).apply(SettingsRoutes.Parsed.Ok.of(AppSettings.SKY_PLOT, v)); io.github.jasondorjeshort.dystopo.app.SettingsUi.revision++ }, "dystopo-gnss-setting").start()
        }
        HelpIcon("gnss.skyPlot")
    }
}

/** The Sky plot setting's two values and their labels (the settings row shows the same). */
val SKY_PLOT_CHOICES: List<Pair<SkyPlotOrientation, String>> = listOf(
    SkyPlotOrientation.PHONE to GnssText.FOLLOW_PHONE,
    SkyPlotOrientation.NORTH to GnssText.NORTH_UP,
)

@Composable
private fun Legend(seen: List<Constellation>, pal: GnssPalette) {
    val systems = (listOf(Constellation.GPS) + seen).distinct().sortedBy { it.ordinal }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (c in systems) LegendItem(pal, c.label) { MarkIcon(markOf(c), null, pal.ink) }
        }
        BasicText(GnssText.LEGEND_FILL, style = TextStyle(color = pal.ink, fontSize = 13.sp))
        RampBar(pal)
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            LegendItem(pal, GnssText.LEGEND_USED) { MarkIcon(Mark.CIRCLE, Cn0Ramp.of(40.0), pal.ink, thick = true) }
            LegendItem(pal, GnssText.LEGEND_SECOND_BAND) { MarkIcon(Mark.CIRCLE, Cn0Ramp.of(40.0), pal.ink, ring = true) }
        }
    }
}

@Composable
internal fun LegendItem(pal: GnssPalette, label: String, icon: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        icon()
        BasicText(label, style = TextStyle(color = pal.ink, fontSize = 13.sp))
    }
}

@Composable
private fun RampBar(pal: GnssPalette) {
    Column(Modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(12.dp)) {
            val n = 40
            val w = size.width / n
            for (k in 0 until n) drawRect(Cn0Ramp.of(Cn0Ramp.RAMP_FROM + (Cn0Ramp.RAMP_TO - Cn0Ramp.RAMP_FROM) * k / (n - 1)), Offset(k * w, 0f), androidx.compose.ui.geometry.Size(w + 1, size.height))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            for (v in listOf(10, 20, 30, 40, 50)) BasicText(if (v == 50) "50+" else "$v", style = TextStyle(color = pal.muted, fontSize = 12.sp))
        }
    }
}

@Composable
private fun SatelliteList(sats: List<io.github.jasondorjeshort.dystopo.gnss.Satellite>, horizon: ((Double) -> Double)?, pal: GnssPalette, onRow: ((io.github.jasondorjeshort.dystopo.gnss.Satellite) -> Unit)? = null, chosen: String? = null) {
    val rows = GnssHealth.listOrder(sats.filter { it.tracked })
    if (rows.isEmpty()) return
    // At large text the five columns do not fit: each satellite takes two lines, its place and band under its name.
    val wide = androidx.compose.ui.platform.LocalDensity.current.fontScale <= 1.3f
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Head(GnssText.H_SATELLITE, Modifier.width(if (wide) 104.dp else 150.dp), pal)
            Head(GnssText.H_SIGNAL, Modifier.weight(1f), pal)
            if (wide) {
                Head(GnssText.H_ELEVATION, Modifier.width(42.dp), pal)
                Head(GnssText.H_AZIMUTH, Modifier.width(46.dp), pal)
                Head(GnssText.H_BAND, Modifier.width(36.dp), pal)
            }
        }
        for (s in rows) {
            val behind = GnssHealth.behind(s.elevationDeg, s.azimuthDeg, horizon)
            val tap = onRow?.let { f -> Modifier.heightIn(min = 48.dp).semantics { this.selected = s.id == chosen }.clickable(role = Role.Button) { f(s) } } ?: Modifier // T327
            Row(Modifier.fillMaxWidth().heightIn(min = 28.dp).then(tap), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.width(if (wide) 104.dp else 150.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    MarkIcon(markOf(s.constellation), Cn0Ramp.of(s.cn0), pal.ink, thick = s.used, size = 20.dp)
                    BasicText("${s.constellation.label} ${s.svid}", style = TextStyle(color = pal.ink, fontSize = 13.sp, fontWeight = if (s.used) FontWeight.Bold else FontWeight.Normal))
                }
                Box(Modifier.weight(1f).padding(end = 6.dp)) {
                    val share = (s.cn0 / Cn0Ramp.RAMP_TO).coerceIn(0.08, 1.0).toFloat()
                    Box(Modifier.fillMaxWidth(share).heightIn(min = 20.dp).background(Cn0Ramp.of(s.cn0), RoundedCornerShape(3.dp)), contentAlignment = Alignment.CenterStart) {
                        BasicText(String.format(Locale.US, "%.1f", s.cn0), Modifier.padding(start = 4.dp), style = TextStyle(color = Cn0Ramp.textOn(s.cn0), fontSize = 12.sp, fontWeight = FontWeight.Bold))
                    }
                }
                if (wide) {
                    BasicText("${Math.round(s.elevationDeg)}°", Modifier.width(42.dp), style = TextStyle(color = if (behind) pal.muted else pal.ink, fontSize = 13.sp))
                    BasicText("${Math.round(s.azimuthDeg)}°", Modifier.width(46.dp), style = TextStyle(color = pal.ink, fontSize = 13.sp))
                    BasicText(s.band?.label ?: GnssText.DASH, Modifier.width(36.dp), style = TextStyle(color = pal.ink, fontSize = 13.sp))
                }
            }
            val flags = (if (wide) emptyList() else listOf(GnssText.place(s.elevationDeg, s.azimuthDeg), s.band?.label ?: GnssText.BAND_UNKNOWN)) +
                listOfNotNull(GnssText.USED.takeIf { s.used }, GnssText.EPHEMERIS.takeIf { s.ephemeris }, GnssText.BEHIND.takeIf { behind })
            if (flags.isNotEmpty()) BasicText(flags.joinToString(" · "), Modifier.padding(start = 28.dp), style = TextStyle(color = pal.muted, fontSize = 12.sp))
        }
    }
}

@Composable
private fun Head(text: String, modifier: Modifier, pal: GnssPalette) =
    BasicText(text, modifier, style = TextStyle(color = pal.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold))

@Composable
internal fun Button(label: String, pal: GnssPalette, filled: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            label,
            style = TextStyle(color = if (filled) pal.paper else pal.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
            modifier = Modifier.background(if (filled) pal.ink else pal.card, RoundedCornerShape(16.dp)).border(1.dp, if (filled) pal.ink else pal.line, RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 9.dp),
        )
    }
}

@Composable
internal fun Choice(label: String, selected: Boolean, pal: GnssPalette, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = 48.dp).semantics { this.selected = selected }.clickable(role = Role.RadioButton, onClick = onClick), contentAlignment = Alignment.Center) {
        BasicText(
            label,
            style = TextStyle(color = if (selected) pal.paper else pal.ink, fontSize = 14.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium),
            modifier = Modifier.background(if (selected) pal.ink else pal.card, RoundedCornerShape(16.dp)).border(1.dp, if (selected) pal.ink else pal.line, RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

@Composable
internal fun Dots(pal: GnssPalette, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = 48.dp).width(48.dp).semantics { contentDescription = GnssText.MORE }.clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.width(6.dp).height(22.dp)) {
            for (k in -1..1) drawCircle(pal.ink, 2.2.dp.toPx(), Offset(size.width / 2, size.height / 2 + k * 7.dp.toPx()))
        }
    }
}

/** T307: the satellites layer's help pop-ups (one paragraph each, no app named); T308: in the Horizon view. */
internal val GNSS_HELP: List<HelpText> = run {
    fun at(row: String) = "${Screens.SETTINGS} › ${SettingsGroup.NAVIGATION.title} › ${GnssText.TITLE} › $row"
    val show: () -> Unit = { GnssUi.open() }
    listOf(
        HelpText("gnss.view", GnssText.SATELLITES, at(GnssText.SATELLITES), show = show) {
            "The satellites your phone hears: each signal's strength in dB-Hz (above 35 is strong, under 25 weak), " +
                "which ones the fix uses, and the systems and bands the phone receives. The time to first fix counts from the app's start."
        },
        HelpText("gnss.check", GnssText.CHECK, at(GnssText.CHECK), show = show) {
            "For two minutes the phone listens to every satellite. Strong signals from four or more mean a healthy GPS; weaker " +
                "ones a blocked sky. Many satellites heard but all very weak may be a hardware fault. Satellites behind the terrain do not count."
        },
        HelpText("gnss.skyPlot", "Sky plot", at("Sky plot"), show = show) {
            "The circle is the sky: its edge the horizon, its centre straight overhead. The shaded band is the land's skyline " +
                "from the elevation data. Follow the phone turns the plot with the compass; North up keeps north at the top."
        },
    )
}
