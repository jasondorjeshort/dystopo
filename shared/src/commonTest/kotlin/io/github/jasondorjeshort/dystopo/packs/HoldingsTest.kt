package io.github.jasondorjeshort.dystopo.packs

import io.github.jasondorjeshort.dystopo.packs.Holdings.Holding
import io.github.jasondorjeshort.dystopo.packs.Holdings.How
import io.github.jasondorjeshort.dystopo.packs.Holdings.Kind
import io.github.jasondorjeshort.dystopo.packs.Holdings.Online
import io.github.jasondorjeshort.dystopo.packs.Holdings.Overview
import io.github.jasondorjeshort.dystopo.packs.Holdings.Role
import io.github.jasondorjeshort.dystopo.packs.Holdings.Source
import io.github.jasondorjeshort.dystopo.packs.Holdings.Stored
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * T341: [Holdings.vectorTile], the world overview (z0 to z5) against the online vector source (stored to z14). T354: the
 * lookup ([Holdings.tile], [Holdings.point]) on the two-maps fixture's pair, areas' cells, the cache, a map that is off, the
 * network's member, and its agreement with `PackPrecedence` tile by tile; [Holdings.pointZoom].
 */
class HoldingsTest {
    private val overview = Overview(0, 5)
    private fun online(allowed: Boolean = true, detail: Boolean = true, stored: Int = 14) = Online(stored, allowed, detail, if (allowed) null else "offline")

    private fun answer(z: Int, map: Boolean = false, ov: Overview? = overview, on: Online? = online(), cached: Boolean = false) =
        Holdings.vectorTile(z, map, ov, on, cached).let { Triple(it.serves, it.fetchBehind, it.cause) }

    @Test
    fun aMapAtItsOwnZoomsAnswersWhateverTheNetwork() {
        assertEquals(Triple(Source.MAP, false, "map"), answer(9, map = true))
        assertEquals(Triple(Source.MAP, false, "map"), answer(4, map = true, cached = true))
    }

    @Test
    fun atTheOverviewsZoomsTheNetworkGoesAheadUnderTheSetting() {
        // Not on the phone yet: the overview answers at once and the fetch runs behind it.
        assertEquals(Triple(Source.OVERVIEW, true, "online-fetching"), answer(3))
        // Fetched earlier: the kept online tile answers.
        assertEquals(Triple(Source.CACHE, false, "online-kept"), answer(5, cached = true))
        // The overview's own zoom range is the edge: z0 and z5 included.
        assertEquals(Source.OVERVIEW, answer(0).first)
        assertEquals(true, answer(0).second)
    }

    @Test
    fun withTheSettingOffNothingIsFetchedButTheKeptTileStillAnswers() {
        assertEquals(Triple(Source.OVERVIEW, false, "overview-online-off"), answer(4, on = online(detail = false)))
        // T356 (ADR 0066 decision 2): the kept tile is held by the phone, ranked by depth whatever the setting says.
        assertEquals(Triple(Source.CACHE, false, "online-kept"), answer(4, on = online(detail = false), cached = true))
    }

    @Test
    fun offlineTheOverviewAnswersAtOnceAndAKeptTileStillWins() {
        assertEquals(Triple(Source.OVERVIEW, false, "overview-not-allowed"), answer(4, on = online(allowed = false)))
        assertEquals(Triple(Source.CACHE, false, "online-kept"), answer(4, on = online(allowed = false), cached = true))
    }

    @Test
    fun theNetworkGoesAheadOnlyWhereItStoresDeeper() {
        // A source storing no deeper than the overview never goes ahead of it.
        assertEquals(Triple(Source.OVERVIEW, false, "overview"), answer(4, on = online(stored = 5)))
        assertEquals(Triple(Source.OVERVIEW, false, "overview"), answer(4, on = online(stored = 5), cached = true))
        assertEquals(Triple(Source.OVERVIEW, true, "online-fetching"), answer(4, on = online(stored = 6)))
    }

    @Test
    fun aboveTheOverviewTheNetworkAnswersAndWithoutASourceNothingDoes() {
        assertEquals(Triple(Source.NETWORK, false, "online"), answer(6))
        assertEquals(Triple(Source.NETWORK, false, "online"), answer(7, on = online(allowed = false)))
        assertEquals(Triple(Source.NONE, false, "no-source"), answer(6, on = null))
        assertEquals(Triple(Source.OVERVIEW, false, "overview"), answer(5, on = null))
        assertEquals(Triple(Source.NETWORK, false, "online"), answer(3, ov = null))
    }

