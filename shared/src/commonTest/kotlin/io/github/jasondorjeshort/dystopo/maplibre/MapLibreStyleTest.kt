package io.github.jasondorjeshort.dystopo.maplibre

import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import io.github.jasondorjeshort.dystopo.style.DefaultOsmStyle
import io.github.jasondorjeshort.dystopo.style.StyleLayer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MapLibreStyleTest {
    private val sources = MapLibreSources(
        demTiles = "http://127.0.0.1:1/dem/{z}/{x}/{y}.png", demMinZoom = 8, demMaxZoom = 14,
        contourTiles = "http://127.0.0.1:1/contours/{z}/{x}/{y}.pbf", contourMinZoom = 7, contourMaxZoom = 15,
        osmTiles = "http://127.0.0.1:1/osm/{z}/{x}/{y}.pbf", osmMinZoom = 0, osmMaxZoom = 14,
        glyphs = "http://127.0.0.1:1/glyphs/{fontstack}/{range}.pbf",
    )

    @Suppress("UNCHECKED_CAST")
    private fun layers(json: String): List<Map<String, Any?>> = MiniJson.parseObject(json)["layers"] as List<Map<String, Any?>>

    @Test
    fun everyDevtoolLayerHasAMapLibreLayer() {
        val json = MapLibreStyle(sources).json()
        val root = MiniJson.parseObject(json)
        assertEquals(8.0, (root["version"] as Number).toDouble())
        val ids = layers(json).map { it["id"] as String }
        assertEquals(ids.size, ids.toSet().size, "unique layer ids")
        for (layer in DefaultOsmStyle.style.layers) {
            when (layer) {
                is StyleLayer.Contours -> assertTrue("contour-index" in ids && "contour-minor" in ids && "contour-labels" in ids)
                is StyleLayer.Symbol -> assertTrue(layer.id in ids || "${layer.id}-marker" in ids, "symbol ${layer.id}")
                else -> assertTrue(layer.id in ids, "layer ${layer.id}")
            }
        }
        // Every layer's source is defined.
        @Suppress("UNCHECKED_CAST")
        val sourceIds = (root["sources"] as Map<String, Any?>).keys
        for (l in layers(json)) (l["source"] as String?)?.let { assertTrue(it in sourceIds, "source $it of ${l["id"]}") }
        assertFalse("NaN" in json || "Infinity" in json)
    }

    @Test
    fun orderAndZoomMapping() {
        val ids = layers(MapLibreStyle(sources).json())
        val byId = ids.associateBy { it["id"] as String }
        val index = ids.map { it["id"] as String }
        // Fills and lines keep the devtool order: wood under contours under water; hillshade at the bottom.
        assertTrue(index.indexOf("hillshade") < index.indexOf("wood"))
        assertTrue(index.indexOf("wood") < index.indexOf("contour-index"))
        assertTrue(index.indexOf("contour-index") < index.indexOf("water"))
        // Labels: all above lines; the devtool's first symbol layer (places) wins collisions, so it is on top;
        // contour labels are the lowest symbol layer.
        val firstSymbol = ids.indexOfFirst { it["type"] == "symbol" }
        assertEquals("contour-labels", index[firstSymbol])
        assertTrue(index.indexOf("places") > index.indexOf("peaks"))
        assertTrue(index.indexOf("peaks") > index.indexOf("trail-names"))
        assertTrue(ids.drop(firstSymbol).all { it["type"] == "symbol" })
        // Devtool zoom 12 (256-px tiles) is MapLibre zoom 11.
        assertEquals(11.0, (byId.getValue("trail-names")["minzoom"] as Number).toDouble())
        // A trail has a casing below it and dashes.
        assertTrue(index.indexOf("trail-casing") < index.indexOf("trail"))
        @Suppress("UNCHECKED_CAST")
        assertNotNull((byId.getValue("trail")["paint"] as Map<String, Any?>)["line-dasharray"])
    }

    @Test
    fun terrainOnlyWithoutOsm() {
        val json = MapLibreStyle(sources.copy(osmTiles = null)).json()
        val ids = layers(json).map { it["id"] }
        assertEquals(listOf("background", "hillshade", "contour-minor", "contour-index", "contour-labels"), ids)
    }

    @Test
    fun jsonWriterEscapesAndFormatsNumbers() {
        assertEquals("""{"a":[1,2.5,null,"x\"\n"],"b":true}""", JsonWriter.write(linkedMapOf("a" to listOf(1, 2.5, Double.NaN, "x\"\n"), "b" to true)))
        assertEquals("3", JsonWriter.write(3.0))
    }
}
