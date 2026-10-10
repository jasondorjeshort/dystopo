package io.github.jasondorjeshort.dystopo.testing

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyntheticTerrainTest {
    @Test
    fun presetsHaveNominalSizeAndNoNaN() {
        for (preset in SyntheticTerrain.presets) {
            val grid = preset.grid(width = 128, height = 128, cellSizeMeters = 40.0)
            assertEquals(128, grid.width)
            assertTrue(grid.values.none { it.isNaN() }, preset.name)
            assertTrue(grid.max() - grid.min() > 100f, "${preset.name} has relief: ${grid.min()}..${grid.max()}")
        }
        assertEquals(512, SyntheticTerrain.MIXED.size)
        assertEquals(10.0, SyntheticTerrain.MIXED.cellSizeMeters)
    }

    @Test
    fun sameSeedSameTerrainOtherSeedDifferentNoise() {
        val a = SyntheticTerrain.MIXED.grid(64, 64, 80.0)
        val b = SyntheticTerrain.MIXED.grid(64, 64, 80.0)
        val c = SyntheticTerrain.MIXED.withSeed(99).grid(64, 64, 80.0)
        assertContentEquals(a.values, b.values)
        assertFalse(a.values.contentEquals(c.values))
    }

    @Test
    fun coneSummitIsHighestPoint() {
        val cone = SyntheticTerrain.CONE
        val summit = cone.elevationAt(0.0, 0.0)
        for (r in listOf(600.0, 1000.0, 1500.0)) assertTrue(cone.elevationAt(r, 0.0) < summit - r * 0.25, "r=$r")
    }

    @Test
    fun ridgeHasASaddleBetweenTwoSummits() {
        val t = SyntheticTerrain.MIXED
        // Ridge from (350, -1900) to (1900, -450): summits at the ends, saddle at the midpoint.
        val end = t.elevationAt(350.0, -1900.0)
        val saddle = t.elevationAt(1125.0, -1175.0)
        val otherEnd = t.elevationAt(1900.0, -450.0)
        assertTrue(saddle < end - 150 && saddle < otherEnd - 150, "saddle $saddle, summits $end, $otherEnd")
        // Across the ridge the saddle is a high point.
        assertTrue(t.elevationAt(1125.0 + 600, -1175.0 - 600) < saddle - 100)
    }

    @Test
    fun lakeIsExactlyFlatWithAShoreline() {
        val t = SyntheticTerrain.MIXED
        val level = t.lakeLevels.single()
        val grid = t.grid()
        val flat = grid.values.count { it == level.toFloat() }
        // A good fraction of the bowl (radius 950 m on 10 m cells, ~28k cells) is water...
        assertTrue(flat in 5_000..28_000, "water cells: $flat")
        // ...and the ground right outside the bowl is above the water.
        assertTrue(t.elevationAt(-1200.0 - 1000.0, 1250.0) > level)
        assertEquals(level, t.elevationAt(-1200.0, 1250.0))
    }

    @Test
    fun cliffBandIsSteep() {
        val t = SyntheticTerrain.LAKE_CLIFF
        // Cliff line through (450, 0), nearly north-south; probe across it.
        val below = t.elevationAt(450.0 - 20, 0.0)
        val above = t.elevationAt(450.0 + 20, 0.0)
        val slopeDeg = atan((above - below) / 40.0) * 180 / PI
        assertTrue(above - below > 150, "step ${above - below}")
        assertTrue(slopeDeg > 70, "slope $slopeDeg")
    }

    @Test
    fun rollingGroundIsGentle() {
        val t = SyntheticTerrain.CONE
        // Far from the cone: only rolling ground, a few meters per 10 m at most.
        var maxStep = 0.0
        for (i in 0 until 200) {
            val x = 2600.0 + i * 10
            maxStep = maxOf(maxStep, abs(t.elevationAt(x + 10, 2600.0) - t.elevationAt(x, 2600.0)))
        }
        assertTrue(maxStep < 2.0, "max step $maxStep")
    }

    @Test
    fun presetLookup() {
        assertEquals(SyntheticTerrain.CONE, SyntheticTerrain.preset("cone"))
        assertFailsWith<IllegalArgumentException> { SyntheticTerrain.preset("nope") }
    }
}
