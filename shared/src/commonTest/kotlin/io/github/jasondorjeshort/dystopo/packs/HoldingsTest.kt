package io.github.jasondorjeshort.dystopo.packs

import io.github.jasondorjeshort.dystopo.packs.Holdings.Online
import io.github.jasondorjeshort.dystopo.packs.Holdings.Overview
import io.github.jasondorjeshort.dystopo.packs.Holdings.Source
import kotlin.test.Test
import kotlin.test.assertEquals

/** T341: [Holdings.vectorTile], the world overview (z0 to z5) against the online vector source (stored to z14). */
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
    fun withTheSettingOffTheOverviewAnswersAsBefore() {
        assertEquals(Triple(Source.OVERVIEW, false, "overview-online-off"), answer(4, on = online(detail = false)))
        assertEquals(Triple(Source.OVERVIEW, false, "overview-online-off"), answer(4, on = online(detail = false), cached = true))
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
}