    // ---- T354: the lookup ----

    private val wideBox = PackBounds(-121.95, 46.68, -121.45, 46.98)
    private val fineBox = PackBounds(-121.82, 46.80, -121.68, 46.90)
    private val summit = 46.8529 to -121.7604
    private val wideOnly = 46.73 to -121.56

    private fun map(name: String, box: PackBounds, vector: Int, dem: Int, chosen: Boolean = false, off: Boolean = false, profile: VectorProfile = VectorProfile.OUTDOORS) =
        Holding(name, Kind.MAP, Stored(8, vector, box), Stored(8, dem, box), profile, chosen, off)

    /** The two-maps fixture's pair: the wide map's vector to z13 and elevation to z11, the fine one's both to z14. */
    private fun pair(chosenWide: Boolean) = listOf(map("wide", wideBox, 13, 11, chosen = chosenWide), map("fine", fineBox, 14, 14, chosen = !chosenWide))

    private fun serves(role: Role, at: Pair<Double, Double>, z: Int, holdings: List<Holding>, online: Online? = null) =
        Holdings.point(role, at.first, at.second, z, holdings, online).serving?.holding?.name

    @Test
    fun aFineMapInsideACoarseOneServesInsideItWhicheverWasChosenLast() {
        for (chosenWide in listOf(true, false)) {
            val h = pair(chosenWide)
            for (z in 12..15) assertEquals("fine", serves(Role.DEM, summit, z, h), "dem z$z chosenWide=$chosenWide")
            for (z in 12..14) assertEquals("fine", serves(Role.VECTOR, summit, z, h), "vector z$z chosenWide=$chosenWide")
            for (z in 12..15) assertEquals("wide", serves(Role.DEM, wideOnly, z, h))
            val here = Holdings.point(Role.DEM, summit.first, summit.second, 13, h, null)
            assertEquals(listOf("fine", "wide"), here.members.map { it.holding.name })
            assertEquals(listOf(How.STORES, How.SYNTHESISES), here.members.map { it.how }, "the wide map's elevation stops at z11")
        }
    }

    @Test
    fun twoMapsOfEqualDepthTheChosenOneServes() {
        for (first in listOf("a", "b")) {
            val h = listOf(map("a", wideBox, 14, 14, chosen = first == "a"), map("b", fineBox, 14, 14, chosen = first == "b"))
            assertEquals(first, serves(Role.DEM, summit, 13, h))
            assertEquals(first, serves(Role.VECTOR, summit, 13, h))
        }
    }

    @Test
    fun theOutdoorsProfileIsAheadForVectorTilesWhateverTheDepth() {
        val h = listOf(map("omt", fineBox, 14, 14, profile = VectorProfile.OPENMAPTILES), map("outdoors", wideBox, 13, 11))
        assertEquals("outdoors", serves(Role.VECTOR, summit, 13, h))
        assertEquals("omt", serves(Role.DEM, summit, 13, h), "elevation has no profile: depth first")
    }

    /** A cell holding: an area or the cache, holding exactly the tiles of [held]. */
    private fun cells(name: String, kind: Kind, vector: Int, dem: Int, held: Set<Triple<Int, Int, Int>>) =
        Holding(name, kind, Stored(8, vector), Stored(8, dem), VectorProfile.OPENMAPTILES, cells = { _, z, x, y -> Triple(z, x, y) in held })

    private fun tileOf(at: Pair<Double, Double>, z: Int): Triple<Int, Int, Int> =
        Holdings.point(Role.DEM, at.first, at.second, z, emptyList(), null).let { Triple(it.z, it.x, it.y) }

    @Test
    fun anAreasHeldCellsAndACachedTileRankByDepth() {
        val t13 = tileOf(wideOnly, 13)
        val area = cells("area", Kind.AREA, 15, 15, setOf(t13))
        val cache = cells("cache", Kind.CACHE, 14, 12, setOf(t13))
        val wide = map("wide", wideBox, 13, 11, chosen = true)
        // Elevation: the area stores to z15, the cache's source to z12, the map to z11: the deepest first.
        val here = Holdings.point(Role.DEM, wideOnly.first, wideOnly.second, 13, listOf(wide, cache, area), null)
        assertEquals(listOf("area", "cache", "wide"), here.members.map { it.holding.name })
        assertEquals("area", here.serving?.holding?.name)
        // Without the area the cached tile (z12 source) is deeper than the map's z11 elevation.
        assertEquals("cache", serves(Role.DEM, wideOnly, 13, listOf(wide, cache)))
        // A deeper map wins over the cache; at equal depth the map goes before the cache.
        assertEquals("deep", serves(Role.DEM, wideOnly, 13, listOf(map("deep", wideBox, 14, 14), cache)))
        assertEquals("even", serves(Role.DEM, wideOnly, 13, listOf(cells("cache", Kind.CACHE, 14, 13, setOf(t13)), map("even", wideBox, 13, 13))))
        // An area holds only its cells: a tile next to them is the map's.
        assertEquals("wide", serves(Role.DEM, wideOnly, 13, listOf(wide, cells("area", Kind.AREA, 15, 15, setOf(Triple(13, t13.second + 1, t13.third))))))
        // An area answers only at its own zooms.
        assertEquals("wide", Holdings.point(Role.DEM, wideOnly.first, wideOnly.second, 16, listOf(wide, area), null).serving?.holding?.name)
    }

