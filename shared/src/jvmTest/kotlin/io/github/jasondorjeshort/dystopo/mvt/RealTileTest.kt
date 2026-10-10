package io.github.jasondorjeshort.dystopo.mvt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Decodes a real tile produced by a mainstream encoder (tippecanoe-style output from the
 * MapLibre demo tileset) to catch anything the synthetic round-trip tests can't.
 *
 * Fixture: `resources/mvt/maplibre-demotiles-z2-x1-y1.pbf` is tile z=2, x=1, y=1 of
 * https://demotiles.maplibre.org/tiles/{z}/{x}/{y}.pbf from https://github.com/maplibre/demotiles
 * (BSD-3-Clause, see `maplibre-demotiles-LICENSE.txt` beside it); the country polygons and
 * centroids are Natural Earth data (public domain). It covers the Americas, so nothing in it
 * relates to the developer.
 */
class RealTileTest {
    private fun fixture(): ByteArray =
        javaClass.getResourceAsStream("/mvt/maplibre-demotiles-z2-x1-y1.pbf")!!.use { it.readAllBytes() }

    @Test
    fun decodesMapLibreDemoTile() {
        val tile = VectorTile.decode(fixture())
        assertEquals(listOf("centroids", "countries", "geolines"), tile.layers.map { it.name })
        for (layer in tile.layers) {
            assertEquals(2, layer.version)
            assertEquals(4096, layer.extent)
        }

        val centroids = tile.layer("centroids")!!
        assertEquals(65, centroids.features.size)
        assertTrue(centroids.features.all { it.type == GeometryType.POINT && it.geometry.vertexCount == 1 })
        assertEquals(listOf("NAME", "ABBREV"), centroids.keys)

        val countries = tile.layer("countries")!!
        assertEquals(78, countries.features.size)
        assertTrue(countries.features.all { it.type == GeometryType.POLYGON })
        assertEquals(listOf("fid", "ADM0_A3", "NAME", "ABBREV", "CONTINENT"), countries.keys)
        for (f in countries.features) {
            assertTrue(f.properties["fid"] is Value.UintValue)
            assertTrue(f.properties["NAME"] is Value.StringValue)
            val polygons = (f.geometry as Geometry.Polygons).polygons
            assertTrue(polygons.isNotEmpty(), "feature ${f.id} has no polygon")
            for (p in polygons) {
                assertTrue(signedDoubleArea(p.exterior) > 0)
                assertTrue(p.interiors.all { signedDoubleArea(it) < 0 })
            }
        }
        val peru = countries.features.first { it.properties["NAME"] == Value.StringValue("Peru") }
        assertEquals(2, (peru.geometry as Geometry.Polygons).polygons.size)
        assertEquals("South America", peru.properties["CONTINENT"]!!.asText())

        val geolines = tile.layer("geolines")!!
        assertEquals(4, geolines.features.size)
        assertTrue(geolines.features.all { it.type == GeometryType.LINESTRING })

        assertEquals(20891, tile.layers.sumOf { l -> l.features.sumOf { it.geometry.vertexCount } })
    }

    @Test
    fun realTileSurvivesReEncoding() {
        val tile = VectorTile.decode(fixture())
        val again = VectorTile.decode(MvtEncoder.encode(tile))
        assertEquals(tile, again)
    }
}
