package io.github.jasondorjeshort.dystopo.png

import java.io.ByteArrayOutputStream
import java.util.zip.Adler32
import java.util.zip.Deflater
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

/** [Inflate] against java.util.zip.Deflater output: every level and strategy, zlib and raw. */
class InflateJvmTest {
    private fun deflate(data: ByteArray, level: Int, strategy: Int = Deflater.DEFAULT_STRATEGY, nowrap: Boolean = false): ByteArray {
        val d = Deflater(level, nowrap)
        d.setStrategy(strategy)
        d.setInput(data)
        d.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return out.toByteArray()
    }

    private val samples: List<ByteArray> = run {
        val r = Random(42)
        listOf(
            ByteArray(0),
            byteArrayOf(7),
            ByteArray(100_000) { r.nextInt().toByte() }, // incompressible: stored blocks
            ByteArray(70_000) { (it % 251).toByte() }, // long repeats, overlapping matches
            ByteArray(200_000) { i -> (sin(i / 97.0) * 40 + (i % 13) + r.nextInt(3)).toInt().toByte() }, // terrain-like
            "the quick brown fox jumps over the lazy dog ".repeat(3000).encodeToByteArray(),
            ByteArray(300_000) { i -> if (r.nextInt(50) == 0) r.nextInt().toByte() else (i / 1000).toByte() }, // long codes
        )
    }

    @Test
    fun matchesDeflaterAtEveryLevelAndStrategy() {
        for ((k, data) in samples.withIndex()) {
            for (level in listOf(0, 1, 6, 9)) {
                for (strategy in listOf(Deflater.DEFAULT_STRATEGY, Deflater.FILTERED, Deflater.HUFFMAN_ONLY)) {
                    val z = deflate(data, level, strategy)
                    assertContentEquals(data, Inflate.zlib(z, data.size), "sample $k level $level strategy $strategy")
                    val raw = deflate(data, level, strategy, nowrap = true)
                    assertContentEquals(data, Inflate.raw(raw, 0, data.size), "raw sample $k level $level strategy $strategy")
                }
            }
        }
    }

    @Test
    fun brokenStreamsFail() {
        val data = samples[4]
        val z = deflate(data, 6)
        assertFailsWith<IllegalArgumentException> { Inflate.zlib(z, data.size + 1) }
        assertFailsWith<IllegalArgumentException> { Inflate.zlib(z, data.size - 1) }
        assertFailsWith<IllegalArgumentException> { Inflate.zlib(z.copyOf(z.size / 2), data.size) }
        assertFailsWith<IllegalArgumentException> { Inflate.zlib(byteArrayOf(0x12, 0x34, 0, 0), 4) }
    }

    @Test
    fun adler32MatchesJavaUtilZip() {
        for (data in samples) {
            val ref = Adler32().apply { update(data) }.value.toInt()
            kotlin.test.assertEquals(ref, Inflate.adler32(data))
        }
    }

    @Test
    fun corruptionThatKeepsTheSizeFailsTheChecksum() {
        // A stored (level 0) stream: flipping a payload byte still inflates to the right size, so only the
        // Adler-32 trailer can catch it.
        val data = samples[5]
        val z = deflate(data, 0)
        val flipped = z.copyOf().also { it[z.size / 2] = (it[z.size / 2].toInt() xor 0x01).toByte() }
        val e = assertFailsWith<IllegalArgumentException> { Inflate.zlib(flipped, data.size) }
        kotlin.test.assertTrue("Adler-32" in e.message.orEmpty(), e.message)
        // A damaged trailer, and a missing one.
        val badTrailer = z.copyOf().also { it[z.size - 1] = (it[z.size - 1].toInt() xor 0x80).toByte() }
        assertFailsWith<IllegalArgumentException> { Inflate.zlib(badTrailer, data.size) }
        assertFailsWith<IllegalArgumentException> { Inflate.zlib(z.copyOf(z.size - 4), data.size) }
    }
}
