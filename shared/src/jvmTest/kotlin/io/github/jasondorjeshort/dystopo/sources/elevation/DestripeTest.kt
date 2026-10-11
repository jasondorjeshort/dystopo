package io.github.jasondorjeshort.dystopo.sources.elevation

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** T70: the 3DEP striping filter removes period-2 stripes where it detects them and nothing else. */
class DestripeTest {
    private val w = 128
    private val h = 128

    /**
     * A slope with rolling texture at 5 to 40 cell wavelengths in random directions (real terrain is not
     * white noise at the pixel scale), a little noise, and a 60 m cliff along x = 96.
     */
    private fun terrain(seed: Int): FloatArray {
        val r = Random(seed)
        val waves = List(8) { k ->
            val len = 5.0 + 35.0 * k / 7
            val dir = r.nextDouble(0.0, 2 * PI)
            doubleArrayOf(cos(dir) * 2 * PI / len, sin(dir) * 2 * PI / len, r.nextDouble(0.0, 2 * PI), 0.05 * len)
        }
        return FloatArray(w * h) { k ->
            val i = k % w
            val j = k / w
            val texture = waves.sumOf { (kx, ky, ph, amp) -> amp * sin(kx * i + ky * j + ph) }
            (j * 2.0 + texture + r.nextDouble(-0.02, 0.02) + if (i >= 96) -60.0 else 0.0).toFloat()
        }
    }

    @Test
    fun removesRowStripesAndLeavesTheRestAlone() {
        val clean = terrain(1)
        val striped = clean.copyOf()
        // Rows alternate by +-0.8 m in the top-left quarter only.
        for (j in 0 until 64) for (i in 0 until 64) striped[j * w + i] += if (j % 2 == 0) 0.8f else -0.8f
        val v = striped.copyOf()
        val stats = Destripe.filter(v, w, h)
        assertTrue(stats.filtered > 0, "stripes detected")
        // Inside the striped quarter (away from its edge, where the strength ramps) the stripe is gone.
        var before = 0.0
        var after = 0.0
        for (j in 8 until 48) for (i in 8 until 48) {
            before += abs(striped[j * w + i] - clean[j * w + i])
            after += abs(v[j * w + i] - clean[j * w + i])
        }
        assertTrue(after < before * 0.15, "stripe left: $after of $before")
        // Far from the stripes (and at the cliff) nothing moves beyond the noise; no cell moves by more than the stripe.
        for (j in 96 until h) for (i in 0 until w) assertEquals(clean[j * w + i], v[j * w + i], 0.05f, "($i, $j) untouched")
        assertTrue(stats.maxChange <= 0.81, "max change ${stats.maxChange}")
    }

    @Test
    fun checkerboardToo() {
        val clean = terrain(2)
        val v = clean.copyOf()
        for (j in 0 until h) for (i in 0 until w) v[j * w + i] += if ((i + j) % 2 == 0) 0.5f else -0.5f
        Destripe.filter(v, w, h)
        var left = 0.0
        for (j in 16 until 112) for (i in 16 until 80) left += abs(v[j * w + i] - clean[j * w + i])
        assertTrue(left / (96 * 64) < 0.1, "checkerboard left ${left / (96 * 64)} m per cell")
    }

    @Test
    fun plainTerrainIsUntouched() {
        val clean = terrain(3)
        val v = clean.copyOf()
        val stats = Destripe.filter(v, w, h)
        // The cliff along x = 96 is not a stripe (medians), and chance hits in the noise move cells by noise amounts only.
        assertTrue(stats.filtered <= 3 * 64 / 20, "detections on unstriped terrain: ${stats.filtered}")
        for (k in v.indices) assertEquals(clean[k], v[k], 0.05f, "cell $k")
    }

    @Test
    fun offByDefault() {
        // The filter only runs with DYSTOPO_3DEP_DESTRIPE=1; CI and the tour never set it.
        assertTrue(System.getenv("DYSTOPO_3DEP_DESTRIPE") != "1")
    }
}
