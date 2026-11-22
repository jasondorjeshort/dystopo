package io.github.jasondorjeshort.dystopo.app.panorama

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import io.github.jasondorjeshort.dystopo.app.gnss.GnssPalette
import io.github.jasondorjeshort.dystopo.app.gnss.PlotSat
import io.github.jasondorjeshort.dystopo.app.gnss.satelliteMark
import io.github.jasondorjeshort.dystopo.app.gnss.sunMark
import io.github.jasondorjeshort.dystopo.app.horizon.FlightMark
import io.github.jasondorjeshort.dystopo.app.horizon.HorizonData
import io.github.jasondorjeshort.dystopo.app.horizon.HorizonText
import io.github.jasondorjeshort.dystopo.app.horizon.drawFlightLayer
import io.github.jasondorjeshort.dystopo.app.horizon.sunColour
import io.github.jasondorjeshort.dystopo.horizon.HorizonFlights
import io.github.jasondorjeshort.dystopo.horizon.HorizonLabels
import io.github.jasondorjeshort.dystopo.horizon.LensView
import io.github.jasondorjeshort.dystopo.horizon.PanoramaScene
import io.github.jasondorjeshort.dystopo.horizon.RimBox
import io.github.jasondorjeshort.dystopo.horizon.RimPt
import io.github.jasondorjeshort.dystopo.horizon.SkyFlight
import io.github.jasondorjeshort.dystopo.nav.Sun
import kotlin.math.abs

/**
 * T327: what the Panorama's other layers draw through the lens, as the Horizon view's round plot draws them (wave 45,
 * T327 (1)): [sunDay] the day's path and hours and [sunNow] the sun now (the Sun layer; null when off), [sats] the
 * satellites (the Satellites layer; empty when off), [flights] (the Flights layer; null when off) with [pickedFlight] the
 * card's. Nothing sits on a rim here: a mark below the level line is drawn where it falls.
 */
class LensLayers(
    val sunDay: HorizonData.SunDay?,
    val sunNow: Sun.Position?,
    val sats: List<PlotSat>,
    val satellitesOn: Boolean,
    val flights: HorizonData.Flights?,
    val pickedFlight: String?,
    val night: Boolean,
) {
    val empty: Boolean get() = sunDay == null && sunNow == null && !satellitesOn && flights == null
}

/** A satellite or the sun as last drawn on the lens (the plot's taps read them; px in the plot). */
class LensMark(val kind: TargetKind, val id: String, val name: String, val at: Offset)

/** A flight's name as last placed, with its mark, so a gesture keeps its offset (choice g, as the peaks'). */
class HeldFlightName(val hex: String, val placed: HorizonLabels.Placed, val markX: Double, val markY: Double)

/** Where ([elDeg], [azDeg]) falls on [this] lens (px), on the view or past its edge; null behind the viewer or too far off to draw. */
internal fun LensView.at(elDeg: Double, azDeg: Double): Offset? =
    project(elDeg, azDeg)?.takeIf { abs(it.x - cx) < FAR * width && abs(it.y - cy) < FAR * width }?.let { Offset(it.x.toFloat(), it.y.toFloat()) }

/** Points this many plot widths from the centre are not drawn (a direction nearly square to the lens projects without end). */
private const val FAR = 20.0

/**
 * T327: the Sun layer on the lens: the day's path solid in view and dashed behind the terrain (runs as `HorizonScreen`'s
 * round plot draws them), the hour dots and their labels; the sun now while it is up. Returns what it drew, for
 * `GET /panorama` `layers.sun`, and the sun's mark for the taps.
 */
internal fun DrawScope.drawSunOnLens(lens: LensView, day: HorizonData.SunDay?, now: Sun.Position?, pal: GnssPalette, night: Boolean, marks: MutableList<LensMark>): Map<String, Any?> {
    var samplesOnView = 0
    var hoursOnView = 0
    day?.let { d ->
        val path = d.sun.path
        val colour = sunColour(night)
        val solid = Stroke(2.5f * density, cap = StrokeCap.Round)
        val dashed = Stroke(1.5f * density, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f * density, 4f * density)))
        val pts = path.map { lens.at(it.altitudeDeg, it.azimuthDeg) }
        samplesOnView = pts.count { it != null && lens.onView(RimPt(it.x.toDouble(), it.y.toDouble())) }
        // Runs of samples on the same side of the skyline, each reaching the first sample of the next; broken behind the viewer.
        var i = 0
        while (i < path.size - 1) {
            val clear = path[i].clear
            var j = i
            while (j < path.size - 1 && path[j].clear == clear) j++
            val line = Path()
            var open = false
            for (k in i..j) {
                val p = pts[k]
                if (p == null) { open = false; continue }
                if (!open) line.moveTo(p.x, p.y) else line.lineTo(p.x, p.y)
                open = true
            }
            drawPath(line, if (clear) colour else pal.muted, style = if (clear) solid else dashed)
            i = j
        }
        val label = android.graphics.Paint().apply { isAntiAlias = true; textSize = 10f * density; color = pal.muted.toArgb(); textAlign = android.graphics.Paint.Align.CENTER }
        val cal = java.util.Calendar.getInstance()
        for (m in d.marks) {
            val p = lens.at(m.altitudeDeg, m.azimuthDeg) ?: continue
            if (lens.onView(RimPt(p.x.toDouble(), p.y.toDouble()))) hoursOnView++
            drawCircle(if (m.clear) colour else pal.muted, 3f * density, p)
            cal.timeInMillis = m.timeMs
            drawContext.canvas.nativeCanvas.drawText(cal.get(java.util.Calendar.HOUR_OF_DAY).toString(), p.x, p.y - 6f * density, label)
        }
    }
    var nowAt: Offset? = null
    if (now != null && HorizonText.sunUp(now.apparentAltitudeDeg)) {
        lens.at(now.apparentAltitudeDeg, now.azimuthDeg)?.let { p ->
            sunMark(p, pal)
            nowAt = p
            marks += LensMark(TargetKind.SUN, PanoramaTargets.SUN_ID, PanoramaTargets.SUN_NAME, p)
        }
    }
    val on = nowAt?.let { lens.onView(RimPt(it.x.toDouble(), it.y.toDouble())) } ?: false
    return linkedMapOf(
        "pathSamples" to (day?.sun?.path?.size ?: 0), "pathOnView" to samplesOnView, "hourMarks" to (day?.marks?.size ?: 0), "hoursOnView" to hoursOnView,
        "nowAltDeg" to now?.let { Math.round(it.apparentAltitudeDeg * 10) / 10.0 }, "nowAzDeg" to now?.let { Math.round(it.azimuthDeg * 10) / 10.0 },
        "nowDrawn" to on, "nowPx" to nowAt?.takeIf { on }?.let { listOf(Math.round(it.x), Math.round(it.y)) },
    )
}

