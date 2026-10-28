package io.github.jasondorjeshort.dystopo.app.areas

import io.github.jasondorjeshort.dystopo.areas.AreaDetail
import io.github.jasondorjeshort.dystopo.areas.AreaManifest
import io.github.jasondorjeshort.dystopo.areas.AreaOfInterest
import io.github.jasondorjeshort.dystopo.areas.AreaPoint
import io.github.jasondorjeshort.dystopo.areas.AreaShape
import io.github.jasondorjeshort.dystopo.areas.TilesItem
import io.github.jasondorjeshort.dystopo.boundaries.Countries
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T157 (ADR 0041 §5's T157 note, ADR 0033): the medium detail in the phone's records, plans, picker and country rows. */
class MediumDetailTest {
    private val mb = 1024L * 1024L

    /** v0.1.13's `areas.json` (written as v0.1.10 writes it, ADR 0041 §2) and its `areas2.json` (two polygon records). */
    private val main: String = javaClass.getResource("/areas/areas-v0.1.13.json")!!.readText()
    private val sibling: String = javaClass.getResource("/areas/areas2-v0.1.13.json")!!.readText()

    private val disc = AreaOfInterest.around(AreaPoint(46.786, -121.735), 5000.0, name = "Around Paradise")

    @Test
    fun aV0113PairOfFilesIsReadUnchangedWithItsIdsAndWrittenBackByteEqual() {
        val files = AreaFiles.decode(main, sibling)
        assertEquals(5, files.records.size)
        assertTrue(files.records.none { it.needsNewerApp })
        assertEquals(listOf("box", "corridor", "corridor", "polygon", "polygon"), files.records.map { it.area.kind })
        // Every id is what v0.1.13 computed, and this build computes the same from the area (no cap: the canonical form unchanged).
        for (r in files.records.filter { it.pack == null }) assertEquals(r.id, r.area.id, r.name)
        assertTrue(files.records.all { it.area.demMaxZoom == null })
        assertEquals(main, files.mainText(), "areas.json byte-equal")
        assertEquals(sibling, files.siblingText(), "areas2.json byte-equal (still version 2)")
    }

    @Test
    fun aMediumRecordGoesToTheSiblingAsVersion3AndReadsBack() {
        val medium = disc.at(AreaDetail.MEDIUM)
        val rec = Areas.Record(id = medium.id, name = "Around Paradise", area = medium, createdAt = 2L, state = "done", source = "sources", bytes = 9, tiles = 4)
        val files = AreaFiles(AreaFiles.decode(main, sibling).records + rec)
        assertEquals(main, files.mainText(), "a version-1 reader never sees it")
        val doc = MiniJson.parseObject(files.siblingText()!!)
        assertEquals(3L, (doc["version"] as Number).toLong())
        val written = (doc["areas"] as List<*>).last() as Map<*, *>
        assertEquals(3L, (written["version"] as Number).toLong())
        assertEquals(listOf(8L, 14L), (written["zooms"] as List<*>).map { (it as Number).toLong() }, "an older build pins its box at z8 to z14")
        assertEquals(13L, ((written["area"] as Map<*, *>)["demMaxZoom"] as Number).toLong())
        val back = AreaFiles.decode(files.mainText(), files.siblingText())
        assertEquals(rec, back.records.last())
        assertEquals(medium.id, back.records.last().area.id)
        // A v0.1.14 reader (manifest version 2) keeps it opaque rather than fetch its elevation to z14: the same check, one lower.
        assertTrue(runCatching { AreaManifest.checkVersion(3) }.isSuccess)
    }

    @Test
    fun theSizeLinesAndTheChoices() {
        assertEquals("About 60 MB (900 tiles) at medium detail", sizeLine(60 * mb, 900, 8, 14, 13))
        assertEquals("About 60 MB (900 tiles) at zoom 8 to 14, elevation to 12", sizeLine(60 * mb, 900, 8, 14, 12))
        assertEquals("About 1.5 GB (12,000 tiles) at zoom 8 to 14", sizeLine(1536 * mb, 12_000, 8, 14))
        assertEquals("Medium detail (about 60 MB)", detailChoiceLine(AreaDetail.MEDIUM, 60 * mb))
        assertEquals("Medium detail", detailChoiceLine(AreaDetail.MEDIUM, null))
        assertEquals(listOf("Full detail", "Medium detail", "Less detail"), AreaDetail.entries.map { detailTitle(it) })
        assertEquals("Every path and place of full detail; the terrain shading and contours are coarser", MEDIUM_DETAIL_HINT)
    }

    private fun plan(a: AreaOfInterest, bytes: Long, budgetMb: Long = 2048) = AreaDownload.Plan(
        a, AreaManifest(a.id, "x", a, AreaManifest.STATUS_READY, listOf(TilesItem("dem", "png", "", emptyMap(), bytes))), "phone", 0, 0, budgetMb,
    )

    @Test
    fun theCardListsTheTwoDetailsNotShown() {
        val s = AreaPicker.Summary(1, plan(disc, 170 * mb), plan(disc.at(AreaDetail.LESS), 30 * mb), false, null, medium = plan(disc.at(AreaDetail.MEDIUM), 45 * mb))
        assertEquals(listOf(DETAIL_MEDIUM to "Medium detail (about 45 MB)", DETAIL_LESS to "Less detail (about 30 MB)"), alternativeLines(DETAIL_FULL, s))
        assertEquals(listOf(DETAIL_FULL to "Full detail (about 170 MB)", DETAIL_LESS to "Less detail (about 30 MB)"), alternativeLines(DETAIL_MEDIUM, s))
        assertEquals(listOf(DETAIL_MEDIUM to "Medium detail (about 45 MB)", DETAIL_FULL to "Full detail (about 170 MB)"), alternativeLines(DETAIL_LESS, s))
        assertEquals("Medium detail (about 45 MB)", alternativeLine(DETAIL_FULL, s))
        // A country while its deeper details are measured: their titles alone.
        val pending = AreaPicker.Summary(1, null, plan(disc.at(AreaDetail.LESS), 30 * mb), true, null)
        assertEquals(listOf(DETAIL_MEDIUM to "Medium detail", DETAIL_FULL to "Full detail"), alternativeLines(DETAIL_LESS, pending))
        assertEquals(s.medium, s.plan(DETAIL_MEDIUM))
        // The default: a country opens at the deepest detail that fits; a drawn area or a disc at full.
        assertEquals(DETAIL_MEDIUM, defaultDetail(country = true, fullFits = false, mediumFits = true))
        assertEquals(DETAIL_LESS, defaultDetail(country = true, fullFits = false, mediumFits = false))
        assertEquals(DETAIL_FULL, defaultDetail(country = true, fullFits = true, mediumFits = true))
        assertEquals(DETAIL_FULL, defaultDetail(country = false, fullFits = false, mediumFits = false))
        // A region's medium detail is tiles, as its less detail is.
        assertEquals(REGION_LESS_LINE, regionPathLine(true, DETAIL_MEDIUM))
    }

    @Test
    fun aCountryRowShowsThreeSizesAndOpensAtTheDeepestThatFits() {
        val less = CountryMeasure(30 * mb, 250, false)
        val medium = CountryMeasure(45 * mb, 900, false)
        val full = CountryMeasure(400 * mb, 3_000, false)
        val e = CountrySizes.Entry(less, full, "a1", "a3", medium, "a2")
        assertEquals(listOf("a1", "a2", "a3"), e.ids)
        val fits = countryRow(e, 0, 0, 2048)
        assertEquals("Less 30 MB · Medium 45 MB · Full 400 MB", fits.sizeText)
        assertEquals(DETAIL_FULL, fits.detail)
        val mediumRow = countryRow(e, 1800 * mb, 0, 2048) // 248 MB left: medium fits, full does not
        assertEquals(DETAIL_MEDIUM, mediumRow.detail)
        assertEquals(RowState.FITS, mediumRow.state)
        assertEquals(45 * mb, mediumRow.bytes)
        assertEquals("Less 30 MB · Medium 45 MB · Full 400 MB", mediumRow.sizeText)
        val lessRow = countryRow(e, 2010 * mb, 0, 2048) // 38 MB left
        assertEquals(DETAIL_LESS, lessRow.detail)
        assertEquals("About 45 MB at medium detail", rowSize(45 * mb, DETAIL_MEDIUM))
        assertEquals("Less 1.1 GB · Medium 1.3 GB · Full 14.0 GB", rowSizes(1126 * mb, 1331 * mb, 14336 * mb))
    }

    @Test
    fun theFullDetailEstimateFromZ14IsAboveTheMeasureAndNearIt() {
        // Liechtenstein and Andorra: the full detail filled at z15 against the estimate from z14's cover.
        for (code in listOf("LIE", "AND")) {
            val c = Countries.byCode(code)!!
            val m = countryArea(c, AreaDetail.MEDIUM)!!
            assertEquals(13, m.demMaxZoom)
            val medium = measureArea(m, m.demZooms(6, 15), m.osmZooms(14))
            val full = countryArea(c, AreaDetail.FULL)!!.let { measureArea(it, 6..15, 8..14) }
            val z14 = io.github.jasondorjeshort.dystopo.areas.TileCover.of(m, 14, 1).size
            val est = estimateFull(medium, z14)
            assertTrue(est.estimated)
            assertTrue(est.bytes >= full.bytes && est.bytes < full.bytes * 1.3, "$code: estimate ${est.bytes / mb} MB, measure ${full.bytes / mb} MB")
            assertTrue(medium.bytes in countryArea(c, AreaDetail.LESS)!!.let { measureArea(it, 6..13, 8..13) }.bytes..full.bytes)
            // One cover per zoom serves all three details: the shared counts give the same measures.
            val counts = HashMap<Int, Int>()
            val shared = measureCounts(6..13, 8..14) { z -> counts.getOrPut(z) { io.github.jasondorjeshort.dystopo.areas.TileCover.of(m, z, 1).size } }
            assertEquals(medium.bytes, shared.bytes)
            assertEquals(medium.tiles, shared.tiles)
        }
    }

    @Test
    fun aServerWithoutZoomCapsIsNotAskedForAMediumManifest() {
        assertEquals(emptyList(), serverZoomCapsOf(emptyMap()), "a server from before T157")
        assertEquals(listOf("dem"), serverZoomCapsOf(mapOf("zoomCaps" to listOf("dem"))))
    }

    @Test
    fun aMediumAreaOfABoxIsNotMadeAndOfAPolygonIs() {
        val box = io.github.jasondorjeshort.dystopo.packs.PackBounds(-121.80, 46.76, -121.70, 46.82)
        val r = regionArea(box, AreaDetail.MEDIUM, "Paradise box")
        assertTrue(r.shape is AreaShape.Polygon)
        assertEquals(8 to 14, r.minZoom to r.maxZoom)
        assertEquals(13, r.demMaxZoom)
        assertNull(regionArea(box, 13, null).demMaxZoom)
    }
}
