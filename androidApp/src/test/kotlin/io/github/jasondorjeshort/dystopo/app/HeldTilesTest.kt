package io.github.jasondorjeshort.dystopo.app

import io.github.jasondorjeshort.dystopo.geo.WebMercator
import io.github.jasondorjeshort.dystopo.packs.Holdings
import io.github.jasondorjeshort.dystopo.packs.PackBounds
import io.github.jasondorjeshort.dystopo.packs.PackCoverage
import io.github.jasondorjeshort.dystopo.packs.PackPrecedence
import io.github.jasondorjeshort.dystopo.packs.PackRoleZooms
import io.github.jasondorjeshort.dystopo.packs.VectorProfile
import java.io.File
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T356 (ADR 0066 decision 1): what the phone holds, ranked by stored depth, in the tile paths ([HeldTiles]) and in the
 * downloads' skip. A box around Rainier (a public place in `areas.json`); the maps and the area are made up here.
 */
class HeldTilesTest {
    private val box = PackBounds(-122.0, 46.6, -121.5, 47.0)
    private val small = PackBounds(-121.80, 46.80, -121.70, 46.88)
    private val lat = 46.85
    private val lon = -121.76

    private fun tile(z: Int): Triple<Int, Int, Int> =
        Triple(z, floor(WebMercator.lonToX(lon, z)).toInt(), floor(WebMercator.latToY(lat, z)).toInt())

    private fun map(name: String, demMax: Int, b: PackBounds = box, chosen: Boolean = false) = Holdings.Holding(
        name, Holdings.Kind.MAP, Holdings.Stored(0, 14, b), Holdings.Stored(8, demMax, b), VectorProfile.OPENMAPTILES, chosen = chosen,
    )

    /** A downloaded area over [b], z10 to [demMax] for elevation (vector to 14): it holds every cell its box touches. */
    private fun area(demMax: Int = 15, b: PackBounds = small) = Holdings.Holding(
        "area", Holdings.Kind.AREA, Holdings.Stored(10, 14, b), Holdings.Stored(10, demMax, b), VectorProfile.OPENMAPTILES,
        cells = { _, z, x, y -> PackPrecedence.overlaps(PackCoverage("a", 0, 99, b), z, x, y, PackRoleZooms.DEM) },
    )

    private fun cache(depth: Int, held: Set<Triple<Int, Int, Int>>) = Holdings.Holding(
        "cache", Holdings.Kind.CACHE, Holdings.Stored(0, 14), Holdings.Stored(0, depth), VectorProfile.OPENMAPTILES,
        cells = { _, z, x, y -> Triple(z, x, y) in held },
    )

    private val bytes = byteArrayOf(1, 2, 3)

    private fun held(maps: List<Holdings.Holding>, areas: List<Holdings.Holding>, sourceSet: Boolean, cache: Holdings.Holding? = null, read: (String, String) -> ByteArray? = { _, _ -> bytes }) =
        HeldTiles(maps, { areas }, { cache }, read, sourceSet)

    // ---- the downloads' skip ----

    @Test
    fun theOldSkipTookAMapsOverzoomForAStoredTile() {
        // What AreaDownload.fetchTile and AheadPrefetch.present asked before T356: does any map answer the tile? A map storing
        // elevation to z11 answers z15 by overzoom, so a z15 area inside it skipped its elevation.
        val (z, x, y) = tile(15)
        assertEquals(0, PackPrecedence.choose(listOf(PackCoverage("coarse", 8, 11, box)), z, x, y, PackRoleZooms.DEM))
    }

    @Test
    fun aZ15AreaInsideAZ11MapFetchesItsElevation() {
        val h = held(listOf(map("coarse", 11)), emptyList(), sourceSet = true)
        val (z, x, y) = tile(15)
        assertFalse(h.mapStores(Holdings.Role.DEM, z, x, y), "a z11 map does not store z15: the download fetches it")
        val (z11, x11, y11) = tile(11)
        assertTrue(h.mapStores(Holdings.Role.DEM, z11, x11, y11), "it stores z11: skipped")
    }

    @Test
    fun aZ15AreaInsideAZ15MapDoesNot() {
        val h = held(listOf(map("fine", 15)), emptyList(), sourceSet = true)
        val (z, x, y) = tile(15)
        assertTrue(h.mapStores(Holdings.Role.DEM, z, x, y))
    }