/** T327: the Satellites layer on the lens: each mark as the round plot draws it ([satelliteMark]), where it falls. */
internal fun DrawScope.drawSatellitesOnLens(lens: LensView, sats: List<PlotSat>, pal: GnssPalette, marks: MutableList<LensMark>): Map<String, Any?> {
    val svid = android.graphics.Paint().apply { isAntiAlias = true; textSize = 10.5f * density; color = pal.ink.toArgb() }
    val drawn = ArrayList<String>()
    for (ps in sats) {
        val p = lens.at(ps.sat.elevationDeg, ps.sat.azimuthDeg) ?: continue
        satelliteMark(ps, p, pal, svid)
        if (lens.onView(RimPt(p.x.toDouble(), p.y.toDouble()))) {
            drawn += ps.sat.id
            marks += LensMark(TargetKind.SATELLITE, ps.sat.id, HorizonText.satelliteName(ps.sat), p)
        }
    }
    return linkedMapOf("listed" to sats.size, "onView" to drawn.size, "ids" to drawn)
}

/**
 * T327: the Flights layer's names on the lens, the second pass of `HorizonLabels.placeLayers` against the scene's peak
 * names: kept off the scene's blocked boxes (the tape, the edges, the zoom buttons' corner), every peak name's box and mark
 * and its leader line, the closest not forced, a picked flight first; so no peak name moves for a flight's. During a
 * gesture ([held] given) each keeps its offset from its dot, as the peaks' do.
 */
internal fun placeFlightNames(scene: PanoramaScene, list: List<SkyFlight>, picked: String?, text: io.github.jasondorjeshort.dystopo.horizon.PanoramaText, held: List<HeldFlightName>?): List<HeldFlightName> {
    val lens = scene.lens
    if (held != null) return held.mapNotNull { h ->
        val f = list.firstOrNull { it.hex == h.hex } ?: return@mapNotNull null
        val at = lens.mark(f.elevationDeg, f.bearingDeg) ?: return@mapNotNull null
        val dx = at.x - h.markX
        val dy = at.y - h.markY
        val p = h.placed
        fun RimBox.moved() = RimBox(left + dx, top + dy, right + dx, bottom + dy)
        HeldFlightName(h.hex, HorizonLabels.Placed(p.scored, p.x + dx, p.y + dy, p.box.moved(), p.gapDeg, p.leader?.map { RimPt(it.x + dx, it.y + dy) }, p.mark.moved()), at.x, at.y)
    }
    val ranked = HorizonLabels.rank(HorizonFlights.labels(list))
    val first = ranked.firstOrNull { (it.label.ref as SkyFlight).hex == picked }?.label
    val taken = scene.blocked + scene.names.flatMap { listOf(it.placed.box, it.placed.mark) }
    val lines = scene.names.mapNotNull { it.placed.leader }
    return HorizonLabels.place(lens, ranked, text.width, text.ascent, text.descent, taken, lines, first, forceClosest = false, keepIn = scene.keepIn).map { p ->
        val at = lens.mark(p.label.angleDeg, p.label.bearingDeg)!!
        HeldFlightName((p.label.ref as SkyFlight).hex, p, at.x, at.y)
    }
}

/** T327: the Flights layer on the lens: tails, dots, rings and the names [named] (`HorizonScreen`'s drawing through the lens). */
internal fun DrawScope.drawFlightsOnLens(lens: LensView, f: HorizonData.Flights, named: List<HeldFlightName>, picked: String?, text: android.graphics.Paint, pal: GnssPalette): Pair<List<FlightMark>, Map<String, Any?>> {
    text.textAlign = android.graphics.Paint.Align.CENTER
    val marks = drawFlightLayer(f.list, named.map { it.placed }, picked, text, pal) { el, az -> lens.at(el, az) }
    text.textAlign = android.graphics.Paint.Align.LEFT
    val onView = marks.filter { lens.onView(RimPt(it.dot.x.toDouble(), it.dot.y.toDouble())) }
    return marks to linkedMapOf(
        "listed" to f.list.size, "onView" to onView.map { it.hex }, "names" to named.map { it.placed.label.name },
        "hollow" to onView.filter { it.hollow }.map { it.hex }, "emergency" to onView.filter { it.emergency }.map { it.hex },
        "tailPoints" to onView.sumOf { it.tailPoints }, "credit" to f.credit, "picked" to picked,
    )
}
