package io.github.jasondorjeshort.dystopo.mvt

import kotlin.math.hypot
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T254: a z12 tile cut to its four z13 children and a z14 grandchild ([MvtOverzoom]): geometry within a pixel of the
 * ancestor's, every layer kept, a polygon ring crossing the child's edge clipped to the edge plus the buffer.
 */
class MvtOverzoomTest {
    private fun p(x: Int, y: Int) = TilePoint(x, y)
    private fun pts(vararg xy: Int) = List(xy.size / 2) { p(xy[2 * it], xy[2 * it + 1]) }

    private val e = 4096
    private val buffer = MvtOverzoom.DEFAULT_BUFFER_4096

    /** One pixel of a 512-px tile in extent units. */
    private val pixel = e / 512.0

    private val diagonal = pts(100, 200, 900, 1000, 2047, 2049, 3100, 2900, 4000, 3900)
    private val topLeftOnly = pts(100, 100, 600, 300, 1000, 900)
    private val square = Polygon(pts(1500, 1500, 2600, 1500, 2600, 2600, 1500, 2600), listOf(pts(1900, 1900, 1900, 2200, 2200, 2200, 2200, 1900)))

    /** The ancestor as a z12 tile from an encoder would arrive: encoded and decoded again. */
    private val ancestor: VectorTile = VectorTile.decode(
        VectorTile(listOf(
            Layer("transportation", listOf(
                Feature(1, mapOf("class" to Value.StringValue("motorway")), Geometry.Lines(listOf(diagonal))),
                Feature(2, mapOf("class" to Value.StringValue("track")), Geometry.Lines(listOf(topLeftOnly))),
            )),
            Layer("water", listOf(Feature(3, mapOf("class" to Value.StringValue("lake")), Geometry.Polygons(listOf(square))))),
            Layer("poi", listOf(Feature(4, emptyMap(), Geometry.Points(pts(500, 500, 3000, 1000, 2047, 2047))))),
            Layer("park", listOf(Feature(5, emptyMap(), Geometry.Polygons(listOf(Polygon(pts(10, 10, 300, 10, 300, 300, 10, 300))))))),
        )).encode(),
    )

    private fun cut(dz: Int, dx: Int, dy: Int): VectorTile =
        VectorTile.decode(MvtOverzoom.cut(ancestor.encode(), dz, dx, dy)) // through bytes, as the phone serves it

    /** Child point [v] back in ancestor units. */
    private fun back(v: TilePoint, dz: Int, dx: Int, dy: Int) = doubleArrayOf((v.x + dx * e).toDouble() / (1 shl dz), (v.y + dy * e).toDouble() / (1 shl dz))

    private fun distToPolyline(x: Double, y: Double, line: List<DoubleArray>): Double {
        var best = Double.MAX_VALUE
        for (i in 0 until line.size - 1) {
            val (ax, ay) = line[i].let { it[0] to it[1] }
            val (bx, by) = line[i + 1].let { it[0] to it[1] }
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (((x - ax) * dx + (y - ay) * dy) / len2).coerceIn(0.0, 1.0)
            best = minOf(best, hypot(x - (ax + t * dx), y - (ay + t * dy)))
        }
        return best
    }

    /** Every vertex of the child's lines lies on the ancestor's line within a pixel; every ancestor point inside the child is covered. */
    private fun assertLineMatches(child: VectorTile, dz: Int, dx: Int, dy: Int, id: Long, original: List<TilePoint>) {
        val s = 1 shl dz
        val parts = child.layer("transportation")!!.features.filter { it.id == id }.flatMap { (it.geometry as Geometry.Lines).lines }
        val orig = original.map { doubleArrayOf(it.x.toDouble(), it.y.toDouble()) }
        for (part in parts) for (v in part) {
            assertTrue(v.x in -buffer..e + buffer && v.y in -buffer..e + buffer, "vertex $v outside the buffered box")
            val a = back(v, dz, dx, dy)
            val d = distToPolyline(a[0], a[1], orig) * s
            assertTrue(d <= pixel, "child $dz/$dx/$dy vertex $v is $d units off the ancestor's line")
        }
        // Coverage: points along the ancestor's line that fall inside the child (with a pixel's margin) are on a part.
        val childParts = parts.map { line -> line.map { doubleArrayOf(it.x.toDouble(), it.y.toDouble()) } }
        for (i in 0 until orig.size - 1) {
            for (k in 0..64) {
                val t = k / 64.0
                val ax = orig[i][0] + t * (orig[i + 1][0] - orig[i][0])
                val ay = orig[i][1] + t * (orig[i + 1][1] - orig[i][1])
                val cx = ax * s - dx * e
                val cy = ay * s - dy * e
                if (cx < pixel || cy < pixel || cx > e - pixel || cy > e - pixel) continue
                val d = childParts.minOfOrNull { distToPolyline(cx, cy, it) } ?: Double.MAX_VALUE
                assertTrue(d <= pixel, "child $dz/$dx/$dy misses the ancestor's line at ($cx, $cy) by $d")
            }
        }
    }

    private fun bounds(ring: List<TilePoint>) = listOf(ring.minOf { it.x }, ring.minOf { it.y }, ring.maxOf { it.x }, ring.maxOf { it.y })

