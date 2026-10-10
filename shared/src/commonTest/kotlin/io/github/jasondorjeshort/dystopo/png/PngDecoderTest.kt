package io.github.jasondorjeshort.dystopo.png

import io.github.jasondorjeshort.dystopo.dem.Terrarium
import io.github.jasondorjeshort.dystopo.tiff.Decompressor
import io.github.jasondorjeshort.dystopo.tiles.ArgbImage
import io.github.jasondorjeshort.dystopo.tiles.ContourBuildCancelled
import io.github.jasondorjeshort.dystopo.tiles.ContourTileOptions
import io.github.jasondorjeshort.dystopo.tiles.ContourTiles
import io.github.jasondorjeshort.dystopo.tiles.DemPyramid
import io.github.jasondorjeshort.dystopo.tiles.ImageDecoder
import kotlin.math.hypot
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [PngDecoder] against PNGs written in-test by [TinyPng]: zlib with stored (uncompressed) deflate
 * blocks, which [storedInflate] reads back, so the test stays pure Kotlin. Real zlib streams written
 * by ImageIO are covered in jvmTest (PngDecoderJvmTest).
 */
class PngDecoderTest {
    private val decoder = PngDecoder(TinyPng.storedInflate)

    private fun randomPixels(w: Int, h: Int, alpha: Boolean, seed: Int): IntArray {
        val r = Random(seed)
        return IntArray(w * h) {
            val a = if (alpha) r.nextInt(256) else 0xFF
            (a shl 24) or r.nextInt(0x1000000)
        }
    }

    @Test
    fun everyFilterRoundTripsForRgbAndRgba() {
        for (alpha in listOf(false, true)) {
            for (filter in 0..4) {
                val argb = randomPixels(13, 9, alpha, seed = filter * 7 + if (alpha) 1 else 0)
                val png = TinyPng.encode(13, 9, argb, alpha, filters = intArrayOf(filter))
                val p = decoder.decode(png)
                assertEquals(13, p.width)
                assertEquals(9, p.height)
                assertEquals(if (alpha) 4 else 3, p.channels)
                assertContentEquals(argb, p.argb(), "alpha=$alpha filter=$filter")
            }
        }
    }

    @Test
    fun mixedFiltersPerRowAndSmoothTerrain() {
        // Smooth data, where sub/up/average/Paeth predict well and carry across rows.
        val w = 64
        val h = 40
        val argb = IntArray(w * h) { i -> Terrarium.encode(1000f + (i % w) * 3.25f + (i / w) * 7.5f) }
        val png = TinyPng.encode(w, h, argb, alpha = false, filters = intArrayOf(4, 1, 2, 3, 0, 4, 4, 3))
        assertContentEquals(argb, decoder.decode(png).argb())
        val t = decoder.terrarium(png)
        assertContentEquals(FloatArray(argb.size) { Terrarium.elevation(argb[it]) }, t.elevations)
    }

    @Test
    fun kotlinInflateReadsStoredBlocksToo() {
        // Stored blocks over 64 KiB (several blocks) through the pure-Kotlin Inflate.
        val argb = randomPixels(200, 120, alpha = true, seed = 5)
        val png = TinyPng.encode(200, 120, argb, alpha = true, filters = intArrayOf(0, 1, 2, 3, 4))
        assertContentEquals(argb, PngDecoder(Inflate.zlib).decode(png).argb())
    }

    @Test
    fun imageDataSplitAcrossSeveralIdatChunks() {
        val argb = randomPixels(30, 20, alpha = true, seed = 3)
        val png = TinyPng.encode(30, 20, argb, alpha = true, filters = intArrayOf(0, 1, 2, 3, 4), idatChunk = 97)
        assertContentEquals(argb, decoder.decode(png).argb())
    }

    @Test
    fun unsupportedFormatsAreReportedAsSuch() {
        val argb = randomPixels(4, 4, alpha = false, seed = 1)
        val good = TinyPng.encode(4, 4, argb, alpha = false)
        fun patched(index: Int, value: Int) = good.copyOf().also { it[index] = value.toByte() }
        // IHDR data starts at byte 16: width, height, bit depth (24), colour type (25), ..., interlace (28).
        assertFailsWith<UnsupportedPngException> { decoder.decode(patched(25, 3)) } // palette
        assertFailsWith<UnsupportedPngException> { decoder.decode(patched(25, 0)) } // grey
        assertFailsWith<UnsupportedPngException> { decoder.decode(patched(24, 16)) } // 16-bit
        assertFailsWith<UnsupportedPngException> { decoder.decode(patched(28, 1)) } // Adam7
        // Broken data is an error, but not "unsupported": a fallback would not help.
        val notPng = assertFailsWith<IllegalArgumentException> { decoder.decode(ByteArray(40)) }
        assertTrue(notPng !is UnsupportedPngException)
        val badFilter = TinyPng.encode(4, 4, argb, alpha = false, filters = intArrayOf(5))
        val e = assertFailsWith<IllegalArgumentException> { decoder.decode(badFilter) }
        assertTrue(e !is UnsupportedPngException && "filter" in e.message!!, e.message)
        assertFailsWith<IllegalArgumentException> { decoder.decode(good.copyOf(good.size - 30)) }
    }