    @Test
    fun aMapThatIsOffIsListedOffAndHoldsNothing() {
        val h = listOf(map("wide", wideBox, 13, 11), map("fine", fineBox, 14, 14, chosen = true, off = true))
        val here = Holdings.point(Role.DEM, summit.first, summit.second, 13, h, null)
        assertEquals("wide", here.serving?.holding?.name)
        assertEquals(listOf("wide"), here.members.map { it.holding.name })
        assertEquals(listOf("fine"), here.off.map { it.name })
        val alone = Holdings.point(Role.VECTOR, summit.first, summit.second, 13, h.drop(1), null)
        assertEquals(null, alone.serving)
        assertEquals(listOf("fine"), alone.off.map { it.name })
        assertEquals(emptyList(), Holdings.point(Role.DEM, wideOnly.first, wideOnly.second, 13, h, null).off.map { it.name }, "off only where it would hold the tile")
    }

    @Test
    fun theWorldOverviewIsLastAndAnEdgeTileGoesToTheHoldingThatHasAllOfIt() {
        val world = Holding("world", Kind.OVERVIEW, Stored(0, 5, PackBounds(-180.0, -85.0, 180.0, 85.0)), null, VectorProfile.OUTDOORS)
        val h = listOf(world, map("wide", wideBox, 13, 11))
        assertEquals("world", serves(Role.VECTOR, summit, 4, h))
        assertEquals("wide", serves(Role.VECTOR, summit, 12, h))
        // At z5 the wide map is not at its zooms (z8 up): the overview.
        assertEquals("world", serves(Role.VECTOR, summit, 5, h))
    }

    @Test
    fun theNetworkAddsWhereItStoresDeeper() {
        val h = pair(chosenWide = true)
        val on = Online(14, allowed = true, detail = true, demMaxZoom = 12)
        // Inside the wide map only: its elevation stops at z11, the source stores to z12.
        val wide = Holdings.point(Role.DEM, wideOnly.first, wideOnly.second, 13, h, on).network!!
        assertEquals(12, wide.adds)
        assertEquals(true, wide.ahead)
        assertEquals(false, Holdings.point(Role.DEM, wideOnly.first, wideOnly.second, 13, h, Online(14, true, detail = false, demMaxZoom = 12)).network!!.ahead)
        // Inside the fine map: nothing to add.
        assertEquals(null, Holdings.point(Role.DEM, summit.first, summit.second, 13, h, on).network!!.adds)
        // Outside both: the network is all there is (adds its depth; not "ahead" of anything).
        val outside = Holdings.point(Role.DEM, 46.0, -121.7, 13, h, on)
        assertEquals(null, outside.serving)
        assertEquals(12, outside.network!!.adds)
        assertEquals(false, outside.network!!.ahead)
        // Vector: z14 stored against the wide map's z13.
        assertEquals(14, Holdings.point(Role.VECTOR, wideOnly.first, wideOnly.second, 12, h, on).network!!.adds)
        assertEquals(null, Holdings.point(Role.DEM, summit.first, summit.second, 13, h, null).network)
    }

