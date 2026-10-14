package io.github.jasondorjeshort.dystopo.routing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T77: the long-drive overlay ([DriveOverlay]) on a synthetic road grid near the equator that spans
 * about 5 by 3 graph tiles (a z11 tile is about 19.6 km there): long drives through the overlay find
 * the same route as the full search while settling far fewer nodes, a drive the overlay cannot carry
 * (a minor road is the only way across a tile border) falls back to the full search, and hiking and
 * short drives never use it.
 */
class DriveOverlayTest {
    private val source = GraphSource("grid-outdoors.pmtiles", "fp", "", 14, 0, drive = true)

    private fun found(r: RouteResult): PlannedRoute {
        assertIs<RouteResult.Found>(r, "expected a route, got $r")
        return r.plan
    }

    /** Minor roads every 0.05 degrees, tertiary and secondary roads every 0.2, a one-way primary, all within lat 0..0.4, lon 0..0.8. */
    private fun grid(minorOnlyAcross: Boolean = false): RoutingGraph {
        val lines = ArrayList<WayLine>()
        fun line(a: WayAttrs, vararg p: Pair<Double, Double>) { lines += WayLine.of(a, *p) }
        val minor = WayAttrs(WayClass.MINOR, Surface.PAVED)
        val tertiary = WayAttrs(WayClass.TERTIARY, Surface.PAVED, name = "Grid Road")
        val secondary = WayAttrs(WayClass.SECONDARY, Surface.PAVED, name = "Cross Highway")
        if (minorOnlyAcross) {
            // Two towns of tertiary streets joined only by one minor road across several tile borders.
            line(tertiary, 0.05 to 0.02, 0.05 to 0.15); line(tertiary, 0.02 to 0.05, 0.10 to 0.05)
            line(tertiary, 0.05 to 0.65, 0.05 to 0.78); line(tertiary, 0.02 to 0.70, 0.10 to 0.70)
            line(minor, 0.05 to 0.15, 0.05 to 0.65)
            return RoutingGraphBuilder().build(lines, source)
        }
        var v = 0.0
        while (v <= 0.8 + 1e-9) {
            val major = (v * 100).toInt() % 20 == 0
            line(if (major) secondary else minor, 0.0 to v, 0.4 to v)
            if (v <= 0.4 + 1e-9) line(if (major) tertiary else minor, v to 0.0, v to 0.8)
            v += 0.05
        }
        // A fast one-way primary, east-bound only, along lat 0.325.
        line(WayAttrs(WayClass.PRIMARY, Surface.PAVED, flags = WayAttrs.ONEWAY, name = "East Express"), 0.325 to 0.0, 0.325 to 0.8)
        return RoutingGraphBuilder().build(lines, source)
    }

    private fun routers(g: RoutingGraph): Pair<TileRouter, TileRouter> {
        val tg = TiledGraph(listOf(GraphTileFile.open(GraphTileFile.encode(g))))
        return TileRouter(tg) to TileRouter(tg, DriveOverlay())
    }

    @Test
    fun longDrivesThroughTheOverlayMatchTheFullSearch() {
        val (plain, fast) = routers(grid())
        val pairs = listOf(
            (0.05 to 0.03) to (0.35 to 0.77), // corner to corner (points on minor roads)
            (0.325 to 0.02) to (0.30 to 0.78), // along the one-way
            (0.15 to 0.11) to (0.25 to 0.63),
            // Back westwards: the fastest way leaves the start area on a minor road across a tile border,
            // which the overlay does not carry (tertiary and up only), so it is found but costs more.
            // (This grid's even lattice of minor roads is the worst case; on the Washington drives measured
            // in PLANNING the routes were identical.)
            (0.35 to 0.76) to (0.05 to 0.04),
        )
        for ((i, pair) in pairs.withIndex()) {
            val (a, b) = pair
            val full = found(plain.route(a.first, a.second, b.first, b.second, TravelMode.DRIVE))
            val over = found(fast.route(a.first, a.second, b.first, b.second, TravelMode.DRIVE))
            val label = "$a -> $b"
            assertTrue(over.cost >= full.cost * (1 - 1e-9), "$label: the overlay never beats the full search")
            if (i >= 2) {
                assertTrue(over.cost <= full.cost * 1.06, "$label: ${over.cost} against ${full.cost}")
                continue
            }
            assertEquals(full.cost, over.cost, full.cost * 1e-9, label)
            assertEquals(full.distanceM, over.distanceM, 0.01, label)
            assertEquals(full.timeS, over.timeS, 0.01, label)
            assertEquals(full.route.points.size, over.route.points.size, label)
            assertTrue(over.memory["overlayTiles"] as Int > 0, "$label used the overlay: ${over.memory}")
            assertTrue(over.expanded < full.expanded, "$label settled ${over.expanded} nodes with the overlay, ${full.expanded} without")
            // The route's points are a continuous line (each step under the grid spacing).
            over.route.points.zipWithNext().forEach { (p, q) ->
                assertTrue(io.github.jasondorjeshort.dystopo.gpx.TrackStats.distanceM(p.lat, p.lon, q.lat, q.lon) < 6000, "$label gap at $p")
            }
        }
    }

