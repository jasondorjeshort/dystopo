package io.github.jasondorjeshort.dystopo.server

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
        for (source in listOf(NorwaySource(kotlin.io.path.createTempDirectory("no").toFile()), AustriaSource(kotlin.io.path.createTempDirectory("at").toFile()))) {
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
        assertEquals(1L, NorwaySource(kotlin.io.path.createTempDirectory("no").toFile()).fetch(jotunheimen).counters["skippedCoarse"])
        val tauern = MercatorWindow.forBbox(BoundingBox(12.4, 46.9, 13.0, 47.2), 9)
        assertEquals(1L, AustriaSource(kotlin.io.path.createTempDirectory("at").toFile()).fetch(tauern).counters["skippedCoarse"])
    }

    @Test
    fun registryHasEveryIdTheSourcesCredit() {
        for (source in listOf(NorwaySource(), AustriaSource())) {
            for (id in source.registryIds) assertTrue(SourceRegistry.builtIn[id] != null, "$id missing from sources.json")
        }
    }
}
