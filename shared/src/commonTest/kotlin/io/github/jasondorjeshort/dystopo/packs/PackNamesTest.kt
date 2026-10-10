package io.github.jasondorjeshort.dystopo.packs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PackNamesTest {
    @Test
    fun parsesPackFileNames() {
        assertEquals(PackFileName("rainier", PackRole.DEM), PackNames.parse("rainier-dem.pmtiles"))
        assertEquals(PackFileName("rainier", PackRole.VECTOR), PackNames.parse("rainier-outdoors.pmtiles"))
        assertEquals(PackFileName("rainier", PackRole.VECTOR), PackNames.parse("rainier-osm.pmtiles"))
        assertEquals(PackFileName("mont-blanc.z8", PackRole.DEM), PackNames.parse("mont-blanc.z8-dem.pmtiles"))
        assertEquals(PackFileName(PackNames.LEGACY_NAME, PackRole.DEM, legacy = true), PackNames.parse("dem.pmtiles"))
        assertEquals(PackFileName(PackNames.LEGACY_NAME, PackRole.VECTOR, legacy = true), PackNames.parse("osm.pmtiles"))
        assertNull(PackNames.parse("rainier-dem.pmtiles.part"))
        assertNull(PackNames.parse("rainier.pmtiles"))
        assertNull(PackNames.parse("-dem.pmtiles"))
        assertNull(PackNames.parse("../x-dem.pmtiles"))
        assertNull(PackNames.parse("active"))
    }

    @Test
    fun validNamesAreFileNameSafe() {
        assertTrue(PackNames.isValidName("rainier"))
        assertTrue(PackNames.isValidName("Rainier_2026.10"))
        assertFalse(PackNames.isValidName(""))
        assertFalse(PackNames.isValidName(".hidden"))
        assertFalse(PackNames.isValidName("a/b"))
        assertFalse(PackNames.isValidName("a..b"))
        assertFalse(PackNames.isValidName("a b"))
        assertFalse(PackNames.isValidName("x".repeat(65)))
        assertEquals("rainier-dem.pmtiles", PackNames.fileName("rainier", PackRole.DEM))
        assertEquals("rainier-outdoors.pmtiles", PackNames.fileName("rainier", PackRole.VECTOR))
        assertFailsWith<IllegalArgumentException> { PackNames.fileName("a/b", PackRole.DEM) }
    }

    @Test
    fun groupsFilesIntoPacks() {
        val packs = PackNames.group(
            listOf(
                "rainier-small-outdoors.pmtiles", "rainier-small-dem.pmtiles", "dem.pmtiles", "osm.pmtiles",
                "other-osm.pmtiles", "other-outdoors.pmtiles", "lonely-outdoors.pmtiles", "x-dem.pmtiles.part", "active",
            ),
        )
        assertEquals(
            listOf(
                PackFileSet("default", "dem.pmtiles", "osm.pmtiles"),
                PackFileSet("lonely", null, "lonely-outdoors.pmtiles"),
                PackFileSet("other", null, "other-outdoors.pmtiles"),
                PackFileSet("rainier-small", "rainier-small-dem.pmtiles", "rainier-small-outdoors.pmtiles"),
            ),
            packs,
        )
    }

    @Test
    fun choosesTheActivePack() {
        val packs = PackNames.group(listOf("b-dem.pmtiles", "a-dem.pmtiles", "dem.pmtiles", "c-outdoors.pmtiles"))
        assertEquals("b", PackNames.chooseActive(packs, "b"))
        assertEquals("default", PackNames.chooseActive(packs, "gone"))
        assertEquals("default", PackNames.chooseActive(packs, "c")) // no DEM: not usable
        assertEquals("default", PackNames.chooseActive(packs, null))
        assertEquals("a", PackNames.chooseActive(PackNames.group(listOf("b-dem.pmtiles", "a-dem.pmtiles")), null))
        assertNull(PackNames.chooseActive(emptyList(), "a"))
    }
}
