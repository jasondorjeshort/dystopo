package io.github.jasondorjeshort.dystopo.devtool

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * T167: the printed sheet against its golden ([PrintGolden], `goldens/print/`), with the map goldens'
 * tolerances: tight outside text, loose inside the text and label boxes. Then the PDF: one page of A4 in
 * points, readable by PDFBox, asking for actual-size printing. `./gradlew updateGoldens` rewrites the golden.
 */
class PrintGoldenTest {
    private val strict = Tolerance(maxMeanAbs = 0.25, pixelThreshold = 12, maxDifferingFraction = 0.002)
    private val labels = Tolerance(maxMeanAbs = 12.0, pixelThreshold = 12, maxDifferingFraction = 0.30)

    @Test
    fun sheetMatchesItsGolden() {
        val sheet = PrintGolden.render()
        val actual = sheet.page
        val expected = javaClass.getResourceAsStream("/goldens/${PrintGolden.FILE}")?.use { ImageIO.read(it) }
            ?: fail("no golden goldens/${PrintGolden.FILE}; actual at ${write(actual, "actual")}. Run ./gradlew updateGoldens and view it.")
        if (expected.width != actual.width || expected.height != actual.height) {
            fail("size ${actual.width}x${actual.height}, golden ${expected.width}x${expected.height}; actual at ${write(actual, "actual")}")
        }
        val diff = ImageDiff(expected, actual, PrintGolden.mask(sheet), 12)
        if (!diff.outside.within(strict) || !diff.inside.within(labels)) {
            write(actual, "actual")
            fail(
                "printed sheet differs from goldens/${PrintGolden.FILE}:\n  outside text: ${diff.outside.describe(strict)}\n" +
                    "  text: ${diff.inside.describe(labels)}\n  ${write(diff.diffImage(), "diff")}\n" +
                    "If the change is intended, run ./gradlew updateGoldens, view the new image, and commit it.",
            )
        }
        // Every margin item the research asks for is on the page (the report lists the margin's text).
        val margin = sheet.report.margin.joinToString("\n")
        val items = listOf(
            "Lake Cliff (synthetic)", "1:25,000", "Contour interval 10 metres", "Magnetic north (MN) is", "Grid north (GN) is",
            "UTM zone 31N, 1 km grid", "Datum WGS84", "Printed 2026-01-01.", "Sources: Synthetic terrain", "Print at 100 percent",
            "1 km", "mE", "mN", "'N", "'E",
        )
        for (item in items) assertTrue(item in margin, "margin lacks '$item':\n$margin")
        assertEquals(1, sheet.report.gpx?.waypointsDrawn)
    }

    @Test
    fun pdfIsOnePageOfThePaperAtActualSize() {
        val sheet = PrintGolden.render()
        org.apache.pdfbox.Loader.loadPDF(sheet.pdf()).use { doc ->
            assertEquals(1, doc.numberOfPages)
            val box = doc.getPage(0).mediaBox
            assertEquals(210.0 / 25.4 * 72, box.width.toDouble(), 0.01)
            assertEquals(297.0 / 25.4 * 72, box.height.toDouble(), 0.01)
            assertEquals("None", doc.documentCatalog.viewerPreferences.printScaling)
            assertEquals("Lake Cliff (synthetic)", doc.documentInformation.title)
        }
    }

    private fun write(image: BufferedImage, kind: String): String {
        val dir = Goldens.diffDir.absoluteFile.also { it.mkdirs() }
        val f = File(dir, "print-sheet-$kind.png")
        ImageIO.write(image, "png", f)
        return f.path
    }
}
