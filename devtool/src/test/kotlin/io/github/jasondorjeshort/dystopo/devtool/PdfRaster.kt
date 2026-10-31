package io.github.jasondorjeshort.dystopo.devtool

import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * T183: the instrument for viewing a printed sheet: one page of a PDF rasterised to an image with PDFBox (already
 * devtool's PDF library; test classpath only, nothing ships it). The tools view the phone's sheet with it
 * (`POST /print` on the phone, then `./gradlew :devtool:rasterisePdf --args="<in.pdf> <out.png> [dpi] [page]"`, run
 * from the repo root), and [PdfRasterTest] checks it against the print golden.
 */
object PdfRaster {
    /** Page [page] (from 0) of [pdf] at [dpi], RGB on white. */
    fun render(pdf: ByteArray, dpi: Float = 150f, page: Int = 0): BufferedImage =
        Loader.loadPDF(pdf).use { doc ->
            require(page in 0 until doc.numberOfPages) { "page $page of ${doc.numberOfPages}" }
            PDFRenderer(doc).renderImageWithDPI(page, dpi, ImageType.RGB)
        }

    /** The page count and each page's size in millimetres (the media box). */
    fun pages(pdf: ByteArray): List<Pair<Double, Double>> = Loader.loadPDF(pdf).use { doc ->
        (0 until doc.numberOfPages).map { i -> doc.getPage(i).mediaBox.let { it.width / 72.0 * 25.4 to it.height / 72.0 * 25.4 } }
    }
}

/** `rasterisePdf <in.pdf> <out.png> [dpi=150] [page=0]`: writes the PNG and prints one JSON line about it. */
fun main(args: Array<String>) {
    require(args.size >= 2) { "usage: rasterisePdf <in.pdf> <out.png> [dpi] [page]" }
    val input = File(args[0])
    val output = File(args[1])
    val dpi = args.getOrNull(2)?.toFloat() ?: 150f
    val page = args.getOrNull(3)?.toInt() ?: 0
    val bytes = input.readBytes()
    val started = System.nanoTime()
    val image = PdfRaster.render(bytes, dpi, page)
    output.absoluteFile.parentFile.mkdirs()
    check(ImageIO.write(image, "png", output)) { "no PNG writer" }
    val sizes = PdfRaster.pages(bytes)
    val ms = (System.nanoTime() - started) / 1_000_000
    val (wMm, hMm) = sizes[page]
    println(
        "{\"pdf\":\"${input.path.replace("\\", "/")}\",\"pdfBytes\":${bytes.size},\"pages\":${sizes.size},\"page\":$page," +
            "\"pageMm\":[${"%.1f".format(java.util.Locale.ROOT, wMm)},${"%.1f".format(java.util.Locale.ROOT, hMm)}],\"dpi\":$dpi," +
            "\"png\":\"${output.path.replace("\\", "/")}\",\"pngPx\":[${image.width},${image.height}],\"pngBytes\":${output.length()},\"ms\":$ms}",
    )
}