    @Test
    fun terrariumDecoderFallsBackForUnsupportedPngs() {
        val argb = IntArray(16) { Terrarium.encode(100f + it) }
        val good = TinyPng.encode(4, 4, argb, alpha = false)
        val palette = good.copyOf().also { it[25] = 3 }
        var fallbackCalls = 0
        val fallback = ImageDecoder { fallbackCalls++; ArgbImage(4, 4, argb) }
        val d = TerrariumPngDecoder(TinyPng.storedInflate, fallback)
        assertContentEquals(FloatArray(16) { 100f + it }, d.elevations(good, 4, 4))
        assertEquals(0, fallbackCalls)
        assertContentEquals(FloatArray(16) { 100f + it }, d.elevations(palette, 4, 4))
        assertContentEquals(argb, d.decode(palette).argb)
        assertEquals(2, fallbackCalls)
        assertEquals(2L, d.fallbacks)
        assertFailsWith<UnsupportedPngException> { TerrariumPngDecoder(TinyPng.storedInflate).elevations(palette, 4, 4) }
        assertFailsWith<IllegalArgumentException> { d.elevations(good, 8, 8) }
    }

    /** A cone, so a contour tile has many levels. */
    private fun coneTile(z: Int, x: Int, y: Int): IntArray {
        val world = 256.0 * (1L shl z)
        return IntArray(256 * 256) { i ->
            val u = (x * 256 + i % 256 + 0.5) / world - 0.70
            val v = (y * 256 + i / 256 + 0.5) / world - 0.35
            Terrarium.encode((4000.0 - hypot(u, v) * 4.0e6).toFloat().coerceAtLeast(0f))
        }
    }

    @Test
    fun demPyramidGivesTheSameWindowThroughEitherDecoderPath() {
        val png = HashMap<String, ByteArray>()
        fun bytes(z: Int, x: Int, y: Int) = png.getOrPut("$z/$x/$y") { TinyPng.encode(256, 256, coneTile(z, x, y), alpha = false, filters = intArrayOf(4, 1, 2, 3, 0)) }
        val argbPath = DemPyramid(10, 12, ImageDecoder { b -> val p = decoder.decode(b); ArgbImage(p.width, p.height, p.argb()) }) { z, x, y -> bytes(z, x, y) }
        val direct = TerrariumPngDecoder(TinyPng.storedInflate)
        val elevPath = DemPyramid(10, 12, direct) { z, x, y -> bytes(z, x, y) }
        val x0 = (0.70 * 256 * 4096).toInt() - 300
        val y0 = (0.35 * 256 * 4096).toInt() - 200
        val a = argbPath.window(12, x0, y0, 600, 400)
        val b = elevPath.window(12, x0, y0, 600, 400)
        assertContentEquals(a.values, b.values)
        assertTrue(elevPath.tilesDecoded > 0 && elevPath.decodeMs >= 0.0 && elevPath.maxDecodeMs <= elevPath.decodeMs)
    }

    @Test
    fun cancellableBuildMatchesAndStopsWhenAsked() {
        val dem = DemPyramid(10, 13, TerrariumPngDecoder(TinyPng.storedInflate)) { z, x, y ->
            TinyPng.encode(256, 256, coneTile(z, x, y), alpha = false)
        }
        val options = ContourTileOptions()
        val z = 12
        val x = (0.70 * 4096).toInt()
        val y = (0.35 * 4096).toInt()
        val plan = ContourTiles.plan(z, x, y, options, dem.tileSize)
        val grid = ContourTiles.sample(dem, plan)
        val plain = ContourTiles.build(grid, plan, options)
        var checks = 0
        val polled = ContourTiles.build(grid, plan, options) { checks++; false }
        // Tracing level groups gives the same tile as one pass over every level.
        assertContentEquals(plain.bytes, polled.bytes)
        assertTrue(checks > 5, "cancel polled $checks times")
        assertTrue(plain.lines > 0)
        var n = 0
        assertFailsWith<ContourBuildCancelled> { ContourTiles.build(grid, plan, options) { ++n >= 3 } }
        assertEquals(3, n)
    }
}

