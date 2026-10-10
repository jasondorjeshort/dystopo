package io.github.jasondorjeshort.dystopo.png

import io.github.jasondorjeshort.dystopo.dem.Terrarium
import io.github.jasondorjeshort.dystopo.tiff.Decompressor
import io.github.jasondorjeshort.dystopo.tiles.ArgbImage
import io.github.jasondorjeshort.dystopo.tiles.ImageDecoder
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater
import javax.imageio.ImageIO
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** [PngDecoder] on real zlib streams: PNGs written by ImageIO (which picks a filter per row) and read back by ImageIO. */
class PngDecoderJvmTest {
    private val inflate: Decompressor = { input, size ->
        val inflater = Inflater()
        try {
            inflater.setInput(input)
            val out = ByteArray(size)
            var n = 0
            while (n < size && !inflater.finished()) {
                val k = inflater.inflate(out, n, size - n)
                if (k == 0 && inflater.needsInput()) break
                n += k
            }
            out
        } finally {
            inflater.end()
        }
    }
    private val decoder = PngDecoder(inflate)

    private fun png(img: BufferedImage): ByteArray = ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()

    private fun imageIoArgb(bytes: ByteArray): IntArray {
        val img = ImageIO.read(bytes.inputStream())
        return img.getRGB(0, 0, img.width, img.height, null, 0, img.width)
    }

    @Test
    fun terrariumTileLikeTheTilesCommandWritesIt() {
        // TYPE_INT_RGB, smooth terrain: what `devtool tiles` and live tiles store in packs.
        val img = BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 256) for (x in 0 until 256) {
            img.setRGB(x, y, Terrarium.encode((1500 + 800 * sin(x / 37.0) * sin(y / 23.0) + x * 0.37).toFloat()))
        }
        val bytes = png(img)
        val expected = imageIoArgb(bytes)
        assertContentEquals(expected, decoder.decode(bytes).argb())
        assertContentEquals(FloatArray(expected.size) { Terrarium.elevation(expected[it]) }, decoder.terrarium(bytes).elevations)
    }

    @Test
    fun noisyRgbAndRgbaImages() {
        val r = Random(11)
        for (type in listOf(BufferedImage.TYPE_INT_RGB, BufferedImage.TYPE_INT_ARGB, BufferedImage.TYPE_3BYTE_BGR, BufferedImage.TYPE_4BYTE_ABGR)) {
            val img = BufferedImage(97, 61, type)
            for (y in 0 until 61) for (x in 0 until 97) img.setRGB(x, y, if (x % 7 < 3) r.nextInt() else (x * 3 + y * 5) or (0xFF shl 24))
            val bytes = png(img)
            val p = decoder.decode(bytes)
            assertEquals(if (img.colorModel.hasAlpha()) 4 else 3, p.channels, "type $type")
            assertContentEquals(imageIoArgb(bytes), p.argb(), "type $type")
        }
    }

    @Test
    fun paletteAndGreyGoToTheFallback() {
        val imageIo = ImageDecoder { b -> val i = ImageIO.read(b.inputStream()); ArgbImage(i.width, i.height, i.getRGB(0, 0, i.width, i.height, null, 0, i.width)) }
        for (type in listOf(BufferedImage.TYPE_BYTE_INDEXED, BufferedImage.TYPE_BYTE_GRAY, BufferedImage.TYPE_USHORT_GRAY)) {
            val img = BufferedImage(16, 16, type)
            for (y in 0 until 16) for (x in 0 until 16) img.setRGB(x, y, (x * 16) * 0x010101)
            val bytes = png(img)
            assertFailsWith<UnsupportedPngException>("type $type") { decoder.decode(bytes) }
            val d = TerrariumPngDecoder(inflate, imageIo)
            assertContentEquals(imageIoArgb(bytes), d.decode(bytes).argb)
            assertEquals(1L, d.fallbacks)
        }
    }
}