    @Test
    fun theLookupAndPackPrecedenceAnswerAlikeTileByTile() {
        // Every tile of z8 to z15 over the pair (and a third, plain map), each chosen in turn: the lookup's serving map is
        // PackPrecedence.choose's, for both roles.
        val third = PackBounds(-121.9, 46.85, -121.6, 47.0)
        for (chosen in listOf("wide", "fine", "plain")) {
            val maps = listOf(
                map("wide", wideBox, 13, 11, chosen = chosen == "wide"), map("fine", fineBox, 14, 14, chosen = chosen == "fine"),
                map("plain", third, 14, 13, chosen = chosen == "plain", profile = VectorProfile.OPENMAPTILES),
            )
            for (role in Role.entries) {
                val sorted = PackPrecedence.sort(maps.map { it.coverage(role)!! }) { it }
                for (z in 8..15) {
                    val t0 = tileOf(46.99 to -121.96, z)
                    val t1 = tileOf(46.67 to -121.44, z)
                    val step = maxOf(1, (t1.second - t0.second) / 12)
                    for (x in t0.second..t1.second step step) for (y in t0.third..t1.third step step) {
                        val i = PackPrecedence.choose(sorted, z, x, y, role.zooms)
                        val want = if (i < 0) null else sorted[i].pack
                        assertEquals(want, Holdings.tile(role, z, x, y, maps, null).serving?.holding?.name, "$role $z/$x/$y chosen=$chosen")
                        val cand = PackPrecedence.candidates(sorted, z, x, y, role.zooms).map { sorted[it].pack }
                        assertEquals(cand, Holdings.tile(role, z, x, y, maps, null).candidates.map { it.holding.name }, "$role $z/$x/$y candidates")
                    }
                }
            }
        }
    }

    @Test
    fun thePointZoomIsTheDeepestMapHoldingItAndTheOnlineSourceBehindItsInput() {
        assertEquals(14, Holdings.pointZoom(listOf(11, 14), listOf(11, 14), 15, onlineByDepth = false, cap = 14))
        assertEquals(11, Holdings.pointZoom(listOf(11), listOf(11, 14), 12, onlineByDepth = false, cap = 14), "inside the wide map only: the online source waits")
        assertEquals(12, Holdings.pointZoom(listOf(11), listOf(11, 14), 12, onlineByDepth = true, cap = 14), "with the input on the deeper online source counts")
        assertEquals(14, Holdings.pointZoom(emptyList(), listOf(14), 12, onlineByDepth = false, cap = 14), "past the boxes, inside a map's tiles")
        assertEquals(12, Holdings.pointZoom(emptyList(), emptyList(), 12, onlineByDepth = false, cap = 14))
        assertEquals(null, Holdings.pointZoom(emptyList(), emptyList(), null, onlineByDepth = false, cap = 14))
        val h = pair(chosenWide = true)
        val on = Online(14, true, true, demMaxZoom = 12)
        assertEquals(14, Holdings.readZoom(h, on, summit.first, summit.second, 14))
        assertEquals(11, Holdings.readZoom(h, on, wideOnly.first, wideOnly.second, 14))
        assertEquals(12, Holdings.readZoom(h, on, wideOnly.first, wideOnly.second, 14, onlineByDepth = true))
        assertEquals(11, Holdings.readZoom(h.map { if (it.name == "fine") Holding("fine", Kind.MAP, it.vector, it.dem, off = true) else it }, on, summit.first, summit.second, 14), "an off map is not read")
    }

    // T360
    @Test
    fun pastAMapsDeepestVectorZoomTheMapIsListedAsShallowerNotAsAMember() {
        val h = pair(chosenWide = true)
        // Inside the wide map only, at z14: the wide map stores vector to z13, so no member serves; it is the one shallower.
        val here = Holdings.point(Role.VECTOR, wideOnly.first, wideOnly.second, 14, h, null)
        assertEquals(emptyList(), here.members.map { it.holding.name })
        assertEquals(listOf("wide"), here.shallower.map { it.holding.name })
        assertEquals(listOf(13), here.shallower.map { it.depth })
        assertEquals(false, here.shallower.single().serves)
        // At its own zooms it is a member, and nothing is shallower.
        assertEquals(emptyList(), Holdings.point(Role.VECTOR, wideOnly.first, wideOnly.second, 13, h, null).shallower)
        // Inside both at z15: the fine map (z14) and the wide one (z13), deepest first.
        assertEquals(listOf("fine", "wide"), Holdings.point(Role.VECTOR, summit.first, summit.second, 15, h, null).shallower.map { it.holding.name })
        // A map that is off is not listed; elevation never lists any (a map past its depth is a member).
        assertEquals(emptyList(), Holdings.point(Role.VECTOR, wideOnly.first, wideOnly.second, 14, listOf(map("wide", wideBox, 13, 11, off = true)), null).shallower)
        assertEquals(emptyList(), Holdings.point(Role.DEM, wideOnly.first, wideOnly.second, 14, h, null).shallower)
        // Who serves is unchanged.
        assertEquals(null, here.serving)
    }
    // end T360
}
