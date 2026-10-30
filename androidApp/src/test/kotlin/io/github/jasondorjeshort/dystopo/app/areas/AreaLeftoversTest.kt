package io.github.jasondorjeshort.dystopo.app.areas

import io.github.jasondorjeshort.dystopo.areas.AreaDetail
import io.github.jasondorjeshort.dystopo.areas.AreaOfInterest
import io.github.jasondorjeshort.dystopo.areas.AreaPoint
import io.github.jasondorjeshort.dystopo.boundaries.Countries
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** T170: the wave 21 follow-ups in the areas package that are pure rules. */
class AreaLeftoversTest {
    private val mb = 1024L * 1024L
    private val disc = AreaOfInterest.around(AreaPoint(46.786, -121.735), 5000.0, name = "Around Paradise")

    @Test
    fun theCardLineShowsAMediumAreasElevationCap() { // T157's follow-up
        assertEquals("z8-15", zoomText(disc.at(AreaDetail.FULL)))
        assertEquals("z8-14, elevation to z13", zoomText(disc.at(AreaDetail.MEDIUM)))
        assertEquals("z8-13", zoomText(disc.at(AreaDetail.LESS)))
    }

    @Test
    fun sizesPast100GbAreWholeGigabytesAndTheBudgetReadsTheSame() { // T152's follow-up
        assertEquals("99.5 GB", aboutSize(99L * 1024 * mb + 512 * mb))
        assertEquals("174 GB", aboutSize(186_610_390_000))
        assertEquals("817 GB", aboutSize(877_000_000_000))
        assertEquals("11.5 TB", aboutSize(12_689_954_735_000))
        assertEquals("2 GB", budgetSize(2048))
        assertEquals("1.5 GB", budgetSize(1536))
        assertEquals("128 GB", budgetSize(131_072))
        assertEquals("146 GB", budgetSize(150_000))
        assertEquals("1 TB", budgetSize(1_048_576))
        assertEquals("1.5 TB", budgetSize(1_572_864))
        // Russia at full detail on a 1 TB budget: was "about 11818.4 GB ... of your 1024 GB" in T152's run.
        assertEquals(
            "This area is about 11.5 TB; 1.6 GB of your 1 TB offline budget is in use. Make the area smaller, choose less detail, or raise the budget in Settings › Storage.",
            areaBudgetError(1638 * mb, 12_689_954_735_000, 0, 1_048_576),
        )
        assertEquals(
            "This area is about 174 GB; 1.6 GB of your 128 GB offline budget is in use. Make the area smaller, choose less detail, or raise the budget in Settings › Storage.",
            areaBudgetError(1638 * mb, 186_610_390_000, 0, 131_072),
        )
    }

    @Test
    fun coverCountsRoundTripForTheirSetOnly() { // T142's follow-up
        val key = CoverCounts.keyOf(Countries.all)
        assertEquals(key, CoverCounts.keyOf(Countries.all), "the same set, the same key")
        assertNotEquals(key, CoverCounts.keyOf(Countries.all.drop(1)))
        val c = CoverCounts(key)
        c.put("LIE", 13, 120)
        c.put("LIE", 8, 2)
        c.put("CHE", 14, 9_000)
        val ids = mapOf(AreaDetail.FULL to "a1", AreaDetail.MEDIUM to "a2", AreaDetail.LESS to "a3")
        c.putIds("LIE", ids)
        c.putIds("ATA", emptyMap()) // no area can hold it
        val text = c.text()
        assertTrue(text.startsWith("country-covers ${CoverCounts.VERSION} $key\n"), text)
        assertTrue("LIE\t8:2,13:120\ta1,a2,a3\n" in text && "ATA\t\t-\n" in text && "CHE\t14:9000\n" in text, text)
        val back = CoverCounts.parse(text, key)
        assertEquals(3, back.size)
        assertEquals(mapOf(8 to 2, 13 to 120), back.of("LIE"))
        assertEquals(mapOf(14 to 9_000), back.of("CHE"))
        assertEquals(ids, back.idsOf("LIE"))
        assertEquals(emptyMap(), back.idsOf("ATA"))
        assertEquals(null, back.idsOf("CHE"), "ids not stored yet")
        assertEquals(text, back.text())
        assertEquals(0, CoverCounts.parse(text, "other").size, "another bundled set measures afresh")
        assertEquals(0, CoverCounts.parse(text.replaceFirst("country-covers ${CoverCounts.VERSION}", "country-covers 0"), key).size)
        assertEquals(0, CoverCounts.parse("", key).size)
        val bad = CoverCounts.parse("country-covers ${CoverCounts.VERSION} $key\nLIE\t8:2,x:3,9:-1,40:5,10:7\ta1,,a3\n\nno tab here\n", key)
        assertEquals(mapOf(8 to 2, 10 to 7), bad.of("LIE"), "bad pairs skipped")
        assertEquals(null, bad.idsOf("LIE"), "an incomplete ids field is measured again")
    }

    @Test
    fun aRecordKeepsContoursForItsResume() { // T151's follow-up
        val r = Areas.Record(id = disc.id, name = "Around Paradise", area = disc, createdAt = 1L, state = "partial", contours = true)
        val text = MiniJson.encode(r.json())
        assertTrue("\"contours\":true" in text, text)
        assertTrue(Areas.Record.parse(MiniJson.parseObject(text)).contours)
        val plain = r.copy(contours = false)
        assertFalse("contours" in MiniJson.encode(plain.json()), "written only when set, so other records read as before")
        assertFalse(Areas.Record.parse(MiniJson.parseObject(MiniJson.encode(plain.json()))).contours)
    }

    @Test
    fun aZ8ToZ10RegionViewOpensAtMediumDetail() { // T157's follow-up
        assertEquals(DETAIL_LESS, regionDetail(7.5))
        assertEquals(DETAIL_MEDIUM, regionDetail(8.0))
        assertEquals(DETAIL_MEDIUM, regionDetail(9.4))
        assertEquals(DETAIL_FULL, regionDetail(10.0))
    }
}
