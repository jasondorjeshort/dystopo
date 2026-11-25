package io.github.jasondorjeshort.dystopo.app.areas

import io.github.jasondorjeshort.dystopo.app.online.RegionDownload
import io.github.jasondorjeshort.dystopo.areas.AreaLimit
import io.github.jasondorjeshort.dystopo.areas.AreaManifest
import io.github.jasondorjeshort.dystopo.areas.AreaOfInterest
import io.github.jasondorjeshort.dystopo.areas.AreaPoint
import io.github.jasondorjeshort.dystopo.areas.AreaShape
import io.github.jasondorjeshort.dystopo.areas.TileSet
import io.github.jasondorjeshort.dystopo.areas.TilesItem
import io.github.jasondorjeshort.dystopo.packs.PackBounds
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T369: the public vector source's cap on a download with no server set (3,000 vector tiles and 50 MB a download): the plan's
 * refusal and its sentence, the pack path's count, the country rows, the resume check's wait, and a trip's corridor that waits
 * for the tap.
 */
class DirectCapTest {
    private val ring = listOf(AreaPoint(46.76, -121.80), AreaPoint(46.76, -121.70), AreaPoint(46.82, -121.70), AreaPoint(46.82, -121.80))
    private val area = AreaOfInterest(AreaShape.Polygon(listOf(ring)), 8, 15, "test")

    /** A plan whose manifest holds exactly [vectorTiles] vector tiles (one row at z14) and 100 elevation tiles. */
    private fun plan(vectorTiles: Int, direct: Boolean, a: AreaOfInterest = area, budgetMb: Long = 1_000_000): AreaDownload.Plan {
        val osm = TileSet(14).apply { addRow(5000, 0, vectorTiles - 1) }
        val dem = TileSet(14).apply { addRow(5000, 0, 99) }
        val items = listOf(TilesItem("dem", "png", "", mapOf(14 to dem), 100L * 160_000), TilesItem("osm", "pbf", "", mapOf(14 to osm), vectorTiles * 10_000L))
        return AreaDownload.Plan(a, AreaManifest(a.id, "x", a, AreaManifest.STATUS_READY, items), "phone", 0, 0, budgetMb, direct = direct)
    }

    @Test
    fun aPlanOf2999VectorTilesIsAllowedAndOf3001Refused() {
        val ok = plan(2_999, direct = true)
        assertEquals(2_999, ok.manifest.vectorTileCount)
        assertNull(ok.capRefusal)
        assertNull(ok.refusal)
        assertEquals(false, ok.json()["refused"])
        val over = plan(3_001, direct = true)
        assertEquals(
            "This area needs about 3,100 map tiles, and the public map source allows 3,000 a download. " +
                "Make the area smaller, choose less detail, or set a server in Settings › Sources.",
            over.refusal,
        )
        @Suppress("UNCHECKED_CAST") val cap = over.json()["cap"] as Map<String, Any?>
        assertEquals(true, cap["over"])
        assertEquals(3_001, cap["vectorTiles"])
        assertEquals(3_000L, cap["maxTiles"])
        assertEquals(true, over.json()["refused"])
        // The elevation tiles are not the cap's: 2,999 vector tiles and 100 elevation tiles are 3,099 in all, allowed.
        assertEquals(3_099, ok.tiles)
    }

    @Test
    fun theSamePlanWithAServerSetIsNotRefused() {
        val withServer = plan(3_001, direct = false)
        assertNull(withServer.capRefusal)
        assertNull(withServer.refusal)
        @Suppress("UNCHECKED_CAST") val cap = withServer.json()["cap"] as Map<String, Any?>
        assertEquals(false, cap["over"])
        assertEquals(false, cap["direct"])
    }

    @Test
    fun theCapComesBeforeTheBudgetWhichCannotLiftIt() {
        val both = plan(5_000, direct = true, budgetMb = 1)
        assertTrue(both.refusal!!.startsWith("This area needs about 5,000 map tiles"), both.refusal)
        assertTrue(plan(100, direct = true, budgetMb = 1).refusal!!.startsWith("This area is about"), "within the cap, the budget's own")
    }

