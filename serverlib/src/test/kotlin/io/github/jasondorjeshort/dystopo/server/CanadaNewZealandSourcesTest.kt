package io.github.jasondorjeshort.dystopo.server

import io.github.jasondorjeshort.dystopo.geo.BoundingBox
import io.github.jasondorjeshort.dystopo.geo.LambertConformalConic
import io.github.jasondorjeshort.dystopo.geo.MercatorWindow
import io.github.jasondorjeshort.dystopo.geo.Utm
import io.github.jasondorjeshort.dystopo.sources.SourceRegistry
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Offline checks of the Canada (HRDEM) and New Zealand (LINZ) sources: grids, URLs, gates, the LERC decoder. */
class CanadaNewZealandSourcesTest {
    private val rainier = MercatorWindow.forBbox(BoundingBox(-121.86, 46.78, -121.66, 46.93), 13)

    @Test
    fun hrdemTilesAndUrls() {
        assertEquals(56, CanadaSource.TILES_1M.size)
        assertEquals(64, CanadaSource.TILES_2M.size)
        assertTrue(CanadaSource.TILES_2M.containsAll(CanadaSource.TILES_1M))
        // Mount Garibaldi is in tile 2_4 (EPSG:3979 x -2000 to -1500 km, y 500 to 1000 km).
        val p = LambertConformalConic.CANADA_ATLAS.forward(49.8506, -123.0047)
        assertEquals(2 to 4, CanadaSource.tileOf(2, p.easting, p.northing))
        assertEquals(2 to 4, CanadaSource.tileOf(1, p.easting, p.northing))
        assertEquals(
            "https://canelevation-dem.s3.ca-central-1.amazonaws.com/hrdem-mosaic-2m/2_4-mosaic-2m-dtm.tif",
            CanadaSource.tileUrl(2, 2, 4),
        )
        // 5_5 exists only at 2 m.
        val x = -0.5 * CanadaSource.TILE_METERS
        val y = 2.5 * CanadaSource.TILE_METERS
        assertEquals(5 to 5, CanadaSource.tileOf(2, x, y))
        assertNull(CanadaSource.tileOf(1, x, y))
    }

    @Test
    fun nztmMatchesTheSheetCornersLinzPublishes() {
        // NZTopo50 sheet BX15 spans NZTM x 1348000 to 1372000, y 5154000 to 5190000; its STAC item gives the
        // footprint lon 169.8713682 to 170.1840917, lat -43.7319737 to -43.4005388.
        fun inverse(x: Double, y: Double): Pair<Double, Double> = Utm.inverse(x - 1_100_000.0, y, 59, north = false).let { it.first to it.second + 2.0 }
        val corners = listOf(1_348_000.0 to 5_154_000.0, 1_348_000.0 to 5_190_000.0, 1_372_000.0 to 5_154_000.0, 1_372_000.0 to 5_190_000.0)
            .map { (x, y) -> inverse(x, y) }
        assertEquals(169.8713682, corners.minOf { it.second }, 1e-6)
        assertEquals(170.1840917, corners.maxOf { it.second }, 1e-6)
        assertEquals(-43.7319737, corners.minOf { it.first }, 1e-6)
        assertEquals(-43.4005388, corners.maxOf { it.first }, 1e-6)
        // And forward: the NZTM origin (173 E on the equator) is 1600000 E, 10000000 N.
        val o = NewZealandSource.nztm(0.0, 173.0)
        assertEquals(1_600_000.0, o.easting, 1e-6)
        assertEquals(10_000_000.0, o.northing, 1e-6)
    }

    @Test
    fun nzSheetCodesFollowTheTopo50Grid() {
        assertEquals(424, NewZealandSource.SHEETS.size)
        val aoraki = NewZealandSource.nztm(-43.5950, 170.1418)
        assertEquals("BX15", NewZealandSource.sheetOf(aoraki.easting, aoraki.northing))
        assertEquals("AT24", NewZealandSource.sheetCode(1_564_000.0, 6_198_000.0 - 1))
        assertEquals("CJ10", NewZealandSource.sheetCode(1_228_000.0, 4_794_000.0 - 1))
        // Row letters skip I and O: BH is followed by BJ.
        assertEquals("BJ28", NewZealandSource.sheetCode(988_000.0 + 28 * 24_000.0, 6_810_000.0 - 32 * 36_000.0 - 1))
        // The open sea west of Fiordland has a code but no sheet.
        val sea = NewZealandSource.nztm(-45.5, 166.0)
        assertNull(NewZealandSource.sheetOf(sea.easting, sea.northing))
        assertEquals(
            "https://nz-elevation.s3.ap-southeast-2.amazonaws.com/new-zealand/new-zealand/dem_1m/2193/BX15.tiff",
            NewZealandSource.sheetUrl("BX15"),
        )
    }

