package io.github.jasondorjeshort.dystopo.packs

import io.github.jasondorjeshort.dystopo.areas.AreaManifest
import io.github.jasondorjeshort.dystopo.areas.AreaOfInterest
import io.github.jasondorjeshort.dystopo.areas.FileItem
import io.github.jasondorjeshort.dystopo.areas.LocalItem
import io.github.jasondorjeshort.dystopo.areas.ManifestItem
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import io.github.jasondorjeshort.dystopo.routing.GraphTileFile
import io.github.jasondorjeshort.dystopo.routing.GraphTiler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T189 (ADR 0049's B): the `derived` list, the manifest's shipped graph, the match rule, and v0.1.14's readers on both. */
class ShippedGraphTest {
    private val demSha = "a".repeat(64)
    private val vecSha = "b".repeat(64)
    private val graphSha = "c".repeat(64)
    private val fp = "v1:1000:20:18:127:900:8-14"
    private val demFp = "v1:5000:40:40:127:700:8-13"

    private fun archive(name: String, type: String, bytes: Long) = PackArchiveInfo(
        fileName = name, bytes = bytes, tileType = type, minZoom = 8, maxZoom = if (type == "mvt") 14 else 13,
        bounds = PackBounds(-121.9, 46.75, -121.6, 46.95), centerZoom = 11, addressedTiles = 10, attribution = "Test credit",
        title = "Rainier", description = null, expectedBytes = bytes,
    )

    private val graph = DerivedFile.graph("rainier-outdoors-graph.idx", "/out/rainier-outdoors-graph.idx", 512_345, graphSha, fp, demFp)

    private fun entry(derived: List<DerivedFile> = listOf(graph)) = CatalogPack.of(
        "rainier", archive("rainier-dem.pmtiles", "png", 1000), demSha, archive("rainier-outdoors.pmtiles", "mvt", 200), vecSha,
        profile = "outdoors", provenance = listOf("3dep", "osm"), builtAt = "2026-10-09T21:43:34Z", builtAtMs = 1L, derived = derived,
    )

    /** The catalog document as a server writes it, through JSON text and back (what goes over the wire). */
    private fun wire(vararg packs: CatalogPack): Map<String, Any?> =
        MiniJson.parseObject(MiniJson.encode(PackCatalog(1, "2026-10-30T00:00:00Z", packs.toList()).json()))

    @Test
    fun derivedIsOutsideFilesBytesAndHash() {
        val with = entry()
        val without = entry(emptyList())
        assertEquals(without.hash, with.hash, "a shipped graph is not part of the pack's identity")
        assertEquals(without.files, with.files)
        assertEquals(without.bytes, with.bytes)
        assertEquals(PackHash.combine(mapOf(PackRole.DEM to demSha, PackRole.VECTOR to vecSha)), with.hash)
        val json = with.json()
        assertEquals(2, (json["files"] as List<*>).size)
        @Suppress("UNCHECKED_CAST")
        val d = (json["derived"] as List<Map<String, Any?>>).single()
        assertEquals("graph", d["kind"])
        assertEquals("rainier-outdoors-graph.idx", d["file"])
        assertEquals("rainier-outdoors-graph.idx.sha256", d["sidecar"])
        assertEquals("DYSG", d["format"])
        assertEquals(GraphTileFile.VERSION, d["version"])
        assertEquals(mapOf("tileZoom" to GraphTiler.ZOOM, "fingerprint" to fp, "demFingerprint" to demFp), d["key"])
        assertFalse("derived" in without.json(), "no `derived` key when nothing is shipped")
    }

    @Test
    fun catalogRoundTripKeepsDerived() {
        val back = PackCatalog.parse(wire(entry()))
        val p = back.packs.single()
        assertEquals(listOf(graph), p.derived)
        assertEquals(graph, p.graph)
        assertEquals(entry().hash, p.hash)
    }

    @Test
    fun unusableDerivedEntriesAreDroppedNeverThePack() {
        val doc = wire(entry())
        @Suppress("UNCHECKED_CAST")
        val pack = ((doc["packs"] as List<*>).single() as Map<String, Any?>).toMutableMap()
        pack["derived"] = listOf(
            mapOf("kind" to "graph", "file" to "../etc/passwd", "bytes" to 1, "sha256" to graphSha),
            mapOf("kind" to "graph", "file" to "x.idx", "bytes" to 1, "sha256" to "zz"),
            "not an object",
            mapOf("kind" to "search", "file" to "rainier-outdoors.search", "bytes" to 9, "sha256" to graphSha, "format" to "DYSS", "version" to 1, "key" to mapOf("x" to 1)),
            graph.json(),
        )
        val skipped = ArrayList<String>()
        val p = PackCatalog.parse(mapOf("version" to 1, "packs" to listOf(pack)), skipped).packs.single()
        assertTrue(skipped.isEmpty(), skipped.toString())
        assertEquals(listOf("search", "graph"), p.derived.map { it.kind })
        assertEquals(graph, p.graph, "an unknown kind is kept but never taken for the graph")
        assertNull(PackCatalog.parse(mapOf("packs" to listOf(pack + ("derived" to "nonsense")))).packs.single().graph)
    }

    @Test
    fun matchRule() {
        assertNull(ShippedGraph.mismatch(graph, fp, demFp))
        assertEquals(graph, ShippedGraph.pick(listOf(graph), fp, demFp))
        assertTrue(ShippedGraph.mismatch(graph, "v1:other", demFp)!!.startsWith("fingerprint mismatch"))
        assertTrue(ShippedGraph.mismatch(graph, fp, "")!!.startsWith("elevation fingerprint mismatch"), "a phone with no DEM does not take a graph built with one")
        assertEquals("routing graph version 7, expected 6", ShippedGraph.mismatch(graph.copy(version = 7), fp, demFp))
        assertTrue(ShippedGraph.mismatch(graph.copy(key = graph.key + ("tileZoom" to 12)), fp, demFp)!!.startsWith("graph tile zoom 12"))
        assertTrue(ShippedGraph.mismatch(graph.copy(format = "OTHER"), fp, demFp)!!.startsWith("format"))
        assertNull(ShippedGraph.pick(listOf(graph.copy(version = 7)), fp, demFp))
        assertNull(ShippedGraph.readable(listOf(graph.copy(version = 7))), "a version this build does not read is never fetched")
        // A graph built without a DEM matches a pack without one.
        val noDem = DerivedFile.graph("v-outdoors-graph.idx", "/out/v-outdoors-graph.idx", 9, graphSha, fp, "")
        assertNull(ShippedGraph.mismatch(noDem, fp, ""))
    }

    @Test
    fun v014CatalogReaderReadsTheNewDocumentUnchanged() {
        val doc = wire(entry(), entry(emptyList()).copy(id = "plain", name = "plain"))
        val skipped = ArrayList<String>()
        val old = V014Readers.parseCatalog(doc, skipped)
        val now = PackCatalog.parse(doc)
        assertTrue(skipped.isEmpty(), "v0.1.14 skips no entry for its `derived` list: $skipped")
        assertEquals(2, old.packs.size)
        // The old reader sees exactly the pack it saw before T189: same files, hash and bytes; it builds the graph itself.
        assertEquals(now.packs.map { it.copy(derived = emptyList()) }, old.packs)
        assertTrue(old.packs.all { it.derived.isEmpty() })
        assertEquals(entry().hash, old.find("rainier")!!.hash)
    }

    private val area = AreaOfInterest.parse(mapOf("kind" to "box", "bbox" to listOf(-121.9, 46.75, -121.6, 46.95), "minZoom" to 8, "maxZoom" to 13))

    private fun manifest(graphItem: LocalItem) = AreaManifest(
        area.id, "rainier", area, AreaManifest.STATUS_READY,
        listOf(
            FileItem("dem", "rainier", "rainier-dem.pmtiles", "/out/rainier-dem.pmtiles", 1000, demSha, "png", 8, 13, listOf(-121.9, 46.75, -121.6, 46.95)),
            FileItem("outdoors", "rainier", "rainier-outdoors.pmtiles", "/out/rainier-outdoors.pmtiles", 200, vecSha, "mvt", 8, 14, listOf(-121.9, 46.75, -121.6, 46.95)),
            graphItem,
            LocalItem(ManifestItem.KIND_SEARCH, "rainier-outdoors.pmtiles", true, "the phone builds the place search index"),
        ),
        generatedAt = "2026-10-30T00:00:00Z",
    )

    @Test
    fun manifestGraphItemCarriesTheShippedGraph() {
        val item = LocalItem(ManifestItem.KIND_GRAPH, "rainier-outdoors.pmtiles", true, "the server built it", graph)
        val json = item.json()
        assertEquals(false, json["onDevice"], "a shipped graph is fetched, not made on the phone")
        assertEquals("graph", json["kind"])
        assertEquals("/out/rainier-outdoors-graph.idx", json["url"])
        assertEquals(512_345L, json["bytes"])
        assertEquals(graphSha, json["sha256"])
        assertEquals(6, json["version"])
        assertEquals(true, LocalItem(ManifestItem.KIND_GRAPH, "x", true, null).json()["onDevice"])
        val m = manifest(item)
        assertEquals(1200L + 512_345L, m.bytes, "the shipped graph's bytes count in the area's size")
        assertEquals(1, m.version, "the manifest's version does not move for the graph's fields")
        val back = AreaManifest.parse(MiniJson.encode(m.json()))
        assertEquals(m.json(), back.json())
        assertEquals(graph, back.items.filterIsInstance<LocalItem>().first { it.kind == "graph" }.shipped)
        // Without a url the item is the phone's own build, as before.
        assertNull(LocalItem.shippedOf(mapOf("kind" to "graph", "from" to "x", "sha256" to graphSha)))
    }

    @Test
    fun v014ManifestReaderReadsTheNewManifestUnchanged() {
        val m = manifest(LocalItem(ManifestItem.KIND_GRAPH, "rainier-outdoors.pmtiles", true, "the server built it", graph))
        val doc = MiniJson.parseObject(MiniJson.encode(m.json()))
        val old = V014Readers.parseManifest(doc)
        val g = old.items.filterIsInstance<LocalItem>().first { it.kind == "graph" }
        assertNull(g.shipped)
        assertEquals("rainier-outdoors.pmtiles", g.from)
        assertTrue(g.available)
        assertEquals(1200L, old.bytes, "v0.1.14 counts the archives only, and builds its graph on the phone")
        assertEquals(2, old.packs.size)
        assertNotNull(old.items.firstOrNull { it.kind == "search" })
    }
}
