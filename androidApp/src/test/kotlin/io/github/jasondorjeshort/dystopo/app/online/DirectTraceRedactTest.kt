package io.github.jasondorjeshort.dystopo.app.online

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * T106 (a T88 follow-up, the privacy rule): a release build's logcat timeline keeps the zooms and the timings but not
 * where: no tile column or row, no source file name (a 3DEP cell names a one-degree square).
 */
class DirectTraceRedactTest {
    @Test
    fun releaseLinesSayNothingOfWhere() {
        assertEquals("1834 dem? 12/-/-", DirectTrace.redact("1834 dem? 12/661/1443"))
        assertEquals("2210 dem. 12/-/- 5873 ms", DirectTrace.redact("2210 dem. 12/661/1443 5873 ms"))
        assertEquals("410 read - 812 KB 640 ms", DirectTrace.redact("410 read some-elevation-cell.tif 812 KB 640 ms"))
        assertEquals("95 c200 13/-/- 4211", DirectTrace.redact("95 c200 13/1322/2886 4211"))
        assertEquals("3000 refresh", DirectTrace.redact("3000 refresh"))
        val all = listOf("7 d? 15/5291/11546", "8 d503 9/82/180", "9 c200p 8/41/90 0").map(DirectTrace::redact)
        assertEquals(listOf("7 d? 15/-/-", "8 d503 9/-/-", "9 c200p 8/-/- 0"), all)
        for (line in all) assertFalse(Regex("""\d+/\d+/\d+""").containsMatchIn(line), line)
    }
}
