package io.github.jasondorjeshort.dystopo.app.areas

import io.github.jasondorjeshort.dystopo.areas.AreaLimit
import io.github.jasondorjeshort.dystopo.areas.AreaManifest
import io.github.jasondorjeshort.dystopo.areas.AreaOfInterest
import io.github.jasondorjeshort.dystopo.areas.AreaPoint
import io.github.jasondorjeshort.dystopo.areas.AreaShape
import io.github.jasondorjeshort.dystopo.boundaries.Countries
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T142 (ADR 0041 §5): the area picker's pure parts: the country rows and sizes, the drawing's geometry, the summary's lines. */
class AreaPickerTest {
    private val mb = 1_048_576L

    /** The emulator's large heap (T138): 576 MB. */
    private val heapTiles = AreaLimit.maxTiles(576L * mb)

    @Test
    fun aCountrysMeasureIsTheManifestsTotals() {
        for (code in listOf("LIE", "AND")) {
            val c = Countries.byCode(code)!!
            val area = countryArea(c, AreaPicker.LESS_MAX_ZOOM)!!
            assertEquals(c.toleranceM, (area.shape as AreaShape.Polygon).bufferM, "the tolerance is the buffer (ADR 0041's writer rule)")
            assertEquals(c.name, area.name)
            for ((dem, osm) in listOf(6..13 to 8..13, 8..13 to 8..13)) {
                val items = AreaManifest.tileItems(area, dem, osm)
                val m = measureArea(area, dem, osm, heapTiles)
                assertFalse(m.tooLarge)
                assertEquals(items.sumOf { it.count }, m.tiles, "$code tiles")
                assertEquals(items.sumOf { it.bytesEstimate }, m.bytes, "$code bytes")
            }
        }
    }

    @Test
    fun aDrawnPolygonsMeasureIsTheManifestsTotalsToo() {
        val ring = listOf(AreaPoint(46.76, -121.80), AreaPoint(46.76, -121.70), AreaPoint(46.82, -121.70), AreaPoint(46.82, -121.80))
        val area = AreaOfInterest(AreaShape.Polygon(listOf(ring)), 8, 15)
        val items = AreaManifest.tileItems(area, 8..15, 8..14)
        val m = measureArea(area, 8..15, 8..14, heapTiles)
        assertEquals(items.sumOf { it.count }, m.tiles)
        assertEquals(items.sumOf { it.bytesEstimate }, m.bytes)
    }

    @Test
    fun theFullDetailFloorIsBelowTheFullDetailMeasure() {
        for (code in listOf("LIE", "AND", "SMR")) {
            val c = Countries.byCode(code)!!
            val less = countryArea(c, AreaPicker.LESS_MAX_ZOOM)!!.let { measureArea(it, 8..13, 8..13, heapTiles) }
            val full = countryArea(c, AreaPicker.FULL_MAX_ZOOM)!!.let { measureArea(it, 8..15, 8..14, heapTiles) }
            assertTrue(less.topTiles > 0)
            assertTrue(fullAtLeast(less) in less.bytes..full.bytes, "$code: ${fullAtLeast(less)} within ${less.bytes}..${full.bytes}")
        }
    }

    @Test
    fun everyCountryButAntarcticaIsAnArea() {
        val none = Countries.all.filter { countryArea(it, AreaPicker.LESS_MAX_ZOOM) == null }.map { it.code }
        assertEquals(listOf("ATA"), none, "Antarctica lies past 85.05 degrees and around the world; it is a greyed row")
    }

    @Test
    fun theLargestCountriesAreTooLargeForTheHeapAtLessDetailAndTheSmallestFit() {
        fun tooLarge(code: String): Boolean = countryArea(Countries.byCode(code)!!, AreaPicker.LESS_MAX_ZOOM)!!.let { a ->
            AreaLimit.refusal(a, 6..13, 8..13, heapTiles) != null
        }
        for (code in listOf("RUS", "USA", "CAN")) assertTrue(tooLarge(code), code)
        for (code in listOf("LIE", "CHE", "ISL")) assertFalse(tooLarge(code), code)
    }

