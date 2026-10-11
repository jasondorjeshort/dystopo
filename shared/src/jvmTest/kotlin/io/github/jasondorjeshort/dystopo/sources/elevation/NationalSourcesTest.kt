package io.github.jasondorjeshort.dystopo.sources.elevation

import io.github.jasondorjeshort.dystopo.geo.BoundingBox
import io.github.jasondorjeshort.dystopo.geo.LambertAzimuthalEqualArea
import io.github.jasondorjeshort.dystopo.geo.MercatorWindow
import io.github.jasondorjeshort.dystopo.sources.SourceRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Offline checks of the Norway and Austria sources: tiling, URLs, coverage gates. */
class NationalSourcesTest {
    private val rainier = MercatorWindow.forBbox(BoundingBox(-121.86, 46.78, -121.66, 46.93), 13)

    @Test
    fun norwayPicksTheCoarsestBlockResolutionNoLargerThanTheCells() {
        assertEquals(1, NorwaySource.resolutionFor(0.6))
        assertEquals(1, NorwaySource.resolutionFor(1.9))
        assertEquals(2, NorwaySource.resolutionFor(2.3))
        assertEquals(8, NorwaySource.resolutionFor(9.1))
        assertEquals(16, NorwaySource.resolutionFor(18.5))
        assertEquals(16, NorwaySource.resolutionFor(29.0))
    }

    @Test
    fun norwayBlockUrlIsAFixedGridCell() {
        val url = NorwaySource.blockUrl(145_408, 6_850_560, 4)
        assertTrue(url.startsWith("https://wcs.geonorge.no/skwms1/wcs.hoyde-dtm-nhm-25833?"), url)
        assertTrue("coverage=nhm_dtm_topo_25833&" in url, url)
        assertTrue("&bbox=145408,6850560,146432,6851584&width=256&height=256" in url, url)
    }

    @Test
    fun norwayRejectsAnErrorPageInsteadOfATiff() {
        val html = "<html><body>ArcGIS Server Error</body></html>".encodeToByteArray()
        val e = assertFailsWith<IllegalArgumentException> { NorwaySource.parseBlock(html, 0.0, 256.0, 1.0) }
        assertTrue("did not return a TIFF" in e.message!!)
    }

    @Test
    fun norwayKeepsSeaLevelUnlessTheWholeBlockIsFill() {
        val partial = FloatArray(16) { if (it < 8) 0f else 12.5f }
        assertTrue(NorwaySource.fillToNaN(partial.copyOf()).contentEquals(partial))
        assertTrue(NorwaySource.fillToNaN(FloatArray(16)).all { it.isNaN() })
        assertTrue(NorwaySource.fillToNaN(FloatArray(16) { if (it == 3) Float.NaN else 0f }).all { it.isNaN() })
    }

    @Test
    fun borderMaskHoldsTheNeighboursNotNorway() {
        val mask = BorderMask.norwayNeighbours
        // Sweden, Finland, Russia.
        for ((lat, lon) in listOf(67.855 to 20.225, 59.33 to 18.07, 63.40 to 12.30, 60.17 to 24.94, 68.97 to 33.08)) {
            assertTrue(mask.contains(lon, lat), "$lat,$lon should be beyond the border")
        }
        // Norway: Galdhøpiggen, towns along the coast and near the borders, and the sea off the coast.
        for ((lat, lon) in listOf(61.6363 to 8.3125, 59.91 to 10.75, 68.44 to 17.43, 69.73 to 30.05, 63.43 to 10.39, 62.0 to 4.5)) {
            assertTrue(!mask.contains(lon, lat), "$lat,$lon should be on the Norwegian side")
        }
    }

    @Test
    fun borderMaskRasterMatchesThePointTest() {
        // A z12 window across the Norway/Sweden border.
        val window = MercatorWindow.forBbox(BoundingBox(11.9, 63.25, 12.4, 63.45), 12)
        val raster = BorderMask.norwayNeighbours.rasterize(window)!!
        var inside = 0
        for (j in 0 until window.height) for (i in 0 until window.width) {
            val expected = BorderMask.norwayNeighbours.contains(window.lonAt(i), window.latAt(j))
            assertEquals(expected, raster[j * window.width + i], "pixel $i,$j")
            if (expected) inside++
        }
        assertTrue(inside in 1 until window.width * window.height, "the window straddles the border ($inside inside)")
        assertNull(BorderMask.norwayNeighbours.rasterize(rainier))
    }

    @Test
    fun clipRingKeepsThePartInsideTheBox() {
        val square = doubleArrayOf(0.0, 0.0, 10.0, 0.0, 10.0, 10.0, 0.0, 10.0)
        val clipped = BorderMask.clipRing(square, 5.0, -1.0, 20.0, 20.0)!!
        val mask = BorderMask("t", listOf(clipped), null)
        assertTrue(mask.contains(7.0, 5.0))
        assertTrue(!mask.contains(3.0, 5.0))
        assertNull(BorderMask.clipRing(square, 11.0, 0.0, 20.0, 5.0))
    }

    private fun feed(vararg editions: Pair<String, Int>): String = buildString {
        append("<feed>")
        for ((date, count) in editions) {
            var k = 0
            for ((n, es) in AustriaSource.TILES) for (e in es) {
                if (k++ >= count) break
                append("<entry><title>ALS DTM CRS3035RES50000mN${n * 50000}E${e * 50000} Höhenraster 1m Stichtag $date</title></entry>")
                append("<entry><title>ALS DSM CRS3035RES50000mN${n * 50000}E${e * 50000} Höhenraster 1m Stichtag $date</title></entry>")
            }
        }
        append("</feed>")
    }

