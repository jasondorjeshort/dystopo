package io.github.jasondorjeshort.dystopo.app.cachebudget

import io.github.jasondorjeshort.dystopo.app.online.TileCache
import io.github.jasondorjeshort.dystopo.areas.AreaPoint
import io.github.jasondorjeshort.dystopo.geo.WebMercator
import io.github.jasondorjeshort.dystopo.packs.PackBounds
import java.io.File
import java.nio.file.Files
import kotlin.math.floor
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T112: the budget over real caches in a temporary directory ([BudgetEngine]): derived data goes first, oldest-used
 * first across the two caches that hold it, each kind only down to its floor, never a tile in view or ahead on the
 * route, never a pinned one.
 */
class CacheBudgetTest {
    private val root: File = Files.createTempDirectory("cache-budget").toFile()
    private var now = 1_000_000L

    @AfterTest
    fun cleanup() {
        root.deleteRecursively()
    }

    private val online = TileCache(File(root, "online"), 1L shl 30) { now }
    private val contours = TileCache(File(root, "contours"), 1L shl 30) { now }
    private val sourcesDir = File(root, "sources").also { it.mkdirs() }
    private var view: PackBounds? = null
    private var route: CacheGuard.RouteAhead? = null
    private var total = 10_000L

    private fun engine() = BudgetEngine(
        listOf(
            TileCacheStore(online, fetched = CacheKind.ONLINE, derived = CacheKind.DERIVED) { k -> k.substringAfter('/').startsWith("contours-") },
            TileCacheStore(contours, fetched = null, derived = CacheKind.DERIVED) { true },
            DirStore(CacheKind.SOURCES, sourcesDir, walkEveryMs = 0) { now },
        ),
        { total },
        CacheGuard({ view }, { route }),
        slack = { 0L },
    )

    // Paradise (public), z12.
    private val x0 = floor(WebMercator.lonToX(-121.736, 12)).toInt()
    private val y0 = floor(WebMercator.latToY(46.786, 12)).toInt()
    private fun dem(i: Int) = "srv/dem@v1/12/${x0 + i}/$y0.png"
    private fun onlineContour(i: Int) = "srv/contours-v2-ft-512@v1/12/${x0 + i}/$y0.pbf"
    private fun packContour(i: Int) = "v2-ft-512-pack/12/${x0 + i}/$y0.pbf"

    private fun put(c: TileCache, key: String, n: Int) {
        now += 60_000 // one minute apart, so the touch times order them
        c.put(key, ByteArray(n))
    }

    @Test
    fun derivedGoesFirstOldestUsedAcrossBothCaches() {
        put(online, onlineContour(0), 500) // the oldest derived tile
        for (i in 0 until 6) put(online, dem(i), 1000)
        for (i in 0 until 4) put(contours, packContour(i), 1000)
        put(online, onlineContour(1), 500) // the newest
        val e = engine()
        assertEquals(mapOf(CacheKind.ONLINE to 6000L, CacheKind.DERIVED to 5000L, CacheKind.SOURCES to 0L), e.used())
        val run = e.enforce { now }
        assertTrue(run.fits, run.toString())
        assertEquals(mapOf("derived" to 1500L), run.freedByKind, "only derived data went (1000 over, then whole tiles)")
        assertTrue((0 until 6).all { online.contains(dem(it)) }, "no downloaded tile went while derived data was above its floor")
        assertFalse(online.contains(onlineContour(0)), "the oldest derived tile went first, though it sits in the online cache")
        assertFalse(contours.contains(packContour(0)))
        assertTrue(online.contains(onlineContour(1)) && contours.contains(packContour(3)), "the newest derived tiles stay")
    }

    @Test
    fun sourceDataGoesOnlyOnceDerivedIsAtItsFloor() {
        total = 6000 // floors: online 1500, derived 900, sources 300
        for (i in 0 until 5) put(online, dem(i), 1000)
        put(contours, packContour(0), 900) // derived exactly at its floor
        File(sourcesDir, "cog-a.bin").writeBytes(ByteArray(1000)); File(sourcesDir, "cog-a.bin").setLastModified(now - 10 * 60_000)
        val run = engine().enforce { now }
        assertTrue(contours.contains(packContour(0)), "derived stays at its floor")
        // Over by 900: online (3500 above its floor) is furthest above, so its oldest tile goes, not the source file.
        assertEquals(mapOf("online" to 1000L), run.freedByKind)
        assertFalse(online.contains(dem(0)))
        assertTrue(File(sourcesDir, "cog-a.bin").isFile)
    }

