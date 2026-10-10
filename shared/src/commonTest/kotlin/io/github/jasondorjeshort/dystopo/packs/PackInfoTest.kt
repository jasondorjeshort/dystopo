package io.github.jasondorjeshort.dystopo.packs

import io.github.jasondorjeshort.dystopo.pmtiles.ByteSink
import io.github.jasondorjeshort.dystopo.pmtiles.PmtilesReader
import io.github.jasondorjeshort.dystopo.pmtiles.PmtilesWriter
import io.github.jasondorjeshort.dystopo.pmtiles.TileType
import io.github.jasondorjeshort.dystopo.tiff.ByteRangeSource
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PackInfoTest {
    private class Archive : ByteSink {
        private val chunks = ArrayList<ByteArray>()
        override fun write(bytes: ByteArray) { chunks += bytes }
        fun bytes(): ByteArray {
            val out = ByteArray(chunks.sumOf { it.size })
            var p = 0
            for (c in chunks) { c.copyInto(out, p); p += c.size }
            return out
        }
    }

    private fun source(bytes: ByteArray) = ByteRangeSource { offset, length ->
        bytes.copyOfRange(offset.toInt().coerceAtMost(bytes.size), minOf(bytes.size.toLong(), offset + length).toInt())
    }

    /** A small Rainier-sized archive: z8..z10 tiles over the box, with metadata like `tiles --pmtiles` writes. */
    private fun rainierArchive(type: TileType, metadata: String): ByteArray {
        val w = PmtilesWriter(type)
        for (z in 8..10) {
            val n = 1 shl (z - 8)
            for (x in 0 until n) for (y in 0 until n) w.addTile(z, (41 shl (z - 8)) + x, (90 shl (z - 8)) + y, byteArrayOf(z.toByte(), x.toByte(), y.toByte(), 7))
        }
        val a = Archive()
        w.finish(a, metadata, minLon = -121.95, minLat = 46.70, maxLon = -121.55, maxLat = 47.00, centerZoom = 9)
        return a.bytes()
    }

    @Test
    fun readsBoundsZoomsSizeAndAttributionFromTheArchive() {
        val bytes = rainierArchive(
            TileType.PNG,
            """{"name":"Rainier DEM","attribution":"<a href=\"https://www.usgs.gov/\">&copy; USGS 3DEP</a> &amp; Copernicus &#169;","description":"Terrarium"}""",
        )
        val info = PackArchiveInfo.read("rainier-dem.pmtiles", bytes.size.toLong(), PmtilesReader.open(source(bytes)))
        assertEquals(8, info.minZoom)
        assertEquals(10, info.maxZoom)
        assertEquals(-121.95, info.bounds.minLon, 1e-7)
        assertEquals(47.00, info.bounds.maxLat, 1e-7)
        assertEquals("png", info.tileType)
        assertEquals(1L + 4 + 16, info.addressedTiles)
        assertEquals("© USGS 3DEP & Copernicus ©", info.attribution)
        assertEquals("Rainier DEM", info.title)
        assertEquals(bytes.size.toLong(), info.expectedBytes)
        assertTrue(info.complete)

        // A truncated copy (an interrupted download) reports itself incomplete.
        val cut = PackArchiveInfo.read("x-dem.pmtiles", bytes.size - 3L, PmtilesReader.open(source(bytes)))
        assertFalse(cut.complete)
    }

    @Test
    fun packCombinesDemAndVector() {
        val dem = rainierArchive(TileType.PNG, """{"attribution":"USGS"}""")
        val osm = rainierArchive(TileType.MVT, """{"attribution":"&copy; OpenStreetMap contributors"}""")
        val d = PackArchiveInfo.read("r-dem.pmtiles", dem.size.toLong(), PmtilesReader.open(source(dem)))
        val v = PackArchiveInfo.read("r-outdoors.pmtiles", osm.size.toLong(), PmtilesReader.open(source(osm)))
        val pack = PackInfo("r", d, v)
        assertTrue(pack.usable)
        assertEquals(dem.size.toLong() + osm.size, pack.bytes)
        assertEquals("© OpenStreetMap contributors", v.attribution)
        assertEquals("USGS | © OpenStreetMap contributors", pack.attribution)
        assertEquals(listOf(-121.95, 46.70, -121.55, 47.00), pack.bounds!!.toList().map { kotlin.math.round(it * 100) / 100.0 })
        val json = pack.json(active = true)
        assertEquals(true, json["active"])
        assertEquals(8, json["minZoom"])
        assertFalse(PackInfo("r", null, v).usable)
        assertNull(PackInfo("r", null, null).bounds)
    }

    @Test
    fun plainTextDecodesEntitiesAndStripsTags() {
        assertEquals("a < b & c", PackArchiveInfo.plainText("a &lt; b &amp; c"))
        assertEquals("© X", PackArchiveInfo.plainText("<b>&copy;</b>\n  X"))
        assertEquals("&bogus; &#xZZ;", PackArchiveInfo.plainText("&bogus; &#xZZ;"))
        assertEquals("🗻", PackArchiveInfo.plainText("&#128507;"))
        assertEquals("", PackArchiveInfo.plainText(null))
    }

    @Test
    fun fitZoomMatchesTheMercatorScale() {
        // A box one 512-px tile wide at zoom z fits a 512-px viewport exactly at z.
        val z = 10
        val span = 360.0 / (1 shl z)
        val b = PackBounds(0.0, -0.1, span, 0.1)
        assertEquals(z.toDouble(), b.fitZoom(512.0, 1e6), 1e-9)
        // Height-limited: a tall box chooses the smaller zoom.
        val tall = PackBounds(-121.8, 46.0, -121.7, 47.5)
        assertTrue(tall.fitZoom(1000.0, 1000.0) < tall.fitZoom(1000.0, 1e6))
        // Padding zooms out.
        assertTrue(b.fitZoom(512.0, 512.0, paddingPx = 64.0) < b.fitZoom(512.0, 512.0))
        // The Rainier region at phone size (411 x 914 dp) is width-limited, about z8.9.
        val rainier = PackBounds(-122.0, 46.65, -121.45, 47.05)
        val fz = rainier.fitZoom(411.0, 914.0, paddingPx = 24.0)
        assertTrue(abs(fz - 8.9) < 0.3, "fit zoom $fz")
    }
}
