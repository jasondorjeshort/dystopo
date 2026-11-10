package io.github.jasondorjeshort.dystopo.tilebuild

import com.onthegomap.planetiler.VectorTile
import com.onthegomap.planetiler.geo.GeoUtils
import org.locationtech.jts.geom.Coordinate
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T274: no `water` ring edge lies on the antimeridian in the committed world overview (see [WorldSeam]). The file read is
 * `tilebuild/world/world-outdoors.pmtiles`, or the one `DYSTOPO_WORLD_FILE` names (to check another build: run this test
 * with `--rerun`; the T264 file fails it with four edges, z0 and z1).
 */
class WorldOverviewSeamTest {
    private fun worldFile(): File = System.getenv("DYSTOPO_WORLD_FILE")?.takeIf { it.isNotBlank() }?.let(::File)
        ?: File("world/world-outdoors.pmtiles").takeIf { it.isFile }
        ?: File("tilebuild/world/world-outdoors.pmtiles")

    @Test
    fun noWaterEdgeOnTheAntimeridianFromZ0ToZ5() {
        val file = worldFile()
        assertTrue(file.isFile, "missing $file")
        val edges = WorldSeam.edges(file, 0, 5)
        assertEquals(emptyList(), edges.map { it.toString() }, "$file: water rings closed along 180")
    }

    /** One `water` polygon tile from tile-pixel corners (0 to 256, the buffer beyond). */
    private fun tile(vararg xy: Double): ByteArray {
        val coords = xy.toList().chunked(2).map { (x, y) -> Coordinate(x, y) }
        val polygon = GeoUtils.JTS_FACTORY.createPolygon((coords + coords.first()).toTypedArray())
        val feature = VectorTile.Feature("water", 1, VectorTile.encodeGeometry(polygon), mapOf<String, Any>())
        return VectorTile().addLayerFeatures("water", listOf(feature)).encode()
    }

    @Test
    fun aRingClosedOnTheWestEdgeOfTheWestColumnIsFound() {
        val closed = tile(0.0, 20.0, 100.0, 20.0, 100.0, 200.0, 0.0, 200.0)
        assertEquals(listOf(WorldSeam.Edge(1, 0, 1, 0, 320, 3200)), WorldSeam.edges(1, 0, 1, closed))
        // The same ring in the east column is an ordinary interior edge (Planetiler fills in from the neighbour).
        assertEquals(emptyList(), WorldSeam.edges(1, 1, 1, closed))
    }

    @Test
    fun aRingClosedOnTheEastEdgeOfTheEastColumnIsFound() {
        val closed = tile(100.0, 20.0, 256.0, 20.0, 256.0, 200.0, 100.0, 200.0)
        assertEquals(listOf(WorldSeam.Edge(1, 1, 0, 4096, 320, 3200)), WorldSeam.edges(1, 1, 0, closed))
        assertEquals(listOf(WorldSeam.Edge(0, 0, 0, 4096, 320, 3200)), WorldSeam.edges(0, 0, 0, closed))
    }

    @Test
    fun aRingRunningIntoTheBufferIsFine() {
        val buffered = tile(-4.0, 20.0, 260.0, 20.0, 260.0, 200.0, -4.0, 200.0)
        assertEquals(emptyList(), WorldSeam.edges(0, 0, 0, buffered))
    }
}