    /** The square scaled into the child and clipped to the buffered box, as min/max x and y. */
    private fun expectedBox(x0: Int, y0: Int, x1: Int, y1: Int, dz: Int, dx: Int, dy: Int): List<Int>? {
        val s = 1 shl dz
        val lo = -buffer
        val hi = e + buffer
        val a = max(x0 * s - dx * e, lo)
        val b = max(y0 * s - dy * e, lo)
        val c = minOf(x1 * s - dx * e, hi)
        val d = minOf(y1 * s - dy * e, hi)
        return if (a < c && b < d) listOf(a, b, c, d) else null
    }

    private fun assertSquareClipped(child: VectorTile, dz: Int, dx: Int, dy: Int) {
        val water = child.layer("water")!!
        val ext = expectedBox(1500, 1500, 2600, 2600, dz, dx, dy)
        if (ext == null) {
            assertTrue(water.features.isEmpty(), "child $dz/$dx/$dy should have no water")
            return
        }
        val polys = (water.features.single().geometry as Geometry.Polygons).polygons
        val poly = polys.single()
        assertEquals(ext, bounds(poly.exterior), "child $dz/$dx/$dy exterior")
        // The clipped square is a rectangle: four corners, area matching its bounds, the spec's winding.
        assertEquals(4, poly.exterior.size, "child $dz/$dx/$dy exterior ${poly.exterior}")
        assertEquals(2L * (ext[2] - ext[0]) * (ext[3] - ext[1]), signedDoubleArea(poly.exterior))
        val hole = expectedBox(1900, 1900, 2200, 2200, dz, dx, dy)
        if (hole == null) assertTrue(poly.interiors.isEmpty()) else {
            assertEquals(hole, bounds(poly.interiors.single()), "child $dz/$dx/$dy hole")
            assertTrue(signedDoubleArea(poly.interiors.single()) < 0, "a hole keeps its winding")
        }
    }

    @Test
    fun z12CutToItsFourZ13Children() {
        for (dy in 0..1) for (dx in 0..1) {
            val child = cut(1, dx, dy)
            assertEquals(ancestor.layers.map { it.name }, child.layers.map { it.name }, "every layer kept in $dx,$dy")
            assertTrue(child.layers.all { it.extent == e && it.version == 2 })
            assertLineMatches(child, 1, dx, dy, 1, diagonal)
            assertSquareClipped(child, 1, dx, dy)
            val motorway = child.layer("transportation")!!.features.first { it.id == 1L }
            assertEquals(Value.StringValue("motorway"), motorway.properties["class"], "properties kept")
        }
        // The diagonal passes 1 unit from the children's corner, so the top-right and bottom-left children hold a sliver in their buffer.
        assertEquals(1, cut(1, 0, 0).layer("transportation")!!.features.count { it.id == 2L }, "the track is in the top-left child")
        assertEquals(0, cut(1, 1, 1).layer("transportation")!!.features.count { it.id == 2L }, "and not in the bottom-right one")
        assertTrue(cut(1, 1, 1).layer("park")!!.features.isEmpty(), "a layer with nothing in the child is kept, empty")
    }

    @Test
    fun z12CutToAZ14Grandchild() {
        for ((dx, dy) in listOf(1 to 1, 2 to 2, 0 to 3)) {
            val child = cut(2, dx, dy)
            assertEquals(ancestor.layers.map { it.name }, child.layers.map { it.name })
            assertLineMatches(child, 2, dx, dy, 1, diagonal)
            assertSquareClipped(child, 2, dx, dy)
        }
        // Grandchild 1,1 covers ancestor 1024..2048: the square from 1500 crosses its right and bottom edges.
        val ring = (cut(2, 1, 1).layer("water")!!.features.single().geometry as Geometry.Polygons).polygons.single().exterior
        assertEquals(listOf(1904, 1904, e + buffer, e + buffer), bounds(ring))
    }

    @Test
    fun pointsInTheBufferAreKeptOthersDropped() {
        // (2047, 2047) is 2 units short of the z13 children's corner: inside 0,0 and within 1,1's buffer at -2,-2.
        assertEquals(listOf(p(1000, 1000), p(4094, 4094)), (cut(1, 0, 0).layer("poi")!!.features.single().geometry as Geometry.Points).points)
        assertEquals(listOf(p(-2, -2)), (cut(1, 1, 1).layer("poi")!!.features.single().geometry as Geometry.Points).points)
        assertEquals(listOf(p(6000 - 4096, 2000), p(4094 - 4096, 4094)), (cut(1, 1, 0).layer("poi")!!.features.single().geometry as Geometry.Points).points)
    }

    @Test
    fun aLineLeavingAndReenteringTheBoxSplits() {
        val zigzag = pts(100, 100, 3000, 100, 3000, 300, 100, 300) // in and out of the left child across x = 2048
        val tile = VectorTile(listOf(Layer("t", listOf(Feature(null, emptyMap(), Geometry.Lines(listOf(zigzag)))))))
        val lines = (MvtOverzoom.cut(tile, 1, 1, 0).layers.single().features.single().geometry as Geometry.Lines).lines
        assertEquals(listOf(listOf(p(-64, 200), p(6000 - 4096, 200), p(6000 - 4096, 600), p(-64, 600))), lines, "the turn inside the right child stays joined")
        val left = (MvtOverzoom.cut(tile, 1, 0, 0).layers.single().features.single().geometry as Geometry.Lines).lines
        assertEquals(listOf(listOf(p(200, 200), p(e + buffer, 200)), listOf(p(e + buffer, 600), p(200, 600))), left, "the turn at x 6000 lies past the buffer")
    }
}