    @Test
    fun aMapsEdgeTileIsFetched() {
        // A map whose box covers part of the tile only holds part of it: the download fetches the tile.
        val edge = PackBounds(lon - 0.0001, 46.6, -121.5, 47.0)
        val (z, x, y) = tile(12)
        assertFalse(held(listOf(map("edge", 14, edge)), emptyList(), sourceSet = true).mapStores(Holdings.Role.DEM, z, x, y))
    }

    // ---- the tile paths ----

    @Test
    fun aHeldCellAnswersWithNoSourceSet() {
        val reads = ArrayList<String>()
        val h = held(listOf(map("coarse", 11)), listOf(area()), sourceSet = false) { route, path -> reads += "$route/$path"; bytes }
        val (z, x, y) = tile(15)
        assertEquals("area", h.deeper(Holdings.Role.DEM, z, x, y)?.holding?.name, "the area stores z15, deeper than the map's z11")
        assertContentEquals(bytes, h.tile(Holdings.Role.DEM, z, x, y))
        assertEquals(listOf("dem/$z/$x/$y.png"), reads)
    }

    @Test
    fun anAreaOnlyPhoneDrawsItsCellsWithNoSourceSet() {
        val h = held(emptyList(), listOf(area()), sourceSet = false)
        val (z, x, y) = tile(14)
        assertEquals("area", h.deeper(Holdings.Role.VECTOR, z, x, y)?.holding?.name)
        assertEquals("area", h.deeper(Holdings.Role.DEM, z, x, y)?.holding?.name)
        assertNotNull(h.tile(Holdings.Role.VECTOR, z, x, y))
    }

    @Test
    fun outsideTheMapsWithASourceSetTheOnlinePathReadsTheCacheAsBefore() {
        val h = held(emptyList(), listOf(area()), sourceSet = true)
        val (z, x, y) = tile(15)
        assertNull(h.deeper(Holdings.Role.DEM, z, x, y))
    }

    @Test
    fun aMapAsDeepAsTheAreaKeepsTheTile() {
        val h = held(listOf(map("fine", 15)), listOf(area()), sourceSet = false)
        val (z, x, y) = tile(15)
        assertNull(h.deeper(Holdings.Role.DEM, z, x, y), "equal depth: the map before the area")
        assertNull(h.tile(Holdings.Role.DEM, z, x, y))
    }

    @Test
    fun theMapAnswersAboveTheAreasZooms() {
        val h = held(listOf(map("coarse", 11)), listOf(area()), sourceSet = false)
        val (z, x, y) = tile(9)
        assertNull(h.deeper(Holdings.Role.DEM, z, x, y), "the area holds z10 and deeper only")
    }

    @Test
    fun aCachedTileRanksByItsSourcesStoredDepth() {
        val (z, x, y) = tile(12)
        val c = cache(12, setOf(Triple(z, x, y)))
        assertEquals("cache", held(listOf(map("coarse", 11)), emptyList(), sourceSet = true, cache = c).deeper(Holdings.Role.DEM, z, x, y)?.holding?.name)
        assertNull(held(listOf(map("fine", 14)), emptyList(), sourceSet = true, cache = c).deeper(Holdings.Role.DEM, z, x, y))
    }

    @Test
    fun aContourOrShadeTileOverAnAreaIsMadeFromIt() {
        val h = held(listOf(map("coarse", 11)), listOf(area()), sourceSet = true)
        val (z, x, y) = tile(14)
        assertNotNull(h.deeperOver(z, x, y), "contour tile z14 reads DEM z15, which the area holds")
        val (z8, x8, y8) = tile(8)
        assertNull(h.deeperOver(z8, x8, y8))
    }

    @Test
    fun aMissingHeldCellFallsBackToTheMap() {
        val h = held(listOf(map("coarse", 11)), listOf(area()), sourceSet = false) { _, _ -> null }
        val (z, x, y) = tile(15)
        assertNull(h.tile(Holdings.Role.DEM, z, x, y))
    }

    // ---- the callers ----

    private val main = File("..", "androidApp/src/main/kotlin/io/github/jasondorjeshort/dystopo/app")

    @Test
    fun bothDownloadsSkipOnlyWhatAMapStores() {
        val download = File(main, "areas/AreaDownload.kt").readText()
        val fetch = download.substring(download.indexOf("private fun fetchTile("), download.indexOf("private fun valid("))
        assertTrue("mapStores(" in fetch && "demFor(" !in fetch, "AreaDownload.fetchTile skips by what a map stores")
        val ahead = File(main, "areas/AheadPrefetch.kt").readText()
        assertFalse("demFor(" in ahead, "AheadPrefetch skips by what a map stores")
        assertTrue("mapStores(" in ahead)
    }
}
