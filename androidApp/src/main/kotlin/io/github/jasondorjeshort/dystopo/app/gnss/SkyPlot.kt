package io.github.jasondorjeshort.dystopo.app.gnss

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.jasondorjeshort.dystopo.dem.HorizonProfile
import io.github.jasondorjeshort.dystopo.gnss.Constellation
import io.github.jasondorjeshort.dystopo.gnss.Satellite
import io.github.jasondorjeshort.dystopo.maplibre.NightColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * T307: the GPS view's colours. Day: the map style's paper and ink; night: the same tokens through the style's own night
 * transform ([NightColors], as `MapLibreStyle` turns the map), so the view matches the map it was opened from. The
 * horizon band is the style's terrain token (its hillshade accent, `#6b5a4d`) over the paper.
 */
class GnssPalette(val night: Boolean) {
    private fun tok(hex: Long): Color {
        if (!night) return Color(hex or 0xFF000000)
        val v = hex.toInt()
        val (r, g, b) = NightColors.of((v shr 16) and 0xFF, (v shr 8) and 0xFF, v and 0xFF)
        return Color(r, g, b)
    }

    /** The style's paper (`#f6f1e4`). */
    val paper = tok(0xf6f1e4)
    val card = tok(0xfffcf5)
    val ink = tok(0x2b2420)
    val muted = tok(0x5e4f42)
    val line = tok(0xb9a88f)
    val link = if (night) Color(0xFF8CC4EC) else Color(0xFF1F6AA5)
    val band = tok(0x6b5a4d).copy(alpha = if (night) 0.55f else 0.38f)
    val sky = tok(0xfffdf7)
}

/**
 * The C/N0 colour ramp: a perceptually even, colour-blind-safe sequential scale (dark violet through teal and green to
 * yellow; not red to green), one stop every 10 dB-Hz from [RAMP_FROM]. The legend shows it in dB-Hz and the list carries
 * the number, so colour is never the only cue.
 */
object Cn0Ramp {
    const val RAMP_FROM = 10.0
    const val RAMP_TO = 50.0
    private val STOPS = listOf(0xFF440154, 0xFF3B528B, 0xFF21918C, 0xFF5EC962, 0xFFFDE725).map { Color(it) }

    fun of(cn0: Double): Color {
        val t = ((cn0 - RAMP_FROM) / (RAMP_TO - RAMP_FROM)).coerceIn(0.0, 1.0) * (STOPS.size - 1)
        val i = t.toInt().coerceAtMost(STOPS.size - 2)
        val f = (t - i).toFloat()
        val a = STOPS[i]
        val b = STOPS[i + 1]
        return Color(a.red + (b.red - a.red) * f, a.green + (b.green - a.green) * f, a.blue + (b.blue - a.blue) * f)
    }

    /** Text drawn on [of]'s colour: dark on the light end, white on the dark. */
    fun textOn(cn0: Double): Color = if (cn0 >= 34) Color(0xFF1A1A1A) else Color.White
}

/** The shape each system is drawn with (circle GPS, square GLONASS, triangle Galileo, pentagon BeiDou, hexagon QZSS, oval NavIC, diamond SBAS). */
enum class Mark { CIRCLE, SQUARE, TRIANGLE, PENTAGON, HEXAGON, OVAL, DIAMOND, STAR }

fun markOf(c: Constellation): Mark = when (c) {
    Constellation.GPS -> Mark.CIRCLE
    Constellation.GLONASS -> Mark.SQUARE
    Constellation.GALILEO -> Mark.TRIANGLE
    Constellation.BEIDOU -> Mark.PENTAGON
    Constellation.QZSS -> Mark.HEXAGON
    Constellation.IRNSS -> Mark.OVAL
    Constellation.SBAS -> Mark.DIAMOND
    Constellation.UNKNOWN -> Mark.STAR
}

