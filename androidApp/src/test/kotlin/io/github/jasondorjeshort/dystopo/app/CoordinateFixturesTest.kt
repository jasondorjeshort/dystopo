package io.github.jasondorjeshort.dystopo.app

import io.github.jasondorjeshort.dystopo.geo.CoordinateFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T388: the privacy checker's fixtures (`tools/privacy-check-coordinates.txt`, read by `tools/test_privacy_check.py`) are
 * exactly what [CoordinateFormat] prints, so a change of the app's text that the checker would no longer read fails here.
 */
class CoordinateFixturesTest {
    @Test
    fun theCheckersFixturesAreTheFormattersOwnText() {
        val rows = File("../tools/privacy-check-coordinates.txt").readLines(Charsets.UTF_8).filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals(16, rows.size, "four places in four formats")
        for (row in rows) {
            val (_, lat, lon, key, text) = row.split("|")
            val f = CoordinateFormat.of(key) ?: error("no format $key")
            assertEquals(f.format(lat.toDouble(), lon.toDouble()), text, row)
        }
        assertTrue(CoordinateFormat.entries.all { f -> rows.any { it.split("|")[3] == f.key } }, "every format has fixtures")
    }
}
