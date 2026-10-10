package io.github.jasondorjeshort.dystopo.app

import io.github.jasondorjeshort.dystopo.render.ContourUnits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsRoutesTest {
    private fun ok(vararg p: Pair<String, Any?>) = assertIs<SettingsRoutes.Parsed.Ok>(SettingsRoutes.parse(mapOf(*p)))
    private fun bad(vararg p: Pair<String, Any?>) = assertIs<SettingsRoutes.Parsed.Error>(SettingsRoutes.parse(mapOf(*p)))

    @Test
    fun textScaleOutsideTheRangeIsRefusedNotClamped() {
        assertEquals(1.6, ok("textScale" to "1.6").textScale)
        assertEquals(3.0, ok("textScale" to 3).textScale)
        assertTrue(bad("textScale" to "9").message.contains("between"))
        bad("textScale" to "0.1")
        bad("textScale" to "NaN")
        bad("textScale" to "big")
    }

    @Test
    fun unitsParseAndCanBeCleared() {
        assertEquals(ContourUnits.METERS, ok("units" to "m").units)
        assertEquals(ContourUnits.METERS, ok("units" to "metres").units)
        assertEquals(ContourUnits.FEET, ok("units" to "ft").units)
        val cleared = ok("units" to "unset")
        assertTrue(cleared.unitsGiven)
        assertNull(cleared.units)
        assertFalse(ok("textScale" to "1").unitsGiven)
        bad("units" to "furlongs")
    }

    @Test
    fun warmupSwitchAndUnknownKeys() {
        assertEquals(false, ok("warmup" to "0").warmup)
        assertEquals(true, ok("warmup" to true).warmup)
        bad("warmup" to "maybe")
        bad("textscale" to "1.2")
    }

    @Test
    fun mapOptionsAreValidatedAndPassedThrough() {
        val o = ok("hillshade" to "multi", "slope" to "on", "night" to "1", "trails" to "off", "contourInterval" to "100")
        assertEquals(mapOf("hillshade" to "multi", "slope" to "on", "night" to "1", "trails" to "off", "contourInterval" to "100"), o.map)
        assertTrue(ok("textScale" to "1.2").map.isEmpty())
        assertTrue(bad("hillshade" to "loud").message.contains("hillshade"))
        bad("hillshadeIntensity" to "2")
        bad("contourInterval" to "33")
        // The interval is checked against the units in the same request, else the current ones.
        bad("contourInterval" to "40", "units" to "m")
        ok("contourInterval" to "40", "units" to "ft")
        assertIs<SettingsRoutes.Parsed.Error>(SettingsRoutes.parse(mapOf("contourInterval" to "40"), ContourUnits.METERS))
        assertIs<SettingsRoutes.Parsed.Ok>(SettingsRoutes.parse(mapOf("contourInterval" to "50"), ContourUnits.METERS))
    }

    @Test
    fun cacheCapsAreWholeMegabytesInRange() {
        assertEquals(512L, ok("onlineCacheMb" to "512").onlineCacheMb)
        assertEquals(64L, ok("contourCacheMb" to 64).contourCacheMb)
        assertNull(ok("textScale" to "1").onlineCacheMb)
        assertTrue(bad("onlineCacheMb" to "1").message.contains("between"))
        bad("contourCacheMb" to "100000")
        bad("contourCacheMb" to "lots")
    }

    @Test
    fun mapRequestsAndDebugRequestsGoToDifferentPools() {
        fun req(path: String) = Request("GET", path, emptyMap(), ByteArray(0))
        for (p in listOf("/dem/12/1/2.png", "/osm/1/0/0.pbf", "/contours/3/1/1.pbf", "/glyphs/Noto Sans Regular/0-255.pbf", "/style.json", "/sprite@2x.png", "/shade/12/1/2.png")) {
            assertTrue(Routes.isMapRequest(req(p)), p)
        }
        for (p in listOf("/health", "/screenshot", "/stats", "/settings", "/features", "/tracks", "/camera", "/shade")) {
            assertFalse(Routes.isMapRequest(req(p)), p)
        }
    }
}
