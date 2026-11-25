package io.github.jasondorjeshort.dystopo.app.online

import io.github.jasondorjeshort.dystopo.packs.Holdings
import io.github.jasondorjeshort.dystopo.packs.Holdings.Kind
import io.github.jasondorjeshort.dystopo.packs.PackBounds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T275: the chip's source word from the records alone ([OnlineView.legacySourceOf], kept for a view whose lookup failed). A DEM
 * tile recorded earlier and since cleared from the cache no longer hides the world overview: the post-wave sweep's europe-z5
 * view read "no data here" over the overview the map drew. T359: the word from the lookup ([OnlineView.sourceOf]), over the
 * two-maps fixture's places (Mount Rainier, public), [OnlineView.offAhead] and the one network rule.
 */
class OnlineViewSourceTest {
    @Test
    fun goneTileFallsBackToTheWorldOverviewWhereItDraws() {
        assertEquals(OnlineTiles.WORLD, OnlineView.legacySourceOf(OnlineService.ONLINE, gone = true, packDem = true) { true })
        assertEquals(OnlineTiles.NONE, OnlineView.legacySourceOf(OnlineService.CACHED, gone = true, packDem = true) { false })
    }

    @Test
    fun aTileStillOnThePhoneKeepsItsSource() {
        var asked = false
        assertEquals(OnlineService.CACHED, OnlineView.legacySourceOf(OnlineService.CACHED, gone = false, packDem = false) { asked = true; true })
        assertEquals(false, asked)
        assertEquals(OnlineTiles.WORLD, OnlineView.legacySourceOf(OnlineTiles.WORLD, gone = false, packDem = true) { false })
    }

    @Test
    fun withoutOnlineModeThePackOrNothing() {
        assertEquals(OnlineTiles.PACK, OnlineView.legacySourceOf(null, gone = false, packDem = true) { true })
        assertEquals(OnlineTiles.NONE, OnlineView.legacySourceOf(null, gone = false, packDem = false) { true })
    }

    private fun served(vector: Kind?, dem: Kind?, network: Boolean) = OnlineView.Served(vector, dem, vector?.word, dem?.word, network)
    private fun source(s: OnlineView.Served, vectorRecord: String? = null, demRecord: String? = null, world: Boolean = false) =
        OnlineView.sourceOf(s, vectorRecord, demRecord) { world }

    @Test
    fun mapsServingBothTilesAreOnThePhone() {
        assertEquals(OnlineTiles.PACK, source(served(Kind.MAP, Kind.MAP, network = true)))
        assertEquals(OnlineTiles.PACK, source(served(Kind.MAP, Kind.MAP, network = false)))
    }

    @Test
    fun theRoadsFromTheNetworkAboveTheMapsElevationAreTheNetworks() {
        // T341's follow-up: at z7.5 inside the coarse map the vector tile (z7) is below the map's first zoom; the elevation (z8) is the map's.
        assertEquals(OnlineService.ONLINE, source(served(null, Kind.MAP, network = true)))
        // Offline nothing fetches the roads: what draws is the phone's.
        assertEquals(OnlineTiles.PACK, source(served(null, Kind.MAP, network = false)))
    }

    @Test
    fun outsideEveryMapTheNetworkWhereAFetchIsAllowedElseNothing() {
        // T339's follow-up: outside both maps the chip read "from your server" with a server set and "no data here" with the
        // phone's own reading: the vector tile is the network's whichever source is set.
        assertEquals(OnlineService.ONLINE, source(served(null, null, network = true)))
        assertEquals(OnlineTiles.NONE, source(served(null, null, network = false)))
        assertEquals(OnlineService.CACHED, source(served(null, null, network = false), demRecord = OnlineService.CACHED))
        assertEquals(OnlineTiles.WIDER, source(served(null, null, network = false), demRecord = OnlineTiles.WIDER))
        assertEquals(OnlineTiles.WORLD, source(served(null, null, network = false), world = true))
    }

    @Test
    fun aDeeperTileFetchedBehindAMapIsTheMapsUntilItLands() {
        // The map serves now; the network adds behind it (ADR 0066 decision 2): the map's word until the cache serves.
        assertEquals(OnlineTiles.PACK, source(served(Kind.MAP, Kind.MAP, network = true), demRecord = null))
        assertEquals(OnlineService.ONLINE, source(served(Kind.MAP, Kind.CACHE, network = true), demRecord = OnlineService.ONLINE))
        assertEquals(OnlineService.CACHED, source(served(Kind.MAP, Kind.CACHE, network = false), demRecord = null))
        // A cached tile at a zoom the map stores is not finer (T368's rule): the map's word, though the lookup names the cache.
        val same = OnlineView.Served(Kind.MAP, Kind.CACHE, "rainier-fine", "cache", network = true, demSameDepth = true)
        assertEquals(Kind.MAP, same.chipDem)
        assertEquals(OnlineTiles.PACK, source(same, demRecord = OnlineService.ONLINE))
    }

    @Test
    fun anAreaAndTheOverview() {
        assertEquals(OnlineView.AREA, source(served(Kind.AREA, Kind.AREA, network = false)))
        assertEquals(OnlineView.AREA, source(served(Kind.MAP, Kind.AREA, network = false)))
        assertEquals(OnlineTiles.WORLD, source(served(Kind.OVERVIEW, null, network = false)))
        assertEquals(OnlineService.CACHED, source(served(Kind.CACHE, null, network = true), vectorRecord = OnlineService.CACHED))
    }

    private val wideBox = PackBounds(-121.95, 46.68, -121.45, 46.98)
    private val fineBox = PackBounds(-121.82, 46.80, -121.68, 46.90)
    private fun map(name: String, box: PackBounds, dem: Int, off: Boolean = false) =
        Holdings.Holding(name, Kind.MAP, Holdings.Stored(8, dem, box), Holdings.Stored(8, dem, box), off = off)

    @Test
    fun aMapThatIsOffIsBlamedOnlyWhereItWouldServeAhead() {
        // The summit at z13 (the elevation tile of a z12 view): the fine map stores z14, the wide one z11.
        val here = { list: List<Holdings.Holding> -> Holdings.point(Holdings.Role.DEM, 46.8529, -121.7604, 13, list, null) }
        assertTrue(OnlineView.offAhead(here(listOf(map("wide", wideBox, 11), map("fine", fineBox, 14, off = true)))))
        assertFalse(OnlineView.offAhead(here(listOf(map("wide", wideBox, 11, off = true), map("fine", fineBox, 14)))))
        assertTrue(OnlineView.offAhead(here(listOf(map("wide", wideBox, 11, off = true), map("fine", fineBox, 14, off = true)))))
        assertFalse(OnlineView.offAhead(here(listOf(map("wide", wideBox, 11), map("fine", fineBox, 14)))))
    }

    @Test
    fun theNetworkRule() {
        assertTrue(OnlineUi.networkOf(capabilities = true, internet = true))
        assertFalse(OnlineUi.networkOf(capabilities = true, internet = false)) // an active network without the internet
        assertFalse(OnlineUi.networkOf(capabilities = false, internet = false)) // airplane mode: no active network
        assertEquals(true, OnlineUi.hasNetwork(null, orElse = true)) // no app to ask (this JVM test): the caller's answer
        assertEquals(false, OnlineUi.hasNetwork(null, orElse = false))
    }
}
