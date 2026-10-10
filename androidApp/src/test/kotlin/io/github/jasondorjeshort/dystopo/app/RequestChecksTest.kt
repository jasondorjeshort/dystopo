package io.github.jasondorjeshort.dystopo.app

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RequestChecksTest {
    private fun ok(z: String, x: String, y: String) = assertNotNull(RequestChecks.tile(z, x, y).first, "$z/$x/$y should be valid")
    private fun bad(z: String, x: String, y: String) {
        val (zxy, reason) = RequestChecks.tile(z, x, y)
        assertNull(zxy, "$z/$x/$y should be rejected")
        assertNotNull(reason)
    }

    @Test
    fun acceptsTilesInsideTheGrid() {
        assertContentEquals(intArrayOf(0, 0, 0), ok("0", "0", "0"))
        assertContentEquals(intArrayOf(13, 1325, 2888), ok("13", "1325", "2888"))
        ok("24", "16777215", "16777215")
    }

    @Test
    fun rejectsNegativeHugeAndOutOfRange() {
        bad("-1", "0", "0")
        bad("10", "-1", "5")
        bad("10", "5", "-1")
        bad("25", "0", "0")
        bad("99999999999999999999", "0", "0")
        bad("10", "99999999999999999999", "0")
        bad("3", "8", "0")
        bad("3", "0", "8")
        bad("0", "1", "0")
        bad("24", "2147483648", "0")
    }

    @Test
    fun attributionBecomesPlainText() {
        assertEquals(
            "© OpenStreetMap contributors © OpenMapTiles",
            RequestChecks.htmlToText("<a href=\"https://www.openstreetmap.org/copyright\">&copy; OpenStreetMap contributors</a> <a href=\"x\">&copy; OpenMapTiles</a>"),
        )
        assertEquals("A & B < C é é", RequestChecks.htmlToText("A &amp; B &lt; C &#233; &#xE9;"))
        assertEquals("&bogus; stays, so does & alone", RequestChecks.htmlToText("&bogus; stays,\n so does & alone"))
        assertEquals("&#0; &#xD800;", RequestChecks.htmlToText("&#0; &#xD800;"))
    }
}
