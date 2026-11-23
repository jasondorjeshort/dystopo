package io.github.jasondorjeshort.dystopo.app.horizon

import io.github.jasondorjeshort.dystopo.geo.WebMercator
import io.github.jasondorjeshort.dystopo.mvt.Feature
import io.github.jasondorjeshort.dystopo.mvt.Geometry
import io.github.jasondorjeshort.dystopo.mvt.Layer
import io.github.jasondorjeshort.dystopo.mvt.TilePoint
import io.github.jasondorjeshort.dystopo.mvt.Value
import io.github.jasondorjeshort.dystopo.mvt.VectorTile
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T352: the Horizon view's peak read ([HorizonData.readPeaks]) asks the maps deepest first and steps past a map whose tile
 * holds no peak names, so a fine map without them does not hide a coarser map's; the line names the maps that answered.
 */
class HorizonDataPeaksTest {
    private val lat = 46.80
    private val lon = -121.75
    private val extent = 4096

    /** Vector tiles at z9 to z12 around Paradise with [peaks] (name, lat, lon) in `mountain_peak`, or the layer empty. */
    private fun tiles(peaks: List<Triple<String, Double, Double>>): Map<Triple<Int, Int, Int>, ByteArray> {
        val out = HashMap<Triple<Int, Int, Int>, MutableList<Feature>>()
        for (z in 9..12) {
            // Every tile the read may ask at z gets the layer (empty where no peak falls), so each map answers there.
            val n = 1 shl z
            val cx = WebMercator.lonToX(lon, z)
            val cy = WebMercator.latToY(lat, z)
            for (ty in floor(cy - 3).toInt()..floor(cy + 3).toInt()) for (tx in floor(cx - 3).toInt()..floor(cx + 3).toInt()) out.getOrPut(Triple(z, tx.mod(n), ty)) { ArrayList() }
            for ((name, plat, plon) in peaks) {
                val fx = WebMercator.lonToX(plon, z)
                val fy = WebMercator.latToY(plat, z)
                val x = floor(fx).toInt()
                val y = floor(fy).toInt()
                out.getOrPut(Triple(z, x, y)) { ArrayList() } += Feature(
                    null, mapOf("class" to Value.StringValue("peak"), "name" to Value.StringValue(name), "ele" to Value.IntValue(2500L)),
                    Geometry.Points(listOf(TilePoint(((fx - x) * extent).toInt(), ((fy - y) * extent).toInt()))),
                )
            }
        }
        return out.mapValues { (_, fs) -> VectorTile(listOf(Layer("mountain_peak", fs, extent))).encode() }
    }

    private fun map(name: String, maxZoom: Int, area: Double, t: Map<Triple<Int, Int, Int>, ByteArray>) =
        HorizonData.PeakMap(name, maxZoom, area) { z, x, y -> t[Triple(z, x, y)] }

    private val withPeaks = listOf(Triple("Panorama Point", 46.8020, -121.7270), Triple("Mount Rainier", 46.8529, -121.7603))

    @Test
    fun aFineMapWithoutPeaksStepsToTheCoarseOne() {
        val fine = map("fine", 14, 0.01, tiles(emptyList()))
        val wide = map("wide", 11, 1.0, tiles(withPeaks))
        // The candidates come in the shared ranking's order (the primary first): the read reorders them, deepest first.
        val r = HorizonData.readPeaks({ _, _, _ -> listOf(wide, fine) }, lat, lon, 10_000.0, 12)
        assertEquals(setOf("Panorama Point", "Mount Rainier"), r.found.map { it.name }.toSet())
        assertEquals(listOf("wide"), r.maps)
        assertTrue(r.tiles > 0)
    }

    @Test
    fun theFineMapWinsWhereItHasPeaks() {
        // The wide map has one more peak in Panorama Point's tile: the fine map answers that tile, so it is not read.
        val fine = map("fine", 14, 0.01, tiles(listOf(Triple("Panorama Point", 46.8020, -121.7270))))
        val wide = map("wide", 11, 1.0, tiles(withPeaks + Triple("Wide Only", 46.8022, -121.7272)))
        val r = HorizonData.readPeaks({ _, _, _ -> listOf(wide, fine) }, lat, lon, 10_000.0, 12)
        val names = r.found.map { it.name }.toSet()
        assertTrue("Panorama Point" in names)
        assertTrue("Wide Only" !in names, "the deepest map is asked first: $names")
        // Rainier's tile in the fine map has no peak: that tile comes from the wide map.
        assertTrue("Mount Rainier" in names)
        assertEquals(setOf("fine", "wide"), r.maps.toSet())
    }

    @Test
    fun bothEmptyReadsTilesButNoPeaksAndTheLineSaysNone() {
        val fine = map("fine", 14, 0.01, tiles(emptyList()))
        val wide = map("wide", 11, 1.0, tiles(emptyList()))
        val r = HorizonData.readPeaks({ _, _, _ -> listOf(fine, wide) }, lat, lon, 10_000.0, 12)
        assertTrue(r.found.isEmpty())
        assertTrue(r.tiles > 0, "the maps answered: the line is \"none in view\", not \"no offline map\"")
        assertEquals(emptyList(), r.maps)
        assertNull(HorizonData.namesFrom(r.maps))
    }

    @Test
    fun noMapReadsNoTile() {
        val r = HorizonData.readPeaks({ _, _, _ -> emptyList() }, lat, lon, 10_000.0, 12)
        assertEquals(0, r.tiles)
        assertTrue(r.found.isEmpty())
    }

    @Test
    fun theOrderIsDeepestThenSmallerThenName() {
        val t = emptyMap<Triple<Int, Int, Int>, ByteArray>()
        val sorted = listOf(map("b", 12, 1.0, t), map("a", 12, 1.0, t), map("c", 12, 0.5, t), map("d", 14, 9.0, t)).sortedWith(HorizonData.PEAK_ORDER)
        assertEquals(listOf("d", "c", "a", "b"), sorted.map { it.name })
    }

    @Test
    fun theLineNamesTheMaps() {
        assertEquals("Names from the offline map wide.", HorizonData.namesFrom(listOf("wide")))
        assertEquals("Names from the offline maps fine and wide.", HorizonData.namesFrom(listOf("fine", "wide")))
        assertEquals("Names from 3 offline maps.", HorizonData.namesFrom(listOf("a", "b", "c")))
    }
}
