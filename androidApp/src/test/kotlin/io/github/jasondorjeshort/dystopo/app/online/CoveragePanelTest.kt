package io.github.jasondorjeshort.dystopo.app.online

import io.github.jasondorjeshort.dystopo.app.PacksRoutes
import io.github.jasondorjeshort.dystopo.packs.Holdings
import io.github.jasondorjeshort.dystopo.packs.Holdings.Holding
import io.github.jasondorjeshort.dystopo.packs.Holdings.Kind
import io.github.jasondorjeshort.dystopo.packs.Holdings.Role
import io.github.jasondorjeshort.dystopo.packs.Holdings.Stored
import io.github.jasondorjeshort.dystopo.packs.PackBounds
import io.github.jasondorjeshort.dystopo.packs.VectorProfile
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T360: the coverage panel's lines are the lookup's answer and nothing else. Each case builds the lookup's inputs (the
 * two-maps fixture's pair over Mount Rainier, an area, the tile cache, the world overview, the online member), asks
 * [Holdings] as [PacksRoutes.holdingsHere] does, and reads the panel from that one answer.
 */
class CoveragePanelTest {
    private val wideBox = PackBounds(-121.95, 46.68, -121.45, 46.98)
    private val fineBox = PackBounds(-121.82, 46.80, -121.68, 46.90)
    private val summit = 46.8529 to -121.7604
    private val wideOnly = 46.73 to -121.56
    private val fineEdge = 46.8025 to -121.75

    private fun map(name: String, box: PackBounds, vector: Int, dem: Int, off: Boolean = false, chosen: Boolean = false, minZoom: Int = 8) =
        Holding(name, Kind.MAP, Stored(minZoom, vector, box), Stored(minZoom, dem, box), VectorProfile.OUTDOORS, chosen, off)

    private val wide = map("rainier-wide", wideBox, 13, 11, chosen = true)
    private val fine = map("rainier-fine", fineBox, 14, 14)
    private val world = Holding("world", Kind.OVERVIEW, Stored(0, 5, PackBounds(-180.0, -85.0, 180.0, 85.0)), null, VectorProfile.OPENMAPTILES)

    private fun online(allowed: Boolean = true, detail: Boolean = true) = Holdings.Online(14, allowed, detail, if (allowed) null else "offline", demMaxZoom = 15)

    /** The lookup's answer as [PacksRoutes.holdingsHere] makes it (the tile paths' answer read from the maps and the overview, or [vectorPath]/[demPath]). */
    private fun here(at: Pair<Double, Double>, zoom: Double, all: List<Holding>, on: Holdings.Online?, vectorPath: String? = null, demPath: String? = null): PacksRoutes.HoldingsHere {
        val vz = floor(zoom).toInt()
        val dz = vz + 1
        val now = all.filter { !it.off && (it.kind == Kind.MAP || it.kind == Kind.OVERVIEW) }
        val v = Holdings.point(Role.VECTOR, at.first, at.second, vz, all, on)
        val d = Holdings.point(Role.DEM, at.first, at.second, dz, all, on)
        val rule = Holdings.vectorTile(vz, false, null, on, false)
        return PacksRoutes.HoldingsHere(
            zoom, at.first, at.second, all, on, v, d, Holdings.point(Role.VECTOR, at.first, at.second, vz, now, on),
            Holdings.point(Role.DEM, at.first, at.second, dz, now, on), rule, null, vectorPath, demPath,
        )
    }

    private fun lines(h: PacksRoutes.HoldingsHere) = CoveragePanel.lines(h)

    @Test
    fun twoMapsTheFineOneShownTheWideOneUnderItTheNetworkFiner() {
        val h = here(summit, 13.0, listOf(wide, fine, world), online())
        assertEquals(
            listOf("rainier-fine · Full detail · shown", "rainier-wide · Low detail · under it", "Online · finer at z14 and up"),
            lines(h),
        )
        // The lines are the lookup's answer: the one shown is what the tile paths serve, the rest are its members.
        assertEquals("rainier-fine", h.vectorNow.serving?.holding?.name)
        assertEquals(setOf("rainier-fine", "rainier-wide"), (h.vector.members + h.dem.members).map { it.holding.name }.toSet())
        // At z14 the network's elevation (z15) is finer here; the same answer with the setting off says why it is not used.
        assertEquals("Online · finer here", lines(here(summit, 14.0, listOf(wide, fine), online())).last())
        assertEquals("Online · finer here · not used: More detail from online is off", lines(here(summit, 14.0, listOf(wide, fine), online(detail = false))).last())
    }

    @Test
    fun insideTheWideMapOnlyTheNetworkIsFinerAlready() {
        val l = lines(here(wideOnly, 12.0, listOf(wide, fine), online()))
        assertEquals(listOf("rainier-wide · Low detail · shown", "Online · finer here"), l)
        // Offline: the same holding, and the network not reachable.
        assertEquals(listOf("rainier-wide · Low detail · shown", "Online · finer here · not reachable now"), lines(here(wideOnly, 12.0, listOf(wide, fine), online(allowed = false))))
    }

    @Test
    fun anAreaTheCacheAndTheirLevels() {
        val area = Holding(
            "my trail", Kind.AREA, Stored(8, 15, wideBox), Stored(8, 15, wideBox), VectorProfile.OPENMAPTILES,
            cells = { _, _, _, _ -> true },
        )
        val cache = Holding("cache", Kind.CACHE, Stored(0, 14), Stored(0, 15), VectorProfile.OPENMAPTILES, cells = { r, _, _, _ -> r == Role.DEM })
        val h = here(wideOnly, 12.0, listOf(wide, area, cache), online(), vectorPath = "my trail", demPath = "my trail")
        val l = lines(h)
        assertEquals("my trail · Full detail · shown", l.first())
        assertTrue("rainier-wide · Low detail · under it" in l, "$l")
        assertTrue("Viewed earlier · under it" in l, "$l")
        // The cache drawing the terrain: its own word, no level.
        val c = lines(here(wideOnly, 12.0, listOf(wide, cache), online(), demPath = "cache"))
        assertEquals(listOf("rainier-wide · Low detail · roads shown", "Viewed earlier · terrain shown"), c.take(2))
    }

