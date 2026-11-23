package io.github.jasondorjeshort.dystopo.app

import io.github.jasondorjeshort.dystopo.app.firstrun.WorldOverview
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** T352: [PackSet.open] reports each archive it leaves closed and why (`GET /packs` `notOpened`), where it used to drop it silently. */
class PackSetNotOpenedTest {
    private val assets = File(System.getProperty("dystopo.mapAssets") ?: "build/generated/mapAssets")
    private val bundled = File(assets, WorldOverview.ASSET)

    @Test
    fun aShortAndAnUnreadableArchiveAreListedWithTheirReasons() {
        val root = Files.createTempDirectory("not-opened").toFile().apply { deleteOnExit() }
        val dir = File(root, PackSet.PACKS_DIR).apply { mkdirs() }
        bundled.copyTo(File(dir, "good-outdoors.pmtiles"))
        val bytes = bundled.readBytes()
        File(dir, "short-outdoors.pmtiles").writeBytes(bytes.copyOf(bytes.size / 2))
        File(dir, "junk-dem.pmtiles").writeBytes(ByteArray(200) { 7 })
        val packs = PackSet.open(listOf(root))
        try {
            assertEquals(listOf("good"), packs.regions.map { it.name })
            val byPack = packs.notOpened.associateBy { it.pack }
            assertEquals(setOf("short", "junk"), byPack.keys)
            val short = byPack.getValue("short")
            assertEquals("osm", short.archive)
            assertTrue(short.reason.startsWith("short: ${bytes.size / 2} of "), short.reason)
            assertTrue(short.dropped)
            val junk = byPack.getValue("junk")
            assertEquals("dem", junk.archive)
            assertEquals("junk-dem.pmtiles", junk.file)
            assertTrue(junk.reason.isNotBlank() && !junk.reason.startsWith("short"), junk.reason)
            assertEquals(setOf("pack", "archive", "file", "reason", "dropped"), junk.json().keys)
        } finally {
            packs.close()
        }
        val clean = PackSet.open(listOf(Files.createTempDirectory("not-opened-none").toFile().apply { deleteOnExit() }))
        assertTrue(clean.notOpened.isEmpty())
    }
}
