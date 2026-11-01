package io.github.jasondorjeshort.dystopo.render

import io.github.jasondorjeshort.dystopo.model.Activity
import io.github.jasondorjeshort.dystopo.model.Track
import io.github.jasondorjeshort.dystopo.model.TrackPoint
import io.github.jasondorjeshort.dystopo.model.TrackSegment
import io.github.jasondorjeshort.dystopo.routing.PaceFit
import io.github.jasondorjeshort.dystopo.routing.TravelMode
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T198: the trip report's figures, the time against the pace, the stops along the profile, and the page it draws. */
class TripReportTest {
    private val lat0 = 45.0
    private val lon0 = 0.5
    private val t0 = 1_767_268_800_000L

    /**
     * A synthetic out-and-back: 1500 m north climbing 200 m at 1 m/s, a 10-minute stop at the top, then back down at
     * 1.25 m/s; a fix every 5 s with a 5 m accuracy and the receiver's speed.
     */
    private fun walk(climbMps: Double = 1.0): Track {
        val dLat = 1.0 / 111_320
        val pts = ArrayList<TrackPoint>()
        var t = t0
        fun add(northM: Double, ele: Double, speed: Double) {
            pts += TrackPoint(lat0 + northM * dLat, lon0 + 0.00001 * (pts.size % 3), ele, t, accuracyM = 5.0, speedMps = speed)
            t += 5_000
        }
        val up = (1500 / (climbMps * 5)).toInt()
        for (i in 0..up) add(1500.0 * i / up, 1000.0 + 200.0 * i / up, climbMps)
        repeat(120) { add(1500.0, 1200.0, 0.0) } // ten minutes standing
        val down = (1500 / (1.25 * 5)).toInt()
        for (i in 1..down) add(1500.0 * (1 - i.toDouble() / down), 1200.0 - 200.0 * i / down, 1.25)
        return Track("t1", "Synthetic ridge", listOf(TrackSegment(pts)), activity = Activity.FOOT)
    }

    @Test
    fun figuresComeInTheSurveysOrder() {
        val s = TripReport.summarize(walk(), ContourUnits.METERS)
        assertEquals(listOf("distance", "movingTime", "totalTime", "ascent", "descent", "highest", "lowest"), s.figures.map { it.key })
        assertEquals(listOf(true, true, false, true, false, false, false), s.figures.map { it.large })
        val f = s.figures.associateBy { it.key }
        assertEquals(3000.0, f.getValue("distance").raw, 60.0)
        assertEquals(1200.0, f.getValue("highest").raw, 5.0)
        assertEquals(1000.0, f.getValue("lowest").raw, 5.0)
        assertEquals(200.0, f.getValue("ascent").raw, 15.0)
        // Moving time leaves the ten minutes out; the total keeps them.
        val total = f.getValue("totalTime").raw
        val moving = f.getValue("movingTime").raw
        assertTrue(total - moving in 500.0..700.0, "total $total moving $moving")
        assertEquals("Distance", f.getValue("distance").label)
        assertEquals("Highest point", f.getValue("highest").label)
    }

    @Test
    fun theStopSitsAtTheTopOfTheProfile() {
        val s = TripReport.summarize(walk(), ContourUnits.METERS)
        assertEquals(1, s.stops.size, "stops ${s.stops}")
        assertEquals(10.0, s.stops[0].durationS / 60, 1.0)
        val total = s.profile.last().distanceM
        assertEquals(0.5, s.stopAtM[0] / total, 0.03)
    }

    @Test
    fun theTimeAgainstThePaceSaysFasterOrSlower() {
        // The standard estimate for a hike without a fit of its own.
        val std = TripReport.summarize(walk(), ContourUnits.METERS)
        assertEquals("standard", std.paceReason)
        assertNotNull(std.pace)
        assertTrue(std.pace!!.line.startsWith("Time against the standard estimate: "), std.pace!!.line)
        // A fit at twice Tobler's speed: the walk is slower than it.
        val quick = PaceFit.TOBLER.copy(peakMps = PaceFit.TOBLER.peakMps * 2, tracks = 6, metres = 30_000.0)
        val s = TripReport.summarize(walk(), ContourUnits.METERS, mapOf(TravelMode.HIKE to quick))
        assertEquals("yours", s.paceReason)
        val p = assertNotNull(s.pace)
        assertTrue(p.deltaS > 0 && p.line.startsWith("Time against your pace: ") && p.line.endsWith(" slower"), p.line)
        assertEquals(listOf(p.line), s.extraLines)
        // A fit at half Tobler's speed: faster.
        val slow = quick.copy(peakMps = PaceFit.TOBLER.peakMps / 2)
        assertTrue(TripReport.summarize(walk(), ContourUnits.METERS, mapOf(TravelMode.HIKE to slow)).pace!!.line.endsWith(" faster"))
    }

    @Test
    fun aTrackWithoutTimesHasNoTimesAndNoPaceLine() {
        val t = walk().let { w -> w.copy(segments = w.segments.map { s -> TrackSegment(s.points.map { it.copy(time = null, speedMps = null) }) }) }
        val s = TripReport.summarize(t, ContourUnits.FEET)
        assertEquals(listOf("distance", "ascent", "descent", "highest", "lowest"), s.figures.map { it.key })
        assertNull(s.pace)
        assertEquals("noTimes", s.paceReason)
        assertTrue(s.figures.first().value.endsWith(" mi"), s.figures.first().value)
    }