    @Test
    fun theSentenceOffersLessDetailOnlyWhereItHelps() {
        assertTrue(lessDetailHelps(area))
        assertFalse(lessDetailHelps(area.withZooms(8, 13)))
        assertTrue(lessDetailHelps(area.withZooms(8, 14, 13))) // medium: vector to z14, so less detail cuts it
        val less = plan(4_000, direct = true, a = area.withZooms(8, 13))
        assertEquals(
            "This area needs about 4,000 map tiles, and the public map source allows 3,000 a download. Make the area smaller, or set a server in Settings › Sources.",
            less.refusal,
        )
        assertNull(capRefusalLine(3_000, true))
        assertEquals(999, roundTiles(999))
        assertEquals(4_700, roundTiles(4_601))
        assertEquals(4_600, roundTiles(4_600))
        for (s in listOf(capRefusalLine(10_000, true)!!, capRefusalLine(10_000, false)!!, CAP_STOPPED, OVER_CAP_ROW, io.github.jasondorjeshort.dystopo.app.firstrun.DOWNLOAD_CAP_HELP)) {
            assertFalse("OpenFreeMap" in s || "OpenStreetMap" in s, "no product named: $s")
            assertTrue("server" in s, "a server offered: $s")
        }
        assertTrue("kept" in CAP_STOPPED && "50 MB" in CAP_STOPPED)
    }

    @Test
    fun aDownloadStoppedAt50MbKeepsItsTilesAndIsNotResumedByItself() {
        assertFalse(AreaLimit.directBytesReached(AreaLimit.DIRECT_MAX_BYTES - 1))
        assertTrue(AreaLimit.directBytesReached(AreaLimit.DIRECT_MAX_BYTES))
        // What the run's end writes when it stopped at the cap: partial, the sentence, the resumes stopped (AreaDownload.runJob).
        val stopped = record("a0001", "partial", AutoResume(stopped = AutoResume.STOP_CAP), error = CAP_STOPPED)
        assertNull(resumeLine(stopped, 0L), "no \"Continues by itself\"")
        assertEquals("cap", decide(listOf(stopped), null, 0L, 2048, userPresent = true).unfinished.single()["why"])
        assertNull(decide(listOf(stopped), null, 0L, 2048, userPresent = true).start)
        assertTrue(failedThrough(emptyMap(), null).isEmpty())
        val src = File("src/main/kotlin/io/github/jasondorjeshort/dystopo/app/areas/AreaDownload.kt").readText()
        assertTrue("j.capStopped -> CAP_STOPPED" in src, "the run's end says the sentence")
        assertTrue("|| j.capStopped -> \"partial\"" in src, "a run stopped by the cap is partial, its tiles kept")
        assertTrue("if (j.capStopped) return false" in src, "a tile queued past the cap stays owed for the cursor")
    }

    @Test
    fun aJobOverTheCapIsNotResumedAndOneWithinItIs() {
        val over = record("a0002", "partial", AutoResume(stopped = AutoResume.STOP_CAP))
        val within = record("a0003", "partial", null, createdAt = 2L)
        val d = decide(listOf(over, within), null, 0L, 2048, userPresent = true, direct = true)
        assertEquals(listOf("cap", "ready"), d.unfinished.map { it["why"] })
        assertEquals("a0003", d.start)
        // With a server set the cap is not the server's: the record goes on.
        assertEquals("a0002", decide(listOf(over), null, 0L, 2048, userPresent = true, direct = false).start)
        // A start of a record from before the cap (no stop recorded) is refused by AreaDownload.start, which records the stop.
        val src = File("src/main/kotlin/io/github/jasondorjeshort/dystopo/app/areas/AreaDownload.kt").readText()
        val start = src.substring(src.indexOf("fun start(area: AreaOfInterest"), src.indexOf("private class Refused"))
        assertTrue("stopped = AutoResume.STOP_CAP" in start && "throw CapRefused(why)" in start, "every start checks the cap")
        assertTrue(start.indexOf("throw CapRefused(why)") < start.indexOf("Areas.put("), "nothing is recorded or fetched before")
    }

    @Test
    fun thePackPathCountsItsVectorTiles() {
        val box = PackBounds(-121.86, 46.78, -121.66, 46.93) // Rainier's public box
        val v = RegionDownload.vectorTileCount(box, 8..14)
        assertTrue(v in 1..AreaLimit.DIRECT_MAX_TILES.toInt(), "$v")
        assertEquals(0, RegionDownload.vectorTileCount(box, 15..15))
        // A 1 degree box at Rainier is over the cap at z8 to z14, a quarter degree within it.
        assertTrue(AreaLimit.overDirectCap(RegionDownload.vectorTileCount(PackBounds(-122.26, 46.35, -121.26, 47.35), 8..14).toLong()))
        assertFalse(AreaLimit.overDirectCap(RegionDownload.vectorTileCount(PackBounds(-121.885, 46.725, -121.635, 46.975), 8..14).toLong()))
    }

