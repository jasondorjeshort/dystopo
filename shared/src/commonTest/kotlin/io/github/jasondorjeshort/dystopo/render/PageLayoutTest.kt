package io.github.jasondorjeshort.dystopo.render

import io.github.jasondorjeshort.dystopo.geo.BoundingBox
import io.github.jasondorjeshort.dystopo.geo.Utm
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** T167: the printed sheet's page maths: frame, true scale, fit, UTM grid and graticule, the three norths. */
class PageLayoutTest {
    // Mount Rainier's summit and Mont Blanc's: public places.
    private val rainierLat = 46.853
    private val rainierLon = -121.760
    private val montBlancLat = 45.833
    private val montBlancLon = 6.865

    /** 2026-01-01T00:00Z. */
    private val jan2026 = 1_767_225_600_000L

    private fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_008.8
        val p1 = lat1 * PI / 180; val p2 = lat2 * PI / 180
        val dp = p2 - p1; val dl = (lon2 - lon1) * PI / 180
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * r * asin(sqrt(a))
    }

    @Test
    fun a4PortraitFrameLeavesTheBandsAndFooter() {
        val f = PageLayout.frameMm(Paper.A4, landscape = false)
        assertEquals(15.0, f.x)
        assertEquals(25.0, f.y)
        assertEquals(180.0, f.w)
        assertEquals(217.0, f.h)
        val l = PageLayout.frameMm(Paper.A4, landscape = true)
        assertEquals(267.0, l.w)
        assertEquals(130.0, l.h)
    }

    @Test
    fun paperFollowsTheRegionAsUnitsDo() {
        assertEquals(Paper.LETTER, Paper.defaultAt(rainierLat, rainierLon))
        assertEquals(Paper.A4, Paper.defaultAt(montBlancLat, montBlancLon))
        assertEquals(Paper.A3, Paper.parse("A3"))
        assertEquals(null, Paper.parse("a5"))
    }

    @Test
    fun scaleIsTrueAtTheCentre() {
        val p = PageLayout.plan(Paper.A4, false, 25_000.0, rainierLat, rainierLon)
        // 180 mm of paper is 4.5 km of ground at 1:25,000.
        assertEquals(4500.0, p.groundWidthM, 4500.0 * 0.002)
        // Measured on the ground along the frame's middle row, the same within the Mercator scale's change across it.
        val y = p.frameHeightPx / 2.0
        val measured = haversineM(p.latAt(y), p.lonAt(0.0), p.latAt(y), p.lonAt(p.frameWidthPx.toDouble()))
        assertEquals(p.groundWidthM, measured, p.groundWidthM * 0.002)
        // The page's dpi moves only by the tile size's rounding; the page stays A4 at that dpi.
        assertTrue(abs(p.dpi - 300.0) < 300.0 * 0.005, "dpi ${p.dpi}")
        assertEquals((210.0 * p.dpi / 25.4).toInt().toDouble(), p.pageWidthPx.toDouble(), 1.0)
        // The style zoom for 1:25,000 near 47 N is 15 (the 40 ft contour interval of a printed 1:24,000 sheet).
        assertEquals(15, p.zoom)
        assertEquals(2.0, p.styleScale)
        // The frame's centre is the sheet's centre.
        assertEquals(rainierLat, p.latAt(p.frameHeightPx / 2.0), 1e-4)
        assertEquals(rainierLon, p.lonAt(p.frameWidthPx / 2.0), 1e-4)
    }

    @Test
    fun theStyleZoomDoesNotDependOnTheDpi() {
        val full = PageLayout.plan(Paper.A4, false, 25_000.0, rainierLat, rainierLon, dpi = 300.0)
        val small = PageLayout.plan(Paper.A4, false, 25_000.0, rainierLat, rainierLon, dpi = 60.0)
        assertEquals(full.zoom, small.zoom)
        assertEquals(full.groundWidthM, small.groundWidthM, full.groundWidthM * 0.01)
        assertEquals(16, PageLayout.plan(Paper.A4, false, 10_000.0, rainierLat, rainierLon).zoom)
        assertEquals(14, PageLayout.plan(Paper.A4, false, 50_000.0, rainierLat, rainierLon).zoom)
    }

    @Test
    fun fitTheViewShowsAllOfItAtTheRatioItPrints() {
        val box = BoundingBox(-121.86, 46.78, -121.66, 46.93) // the public rainier area
        for (landscape in listOf(false, true)) {
            val scale = PageLayout.fitScale(box, Paper.LETTER, landscape)
            val (lat, lon) = PageLayout.center(box)
            val p = PageLayout.plan(Paper.LETTER, landscape, scale, lat, lon)
            val f = p.bbox
            assertTrue(f.west <= box.west + 1e-4 && f.east >= box.east - 1e-4 && f.south <= box.south + 1e-4 && f.north >= box.north - 1e-4, "$f does not hold $box")
            // One side fits snugly: within a few pixels.
            val slackX = (box.west - f.west) / (f.east - f.west)
            val slackY = (mercY(box.north) - mercY(f.north)) / (mercY(f.south) - mercY(f.north))
            assertTrue(minOf(abs(slackX), abs(slackY)) < 0.003, "slack $slackX, $slackY")
        }
        assertTrue(PageLayout.fitScale(box, Paper.A4, false) > 70_000.0)
    }

    private fun mercY(lat: Double) = io.github.jasondorjeshort.dystopo.geo.WebMercator.latToY(lat, 0)

    @Test
    fun gridSpacingKeepsLinesAPrintableDistanceApart() {
        assertEquals(1000.0, PageLayout.gridSpacingMeters(25_000.0))
        assertEquals(1000.0, PageLayout.gridSpacingMeters(24_000.0))
        assertEquals(500.0, PageLayout.gridSpacingMeters(10_000.0))
        assertEquals(2000.0, PageLayout.gridSpacingMeters(50_000.0))
        assertEquals(30, PageLayout.graticuleSpacingArcSec(25_000.0, rainierLat))
        assertEquals(60, PageLayout.graticuleSpacingArcSec(50_000.0, rainierLat))
    }

    @Test
    fun utmLinesAreWholeKilometresFortyMillimetresApart() {
        val p = PageLayout.plan(Paper.A4, false, 25_000.0, rainierLat, rainierLon)
        val g = p.utmGrid()
        assertEquals(10, g.zone)
        assertEquals("10N", g.zoneName)
        assertEquals(1000.0, g.spacingM)
        assertTrue(g.eastings.size in 4..6, "${g.eastings.size} eastings")
        assertTrue(g.northings.size in 5..7, "${g.northings.size} northings")
        for (l in g.eastings + g.northings) assertEquals(0.0, l.value % 1000.0)
        // Neighbouring easting lines, at their middle sample, are 40 mm apart on paper (1 km at 1:25,000).
        val a = g.eastings[1].xy
        val b = g.eastings[2].xy
        val mid = a.size / 4 * 2
        val mm = hypot(b[mid] - a[mid], b[mid + 1] - a[mid + 1]) / p.dpi * 25.4
        assertEquals(40.0, mm, 0.3)
        // A grid line goes through the point it names: the easting's UTM coordinates map back onto its polyline.
        val e = g.eastings[2].value
        val pt = Utm.forward(p.latAt(b[mid + 1]), p.lonAt(b[mid]), 10)
        assertEquals(e, pt.easting, 2 * p.metersPerPixel)
        // Each edge gets labels, the first in full and the rest as principal digits.
        val top = g.labels.filter { it.edge == FrameEdge.TOP }
        assertTrue(top.size >= 4)
        assertTrue(top.first().text.endsWith("mE") && top.first().text.length == 8, top.first().text)
        assertEquals(2, top[1].text.length, top[1].text)
        val left = g.labels.filter { it.edge == FrameEdge.LEFT }
        assertTrue(left.first().text.endsWith("mN") && left.first().text.length == 9, left.first().text)
        for (lbl in g.labels) assertTrue(lbl.at >= 0 && lbl.at <= maxOf(p.frameWidthPx, p.frameHeightPx))
    }

    @Test
    fun graticuleLabelsReadInDegreesMinutesSeconds() {
        assertEquals("46°51'30\"N", PageLayout.dms(46.858333, lat = true))
        assertEquals("121°45'W", PageLayout.dms(-121.75, lat = false))
        assertEquals("6°52'E", PageLayout.dms(6.866667, lat = false))
        val p = PageLayout.plan(Paper.A4, false, 25_000.0, rainierLat, rainierLon)
        val g = p.graticule()
        assertEquals(30, g.spacingArcSec)
        assertTrue(g.lons.isNotEmpty() && g.lats.isNotEmpty())
        for (lon in g.lons) assertTrue(p.frameX(lon) in 0.0..p.frameWidthPx.toDouble())
        assertEquals(2 * (g.lons.size + g.lats.size), g.labels.size)
    }

    @Test
    fun theThreeNorthsAtRainier() {
        val p = PageLayout.plan(Paper.LETTER, false, 25_000.0, rainierLat, rainierLon)
        val n = p.north(jan2026)
        // East of zone 10's central meridian (123 W) grid north leans east of true north: about 0.9 degrees.
        assertEquals(0.9, n.convergenceDeg, 0.1)
        // Magnetic declination there is about 15 degrees east in 2026 (WMM-2025).
        assertTrue(n.declinationDeg in 14.0..16.5, "declination ${n.declinationDeg}")
        assertEquals(n.declinationDeg - n.convergenceDeg, n.magneticFromGridDeg)
        assertEquals(2026.0, n.year, 0.01)
    }

    @Test
    fun ratioAndUtmLabelText() {
        assertEquals("1:25,000", PageLayout.ratio(25_000.0))
        assertEquals("1:1,234,567", PageLayout.ratio(1_234_567.0))
        assertEquals("1:500", PageLayout.ratio(500.0))
        assertEquals("594000mE", PageLayout.utmLabel(594_000.0, 1000.0, full = true, suffix = "mE"))
        assertEquals("95", PageLayout.utmLabel(595_000.0, 1000.0, full = false, suffix = "mE"))
        assertEquals("01", PageLayout.utmLabel(5_201_000.0, 1000.0, full = false, suffix = "mN"))
        assertEquals("94.5", PageLayout.utmLabel(594_500.0, 500.0, full = false, suffix = "mE"))
        assertEquals("04.5", PageLayout.utmLabel(504_500.0, 500.0, full = false, suffix = "mE"))
    }
}
