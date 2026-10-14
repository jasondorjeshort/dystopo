package io.github.jasondorjeshort.dystopo.sources.elevation

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** T88 (from T69): the LERC2 decoder refuses headers that would allocate more than a COG block needs, before allocating. */
class LercCapsTest {
    private fun header(width: Int, height: Int, microBlock: Int = 8, numValid: Int = 0, dt: Int = 6, blobSize: Int = 66): ByteArray {
        val out = ArrayList<Byte>()
        fun i32(v: Int) = (0 until 4).forEach { out += (v ushr (8 * it)).toByte() }
        fun f64(v: Double) = v.toRawBits().let { b -> (0 until 8).forEach { out += (b ushr (8 * it)).toByte() } }
        "Lerc2 ".encodeToByteArray().forEach { out += it }
        i32(3) // version (no depth field)
        i32(0) // checksum (never reached: the caps come first)
        i32(height)
        i32(width)
        i32(numValid)
        i32(microBlock)
        i32(blobSize)
        i32(dt)
        f64(0.0)
        f64(0.0)
        f64(0.0)
        while (out.size < 66) out += 0 // the blob holds blobSize bytes
        return out.toByteArray()
    }

    private fun refused(blob: ByteArray): String {
        val e = assertFailsWith<IllegalArgumentException> { Lerc2.decode(blob) }
        return e.message.orEmpty()
    }

    @Test
    fun hugeImagesAreRefusedBeforeAllocating() {
        refused(header(65536, 65536)).let { assertTrue("larger than" in it, it) }
        refused(header(Lerc2.MAX_SIDE + 1, 1)).let { assertTrue("larger than" in it, it) }
        // An int overflow of width * height must not slip under the cap.
        refused(header(46341, 46341)).let { assertTrue("larger than" in it, it) }
    }

    @Test
    fun oddFieldsAreRefused() {
        refused(header(512, 512, microBlock = 100_000)).let { assertTrue("micro block" in it, it) }
        refused(header(512, 512, numValid = 512 * 512 + 1)).let { assertTrue("valid count" in it, it) }
        refused(header(512, 512, dt = 9)).let { assertTrue("data type" in it, it) }
        refused(header(512, 512, blobSize = 3)).let { assertTrue("blob size" in it, it) }
    }

    @Test
    fun aPlausibleHeaderGetsAsFarAsTheChecksum() {
        refused(header(512, 512)).let { assertTrue("checksum" in it, it) }
    }
}
