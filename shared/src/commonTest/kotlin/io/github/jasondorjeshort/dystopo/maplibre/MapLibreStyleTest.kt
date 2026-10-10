package io.github.jasondorjeshort.dystopo.maplibre

import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import io.github.jasondorjeshort.dystopo.style.DefaultOsmStyle
import io.github.jasondorjeshort.dystopo.style.Sprites
import io.github.jasondorjeshort.dystopo.style.StyleLayer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MapLibreStyleTest {
    private val sources = MapLibreSources(
        demTiles = "http://127.0.0.1:1/dem/{z}/{x}/{y}.png", demMinZoom = 8, demMaxZoom = 14,
        contourTiles = "http://127.0.0.1:1/contours/{z}/{x}/{y}.pbf", contourMinZoom = 7, contourMaxZoom = 15,
        osmTiles = "http://127.0.0.1:1/osm/{z}/{x}/{y}.pbf", osmMinZoom = 0, osmMaxZoom = 14,
        glyphs = "http://127.0.0.1:1/glyphs/{fontstack}/{range}.pbf",
    )

    private val spriteSources = sources.copy(sprite = "file:///data/sprites/sprite")

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
        val styles = listOf(MapLibreStyle(sources).json(), MapLibreStyle(sources.copy(osmTiles = null)).json(), MapLibreStyle(spriteSources).json())
        for (json in styles) {
            val symbols = layers(json).filter { it["type"] == "symbol" }
            assertTrue(symbols.isNotEmpty())
            for (l in symbols) {
                // An icon-only symbol (parking with a sprite) has no text and needs no font.
                if (layout(l)["text-field"] == null && layout(l)["icon-image"] != null) continue
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
                // Contour numbers grow with the fourth root above 1.0 (longer labels fit fewer bent contours).
                val k = if (id == "contour-labels") kotlin.math.sqrt(kotlin.math.sqrt(1.6)) else 1.6
                assertEquals(s0 * k, s1, 1e-6, "$id text-size")
                // Never overlap names: only marker glyphs (drawn on their own disc) may ignore collisions.
                if (!id.endsWith("-glyph")) assertTrue(layout(l)["text-allow-overlap"] != true, "$id overlaps")
            }
            if (l["type"] == "circle") {
                assertEquals(maxNumber(paint(l)["circle-radius"]) * 1.6, maxNumber(paint(big.getValue(id))["circle-radius"]), 1e-6, "$id radius")
            }
        }
        // Larger labels get more room: padding grows, and name spacing along lines. Contour labels are
        // the exception (contourLabelsStayDenseAtLargeTextScales).
        for (id in listOf("road-names", "peaks", "places")) {
            assertTrue(maxNumber(layout(big.getValue(id))["text-padding"]) > maxNumber(layout(base.getValue(id))["text-padding"]), "$id padding")
        }
        assertTrue(maxNumber(layout(big.getValue("road-names"))["symbol-spacing"]) > maxNumber(layout(base.getValue("road-names"))["symbol-spacing"]))
        // Default 1.0 is the built-in readable size: devtool contour label 11 px times BASE_TEXT_SCALE.
        assertEquals(11.0 * MapLibreStyle.BASE_TEXT_SCALE, maxNumber(layout(base.getValue("contour-labels"))["text-size"]), 1e-9)
    }

    @Test
    fun spriteMarkersAreIconsInTheLabelLayers() {
        val plain = MiniJson.parseObject(MapLibreStyle(sources).json())
        assertFalse("sprite" in plain, "no sprite key without a sprite")
        val root = MiniJson.parseObject(MapLibreStyle(spriteSources).json())
        assertEquals("file:///data/sprites/sprite", root["sprite"])
        val ls = layers(MapLibreStyle(spriteSources).json())
        val byId = ls.associateBy { it["id"] as String }
        // No discs and no separate glyph layers: the sprite icon carries the shape and the P.
        assertTrue(ls.none { it["type"] == "circle" }, "circle layers with a sprite")
        assertTrue(ls.none { (it["id"] as String).endsWith("-glyph") || (it["id"] as String).endsWith("-marker") })
        val iconNames = Sprites.icons().map { it.name }.toSet()
        val markerLayers = DefaultOsmStyle.style.layers.filterIsInstance<StyleLayer.Symbol>().filter { it.marker != null }
        assertTrue(markerLayers.size >= 10)
        for (sl in markerLayers) {
            val l = byId.getValue(sl.id)
            assertEquals("symbol", l["type"])
            val lay = layout(l)
            assertEquals(Sprites.iconName(sl.marker!!), lay["icon-image"], sl.id)
            assertTrue(lay["icon-image"] in iconNames, "${sl.id} icon in the sheet")
            assertEquals(MapLibreStyle.BASE_TEXT_SCALE / Sprites.ICON_SCALE, (lay["icon-size"] as Number).toDouble(), 1e-3, "${sl.id} icon-size")
            assertTrue(lay["icon-allow-overlap"] != true && lay["text-allow-overlap"] != true, "${sl.id} collides")
            if (sl.text.isNotBlank()) {
                assertEquals(sl.textOptional, lay["text-optional"], "${sl.id} text-optional")
                assertEquals("top", (lay["text-variable-anchor"] as List<*>)[0], "${sl.id} label below the icon first")
                assertTrue((lay["text-radial-offset"] as Number).toDouble() > 0.3)
            } else {
                assertNull(lay["text-field"], "${sl.id} icon only")
            }
        }
        // Icons scale with the text scale.
        val big = layers(MapLibreStyle(spriteSources, textScale = 2.0).json()).associateBy { it["id"] as String }
        assertEquals(2.0 * (layout(byId.getValue("huts"))["icon-size"] as Number).toDouble(), (layout(big.getValue("huts"))["icon-size"] as Number).toDouble(), 2e-3)
        // Same label priority order as without a sprite.
        val plainOrder = layers(MapLibreStyle(sources).json()).map { it["id"] as String }.filter { it in byId }
        val spriteOrder = ls.map { it["id"] as String }
        assertEquals(plainOrder.filter { it in spriteOrder }, spriteOrder.filter { it in plainOrder })
    }

    @Test
    fun contourLabelHaloHidesTheLineBetweenGlyphs() {
        // With 1.6 px the contour line showed in the gap after a "1": "13800" read as "1·3800" on the phone.
        val l = layers(MapLibreStyle(sources).json()).first { it["id"] == "contour-labels" }
        val halo = ((l["paint"] as Map<*, *>)["text-halo-width"] as Number).toDouble()
        assertTrue(halo >= 2.4, "contour label halo $halo")
    }

    @Test
    fun contourLabelsAreDenseFromZ12() {
        val l = layers(MapLibreStyle(sources).json()).first { it["id"] == "contour-labels" }
        val spacing = layout(l)["symbol-spacing"] as List<*>
        // ["interpolate", ["linear"], ["zoom"], 10, 260, 12, 120]: 120 px from MapLibre z12 (devtool z13).
        assertEquals(12.0, (spacing[spacing.size - 2] as Number).toDouble())
        assertTrue((spacing.last() as Number).toDouble() <= 150.0)
        assertEquals(MapLibreStyle.CONTOUR_LABEL_MAX_ANGLE.toDouble(), (layout(l)["text-max-angle"] as Number).toDouble())
        assertTrue(MapLibreStyle.CONTOUR_LABEL_MAX_ANGLE > 45)
    }

    @Test
    fun contourLabelsStayDenseAtLargeTextScales() {
        fun lay(s: Double) = layout(layers(MapLibreStyle(sources, textScale = s).json()).first { it["id"] == "contour-labels" })
        val one = lay(1.0)
        val two = lay(2.0)
        // At 1.0 nothing changed: 120 px from MapLibre z12, padding 4, angle 65.
        assertEquals(120.0, ((one["symbol-spacing"] as List<*>).last() as Number).toDouble())
        assertEquals(4.0, (one["text-padding"] as Number).toDouble())
        // At 2.0: fourth-root size, closer anchors, a wider angle and fixed padding, so fewer, larger labels rather than none.
        val sp = (two["symbol-spacing"] as List<*>).drop(3).chunked(2).associate { (z, v) -> (z as Number).toDouble() to (v as Number).toDouble() }
        assertEquals(60.0, sp.getValue(12.0))
        assertEquals(60.0, sp.getValue(13.5))
        assertEquals(240.0, sp.getValue(15.0), "opens up again at high zoom")
        assertEquals(3.0, (two["text-padding"] as Number).toDouble())
        assertEquals(MapLibreStyle.CONTOUR_LABEL_MAX_ANGLE_LARGE.toDouble(), (two["text-max-angle"] as Number).toDouble())
        assertEquals(maxNumber(one["text-size"]) * kotlin.math.sqrt(kotlin.math.sqrt(2.0)), maxNumber(two["text-size"]), 1e-9)
        // Below 1.0 the square root, as before.
        assertEquals(maxNumber(one["text-size"]) * kotlin.math.sqrt(0.5), maxNumber(lay(0.5)["text-size"]), 1e-9)
    }

    @Test
    fun peaksAndPlacesWinOverPoisAndContourLabelsComeLast() {
        // MapLibre places the topmost symbol layer first; within a layer the sort key decides.
        for (src in listOf(sources, spriteSources)) for (scale in listOf(1.0, 1.6, 2.0)) {
            val ls = layers(MapLibreStyle(src, textScale = scale).json())
            val symbols = ls.filter { it["type"] == "symbol" }.map { it["id"] as String }
            val first = symbols.asReversed() // placement order
            assertEquals("contour-labels", first.last(), "contour labels place last")
            val pois = listOf("huts", "trailheads", "campsites", "shelters", "waterfalls", "viewpoints", "springs", "caves", "outdoor-pois", "parking")
            for (winner in listOf("places", "peaks")) for (poi in pois) {
                if (poi in first) assertTrue(first.indexOf(winner) < first.indexOf(poi), "$winner before $poi at $scale")
            }
            val byId = ls.associateBy { it["id"] as String }
            assertEquals("""["-",0,["coalesce",["to-number",["get","ele"]],0]]""", JsonWriter.write(layout(byId.getValue("peaks"))["symbol-sort-key"]))
            assertEquals("""["coalesce",["to-number",["get","rank"]],99]""", JsonWriter.write(layout(byId.getValue("places"))["symbol-sort-key"]))
        }
    }

    @Test
    fun glyphRangesCoverTheLocalScripts() {
        val r = MapLibreStyle.GLYPH_RANGES.map { it.split("-").map(String::toInt) }
        // Basic Latin, Greek, Cyrillic, Hebrew, Arabic, Thai, Georgian, Vietnamese, punctuation.
        for (cp in listOf(0x41, 0x3B1, 0x416, 0x5D0, 0x627, 0xE01, 0x10D0, 0x1EA0, 0x2013)) {
            assertTrue(r.any { (a, b) -> cp in a..b }, "U+${cp.toString(16)} has a glyph range")
        }
        for ((a, b) in r) assertEquals(255, b - a, "ranges are MapLibre's 256-codepoint blocks")
    }

    @Test
    fun cliffTicksGrowWithZoom() {
        val ticks = layers(MapLibreStyle(sources).json()).first { it["id"] == "cliff-ticks" }
        val width = paint(ticks)["line-width"] as List<*>
        // Tick length stops reach devtool z17 (MapLibre 16), so ticks keep growing past z14.
        val stops = width.drop(3).chunked(2).map { (z, v) -> (z as Number).toDouble() to (v as Number).toDouble() }
        assertEquals(16.0, stops.last().first)
        assertTrue(stops.last().second > stops.first { it.first == 14.0 }.second)
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
        assertTrue("""["is-supported-script",["var","local"]]""" in nameJson)
        // The local line: name:nonlatin, else a name starting outside the Latin blocks (outdoors packs have no name:nonlatin).
        assertTrue(""""local",["coalesce",["get","name:nonlatin"],["case",["all",["has","name"]""" in nameJson, nameJson)
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
