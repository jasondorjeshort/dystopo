package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.mvt.Feature
import io.github.jasondorjeshort.dystopo.mvt.Geometry
import io.github.jasondorjeshort.dystopo.mvt.Layer
import io.github.jasondorjeshort.dystopo.mvt.TilePoint
import io.github.jasondorjeshort.dystopo.mvt.Value
import io.github.jasondorjeshort.dystopo.mvt.VectorTile
import io.github.jasondorjeshort.dystopo.pmtiles.TileIds
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** T174: the geometry and the byte splice of `devtool drainage-pack` ([DrainageShape]). */
class DrainagePackTest {
    /** A D8 staircase along y = x / 2: one straight step, one diagonal step, repeated. */
    private fun staircase(steps: Int): DoubleArray {
        val pts = ArrayList<Double>()
        var x = 0.0
        var y = 0.0
        pts += x; pts += y
        repeat(steps) { k ->
            x += 1
            if (k % 2 == 1) y += 1
            pts += x; pts += y
        }
        return pts.toDoubleArray()
    }

    @Test
    fun smoothingAndSimplifyingFlattenTheStaircaseAndKeepTheEnds() {
        val raw = staircase(40)
        val shaped = DrainageShape.simplify(DrainageShape.smooth(raw, DrainageShape.SMOOTH_PASSES), DrainageShape.SIMPLIFY_CELLS)
        // The ends do not move, so lines still meet at their confluence cells.
        assertEquals(raw[0], shaped[0]); assertEquals(raw[1], shaped[1])
        assertEquals(raw[raw.size - 2], shaped[shaped.size - 2]); assertEquals(raw[raw.size - 1], shaped[shaped.size - 1])
        assertTrue(shaped.size / 2 <= 4, "a straight staircase keeps few vertices: ${shaped.size / 2}")
        // Inside the line the smoothed points lie close to the trend the staircase follows.
        val inner = DrainageShape.smooth(raw, DrainageShape.SMOOTH_PASSES)
        for (k in 3 until inner.size / 2 - 3) assertTrue(abs(inner[2 * k + 1] - (inner[2 * k] - 0.5) / 2) < 0.3, "point $k off the trend")
        // The staircase's length (sqrt 2 per diagonal) shrinks toward the straight length.
        assertTrue(DrainageShape.length(shaped) < DrainageShape.length(raw))
    }

    @Test
    fun clipKeepsRunsInsideTheRectangle() {
        val xs = doubleArrayOf(-10.0, 5.0, 5.0, 20.0, 30.0)
        val ys = doubleArrayOf(5.0, 5.0, 8.0, 8.0, 8.0)
        val runs = DrainageShape.clip(xs, ys, 0.0, 0.0, 10.0, 10.0)
        assertEquals(1, runs.size)
        assertContentEquals(doubleArrayOf(0.0, 5.0, 5.0, 5.0, 5.0, 8.0, 10.0, 8.0), runs[0])
        // Out and back in: two runs.
        val two = DrainageShape.clip(doubleArrayOf(2.0, 20.0, 3.0), doubleArrayOf(2.0, 2.0, 4.0), 0.0, 0.0, 10.0, 10.0)
        assertEquals(2, two.size)
        assertTrue(DrainageShape.clip(doubleArrayOf(20.0, 30.0), doubleArrayOf(0.0, 0.0), 0.0, 0.0, 10.0, 10.0).isEmpty())
    }

    @Test
    fun cutPutsALineIntoEveryTileItCrossesInTileUnits() {
        // Window at global pixel (512, 256) of zoom 13; a horizontal line from cell 200 to cell 400 at row 10 crosses the
        // tile edge at global x 768 (cell 255.5).
        val line = doubleArrayOf(200.0, 10.0, 400.0, 10.0)
        val cut = DrainageShape.cut(line, 512, 256, 1.0, 13, 4)
        assertEquals(setOf(TileIds.fromZxy(13, 2, 1), TileIds.fromZxy(13, 3, 1)), cut.keys)
        val left = cut.getValue(TileIds.fromZxy(13, 2, 1)).single()
        val right = cut.getValue(TileIds.fromZxy(13, 3, 1)).single()
        // Cell 200 is global x 712.5: (712.5 - 512) * 16 = 3208 units; the run stops at the buffer, 4 px past the edge.
        assertEquals(TilePoint(3208, 168), left.first())
        assertEquals(TilePoint(4096 + 64, 168), left.last())
        assertEquals(TilePoint(-64, 168), right.first())
        assertEquals(TilePoint((400.5 + 512 - 768).times(16).toInt(), 168), right.last())
        // One zoom out the same line is half as long and sits in one tile.
        val z12 = DrainageShape.cut(line, 512, 256, 0.5, 12, 4)
        assertEquals(setOf(TileIds.fromZxy(12, 1, 0)), z12.keys)
    }

    @Test
    fun theLayerIsSplicedInAndEveryOtherLayerKeepsItsBytes() {
        fun layer(name: String, vararg pts: Pair<Int, Int>) = Layer(
            name, listOf(Feature(7L, mapOf("class" to Value.StringValue(name)), Geometry.Lines(listOf(pts.map { TilePoint(it.first, it.second) })))),
        )
        val tile = VectorTile(listOf(layer("water", 0 to 0, 10 to 10), layer("drainage", 1 to 1, 2 to 2), layer("waterway", 5 to 5, 9 to 1))).encode()
        val before = DrainageShape.layers(tile)
        assertEquals(listOf("water", "drainage", "waterway"), before.map { it.first })
        val (kept, removed) = DrainageShape.withoutLayer(tile, "drainage")
        assertEquals(1, removed)
        val next = kept + VectorTile(listOf(layer("drainage", 3 to 3, 4 to 4))).encode()
        val after = DrainageShape.layers(next)
        assertEquals(listOf("water", "waterway", "drainage"), after.map { it.first })
        assertContentEquals(before[0].second, after[0].second)
        assertContentEquals(before[2].second, after[1].second)
        // The spliced tile is a valid tile with the new layer.
        val decoded = VectorTile.decode(next)
        assertEquals(TilePoint(3, 3), (decoded.layer("drainage")!!.features.single().geometry as Geometry.Lines).lines.single().first())
        // A tile without the layer comes back unchanged.
        val plain = VectorTile(listOf(layer("water", 0 to 0, 10 to 10))).encode()
        assertContentEquals(plain, DrainageShape.withoutLayer(plain, "drainage").first)
    }
}
