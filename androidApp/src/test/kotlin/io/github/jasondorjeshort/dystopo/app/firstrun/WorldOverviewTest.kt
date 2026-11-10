package io.github.jasondorjeshort.dystopo.app.firstrun

import io.github.jasondorjeshort.dystopo.app.Pack
import io.github.jasondorjeshort.dystopo.app.PackSet
import io.github.jasondorjeshort.dystopo.app.about.AppCredits
import io.github.jasondorjeshort.dystopo.geo.WebMercator
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * T264: the bundled world overview (`bundleMapAssets` puts `tilebuild/world/world-outdoors.pmtiles` in the assets): the file
 * itself, its first-start copy ([WorldOverview.install]) and its place in the pack set (last, outside the pack list).
 */
class WorldOverviewTest {
    private val assets = File(System.getProperty("dystopo.mapAssets") ?: "build/generated/mapAssets")
    private val bundled = File(assets, WorldOverview.ASSET)

    private fun tmpDir(): File = Files.createTempDirectory("world-overview").toFile().apply { deleteOnExit() }

    private fun tile(z: Int, lat: Double, lon: Double) =
        Triple(z, WebMercator.lonToX(lon, z).toInt(), WebMercator.latToY(lat, z).toInt())

    @Test
    fun theBundledFileIsTheNaturalEarthWorldToZ5() {
        assertTrue(bundled.isFile, "$bundled missing")
        assertTrue(bundled.length() < 5_000_000, "the overview grew to ${bundled.length()} bytes: over the 5 MB the research allows bundling")
        Pack("world", bundled).use { p ->
            assertEquals(0, p.header.minZoom)
            assertEquals(5, p.header.maxZoom)
            assertEquals("natural-earth,openmaptiles", p.metadata["dystopo:provenance"])
            assertEquals("outdoors", p.metadata["dystopo:profile"])
            // The Pacific at z3 is a tile of water; Europe at z5 a tile with borders and names.
            val (z, x, y) = tile(3, 0.0, -150.0)
            assertTrue((p.tile(z, x, y)?.size ?: 0) > 0)
            val (z5, x5, y5) = tile(5, 47.0, 8.0)
            assertTrue((p.tile(z5, x5, y5)?.size ?: 0) > 1000)
        }
    }

    @Test
    fun theCopyRunsOnceAVersionAndKeepsTheOldFileOnAFailure() {
        val dir = tmpDir()
        val first = WorldOverview.install(dir, "v1") { ByteArrayInputStream(ByteArray(1000) { 7 }) }
        assertEquals("installed", first["state"])
        assertEquals(1000L, first["bytes"])
        assertEquals(Thread.currentThread().name, first["thread"])
        assertEquals(1000L, WorldOverview.file(dir).length())
        assertFalse(File(dir, PackSet.WORLD_FILE + ".tmp").exists())

        var opened = false
        val again = WorldOverview.install(dir, "v1") { opened = true; ByteArrayInputStream(ByteArray(1)) }
        assertEquals("current", again["state"])
        assertFalse(opened, "a current copy reads nothing from the APK")

        val failed = WorldOverview.install(dir, "v2") { throw IOException("asset gone") }
        assertEquals("failed", failed["state"])
        assertEquals(1000L, WorldOverview.file(dir).length(), "the previous copy stays")
        assertFalse(File(dir, PackSet.WORLD_FILE + ".tmp").exists())

        assertEquals("installed", WorldOverview.install(dir, "v2") { ByteArrayInputStream(ByteArray(10)) }["state"])
        assertEquals(10L, WorldOverview.file(dir).length())
    }

    @Test
    fun theOverviewIsTheLastVectorArchiveAndNoPack() {
        val root = tmpDir()
        bundled.copyTo(File(root, PackSet.WORLD_FILE))
        val packs = PackSet.open(listOf(root))
        try {
            val world = packs.world
            assertTrue(world != null)
            assertTrue(packs.regions.isEmpty(), "no pack list, area row, Storage row or delete sees it")
            assertTrue(packs.archives.isEmpty())
            assertSame(world, packs.osmPacks.last())
            val (z, x, y) = tile(3, 0.0, -150.0)
            assertSame(world, packs.osmFor(z, x, y))
            assertTrue(packs.worldAnswers(z, x, y))
            val (z6, x6, y6) = tile(6, 47.0, 8.0)
            assertNull(packs.osmFor(z6, x6, y6), "z6 and deeper are never the overview's")
            assertFalse(packs.worldAnswers(z6, x6, y6))
            assertEquals(PackSet.WORLD_NAME, packs.packOf(world!!))
            val row = packs.json().single()
            assertEquals(true, row["builtIn"])
            assertEquals(PackSet.WORLD_NAME, row["name"])

            // Credited as Natural Earth (and the schema), beside the bundled country set.
            val credits = AppCredits.compose(packs)
            val ne = credits.sources.single { it.source.id == "natural-earth" }
            assertTrue(AppCredits.BUNDLED_USE in ne.usedBy, ne.usedBy.toString())
            assertTrue(ne.usedBy.size >= 2, ne.usedBy.toString())
            assertTrue(credits.sources.none { it.source.id == "osm" })
        } finally {
            packs.close()
        }
        // Without the file there is no overview.
        assertNull(PackSet.open(listOf(tmpDir())).world)
    }
}
