package io.github.jasondorjeshort.dystopo.app

import io.github.jasondorjeshort.dystopo.geo.CoordinateFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T388: the privacy checker's fixtures (`tools/privacy-check-coordinates.txt`, read by `tools/test_privacy_check.py`) are
 * exactly what [CoordinateFormat] prints (`format` and, as `<key>-line`, the card's `line` with its no-break spaces), so a change of the app's text that the checker would no longer read fails here.
 */
class CoordinateFixturesTest {
    @Test
    fun theCheckersFixturesAreTheFormattersOwnText() {
        val rows = File("../tools/privacy-check-coordinates.txt").readLines(Charsets.UTF_8).filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals(32, rows.size, "four places in four formats, each as format() and as line() prints it")
        for (row in rows) {
            val (_, lat, lon, key, text) = row.split("|")
            val f = CoordinateFormat.of(key.removeSuffix("-line")) ?: error("no format $key")
            val want = if (key.endsWith("-line")) f.line(lat.toDouble(), lon.toDouble()) else f.format(lat.toDouble(), lon.toDouble())
            assertEquals(want, text, row)
        }
        assertTrue(CoordinateFormat.entries.all { f -> rows.any { it.split("|")[3] == f.key } }, "every format has fixtures")
    }
}
