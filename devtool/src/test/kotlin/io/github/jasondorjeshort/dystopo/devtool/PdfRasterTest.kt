package io.github.jasondorjeshort.dystopo.devtool

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T183: the PDF rasteriser ([PdfRaster]) against the print golden: the golden sheet's PDF rasterised at the sheet's
 * own dpi comes back the size of the page image and close to it (the PDF holds the page as a JPEG).
 */
class PdfRasterTest {
    @Test
    fun goldenPdfRasterisesToItsPage() {
        val sheet = PrintGolden.render()
        val pdf = sheet.pdf()
        val pages = PdfRaster.pages(pdf)
        assertEquals(1, pages.size)
        assertEquals(210.0, pages[0].first, 0.05)
        assertEquals(297.0, pages[0].second, 0.05)
        val image = PdfRaster.render(pdf, sheet.layout.dpi.toFloat())
        assertTrue(abs(image.width - sheet.page.width) <= 1 && abs(image.height - sheet.page.height) <= 1, "${image.width}x${image.height} against ${sheet.page.width}x${sheet.page.height}")
        val w = minOf(image.width, sheet.page.width)
        val h = minOf(image.height, sheet.page.height)
        var sum = 0L
        for (y in 0 until h) for (x in 0 until w) {
            val a = image.getRGB(x, y)
            val b = sheet.page.getRGB(x, y)
            for (shift in intArrayOf(0, 8, 16)) sum += abs(((a shr shift) and 0xff) - ((b shr shift) and 0xff))
        }
        val mean = sum.toDouble() / (w * h * 3)
        assertTrue(mean < 6.0, "mean difference ${(mean * 100).roundToInt() / 100.0} per channel")
    }
}