    @Test
    fun aMapThatIsOffIsNotUsed() {
        val l = lines(here(summit, 13.0, listOf(wide, map("rainier-fine", fineBox, 14, 14, off = true)), online()))
        assertEquals(listOf("rainier-wide · Low detail · shown", "rainier-fine · Not used", "Online · finer here"), l)
    }

    @Test
    fun nothingHeld() {
        val out = 46.0 to -121.7
        assertEquals(listOf("Nothing on this phone here"), lines(here(out, 12.0, listOf(wide, fine), null)))
        assertEquals(listOf("Nothing on this phone here", "Online · not reachable now"), lines(here(out, 12.0, listOf(wide, fine), online(allowed = false))))
        // With the network: it shows the view.
        assertEquals(listOf("Online · shown"), lines(here(out, 12.0, listOf(wide, fine), online())))
    }

    @Test
    fun outsideTheMapsTheTilesKeptFromViewingDrawWhereTheTilePathsNameNone() {
        // Offline outside both maps, the view seen earlier: the lookup serves both tiles from the cache, which the tile paths
        // read before the network (`tilePathServes` names the cache only at the overview's zooms), so it is shown, not the
        // world overview enlarged.
        val cache = Holding("cache", Kind.CACHE, Stored(0, 14), Stored(0, 15), VectorProfile.OPENMAPTILES, cells = { _, _, _, _ -> true })
        val out = 46.0 to -121.7
        assertEquals(listOf("Viewed earlier · shown", "Online · not reachable now"), lines(here(out, 12.0, listOf(wide, fine, world, cache), online(allowed = false))))
        // Nothing kept: the overview, enlarged.
        assertEquals(
            listOf("World overview · roads shown", "Coarser here: roads enlarged from z5", "Online · not reachable now"),
            lines(here(out, 12.0, listOf(wide, fine, world), online(allowed = false))),
        )
    }

    @Test
    fun steppedDown() {
        // Near the fine map's edge at z11 its box covers only part of the vector tile: the wide map draws the roads, the finer one
        // is passed over there (its elevation tile, one zoom deeper, lies inside it).
        val edge = lines(here(fineEdge, 11.0, listOf(wide, fine), null))
        assertEquals(listOf("rainier-fine · Full detail · terrain shown", "rainier-wide · Low detail · roads shown", "Coarser here: the finer map ends"), edge)
        // Offline past the wide map's deepest vector zoom (z13): its roads are enlarged.
        val past = lines(here(wideOnly, 14.0, listOf(wide), online(allowed = false)))
        assertEquals(listOf("rainier-wide · Low detail · shown", "Coarser here: roads enlarged from z13", "Online · finer here · not reachable now"), past)
        // At z5 the world overview draws where the maps start at z8.
        val low = lines(here(summit, 5.0, listOf(wide, fine, world), null))
        assertEquals(listOf("World overview · roads shown", "Coarser here: rainier-wide starts at z8"), low)
        // At z6.5, offline, past the overview's z5: its roads enlarged, and the maps still start deeper.
        assertEquals(listOf("World overview · roads shown", "Coarser here: rainier-wide starts at z8", "Online · not reachable now"), lines(here(summit, 6.5, listOf(wide, fine, world), online(allowed = false))))
    }

    @Test
    fun atMostFiveLinesThenAndNMore() {
        val many = (1..8).map { "line $it" }
        assertEquals((1..5).map { "line $it" } to 3, CoveragePanel.fit(many))
        assertEquals(many.take(5) to 0, CoveragePanel.fit(many.take(5)))
        assertEquals("and 3 more", CoveragePanel.json(many)!!["more"])
        assertEquals(null, CoveragePanel.json(many.take(2))!!["more"])
    }

    @Test
    fun theSectionShortensDownToNothingBeforeTheColumnHides() {
        // The rule places the column while it is at most `room` tall: a stand-in for OverlayLayout.place.
        fun fit(room: Float, measured: Float, viewport: Float, natural: Float) =
            CoveragePanel.fitColumn(measured, viewport, natural, { h -> h to (h <= room) }) { it.second }
        // Room for all of it: the whole section.
        assertEquals(300f, fit(400f, 300f, 100f, 100f).first)
        // Room for part: the most the rule places, within 1 dp; the column shown.
        val part = fit(250f, 300f, 100f, 100f)
        assertTrue(part.first in 249f..250f && part.second.second, "$part")
        // Room for the card alone: the section shortens to nothing, the column shown.
        val none = fit(200f, 300f, 100f, 100f)
        assertTrue(none.first in 200f..200.5f && none.second.second, "$none")
        // Not even the card alone: the whole height, as before the section (the column hides for the card, not the section).
        assertEquals(300f, fit(150f, 300f, 100f, 100f).first)
        // Measured within an earlier limit (the section drawn 20 of its 100 dp): the same answer as measured whole.
        assertTrue(fit(250f, 220f, 20f, 100f).first in 249f..250f)
        // Wherever the column fits without the section, it fits with it.
        for (room in 150..450 step 7) {
            val r = room.toFloat()
            val (h, p) = fit(r, 300f, 100f, 100f)
            if (r >= 200f) assertTrue(p.second && h <= r, "room $r: $h")
        }
    }
}