    @Test
    fun aRowOpensAtFullDetailOnlyWhenItFitsTheBudget() {
        val less = CountryMeasure(30 * mb, 250, false)
        val full = CountryMeasure(200 * mb, 1_500, false)
        val measuring = countryRow(null, 0, 0, 2048)
        assertEquals(RowState.MEASURING, measuring.state)
        assertEquals(MEASURING, measuring.sizeText)
        val fits = countryRow(CountrySizes.Entry(less, full, "a1", "a2"), 0, 0, 2048)
        assertEquals(DETAIL_FULL, fits.detail)
        assertEquals("About 200 MB at full detail", fits.sizeText)
        assertFalse(fits.greyed)
        // Full detail past the budget: the row is at less detail and fits.
        val lessRow = countryRow(CountrySizes.Entry(less, full, "a1", "a2"), 1900 * mb, 0, 2048)
        assertEquals(DETAIL_LESS, lessRow.detail)
        assertEquals("About 30 MB at less detail", lessRow.sizeText)
        assertEquals(RowState.FITS, lessRow.state)
        // Not measured at full detail (the less-detail size did not fit when measured): less detail.
        assertEquals(DETAIL_LESS, countryRow(CountrySizes.Entry(less, null, "a1", "a2"), 0, 0, 2048).detail)
    }

    @Test
    fun aRowOverTheBudgetIsGreyedWithTheBudgetSentence() {
        val row = countryRow(CountrySizes.Entry(CountryMeasure(1157 * mb, 12_512, false), null, "a1", "a2"), 1638 * mb, 0, 2048)
        assertEquals(RowState.OVER_BUDGET, row.state)
        assertTrue(row.greyed)
        assertEquals(
            "This area is about 1.1 GB; 1.6 GB of your 2 GB offline budget is in use. Make the area smaller, choose less detail, or raise the budget in Settings › Storage.",
            row.sentence,
        )
        assertEquals("About 1.1 GB at less detail", row.sizeText)
        // Its own bytes already pinned (downloaded before) do not count twice.
        assertEquals(RowState.FITS, countryRow(CountrySizes.Entry(CountryMeasure(1157 * mb, 12_512, false), null, "a1", "a2"), 1638 * mb, 1157 * mb, 2048).state)
    }

    @Test
    fun aRowTheHeapRefusesIsGreyedWithTheTooLargeSentence() {
        val row = countryRow(CountrySizes.Entry(CountryMeasure(0, 0, true), null, null, null), 0, 0, 2048)
        assertEquals(RowState.TOO_LARGE, row.state)
        assertTrue(row.greyed)
        assertEquals("Too large to download whole at this detail; draw an area instead", row.sentence)
    }

    @Test
    fun theListIsGroupedByContinentThenAlphabeticalAndTheFilterUsesFind() {
        val groups = byContinent(Countries.all)
        assertEquals(groups.map { it.first }.sorted(), groups.map { it.first })
        for ((_, cs) in groups) assertEquals(cs.map { it.name }.sortedWith(String.CASE_INSENSITIVE_ORDER), cs.map { it.name }.sortedWith(String.CASE_INSENSITIVE_ORDER))
        assertEquals(Countries.all.size, groups.sumOf { it.second.size })
        val europe = groups.first { it.first == "Europe" }.second
        assertTrue(europe.any { it.code == "CHE" } && europe.any { it.code == "LIE" })
        val filtered = countryRows(Countries.all, "swit")
        assertEquals(1, filtered.size)
        assertNull(filtered[0].first, "a filtered list is flat, in the find's order")
        assertEquals("CHE", filtered[0].second.first().code)
        assertEquals(groups.size, countryRows(Countries.all, " ").size)
    }

    @Test
    fun theMarksAndTheSizeText() {
        assertEquals(DOWNLOADED_MARK, downloadMark("done"))
        assertEquals(DOWNLOADING_MARK, downloadMark("downloading"))
        assertNull(downloadMark("partial"))
        assertNull(downloadMark(null))
        assertEquals("About 4.2 MB at less detail", rowSize((4.2 * mb).toLong(), DETAIL_LESS))
    }

    @Test
    fun aTapGoesIntoTheEdgeItLengthensLeast() {
        val square = listOf(0.0 to 0.0, 100.0 to 0.0, 100.0 to 100.0, 0.0 to 100.0)
        assertEquals(0, bestEdge(square, 50.0 to -10.0)) // above the top edge (0 -> 1)
        assertEquals(1, bestEdge(square, 110.0 to 50.0)) // right of the right edge (1 -> 2)
        assertEquals(3, bestEdge(square, -10.0 to 50.0)) // the closing edge (3 -> 0)
        val ring = listOf(AreaPoint(0.0, 0.0), AreaPoint(0.0, 1.0), AreaPoint(1.0, 1.0))
        assertEquals(listOf(AreaPoint(0.0, 0.0), AreaPoint(0.0, 1.0), AreaPoint(9.0, 9.0), AreaPoint(1.0, 1.0)), insert(ring, 2, AreaPoint(9.0, 9.0)))
        assertEquals(4, insert(ring, 9, AreaPoint(9.0, 9.0)).size)
    }

