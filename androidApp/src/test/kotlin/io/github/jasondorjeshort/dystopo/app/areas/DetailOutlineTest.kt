package io.github.jasondorjeshort.dystopo.app.areas

import io.github.jasondorjeshort.dystopo.app.areas.DetailOutline.Level
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** T366: the mapping from a map's detail to its outline (finding 2026-11-23-outline-colours-by-map-detail). */
class DetailOutlineTest {
    /** WCAG 2's relative luminance and contrast, worked out here rather than read from the object under test. */
    private fun lum(hex: String): Double {
        val v = hex.removePrefix("#").toInt(16)
        fun ch(c: Int) = (c / 255.0).let { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * ch((v shr 16) and 0xff) + 0.7152 * ch((v shr 8) and 0xff) + 0.0722 * ch(v and 0xff)
    }

    private fun ratio(a: String, b: String): Double = lum(a).let { la -> lum(b).let { lb -> (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05) } }

    @Test
    fun everyLevelHasADayAndANightLookAtLeastThreeToOneOnItsPaper() {
        for (level in Level.values()) for (night in listOf(false, true)) {
            val look = DetailOutline.look(level, night)
            assertTrue(Regex("#[0-9a-f]{6}").matches(look.colour), "$level night=$night: ${look.colour}")
            val paper = if (night) "#2f2816" else "#f6f1e4"
            assertEquals(paper, DetailOutline.paper(night))
            val r = ratio(look.colour, paper)
            assertTrue(r >= 3.0, "$level night=$night: ${look.colour} on $paper is ${"%.2f".format(r)}:1")
            assertEquals(r, DetailOutline.contrast(look.colour, paper), 1e-9)
        }
    }

    @Test
    fun theDayAndNightColoursDifferForEveryLevel() {
        for (level in Level.values()) assertNotEquals(DetailOutline.look(level, false).colour, DetailOutline.look(level, true).colour, "$level")
    }

    @Test
    fun adjacentLevelsDifferInColourWidthAndDashByDayAndByNight() {
        for (night in listOf(false, true)) for ((a, b) in DetailOutline.DETAILS.zipWithNext()) {
            val la = DetailOutline.look(a, night)
            val lb = DetailOutline.look(b, night)
            assertNotEquals(la.colour, lb.colour, "$a/$b night=$night colour")
            assertTrue(lb.width > la.width, "$a/$b night=$night: width rises with detail")
            assertNotEquals(la.dash, lb.dash, "$a/$b night=$night dash")
        }
        // More detail reads stronger against the paper: darker by day, lighter by night.
        for (night in listOf(false, true)) {
            val ratios = DetailOutline.DETAILS.map { ratio(DetailOutline.look(it, night).colour, DetailOutline.paper(night)) }
            assertEquals(ratios.sorted(), ratios, "night=$night: $ratios")
        }
        assertEquals(null, DetailOutline.look(Level.FULL, false).dash) // the finest is solid
    }

    @Test
    fun theDraftIsTheOnlyFilledShapeAndKeepsItsTeal() {
        assertEquals("#00796b", DetailOutline.look(Level.DRAFT, false).colour)
        for (level in Level.values()) for (night in listOf(false, true)) {
            assertEquals(level == Level.DRAFT, DetailOutline.look(level, night).fill > 0f, "$level")
        }
    }

    @Test
    fun aMapsLevelComesFromItsZooms() {
        assertEquals(Level.FULL, DetailOutline.levelOf(14, 14))
        assertEquals(Level.FULL, DetailOutline.levelOf(15, 15)) // an area's z15
        assertEquals(Level.MEDIUM, DetailOutline.levelOf(14, 13))
        assertEquals(Level.LESS, DetailOutline.levelOf(13, 13))
        assertEquals(Level.LOW, DetailOutline.levelOf(13, 11)) // the two-maps fixture's wide map
        assertEquals(Level.FULL, DetailOutline.levelOf(null, 14)) // terrain only: by the one it has
        assertEquals(Level.LESS, DetailOutline.levelOf(13, null))
        assertEquals(Level.LOW, DetailOutline.levelOf(null, null))
        assertEquals(Level.OFF, DetailOutline.levelOf(14, 14, off = true))
        for (l in Level.values()) assertEquals(l, DetailOutline.byKey(l.key))
    }

    @Test
    fun theKeyListsTheDraftThenFinestFirstThenNotUsed() {
        assertEquals(
            listOf(Level.DRAFT, Level.FULL, Level.LESS, Level.LOW, Level.OFF),
            DetailOutline.keyOrder(listOf(Level.LOW, Level.OFF, Level.FULL, Level.DRAFT, Level.LESS, Level.FULL)),
        )
        // Drawn coarse first, the finest on top, a map not used under all.
        assertEquals(listOf(Level.OFF, Level.LOW, Level.LESS, Level.MEDIUM, Level.FULL, Level.DRAFT), Level.values().sortedBy { it.order })
    }

    @Test
    fun theCasingIsOneDpEachSideWithItsDashesUnderTheLines() {
        val m = DetailOutline.look(Level.MEDIUM, false)
        assertEquals(4.5f, DetailOutline.casingWidth(m))
        val d = DetailOutline.casingDash(m)!!
        assertEquals(m.dash!![0] * m.width, d[0] * DetailOutline.casingWidth(m), 1e-4f)
        assertEquals("Medium detail · rainier-fine", DetailOutline.longLabel(Level.MEDIUM, "rainier-fine"))
        assertEquals("Full detail", DetailOutline.longLabel(Level.FULL, null))
    }
}