    @Test
    fun outsideCoverageFetchesNothing() {
        for (source in listOf(CanadaSource(kotlin.io.path.createTempDirectory("ca").toFile()), NewZealandSource(kotlin.io.path.createTempDirectory("nz").toFile()))) {
            val r = source.fetch(rainier)
            assertEquals(1L, r.counters["outsideCoverage"], source.id)
            assertTrue(r.grid.values.all { it.isNaN() })
            assertEquals(emptyList(), r.provenance)
        }
    }

    @Test
    fun coarseWindowsAreSkipped() {
        val coast = MercatorWindow.forBbox(BoundingBox(-123.4, 49.6, -122.6, 50.1), 9)
        assertEquals(1L, CanadaSource(kotlin.io.path.createTempDirectory("ca").toFile()).fetch(coast).counters["skippedCoarse"])
        val alps = MercatorWindow.forBbox(BoundingBox(169.8, -43.8, 170.4, -43.4), 9)
        assertEquals(1L, NewZealandSource(kotlin.io.path.createTempDirectory("nz").toFile()).fetch(alps).counters["skippedCoarse"])
    }

    @Test
    fun registryHasEveryIdTheSourcesCredit() {
        for (source in listOf(CanadaSource(), NewZealandSource(), BestSource())) {
            for (id in source.registryIds) assertTrue(SourceRegistry.builtIn[id] != null, "$id missing from sources.json")
        }
        assertEquals("OGL-Canada-2.0", SourceRegistry.builtIn["hrdem"]?.license)
        assertEquals("CC-BY-4.0", SourceRegistry.builtIn["linz-dem"]?.license)
        assertTrue("live-hrdem" == LiveTiles.labelFor(listOf("hrdem", "cgg2013a", "egm2008")))
        assertTrue("live-linz" == LiveTiles.labelFor(listOf("linz-dem", "nzgeoid2016", "egm2008")))
    }

    @Test
    fun lercDecodesARealLinzTile() {
        // The coarsest overview (187 x 281 px in one 512 px tile, 10 cm maximum error) of LINZ's 1 m DEM sheet AS21,
        // the far north of Northland: LINZ New Zealand LiDAR 1m DEM, CC BY 4.0. Mostly sea, so it has a validity
        // mask; inside the image, sea is the GeoTIFF no-data value -9999.
        val blob = javaClass.getResourceAsStream("/lerc/linz-as21-overview7.lerc")!!.readBytes()
        val image = Lerc2.decode(blob)
        assertEquals(512, image.width)
        assertEquals(512, image.height)
        var valid = 0
        var land = 0
        var max = Float.NEGATIVE_INFINITY
        for (row in 0 until 512) for (col in 0 until 512) {
            val v = image.values[row * 512 + col]
            val inside = row < 281 && col < 187
            assertEquals(inside, !v.isNaN(), "pixel $row,$col")
            if (v.isNaN()) continue
            valid++
            if (v != -9999f) {
                land++
                max = maxOf(max, v)
                assertTrue(v > -20f && v < 175f, "height $v at $row,$col")
            }
        }
        assertEquals(187 * 281, valid)
        assertEquals(515, land) // land pixels at 128 m (the rest of the sheet is sea)
        assertEquals(171.56293f, max, 0.001f)
        // The bytes GeoTiff gets: little-endian floats, padded to the tile.
        val bytes = Lerc2.decodeToFloatBytes(blob, 512 * 512 * 4)
        assertEquals(image.values[0].toRawBits(), (bytes[0].toInt() and 0xFF) or ((bytes[1].toInt() and 0xFF) shl 8) or ((bytes[2].toInt() and 0xFF) shl 16) or (bytes[3].toInt() shl 24))
        // A flipped byte fails the checksum instead of decoding garbage.
        val broken = blob.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        assertFailsWith<IllegalArgumentException> { Lerc2.decode(broken) }
    }