    @Test
    fun aMidpointIsTheMiddleAsDrawnAndRingsTurnCounterClockwise() {
        val m = midpoint(AreaPoint(46.0, -122.0), AreaPoint(47.0, -121.0))
        assertEquals(-121.5, m.lon, 1e-9)
        assertTrue(m.lat > 46.5 && m.lat < 46.51, "Mercator's middle lies a little poleward: ${m.lat}")
        val cw = listOf(AreaPoint(46.0, -122.0), AreaPoint(47.0, -122.0), AreaPoint(47.0, -121.0), AreaPoint(46.0, -121.0)) // north, then east: clockwise
        assertEquals(cw.reversed(), counterClockwise(cw))
        assertEquals(cw.reversed(), counterClockwise(cw.reversed()))
        val area = AreaDraw.area(cw)
        assertEquals(2, area.manifestVersion)
        assertEquals(15, area.maxZoom)
    }

    @Test
    fun theAroundFlowsPresetsFollowTheUnits() {
        assertEquals(listOf("2 km", "5 km", "10 km", "20 km"), radiusPresets(ContourUnits.METERS).map { presetLabel(it, ContourUnits.METERS) })
        assertEquals(listOf("1 mi", "3 mi", "5 mi", "10 mi"), radiusPresets(ContourUnits.FEET).map { presetLabel(it, ContourUnits.FEET) })
        assertEquals(5_000.0, defaultRadius(ContourUnits.METERS))
        assertEquals(3 * 1609.344, defaultRadius(ContourUnits.FEET), 1e-6)
        assertEquals(7_500.0, typedRadius("7,5", ContourUnits.METERS))
        assertEquals(2 * 1609.344, typedRadius(" 2 ", ContourUnits.FEET)!!, 1e-6)
        assertNull(typedRadius("-1", ContourUnits.METERS))
        assertNull(typedRadius("x", ContourUnits.METERS))
        assertTrue(radiusPresets(ContourUnits.FEET).all { it <= AreaPicker.MAX_RADIUS_M })
    }

    @Test
    fun theDefaultDetailAndTheOtherDetailsLine() {
        assertEquals(DETAIL_FULL, defaultDetail(country = false, fullFits = false))
        assertEquals(DETAIL_LESS, defaultDetail(country = true, fullFits = null))
        assertEquals(DETAIL_LESS, defaultDetail(country = true, fullFits = false))
        assertEquals(DETAIL_FULL, defaultDetail(country = true, fullFits = true))
        val ring = listOf(AreaPoint(46.76, -121.80), AreaPoint(46.76, -121.70), AreaPoint(46.82, -121.70))
        val full = AreaOfInterest(AreaShape.Polygon(listOf(ring)), 8, 15)
        val less = full.copy(maxZoom = 13)
        fun plan(a: AreaOfInterest, bytes: Long, tooLarge: String? = null) = AreaDownload.Plan(
            a, AreaManifest(a.id, "x", a, AreaManifest.STATUS_READY, listOf(io.github.jasondorjeshort.dystopo.areas.TilesItem("dem", "png", "", emptyMap(), bytes))),
            "phone", 0, 0, 2048, tooLarge = tooLarge,
        )
        val s = AreaPicker.Summary(1, plan(full, 170 * mb), plan(less, 30 * mb), false, null)
        assertEquals("Less detail (about 30 MB)", alternativeLine(DETAIL_FULL, s))
        assertEquals("Full detail (about 170 MB)", alternativeLine(DETAIL_LESS, s))
        assertEquals("Full detail", alternativeLine(DETAIL_LESS, AreaPicker.Summary(1, null, plan(less, 30 * mb), true, null)))
        assertEquals("Full detail", alternativeLine(DETAIL_LESS, AreaPicker.Summary(1, plan(full, 0, AreaLimit.TOO_LARGE), plan(less, 30 * mb), false, null)))
        assertNull(alternativeLine(DETAIL_LESS, AreaPicker.Summary(1, null, plan(less, 30 * mb), false, null)))
    }

    @Test
    fun aCountryAcrossTheAntimeridianFitsItsLargestPart() {
        val fiji = Countries.byCode("FJI")!!
        assertTrue(fiji.crossesAntimeridian)
        val b = countryBox(fiji.rings.map { AreaOfInterest.openRing(it) })
        assertTrue(b.maxLon - b.minLon < 10, "one side of 180: $b")
        val che = countryBox(Countries.byCode("CHE")!!.rings)
        assertTrue(che.minLon > 5 && che.maxLon < 11, "$che")
        assertNotNull(che)
    }
}