    @Test
    fun aCountryOverTheCapIsGreyedWithItsSentence() {
        val less = CountryMeasure(30L shl 20, 9_000, false, vectorTiles = 4_500)
        val e = CountrySizes.Entry(less, null, "a1", null)
        val direct = countryRow(e, 0, 0, 1_000_000, direct = true)
        assertEquals(RowState.OVER_CAP, direct.state)
        assertTrue(direct.greyed)
        assertEquals(OVER_CAP_ROW, direct.sentence)
        assertEquals(RowState.FITS, countryRow(e, 0, 0, 1_000_000, direct = false).state)
        // Within the cap at less detail, a deeper detail over it is not the row's.
        val small = CountrySizes.Entry(CountryMeasure(1L shl 20, 900, false, vectorTiles = 400), CountryMeasure(5L shl 20, 9_000, false, vectorTiles = 3_500), "a1", "a2", CountryMeasure(3L shl 20, 4_000, false, vectorTiles = 3_500), "a3")
        assertEquals(DETAIL_LESS, countryRow(small, 0, 0, 1_000_000, direct = true).detail)
        assertEquals(DETAIL_FULL, countryRow(small, 0, 0, 1_000_000, direct = false).detail)
        assertEquals(7 * 500L, measureCounts(8..15, 8..14) { 500 }.vectorTiles) // seven vector zooms of 500
    }

    @Test
    fun aCorridorWithNoServerSetStartsOnlyOnTheTap() {
        assertTrue(corridorMayStart(direct = false, tapped = false))
        assertFalse(corridorMayStart(direct = true, tapped = false))
        assertTrue(corridorMayStart(direct = true, tapped = true))
        val offer = corridorStatus(1000, false, false, null, null, null, ContourUnits.METERS, offerBytes = 14L shl 20)
        assertEquals("offer", offer.state)
        assertEquals("Ready to download, 1.00 km each side: about 14 MB", offer.text)
        assertEquals(listOf("keep"), offer.actions) // Download for offline
        // While a reconcile runs the offer stands over "Waiting to download".
        assertEquals("offer", corridorStatus(1000, false, true, null, null, null, ContourUnits.METERS, offerBytes = 1L shl 20).state)
        // Over the cap the plan's refusal is the note.
        assertEquals("refused", corridorStatus(1000, false, false, "This area needs about 4,000 map tiles", null, null, ContourUnits.METERS, offerBytes = null).state)
        // Every path that asks for a corridor reaches the one start through the gate: the app's start, a trip saved or synced,
        // the width and budget settings and Pin call request(), whose reconcile calls make(); Keep offline with the width off
        // calls make() itself; make() checks the gate before anything else.
        val src = File("src/main/kotlin/io/github/jasondorjeshort/dystopo/app/areas/TripCorridors.kt").readText()
        assertEquals(1, Regex("AreaDownload\\.start\\(").findAll(src).count(), "one start")
        val make = src.substring(src.indexOf("private fun make("), src.indexOf("// T369\n    /**\n     * T369: [want]"))
        assertTrue(make.indexOf("corridorMayStart(AreaDownload.direct(), p.id in tapped)") in 0 until make.indexOf("AreaDownload.start("), "the gate first")
        assertTrue(make.indexOf("corridorMayStart(") < make.indexOf("swap.cancelStale"), "nothing of the old corridor is touched before the tap")
        val callers = Regex("\\bmake\\(p, ").findAll(src).count() + Regex("\\bmake\\(p, want").findAll(src).count()
        assertTrue(callers >= 2, "make is reached from reconcile and keep")
        for (path in listOf("fun applyWidth", "fun keep", "fun userPin")) assertNotNull(Regex("${Regex.escape(path)}[\\s\\S]*?request\\(").find(src), path)
    }

    private fun record(id: String, state: String, auto: AutoResume?, createdAt: Long = 1L, error: String? = null) =
        Areas.Record(id = id, name = id, area = area, createdAt = createdAt, state = state, source = "sources", autoResume = auto, error = error)
}