    @Test
    fun linesAndScales() {
        assertEquals("Time against your pace: 12 min faster", PaceAgainst(3000.0, 3720.0, 5000.0, true).line)
        assertEquals("Time against your pace: 1 h 05 min slower", PaceAgainst(7500.0, 3600.0, 5000.0, true).line)
        assertEquals("Time against the standard estimate: within a minute", PaceAgainst(3600.0, 3620.0, 5000.0, false).line)
        assertEquals("45 min", TripReport.duration(45 * 60.0))
        assertEquals("4 h 10 min", TripReport.duration(250 * 60.0))
        assertEquals(5000.0, TripReport.tidyScale(1200.0))
        assertEquals(10_000.0, TripReport.tidyScale(9_400.0))
        assertEquals(12_500.0, TripReport.tidyScale(10_001.0))
        assertEquals(25_000.0, TripReport.tidyScale(24_000.0))
        assertEquals(100_000.0, TripReport.tidyScale(76_000.0))
    }

    @Test
    fun thePageFitsTheTrackAndDrawsEveryFigure() {
        val s = TripReport.summarize(walk(), ContourUnits.METERS)
        val l = TripReport.plan(s, Paper.A4, landscape = false, dpi = 100.0)
        // The whole track inside the frame.
        for (seg in s.line) for (p in seg) {
            assertTrue(l.frameX(p.lon) in 0.0..l.frameWidthPx.toDouble() && l.frameY(p.lat) in 0.0..l.frameHeightPx.toDouble())
        }
        assertEquals(l.scale, TripReport.tidyScale(l.scale))
        val f = l.frameMm
        assertEquals(TripReport.MARGIN_MM, f.x)
        assertTrue(f.y + f.h + TripReport.GAP_MM + TripReport.DATA_MM + 2 + TripReport.FOOTER_MM <= 297.0 - TripReport.MARGIN_MM + 1e-9)
        val page = TripReportPage(l, s, "Synthetic ridge", "2026-01-01", "2026-01-02", "Synthetic terrain.", "Contour interval 10 metres")
        val c = Recorder()
        page.paint(c)
        val all = page.texts.joinToString("\n")
        var at = -1
        for (fig in s.figures) {
            val i = all.indexOf("${fig.label}: ${fig.value}")
            assertTrue(i > at, "'${fig.label}' out of order or missing:\n$all")
            at = i
        }
        for (item in listOf("Synthetic ridge", "Trip report, 2026-01-01", l.ratioText, "Elevation profile, stops marked in minutes",
            "Time against the standard estimate", "Printed 2026-01-02.", "Sources: Synthetic terrain.", "Contour interval 10 metres.", "EGM2008")) {
            assertTrue(item in all, "page lacks '$item':\n$all")
        }
        assertEquals(1, page.stopLabels)
        assertTrue(c.roundLines >= 3, "track casing, track and profile: ${c.roundLines}")
        val prof = assertNotNull(page.profileBox)
        val figs = assertNotNull(page.figuresBox)
        assertTrue(!prof.intersects(figs))
        assertTrue(prof.y > l.frameYPx + l.frameHeightPx && prof.maxY < l.pageHeightPx)
        // Nothing drawn off the page.
        for ((t, b) in c.texts) assertTrue(b.x >= 0 && b.maxX <= l.pageWidthPx && b.y >= 0 && b.maxY <= l.pageHeightPx, "'$t' at $b")
        assertEquals(0, c.clips)
    }

    @Test
    fun aLandscapePageStillHoldsTheBand() {
        val s = TripReport.summarize(walk(), ContourUnits.FEET)
        val l = TripReport.plan(s, Paper.LETTER, landscape = true, dpi = 100.0)
        assertTrue(l.frameMm.h > 80.0, "frame ${l.frameMm}")
        assertTrue(abs(l.pageWidthMm - 279.4) < 1e-6)
        // The fit's latitude correction keeps the scale honest: 1 mm of paper is scale/1000 metres at the centre.
        assertEquals(l.scale / 1000, l.metersPerPixel * l.dpi / 25.4, l.scale / 1000 * 0.01)
        assertTrue(cos(l.centerLat * PI / 180) > 0)
    }

    private class Recorder : SheetCanvas {
        val texts = ArrayList<Pair<String, BoxPx>>()
        var roundLines = 0
        var clips = 0
        override fun fontPx(font: SheetFont) = font.sizePt * 100 / 72
        override fun advance(text: String, font: SheetFont) = text.length * fontPx(font) * 0.5
        override fun measure(text: String, font: SheetFont, x: Double, y: Double, align: Double, rotate: Boolean): BoxPx {
            val w = advance(text, font)
            val h = fontPx(font) * 0.7
            return BoxPx(x - w * align, y - h, w, h)
        }
        override fun text(text: String, font: SheetFont, x: Double, y: Double, align: Double, rotate: Boolean, color: Int): BoxPx {
            val b = measure(text, font, x, y, align, rotate)
            texts += text to b
            return b
        }
        override fun haloText(text: String, font: SheetFont, x: Double, y: Double, align: Double, color: Int, halo: Int) {}
        override fun line(x0: Double, y0: Double, x1: Double, y1: Double, color: Int, width: Double) {}
        override fun polyline(xy: DoubleArray, dx: Double, dy: Double, color: Int, width: Double) {}
        override fun roundLine(xy: DoubleArray, dx: Double, dy: Double, color: Int, width: Double) { roundLines++ }
        override fun rect(x: Double, y: Double, w: Double, h: Double, fill: Int?, stroke: Int?, width: Double) {}
        override fun polygon(xy: DoubleArray, color: Int) {}
        override fun clip(x: Double, y: Double, w: Double, h: Double) { clips++ }
        override fun unclip() { clips-- }
    }
}
