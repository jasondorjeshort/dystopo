package io.github.jasondorjeshort.dystopo.reports

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T143: the report scrubber on synthetic text. Places are public (Rainier's box from `areas.json`, Mont Blanc) or
 * made up; the names in [Scrub.Known] are invented.
 */
class ScrubTest {
    private fun s(text: String, known: Scrub.Known = Scrub.Known()) = Scrub.text(text, known)

    /** No digit run that could still be a coordinate: a decimal with a fractional part within +-180. */
    private fun assertNoCoordinate(text: String) {
        val left = Regex("-?\\d{1,3}\\.\\d+").findAll(text).map { it.value }.toList()
        assertTrue(left.isEmpty(), "a decimal is left in: $text")
    }

    @Test
    fun coordinatePairsInEitherOrder() {
        assertEquals("view at <coords> z13", s("view at 46.8528, -121.7604 z13"))
        assertEquals("[<coords>]", s("[-121.7604,46.8528]"))
        assertEquals("(<coords>)", s("(45.8326 6.8652)"))
        assertEquals("a <coords>; b <coords>", s("a 46.85,-121.76; b 46.86,-121.75"))
        assertEquals("<coords>,1650.0", s("46.85,-121.76,1650.0"), "a third value (an elevation) is not part of the pair")
        // Out of range for degrees: not a coordinate.
        assertEquals("ratio 812.5, 913.25", s("ratio 812.5, 913.25"))
        assertEquals("<coords>", s("1.5 2.5"), "two short decimals in range read as a pair: reports write sizes with their unit")
    }

    @Test
    fun keyedValues() {
        assertEquals("lat=<coord>&", s("lat=46.8528&"))
        assertEquals("{\"lat\": <coord>, \"lon\": <coord>}", s("{\"lat\": 46.8528, \"lon\": -121.7604}"))
        assertEquals("LatLng [latitude=<coord>, longitude=<coord>, altitude=0.0]", s("LatLng [latitude=46.85, longitude=-121.76, altitude=0.0]"))
        assertEquals("centerLat: <coord> minLon=<coord> lon_max=<coord>", s("centerLat: 46 minLon=-121.9 lon_max=-121.6"))
        // Words that merely contain the letters are not keys.
        assertEquals("translationX=1.5 platform=35 slate=4", s("translationX=1.5 platform=35 slate=4"))
        assertTrue(Scrub.isCoordinateKey("userLatitude"))
        assertTrue(Scrub.isCoordinateKey("LON"))
        assertFalse(Scrub.isCoordinateKey("translation"))
        assertFalse(Scrub.isCoordinateKey("relative"))
    }

    @Test
    fun degreesMinutesAndLoneHighPrecisionValues() {
        assertEquals("at <coord> <coord>", s("at 46°51'N 121°45'W"))
        assertEquals("fix <coord> ok", s("fix -121.760412 ok"))
        assertEquals("took 0.25 s, 12.5 MB", s("took 0.25 s, 12.5 MB"), "a short decimal alone stays")
        assertEquals("version 1.2.34567", s("version 1.2.34567"), "a version is not a coordinate")
    }

    @Test
    fun boundingBoxes() {
        assertEquals("bbox <bbox> done", s("bbox -121.86,46.78,-121.66,46.93 done"))
        assertEquals("[<bbox>]", s("[-121.86, 46.78, -121.66, 46.93]"))
        assertEquals("1,2,3,4", s("1,2,3,4"), "four integers are not a box")
    }

    @Test
    fun tilePaths() {
        assertEquals("GET /dem/<tile>.png 200", s("GET /dem/13/1328/2876.png 200"))
        assertEquals("tile <tile> failed", s("tile 13/1328/2876 failed"))
        assertEquals("osm/<tile>.pbf", s("osm/14/2656/5753.pbf"))
        // x or y outside the zoom's grid: not a tile.
        assertEquals("2/7/1", s("2/7/1"))
    }

    @Test
    fun queryStringsAndStoragePaths() {
        assertEquals("POST /route?<query> 200", s("POST /route?from=46.85,-121.76&to=46.80,-121.73 200"))
        assertEquals("http://10.0.2.2:8765/area?<query>", s("http://10.0.2.2:8765/area?bbox=-121.86,46.78,-121.66,46.93&z=13"))
        assertEquals("open <file> failed", s("open /data/user/0/io.github.jasondorjeshort.dystopo/files/packs/myplace-dem.pmtiles failed"))
        assertEquals("(<file>)", s("(/storage/emulated/0/Android/data/io.github.jasondorjeshort.dystopo/files/areas/areas.json)"))
        assertEquals("at <file>: no space", s("at /sdcard/Download/trip.gpx: no space"))
        assertEquals("cache <file>", s("cache /var/app/cache/x", Scrub.Known(roots = listOf("/var/app/"))))
    }

    @Test
    fun packAreaAndSearchNames() {
        assertEquals("saved <pack> (12 MB)", s("saved view-46.85N-121.76W-z8-14 (12 MB)"))
        assertEquals("opened <pack>.pmtiles", s("opened rainier-outdoors.pmtiles"))
        val known = Scrub.Known(packs = listOf("Hidden Valley"), areas = listOf("Cabin weekend"), searches = listOf("blue lake"))
        assertEquals("pack <pack>, area <area>, search \"<search>\"", s("pack hidden valley, area Cabin Weekend, search \"Blue Lake\"", known))
        assertEquals("ab", s("ab", Scrub.Known(packs = listOf("ab"))), "names under three characters are left (they would wreck the text)")
    }

    @Test
    fun aDiagnosticsReportKeepsItsNumbers() {
        val report = """
            Dystopo diagnostics
            App: 0.1.12-alpha (1234), release
            Android 15 (API 35), Google sdk_gphone64_x86_64
            Max heap: 576.0 MB
            Heap used now: 42.3 MB
            PSS: 210.7 MB
            Last trim: level 80 (complete) at 2026-10-25 14:03:12
        """.trimIndent()
        assertEquals(report, s(report))
    }

    @Test
    fun aCrashTextNamesNoPlace() {
        val crash = """
            java.lang.IllegalStateException: no DEM for 46.8528,-121.7604 at z13 (tile 13/1328/2876)
                at io.github.jasondorjeshort.dystopo.app.TileService.demTile(TileService.kt:212)
            request: GET http://localhost:8686/dem/13/1328/2876.png?t=abcdef
            camera: LatLng [latitude=45.8326, longitude=6.8652, altitude=0.0] zoom=13.5
            pack: /data/user/0/io.github.jasondorjeshort.dystopo/files/packs/view-45.83N-6.87E-z8-14-dem.pmtiles
            area bbox [-121.86,46.78,-121.66,46.93]
        """.trimIndent()
        val r = Scrub.scrub(crash)
        assertNoCoordinate(r.text.replace("altitude=0.0", "").replace("zoom=13.5", ""))
        assertFalse("1328" in r.text, r.text)
        assertFalse("45.83N" in r.text, r.text)
        assertTrue("TileService.kt:212" in r.text, "stack frames stay: ${r.text}")
        assertTrue((r.replaced["coord"] ?: 0) >= 2 && (r.replaced["tile"] ?: 0) >= 1, r.replaced.toString())
        assertEquals(r.text, Scrub.text(r.text), "scrubbing twice changes nothing")
    }
}
