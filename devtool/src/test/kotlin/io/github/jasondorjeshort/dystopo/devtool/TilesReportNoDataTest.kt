package io.github.jasondorjeshort.dystopo.devtool

import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A zoom with no written tiles (open ocean) must report null elevations, not NaN that breaks the JSON encoder. */
class TilesReportNoDataTest {
    @Test
    fun noWrittenTilesGiveNullRange() {
        assertNull(finiteMinOrNull(emptyList()))
        assertNull(finiteMaxOrNull(emptyList()))
        assertNull(finiteMinOrNull(listOf(Float.NaN, Float.NaN)))
    }

    @Test
    fun nanAndInfinityAreIgnored() {
        val values = listOf(Float.NaN, 12.5f, Float.POSITIVE_INFINITY, -3f, Float.NEGATIVE_INFINITY)
        assertEquals(-3.0, finiteMinOrNull(values))
        assertEquals(12.5, finiteMaxOrNull(values))
    }

    @Test
    fun anEmptyZoomReportEncodes() {
        val report = ZoomReport(
            zoom = 9, tiles = 4, written = 0, skippedNoData = 4, bytes = 0, halo = 2, windowPx = 260,
            cellSizeMeters = 300.0, overviewTargetMeters = 300.0, featherCells = 0.0, noDataCells = 4 * 65536L,
            elevationMinMeters = finiteMinOrNull(emptyList()), elevationMaxMeters = finiteMaxOrNull(emptyList()),
            timeMs = 1, fetchMsTotal = 1, encodeMsTotal = 0, sourceCounterSums = emptyMap(),
            verify = VerifyStats(0, 0.0, 0, 0, 0, 0.0),
        )
        val json = reportJson.encodeToString(report)
        assertTrue("\"elevationMinMeters\": null" in json, json)
        assertFalse("NaN" in json || "Infinity" in json, json)
    }
}
