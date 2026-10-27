package io.github.jasondorjeshort.dystopo.areas

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** T152: the tile set held as rows, and the download's walk over a manifest's rows. */
class TileRowsTest {
    private fun key(x: Int, y: Int) = (x.toLong() shl 32) or y.toLong()

    /** The set as T140 held it (every tile), for comparison. */
    private fun reference(seed: Int, z: Int, ops: Int): Pair<TileSet, HashSet<Long>> {
        val r = Random(seed)
        val n = 1 shl z
        val t = TileSet(z)
        val ref = HashSet<Long>()
        repeat(ops) {
            val y = r.nextInt(n)
            if (r.nextBoolean()) {
                val x = r.nextInt(n)
                t.add(x, y)
                ref += key(x, y)
            } else {
                val x0 = r.nextInt(n)
                val x1 = (x0 + r.nextInt(12) - 2).coerceIn(0, n - 1) // sometimes empty (x1 < x0)
                t.addRow(y, x0, x1)
                for (x in x0..x1) ref += key(x, y)
            }
        }
        return t to ref
    }

    private fun tilesOf(ref: Set<Long>) = ref.map { (it ushr 32).toInt() to (it and 0xffffffffL).toInt() }.sortedWith(compareBy({ it.second }, { it.first }))

    @Test
    fun rowsHoldExactlyTheTilesAddedMergedAndSorted() {
        for (seed in 1..40) {
            val z = 5
            val (t, ref) = reference(seed, z, 120)
            assertEquals(ref.size, t.size, "seed $seed")
            for (y in 0 until (1 shl z)) for (x in 0 until (1 shl z)) assertEquals(key(x, y) in ref, t.contains(x, y), "seed $seed $x/$y")
            val want = tilesOf(ref)
            assertEquals(want, t.tiles(), "seed $seed")
            assertEquals(want, t.tiles().indices.map { t.tiles()[it] }, "indexing walks the rows the same way")
            // Rows are maximal: no two on one row touch, and they round-trip.
            val rows = t.rows()
            assertEquals(t.rowCount, rows.size)
            for ((a, b) in rows.zipWithNext()) assertTrue(a[0] < b[0] || (a[0] == b[0] && a[2] + 1 < b[1]), "seed $seed: $a then $b")
            assertEquals(want, TileSet.fromRows(z, rows).tiles())
        }
    }

    @Test
    fun dilateAndAncestorsMatchTheTileByTileResult() {
        for (seed in 1..20) {
            val z = 6
            val max = (1 shl z) - 1
            val (t, ref) = reference(seed, z, 60)
            for (n in 1..2) {
                val grown = HashSet<Long>()
                for (k in ref) {
                    val x = (k ushr 32).toInt()
                    val y = (k and 0xffffffffL).toInt()
                    for (dy in -n..n) for (dx in -n..n) if (x + dx in 0..max && y + dy in 0..max) grown += key(x + dx, y + dy)
                }
                assertEquals(tilesOf(grown), t.dilate(n).tiles(), "seed $seed dilate $n")
            }
            for (levels in 1..3) {
                val up = ref.map { k -> key((k ushr 32).toInt() shr levels, (k and 0xffffffffL).toInt() shr levels) }.toSet()
                assertEquals(tilesOf(up), t.ancestors(levels).tiles(), "seed $seed ancestors $levels")
            }
        }
    }

    @Test
    fun aLargeBoxIsOneRowPerTileRow() {
        // Rainier's box at z18: tens of thousands of tiles, as many rows as tile rows (T140 held every tile in a hash set).
        val z = 18
        val t = TileCover.box(-121.85, 46.80, -121.70, 46.90, z)
        val margin = t.dilate(1)
        val rowsOfTiles = t.rows().map { it[0] }.distinct().size
        assertEquals(rowsOfTiles, t.rowCount)
        assertEquals(rowsOfTiles + 2, margin.rowCount)
        assertTrue(t.size > 50 * t.rowCount, "${t.size} tiles in ${t.rowCount} rows")
        val first = t.rows().first()
        assertTrue(t.contains(first[1], first[0]) && t.contains(first[2], first[0]))
        assertFalse(t.contains(first[2] + 1, first[0]))
    }

    @Test
    fun theWalkVisitsEveryTileOnceByZoomThenRouteThenRow() {
        val ring = listOf(AreaPoint(46.74, -121.85), AreaPoint(46.74, -121.65), AreaPoint(46.84, -121.65), AreaPoint(46.82, -121.75), AreaPoint(46.84, -121.85))
        val hole = listOf(AreaPoint(46.77, -121.79), AreaPoint(46.77, -121.71), AreaPoint(46.81, -121.71), AreaPoint(46.81, -121.79))
        val area = AreaOfInterest(AreaShape.Polygon(listOf(ring, hole)), 8, 15)
        val items = AreaManifest.tileItems(area, 6..15, 8..14)
        val m = AreaManifest(area.id, "walk", area, AreaManifest.STATUS_READY, items.reversed()) // item order does not matter
        val walk = m.walk().toList()
        assertEquals(walk.sortedWith(TileRow.ORDER), walk, "zoom, then route, then y, then x0")
        assertEquals(m.rowCount, walk.size)
        assertEquals(m.tileCount, walk.sumOf { it.count })
        assertEquals(listOf("dem", "osm"), walk.filter { it.z == 10 }.map { it.route }.distinct())
        assertEquals(6, walk.first().z)
        assertEquals("dem", walk.last().route) // z15 holds DEM only
        // Every tile of every item exactly once.
        for (item in items) for ((z, set) in item.tiles) {
            val seen = HashSet<Long>()
            for (r in walk.filter { it.z == z && it.route == item.route }) for (x in r.x0..r.x1) assertTrue(seen.add(key(x, r.y)), "${item.route} z$z $x/${r.y} twice")
            assertEquals(set.size, seen.size)
            for ((x, y) in set.tiles()) assertTrue(key(x, y) in seen)
        }
        // A hole makes rows with two spans: the walk's unit is a span, so (z, route, y, x0) names it.
        assertTrue(walk.groupBy { Triple(it.z, it.route, it.y) }.values.any { it.size > 1 })
        // The same manifest walks the same way after a round trip through its JSON.
        assertEquals(walk, AreaManifest.parse(io.github.jasondorjeshort.dystopo.pmtiles.MiniJson.encode(m.json())).walk().toList())
    }
}
