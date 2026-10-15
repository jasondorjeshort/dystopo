package io.github.jasondorjeshort.dystopo.maplibre

import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import io.github.jasondorjeshort.dystopo.render.SlopeShading
import io.github.jasondorjeshort.dystopo.style.DefaultOsmStyle
import io.github.jasondorjeshort.dystopo.tiles.HillshadeMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MapOptionsTest {
    private val sources = MapLibreSources(
        demTiles = "http://127.0.0.1:1/dem/{z}/{x}/{y}.png", demMinZoom = 8, demMaxZoom = 14,
        contourTiles = "http://127.0.0.1:1/contours/{z}/{x}/{y}.pbf", contourMinZoom = 7, contourMaxZoom = 15,
        osmTiles = "http://127.0.0.1:1/osm/{z}/{x}/{y}.pbf", osmMinZoom = 0, osmMaxZoom = 14,
        glyphs = "http://127.0.0.1:1/glyphs/{fontstack}/{range}.pbf",
        shadeTiles = "http://127.0.0.1:1/shade/{z}/{x}/{y}.png?v=x", shadeMinZoom = 8, shadeMaxZoom = 16,
    )

    @Suppress("UNCHECKED_CAST")
    private fun doc(o: MapOptions) = MiniJson.parseObject(MapLibreStyle(sources, options = o).json())

    @Suppress("UNCHECKED_CAST")
    private fun layers(o: MapOptions) = doc(o)["layers"] as List<Map<String, Any?>>
    private fun ids(o: MapOptions) = layers(o).map { it["id"] as String }

    @Suppress("UNCHECKED_CAST")
    private fun sourceIds(o: MapOptions) = (doc(o)["sources"] as Map<String, Any?>).keys

    @Test
    fun defaultsKeepTheMapAsItWas() {
        val noShade = sources.copy(shadeTiles = null)
        assertEquals(MapLibreStyle(noShade).json(), MapLibreStyle(sources, options = MapOptions()).json())
        val ids = ids(MapOptions())
        assertTrue("hillshade" in ids)
        assertFalse("shade" in ids)
        assertFalse("shade" in sourceIds(MapOptions()))
        assertNull(MapOptions().shade)
    }

    @Test
    fun generatedShadeReplacesNativeHillshadeAboveLandCover() {
        for (o in listOf(MapOptions(hillshade = HillshadeMode.MULTI), MapOptions(hillshadeIntensity = 0.6), MapOptions(slope = SlopeShading.AVALANCHE, hillshade = HillshadeMode.OFF), MapOptions(slope = SlopeShading.STEEPNESS, hillshade = HillshadeMode.OFF))) {
            val ids = ids(o)
            assertFalse("hillshade" in ids, "$o")
            assertTrue("shade" in ids && "shade" in sourceIds(o), "$o")
            assertTrue(ids.indexOf("glacier") < ids.indexOf("shade") && ids.indexOf("shade") < ids.indexOf("contour-minor"), "$o")
            assertTrue(ids.indexOf("shade") < ids.indexOf("water"), "water covers the shade")
        }
        // Single light at full intensity plus slope: MapLibre's hillshade and the slope tiles together.
        val both = MapOptions(slope = SlopeShading.AVALANCHE)
        assertTrue("hillshade" in ids(both) && "shade" in ids(both))
        assertEquals(HillshadeMode.OFF, both.shade?.hillshade)
        // Nothing to shade: neither.
        val none = MapOptions(hillshade = HillshadeMode.OFF)
        assertFalse("hillshade" in ids(none) || "shade" in ids(none))
        assertNull(none.shade)
        @Suppress("UNCHECKED_CAST")
        val src = (doc(both)["sources"] as Map<String, Map<String, Any?>>)["shade"]!!
        assertEquals("raster", src["type"])
        assertEquals(256.0, (src["tileSize"] as Number).toDouble())
    }

    @Test
    fun layerTogglesHideTheirGroups() {
        val all = ids(MapOptions())
        // Every id a toggle names exists in the default style (a renamed layer would silently stay on).
        val styleIds = DefaultOsmStyle.style.layers.map { it.id }.toSet()
        for (g in MapOptions.LayerGroup.entries) for (id in MapOptions.LayerGroup.ids(g)) assertTrue(id in styleIds, "$g: $id")
        val off = mapOf(
            "trails" to MapOptions(trails = false), "water" to MapOptions(water = false), "landcover" to MapOptions(landcover = false),
            "pois" to MapOptions(pois = false), "boundaries" to MapOptions(boundaries = false),
        )
        for ((key, o) in off) {
            val ids = ids(o)
            val group = MapOptions.LayerGroup.entries.first { it.key == key }
            for (id in MapOptions.LayerGroup.ids(group)) assertTrue(ids.none { it == id || it.startsWith("$id-") }, "$key hides $id")
            assertTrue(ids.size < all.size)
            assertTrue("road-major" in ids && "peaks" in ids && "contour-index" in ids, "$key keeps the rest")
        }
        assertFalse("trail" in ids(MapOptions(trails = false)))
        assertTrue("trail" in all && "trail-names" in all)
    }

    @Test
    fun nightTurnsTheBaseDarkAndTextLight() {
        fun paint(o: MapOptions, id: String) = layers(o).first { it["id"] == id }["paint"] as Map<*, *>
        assertEquals("#f6f1e4", paint(MapOptions(), "background")["background-color"])
        val bg = paint(MapOptions(night = true), "background")["background-color"] as String
        val (r, g, b) = Regex("""rgb\((\d+),(\d+),(\d+)\)""").matchEntire(bg)!!.destructured.toList().map { it.toInt() }
        assertTrue(r + g + b < 120, "dark base: $bg")
        val text = paint(MapOptions(night = true), "peaks")["text-color"] as String
        val lum = Regex("""\d+""").findAll(text).take(3).sumOf { it.value.toInt() }
        assertTrue(lum > 450, "light text: $text")
        // Hillshade dimmed at night.
        val hs = paint(MapOptions(night = true), "hillshade")
        assertTrue((hs["hillshade-exaggeration"] as Number).toDouble() < 0.55)
        assertEquals(Triple(255, 255, 255).let { NightColors.of(255, 255, 255) }, NightColors.of(255, 255, 255))
        val white = NightColors.of(255, 255, 255)
        val black = NightColors.of(0, 0, 0)
        assertTrue(white.first < 30 && black.first > 200, "lightness turned over: $white $black")
    }

    @Test
    fun labelDensityChangesSpacing() {
        fun spacing(o: MapOptions) = ((layers(o).first { it["id"] == "contour-labels" }["layout"] as Map<*, *>)["symbol-spacing"] as List<*>).last()
        val normal = (spacing(MapOptions()) as Number).toDouble()
        assertTrue((spacing(MapOptions(contourLabels = ContourLabelDensity.DENSE)) as Number).toDouble() < normal)
        assertTrue((spacing(MapOptions(contourLabels = ContourLabelDensity.SPARSE)) as Number).toDouble() > normal)
    }

    @Test
    fun parseValidatesEveryKey() {
        val ft = ContourUnits.FEET
        val o = MapOptions.parse(
            mapOf(
                "hillshade" to "multi", "hillshadeIntensity" to "0.7", "slope" to "on", "contourInterval" to "100",
                "contourLabels" to "dense", "trails" to "off", "water" to "0", "landcover" to "false", "pois" to "no",
                "boundaries" to "off", "night" to "on",
            ),
            MapOptions(), ft,
        )
        assertEquals(MapOptions(HillshadeMode.MULTI, 0.7, SlopeShading.AVALANCHE, 100.0, ContourLabelDensity.DENSE, false, false, false, false, false, true), o)
        assertNull(MapOptions.parse(mapOf("contourInterval" to "auto"), o, ft).contourInterval)
        assertEquals(20.0, MapOptions.parse(mapOf("contourInterval" to "20m"), o, ContourUnits.METERS).contourInterval)
        assertEquals(100.0, MapOptions.parse(mapOf("contourInterval" to 100), o, ft).contourInterval)
        for (bad in listOf(
            "hillshade" to "dramatic", "hillshadeIntensity" to "1.5", "hillshadeIntensity" to "-0.1", "hillshadeIntensity" to "NaN",
            "slope" to "maybe", "contourInterval" to "37", "contourInterval" to "40", "contourLabels" to "lots", "night" to "dusk",
        )) {
            val units = if (bad.second == "40") ContourUnits.METERS else ft // 40 ft is offered, 40 m is not
            assertFailsWith<IllegalArgumentException>("$bad") { MapOptions.parse(mapOf(bad), MapOptions(), units) }
        }
        // Values come back as the API spells them.
        val json = o.json(ft)
        assertEquals("multi", json["hillshade"])
        assertEquals("100", json["contourInterval"])
        assertEquals("on", json["night"])
        assertNotNull(json["contourIntervalChoices"])
        assertEquals("avalanche", json["slope"])
        assertEquals(listOf("off", "avalanche", "steepness"), json["slopeChoices"])
    }

    @Test
    fun slopeTakesEachShadingAndTheOldOnOff() {
        // T104: stored settings and the browser viewer still say on/off; on is the avalanche shading.
        val ft = ContourUnits.FEET
        for ((raw, want) in listOf("on" to SlopeShading.AVALANCHE, "off" to SlopeShading.OFF, "avalanche" to SlopeShading.AVALANCHE, "steepness" to SlopeShading.STEEPNESS)) {
            val o = MapOptions.parse(mapOf("slope" to raw), MapOptions(), ft)
            assertEquals(want, o.slope, raw)
            // What is stored is read back unchanged (AppSettings writes json(), reads it through parse).
            assertEquals(o, MapOptions.parse(mapOf("slope" to o.json(ft)["slope"]), MapOptions(), ft))
        }
        val steep = MapOptions.parse(mapOf("slope" to "steepness", "hillshade" to "off"), MapOptions(), ft)
        assertEquals(SlopeShading.STEEPNESS, steep.shade?.slope)
        assertNotEquals(steep.shade?.key, steep.copy(slope = SlopeShading.AVALANCHE).shade?.key)
        val e = assertFailsWith<IllegalArgumentException> { MapOptions.parse(mapOf("slope" to "maybe"), MapOptions(), ft) }
        assertTrue("off, avalanche or steepness" in e.message.orEmpty(), e.message)
    }
}