/** A minimal PNG writer for tests: 8-bit RGB/RGBA, chosen filter per row (cycling), stored deflate blocks. */
object TinyPng {
    fun encode(w: Int, h: Int, argb: IntArray, alpha: Boolean, filters: IntArray = intArrayOf(0), idatChunk: Int = Int.MAX_VALUE): ByteArray {
        val bpp = if (alpha) 4 else 3
        val rowBytes = w * bpp
        val raw = ByteArray(rowBytes * h)
        for (i in 0 until w * h) {
            val c = argb[i]
            raw[i * bpp] = (c shr 16).toByte()
            raw[i * bpp + 1] = (c shr 8).toByte()
            raw[i * bpp + 2] = c.toByte()
            if (alpha) raw[i * bpp + 3] = (c ushr 24).toByte()
        }
        val filtered = ByteArray((rowBytes + 1) * h)
        for (y in 0 until h) {
            val f = filters[y % filters.size]
            filtered[y * (rowBytes + 1)] = f.toByte()
            for (i in 0 until rowBytes) {
                val x = raw[y * rowBytes + i].toInt() and 0xFF
                val a = if (i >= bpp) raw[y * rowBytes + i - bpp].toInt() and 0xFF else 0
                val b = if (y > 0) raw[(y - 1) * rowBytes + i].toInt() and 0xFF else 0
                val c = if (y > 0 && i >= bpp) raw[(y - 1) * rowBytes + i - bpp].toInt() and 0xFF else 0
                val pred = when (f) {
                    1 -> a
                    2 -> b
                    3 -> (a + b) / 2
                    4 -> paeth(a, b, c)
                    else -> 0
                }
                filtered[y * (rowBytes + 1) + 1 + i] = (x - pred).toByte()
            }
        }
        val z = zlibStored(filtered)
        val out = ArrayList<Byte>()
        out += listOf(-119, 80, 78, 71, 13, 10, 26, 10).map { it.toByte() }
        val ihdr = ByteArray(13)
        putInt(ihdr, 0, w)
        putInt(ihdr, 4, h)
        ihdr[8] = 8
        ihdr[9] = if (alpha) 6 else 2
        chunk(out, "IHDR", ihdr)
        var o = 0
        while (o < z.size) {
            val n = minOf(idatChunk, z.size - o)
            chunk(out, "IDAT", z.copyOfRange(o, o + n))
            o += n
        }
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = kotlin.math.abs(p - a)
        val pb = kotlin.math.abs(p - b)
        val pc = kotlin.math.abs(p - c)
        return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
    }

    private fun chunk(out: MutableList<Byte>, type: String, data: ByteArray) {
        val len = ByteArray(4).also { putInt(it, 0, data.size) }
        out += len.toList()
        out += type.encodeToByteArray().toList()
        out += data.toList()
        out += listOf<Byte>(0, 0, 0, 0) // CRC (not checked by the decoder)
    }

    private fun putInt(b: ByteArray, i: Int, v: Int) {
        b[i] = (v ushr 24).toByte()
        b[i + 1] = (v ushr 16).toByte()
        b[i + 2] = (v ushr 8).toByte()
        b[i + 3] = v.toByte()
    }

    /** zlib stream of stored blocks (no compression), with the Adler-32 trailer. */
    private fun zlibStored(data: ByteArray): ByteArray {
        val out = ArrayList<Byte>()
        out += 0x78.toByte()
        out += 0x01.toByte()
        var o = 0
        do {
            val n = minOf(65535, data.size - o)
            val last = o + n >= data.size
            out += (if (last) 1 else 0).toByte()
            out += n.toByte()
            out += (n shr 8).toByte()
            out += n.inv().toByte()
            out += (n.inv() shr 8).toByte()
            for (i in o until o + n) out += data[i]
            o += n
        } while (!last)
        var s1 = 1
        var s2 = 0
        for (b in data) {
            s1 = (s1 + (b.toInt() and 0xFF)) % 65521
            s2 = (s2 + s1) % 65521
        }
        val adler = (s2 shl 16) or s1
        out += listOf((adler ushr 24).toByte(), (adler ushr 16).toByte(), (adler ushr 8).toByte(), adler.toByte())
        return out.toByteArray()
    }

    /** Inflates a zlib stream of stored blocks only (what [encode] writes). */
    val storedInflate: Decompressor = { input, size ->
        val out = ByteArray(size)
        var p = 2
        var o = 0
        while (true) {
            val hdr = input[p].toInt()
            require((hdr shr 1) and 3 == 0) { "test inflater reads stored blocks only" }
            val n = (input[p + 1].toInt() and 0xFF) or ((input[p + 2].toInt() and 0xFF) shl 8)
            input.copyInto(out, o, p + 5, p + 5 + n)
            o += n
            p += 5 + n
            if (hdr and 1 == 1) break
        }
        out
    }
}
