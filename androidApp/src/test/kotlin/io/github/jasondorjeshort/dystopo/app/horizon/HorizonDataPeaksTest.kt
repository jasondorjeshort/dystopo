package io.github.jasondorjeshort.dystopo.app.horizon

import io.github.jasondorjeshort.dystopo.areas.AreaDetail
import io.github.jasondorjeshort.dystopo.areas.AreaOfInterest
import io.github.jasondorjeshort.dystopo.areas.AreaShape
import io.github.jasondorjeshort.dystopo.areas.TileSet
import io.github.jasondorjeshort.dystopo.geo.WebMercator
import io.github.jasondorjeshort.dystopo.mvt.Feature
import io.github.jasondorjeshort.dystopo.mvt.Geometry
import io.github.jasondorjeshort.dystopo.mvt.Layer
import io.github.jasondorjeshort.dystopo.mvt.TilePoint
import io.github.jasondorjeshort.dystopo.mvt.Value
import io.github.jasondorjeshort.dystopo.mvt.VectorTile
import io.github.jasondorjeshort.dystopo.packs.Holdings
import io.github.jasondorjeshort.dystopo.packs.PackBounds
import io.github.jasondorjeshort.dystopo.packs.VectorProfile
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T352: the Horizon view's peak read ([HorizonData.readPeaks]) asks the maps deepest first and steps past a map whose tile
 * holds no peak names, so a fine map without them does not hide a coarser map's; the line names the maps that answered.
 * T378: an area kept as tiles is one more source, and every source is asked in ADR 0066's lookup order ([HorizonData.inLookupOrder]).
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

    private val withPeaks = listOf(Triple("Panorama Point", 46.8020, -121.7270), Triple("Mount Rainier", 46.8529, -121.7603))

    /** T378: an offline map as the lookup sees it: a box around Paradise of [halfDeg] each way, its vector zooms 8 to [maxZoom]. */
    private fun mapSource(name: String, maxZoom: Int, t: Map<Triple<Int, Int, Int>, ByteArray>, halfDeg: Double = 1.0, chosen: Boolean = false, profile: VectorProfile = VectorProfile.OUTDOORS) =
        HorizonData.PeakSource(
            Holdings.Holding(name, Holdings.Kind.MAP, Holdings.Stored(8, maxZoom, PackBounds(lon - halfDeg, lat - halfDeg, lon + halfDeg, lat + halfDeg)), null, profile, chosen),
        ) { z, x, y -> t[Triple(z, x, y)] }

    /** T378: an area kept as tiles at [detail] whose rows (its manifest's) are [rows]; it holds the tiles of [t] in its rows. */
    private fun areaSource(name: String, detail: AreaDetail, rows: Map<Int, TileSet>, t: Map<Triple<Int, Int, Int>, ByteArray>, asked: MutableList<Int>? = null): HorizonData.PeakSource {
        val shape = AreaShape.Box(lon - 0.02, lat - 0.02, lon + 0.02, lat + 0.02)
        val held = AreaOfInterest(shape, AreaDetail.MIN_ZOOM, AreaDetail.MIN_ZOOM + 1).at(detail)
        return HorizonData.PeakSource(HorizonData.areaHolding(name, held, rows)) { z, x, y ->
            asked?.add(z)
            if (rows[z]?.contains(x, y) == true) t[Triple(z, x, y)] else null
        }
    }

    /** The rows of the tiles holding [points] (lat, lon) at zooms 8 to [maxZoom]: a small area's manifest rows. */
    private fun rowsAround(points: List<Pair<Double, Double>>, maxZoom: Int): Map<Int, TileSet> = (8..maxZoom).associateWith { z ->
        TileSet(z).apply { for ((plat, plon) in points) add(floor(WebMercator.lonToX(plon, z)).toInt(), floor(WebMercator.latToY(plat, z)).toInt()) }
    }

    private fun read(sources: List<HorizonData.PeakSource>, rangeM: Double = 10_000.0, zoom: Int? = 12) = HorizonData.readPeaks(HorizonData.inLookupOrder(sources), lat, lon, rangeM, zoom)

    private fun tileOf(plat: Double, plon: Double, z: Int) = Triple(z, floor(WebMercator.lonToX(plon, z)).toInt(), floor(WebMercator.latToY(plat, z)).toInt())

    @Test
    fun aFineMapWithoutPeaksStepsToTheCoarseOne() {
        val fine = mapSource("fine", 14, tiles(emptyList()), halfDeg = 0.5)
        val wide = mapSource("wide", 12, tiles(withPeaks), halfDeg = 2.0)
        // The fine map is asked first (deeper): its tiles hold no peak names, so the wide map's are read.
        val r = read(listOf(wide, fine))
        assertEquals(setOf("Panorama Point", "Mount Rainier"), r.found.map { it.name }.toSet())
        assertEquals(listOf("wide"), r.maps)
        assertTrue(r.tiles > 0)
    }

    @Test
    fun theFineMapWinsWhereItHasPeaks() {
        // The wide map has one more peak in Panorama Point's tile: the fine map answers that tile, so it is not read.
        val fine = mapSource("fine", 14, tiles(listOf(Triple("Panorama Point", 46.8020, -121.7270))), halfDeg = 0.5)
        val wide = mapSource("wide", 12, tiles(withPeaks + Triple("Wide Only", 46.8022, -121.7272)), halfDeg = 2.0)
        val r = read(listOf(wide, fine))
        val names = r.found.map { it.name }.toSet()
        assertTrue("Panorama Point" in names)
        assertTrue("Wide Only" !in names, "the deepest map is asked first: $names")
        // Rainier's tile in the fine map has no peak: that tile comes from the wide map.
        assertTrue("Mount Rainier" in names)
        assertEquals(setOf("fine", "wide"), r.maps.toSet())
    }

    @Test
    fun bothEmptyReadsTilesButNoPeaksAndTheLineSaysNone() {
        val r = read(listOf(mapSource("fine", 14, tiles(emptyList()), 0.5), mapSource("wide", 12, tiles(emptyList()), 2.0)))
        assertTrue(r.found.isEmpty())
        assertTrue(r.tiles > 0, "the maps answered: the line is none in view, not no offline map")
        assertEquals(emptyList(), r.maps)
        assertNull(HorizonData.namesFrom(r.maps))
    }

    @Test
    fun noMapReadsNoTile() {
        val r = HorizonData.readPeaks({ _, _, _ -> emptyList() }, lat, lon, 10_000.0, 12)
        assertEquals(0, r.tiles)
        assertTrue(r.found.isEmpty())
        assertEquals(0, read(emptyList()).tiles)
    }

    // ---- T378: an area kept as tiles, and the lookup's order ----

    private val paradise = listOf(46.8020 to -121.7270)

    @Test
    fun anAreaAloneNamesThePeaksInItsTiles() {
        // The pack off: only the area at Paradise answers, in its own rows; Rainier's tile is outside them.
        val r = read(listOf(areaSource("Paradise tiles", AreaDetail.MEDIUM, rowsAround(paradise, 14), tiles(withPeaks))))
        assertEquals(listOf("Panorama Point"), r.found.map { it.name })
        assertEquals(listOf("Paradise tiles"), r.maps)
        assertEquals(listOf("area"), r.kinds)
        assertTrue(r.areaTiles > 0)
        assertEquals("Names from the offline map Paradise tiles.", HorizonData.namesFrom(r.maps))
    }

    @Test
    fun aMapAndAnAreaHoldingOnePeakGiveItOnceFromTheDeeper() {
        val t = tiles(withPeaks)
        val rows = rowsAround(paradise, 14)
        // Same profile, the map to z12 and the area at Medium (z14): the area is deeper and answers its tiles.
        val coarse = mapSource("coarse", 12, t, profile = VectorProfile.OPENMAPTILES)
        val r = read(listOf(coarse, areaSource("Paradise tiles", AreaDetail.MEDIUM, rows, t)))
        assertEquals(1, r.found.count { it.name == "Panorama Point" }, "${r.found}")
        assertEquals(setOf("Paradise tiles", "coarse"), r.maps.toSet(), "the area gives Paradise's tile, the map Rainier's")
        // A map as deep as the area (z14): the map ranks first (a map before an area at equal depth); still once.
        val deep = mapSource("deep", 14, t, profile = VectorProfile.OPENMAPTILES)
        val r2 = read(listOf(areaSource("Paradise tiles", AreaDetail.MEDIUM, rows, t), deep))
        assertEquals(1, r2.found.count { it.name == "Panorama Point" })
        assertEquals(listOf("deep"), r2.maps)
    }

    @Test
    fun theOutdoorsProfileStaysAheadOfADeeperArea() {
        // ADR 0066 decision 1: for vector tiles the outdoors profile ranks before depth, as the map's own tiles do.
        val t = tiles(withPeaks)
        val outdoors = mapSource("outdoors", 12, t, profile = VectorProfile.OUTDOORS)
        val order = HorizonData.inLookupOrder(listOf(areaSource("Paradise tiles", AreaDetail.MEDIUM, rowsAround(paradise, 14), t), outdoors))
        val (z, x, y) = tileOf(46.8020, -121.7270, 12)
        assertEquals(listOf("outdoors", "Paradise tiles"), order(z, x, y).map { it.name })
    }

    @Test
    fun theChosenMapIsTheTieBetweenTwoOfEqualDepth() {
        val t = emptyMap<Triple<Int, Int, Int>, ByteArray>()
        val (z, x, y) = tileOf(lat, lon, 12)
        // Without the choice the smaller box goes first; the map chosen last wins the tie; a deeper map still goes ahead of it.
        val small = mapSource("small", 14, t, halfDeg = 0.5)
        val big = mapSource("big", 14, t, halfDeg = 1.5)
        assertEquals(listOf("small", "big"), HorizonData.inLookupOrder(listOf(big, small))(z, x, y).map { it.name })
        val bigChosen = mapSource("big", 14, t, halfDeg = 1.5, chosen = true)
        assertEquals(listOf("big", "small"), HorizonData.inLookupOrder(listOf(small, bigChosen))(z, x, y).map { it.name })
        val deeper = mapSource("deeper", 15, t, halfDeg = 1.5)
        assertEquals(listOf("deeper", "big", "small"), HorizonData.inLookupOrder(listOf(small, bigChosen, deeper))(z, x, y).map { it.name })
    }

    @Test
    fun anAreaAtLessIsReadAtTheSameZoomsAsAFullOneAndLosesNoPeak() {
        // The read asks z12 down to z9 (Viewshed.readPeaks: the layer is thinned below z13; never z13 or z14), all inside
        // Less's z8 to z13: a Less area names as many peaks as a Full one over the same tiles.
        val many = withPeaks + Triple("Alta Vista", 46.7990, -121.7310) + Triple("Pinnacle Peak", 46.7570, -121.7490)
        val t = tiles(many)
        val pts = many.map { it.second to it.third }
        val askedLess = ArrayList<Int>()
        val askedFull = ArrayList<Int>()
        // The round plot's default zoom for a 60 km range (Viewshed.peakZoom: z10), so z12, z11 and z10 are read.
        val less = read(listOf(areaSource("less", AreaDetail.LESS, rowsAround(pts, 13), t, askedLess)), 60_000.0, null)
        val full = read(listOf(areaSource("full", AreaDetail.FULL, rowsAround(pts, 14), t, askedFull)), 60_000.0, null)
        assertEquals(full.found.map { it.name }.toSet(), less.found.map { it.name }.toSet())
        assertEquals(many.size, less.found.size)
        assertEquals(setOf(10, 11, 12), askedLess.toSet())
        assertEquals(askedFull.toSet(), askedLess.toSet())
    }

    @Test
    fun theLineNamesTheMaps() {
        assertEquals("Names from the offline map wide.", HorizonData.namesFrom(listOf("wide")))
        assertEquals("Names from the offline maps fine and wide.", HorizonData.namesFrom(listOf("fine", "wide")))
        assertEquals("Names from 3 offline maps.", HorizonData.namesFrom(listOf("a", "b", "c")))
    }
}
