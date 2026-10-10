package io.github.jasondorjeshort.dystopo.server

import io.github.jasondorjeshort.dystopo.geo.BoundingBox
import io.github.jasondorjeshort.dystopo.geo.TileId
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PackCatalogTest {
    private val dir: File = Files.createTempDirectory("catalog").toFile()

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    /** A tiny DEM pack: [tiles] fake PNG payloads at z8 inside [box]. */
    private fun demPack(name: String, box: BoundingBox = BoundingBox(-121.9, 46.7, -121.6, 46.95), seed: Int = 1): File {
        val file = File(dir, "$name-dem.pmtiles")
        DemPmtilesWriter(file).use { w ->
            w.add(TileId(8, 42, 90), ByteArray(64) { (it * seed).toByte() })
            w.finish(
                TileSetMetadata(
                    name = "$name-dem", tileSize = 256, bounds = TileSets.geoJsonBounds(box), minzoom = 8, maxzoom = 8,
                    source = "best", attribution = "test", attributionHtml = "test", provenance = listOf("copernicus", "egm2008"),
                ),
                box, 8, 8,
            )
        }
        return file
    }

    @Test
    fun listsDemPacksWithHashesCachedInSidecars() {
        val dem = demPack("alpha")
        demPack("beta", BoundingBox(6.8, 45.8, 6.9, 45.9), seed = 3)
        File(dir, "gamma-outdoors.pmtiles").writeBytes(ByteArray(10)) // vector archive without a DEM: not listed
        File(dir, "delta-dem.pmtiles.building").writeBytes(ByteArray(10)) // still being written: not listed
        val catalog = PackCatalog(dir)
        val packs = catalog.packs()
        assertEquals(listOf("alpha", "beta"), packs.map { it["id"] })
        val alpha = packs.first()
        assertEquals("alpha", alpha["name"])
        assertEquals(8, alpha["minZoom"])
        assertNull(alpha["profile"])
        assertEquals(dem.length(), alpha["bytes"])
        assertEquals(listOf("copernicus", "egm2008"), alpha["provenance"])
        val bounds = alpha["bounds"] as List<*>
        assertEquals(-121.9, bounds[0] as Double, 1e-6)
        val files = alpha["files"] as List<*>
        val f = files.single() as Map<*, *>
        assertEquals("/out/alpha-dem.pmtiles", f["url"])
        assertEquals(PackCatalog.hashFile(dem), f["sha256"])
        assertEquals(PackCatalog.packHash(listOf("dem" to PackCatalog.hashFile(dem))), alpha["contentHash"])
        assertTrue((alpha["contentHash"] as String).startsWith("sha256:"))
        assertTrue(PackCatalog.sidecar(dem).readText().startsWith(PackCatalog.hashFile(dem) + "  alpha-dem.pmtiles"))
        assertEquals(2L, catalog.hashesComputed)

        // A second listing reads the sidecars.
        catalog.packs()
        assertEquals(2L, catalog.hashesComputed)
        val fresh = PackCatalog(dir) // a restarted server: nothing to hash again
        fresh.packs()
        assertEquals(0L, fresh.hashesComputed)
    }

    @Test
    fun aRebuiltPackGetsANewHash() {
        val dem = demPack("alpha")
        val catalog = PackCatalog(dir)
        val before = catalog.packs().single()["contentHash"]
        Thread.sleep(20)
        demPack("alpha", seed = 7)
        // Make sure the rebuilt file is newer than the sidecar even on a coarse-mtime file system.
        dem.setLastModified(PackCatalog.sidecar(dem).lastModified() + 2000)
        val after = catalog.packs().single()["contentHash"]
        assertNotEquals(before, after)
        assertEquals(PackCatalog.hashFile(dem), (catalog.packs().single()["files"] as List<*>).let { (it.single() as Map<*, *>)["sha256"] })
    }

    @Test
    fun vectorProfile() {
        assertEquals("outdoors", PackCatalog.profileOf(mapOf("dystopo:profile" to "outdoors")))
        assertEquals("openmaptiles", PackCatalog.profileOf(mapOf("dystopo:profile" to "openmaptiles", "vector_layers" to listOf(mapOf("id" to "outdoor_poi")))))
        // Archives from before dystopo:profile: the outdoors-only layer decides.
        assertEquals("outdoors", PackCatalog.profileOf(mapOf("vector_layers" to listOf(mapOf("id" to "water"), mapOf("id" to "outdoor_poi")))))
        assertEquals("openmaptiles", PackCatalog.profileOf(mapOf("vector_layers" to listOf(mapOf("id" to "water")))))
    }

    @Test
    fun packHashIsOrderIndependentAndRoleSensitive() {
        val a = PackCatalog.packHash(listOf("dem" to "aa", "outdoors" to "bb"))
        assertEquals(a, PackCatalog.packHash(listOf("outdoors" to "bb", "dem" to "aa")))
        assertNotEquals(a, PackCatalog.packHash(listOf("dem" to "bb", "outdoors" to "aa")))
    }
}
