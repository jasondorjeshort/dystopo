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

    @Suppress("UNCHECKED_CAST")
    private fun layout(l: Map<String, Any?>) = l["layout"] as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun paint(l: Map<String, Any?>) = l["paint"] as Map<String, Any?>

    /** A number, or the largest number inside an expression (sizes are zoom interpolations). */
    private fun maxNumber(v: Any?): Double = when (v) {
        is Number -> v.toDouble()
        is List<*> -> v.maxOfOrNull { maxNumber(it) } ?: Double.NEGATIVE_INFINITY
        else -> Double.NEGATIVE_INFINITY
    }

    @Test
    fun everySymbolLayerHasAFontTheGlyphRouteServes() {
        // MapLibre Native falls back to its own default stack (Open Sans / Arial Unicode MS) when a layer
        // names no font; our glyph route only has the fonts in FONTS (tools/android-push-pack.ps1).
        for (json in listOf(MapLibreStyle(sources).json(), MapLibreStyle(sources.copy(osmTiles = null)).json())) {
            val symbols = layers(json).filter { it["type"] == "symbol" }
            assertTrue(symbols.isNotEmpty())
            for (l in symbols) {
                val fonts = layout(l)["text-font"]
                assertTrue(fonts is List<*> && fonts.size == 1, "${l["id"]}: one font, got $fonts")
                assertTrue(fonts[0] in MapLibreStyle.FONTS, "${l["id"]}: ${fonts[0]} not in ${MapLibreStyle.FONTS}")
                assertNotNull(layout(l)["text-field"], "${l["id"]} has text")
            }
        }
        assertEquals(3, MapLibreStyle.FONTS.toSet().size)
    }

    @Test
    fun glyphTemplateIsPassedThrough() {
        val root = MiniJson.parseObject(MapLibreStyle(sources.copy(glyphs = "file:///data/glyphs/{fontstack}/{range}.pbf")).json())
        assertEquals("file:///data/glyphs/{fontstack}/{range}.pbf", root["glyphs"])
    }

    @Test
    fun textScaleScalesTextMarkersAndRoom() {
        val base = layers(MapLibreStyle(sources).json()).associateBy { it["id"] as String }
        val big = layers(MapLibreStyle(sources, textScale = 1.6).json()).associateBy { it["id"] as String }
        assertEquals(base.keys, big.keys, "same layers at any scale")
        for ((id, l) in base) {
            if (l["type"] == "symbol") {
                val s0 = maxNumber(layout(l)["text-size"])
                val s1 = maxNumber(layout(big.getValue(id))["text-size"])
                assertEquals(s0 * 1.6, s1, 1e-6, "$id text-size")
                // Never overlap names: only marker glyphs (drawn on their own disc) may ignore collisions.
                if (!id.endsWith("-glyph")) assertTrue(layout(l)["text-allow-overlap"] != true, "$id overlaps")
            }
            if (l["type"] == "circle") {
                assertEquals(maxNumber(paint(l)["circle-radius"]) * 1.6, maxNumber(paint(big.getValue(id))["circle-radius"]), 1e-6, "$id radius")
            }
        }
        // Larger labels get more room: padding and line-label spacing grow.
        for (id in listOf("contour-labels", "road-names")) {
            assertTrue(maxNumber(layout(big.getValue(id))["text-padding"]) > maxNumber(layout(base.getValue(id))["text-padding"]), "$id padding")
            assertTrue(maxNumber(layout(big.getValue(id))["symbol-spacing"]) > maxNumber(layout(base.getValue(id))["symbol-spacing"]), "$id spacing")
        }
        // Default 1.0 is the built-in readable size: devtool contour label 11 px times BASE_TEXT_SCALE.
        assertEquals(11.0 * MapLibreStyle.BASE_TEXT_SCALE, maxNumber(layout(base.getValue("contour-labels"))["text-size"]), 1e-9)
    }

    @Test
    fun textScaleIsClamped() {
        assertEquals(MapLibreStyle.MAX_TEXT_SCALE, MapLibreStyle(sources, textScale = 50.0).textScale)
        assertEquals(MapLibreStyle.MIN_TEXT_SCALE, MapLibreStyle(sources, textScale = 0.01).textScale)
        assertEquals(1.0, MapLibreStyle.clampTextScale(Double.NaN))
        assertFalse("NaN" in MapLibreStyle(sources, textScale = Double.POSITIVE_INFINITY).json())
    }

    @Test
    fun namesPreferEnglishWithTheLocalScriptSecond() {
        val json = MapLibreStyle(sources).json()
        val byId = layers(json).associateBy { it["id"] as String }
        val nameJson = JsonWriter.write(MapLibreStyle.LABEL_NAME)
        // English, then Latin transliteration, then the local name; the local script only where it can be shaped.
        assertTrue(nameJson.startsWith("""["let","primary",["coalesce",["get","name:en"],["get","name_en"],["get","name:latin"],["get","name"],""]"""), nameJson)
        assertTrue("""["is-supported-script",["get","name:nonlatin"]]""" in nameJson)
        for (id in listOf("places", "peaks", "water-names")) {
            val field = JsonWriter.write(layout(byId.getValue(id))["text-field"])
            assertTrue(nameJson in field, "$id uses the label name: $field")
        }
        // No layer reads the bare local name any more.
        assertFalse("""["to-string",["coalesce",["get","name"],""]]""" in json)
    }

    @Test
    fun peakElevationsFollowTheUnitSetting() {
        fun peakField(units: io.github.jasondorjeshort.dystopo.render.ContourUnits) =
            JsonWriter.write(layout(layers(MapLibreStyle(sources, units = units).json()).single { it["id"] == "peaks" })["text-field"])
        val feet = peakField(io.github.jasondorjeshort.dystopo.render.ContourUnits.FEET)
        val metres = peakField(io.github.jasondorjeshort.dystopo.render.ContourUnits.METERS)
        assertTrue("0.3048" in feet && " ft" in feet, feet)
        assertTrue(""" m"""" in metres && " ft" !in metres, metres)
    }

    @Test
    fun jsonWriterEscapesAndFormatsNumbers() {
        assertEquals("""{"a":[1,2.5,null,"x\"\n"],"b":true}""", JsonWriter.write(linkedMapOf("a" to listOf(1, 2.5, Double.NaN, "x\"\n"), "b" to true)))
        assertEquals("3", JsonWriter.write(3.0))
    }
}
