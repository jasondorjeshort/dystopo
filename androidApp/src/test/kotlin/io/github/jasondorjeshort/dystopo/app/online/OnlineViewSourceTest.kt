package io.github.jasondorjeshort.dystopo.app.online

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * T275: the chip's source word ([OnlineView.sourceOf]). A DEM tile recorded earlier and since cleared from the cache no longer
 * hides the world overview: the post-wave sweep's europe-z5 view read "no data here" over the overview the map drew.
 */
class OnlineViewSourceTest {
    @Test
    fun goneTileFallsBackToTheWorldOverviewWhereItDraws() {
        assertEquals(OnlineTiles.WORLD, OnlineView.sourceOf(OnlineService.ONLINE, gone = true, packDem = true) { true })
        assertEquals(OnlineTiles.NONE, OnlineView.sourceOf(OnlineService.CACHED, gone = true, packDem = true) { false })
    }

    @Test
    fun aTileStillOnThePhoneKeepsItsSource() {
        var asked = false
        assertEquals(OnlineService.CACHED, OnlineView.sourceOf(OnlineService.CACHED, gone = false, packDem = false) { asked = true; true })
        assertEquals(false, asked)
        assertEquals(OnlineTiles.WORLD, OnlineView.sourceOf(OnlineTiles.WORLD, gone = false, packDem = true) { false })
    }

    @Test
    fun withoutOnlineModeThePackOrNothing() {
        assertEquals(OnlineTiles.PACK, OnlineView.sourceOf(null, gone = false, packDem = true) { true })
        assertEquals(OnlineTiles.NONE, OnlineView.sourceOf(null, gone = false, packDem = false) { true })
    }
}
