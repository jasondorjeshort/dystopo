package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.png.TerrariumPngDecoder
import io.github.jasondorjeshort.dystopo.tiff.Decompressor
import io.github.jasondorjeshort.dystopo.tiles.ArgbImage
import io.github.jasondorjeshort.dystopo.tiles.ImageDecoder
import java.util.zip.Inflater
import javax.imageio.ImageIO

/** ImageIO PNG decoding (the devtool's general-purpose decoder; the fallback for PNGs [PngDecoder] does not read). */
val imageIoDecoder = ImageDecoder { bytes ->
    val img = ImageIO.read(bytes.inputStream()) ?: error("unreadable image (${bytes.size} bytes)")
    ArgbImage(img.width, img.height, img.getRGB(0, 0, img.width, img.height, null, 0, img.width))
}

/**
 * The devtool's DEM tile decoder (serve, live tiles, route): the shared Kotlin [TerrariumPngDecoder] with
 * java.util.zip inflate (the faster choice on the JVM, T42), ImageIO for anything it does not read.
 */
val demDecoder: TerrariumPngDecoder by lazy { TerrariumPngDecoder(ZlibInflate.decompressor, imageIoDecoder) }

/** zlib inflate for [PngDecoder] on the JVM: one [Inflater] per thread, reset between images. */
object ZlibInflate {
    private val inflaters = ThreadLocal.withInitial { Inflater() }

    val decompressor: Decompressor = { input, size ->
        val inflater = inflaters.get()
        inflater.reset()
        inflater.setInput(input)
        val out = ByteArray(size)
        var n = 0
        while (n < size && !inflater.finished()) {
            val k = inflater.inflate(out, n, size - n)
            if (k == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
            n += k
        }
        require(n == size) { "zlib stream ended after $n of $size bytes" }
        out
    }
}
