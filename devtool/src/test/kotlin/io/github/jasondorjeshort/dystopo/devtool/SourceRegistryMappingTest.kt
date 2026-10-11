package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.server.*
import io.github.jasondorjeshort.dystopo.sources.elevation.ElevationSource // T80: nested Result needs the class, not the alias

import io.github.jasondorjeshort.dystopo.dem.ElevationGrid
import io.github.jasondorjeshort.dystopo.sources.SourceKind
import io.github.jasondorjeshort.dystopo.sources.SourceRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Every devtool elevation source must be backed by registry entries that carry a license and attribution. */
class SourceRegistryMappingTest {
    private val registry = SourceRegistry.builtIn

    @Test
    fun everyElevationSourceMapsToRegistryEntriesWithLicenseAndAttribution() {
        for (id in ElevationSource.ids) {
            val src = ElevationSource.byId(id) // constructors don't touch the network
            assertTrue(src.registryIds.isNotEmpty(), "$id has no registry ids")
            for (rid in src.registryIds) {
                val entry = registry[rid] ?: error("$id -> '$rid' is not in sources.json")
                assertTrue(entry.license.isNotBlank(), "$rid has no license")
                assertTrue(entry.attribution.isNotBlank(), "$rid has no attribution")
                assertTrue(entry.attributionHtml.isNotBlank(), "$rid has no HTML attribution")
            }
            assertTrue(src.registryIds.any { registry.getValue(it).kind == SourceKind.DEM }, "$id draws on no DEM")
            assertEquals(registry.attribution(src.registryIds), src.attribution)
        }
    }

    @Test
    fun bestCreditsEveryLayerAndTheGeoids() {
        assertEquals(
            listOf(
                "copernicus", "3dep", "3dep-1m", "swissalti3d", "kartverket-dtm", "bev-als-dtm", "hrdem", "linz-dem",
                "geoid18", "geoid18-prvi", "geoid12b-alaska", "geoid12b-hawaii", "chgeo2004", "href2018b", "bev-geoid", "cgg2013a", "nzgeoid2016", "egm2008",
            ),
            registry.ordered(ElevationSource.byId("best").registryIds),
        )
        // USGS layers are converted from US datums, so their full credit includes every hybrid geoid and EGM2008.
        for (id in listOf("3dep", "3dep-1m", "3dep-best")) {
            assertTrue(ElevationSource.byId(id).registryIds.containsAll(Geoids.REGISTRY_IDS), id)
        }
    }

    @Test
    fun resultAttributionFollowsProvenance() {
        val grid = ElevationGrid(1, 1, 10.0, floatArrayOf(100f))
        val best = ElevationSource.byId("best")
        val usOnly = ElevationSource.Result(grid, emptyMap(), listOf("3dep", "geoid18", "egm2008"))
        assertTrue("Copernicus" !in usOnly.attribution(best))
        assertTrue("3DEP" in usOnly.attribution(best))
        // Nothing contributed: fall back to the source's full credit.
        val noData = ElevationGrid(1, 1, 10.0, floatArrayOf(Float.NaN))
        assertEquals(best.attribution, ElevationSource.Result(noData, emptyMap(), emptyList()).attribution(best))
        assertEquals(listOf("copernicus"), ElevationSource.provenanceIfData(grid, listOf("copernicus")))
        assertEquals(emptyList(), ElevationSource.provenanceIfData(ElevationGrid(1, 1, 10.0, floatArrayOf(Float.NaN)), listOf("copernicus")))
    }
}
