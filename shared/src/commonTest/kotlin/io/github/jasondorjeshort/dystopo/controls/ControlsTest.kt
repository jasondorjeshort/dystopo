package io.github.jasondorjeshort.dystopo.controls

import io.github.jasondorjeshort.dystopo.maplibre.ContourLabelDensity
import io.github.jasondorjeshort.dystopo.maplibre.MapOptions
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import io.github.jasondorjeshort.dystopo.render.SlopeShading
import io.github.jasondorjeshort.dystopo.tiles.HillshadeMode
import io.github.jasondorjeshort.dystopo.ui.OverlayIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T208 (ADR 0053 build step 1): the registry's map options against `MapOptions`, and the manifest's own rules; T238
 * (step 2): the rest of the shared surface, the items of one client, and `GET /layout`'s elements as manifest items.
 */
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
        assertEquals(ids.size, ids.toSet().size, "item ids are unique: ${ids.groupBy { it }.filterValues { it.size > 1 }.keys}")
        assertEquals(Controls.categories.map { it.order }, (1..Controls.categories.size).toList())
        for (item in Controls.items) {
            assertTrue(item.placements.isNotEmpty(), "${item.id} has no placement")
            for ((p, pl) in item.placements) {
                assertNotNull(Controls.category(pl.category), "${item.id}: no category ${pl.category}")
                assertTrue(pl.zone in p.zones, "${item.id}: zone ${pl.zone} is not one of ${p.key}'s ${p.zones}")
                if (p == Platform.ANDROID && pl.zone == "settings") assertNotNull(pl.row, "${item.id}: a settings placement names its row")
                if (p == Platform.ANDROID && pl.zone != "settings") assertNull(pl.row, "${item.id}: only a settings placement names a row")
                if (p == Platform.WEB) assertTrue(pl.row == null && pl.inventory.isEmpty(), "${item.id}: a web placement names elements only")
            }
            // A registry item carries its values; an item whose values stay in code (or of one client) carries none.
            if (item.default != null || item.choices.isNotEmpty() || item.min != null) assertNotNull(item.type, "${item.id}: values without a type")
            if (item.type != null) assertEquals(ControlKind.SETTING, item.kind, item.id)
            val sync = item.syncKey
            if (sync != null) {
                assertEquals(ControlKind.SETTING, item.kind, "${item.id}: only a setting syncs")
                assertTrue(Platform.ANDROID in item.placements, "${item.id}: the phone is what syncs")
                // SettingsSync.syncedKey: map options as app.map.<key>, every other setting as app.<key>.
                assertEquals(if (item.id in MapOptions.KEYS) "app.map.${item.id}" else "app.${item.id}", sync, item.id)
            }
        }
        for (p in Platform.entries) for (c in Controls.categories) {
            val orders = Controls.items.mapNotNull { it.placements[p]?.takeIf { pl -> pl.category == c.id }?.order }
            assertEquals(orders.size, orders.toSet().size, "${p.key} › ${c.id}: two items share an order")
        }
        val web = Controls.webElements()
        assertEquals("hillshade", web["hill"])
        assertEquals("hillshade", web["opt-hillshade"])
        assertEquals("orientationButton", web["maplibregl.NavigationControl"])
        assertEquals(listOf("hillshadeIntensity", "contourLabels", "drainage"), MapControls.items.filter { Platform.WEB !in it.placements }.map { it.id })
        // The items of both clients (ADR 0053's shared surface) and the files each kind lives in.
        for (item in MapControls.items + SharedControls.items) assertTrue(Platform.ANDROID in item.placements, item.id)
        for (item in AndroidControls.items) assertEquals(listOf(Platform.ANDROID), item.platforms, item.id)
        for (item in PageControls.items) assertEquals(listOf(Platform.WEB), item.platforms, item.id)
        assertEquals("${Controls.DIR}/SharedControls.kt", Controls.sourceOf("units"))
        assertEquals("${Controls.DIR}/", Controls.sourceOf("nothing"))
    }

    @Test
    fun theLayoutsElementsAreItemsWithTheirInventoryRows() {
        // T224's GET /layout ids (OverlayIds) and their M-numbers are the manifest's phone map elements (T238).
        for ((layoutId, name) in OverlayIds.NAMES) {
            val item = assertNotNull(Controls.item(name), "GET /layout's element $layoutId ($name) is not a manifest item: add it to ${Controls.DIR}/AndroidControls.kt")
            val pl = assertNotNull(item.placements[Platform.ANDROID], name)
            assertTrue(pl.zone != "settings", name)
            assertEquals(OverlayIds.CONTROLS[layoutId].orEmpty(), pl.inventory, "$name's inventory rows are GET /layout's")
        }
        // The two elements GET /layout does not list yet (T241 adds them).
        for (id in listOf("firstRunCard", "onlineCard")) assertEquals("card", Controls.item(id)!!.placements[Platform.ANDROID]!!.zone)
    }

    @Test
    fun theDocumentCarriesTheRegistry() {
        val doc = MiniJson.parseObject(ControlsDocument.text())
        assertEquals(MiniJson.parseObject(ControlsDocument.compact()), doc, "the file and the inlined form say the same")
        assertEquals(ControlsDocument.VERSION.toLong(), (doc["version"] as Number).toLong())
        @Suppress("UNCHECKED_CAST")
        val items = doc["items"] as List<Map<String, Any?>>
        assertEquals(MapOptions.KEYS.toList(), items.map { it["id"] }.take(MapOptions.KEYS.size), "the map options come first")
        assertEquals(Controls.items.map { it.id }, items.map { it["id"] })
        // An item of one client carries only its id, kind, platforms and placement; a phone map element its inventory rows.
        assertEquals(listOf("id", "kind", "platforms", "placement"), items.first { it["id"] == "search" }.keys.toList())
        @Suppress("UNCHECKED_CAST")
        val search = (items.first { it["id"] == "search" }["placement"] as Map<String, Map<String, Any?>>)["android"]!!
        assertEquals(listOf("M3", "M4", "M5", "M6", "M7"), search["inventory"])
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