    @Test
    fun theOneWayIsOnlyUsedItsWay() {
        val (_, fast) = routers(grid())
        val east = found(fast.route(0.325, 0.02, 0.325, 0.78, TravelMode.DRIVE))
        assertTrue(east.metersByClass[WayClass.PRIMARY]!! > 80_000, "east on the primary: ${east.metersByClass}")
        val west = found(fast.route(0.325, 0.78, 0.325, 0.02, TravelMode.DRIVE))
        // Only the bits at either end (it starts and ends on the one-way, so it drives on to the next junction).
        assertNull(west.metersByClass[WayClass.PRIMARY]?.takeIf { it > 10_000 }, "west not on the one-way: ${west.metersByClass}")
    }

    @Test
    fun aDriveTheOverlayCannotCarryFallsBackToTheFullSearch() {
        val (plain, fast) = routers(grid(minorOnlyAcross = true))
        val full = found(plain.route(0.05, 0.03, 0.05, 0.77, TravelMode.DRIVE))
        val over = found(fast.route(0.05, 0.03, 0.05, 0.77, TravelMode.DRIVE))
        assertEquals(full.distanceM, over.distanceM, 0.01)
        assertNull(over.memory["overlayTiles"], "the answer is the full search's: ${over.memory}")
    }

    @Test
    fun theFullFallbackHasASettleBudget() {
        // T89 (critic of ADR 0028): the overlay cannot carry this drive (a minor road is the only way across), and the
        // full search it falls back to stops at the budget with a plain failure instead of searching on.
        val g = grid(minorOnlyAcross = true)
        val tg = TiledGraph(listOf(GraphTileFile.open(GraphTileFile.encode(g))))
        val full = found(TileRouter(tg, DriveOverlay()).route(0.05, 0.03, 0.05, 0.77, TravelMode.DRIVE))
        val capped = TileRouter(tg, DriveOverlay(), maxSettled = full.expanded / 2).route(0.05, 0.03, 0.05, 0.77, TravelMode.DRIVE)
        assertIs<RouteResult.Failed>(capped)
        assertEquals(RouteFailure.SEARCH_LIMIT, capped.reason, capped.message)
        assertTrue(capped.details["expanded"] as Int <= full.expanded / 2 + 1, "${capped.details}")
        assertTrue(capped.message.contains("search limit"), capped.message)
    }

    @Test
    fun hikingAndShortDrivesNeverUseIt() {
        val (plain, fast) = routers(grid())
        val hike = found(fast.route(0.05, 0.03, 0.35, 0.77, TravelMode.HIKE))
        assertNull(hike.memory["overlayTiles"])
        assertEquals(found(plain.route(0.05, 0.03, 0.35, 0.77, TravelMode.HIKE)).cost, hike.cost, 1e-6)
        val short = found(fast.route(0.05, 0.03, 0.15, 0.13, TravelMode.DRIVE))
        assertNull(short.memory["overlayTiles"])
    }

    @Test
    fun cellsAreBuiltOnceAndPricedExactly() {
        val g = grid()
        val archive = GraphTileFile.open(GraphTileFile.encode(g))
        val tg = TiledGraph(listOf(archive))
        val overlay = DriveOverlay()
        val e = archive.entries.first { tg.pieces(it.x, it.y).isNotEmpty() && overlay.piece(it.x, it.y, tg.pieces(it.x, it.y)) != null }
        val piece = overlay.piece(e.x, e.y, tg.pieces(e.x, e.y))!!
        val built = overlay.json()["built"]
        val hits = overlay.json()["hits"] as Long
        val o = piece.overlay!!
        // Every shortcut expands into base edges whose drive cost adds up to the shortcut's.
        val model = CostModel(TravelMode.DRIVE)
        for (s in 0 until piece.graph.edgeCount) {
            val steps = o.unpack(s)
            val c = steps.sumOf { (edge, _) -> o.base.graph.edgeLength[edge] * model.weight(o.base.graph, edge) }
            assertEquals(o.cost(s), c, 1e-3 * maxOf(1.0, c))
        }
        overlay.piece(e.x, e.y, tg.pieces(e.x, e.y))
        assertEquals(built, overlay.json()["built"])
        assertEquals(hits + 1, overlay.json()["hits"])
    }

    // ---- T89: the second level (z8 cells over the z11 cells) and cell files ----