    /** Little-endian LSB-first bit packer, stored as LERC trims it (the last word only as far as it holds bits). */
    private fun stuff(values: IntArray, bits: Int): ByteArray {
        val total = values.size * bits
        val words = (total + 31) / 32
        val packed = LongArray(words)
        for ((i, v) in values.withIndex()) {
            val bit = i * bits
            val w = bit / 32
            val s = bit % 32
            packed[w] = packed[w] or ((v.toLong() shl s) and 0xFFFFFFFFL)
            if (s + bits > 32) packed[w + 1] = packed[w + 1] or (v.toLong() ushr (32 - s))
        }
        val out = ByteArrayOutputStream()
        for (w in packed) for (k in 0 until 4) out.write(((w ushr (8 * k)) and 0xFF).toInt())
        val tail = ((total and 31) + 7) shr 3
        val stored = words * 4 - (if (tail > 0) 4 - tail else 0)
        return out.toByteArray().copyOf(stored)
    }

    private fun ByteArrayOutputStream.i32(v: Int) { for (k in 0 until 4) write((v ushr (8 * k)) and 0xFF) }
    private fun ByteArrayOutputStream.f64(v: Double) { val b = v.toRawBits(); for (k in 0 until 8) write(((b ushr (8 * k)) and 0xFF).toInt()) }
    private fun ByteArrayOutputStream.f32(v: Float) = i32(v.toRawBits())

    @Test
    fun lercDecodesBitStuffedAndLookupTableBlocks() {
        // An 8 x 4 image in two 4 x 4 micro blocks (version 4, float, max error 0.5 so each step is 1 m):
        // block one offset 100 with 3-bit values 0..7; block two offset 200 (stored as a short) through a
        // lookup table {5, 9}.
        val body = ByteArrayOutputStream()
        body.i32(0) // no mask: all valid
        body.f32(100f) // band min (version 4)
        body.f32(209f) // band max
        body.write(0) // not one sweep
        body.write(1) // block 1: bit-stuffed, float offset, check bits for column 0
        body.f32(100f)
        body.write(0x80 or 3) // one count byte, no table, 3 bits
        body.write(16)
        body.write(stuff(IntArray(16) { it % 8 }, 3))
        body.write(0x40 or 1) // block 2: bit-stuffed, short offset
        body.write(200 and 0xFF)
        body.write(200 ushr 8)
        body.write(0x80 or 0x20 or 4) // one count byte, lookup table, 4-bit table entries
        body.write(16)
        body.write(3) // table of 2 entries plus the implicit 0
        body.write(stuff(intArrayOf(5, 9), 4))
        val indices = IntArray(16) { it % 3 }
        body.write(stuff(indices, 2))
        val bodyBytes = body.toByteArray()

        val head = ByteArrayOutputStream()
        head.write("Lerc2 ".encodeToByteArray())
        head.i32(4)
        head.i32(0) // checksum, filled below
        head.i32(4) // rows
        head.i32(8) // cols
        head.i32(1) // depth
        head.i32(32) // valid
        head.i32(4) // micro block
        val headerSize = head.size() + 4 * 2 + 8 * 3
        head.i32(headerSize + bodyBytes.size) // blob size
        head.i32(6) // float
        head.f64(0.5)
        head.f64(100.0)
        head.f64(209.0)
        val blob = head.toByteArray() + bodyBytes
        val sum = Lerc2.fletcher32(blob, 14, blob.size)
        for (k in 0 until 4) blob[10 + k] = (sum ushr (8 * k)).toByte()

        val image = Lerc2.decode(blob)
        val lut = intArrayOf(0, 5, 9)
        for (r in 0 until 4) for (c in 0 until 4) {
            assertEquals(100f + (r * 4 + c) % 8, image.values[r * 8 + c], "block 1 at $r,$c")
            assertEquals(200f + lut[(r * 4 + c) % 3], image.values[r * 8 + 4 + c], "block 2 at $r,$c")
        }
    }

    @Test
    fun lercRefusesWhatItCannotRead() {
        assertFailsWith<IllegalArgumentException> { Lerc2.decode("not lerc at all, no".encodeToByteArray()) }
        val v2 = ByteArrayOutputStream().apply { write("Lerc2 ".encodeToByteArray()); i32(2); repeat(10) { i32(0) } }.toByteArray()
        assertTrue("version 2" in assertFailsWith<IllegalArgumentException> { Lerc2.decode(v2) }.message!!)
    }
}
