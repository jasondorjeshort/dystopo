package io.github.jasondorjeshort.dystopo.packs

import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PackCatalogTest {
    @Test
    fun sha256KnownVectors() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Sha256.hex(ByteArray(0)))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sha256.hex("abc".encodeToByteArray()))
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            Sha256.hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray()),
        )
    }

    @Test
    fun sha256StreamsInAnyChunks() {
        // One million 'a' (FIPS 180-2 test), fed in uneven chunks across block boundaries.
        val chunk = ByteArray(997) { 'a'.code.toByte() }
        val s = Sha256()
        var left = 1_000_000
        var step = 1
        while (left > 0) {
            val n = minOf(left, step, chunk.size)
            s.update(chunk, 0, n)
            left -= n
            step = step * 3 % 997 + 1
        }
        assertEquals("cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0", s.hex())
        // 55, 56 and 64 bytes: the padding edge cases.
        for (n in listOf(55, 56, 63, 64, 65)) {
            val bytes = ByteArray(n) { it.toByte() }
            val split = Sha256().update(bytes, 0, n / 2).update(bytes, n / 2, n - n / 2).hex()
            assertEquals(Sha256.hex(bytes), split, "n=$n")
        }
    }

    private val demSha = "a".repeat(64)
    private val vecSha = "b".repeat(64)

    private fun archive(name: String, type: String, bytes: Long) = PackArchiveInfo(
        fileName = name, bytes = bytes, tileType = type, minZoom = 8, maxZoom = if (type == "mvt") 14 else 13,
        bounds = PackBounds(-121.9, 46.75, -121.6, 46.95), centerZoom = 11, addressedTiles = 10, attribution = "Test credit",
        title = "Rainier", description = null, expectedBytes = bytes,
    )

    private fun entry() = CatalogPack.of(
        "rainier", archive("rainier-dem.pmtiles", "png", 1000), demSha, archive("rainier-outdoors.pmtiles", "mvt", 200), vecSha,
        profile = "outdoors", provenance = listOf("3dep", "osm"), builtAt = "2026-10-09T21:43:34Z", builtAtMs = 1L,
    )

    @Test
    fun combinedHashDependsOnContentNotNames() {
        val h = PackHash.combine(mapOf(PackRole.DEM to demSha, PackRole.VECTOR to vecSha))
        assertTrue(h.startsWith("sha256:") && h.length == 7 + 64)
        // Order of the map does not matter; the DEM line always comes first.
        assertEquals(h, PackHash.combine(linkedMapOf(PackRole.VECTOR to vecSha, PackRole.DEM to demSha.uppercase())))
        assertTrue(h != PackHash.combine(mapOf(PackRole.DEM to demSha)))
        assertTrue(h != PackHash.combine(mapOf(PackRole.DEM to vecSha, PackRole.VECTOR to demSha)))
        assertFailsWith<IllegalArgumentException> { PackHash.combine(mapOf(PackRole.DEM to "xyz")) }
    }

    @Test
    fun hashMatching() {
        assertTrue(PackHash.matches(demSha, "sha256:" + demSha.uppercase()))
        assertFalse(PackHash.matches(demSha, vecSha))
        assertFalse(PackHash.matches(null, demSha))
        assertFalse(PackHash.matches(demSha, null))
        assertFalse(PackHash.matches("short", "short"))
    }

    @Test
    fun entryRoundTripsThroughJson() {
        val e = entry()
        assertEquals(1200, e.bytes)
        assertEquals("2026-10-09", e.builtDate)
        assertEquals("/out/rainier-dem.pmtiles", e.file(PackRole.DEM)!!.url)
        val doc = MiniJson.parseObject(MiniJson.encode(PackCatalog(1, "2026-10-10T00:00:00Z", listOf(e)).json()))
        val back = PackCatalog.parse(doc)
        assertEquals(listOf(e), back.packs)
        assertEquals(e, back.find("rainier"))
    }

    @Test
    fun badEntriesAreSkippedNotFatal() {
        val good = entry().json()
        val tampered = LinkedHashMap(good).apply { put("name", "other"); put("contentHash", "sha256:" + "c".repeat(64)) }
        val noDem = LinkedHashMap(good).apply { put("name", "nodem"); put("files", (good["files"] as List<*>).drop(1)); remove("contentHash") }
        val badName = LinkedHashMap(good).apply { put("name", "../etc") }
        val skipped = ArrayList<String>()
        val noFiles = LinkedHashMap(good).apply { put("name", "empty"); put("files", emptyList<Any>()); remove("contentHash") }
        val c = PackCatalog.parse(mapOf("version" to 1L, "packs" to listOf(good, tampered, noDem, badName, noFiles, "junk")), skipped)
        // T65: a vector-only entry is listed (vectorOnly), with the vector archive's bounds.
        assertEquals(listOf("rainier", "nodem"), c.packs.map { it.name })
        assertEquals(listOf(false, true), c.packs.map { it.vectorOnly })
        assertEquals(c.packs[1].file(PackRole.VECTOR)!!.bounds ?: c.packs[1].bounds, c.packs[1].bounds)
        assertEquals(3, skipped.size, skipped.toString())
        assertTrue(skipped.any { "does not match" in it })
    }

    @Test
    fun statusFollowsTheInstalledHash() {
        val e = entry()
        assertEquals(CatalogStatus.AVAILABLE, CatalogStatus.of(e, null))
        val same = InstalledPack("rainier", mapOf(PackRole.DEM to demSha, PackRole.VECTOR to vecSha))
        assertEquals(CatalogStatus.INSTALLED, CatalogStatus.of(e, same))
        assertEquals(CatalogStatus.DOWNLOADING, CatalogStatus.of(e, same, downloading = true))
        assertEquals(CatalogStatus.CHECKING, CatalogStatus.of(e, InstalledPack("rainier", mapOf(PackRole.DEM to demSha, PackRole.VECTOR to null))))
        val rebuiltDem = InstalledPack("rainier", mapOf(PackRole.DEM to "d".repeat(64), PackRole.VECTOR to vecSha))
        assertEquals(CatalogStatus.UPDATE, CatalogStatus.of(e, rebuiltDem))
        assertEquals(setOf(PackRole.DEM), CatalogStatus.changedRoles(e, rebuiltDem))
        // A terrain-only install of a pack that now has a map archive: update, fetching only the new archive.
        val demOnly = InstalledPack("rainier", mapOf(PackRole.DEM to demSha))
        assertEquals(CatalogStatus.UPDATE, CatalogStatus.of(e, demOnly))
        assertEquals(setOf(PackRole.VECTOR), CatalogStatus.changedRoles(e, demOnly))
        assertEquals(setOf(PackRole.DEM, PackRole.VECTOR), CatalogStatus.changedRoles(e, null))
        assertNull(InstalledPack("x", mapOf(PackRole.DEM to null)).hash)
    }

    @Test
    fun readsTheServerModuleShape() {
        // T52's serverlib PackCatalog: contentHash, center, license, per-file modified; no title or builtAtMs.
        val dem = "1".repeat(64)
        val vec = "2".repeat(64)
        val hash = PackHash.combine(mapOf(PackRole.DEM to dem, PackRole.VECTOR to vec))
        val doc = """{"version":1,"generatedAt":"2026-10-10T10:00:00Z","packs":[{"id":"montblanc","name":"montblanc",
            "bounds":[6.75,45.78,6.97,45.92],"center":[6.86,45.85],"minZoom":8,"maxZoom":14,"profile":"openmaptiles","bytes":300,
            "builtAt":"2026-10-10T05:53:11Z","provenance":["copernicus","osm"],"attribution":"x","license":"y","contentHash":"$hash",
            "files":[{"role":"dem","file":"montblanc-dem.pmtiles","url":"/out/montblanc-dem.pmtiles","bytes":200,"tileType":"png","minZoom":8,"maxZoom":14,
                      "bounds":[6.75,45.78,6.97,45.92],"modified":"2026-10-10T05:53:11Z","sha256":"$dem"},
                     {"role":"outdoors","file":"montblanc-outdoors.pmtiles","url":"/out/montblanc-outdoors.pmtiles","bytes":100,"sha256":"$vec"}]}]}"""
        val p = PackCatalog.parse(MiniJson.parseObject(doc)).find("montblanc")!!
        assertEquals(hash, p.hash)
        assertEquals("openmaptiles", p.profile)
        assertEquals("2026-10-10", p.builtDate)
        assertEquals(6.75, p.bounds!!.minLon)
        assertEquals(vec, p.file(PackRole.VECTOR)!!.sha256)
    }

    @Test
    fun urlsResolveAgainstTheServer() {
        assertEquals("http://10.0.2.2:8833/out/a-dem.pmtiles", PackCatalog.resolveUrl("http://10.0.2.2:8833", "/out/a-dem.pmtiles"))
        assertEquals("http://h/base/out/a.pmtiles", PackCatalog.resolveUrl("http://h/base/", "out/a.pmtiles"))
        assertEquals("http://h/base/out/a.pmtiles", PackCatalog.resolveUrl("http://h/base", "/out/a.pmtiles"))
        assertEquals("https://cdn/x.pmtiles", PackCatalog.resolveUrl("http://h", "https://cdn/x.pmtiles"))
    }

    /** T78: a phone publishes the same document with `publisher` and its own file URLs; a reader keeps the publisher, and a vector-only pack is an entry too. */
    @Test
    fun phoneCatalogKeepsPublisherAndVectorOnlyPacks() {
        val vectorOnly = CatalogPack.of("trail", null, null, archive("trail-outdoors.pmtiles", "mvt", 300), vecSha, "outdoors", emptyList(), null, null, urlPrefix = "/peer/file/")
        assertTrue(vectorOnly.vectorOnly)
        assertEquals("/peer/file/trail-outdoors.pmtiles", vectorOnly.files.single().url)
        assertEquals(PackHash.combine(mapOf(PackRole.VECTOR to vecSha)), vectorOnly.hash)
        val doc = PackCatalog(1, "2026-10-12T00:00:00Z", listOf(entry(), vectorOnly), mapOf("name" to "Dystopo Pixel 6 3f2a", "device" to "3f2a0b1c"))
        val back = PackCatalog.parse(MiniJson.parseObject(MiniJson.encode(doc.json())))
        assertEquals(doc, back)
        assertEquals("Dystopo Pixel 6 3f2a", back.publisher?.get("name"))
        assertEquals("peer://10.0.2.24:8841/peer/file/trail-outdoors.pmtiles", PackCatalog.resolveUrl("peer://10.0.2.24:8841", back.find("trail")!!.files.single().url))
        // A server's catalog has no publisher, and its JSON has no such key.
        assertNull(PackCatalog(1, null, listOf(entry())).json()["publisher"])
        assertFailsWith<IllegalArgumentException> { CatalogPack.of("none", null, null, null, null, null, emptyList(), null, null) }
    }
}
