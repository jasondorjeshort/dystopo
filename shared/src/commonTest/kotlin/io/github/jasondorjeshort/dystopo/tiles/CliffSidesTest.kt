package io.github.jasondorjeshort.dystopo.tiles

import io.github.jasondorjeshort.dystopo.mvt.Feature
import io.github.jasondorjeshort.dystopo.mvt.Geometry
import io.github.jasondorjeshort.dystopo.mvt.Layer
import io.github.jasondorjeshort.dystopo.mvt.TilePoint
import io.github.jasondorjeshort.dystopo.mvt.Value
import io.github.jasondorjeshort.dystopo.mvt.VectorTile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CliffSidesTest {
    private fun cliff(vararg pts: Pair<Int, Int>) =
        Feature(7L, mapOf("class" to Value.StringValue("cliff")), Geometry.Lines(listOf(pts.map { TilePoint(it.first, it.second) })))

    private fun tile(vararg f: Feature) = VectorTile(listOf(Layer(CliffSides.LAYER, f.toList()), Layer("water", emptyList())))

    // Ground falling toward +y (south on screen): an eastward line has it on its right.
    private val southFacing = { _: Double, y: Double -> (3000 - 2 * y).toFloat() }

    @Test
    fun alreadyDownhillRightStaysAsIs() {
        val t = tile(cliff(100 to 500, 900 to 500))
        val r = CliffSides.orient(t, southFacing, offset = 40.0, step = 20.0)
        assertFalse(r.changed)
        assertSame(t, r.tile)
        assertEquals(1, r.lines)
    }

    @Test
    fun aLineDrawnTheOtherWayIsReversed() {
        val t = tile(cliff(900 to 500, 100 to 500))
        val r = CliffSides.orient(t, southFacing, offset = 40.0, step = 20.0)
        assertTrue(r.changed)
        assertEquals(1, r.segmentsLeft)
        val line = (r.tile.layer(CliffSides.LAYER)!!.features.single().geometry as Geometry.Lines).lines.single()
        assertEquals(listOf(TilePoint(100, 500), TilePoint(900, 500)), line)
        // Survives an encode/decode round trip with the id and properties.
        val back = VectorTile.decode(r.tile.encode()).layer(CliffSides.LAYER)!!.features.single()
        assertEquals(7L, back.id)
        assertEquals(Value.StringValue("cliff"), back.properties["class"])
    }

    @Test
    fun aBendIsCutWhereTheLowSideSwitches() {
        // East leg with the low side south (right), then a north leg whose low side is west (left).
        val ground = { x: Double, y: Double -> (if (y < 400) 3000 + 2 * (x - 900) else 3000 - 2 * (y - 500)).toFloat() }
        val o = CliffSides.orientLine(listOf(TilePoint(100, 500), TilePoint(900, 500), TilePoint(900, 100)), ground, 40.0, 20.0)
        assertEquals(1, o.segmentsLeft)
        assertEquals(1, o.reversed)
        assertEquals(listOf(listOf(TilePoint(100, 500), TilePoint(900, 500)), listOf(TilePoint(900, 100), TilePoint(900, 500))), o.pieces)
    }

    @Test
    fun undecidedSegmentsFollowTheLineAndNoDataKeepsIt() {
        // Second leg is flat: it takes the whole line's answer (left low), so the whole line is reversed.
        val ground = { x: Double, y: Double -> if (x > 500) 1000f else (1000 + 2 * y).toFloat() }
        val o = CliffSides.orientLine(listOf(TilePoint(100, 500), TilePoint(500, 500), TilePoint(900, 500)), ground, 40.0, 20.0)
        assertEquals(1, o.segmentsUndecided)
        assertEquals(listOf(listOf(TilePoint(900, 500), TilePoint(500, 500), TilePoint(100, 500))), o.pieces)
        val none = CliffSides.orientLine(listOf(TilePoint(100, 500), TilePoint(900, 500)), { _, _ -> Float.NaN }, 40.0, 20.0)
        assertEquals(0, none.reversed)
    }

    @Test
    fun otherFeaturesAndLayersAreUntouched() {
        val ridge = Feature(1L, mapOf("class" to Value.StringValue("ridge")), Geometry.Lines(listOf(listOf(TilePoint(900, 500), TilePoint(100, 500)))))
        val t = tile(ridge, cliff(900 to 500, 100 to 500))
        val r = CliffSides.orient(t, southFacing, 40.0, 20.0)
        assertEquals(ridge, r.tile.layer(CliffSides.LAYER)!!.features[0])
        assertEquals(t.layer("water"), r.tile.layer("water"))
        assertTrue(CliffSides.mayHaveCliffs(t.encode()))
        assertFalse(CliffSides.mayHaveCliffs(VectorTile(listOf(Layer("water", emptyList()))).encode()))
    }
}
