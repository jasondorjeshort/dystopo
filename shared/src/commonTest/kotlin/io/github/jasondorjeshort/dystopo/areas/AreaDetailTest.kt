package io.github.jasondorjeshort.dystopo.areas

import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T157 (ADR 0041 §5's T157 note): the medium detail's per-kind zoom cap through the area, its id, the manifest and the details. */
class AreaDetailTest {
    private val paradise = AreaPoint(46.786, -121.735)
    private val disc = AreaOfInterest.around(paradise, 5000.0, name = "Around Paradise")

    @Test
    fun anUncappedAreaKeepsItsIdAndJson() {
        // The disc of the v0.1.10 and v0.1.13 fixtures (`areas-v0.1.10.json`): the same id as those releases computed.
        assertEquals("a3331977ee67a76d9", disc.id)
        assertNull(disc.demMaxZoom)
        assertEquals(1, disc.manifestVersion)
        assertTrue("demMaxZoom" !in disc.json())
        assertEquals(disc, AreaOfInterest.parse(disc.json()))
    }

    @Test
    fun theThreeDetailsHaveThreeIds() {
        val full = disc.at(AreaDetail.FULL)
        val medium = disc.at(AreaDetail.MEDIUM)
        val less = disc.at(AreaDetail.LESS)
        assertEquals(disc.id, full.id, "full detail is the disc as before")
        assertEquals(setOf(full.id, medium.id, less.id).size, 3)
        assertNotEquals(medium.id, disc.withZooms(8, 14).id, "the cap is in the id")
        assertEquals(8 to 14, medium.minZoom to medium.maxZoom)
        assertEquals(13, medium.demMaxZoom)
        assertEquals(13, medium.demTop)
        assertEquals(3, medium.manifestVersion)
        assertEquals(AreaDetail.MEDIUM, AreaDetail.of(medium))
        assertEquals(AreaDetail.LESS, AreaDetail.of(less))
        assertEquals(AreaDetail.FULL, AreaDetail.of(full))
        assertNull(AreaDetail.of(disc.withZooms(8, 14)))
        // A polygon at medium detail is version 3 too.
        val poly = AreaOfInterest(AreaShape.Polygon(listOf(listOf(AreaPoint(46.77, -121.76), AreaPoint(46.77, -121.71), AreaPoint(46.80, -121.71)))), 8, 15)
        assertEquals(2, poly.manifestVersion)
        assertEquals(3, poly.at(AreaDetail.MEDIUM).manifestVersion)
    }

    @Test
    fun theCapIsWrittenAfterMaxZoomAndReadBack() {
        val medium = disc.at(AreaDetail.MEDIUM)
        val json = medium.json()
        assertEquals(listOf("kind", "line", "bufferM", "minZoom", "maxZoom", "demMaxZoom", "name"), json.keys.toList())
        assertEquals(13, json["demMaxZoom"])
        val back = AreaOfInterest.parse(MiniJson.parseObject(MiniJson.encode(json)))
        assertEquals(medium, back)
        assertEquals(medium.id, back.id)
        // A cap at or past maxZoom is no cap: the same area and id as without it.
        assertEquals(disc, AreaOfInterest.parse(disc.json() + ("demMaxZoom" to 15)))
        assertEquals(disc, AreaOfInterest.parse(disc.json() + ("demMaxZoom" to "16")))
        assertFailsWith<IllegalArgumentException> { AreaOfInterest.parse(disc.json() + ("demMaxZoom" to -1)) }
        assertFailsWith<IllegalArgumentException> { AreaOfInterest.parse(disc.json() + ("demMaxZoom" to "x")) }
        assertFailsWith<IllegalArgumentException> { AreaOfInterest(disc.shape, 8, 14, demMaxZoom = 14) }
        // withZooms drops a cap that no longer lies below the range.
        assertNull(medium.withZooms(8, 13, medium.demMaxZoom).demMaxZoom)
    }

    @Test
    fun theQueryTakesTheCapOrTheDetail() {
        val q = mapOf("around" to "46.786,-121.735", "radiusM" to "5000")
        assertEquals(disc.at(AreaDetail.MEDIUM).id, AreaOfInterest.fromQuery(q + ("detail" to "medium")).id)
        assertEquals(disc.at(AreaDetail.MEDIUM).id, AreaOfInterest.fromQuery(q + mapOf("maxZoom" to "14", "demMaxZoom" to "13")).id)
        assertEquals(disc.id, AreaOfInterest.fromQuery(q + ("detail" to "full")).id)
        assertEquals(disc.at(AreaDetail.LESS).id, AreaOfInterest.fromQuery(q + ("detail" to "less")).id)
        assertFailsWith<IllegalArgumentException> { AreaOfInterest.fromQuery(q + ("detail" to "most")) }
    }

    @Test
    fun theMediumManifestHoldsVectorZ14AndNoElevationZ14() {
        val medium = disc.at(AreaDetail.MEDIUM)
        assertEquals(6..13, medium.demZooms(6, 15))
        assertEquals(8..14, medium.osmZooms(14))
        assertEquals(6..15, disc.demZooms(6, 15))
        assertEquals(6..13, disc.at(AreaDetail.LESS).demZooms(6, 15))
        assertEquals(8..13, disc.at(AreaDetail.LESS).osmZooms(14))
        val items = AreaManifest.tileItems(medium, medium.demZooms(6, 15), medium.osmZooms(14))
        val dem = items.single { it.route == AreaManifest.ROUTE_DEM }
        val osm = items.single { it.route == AreaManifest.ROUTE_OSM }
        assertEquals(6..13, dem.zooms)
        assertEquals(8..14, osm.zooms)
        val m = AreaManifest(medium.id, "Around Paradise", medium, AreaManifest.STATUS_READY, items)
        assertEquals(3, m.version)
        val back = AreaManifest.parse(MiniJson.encode(m.json()))
        assertEquals(medium, back.area)
        assertEquals(3, back.version)
        assertEquals(m.tileCount, back.tileCount)
        // Bytes: medium sits between less and full, much nearer less (the DEM at z14 and z15 is most of full; for this 5 km
        // disc about 24, 25 and 67 MB, the margin tiles weighing more than over a large area).
        fun bytes(a: AreaOfInterest) = AreaManifest.tileItems(a, a.demZooms(6, 15), a.osmZooms(14)).sumOf { it.bytesEstimate }
        val (l, md, f) = Triple(bytes(disc.at(AreaDetail.LESS)), bytes(medium), bytes(disc))
        assertTrue(l < md && md * 2 < f && md - l < (f - md) / 10, "less $l, medium $md, full $f")
    }

    @Test
    fun aVersion4ManifestNeedsANewerApp() {
        val m = AreaManifest(disc.id, "x", disc, AreaManifest.STATUS_READY, emptyList()).json() + ("version" to 4)
        val e = assertFailsWith<NeedsNewerApp> { AreaManifest.parse(m) }
        assertEquals("manifest version 4 is newer than this app reads (3)", e.message)
    }
}
