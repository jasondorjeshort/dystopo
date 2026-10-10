package io.github.jasondorjeshort.dystopo.devtool

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TourChecksTest {
    private fun datum(def: TourAreaDef, unconverted: Long): TourCheck =
        TourCommand.checks(def, 100.0, 200.0, 0.0, unconverted, null).single { it.name == "datum" }

    @Test
    fun unconvertedCellsAlwaysFail() {
        val plain = TourAreaDef(bbox = listOf(0.0, 0.0, 1.0, 1.0), expectedElevation = listOf(100.0, 200.0))
        assertTrue(datum(plain, 0).pass)
        assertFalse(datum(plain, 12).pass)
        // The old expectUnconverted tag (3DEP outside GEOID18) is gone: it no longer inverts the check.
        val tagged = plain.copy(tags = listOf("expectUnconverted"))
        assertTrue(datum(tagged, 0).pass)
        assertFalse(datum(tagged, 12).pass)
    }

    @Test
    fun noAreaUsesTheRetiredTag() {
        val areas = TourCommand.load(java.io.File(".."))  // tests run in devtool/
        assertTrue(areas.isNotEmpty())
        assertEquals(emptyList(), areas.filterValues { "expectUnconverted" in it.tags }.keys.toList())
    }
}
