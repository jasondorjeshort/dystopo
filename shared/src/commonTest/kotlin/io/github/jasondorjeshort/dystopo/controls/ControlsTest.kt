package io.github.jasondorjeshort.dystopo.controls

import io.github.jasondorjeshort.dystopo.maplibre.ContourLabelDensity
import io.github.jasondorjeshort.dystopo.maplibre.MapOptions
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import io.github.jasondorjeshort.dystopo.render.SlopeShading
import io.github.jasondorjeshort.dystopo.tiles.HillshadeMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T208 (ADR 0053 build step 1): the registry's map options against `MapOptions`, and the manifest's own rules. */
class ControlsTest {
    @Test
    fun mapOptionsTakeTheirKeysDefaultsAndChoicesFromTheRegistry() {
        assertEquals(
            listOf("hillshade", "hillshadeIntensity", "slope", "contourInterval", "contourLabels", "trails", "water", "landcover", "pois", "boundaries", "drainage", "airportGates", "night"),
            MapOptions.KEYS.toList(),
        )
        assertEquals(MapControls.ids, MapOptions.KEYS)
        // The defaults as the map was before T47 (MapOptionsTest.defaultsKeepTheMapAsItWas draws them).
        val d = MapOptions()
        assertEquals(HillshadeMode.SINGLE, d.hillshade)
        assertEquals(1.0, d.hillshadeIntensity)
        assertEquals(SlopeShading.OFF, d.slope)
        assertNull(d.contourInterval)
        assertEquals(ContourLabelDensity.NORMAL, d.contourLabels)
        assertEquals(listOf(true, true, true, true, true, false, true, false), listOf(d.trails, d.water, d.landcover, d.pois, d.boundaries, d.drainage, d.airportGates, d.night))
        // MapOptions.json() reports every default as the registry has it.
        val json = d.json(ContourUnits.FEET)
        for (item in MapControls.items) {
            val v = json[item.id]
            val want = if (item.id == "night") (if (item.default == true) "on" else "off") else item.default
            assertEquals(want, v, item.id)
        }
        assertEquals(MapControls.SLOPE.choices, json["slopeChoices"])
        assertEquals(listOf("auto", "10", "20", "40", "50", "80", "100", "200", "250", "500", "1000"), json["contourIntervalChoices"])
        assertEquals(MapControls.FEET_INTERVALS, MapOptions.intervals(ContourUnits.FEET))
        assertEquals(MapControls.METRE_INTERVALS, MapOptions.intervals(ContourUnits.METERS))
    }

    @Test
    fun theParseAcceptsEveryChoiceAndEveryDefault() {
        for (units in ContourUnits.entries) {
            val defaults = MapControls.items.associate { it.id to it.default }
            assertEquals(MapOptions(), MapOptions.parse(defaults, MapOptions(), units))
            for (item in MapControls.items) {
                for (c in item.choices) MapOptions.parse(mapOf(item.id to c), MapOptions(), units)
                for (v in item.choicesByUnits[units.symbol].orEmpty()) {
                    assertEquals(v, MapOptions.parse(mapOf(item.id to MapOptions.fmt(v)), MapOptions(), units).contourInterval)
                }
                if (item.type == ValueType.NUMBER) {
                    MapOptions.parse(mapOf(item.id to item.min), MapOptions(), units)
                    MapOptions.parse(mapOf(item.id to item.max), MapOptions(), units)
                    assertFailsWith<IllegalArgumentException> { MapOptions.parse(mapOf(item.id to item.max!! + 0.5), MapOptions(), units) }
                }
            }
        }
        // The messages still list the choices as they did.
        val e = assertFailsWith<IllegalArgumentException> { MapOptions.parse(mapOf("hillshade" to "x"), MapOptions(), ContourUnits.FEET) }
        assertEquals("hillshade must be off, single or multi (got x)", e.message)
        val s = assertFailsWith<IllegalArgumentException> { MapOptions.parse(mapOf("slope" to "x"), MapOptions(), ContourUnits.FEET) }
        assertEquals("slope must be off, avalanche or steepness (got x)", s.message)
        val l = assertFailsWith<IllegalArgumentException> { MapOptions.parse(mapOf("contourLabels" to "x"), MapOptions(), ContourUnits.FEET) }
        assertEquals("contourLabels must be sparse, normal or dense (got x)", l.message)
    }

    @Test
    fun everyItemIsPlacedByTheManifestsRules() {
        val ids = Controls.items.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "item ids are unique")
        assertEquals(Controls.categories.map { it.order }, (1..Controls.categories.size).toList())
        for (item in Controls.items) {
            assertTrue(item.placements.isNotEmpty(), "${item.id} has no placement")
            for ((p, pl) in item.placements) {
                assertNotNull(Controls.category(pl.category), "${item.id}: no category ${pl.category}")
                assertTrue(pl.zone in p.zones, "${item.id}: zone ${pl.zone} is not one of ${p.key}'s ${p.zones}")
                if (p == Platform.WEB) assertTrue(pl.elements.isNotEmpty(), "${item.id}: a web placement names the page's elements")
                if (p == Platform.ANDROID && pl.zone == "settings") assertNotNull(pl.row, "${item.id}: a settings placement names its row")
            }
            if (item.kind == ControlKind.SETTING) {
                assertNotNull(item.type, item.id)
                assertEquals("app.map.${item.id}", item.syncKey, "map options sync as app.map.<key> (SettingsSync.syncedKey)")
            }
        }
        for (p in Platform.entries) for (c in Controls.categories) {
            val orders = Controls.items.mapNotNull { it.placements[p]?.takeIf { pl -> pl.category == c.id }?.order }
            assertEquals(orders.size, orders.toSet().size, "${p.key} › ${c.id}: two items share an order")
        }
        val web = Controls.webElements()
        assertEquals("hillshade", web["hill"])
        assertEquals("hillshade", web["opt-hillshade"])
        assertEquals(listOf("hillshadeIntensity", "contourLabels", "drainage"), Controls.items.filter { Platform.WEB !in it.placements }.map { it.id })
    }

    @Test
    fun theDocumentCarriesTheRegistry() {
        val doc = MiniJson.parseObject(ControlsDocument.text())
        assertEquals(MiniJson.parseObject(ControlsDocument.compact()), doc, "the file and the inlined form say the same")
        assertEquals(ControlsDocument.VERSION.toLong(), (doc["version"] as Number).toLong())
        @Suppress("UNCHECKED_CAST")
        val items = doc["items"] as List<Map<String, Any?>>
        assertEquals(MapOptions.KEYS.toList(), items.map { it["id"] })
        val ci = items.first { it["id"] == "contourInterval" }
        assertEquals(listOf("auto"), ci["choices"])
        @Suppress("UNCHECKED_CAST")
        val byUnits = ci["choicesByUnits"] as Map<String, List<Number>>
        assertEquals(MapOptions.FEET_INTERVALS, byUnits["ft"]!!.map { it.toDouble() })
        assertEquals(MapOptions.METRE_INTERVALS, byUnits["m"]!!.map { it.toDouble() })
        val hs = items.first { it["id"] == "hillshade" }
        assertEquals(listOf("web", "android"), hs["platforms"])
        @Suppress("UNCHECKED_CAST")
        val place = hs["placement"] as Map<String, Map<String, Any?>>
        assertEquals(listOf("opt-hillshade", "hill"), place["web"]!!["elements"])
        assertEquals("mapOptions", place["android"]!!["row"])
        assertTrue(ControlsDocument.text().endsWith("}\n"))
    }
}
