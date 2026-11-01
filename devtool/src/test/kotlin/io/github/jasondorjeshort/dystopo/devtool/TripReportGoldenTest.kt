package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.gpx.Gpx
import io.github.jasondorjeshort.dystopo.gpx.GpxDocument
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * T198: the trip report against its golden ([TripReportGolden], `goldens/print/`), with the sheet golden's tolerances,
 * then through the rasteriser ([PdfRaster]): the report's PDF rasterised at the page's dpi is the golden's size and
 * close to it (the PDF holds the page as a JPEG). The margin's figures are on the page, in the survey's order, and
 * in the JSON report.
 */
class TripReportGoldenTest {
    private val strict = Tolerance(maxMeanAbs = 0.25, pixelThreshold = 12, maxDifferingFraction = 0.002)
    private val labels = Tolerance(maxMeanAbs = 12.0, pixelThreshold = 12, maxDifferingFraction = 0.30)

    private fun golden(actual: BufferedImage): BufferedImage =
        javaClass.getResourceAsStream("/goldens/${TripReportGolden.FILE}")?.use { ImageIO.read(it) }
            ?: fail("no golden goldens/${TripReportGolden.FILE}; actual at ${write(actual, "actual")}. Run ./gradlew updateGoldens and view it.")

    @Test
    fun reportMatchesItsGolden() {
        val r = TripReportGolden.render()
        val actual = r.page
        val expected = golden(actual)
        if (expected.width != actual.width || expected.height != actual.height) {
            fail("size ${actual.width}x${actual.height}, golden ${expected.width}x${expected.height}; actual at ${write(actual, "actual")}")
        }
        val diff = ImageDiff(expected, actual, TripReportGolden.mask(r), 12)
        if (!diff.outside.within(strict) || !diff.inside.within(labels)) {
            write(actual, "actual")
            fail(
                "trip report differs from goldens/${TripReportGolden.FILE}:\n  outside text: ${diff.outside.describe(strict)}\n" +
                    "  text: ${diff.inside.describe(labels)}\n  ${write(diff.diffImage(), "diff")}\n" +
                    "If the change is intended, run ./gradlew updateGoldens, view the new image, and commit it.",
            )
        }
    }

    @Test
    fun theRasterisedPdfMatchesTheGolden() {
        val r = TripReportGolden.render()
        val pdf = r.pdf()
        val pages = PdfRaster.pages(pdf)
        assertEquals(1, pages.size)
        assertEquals(210.0, pages[0].first, 0.05)
        assertEquals(297.0, pages[0].second, 0.05)
        val image = PdfRaster.render(pdf, r.layout.dpi.toFloat())
        val expected = golden(r.page)
        assertTrue(abs(image.width - expected.width) <= 1 && abs(image.height - expected.height) <= 1, "${image.width}x${image.height} against ${expected.width}x${expected.height}")
        val w = minOf(image.width, expected.width)
        val h = minOf(image.height, expected.height)
        var sum = 0L
        for (y in 0 until h) for (x in 0 until w) {
            val a = image.getRGB(x, y)
            val b = expected.getRGB(x, y)
            for (shift in intArrayOf(0, 8, 16)) sum += abs(((a shr shift) and 0xff) - ((b shr shift) and 0xff))
        }
        val mean = sum.toDouble() / (w * h * 3)
        if (mean >= 6.0) write(image, "rasterised")
        assertTrue(mean < 6.0, "mean difference ${(mean * 100).roundToInt() / 100.0} per channel")
        org.apache.pdfbox.Loader.loadPDF(pdf).use { doc ->
            assertEquals("None", doc.documentCatalog.viewerPreferences.printScaling)
            assertEquals("Lake Cliff walk (synthetic)", doc.documentInformation.title)
        }
    }

    @Test
    fun everyFigureIsOnThePageInOrderAndInTheJson() {
        val r = TripReportGolden.render()
        val margin = r.page.let { (r.report["margin"] as List<*>).joinToString("\n") }
        val keys = listOf("distance", "movingTime", "totalTime", "ascent", "descent", "highest", "lowest")
        assertEquals(keys, r.summary.figures.map { it.key })
        var at = -1
        for (f in r.summary.figures) {
            val i = margin.indexOf("${f.label}: ${f.value}")
            assertTrue(i > at, "'${f.label}: ${f.value}' missing or out of order:\n$margin")
            at = i
        }
        for (item in listOf("Lake Cliff walk (synthetic)", "Trip report, 2026-01-01", "Time against your pace: ", "Printed 2026-01-01.",
            "Sources: Synthetic terrain", "Contour interval", "Elevation profile, stops marked in minutes")) {
            assertTrue(item in margin, "margin lacks '$item':\n$margin")
        }
        assertEquals(2, r.summary.stops.size, "stops ${r.summary.stops}")
        assertEquals(2, r.report["stopLabels"])
        @Suppress("UNCHECKED_CAST")
        val figures = r.report["figures"] as List<Map<String, Any?>>
        assertEquals(keys, figures.map { it["key"] })
        assertEquals("yours", (r.report["pace"] as Map<*, *>)["reason"])
        assertTrue(r.json().contains("\"extraLines\":[\"Time against your pace: "))
    }

    @Test
    fun aGpxFilesFirstTrackIsTheReportsTrack() {
        val doc = GpxDocument(tracks = listOf(TripReportGolden.track()))
        val back = Gpx.read(Gpx.write(doc)).document
        val t = assertNotNull(TripReportSheet.trackOf(back, "fallback"))
        assertEquals("Lake Cliff walk (synthetic)", t.name)
        assertEquals(TripReportGolden.track().points.size, t.points.size)
        // A file with only a route: one segment, no times.
        val r = TripReportSheet.trackOf(GpxDocument(routes = listOf(io.github.jasondorjeshort.dystopo.model.Route("r", "", t.points.take(5).map {
            io.github.jasondorjeshort.dystopo.model.RoutePoint(it.lat, it.lon, it.ele)
        }))), "Paradise")
        assertEquals("Paradise", r?.name)
        assertEquals(5, r?.points?.size)
    }

    private fun write(image: BufferedImage, kind: String): String {
        val dir = Goldens.diffDir.absoluteFile.also { it.mkdirs() }
        val f = File(dir, "trip-report-$kind.png")
        ImageIO.write(image, "png", f)
        return f.path
    }
}
