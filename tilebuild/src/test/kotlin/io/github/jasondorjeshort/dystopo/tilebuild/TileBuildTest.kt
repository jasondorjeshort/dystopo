package io.github.jasondorjeshort.dystopo.tilebuild

import com.github.ajalt.clikt.core.UsageError
import com.onthegomap.planetiler.stats.Timers
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TileBuildTest {
    @Test
    fun tempDirIsKeyedByTheFullOutputPath() {
        val cache = File("cache-test/planetiler")
        val a = TileBuild.tempDirFor(cache, File("out/a/region.pmtiles"))
        val b = TileBuild.tempDirFor(cache, File("out/b/region.pmtiles"))
        assertNotEquals(a, b)
        assertTrue(a.name.startsWith("region-"), a.name)
        assertEquals(File(cache, "tmp"), a.parentFile)
        // Same file, spelled differently: same directory.
        assertEquals(a, TileBuild.tempDirFor(cache, File("out/a/../a/region.pmtiles")))
        assertEquals(a, TileBuild.tempDirFor(cache, File("out/a/region.pmtiles").absoluteFile))
    }

    /** Guards the reflective read of Planetiler's package-private `Timers.Stage`; fails if an upgrade breaks it. */
    @Test
    fun stageSecondsReadsPlanetilerTimers() {
        val timers = Timers()
        timers.startTimer("osm_pass1").stop()
        timers.startTimer("archive").stop()
        val times = TileBuild.stageSeconds(timers)
        assertNull(times.error)
        assertEquals(setOf("osm_pass1", "archive"), times.seconds.keys)
        assertTrue(times.seconds.values.all { it >= 0.0 })
    }

    @Test
    fun unknownAreaErrorListsOnlyPublicAreas() {
        val root = Files.createTempDirectory("tilebuild-areas").toFile()
        try {
            File(root, Areas.PUBLIC_FILE).writeText("""{"areas":{"rainier":{"bbox":[-121.9,46.75,-121.6,46.95]}}}""")
            File(root, Areas.LOCAL_FILE).writeText("""{"areas":{"secret-spot":{"bbox":[1.0,2.0,3.0,4.0]}}}""")
            // Local areas still resolve...
            assertContentEquals(doubleArrayOf(1.0, 2.0, 3.0, 4.0), Areas.bbox("secret-spot", root))
            // ...but are never named in the error.
            val e = assertFailsWith<UsageError> { Areas.bbox("nowhere", root) }
            val message = e.message!!
            assertTrue("rainier" in message, message)
            assertFalse("secret-spot" in message, message)
        } finally {
            root.deleteRecursively()
        }
    }
}
