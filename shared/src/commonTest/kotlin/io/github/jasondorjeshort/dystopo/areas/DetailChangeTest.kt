package io.github.jasondorjeshort.dystopo.areas

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** T386: a saved area's detail changed with its shape kept ([DetailChange]); public places only (Paradise, Mount Rainier). */
class DetailChangeTest {
    private val ring = listOf(AreaPoint(46.781, -121.752), AreaPoint(46.781, -121.718), AreaPoint(46.797, -121.718), AreaPoint(46.797, -121.752))
    private val drawn = AreaOfInterest(AreaShape.Polygon(listOf(ring)), 8, 15, "Paradise")
    private val demLo = 8 // the public sources' lowest elevation zoom

    private fun change(from: AreaDetail, to: AreaDetail) = DetailChange.of(drawn, from, to, demLo)

    @Test
    fun lessToMediumAddsTheVectorZ14Only() {
        val c = change(AreaDetail.LESS, AreaDetail.MEDIUM)
        assertTrue(c.raise && !c.lower)
        assertEquals(mapOf(AreaManifest.ROUTE_OSM to setOf(14)), c.added)
        val m = DetailChange.raiseManifest(c)
        assertEquals(listOf(AreaManifest.ROUTE_OSM), m.items.map { (it as TilesItem).route })
        assertEquals(setOf(14), m.tiles(AreaManifest.ROUTE_OSM)!!.tiles.keys)
        assertEquals(drawn.at(AreaDetail.MEDIUM).id, m.id, "under the new id")
    }

    @Test
    fun mediumToFullAddsTheElevationZ14AndZ15Only() {
        val c = change(AreaDetail.MEDIUM, AreaDetail.FULL)
        assertEquals(mapOf(AreaManifest.ROUTE_DEM to setOf(14, 15)), c.added)
        assertTrue(c.dropped.isEmpty())
        val m = DetailChange.raiseManifest(c)
        assertEquals(0, m.vectorTileCount, "no vector tile: the cap has nothing to count")
        assertEquals(setOf(14, 15), m.tiles(AreaManifest.ROUTE_DEM)!!.tiles.keys)
    }

    @Test
    fun lessToFullAddsBoth() {
        val c = change(AreaDetail.LESS, AreaDetail.FULL)
        assertEquals(mapOf(AreaManifest.ROUTE_DEM to setOf(14, 15), AreaManifest.ROUTE_OSM to setOf(14)), c.added)
    }

    @Test
    fun aLowerDropsTheSameZoomsAndFetchesNothing() {
        assertEquals(mapOf(AreaManifest.ROUTE_DEM to setOf(14, 15), AreaManifest.ROUTE_OSM to setOf(14)), change(AreaDetail.FULL, AreaDetail.LESS).dropped)
        assertEquals(mapOf(AreaManifest.ROUTE_DEM to setOf(14, 15)), change(AreaDetail.FULL, AreaDetail.MEDIUM).dropped)
        val c = change(AreaDetail.MEDIUM, AreaDetail.LESS)
        assertEquals(mapOf(AreaManifest.ROUTE_OSM to setOf(14)), c.dropped)
        assertTrue(c.lower && !c.raise && c.rekey)
        assertTrue(DetailChange.addedItems(c).isEmpty())
        assertTrue(change(AreaDetail.FULL, AreaDetail.FULL).none)
    }

