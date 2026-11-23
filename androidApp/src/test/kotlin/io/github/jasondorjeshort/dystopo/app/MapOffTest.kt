package io.github.jasondorjeshort.dystopo.app

import io.github.jasondorjeshort.dystopo.app.areas.AreaFiles
import io.github.jasondorjeshort.dystopo.app.areas.Areas
import io.github.jasondorjeshort.dystopo.app.areas.OfflineMapList
import io.github.jasondorjeshort.dystopo.app.areas.offPacksIn
import io.github.jasondorjeshort.dystopo.app.firstrun.WorldOverview
import io.github.jasondorjeshort.dystopo.areas.AreaOfInterest
import io.github.jasondorjeshort.dystopo.areas.AreaShape
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T342: a map switched off stays on the phone and nothing reads it: [PackSet.open] leaves it closed (the filter every reader
 * goes through), the state lives on the map's area records ([Areas.Record.off], absent reads on) or, for a map no record names,
 * in the list beside the primary ([PackSet.OFF_FILE]), both read from files so they survive a restart; the list row carries the
 * switch (the map's message: `MapMessageTest`).
 */
class MapOffTest {
    private val assets = File(System.getProperty("dystopo.mapAssets") ?: "build/generated/mapAssets")
    private val bundled = File(assets, WorldOverview.ASSET)
    private val box = AreaOfInterest(AreaShape.Box(-121.95, 46.68, -121.45, 46.98), 8, 14, "Rainier")

    private fun tmpDir(): File = Files.createTempDirectory("map-off").toFile().apply { deleteOnExit() }

    /** A root with two vector-only maps `wide` and `fine` (copies of the bundled overview: any archive that opens will do). */
    private fun rootWithTwoMaps(): File {
        val root = tmpDir()
        val packs = File(root, PackSet.PACKS_DIR).apply { mkdirs() }
        for (n in listOf("wide", "fine")) bundled.copyTo(File(packs, "$n-outdoors.pmtiles"))
        return root
    }

    private fun writeRecords(root: File, vararg records: Areas.Record) {
        val dir = File(root, Areas.DIR).apply { mkdirs() }
        val files = AreaFiles(records.toList())
        File(dir, "areas.json").writeText(files.mainText())
        files.siblingText()?.let { File(dir, "areas2.json").writeText(it) }
    }

    private fun names(p: PackSet) = p.regions.map { it.name }.sorted()

    @Test
    fun anOffMapInTheListIsNotOpenedAndTheListSurvivesAReopen() {
        val roots = listOf(rootWithTwoMaps())
        PackSet.open(roots).let { p -> try { assertEquals(listOf("fine", "wide"), names(p)); assertTrue(p.off.isEmpty()) } finally { p.close() } }
        PackSet.storeOff(roots, "fine", true)
        assertEquals(setOf("fine"), PackSet.storedOff(roots))
        // A new set read from the files, as after a restart: the list is on disk.
        PackSet.open(roots).let { p ->
            try {
                assertEquals(listOf("wide"), names(p))
                assertEquals(listOf("fine"), p.off)
                assertTrue(p.osmPacks.none { p.packOf(it) == "fine" }, "no reader of the set sees it")
                assertTrue(p.json().none { it["name"] == "fine" })
            } finally { p.close() }
        }
        // Still on the phone: the catalog (Storage, the list) has it.
        assertEquals(listOf("fine", "wide"), PackSet.catalog(roots).map { it.name }.sorted())
        PackSet.storeOff(roots, "fine", false)
        assertFalse(File(roots[0], "${PackSet.PACKS_DIR}/${PackSet.OFF_FILE}").exists(), "the empty list goes")
        PackSet.open(roots).let { p -> try { assertEquals(listOf("fine", "wide"), names(p)) } finally { p.close() } }
    }

    @Test
    fun aRecordNamingTheMapCarriesItsState() {
        val root = rootWithTwoMaps()
        val roots = listOf(root)
        val on = Areas.Record(id = "a0001", name = "Rainier wide", area = box, createdAt = 1, state = "done", pack = "wide")
        writeRecords(root, on)
        assertTrue(offPacksIn(File(root, Areas.DIR)).isEmpty())
        writeRecords(root, on.copy(off = true))
        assertEquals(setOf("wide"), offPacksIn(File(root, Areas.DIR)))
        assertEquals(setOf("wide"), PackSet.offNames(roots))
        PackSet.open(roots).let { p -> try { assertEquals(listOf("fine"), names(p)); assertEquals(listOf("wide"), p.off) } finally { p.close() } }
        // Both off: nothing open, both still on the phone.
        PackSet.storeOff(roots, "fine", true)
        PackSet.open(roots).let { p -> try { assertTrue(p.regions.isEmpty()); assertNull(p.primary); assertEquals(listOf("fine", "wide"), p.off.sorted()) } finally { p.close() } }
    }

    @Test
    fun aPrimaryThatIsOffLeavesTheStoredChoiceAlone() {
        val roots = listOf(rootWithTwoMaps())
        PackSet.storeActive(roots, "fine")
        PackSet.storeOff(roots, "fine", true)
        PackSet.open(roots).close()
        assertEquals("fine", PackSet.storedActive(roots))
    }

    @Test
    fun aRecordWithoutTheFieldReadsOnAndTheFieldIsWrittenOnlyWhenSet() {
        val r = Areas.Record(id = "a0002", name = "Rainier", area = box, createdAt = 1, state = "done", pack = "wide")
        assertFalse("off" in r.json())
        assertFalse(Areas.Record.parse(r.json()).off)
        val off = r.copy(off = true)
        assertEquals(true, off.json()["off"])
        assertTrue(Areas.Record.parse(MiniJson.parseObject(MiniJson.encode(off.json()))).off)
    }

    @Test
    fun theRowHasTheSwitchAndNoShowFirstWhileOff() {
        val packs = listOf(OfflineMapList.Pack("wide", 100, usable = true, hasDem = true, hasVector = true), OfflineMapList.Pack("fine", 100, usable = true, hasDem = true, hasVector = true))
        val rows = OfflineMapList.rows(OfflineMapList.Inputs(packs = packs, active = "wide", off = setOf("fine")))
        val fine = rows.single { it.pack == "fine" }
        val wide = rows.single { it.pack == "wide" }
        assertEquals(true, fine.off)
        assertEquals(false, wide.off)
        assertFalse(OfflineMapList.SHOW_FIRST in fine.actions)
        assertEquals(true, fine.json()["off"])
        // A map not on the phone (a download under way) has no switch.
        val job = OfflineMapList.Job("new", "running", "downloading")
        assertNull(OfflineMapList.rows(OfflineMapList.Inputs(jobs = listOf(job))).single().off)
    }

    @Test
    fun anOffMapIsNeverAMapThatDidNotOpen() {
        // A truncated archive is one T352's notOpened reports; switched off it is not opened at all, so not reported.
        val root = rootWithTwoMaps()
        val roots = listOf(root)
        val fine = File(root, "${PackSet.PACKS_DIR}/fine-outdoors.pmtiles")
        fine.writeBytes(fine.readBytes().copyOf((fine.length() / 2).toInt()))
        PackSet.open(roots).let { p -> try { assertEquals(listOf("fine"), p.notOpened.map { it.pack }) } finally { p.close() } }
        PackSet.storeOff(roots, "fine", true)
        PackSet.open(roots).let { p ->
            try {
                assertTrue(p.notOpened.isEmpty(), p.notOpened.map { it.line }.toString())
                assertEquals(listOf("fine"), p.off)
            } finally { p.close() }
        }
    }
}
