package io.github.jasondorjeshort.dystopo.sources.elevation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [ProjGeoid.overlapRanges]: what `prefetch` reads for boxes inside, across the edge of, and outside a grid. */
class GeoidPrefetchTest {
    private fun assertRanges(expected: List<Pair<Double, Double>>, actual: List<Pair<Double, Double>>) {
        assertEquals(expected.size, actual.size, "ranges $actual")
        for ((e, a) in expected.zip(actual)) {
            assertEquals(e.first, a.first, 1e-9)
            assertEquals(e.second, a.second, 1e-9)
        }
    }

    @Test
    fun boxInsideA0To360GridIsShiftedIntoItsFrame() {
        // Rainier in a GEOID18-like grid spanning 230..300 E.
        assertRanges(listOf(238.1 to 238.4), ProjGeoid.overlapRanges(-121.9, -121.6, 230.0, 300.0))
    }

    @Test
    fun boxStraddlingTheGridEdgeIsClampedNotSkipped() {
        // A grid running 172..234 E (the Alaska grid's extent); the box crosses its east edge at -126.
        assertRanges(listOf(233.5 to 234.0), ProjGeoid.overlapRanges(-126.5, -125.5, 172.0, 234.0))
        // And its west edge, from the other frame.
        assertRanges(listOf(172.0 to 172.5), ProjGeoid.overlapRanges(171.5, 172.5, 172.0, 234.0))
    }

    @Test
    fun antimeridianBoxGivesBothPieces() {
        val unwrapped = ProjGeoid.overlapRanges(179.9, 180.1, -180.0, 180.0)
        assertRanges(listOf(-180.0 to -179.9, 179.9 to 180.0), unwrapped)
        // A wrapped box (east < west) means the same thing.
        assertRanges(unwrapped, ProjGeoid.overlapRanges(179.9, -179.9, -180.0, 180.0))
    }

    @Test
    fun disjointBoxReadsNothing() {
        assertTrue(ProjGeoid.overlapRanges(10.0, 11.0, 172.0, 234.0).isEmpty())
    }
}
