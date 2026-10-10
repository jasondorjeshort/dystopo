package io.github.jasondorjeshort.dystopo.dem

import io.github.jasondorjeshort.dystopo.sources.SourceKind
import io.github.jasondorjeshort.dystopo.sources.SourceRegistry
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VerticalDatumTest {
    @Test
    fun ellipsoidShiftIsTheTranslationAlongUp() {
        // On the equator at 0° longitude "up" is +X, so the shift is -TX.
        assertEquals(-0.99343, Datums.wgs84MinusNad83Height(0.0, 0.0), 1e-9)
        // At the north pole "up" is +Z.
        assertEquals(0.52655, Datums.wgs84MinusNad83Height(90.0, 0.0), 1e-9)
    }

    @Test
    fun ellipsoidShiftIsAboutAMeterAcrossTheUs() {
        for ((lat, lon) in listOf(46.85 to -121.76, 39.0 to -105.0, 40.7 to -74.0, 25.8 to -80.2)) {
            val shift = Datums.wgs84MinusNad83Height(lat, lon)
            assertTrue(shift in -2.0..0.0, "shift at $lat,$lon = $shift")
        }
    }

    @Test
    fun navd88ToEgm2008CombinesTheModels() {
        val geoid18 = GeoidModel { _, _ -> -17.0 }
        val egm2008 = GeoidModel { _, _ -> -16.5 }
        val shift = Datums.wgs84MinusNad83Height(39.0, -105.0)
        assertEquals(-17.0 + shift + 16.5, Datums.navd88ToEgm2008(39.0, -105.0, geoid18, egm2008), 1e-12)
    }

    @Test
    fun hybridGeoidIsChosenByLocation() {
        fun at(lat: Double, lon: Double) = Datums.hybridGeoidFor(lat, lon)?.registryId
        assertEquals("geoid18", at(46.85, -121.76)) // Rainier
        assertEquals("geoid18", at(40.75, -113.9)) // Bonneville
        assertEquals("geoid12b-alaska", at(63.07, -151.0)) // Denali
        assertEquals("geoid12b-alaska", at(52.0, 177.0)) // western Aleutians, east of the antimeridian
        assertEquals("geoid12b-alaska", at(52.0, -179.9))
        assertEquals("geoid12b-hawaii", at(19.82, -155.47)) // Mauna Kea
        assertEquals("geoid18-prvi", at(18.2, -66.5)) // Puerto Rico
        // CONUS comes first where its box overlaps Alaska's (coastal British Columbia).
        assertEquals("geoid18", at(55.0, -129.0))
        assertNull(at(45.83, 6.86)) // Mont Blanc
        assertNull(at(64.1, -21.9)) // Iceland: north-west hemisphere but no US grid
        assertNull(at(-50.9, -73.0)) // Patagonia
    }

    @Test
    fun hybridGeoidBoxTestsHandleTheAntimeridian() {
        fun ids(w: Double, s: Double, e: Double, n: Double) = Datums.hybridGeoidsIntersecting(w, s, e, n).map { it.registryId }
        assertEquals(listOf("geoid12b-alaska"), ids(179.5, 51.0, -179.5, 52.0)) // box crossing the antimeridian
        assertEquals(listOf("geoid12b-alaska"), ids(179.5, 51.0, 180.5, 52.0)) // same box, unwrapped
        assertEquals(listOf("geoid12b-alaska"), ids(-151.15, 62.99, -150.85, 63.13))
        assertEquals(emptyList(), ids(6.8, 45.8, 6.9, 45.9))
        assertEquals(emptyList(), ids(-22.0, 64.0, -21.8, 64.2))
        val hawaii = Datums.HYBRID_GEOIDS.single { it.registryId == "geoid12b-hawaii" }
        assertFalse(hawaii.intersects(-153.9, 19.0, -153.0, 20.0))
        assertTrue(hawaii.intersects(-154.5, 19.0, -153.0, 20.0))
    }

    @Test
    fun everyHybridGeoidHasARegistryEntryForItsGrid() {
        for (g in Datums.HYBRID_GEOIDS) {
            val entry = SourceRegistry.builtIn[g.registryId] ?: error("${g.registryId} not in sources.json")
            assertEquals(SourceKind.GEOID, entry.kind)
            assertEquals("https://cdn.proj.org/${g.file}", entry.url)
        }
    }

    @Test
    fun swissHeightsGoThroughChgeoThenEgm2008() {
        // Zimmerwald (swisstopo refsys 7.1): LN02 897.915 m, ETRS89 ellipsoidal 947.149 m, so N(LN02) = 49.234 m.
        val chgeo = GeoidModel { _, _ -> 49.234 }
        val egm2008 = GeoidModel { _, _ -> 49.5 }
        assertEquals(49.234 - 49.5, Datums.swissToEgm2008(46.877, 7.465, chgeo, egm2008), 1e-12)
        assertEquals("ch_swisstopo_chgeo2004_ETRS89_LN02.tif", Datums.swissGeoidFor(5728)?.file)
        assertEquals("ch_swisstopo_chgeo2004_ETRS89_LHN95.tif", Datums.swissGeoidFor(5729)?.file)
        assertNull(Datums.swissGeoidFor(5703))
        for (g in Datums.SWISS_GEOIDS) {
            val entry = SourceRegistry.builtIn[g.registryId] ?: error("${g.registryId} not in sources.json")
            assertEquals(SourceKind.GEOID, entry.kind)
        }
        assertEquals("https://cdn.proj.org/${Datums.swissGeoidFor(5728)!!.file}", SourceRegistry.builtIn["chgeo2004"]?.url)
    }

    @Test
    fun pa11ShiftDiffersFromNad83Of2011ByAboutTenCentimetersInHawaii() {
        val a = Datums.wgs84MinusNad83Height(19.82, -155.47, Nad83Frame.NAD83_2011)
        val b = Datums.wgs84MinusNad83Height(19.82, -155.47, Nad83Frame.NAD83_PA11)
        assertTrue(abs(a - b) in 0.05..0.2, "2011 $a vs PA11 $b")
        val hybrid = GeoidModel { _, _ -> 18.0 }
        val egm = GeoidModel { _, _ -> 17.5 }
        assertEquals(18.0 + b - 17.5, Datums.navd88ToEgm2008(19.82, -155.47, hybrid, egm, Nad83Frame.NAD83_PA11), 1e-12)
    }
}
