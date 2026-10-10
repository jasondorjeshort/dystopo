package io.github.jasondorjeshort.dystopo.render

import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContourStyleTest {
    @Test
    fun intervalDoublesRoughlyPerZoomAndIsMonotonic() {
        for (units in ContourUnits.entries) {
            var previous = ContourStyle.forZoom(17, units).intervalMeters
            for (zoom in 16 downTo 9) {
                val spec = ContourStyle.forZoom(zoom, units)
                assertTrue(spec.intervalMeters >= previous, "$units z$zoom: ${spec.intervalMeters} < $previous")
                previous = spec.intervalMeters
            }
        }
    }

    @Test
    fun tableClampsOutsideItsRange() {
        assertEquals(ContourStyle.forZoom(16, ContourUnits.FEET), ContourStyle.forZoom(20, ContourUnits.FEET))
        assertEquals(ContourStyle.forZoom(10, ContourUnits.METERS), ContourStyle.forZoom(3, ContourUnits.METERS))
    }

    @Test
    fun knownEntries() {
        assertEquals(ContourSpec(200.0, 5, ContourUnits.FEET), ContourStyle.forZoom(13, ContourUnits.FEET))
        assertEquals(ContourSpec(40.0, 5, ContourUnits.FEET), ContourStyle.forZoom(15, ContourUnits.FEET))
        assertEquals(ContourSpec(1000.0, 5, ContourUnits.FEET), ContourStyle.forZoom(11, ContourUnits.FEET))
        assertEquals(ContourSpec(50.0, 4, ContourUnits.METERS), ContourStyle.forZoom(13, ContourUnits.METERS))
    }

    @Test
    fun linesOnA30DegreeSlopeStayReadableAtEveryZoom() {
        // Mercator pixel size at 46 N: 156543 * cos(lat) / 2^zoom meters.
        val slope = tan(30.0 * kotlin.math.PI / 180)
        for (units in ContourUnits.entries) for (zoom in 10..16) {
            val metersPerPx = 156543.03 * 0.6947 / (1 shl zoom)
            val spacingPx = ContourStyle.forZoom(zoom, units).intervalMeters / slope / metersPerPx
            assertTrue(spacingPx in 5.0..14.0, "$units z$zoom: $spacingPx px between lines")
        }
    }

    @Test
    fun overridesReplaceOnlyWhatWasGiven() {
        val spec = ContourStyle.resolve(13, ContourUnits.FEET, interval = 100.0)
        assertEquals(100.0, spec.interval)
        assertEquals(5, spec.indexEvery)
        assertEquals(2, ContourStyle.resolve(13, ContourUnits.FEET, indexEvery = 2).indexEvery)
        assertEquals(200.0, ContourStyle.resolve(13, ContourUnits.FEET, indexEvery = 2).interval)
    }

    @Test
    fun indexDetectionAndLabels() {
        val spec = ContourSpec(40.0, 5, ContourUnits.FEET)
        assertEquals(60.96, spec.indexIntervalMeters, 1e-9)
        val levels = spec.levelsMeters(0.0, 1000.0 * 0.3048)
        assertEquals(24, levels.size)
        val index = levels.filter { spec.isIndex(it) }
        assertEquals(listOf("200", "400", "600", "800"), index.map { spec.label(it) })
        assertFalse(spec.isIndex(40 * 0.3048))
        assertEquals("6400", spec.label(6400 * 0.3048))
        assertEquals("1500", ContourSpec(50.0, 4, ContourUnits.METERS).label(1500.0))
    }

    @Test
    fun indexEveryZeroDisablesIndexContours() {
        val spec = ContourSpec(40.0, 0, ContourUnits.FEET)
        assertEquals(0.0, spec.indexIntervalMeters)
        assertFalse(spec.isIndex(0.0))
    }

    @Test
    fun unitsParse() {
        assertEquals(ContourUnits.FEET, ContourUnits.parse("ft"))
        assertEquals(ContourUnits.METERS, ContourUnits.parse("m"))
    }
}