    /** Secondary roads every 0.2 degrees over lon 0..4.4, lat 0..1.6 near the equator: across 4 by 2 z8 tiles. */
    private fun wideGrid(): RoutingGraph {
        val road = WayAttrs(WayClass.SECONDARY, Surface.PAVED, name = "Grid Highway")
        val lines = ArrayList<WayLine>()
        var v = 0.0
        while (v <= 4.4 + 1e-9) { lines += WayLine.of(road, 0.0 to v, 1.6 to v); v += 0.2 }
        v = 0.0
        while (v <= 1.6 + 1e-9) { lines += WayLine.of(road, v to 0.0, v to 4.4); v += 0.2 }
        return RoutingGraphBuilder().build(lines, source)
    }

    /** Builds every level-2 cell (and so every level-1 cell) of [archive], as the server's warm-up does. */
    private fun warm(overlay: DriveOverlay, archive: GraphTileArchive, tg: TiledGraph): Int {
        val step = overlay.tileZoom - overlay.level2Zoom
        var cells = 0
        for ((x2, y2) in archive.entries.map { (it.x shr step) to (it.y shr step) }.toSet()) {
            if (overlay.buildLevel2(x2, y2, archive.source, archive::has) { x, y -> tg.piece(0, x, y) } != null) cells++
        }
        return cells
    }

    @Test
    fun theSecondLevelGivesTheSameRouteSettlingFewerNodesAndItsCellsReloadFromAFile() {
        val g = wideGrid()
        val bytes = GraphTileFile.encode(g)
        val archive = GraphTileFile.open(bytes)
        val tg = TiledGraph(listOf(archive))
        val from = 0.2 to 0.1
        val to = 1.4 to 4.3
        val full = found(TileRouter(tg).route(from.first, from.second, to.first, to.second, TravelMode.DRIVE))
        val one = found(TileRouter(tg, DriveOverlay()).route(from.first, from.second, to.first, to.second, TravelMode.DRIVE))
        assertEquals(0, one.memory["level2Tiles"], "level 2 is never built inside a search: ${one.memory}")
        val overlay = DriveOverlay()
        val cells2 = warm(overlay, archive, tg)
        assertTrue(cells2 >= 6, "level-2 cells $cells2")
        val two = found(TileRouter(tg, overlay).route(from.first, from.second, to.first, to.second, TravelMode.DRIVE))
        assertTrue(two.memory["level2Tiles"] as Int > 0, "${two.memory}")
        for ((label, r) in listOf("level 1" to one, "level 2" to two)) {
            assertEquals(full.cost, r.cost, full.cost * 1e-9, label)
            assertEquals(full.distanceM, r.distanceM, 0.01, label)
            assertEquals(full.timeS, r.timeS, 0.01, label)
            assertEquals(full.route.points.size, r.route.points.size, label)
        }
        assertTrue(two.expanded < one.expanded, "level 2 settled ${two.expanded} nodes, level 1 ${one.expanded}")
        // The cell file: a fresh overlay reads every cell from it and builds none, with the same route.
        val (c1, c2) = overlay.cellsOf(archive.source)
        val file = OverlayCellFile.open(OverlayCellFile.encode(archive.source, overlay.tileZoom, overlay.level2Zoom, c1, c2))
        assertEquals(c1.size + c2.size, file.cellCount)
        val fresh = DriveOverlay()
        fresh.register(file)
        val again = found(TileRouter(tg, fresh).route(from.first, from.second, to.first, to.second, TravelMode.DRIVE))
        assertEquals(two.cost, again.cost, 1e-6)
        assertEquals(two.route.points, again.route.points)
        assertEquals(two.memory["level2Tiles"], again.memory["level2Tiles"])
        val stats = fresh.json()
        assertEquals(0L, stats["built"], "nothing built: $stats")
        assertEquals(0L, stats["level2Built"], "$stats")
        assertTrue(stats["loadedFromFiles"] as Long > 0, "$stats")
        // A cell read back is the cell written.
        val k = c1.first()
        val back = file.cell1(k.x, k.y)!!
        assertTrue(k.baseNode.contentEquals(back.baseNode) && k.cost.contentEquals(back.cost) && k.graph == back.graph)
        val k2 = c2.first()
        val back2 = file.cell2(k2.x, k2.y)!!
        assertTrue(k2.subNode.contentEquals(back2.subNode) && k2.subX.contentEquals(back2.subX) && k2.cost.contentEquals(back2.cost) && k2.graph == back2.graph)
        // Another graph's file is not used for this one, and a damaged file is refused.
        assertNull(file.cell1(-1, -1))
        kotlin.test.assertFailsWith<IllegalArgumentException> { OverlayCellFile.open(OverlayCellFile.encode(archive.source, 11, 8, c1, c2).copyOf(30)) }
    }
}
