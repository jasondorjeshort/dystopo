package io.github.jasondorjeshort.dystopo.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** T368: the network goes ahead of the map only past the map's depth, where it stores deeper, under the setting. */
class NetworkAheadTest {
    @Test
    fun aServerDeeperThanAMediumMapFetchesItsZ14AndZ15() {
        assertTrue(NetworkAhead.fetches(14, phoneDepth = 13, networkStored = 15, detail = true, allowed = true))
        assertTrue(NetworkAhead.fetches(15, 13, 15, true, true))
        // At the map's own zooms it has all the detail there is.
        assertFalse(NetworkAhead.fetches(13, 13, 15, true, true))
        assertFalse(NetworkAhead.fetches(12, 13, 15, true, true))
    }

    @Test
    fun directReadingAtItsDefaultAddsNothingOverAMediumMap() {
        // DirectSources.DEFAULT_MAX_ZOOM 12 stores less than a z13 map: no DEM fetch inside it (proposal 0066, B2).
        assertFalse(NetworkAhead.fetches(14, 13, 12, true, true))
        // Over a map to z11 it adds its z12, and what it renders past it.
        assertTrue(NetworkAhead.fetches(12, 11, 12, true, true))
        assertTrue(NetworkAhead.fetches(14, 11, 12, true, true))
    }

    @Test
    fun theSettingOffOrNoFetchAllowedFetchesNothing() {
        assertFalse(NetworkAhead.fetches(15, 11, 15, detail = false, allowed = true))
        assertFalse(NetworkAhead.fetches(15, 11, 15, detail = true, allowed = false))
    }

    @Test
    fun whereThePhoneHoldsNothingTheOnlinePathAsksAsBefore() {
        assertFalse(NetworkAhead.fetches(15, null, 15, true, true))
        assertFalse(NetworkAhead.fetches(15, 11, null, true, true))
    }

    @Test
    fun theStylesTopElevationZoom() {
        assertEquals(15, NetworkAhead.styleTop(13, 15, detail = true, cap = 15))
        assertEquals(13, NetworkAhead.styleTop(13, 15, detail = false, cap = 15))
        assertEquals(13, NetworkAhead.styleTop(13, 12, detail = true, cap = 15))
        assertEquals(14, NetworkAhead.styleTop(14, null, detail = true, cap = 15))
        assertEquals(15, NetworkAhead.styleTop(14, 16, detail = true, cap = 15))
    }
}