    @Test
    fun tilesInViewAndAheadOnTheRouteStayEvenWhenOldest() {
        total = 4000 // floors: online 1000, derived 600
        // The oldest two DEM tiles are in view; the next one is on the route ahead; three more are elsewhere (Mont Blanc).
        put(online, dem(0), 1000)
        put(online, dem(1), 1000)
        val bx = floor(WebMercator.lonToX(6.86, 12)).toInt()
        val by = floor(WebMercator.latToY(45.83, 12)).toInt()
        val far = (0 until 3).map { "srv/dem@v1/12/${bx + it}/$by.png" }
        for (k in far) put(online, k, 1000)
        put(online, dem(4), 1000)
        val tile = CacheGuard.tileBox(12, x0, y0)
        view = PackBounds(tile.minLon + 0.001, tile.minLat + 0.001, tile.maxLon + 0.01, tile.maxLat - 0.001) // tiles x0 and x0+1 (with the margin)
        val ahead = CacheGuard.tileBox(12, x0 + 4, y0)
        route = CacheGuard.RouteAhead("trip", 0.0) {
            listOf(AreaPoint(ahead.centerLat, ahead.minLon + 0.002), AreaPoint(ahead.centerLat, ahead.maxLon - 0.002))
        }
        val e = engine()
        val run = e.enforce { now }
        assertTrue(run.fits, run.toString())
        assertTrue(online.contains(dem(0)) && online.contains(dem(1)), "the view's tiles stay though they are the oldest")
        assertTrue(online.contains(dem(4)), "the route ahead stays")
        assertEquals(1, far.count { online.contains(it) }, "two tiles elsewhere went instead (2000 over)")
        assertTrue(e.guard.spared.get() > 0)
        assertEquals(3000L, run.sparedBefore, "two tiles in view and one ahead")
        assertEquals(run.sparedBefore, run.sparedAfter, "none of them went")
    }

    @Test
    fun pinnedTilesAreOutsideTheBudgetAndAKindStuckOnProtectedTilesStops() {
        total = 2000
        online.keep = { it == dem(0) } // a pinned offline area
        for (i in 0 until 4) put(online, dem(i), 1000)
        online.recountPinned()
        view = CacheGuard.tileBox(12, x0 + 1, y0).let { PackBounds(it.minLon - 1, it.minLat - 1, it.maxLon + 1, it.maxLat + 1) } // everything in view
        val e = engine()
        assertEquals(3000L, e.used().getValue(CacheKind.ONLINE), "pinned bytes do not count")
        val run = e.enforce { now }
        assertFalse(run.fits)
        assertEquals(listOf("online"), run.stuck)
        assertEquals(4, online.size, "nothing in view or pinned went")
    }

    @Test
    fun sourceFilesGoOldestWrittenFirst() {
        total = 2000 // floors: online 500, derived 300, sources 100
        val names = listOf("old.bin", "mid.bin", "new.bin")
        names.forEachIndexed { i, n -> File(sourcesDir, "copernicus/$n").apply { parentFile.mkdirs(); writeBytes(ByteArray(1000)); setLastModified(now - (3 - i) * 60_000L) } }
        File(sourcesDir, "openfreemap/planet.json").apply { parentFile.mkdirs(); writeBytes(ByteArray(10)) }
        val store = DirStore(CacheKind.SOURCES, sourcesDir, keepNames = setOf("planet.json"), walkEveryMs = 0) { now }
        assertEquals(3000L, store.total(), "planet.json is not counted")
        val run = BudgetEngine(listOf(store), { total }, CacheGuard({ null }), slack = { 0L }).enforce { now }
        assertEquals(mapOf("sources" to 1000L), run.freedByKind)
        assertFalse(File(sourcesDir, "copernicus/old.bin").exists())
        assertTrue(File(sourcesDir, "copernicus/new.bin").exists() && File(sourcesDir, "openfreemap/planet.json").exists())
    }

    @Test
    fun evictOldestSkipsWhatItIsToldToAndCountsItself() {
        for (i in 0 until 4) put(online, dem(i), 100)
        assertEquals(online.oldestTouch(), online.oldestTouch(eligible = { true }))
        val t0 = online.oldestTouch(eligible = { it != dem(0) })!!
        assertTrue(t0 > online.oldestTouch()!!)
        assertEquals(200L, online.evictOldest(150, spare = { it == dem(0) }))
        assertTrue(online.contains(dem(0)) && !online.contains(dem(1)) && !online.contains(dem(2)) && online.contains(dem(3)))
        assertEquals(2L, online.budgetEvictions.get())
        assertEquals(200L, online.bytesWhere { true })
        assertEquals(1_073_741_824L, online.maxBytes, "the cap is untouched")
    }
}
