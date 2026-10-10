package io.github.jasondorjeshort.dystopo.png

import io.github.jasondorjeshort.dystopo.dem.Terrarium
import io.github.jasondorjeshort.dystopo.tiff.Decompressor
import io.github.jasondorjeshort.dystopo.tiles.ArgbImage
import io.github.jasondorjeshort.dystopo.tiles.ElevationImageDecoder
import io.github.jasondorjeshort.dystopo.tiles.ImageDecoder
import kotlin.concurrent.Volatile
import kotlin.jvm.Synchronized

/**
 * The DEM tile decoder shared by every platform: [PngDecoder] (pure Kotlin, zlib through [inflate])
 * decoding Terrarium PNGs straight to elevations for [io.github.jasondorjeshort.dystopo.tiles.DemPyramid].
 * A PNG it does not support ([UnsupportedPngException]: palette, grey, 16-bit, interlaced) goes to
 * [fallback] (ImageIO, BitmapFactory) when one is given; [fallbacks] counts those.
 */
class TerrariumPngDecoder(inflate: Decompressor, private val fallback: ImageDecoder? = null) : ElevationImageDecoder {
    private val png = PngDecoder(inflate)

    /** Images handed to [fallback] because [PngDecoder] does not support them. */
    @Volatile
    var fallbacks: Long = 0
        private set

    @Synchronized
    private fun countFallback() {
        fallbacks++
    }

    /** Time per decoding phase so far ([PngDecoder.phases]). */
    fun phases(): Map<String, Any?> = png.phases()

    override fun decode(bytes: ByteArray): ArgbImage = try {
        val p = png.decode(bytes)
        ArgbImage(p.width, p.height, p.argb())
    } catch (e: UnsupportedPngException) {
        val f = fallback ?: throw e
        countFallback()
        f.decode(bytes)
    }

    override fun elevations(bytes: ByteArray, width: Int, height: Int): FloatArray {
        val values = try {
            val t = png.terrarium(bytes)
            require(t.width == width && t.height == height) { "tile is ${t.width}x${t.height}, expected ${width}x$height" }
            t.elevations
        } catch (e: UnsupportedPngException) {
            val f = fallback ?: throw e
            countFallback()
            val img = f.decode(bytes)
            require(img.width == width && img.height == height) { "tile is ${img.width}x${img.height}, expected ${width}x$height" }
            FloatArray(img.argb.size) { Terrarium.elevation(img.argb[it]) }
        }
        return values
    }
}
