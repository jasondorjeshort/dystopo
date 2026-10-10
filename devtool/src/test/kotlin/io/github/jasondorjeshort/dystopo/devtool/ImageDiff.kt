package io.github.jasondorjeshort.dystopo.devtool

import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.math.max

/** Tolerance for one region of an image. */
data class Tolerance(
    /** Mean absolute difference per channel (0..255), averaged over the region's pixels. */
    val maxMeanAbs: Double,
    /** A pixel "differs" when any channel differs by more than this. */
    val pixelThreshold: Int,
    /** At most this fraction of the region's pixels may differ. */
    val maxDifferingFraction: Double,
)

/** Statistics for one region (all pixels, or only those in or out of a mask). */
data class RegionDiff(val pixels: Int, val meanAbs: Double, val differing: Int, val maxChannelDiff: Int) {
    val differingFraction: Double get() = if (pixels == 0) 0.0 else differing.toDouble() / pixels

    fun within(t: Tolerance) = meanAbs <= t.maxMeanAbs && differingFraction <= t.maxDifferingFraction

    fun describe(t: Tolerance) = "%d px, mean |d| %.3f (max %.3f), differing %.3f%% (max %.3f%%), worst channel diff %d"
        .format(pixels, meanAbs, t.maxMeanAbs, differingFraction * 100, t.maxDifferingFraction * 100, maxChannelDiff)
}

/** Compares two same-size RGB images, split into pixels inside and outside a mask. */
class ImageDiff(
    val expected: BufferedImage,
    val actual: BufferedImage,
    val mask: BooleanArray,
    val pixelThreshold: Int,
) {
    val width = expected.width
    val height = expected.height
    /** Per pixel: the largest channel difference. */
    val perPixel = IntArray(width * height)
    val outside: RegionDiff
    val inside: RegionDiff

    init {
        require(actual.width == width && actual.height == height)
        require(mask.size == width * height)
        val e = expected.getRGB(0, 0, width, height, null, 0, width)
        val a = actual.getRGB(0, 0, width, height, null, 0, width)
        val sum = LongArray(2)
        val count = IntArray(2)
        val differing = IntArray(2)
        val worst = IntArray(2)
        for (i in e.indices) {
            val dr = abs(((e[i] shr 16) and 0xff) - ((a[i] shr 16) and 0xff))
            val dg = abs(((e[i] shr 8) and 0xff) - ((a[i] shr 8) and 0xff))
            val db = abs((e[i] and 0xff) - (a[i] and 0xff))
            val d = max(dr, max(dg, db))
            perPixel[i] = d
            val k = if (mask[i]) 1 else 0
            sum[k] += (dr + dg + db).toLong()
            count[k]++
            if (d > pixelThreshold) differing[k]++
            if (d > worst[k]) worst[k] = d
        }
        fun region(k: Int) = RegionDiff(count[k], if (count[k] == 0) 0.0 else sum[k] / (3.0 * count[k]), differing[k], worst[k])
        outside = region(0)
        inside = region(1)
    }

    /**
     * A picture of the difference: the expected image faded to light gray, label regions tinted
     * blue, pixels over the threshold in red (brighter = larger), smaller differences in pink.
     */
    fun diffImage(): BufferedImage {
        val out = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val e = expected.getRGB(0, 0, width, height, null, 0, width)
        val px = IntArray(width * height) { i ->
            val lum = (((e[i] shr 16) and 0xff) * 3 + ((e[i] shr 8) and 0xff) * 6 + (e[i] and 0xff)) / 10
            val g = 170 + lum / 4
            val d = perPixel[i]
            when {
                d > pixelThreshold -> {
                    val r = (128 + d * 4).coerceAtMost(255)
                    (r shl 16)
                }
                d > 0 -> (255 shl 16) or ((g - d * 6).coerceAtLeast(110) shl 8) or (g - d * 6).coerceAtLeast(110)
                mask[i] -> ((g - 40) shl 16) or ((g - 20) shl 8) or 255
                else -> (g shl 16) or (g shl 8) or g
            }
        }
        out.setRGB(0, 0, width, height, px, 0, width)
        return out
    }
}
