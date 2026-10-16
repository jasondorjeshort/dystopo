package io.github.jasondorjeshort.dystopo.app.cachebudget

import io.github.jasondorjeshort.dystopo.areas.AreaPoint
import io.github.jasondorjeshort.dystopo.geo.WebMercator
import io.github.jasondorjeshort.dystopo.packs.PackBounds
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** T112: what the budget never evicts ([CacheGuard]): the view, grown by half on every side, and the route ahead. */
class CacheGuardTest {
    @Test
    fun keysNameTheirTile() {
        assertEquals(Triple(13, 1371, 2893), CacheGuard.tileOf("10.0.2.2_8765/dem@v1/13/1371/2893.png"))
        assertEquals(Triple(13, 1371, 2893), CacheGuard.tileOf("v2-ft-512-abcd/13/1371/2893.pbf")) // the contour cache
        assertEquals(Triple(13, 1371, 2893), CacheGuard.tileOf("srv/contours-v2-ft-512@v1/13/1371/2893.halo-13-1370-2893.pbf"))
        assertNull(CacheGuard.tileOf("srv/glyphs@a1/Noto Sans Regular/0-255.pbf"))
        assertNull(CacheGuard.tileOf("srv/dem@v1/3/9/0.png"), "x outside zoom 3")
        assertNull(CacheGuard.tileOf("12/1/1.png"), "too short to have a prefix")
    }

    @Test
    fun theViewIsKeptWithAMargin() {
        // Paradise (public) at z14.
        val x = floor(WebMercator.lonToX(-121.736, 14)).toInt()
        val y = floor(WebMercator.latToY(46.786, 14)).toInt()
        val t = CacheGuard.tileBox(14, x, y)
        val g = CacheGuard({ t })
        val spare = g.predicate()
        assertTrue(spare("s/dem@v/14/$x/$y.png"))
        assertTrue(spare("s/dem@v/14/${x + 1}/$y.png"), "half a view of margin reaches the neighbour")
        assertFalse(spare("s/dem@v/14/${x + 3}/$y.png"))
        assertTrue(spare("s/dem@v/9/${x shr 5}/${y shr 5}.png"), "the coarser tiles under the view")
        assertTrue(spare("s/osm@o/16/${x * 4 + 1}/${y * 4 + 2}.pbf"), "and the finer ones")
        assertFalse(spare("s/glyphs@a/Noto Sans Regular/0-255.pbf"))
        assertEquals(4L, g.spared.get())
        assertFalse(CacheGuard({ null }).predicate()("s/dem@v/14/$x/$y.png"), "no view yet: nothing spared")
    }

    @Test
    fun theRouteAheadIsKeptAndRecomputedAsTheWalkerMovesOn() {
        // From Paradise (public) about 7 km due north (a synthetic line).
        val line = listOf(AreaPoint(46.7859, -121.7356), AreaPoint(46.85, -121.7356))
        var progress = 0.0
        val g = CacheGuard({ null }, { CacheGuard.RouteAhead("trip-1", progress) { line } })
        val first = assertNotNull(g.ahead())
        assertSame(first, g.ahead(), "same 100 m: the cover is reused")
        val x = floor(WebMercator.lonToX(-121.7356, 15)).toInt()
        val zy = { lat: Double -> floor(WebMercator.latToY(lat, 15)).toInt() }
        val start = "s/dem@v/15/$x/${zy(46.7859)}.png"
        val sixKm = "s/contours-v2-ft-512@v/15/$x/${zy(46.84)}.pbf"
        assertTrue(g.predicate()(start))
        assertFalse(g.predicate()(sixKm), "6 km on is beyond the 3 km ahead")
        progress = 4000.0
        assertTrue(g.ahead() !== first, "moved on: a new cover")
        assertFalse(g.predicate()(start), "behind the walker now")
        assertTrue(g.predicate()(sixKm))
        assertFalse(g.predicate()("s/dem@v/15/${floor(WebMercator.lonToX(6.86, 15)).toInt()}/${zy(45.83)}.png"), "Mont Blanc")
    }

    @Test
    fun tileBoxesMatchWebMercator() {
        val b = CacheGuard.tileBox(12, 667, 1453)
        assertEquals(WebMercator.xToLon(667.0, 12), b.minLon, 1e-9)
        assertEquals(WebMercator.yToLat(1453.0, 12), b.maxLat, 1e-9)
        assertTrue(CacheGuard.overlaps(Triple(12, 667, 1453), PackBounds(b.centerLon, b.centerLat, b.centerLon + 1e-4, b.centerLat + 1e-4)))
        assertFalse(CacheGuard.overlaps(Triple(12, 668, 1453), PackBounds(b.minLon, b.minLat, b.maxLon - 1e-6, b.maxLat)))
    }
}
