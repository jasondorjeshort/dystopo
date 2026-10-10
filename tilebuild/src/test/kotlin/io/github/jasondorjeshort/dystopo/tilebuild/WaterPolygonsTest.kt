package io.github.jasondorjeshort.dystopo.tilebuild

import org.geotools.data.shapefile.ShapefileDataStore
import org.locationtech.jts.geom.Geometry
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** T50: the per-box water polygon choice, on a small synthetic shapefile laid out like OSM's split file. */
class WaterPolygonsTest {
    /** Axis-aligned squares in EPSG:3857 metres: (minX, minY, size). */
    private val squares = listOf(
        Triple(-13_900_000.0, 5_900_000.0, 50_000.0), // off the Washington coast
        Triple(-13_850_000.0, 5_850_000.0, 40_000.0),
        Triple(700_000.0, 5_700_000.0, 30_000.0), // Mediterranean-ish, far from both boxes
    )

    private fun polygonRecord(i: Int, x: Double, y: Double, s: Double): ByteArray {
        val points = listOf(x to y, x to y + s, x + s to y + s, x + s to y, x to y) // clockwise outer ring
        val content = ByteBuffer.allocate(44 + 4 + 16 * points.size).order(ByteOrder.LITTLE_ENDIAN)
        content.putInt(5).putDouble(x).putDouble(y).putDouble(x + s).putDouble(y + s).putInt(1).putInt(points.size).putInt(0)
        for ((px, py) in points) content.putDouble(px).putDouble(py)
        val c = content.array()
        return ByteBuffer.allocate(8 + c.size).order(ByteOrder.BIG_ENDIAN).putInt(i + 1).putInt(c.size / 2).put(c).array()
    }

    private fun header(totalBytes: Int): ByteArray = ByteBuffer.allocate(100).apply {
        order(ByteOrder.BIG_ENDIAN).putInt(0, 9994).putInt(24, totalBytes / 2)
        order(ByteOrder.LITTLE_ENDIAN).putInt(28, 1000).putInt(32, 5)
        putDouble(36, squares.minOf { it.first }); putDouble(44, squares.minOf { it.second })
        putDouble(52, squares.maxOf { it.first + it.third }); putDouble(60, squares.maxOf { it.second + it.third })
    }.array()

    private fun dbf(): ByteArray {
        // One numeric field "id" (N, 5 wide) per row.
        val headerLen = 32 + 32 + 1
        val recordLen = 1 + 5
        val b = ByteBuffer.allocate(headerLen + squares.size * recordLen + 1).order(ByteOrder.LITTLE_ENDIAN)
        b.put(0, 3).putInt(4, squares.size).putShort(8, headerLen.toShort()).putShort(10, recordLen.toShort())
        "id".toByteArray().copyInto(b.array(), 32)
        b.put(32 + 11, 'N'.code.toByte()).put(32 + 16, 5)
        b.put(64, 0x0D)
        for (i in squares.indices) {
            val row = " " + (i + 1).toString().padStart(5)
            row.toByteArray().copyInto(b.array(), headerLen + i * recordLen)
        }
        b.put(headerLen + squares.size * recordLen, 0x1A)
        return b.array()
    }

    private fun zip(dir: File): File {
        val shp = ByteArrayOutputStream()
        val shx = ByteArrayOutputStream()
        for ((i, sq) in squares.withIndex()) {
            val rec = polygonRecord(i, sq.first, sq.second, sq.third)
            shx.write(ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putInt((100 + shp.size()) / 2).putInt((rec.size - 8) / 2).array())
            shp.write(rec)
        }
        val prj = """PROJCS["WGS 84 / Pseudo-Mercator",GEOGCS["WGS 84",DATUM["WGS_1984",SPHEROID["WGS 84",6378137,298.257223563]],PRIMEM["Greenwich",0],UNIT["degree",0.0174532925199433]],PROJECTION["Mercator_1SP"],PARAMETER["central_meridian",0],PARAMETER["scale_factor",1],PARAMETER["false_easting",0],PARAMETER["false_northing",0],UNIT["metre",1]]"""
        val file = File(dir, "water-polygons-split-3857.zip")
        ZipOutputStream(file.outputStream()).use { z ->
            fun put(name: String, bytes: ByteArray) { z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() }
            put(WaterPolygons.SHP, header(100 + shp.size()) + shp.toByteArray())
            put(WaterPolygons.SHX, header(100 + shx.size()) + shx.toByteArray())
            put(WaterPolygons.DBF, dbf())
            put(WaterPolygons.PRJ, prj.toByteArray())
            put(WaterPolygons.CPG, "UTF-8".toByteArray())
        }
        return file
    }

    @Test
    fun inlandBoxSkipsCoastalBoxGetsAClippedCopy() {
        val dir = Files.createTempDirectory("water").toFile()
        try {
            val zip = zip(dir)
            val index = WaterPolygons.index(zip, dir)
            assertEquals(3, index.size)
            assertEquals(-13_900_000.0, index[0].minX)
            assertEquals(5_730_000.0, index[2].maxY)
            assertTrue(File(dir, WaterPolygons.INDEX_NAME).isFile)

            // Paradise, Mount Rainier: inland.
            val inland = WaterPolygons.forBox(zip, dir, doubleArrayOf(-121.78, 46.76, -121.70, 46.82))
            assertIs<WaterPolygons.Skip>(inland)

            // A box over the first two squares: a two-record shapefile GeoTools reads back with the same shapes.
            val (wx, sy) = Pair(-13_920_000.0, 5_840_000.0)
            val lon0 = wx / 6378137.0 * 180 / Math.PI
            val lat0 = Math.toDegrees(2 * Math.atan(Math.exp(sy / 6378137.0)) - Math.PI / 2)
            val box = doubleArrayOf(lon0, lat0, lon0 + 1.0, lat0 + 1.0)
            val clipped = assertIs<WaterPolygons.Clipped>(WaterPolygons.forBox(zip, dir, box))
            assertEquals(2, clipped.used)
            val store = ShapefileDataStore(clipped.shp.toURI().toURL())
            try {
                val features = store.featureSource.features.features()
                val read = ArrayList<Pair<Geometry, Any?>>()
                features.use { while (it.hasNext()) it.next().let { f -> read += (f.defaultGeometry as Geometry) to f.getAttribute("id") } }
                assertEquals(2, read.size)
                assertEquals(listOf(1L, 2L), read.map { (it.second as Number).toLong() })
                assertEquals(50_000.0 * 50_000.0, read[0].first.area, 1.0)
                assertEquals(40_000.0 * 40_000.0, read[1].first.area, 1.0)
            } finally {
                store.dispose()
            }
            // The same box again comes from the cache.
            assertTrue(assertIs<WaterPolygons.Clipped>(WaterPolygons.forBox(zip, dir, box)).cached)
            // No zip yet: the full file, as before.
            assertIs<WaterPolygons.Full>(WaterPolygons.forBox(File(dir, "missing.zip"), dir, box))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun attributionDropsTheWaterPolygonsOnlyWhenSkipped() {
        assertTrue("osm-water-polygons" in Profiles.outdoorsSourceIds(false))
        assertTrue("osm-water-polygons" !in Profiles.outdoorsSourceIds(false, waterPolygons = false))
        assertTrue("osm" in Profiles.outdoorsSourceIds(false, waterPolygons = false))
    }
}