    @Test
    fun theRekeyedManifestKeepsWhatTheAreaHadAndDropsTheTopZooms() {
        val full = drawn.at(AreaDetail.FULL)
        val whole = AreaManifest(full.id, "Paradise", full, AreaManifest.STATUS_READY, AreaManifest.tileItems(full, full.demZooms(demLo, 15), full.osmZooms()) + LocalItem(ManifestItem.KIND_CONTOURS, "dem", true, null))
        val c = change(AreaDetail.FULL, AreaDetail.LESS)
        val less = DetailChange.rekeyed(whole, c)
        assertEquals(drawn.at(AreaDetail.LESS).id, less.id)
        assertEquals((8..13).toSet(), less.tiles(AreaManifest.ROUTE_DEM)!!.tiles.keys)
        assertEquals((8..13).toSet(), less.tiles(AreaManifest.ROUTE_OSM)!!.tiles.keys)
        assertTrue(less.items.any { it.kind == ManifestItem.KIND_CONTOURS }, "the other items stay")
        // The tiles freed are exactly the top zooms of the whole manifest.
        val dropped = DetailChange.droppedItems(whole, c).associate { it.route to it.tiles.keys }
        assertEquals(mapOf(AreaManifest.ROUTE_DEM to setOf(14, 15), AreaManifest.ROUTE_OSM to setOf(14)), dropped)
        assertEquals(whole.tileCount, less.tileCount + DetailChange.droppedItems(whole, c).sumOf { it.count })
        // Raised again: the same rows as the whole one.
        val back = DetailChange.rekeyed(less, DetailChange.of(less.area, full, demLo))
        assertEquals(whole.tileCount, back.tileCount)
        assertEquals(full.id, back.id)
    }

    @Test
    fun aManifestOfFewerRowsKeepsThem() {
        // T377's fixture keeps only the vector tiles a pack held: a lower keeps its rows, a raise adds the cover of the new zoom.
        val medium = drawn.at(AreaDetail.MEDIUM)
        val partial = TileSet(13).apply { add(1, 1) }
        val only = AreaManifest(medium.id, "Paradise", medium, AreaManifest.STATUS_READY, listOf(TilesItem(AreaManifest.ROUTE_OSM, "pbf", "", mapOf(13 to partial, 14 to TileSet(14).apply { add(2, 2) }), 0)))
        val less = DetailChange.rekeyed(only, change(AreaDetail.MEDIUM, AreaDetail.LESS))
        assertEquals(1, less.tileCount)
        assertEquals(listOf(AreaManifest.ROUTE_OSM), less.items.map { (it as TilesItem).route }, "no elevation item made by a lower")
    }

    @Test
    fun theCapCountsTheAddedTilesAlone() {
        val c = change(AreaDetail.LESS, AreaDetail.MEDIUM)
        val medium = drawn.at(AreaDetail.MEDIUM)
        val wholeVector = AreaManifest.tileItems(medium, null, medium.osmZooms()).single().count
        val added = DetailChange.raiseManifest(c).vectorTileCount
        assertEquals(TileCover.of(medium, 14, 1).size, added)
        assertTrue(added < wholeVector, "the cap counts $added added tiles, not the area's $wholeVector")
        assertFalse(AreaLimit.overDirectCap(added.toLong()))
    }

    @Test
    fun aPendingRaiseCountsFromWhatIsHeld() {
        // Less held, Full recorded (a raise waits): Medium fetches the vector z14 and frees the elevation z14 and z15 fetched so far.
        val c = DetailChange.of(drawn.at(AreaDetail.LESS), drawn.at(AreaDetail.MEDIUM), demLo, current = drawn.at(AreaDetail.FULL))
        assertEquals(mapOf(AreaManifest.ROUTE_OSM to setOf(14)), c.added)
        assertEquals(mapOf(AreaManifest.ROUTE_DEM to setOf(14, 15)), c.dropped)
        // Choosing the held detail again cancels the raise: nothing fetched, the raise's zooms freed.
        val back = DetailChange.of(drawn.at(AreaDetail.LESS), drawn.at(AreaDetail.LESS), demLo, current = drawn.at(AreaDetail.FULL))
        assertFalse(back.raise)
        assertEquals(mapOf(AreaManifest.ROUTE_DEM to setOf(14, 15), AreaManifest.ROUTE_OSM to setOf(14)), back.dropped)
        assertNotEquals(back.current.id, back.target.id)
    }
}
