package io.github.jasondorjeshort.dystopo.render

import io.github.jasondorjeshort.dystopo.geo.BoundingBox
import io.github.jasondorjeshort.dystopo.geo.Utm
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** T167: the printed sheet's page maths: frame, true scale, fit, UTM grid and graticule, the three norths; T183: the phone's sheet and the shared margins. */
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

    // T183: the phone's sheet (a MapLibre snapshot at dpi / STYLE_DPI placed by the layout) and the shared margins.

    /** Paradise on Mount Rainier, the device tour's view. */
    private val paradiseLat = 46.786
    private val paradiseLon = -121.736

    /** The ground metres one MapLibre physical pixel covers at [zoom] and [ratio] (512 px tiles). */
    private fun mapLibreMetersPerPixel(lat: Double, zoom: Double, ratio: Double): Double =
        40_075_016.686 * cos(lat * PI / 180) / (512.0 * 2.0.pow(zoom) * ratio)

    @Test
    fun phoneSheetAtParadiseIsLetterAt25000AndTheSnapshotZoomKeepsTheScale() {
        val paper = Paper.defaultAt(paradiseLat, paradiseLon)
        assertEquals(Paper.LETTER, paper)
        val l = PageLayout.plan(paper, landscape = false, scale = PageLayout.DEFAULT_SCALE, centerLat = paradiseLat, centerLon = paradiseLon, dpi = 200.0)
        assertTrue(abs(l.dpi - 200.0) < 0.2, "dpi ${l.dpi}")
        // Letter's frame (185.9 x 199.4 mm) at about 200 dpi.
        assertEquals(1464.0, l.frameWidthPx.toDouble(), 2.0)
        assertEquals(1570.0, l.frameHeightPx.toDouble(), 2.0)
        val ratio = l.dpi / PageLayout.STYLE_DPI
        val zoom = l.mapLibreZoom(ratio)
        assertEquals(13.6, zoom, 0.1)
        // One snapshot pixel is one page pixel: the scale on paper is exactly 1:25,000.
        val mpp = mapLibreMetersPerPixel(paradiseLat, zoom, ratio)
        assertEquals(l.metersPerPixel, mpp, l.metersPerPixel * 1e-9)
        assertEquals(25_000.0, mpp / (PageLayout.METERS_PER_INCH / l.dpi), 1e-6)
        // The frame's centre is the asked-for centre within a page pixel (the window starts on a whole pixel).
        val (cLat, cLon) = l.frameCenter
        assertTrue(abs(l.frameX(cLon) - l.frameWidthPx / 2.0) < 1e-5 && abs(l.frameX(paradiseLon) - l.frameWidthPx / 2.0) < 1.0)
        assertTrue(abs(l.frameY(cLat) - l.frameHeightPx / 2.0) < 1e-5 && abs(l.frameY(paradiseLat) - l.frameHeightPx / 2.0) < 1.0)
        // 1:25,000 on Letter covers about 4.6 by 5 km of ground.
        assertEquals(4648.0, l.groundWidthM, 10.0)
        assertEquals(4985.0, l.groundHeightM, 10.0)
    }

    @Test
    fun phoneSheetScalesAndPapersKeepTheScaleExact() {
        for (scale in PageLayout.SCALES) for (paper in Paper.entries) for (landscape in listOf(false, true)) {
            for ((lat, lon) in listOf(paradiseLat to paradiseLon, montBlancLat to montBlancLon)) {
                val l = PageLayout.plan(paper, landscape, scale.toDouble(), lat, lon, dpi = 200.0)
                val ratio = l.dpi / PageLayout.STYLE_DPI
                val mpp = mapLibreMetersPerPixel(lat, l.mapLibreZoom(ratio), ratio)
                assertEquals(l.metersPerPixel, mpp, l.metersPerPixel * 1e-9, "1:$scale ${paper.key} landscape=$landscape")
                assertTrue(abs(l.dpi - 200.0) / 200.0 < 0.01, "dpi ${l.dpi} at 1:$scale")
                assertEquals(if (landscape) paper.heightMm else paper.widthMm, l.pageWidthMm)
            }
        }
        // Mont Blanc: A4; 1:50,000 shows four times the ground of 1:25,000 in the same frame, one zoom out.
        assertEquals(Paper.A4, Paper.defaultAt(montBlancLat, montBlancLon))
        val a = PageLayout.plan(Paper.A4, false, 25_000.0, montBlancLat, montBlancLon, 200.0)
        val b = PageLayout.plan(Paper.A4, false, 50_000.0, montBlancLat, montBlancLon, 200.0)
        assertEquals(4.0, b.groundWidthM * b.groundHeightM / (a.groundWidthM * a.groundHeightM), 0.02)
        assertEquals(a.mapLibreZoom(a.dpi / 150) - 1, b.mapLibreZoom(b.dpi / 150), 0.01)
    }

    @Test
    fun phoneFitTheViewTakesAllOfTheView() {
        // A phone's view around Paradise, about 6 by 8 km: fitted on Letter it needs a smaller scale than 1:25,000.
        val view = BoundingBox(-121.775, 46.75, -121.695, 46.82)
        val scale = PageLayout.fitScale(view, Paper.LETTER, landscape = false)
        assertTrue(scale > 25_000 && scale < 60_000, "scale $scale")
        val (lat, lon) = PageLayout.center(view)
        val l = PageLayout.plan(Paper.LETTER, false, scale, lat, lon, 200.0)
        val b = l.bbox
        assertTrue(b.west <= view.west + 1e-4 && b.east >= view.east - 1e-4 && b.south <= view.south + 1e-4 && b.north >= view.north - 1e-4, "$b against $view")
        assertEquals(PageLayout.ratio(scale), l.ratioText)
    }

    @Test
    fun sheetQueryParsesThePrintOptions() {
        assertEquals(SheetQuery(25_000.0, null, false, SheetGrid.UTM), SheetQuery.parse(emptyMap()))
        assertEquals(SheetQuery(null, Paper.A3, true, SheetGrid.BOTH), SheetQuery.parse(mapOf("scale" to "fit", "paper" to "A3", "landscape" to "1", "grid" to "both")))
        assertEquals(10_000.0, SheetQuery.parse(mapOf("scale" to "10000")).scale)
        for (bad in listOf(mapOf("scale" to "100"), mapOf("scale" to "x"), mapOf("paper" to "b5"), mapOf("grid" to "mgrs"))) {
            val e = runCatching { SheetQuery.parse(bad) }.exceptionOrNull()
            assertTrue(e is IllegalArgumentException, "$bad gave $e")
        }
    }

    /** A canvas that records what the margins draw, measuring text as half an em per character. */
    private class RecordingCanvas : SheetCanvas {
        val texts = ArrayList<Pair<String, BoxPx>>()
        var lines = 0
        var polylines = 0
        var rects = 0
        var polygons = 0
        var clips = 0
        override fun fontPx(font: SheetFont) = font.sizePt * 200 / 72
        override fun advance(text: String, font: SheetFont) = text.length * fontPx(font) * 0.5
        override fun measure(text: String, font: SheetFont, x: Double, y: Double, align: Double, rotate: Boolean): BoxPx {
            val w = advance(text, font)
            val h = fontPx(font) * 0.7
            return if (!rotate) BoxPx(x - w * align, y - h, w, h) else BoxPx(x - h, y - w * (1 - align), h, w)
        }
        override fun text(text: String, font: SheetFont, x: Double, y: Double, align: Double, rotate: Boolean, color: Int): BoxPx {
            val b = measure(text, font, x, y, align, rotate)
            texts += text to b
            return BoxPx(b.x - 2, b.y - 2, b.w + 4, b.h + 4)
        }
        override fun haloText(text: String, font: SheetFont, x: Double, y: Double, align: Double, color: Int, halo: Int) {
            texts += text to measure(text, font, x, y, align, false)
        }
        override fun line(x0: Double, y0: Double, x1: Double, y1: Double, color: Int, width: Double) { lines++ }
        override fun polyline(xy: DoubleArray, dx: Double, dy: Double, color: Int, width: Double) { polylines++ }
        override fun rect(x: Double, y: Double, w: Double, h: Double, fill: Int?, stroke: Int?, width: Double) { rects++ }
        override fun polygon(xy: DoubleArray, color: Int) { polygons++ }
        override fun clip(x: Double, y: Double, w: Double, h: Double) { clips++ }
        override fun unclip() { clips-- }
    }

    @Test
    fun sheetMarginDrawsEveryItemOnThePhonesPage() {
        val l = PageLayout.plan(Paper.LETTER, false, 25_000.0, paradiseLat, paradiseLon, 200.0)
        val m = SheetMargin(l, "Paradise", SheetGrid.UTM, ContourUnits.FEET, 80.0, "ft", l.north(jan2026), "2026-01-01", "Elevation: USGS 3DEP.")
        val c = RecordingCanvas()
        m.paint(c)
        val all = m.texts.joinToString("\n")
        for (item in listOf(
            "Paradise", "1:25,000", "Contour interval 80 feet", "Magnetic north (MN) is 15° east of true north (star).",
            "Grid north (GN) is", "UTM zone 10N, 1 km grid. Datum WGS84.", "Heights in feet above sea level (EGM2008).",
            "Printed 2026-01-01.", "Sources: Elevation: USGS 3DEP.", SheetMargin.PRINT_NOTE, "mE", "mN", "0", " mi", " km",
        )) assertTrue(item in all, "margin lacks '$item':\n$all")
        assertEquals(0, c.clips, "every clip is undone")
        // The scale bars (a feet sheet has a mile bar and a kilometre bar, four or five blocks each) and the frame.
        assertTrue(c.rects in 9..11, "rects ${c.rects}")
        assertEquals(l.utmGrid().let { it.eastings.size + it.northings.size }, c.polylines)
        assertEquals(2, c.polygons) // the star and magnetic north's arrowhead
        // Every text is on the page; the print note is below the frame.
        val pageW = l.pageWidthPx.toDouble()
        val pageH = l.pageHeightPx.toDouble()
        for ((t, b) in c.texts) assertTrue(b.x >= 0 && b.maxX <= pageW && b.y >= 0 && b.maxY <= pageH, "'$t' at $b off the ${pageW}x$pageH page")
        val frameBottom = (l.frameYPx + l.frameHeightPx).toDouble()
        assertTrue(c.texts.first { it.first.startsWith("Print at") }.second.y > frameBottom)
        // UTM labels drawn above the frame never overlap.
        val top = c.texts.map { it.second }.filter { it.maxY < l.frameYPx && it.y > PageLayout.MARGIN_MM * l.dpi / 25.4 + 30 }
        assertTrue(top.size >= 3, "top labels $top")
        for (i in top.indices) for (j in i + 1 until top.size) assertTrue(!top[i].intersects(top[j]), "${top[i]} and ${top[j]}")
    }

    @Test
    fun sheetMarginLinesForMetresAndLatLon() {
        val l = PageLayout.plan(Paper.A4, true, 50_000.0, montBlancLat, montBlancLon, 200.0)
        val m = SheetMargin(l, "Mont Blanc", SheetGrid.LATLON, ContourUnits.METERS, 20.0, "m", l.north(jan2026), "2026-01-01", "x")
        m.paint(RecordingCanvas())
        assertEquals(null, m.utm)
        assertTrue(m.gridLine().startsWith("Latitude and longitude every ") && m.gridLine().endsWith("Datum WGS84. UTM zone 32."), m.gridLine())
        assertTrue("Contour interval 20 metres" in m.texts)
        assertTrue(m.texts.none { it.startsWith("Grid north") })
        assertEquals("Contour interval 12.5 metres", SheetMargin.contourLine(12.5, "m"))
        assertEquals("less than 1° west", SheetMargin.degrees(-0.4))
        assertEquals("2.5 km", SheetMargin.spacing(2500.0))
        assertEquals("30\"", SheetMargin.arc(30))
        assertTrue(BoxPx(0.0, 0.0, 10.0, 10.0).intersects(BoxPx(5.0, 5.0, 10.0, 10.0)))
        assertTrue(!BoxPx(0.0, 0.0, 10.0, 10.0).intersects(BoxPx(10.0, 0.0, 10.0, 10.0)))
    }
}