/** The outline of [mark] centred at [c], [r] its half-size (px). */
fun markPath(mark: Mark, c: Offset, r: Float): Path = Path().apply {
    fun poly(n: Int, start: Double, scale: Float = r) {
        for (k in 0 until n) {
            val a = start + 2 * PI * k / n
            val p = Offset(c.x + scale * sin(a).toFloat(), c.y - scale * cos(a).toFloat())
            if (k == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
        }
        close()
    }
    when (mark) {
        Mark.CIRCLE -> addOval(androidx.compose.ui.geometry.Rect(c, r))
        Mark.SQUARE -> addRect(androidx.compose.ui.geometry.Rect(c.x - r * 0.85f, c.y - r * 0.85f, c.x + r * 0.85f, c.y + r * 0.85f))
        Mark.TRIANGLE -> poly(3, 0.0, r * 1.15f)
        Mark.PENTAGON -> poly(5, 0.0, r * 1.05f)
        Mark.HEXAGON -> poly(6, PI / 6)
        Mark.OVAL -> addOval(androidx.compose.ui.geometry.Rect(c.x - r * 1.2f, c.y - r * 0.75f, c.x + r * 1.2f, c.y + r * 0.75f))
        Mark.DIAMOND -> poly(4, 0.0, r * 1.15f)
        Mark.STAR -> {
            for (k in 0 until 10) {
                val a = 2 * PI * k / 10
                val s = if (k % 2 == 0) r * 1.15f else r * 0.5f
                val p = Offset(c.x + s * sin(a).toFloat(), c.y - s * cos(a).toFloat())
                if (k == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
            }
            close()
        }
    }
}

/** One satellite as the plot draws it: its strongest signal, and whether it is heard on more than one band. */
class PlotSat(val sat: Satellite, val secondBand: Boolean)

fun plotSats(sats: List<Satellite>): List<PlotSat> = sats.groupBy { it.id }.values.map { s ->
    PlotSat(s.maxBy { it.cn0 }, s.mapNotNull { it.band }.distinct().size > 1)
}.sortedWith(compareBy<PlotSat> { it.sat.cn0 }) // the strongest drawn last, on top

/**
 * T307: the sky plot: the sky as a circle (the rim the horizon, the centre straight up, rings at 30 and 60 degrees,
 * labelled), turned by [rotationDeg] (the phone's heading in "Follow the phone", 0 north up) with N, E, S and W on the rim
 * and north always marked; the terrain's skyline ([horizon]) as a band from the rim inwards; each satellite a [markOf]
 * shape filled by [Cn0Ramp], a thick outline when used in the fix, its number beside it, a ring when heard on a second
 * band; the sun where it stands when above the horizon ([sunAltDeg], [sunAzDeg]).
 */
@Composable
fun SkyPlot(
    sats: List<PlotSat>, horizon: HorizonProfile?, rotationDeg: Double, sunAzDeg: Double?, sunAltDeg: Double?,
    pal: GnssPalette, description: String, modifier: Modifier = Modifier,
) {
    Canvas(modifier.semantics { contentDescription = description }) {
        val size = minOf(size.width, size.height)
        val c = Offset(this.size.width / 2, this.size.height / 2)
        val labelPx = 15.dp.toPx()
        val radius = size / 2 - labelPx * 1.6f
        fun at(elDeg: Double, azDeg: Double): Offset {
            val r = radius * ((90 - elDeg.coerceIn(0.0, 90.0)) / 90).toFloat()
            val a = (azDeg - rotationDeg) * PI / 180
            return Offset(c.x + r * sin(a).toFloat(), c.y - r * cos(a).toFloat())
        }
        drawCircle(pal.sky, radius, c)
        // The terrain's skyline: the band between the rim and the horizon's elevation at each bearing.
        if (horizon != null) {
            val band = Path().apply {
                fillType = PathFillType.EvenOdd
                addOval(androidx.compose.ui.geometry.Rect(c, radius))
                for (k in 0..360) {
                    val p = at(horizon.angleAt(k.toDouble()).coerceAtLeast(0.0), k.toDouble())
                    if (k == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
                }
                close()
            }
            drawPath(band, pal.band, style = Fill)
        }
        // Rings at 0 (the rim), 30 and 60 degrees, and the cross through the centre.
        for (el in listOf(0.0, 30.0, 60.0)) drawCircle(pal.line, radius * ((90 - el) / 90).toFloat(), c, style = Stroke(if (el == 0.0) 1.6.dp.toPx() else 1.dp.toPx()))
        for (az in listOf(0.0, 90.0)) drawLine(pal.line, at(0.0, az), at(0.0, az + 180), 1.dp.toPx())
        val text = android.graphics.Paint().apply { isAntiAlias = true; textSize = 12.dp.toPx(); color = pal.muted.toArgb() }
        for ((el, label) in listOf(0.0 to "0°", 30.0 to "30°", 60.0 to "60°")) {
            val p = at(el, rotationDeg + 45)
            drawContext.canvas.nativeCanvas.drawText(label, p.x + 3.dp.toPx(), p.y - 3.dp.toPx(), text)
        }
        // N, E, S, W outside the rim; north bold with a mark on the rim.
        val points = android.graphics.Paint().apply { isAntiAlias = true; textSize = labelPx; color = pal.ink.toArgb(); textAlign = android.graphics.Paint.Align.CENTER }
        for ((az, label) in listOf(0.0 to "N", 90.0 to "E", 180.0 to "S", 270.0 to "W")) {
            val a = (az - rotationDeg) * PI / 180
            val r = radius + labelPx * 0.85f
            val p = Offset(c.x + r * sin(a).toFloat(), c.y - r * cos(a).toFloat())
            points.isFakeBoldText = az == 0.0
            points.color = (if (az == 0.0) pal.link else pal.ink).toArgb()
            drawContext.canvas.nativeCanvas.drawText(label, p.x, p.y + labelPx * 0.36f, points)
        }
        northMark(c, radius, rotationDeg, pal.link)
        sun(sunAzDeg, sunAltDeg, ::at, pal)
        // The satellites.
        val r = 8.dp.toPx()
        val svid = android.graphics.Paint().apply { isAntiAlias = true; textSize = 10.5f.dp.toPx(); color = pal.ink.toArgb() }
        for (ps in sats) {
            val s = ps.sat
            val p = at(s.elevationDeg, s.azimuthDeg)
            val path = markPath(markOf(s.constellation), p, r)
            if (s.tracked) drawPath(path, Cn0Ramp.of(s.cn0))
            drawPath(path, pal.ink, style = Stroke(if (s.used) 3.dp.toPx() else 1.dp.toPx()))
            if (ps.secondBand) drawCircle(pal.ink, r + 4.dp.toPx(), p, style = Stroke(1.dp.toPx()))
            drawContext.canvas.nativeCanvas.drawText(s.svid.toString(), p.x + r + 3.dp.toPx(), p.y - r * 0.4f, svid)
        }
    }
}

private fun DrawScope.northMark(c: Offset, radius: Float, rotationDeg: Double, color: Color) {
    val a = -rotationDeg * PI / 180
    val tip = Offset(c.x + (radius + 2.dp.toPx()) * sin(a).toFloat(), c.y - (radius + 2.dp.toPx()) * cos(a).toFloat())
    val back = radius - 9.dp.toPx()
    val w = 5.dp.toPx()
    val base = Offset(c.x + back * sin(a).toFloat(), c.y - back * cos(a).toFloat())
    val side = Offset(cos(a).toFloat() * w, sin(a).toFloat() * w)
    drawPath(Path().apply { moveTo(tip.x, tip.y); lineTo(base.x + side.x, base.y + side.y); lineTo(base.x - side.x, base.y - side.y); close() }, color)
}

private fun DrawScope.sun(azDeg: Double?, altDeg: Double?, at: (Double, Double) -> Offset, pal: GnssPalette) {
    if (azDeg == null || altDeg == null || altDeg < -0.5) return
    val p = at(altDeg.coerceAtLeast(0.0), azDeg)
    val r = 6.dp.toPx()
    drawCircle(pal.card, r, p)
    drawCircle(pal.ink, r, p, style = Stroke(1.5.dp.toPx()))
    for (k in 0 until 8) {
        val a = 2 * PI * k / 8
        val d = Offset(sin(a).toFloat(), -cos(a).toFloat())
        drawLine(pal.ink, Offset(p.x + d.x * (r + 2.dp.toPx()), p.y + d.y * (r + 2.dp.toPx())), Offset(p.x + d.x * (r + 5.dp.toPx()), p.y + d.y * (r + 5.dp.toPx())), 1.5.dp.toPx())
    }
}

/** A legend mark: [mark] in [fill] with an outline as the plot draws it. */
@Composable
fun MarkIcon(mark: Mark, fill: Color?, outline: Color, thick: Boolean = false, ring: Boolean = false, sunIcon: Boolean = false, band: Color? = null, size: Dp = 22.dp) {
    Box(Modifier.size(size)) {
        Canvas(Modifier.size(size)) {
            val c = Offset(this.size.width / 2, this.size.height / 2)
            if (band != null) {
                drawRect(band)
                return@Canvas
            }
            if (sunIcon) {
                sun(0.0, 90.0, { _, _ -> c }, paletteFor(outline))
                return@Canvas
            }
            val r = if (ring) 5.dp.toPx() else 7.dp.toPx()
            val path = markPath(mark, c, r)
            fill?.let { drawPath(path, it) }
            drawPath(path, outline, style = Stroke(if (thick) 3.dp.toPx() else 1.dp.toPx()))
            if (ring) drawCircle(outline, r + 4.dp.toPx(), c, style = Stroke(1.dp.toPx()))
        }
    }
}

private fun paletteFor(outline: Color): GnssPalette =
    if (outline.luminance() > 0.5f) GnssPalette(true) else GnssPalette(false)

private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue
