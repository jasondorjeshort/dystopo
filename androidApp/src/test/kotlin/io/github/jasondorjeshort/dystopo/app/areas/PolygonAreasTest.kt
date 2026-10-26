package io.github.jasondorjeshort.dystopo.app.areas

import io.github.jasondorjeshort.dystopo.areas.AreaOfInterest
import io.github.jasondorjeshort.dystopo.areas.AreaPins
import io.github.jasondorjeshort.dystopo.areas.AreaPoint
import io.github.jasondorjeshort.dystopo.areas.AreaShape
import io.github.jasondorjeshort.dystopo.areas.NeedsNewerApp
import io.github.jasondorjeshort.dystopo.geo.WebMercator
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import java.time.LocalDate
import java.util.Locale
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T140 (ADR 0041 §2 to §5): the phone's records of polygon areas, opaque records, the sibling file, the plan's lines. */
class PolygonAreasTest {
    /** `areas.json` as v0.1.10 writes it (a region pack's box, a trip's corridor, a disc), written by that release's Record.json. */
    private val v0110: String = javaClass.getResource("/areas/areas-v0.1.10.json")!!.readText()

    private val rainierRing = listOf(AreaPoint(46.70, -121.95), AreaPoint(46.72, -121.55), AreaPoint(46.98, -121.60), AreaPoint(47.00, -121.90))

    /** A record of a kind this build does not know, as a newer build might write it (hand-written). */
    private val regionRecord = """{"id":"a0123456789abcdef","name":"Some region","kind":"region","content":"tiles","pinned":true,"state":"done",""" +
        """"source":"http://10.0.2.2:8765","bytes":5000000,"createdAt":1760000300000,"bounds":[-121.9,46.7,-121.6,46.95],"zooms":[8,13],""" +
        """"area":{"kind":"region","code":"X-1","minZoom":8,"maxZoom":13},"versions":{"10.0.2.2_8765":{"dem":"v9"}},"future":{"a":[1,2]}}"""

    @Test
    fun aV0110FileIsReadUnchangedAndWrittenBackByteEqual() {
        val files = AreaFiles.decode(v0110, null)
        assertEquals(3, files.records.size)
        assertTrue(files.records.none { it.needsNewerApp })
        assertEquals(listOf("box", "corridor", "corridor"), files.records.map { it.area.kind })
        assertEquals(v0110, files.mainText(), "written back byte-equal")
        assertNull(files.siblingText(), "no sibling file for what v0.1.10 reads whole")
    }

    @Test
    fun anUnknownKindIsKeptPinnedByItsBoundsAndWrittenToTheSibling() {
        val withRegion = v0110.dropLast(2) + "," + regionRecord + "]}" // written into areas.json by hand
        val files = AreaFiles.decode(withRegion, null)
        val r = files.records.single { it.id == "a0123456789abcdef" }
        assertTrue(r.needsNewerApp)
        assertEquals("kind must be box, corridor or polygon (got 'region')", r.opaque!!.reason)
        assertEquals(AreaShape.Box(-121.9, 46.7, -121.6, 46.95), r.area.shape)
        assertEquals(8..13, r.area.minZoom..r.area.maxZoom)
        assertTrue(r.pinned && r.versions["10.0.2.2_8765"] == mapOf("dem" to "v9"))
        // Pinned by its box: a tile inside it is held.
        val pins = AreaPins(listOf(AreaPins.cover(opaquePinArea(r.area, 1_000_000))))
        val z = 13
        val x = floor(WebMercator.lonToX(-121.75, z)).toInt()
        val y = floor(WebMercator.latToY(46.8, z)).toInt()
        assertTrue(pins.isPinned("10.0.2.2_8765/dem/$z/$x/$y.png"))
        // A save: areas.json is v0.1.10's again, byte for byte; the record goes to the sibling unchanged.
        assertEquals(v0110, files.mainText())
        val sibling = MiniJson.parseObject(assertNotNull(files.siblingText()))
        assertEquals(2L, (sibling["version"] as Number).toLong())
        assertEquals(MiniJson.parseObject(regionRecord), (sibling["areas"] as List<*>).single())
        // The next start reads both files and loses nothing.
        val again = AreaFiles.decode(files.mainText(), files.siblingText())
        assertEquals(4, again.records.size)
        assertEquals(files.siblingText(), again.siblingText())
        assertEquals(mapOf("needsNewerApp" to true, "needsNewerAppReason" to r.opaque!!.reason), shapeJson(r))
    }

    @Test
    fun aVersion3RecordIsKeptWithTheVersionSentenceAndARecordWithoutBoundsAsItIs() {
        val v3 = """{"version":3,"id":"a00000000000000aa","name":"Later","bounds":[-121.8,46.7,-121.7,46.8],"zooms":[8,15],"area":{"kind":"polygon","rings":[]}}"""
        val bare = """{"kind":"region","name":"No bounds"}"""
        val files = AreaFiles.decode(null, """{"version":2,"areas":[$v3,$bare]}""")
        val r = files.records.single()
        assertEquals("manifest version 3 is newer than this app reads (2)", r.opaque!!.reason, "the version before the kind")
        assertEquals(listOf(MiniJson.parseObject(bare)), files.unreadable)
        val sibling = MiniJson.parseObject(files.siblingText()!!)["areas"] as List<*>
        assertEquals(listOf(MiniJson.parseObject(v3), MiniJson.parseObject(bare)), sibling)
        assertEquals("""{"version":1,"areas":[]}""", files.mainText())
    }

    @Test
    fun aPolygonRecordGoesToTheSiblingAsVersion2AndReadsBack() {
        val area = AreaOfInterest(AreaShape.Polygon(listOf(rainierRing)), 8, 15, "Drawn area")
        val rec = Areas.Record(id = area.id, name = "Drawn area", area = area, createdAt = 1L, state = "done", source = "sources", bytes = 3, tiles = 2)
        val files = AreaFiles(AreaFiles.decode(v0110, null).records + rec)
        assertEquals(v0110, files.mainText(), "a version-1 reader never sees it")
        val back = AreaFiles.decode(files.mainText(), files.siblingText())
        assertEquals(rec, back.records.last())
        assertEquals(2L, ((MiniJson.parseObject(files.siblingText()!!)["areas"] as List<*>).single() as Map<*, *>)["version"].let { (it as Number).toLong() })
        val extras = shapeJson(rec)
        assertEquals(1, extras["rings"])
        assertEquals(4, extras["vertices"])
        assertTrue((extras["areaKm2"] as Double) in 700.0..900.0, "${extras["areaKm2"]}")
    }

    @Test
    fun theOverBudgetSentenceIsTheOwners() {
        val mb = 1024L * 1024L
        val e = areaBudgetError(pinnedBytes = 1638 * mb, estimateBytes = 450 * mb, alreadyBytes = 0, budgetMb = 2048)
        assertEquals(
            "This area is about 450 MB; 1.6 GB of your 2 GB offline budget is in use. Make the area smaller, choose less detail, or raise the budget in Settings › Storage.",
            e,
        )
        assertNull(areaBudgetError(pinnedBytes = 100 * mb, estimateBytes = 450 * mb, alreadyBytes = 0, budgetMb = 2048))
        // A disc and a polygon get it; a trip's corridor keeps T91's.
        val disc = AreaOfInterest.around(AreaPoint(46.786, -121.735), 5000.0)
        val trip = AreaOfInterest(AreaShape.Corridor(rainierRing, 1000.0), 8, 15)
        assertTrue(budgetRefusal(disc, 1638 * mb, 450 * mb, 0, 2048)!!.startsWith("This area is about"))
        assertTrue(budgetRefusal(trip, 1638 * mb, 450 * mb, 0, 2048)!!.startsWith("Offline areas would use"))
        assertEquals("disc", shapeName(disc))
        assertEquals("corridor", shapeName(trip))
    }

    @Test
    fun theSizeTimeAndLessDetailLines() {
        val mb = 1024L * 1024L
        assertEquals("About 170 MB (1,300 tiles) at full detail", sizeLine(170 * mb, 1300, 8, 15))
        assertEquals("About 30 MB (190 tiles) at less detail", sizeLine(30 * mb, 190, 8, 13))
        assertEquals("About 1.5 GB (12,000 tiles) at zoom 8 to 14", sizeLine(1536 * mb, 12_000, 8, 14))
        assertEquals("Less detail (about 30 MB)", lessDetailLine(30 * mb))
        assertNull(timeLine(170 * mb, null), "before the first download")
        assertEquals("Takes about 3 min at the speed of the last download", timeLine(170 * mb, 1_000.0))
        assertEquals("Takes under a minute at the speed of the last download", timeLine(30 * mb, 1_000.0))
        assertEquals("Takes about 2.0 h at the speed of the last download", timeLine(7200L * 1_000_000, 1_000.0))
    }

    @Test
    fun theLastCompletedDownloadsSpeed() {
        val mb = 1024L * 1024L
        val r = { id: String, created: Long, bytes: Long, ms: Long?, state: String -> Areas.Record(id, id, AreaOfInterest.around(AreaPoint(46.786, -121.735), 5000.0), created, state = state, bytes = bytes, downloadMs = ms) }
        assertNull(downloadSpeed(emptyList(), emptyList()))
        val records = listOf(r("a1", 1, 10 * mb, 10_000, "done"), r("a2", 2, 20 * mb, 10_000, "done"), r("a3", 3, 50 * mb, 1000, "failed"))
        assertEquals(20.0 * mb / 10_000, downloadSpeed(emptyList(), records)!!, 1e-9)
        val recent = listOf(mapOf("state" to "cancelled", "bytes" to 9 * mb, "ms" to 1000L), mapOf("state" to "done", "bytes" to 4 * mb, "ms" to 2000L))
        assertEquals(4.0 * mb / 2000, downloadSpeed(recent, records)!!, 1e-9, "this run's newest completed one first")
        assertNull(downloadSpeed(listOf(mapOf("state" to "done", "bytes" to 1000L, "ms" to 5000L)), emptyList()), "too small to time")
    }

    @Test
    fun namesRecentAndShapes() {
        val day = LocalDate.of(2026, 10, 25)
        val poly = AreaOfInterest(AreaShape.Polygon(listOf(rainierRing)), 8, 15)
        assertEquals("Drawn area Oct 25, 2026", defaultName(poly, day, Locale.US))
        assertEquals("Around here Oct 25, 2026", defaultName(AreaOfInterest.around(AreaPoint(46.786, -121.735), 5000.0), day, Locale.US))
        // T136's follow-up: the finished job is shown once, above `recent`.
        val recent = listOf(mapOf("id" to "a1", "finishedAt" to 10L), mapOf("id" to "a0", "finishedAt" to 5L))
        assertEquals(listOf(mapOf("id" to "a0", "finishedAt" to 5L)), recentWithout(recent, "a1", 10L))
        assertEquals(recent, recentWithout(recent, "a2", 0L), "a running job is not in it")
        assertEquals(listOf("box", "corridor"), serverShapesOf(emptyMap()), "a server from before T140")
        assertEquals(listOf("box", "corridor", "polygon"), serverShapesOf(mapOf("shapes" to listOf("box", "corridor", "polygon"))))
    }

    @Test
    fun anOpaqueRecordsPinIsCappedByTheHeap() {
        val big = AreaOfInterest(AreaShape.Box(-125.0, 25.0, -70.0, 50.0), 8, 15)
        val capped = opaquePinArea(big, 262_144)
        assertTrue(capped.maxZoom < 15, "z${capped.maxZoom}")
        assertTrue(io.github.jasondorjeshort.dystopo.areas.AreaLimit.upperBound(capped, 0..capped.maxZoom, null, 1) <= 131_072)
        val small = AreaOfInterest(AreaShape.Box(-121.9, 46.7, -121.6, 46.95), 8, 13)
        assertEquals(small, opaquePinArea(small, 262_144))
    }

    @Test
    fun polygonPartsAndCardLines() {
        val hole = listOf(AreaPoint(46.80, -121.82), AreaPoint(46.82, -121.70), AreaPoint(46.90, -121.72), AreaPoint(46.88, -121.80))
        val part = listOf(AreaPoint(46.50, -121.40), AreaPoint(46.52, -121.20), AreaPoint(46.62, -121.25))
        val parts = polygonParts(listOf(rainierRing, hole, part))
        assertEquals(listOf(rainierRing to listOf(hole), part to emptyList()), parts)
        assertEquals("Within 5 km of a point", discLine(5000.0, ContourUnits.METERS))
        assertEquals("Within 3.1 mi of a point", discLine(5000.0, ContourUnits.FEET))
        assertEquals("Within 3 mi of a point", discLine(3 * 1609.344, ContourUnits.FEET))
        assertEquals("Within 800 m of a point", discLine(800.0, ContourUnits.METERS))
        assertEquals("Area of 12.7 km²", polygonLine(12.7e6, ContourUnits.METERS))
        assertEquals("Area of 4.9 sq mi", polygonLine(12.7e6, ContourUnits.FEET))
        assertEquals("Area of 2,750 km²", polygonLine(2750e6, ContourUnits.METERS))
        assertFalse(NeedsNewerApp.USER_SENTENCE.isEmpty())
    }
}
