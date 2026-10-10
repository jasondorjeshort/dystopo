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

    /** A tiny vector archive (one fake MVT tile) at [fileName]. */
    private fun vectorArchive(fileName: String): File {
        val file = File(dir, fileName)
        val w = io.github.jasondorjeshort.dystopo.pmtiles.PmtilesWriter(io.github.jasondorjeshort.dystopo.pmtiles.TileType.MVT)
        w.addTile(8, 42, 90, ByteArray(32) { it.toByte() })
        file.outputStream().use { os ->
            w.finish({ os.write(it) }, """{"vector_layers":[{"id":"water"}]}""", -121.9, 46.7, -121.6, 46.95, minZoom = 8, maxZoom = 8)
        }
        return file
    }

    @Test
    fun singleArchivesAreSingleFilePacks() {
        demPack("alpha")
        vectorArchive("alpha-outdoors.pmtiles")
        val shadowed = vectorArchive("alpha-osm.pmtiles") // -outdoors wins the pair; -osm is listed alone
        vectorArchive("gamma-outdoors.pmtiles") // a vector archive without its DEM
        val plain = File(dir, "liechtenstein.pmtiles").also { vectorArchive(it.name) } // tilebuild output, no role suffix
        File(dir, "broken.pmtiles").writeBytes(ByteArray(10)) // not a PMTiles archive: skipped
        val packs = PackCatalog(dir).packs().associateBy { it["id"] }
        assertEquals(setOf("alpha", "alpha-osm", "gamma-outdoors", "liechtenstein"), packs.keys)
        assertEquals(false, packs["alpha"]!!["single"])
        assertEquals(2, (packs["alpha"]!!["files"] as List<*>).size)
        val single = packs["alpha-osm"]!!
        assertEquals(true, single["single"])
        val f = (single["files"] as List<*>).single() as Map<*, *>
        assertEquals("outdoors", f["role"])
        assertEquals("alpha-osm.pmtiles", f["file"])
        assertEquals(PackCatalog.hashFile(shadowed), f["sha256"])
        assertEquals(PackCatalog.packHash(listOf("outdoors" to PackCatalog.hashFile(shadowed))), single["contentHash"])
        assertEquals("openmaptiles", single["profile"])
        assertEquals(8, single["minZoom"])
        assertEquals(PackCatalog.hashFile(plain), ((packs["liechtenstein"]!!["files"] as List<*>).single() as Map<*, *>)["sha256"])
    }

    @Test
    fun refreshFindsBytesChangedInPlace() {
        val dem = demPack("alpha")
        val catalog = PackCatalog(dir)
        val before = catalog.packs().single()["contentHash"]
        // Flip a byte, keeping the size and the modification time: the sidecar still looks current.
        val modified = dem.lastModified()
        val bytes = dem.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x55).toByte()
        dem.writeBytes(bytes)
        dem.setLastModified(modified)
        assertEquals(before, catalog.packs().single()["contentHash"])
        val (status, body) = catalog.route("/catalog/refresh", "POST")!!
        assertEquals(200, status)
        val refresh = body["refresh"] as Map<*, *>
        assertEquals(listOf("alpha-dem.pmtiles"), refresh["changed"])
        assertEquals(1, refresh["files"])
        val after = (body["packs"] as List<*>).single() as Map<*, *>
        assertNotEquals(before, after["contentHash"])
        assertEquals(PackCatalog.hashFile(dem), PackCatalog.sidecar(dem).readText().substringBefore(' '))
        assertEquals(dem.lastModified(), PackCatalog.sidecar(dem).lastModified())
        assertEquals(405, catalog.route("/catalog/refresh", "GET")!!.first)
        assertNull(catalog.route("/elsewhere", "GET"))
    }

    @Test
    fun aSidecarWithAnotherTimeIsStale() {
        val dem = demPack("alpha")
        PackCatalog(dir).packs()
        // A sidecar newer than its file (the pre-T58 rule accepted it) no longer counts.
        PackCatalog.sidecar(dem).writeText("0".repeat(64) + "  alpha-dem.pmtiles\n")
        PackCatalog.sidecar(dem).setLastModified(dem.lastModified() + 60_000)
        val fresh = PackCatalog(dir)
        val f = (fresh.packs().single()["files"] as List<*>).single() as Map<*, *>
        assertEquals(PackCatalog.hashFile(dem), f["sha256"])
        assertEquals(1L, fresh.hashesComputed)
    }

    @Test
    fun warmStateGoesFromColdToReady() {
        demPack("alpha")
        demPack("beta", seed = 2)
        val catalog = PackCatalog(dir)
        assertEquals("cold", catalog.warmState()["state"])
        catalog.warm().join()
        val s = catalog.warmState()
        assertEquals("ready", s["state"])
        assertEquals(2, s["files"])
        assertEquals(2, s["hashed"])
        assertEquals(2L, s["hashesComputed"])
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