    @Test
    fun austriaEditionIsTheNewestCompleteEvrfOneInTheFeed() {
        // 2021 is GHA, 15.09.2026 is only partly published: 2025 wins.
        val e = AustriaEditions.parse(feed("15.09.2021" to 55, "15.09.2025" to 55, "15.09.2026" to 12))!!
        assertEquals("20250915", e.id)
        assertEquals(55, e.tileCount)
        assertEquals(53 to 90, AustriaSource.tileOf(LambertAzimuthalEqualArea.EUROPE.forward(47.0742, 12.6947).let { it.easting }, LambertAzimuthalEqualArea.EUROPE.forward(47.0742, 12.6947).northing, e))
        assertEquals("20260915", AustriaEditions.parse(feed("15.09.2025" to 55, "15.09.2026" to 55))!!.id)
        assertNull(AustriaEditions.parse(feed("15.09.2021" to 55)))
        assertNull(AustriaEditions.parse("<html>maintenance</html>"))
    }

    @Test
    fun austriaEditionFallsBackToACachedFeedThenThePinnedOne() {
        val dir = kotlin.io.path.createTempDirectory("at-atom").toFile().path
        val down = { _: String -> throw java.io.IOException("unreachable") }
        val pinned = AustriaEditions.resolve(dir, down)
        assertEquals("pinned", pinned.from)
        assertEquals(AustriaSource.EDITION, pinned.id)
        assertTrue("unreachable" in pinned.problem!!)

        val fetched = AustriaEditions.resolve(dir) { feed("15.09.2026" to 55).encodeToByteArray() }
        assertEquals("atom" to "20260915", fetched.from to fetched.id)
        // Fresh copy: no fetch. Stale copy and BEV down: the copy, marked as such.
        assertEquals("20260915", AustriaEditions.resolve(dir) { error("must not fetch") }.id)
        java.io.File(dir, "atom/service.xml").setLastModified(System.currentTimeMillis() - AustriaEditions.MAX_AGE_MS - 60_000)
        val stale = AustriaEditions.resolve(dir, down)
        assertEquals("atom-cached" to "20260915", stale.from to stale.id)
        // The source reads the edition it is given.
        assertEquals(
            "https://data.bev.gv.at/download/ALS/DTM/20260915/ALS_DTM_CRS3035RES50000mN2650000E4500000.tif",
            AustriaSource.tileUrl(53, 90, stale.id),
        )
    }

    @Test
    fun austriaHasFiftyFiveTilesAndFindsGrossglockner() {
        assertEquals(55, AustriaSource.TILES.values.sumOf { it.count() })
        val p = LambertAzimuthalEqualArea.EUROPE.forward(47.0742, 12.6947)
        assertEquals(53 to 90, AustriaSource.tileOf(p.easting, p.northing))
        assertEquals(
            "https://data.bev.gv.at/download/ALS/DTM/20250915/ALS_DTM_CRS3035RES50000mN2650000E4500000.tif",
            AustriaSource.tileUrl(53, 90),
        )
        // Munich, in Germany, lies in no BEV tile.
        val munich = LambertAzimuthalEqualArea.EUROPE.forward(48.14, 11.58)
        assertNull(AustriaSource.tileOf(munich.easting, munich.northing))
    }

    @Test
    fun outsideCoverageFetchesNothing() {
        for (source in listOf(NorwaySource(kotlin.io.path.createTempDirectory("no").toFile().path), AustriaSource(kotlin.io.path.createTempDirectory("at").toFile().path))) {
            val r = source.fetch(rainier)
            assertEquals(1L, r.counters["outsideCoverage"], source.id)
            assertTrue(r.grid.values.all { it.isNaN() })
            assertEquals(emptyList(), r.provenance)
        }
    }

    @Test
    fun coarseWindowsAreSkipped() {
        // z9 over Jotunheimen and the Hohe Tauern: ~150 m cells, Copernicus is enough.
        val jotunheimen = MercatorWindow.forBbox(BoundingBox(8.0, 61.5, 8.6, 61.8), 9)
        assertEquals(1L, NorwaySource(kotlin.io.path.createTempDirectory("no").toFile().path).fetch(jotunheimen).counters["skippedCoarse"])
        val tauern = MercatorWindow.forBbox(BoundingBox(12.4, 46.9, 13.0, 47.2), 9)
        assertEquals(1L, AustriaSource(kotlin.io.path.createTempDirectory("at").toFile().path).fetch(tauern).counters["skippedCoarse"])
    }

    @Test
    fun registryHasEveryIdTheSourcesCredit() {
        for (source in listOf(NorwaySource(), AustriaSource())) {
            for (id in source.registryIds) assertTrue(SourceRegistry.builtIn[id] != null, "$id missing from sources.json")
        }
    }

    @Test
    fun licenceUrlsPointAtLicencesNotReadmes() {
        // T66: the geoid grids pointed at PROJ-data READMEs; the README is named in the notes instead.
        for (s in SourceRegistry.builtIn.sources) {
            assertTrue(!s.licenseUrl.contains("README", ignoreCase = true), "${s.id}: licenseUrl ${s.licenseUrl}")
            if (s.license == "CC-BY-4.0") assertEquals("https://creativecommons.org/licenses/by/4.0/", s.licenseUrl, s.id)
            if (s.license == "CC0-1.0") assertEquals("https://creativecommons.org/publicdomain/zero/1.0/", s.licenseUrl, s.id)
        }
    }
}
