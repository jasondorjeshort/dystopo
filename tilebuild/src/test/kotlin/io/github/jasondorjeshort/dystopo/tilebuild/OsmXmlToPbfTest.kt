package io.github.jasondorjeshort.dystopo.tilebuild

import com.onthegomap.planetiler.reader.osm.OsmElement
import com.onthegomap.planetiler.reader.osm.OsmInputFile
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** OSM XML (as Overpass answers) to PBF, read back with Planetiler's own reader. Synthetic data only. */
class OsmXmlToPbfTest {
    private val dir: File = Files.createTempDirectory("osm-xml-test").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    // Out of id order and with a duplicate node, as Overpass unions can print.
    private val xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <osm version="0.6" generator="Overpass API">
          <note>The data included in this document is from www.openstreetmap.org.</note>
          <meta osm_base="2026-10-10T00:00:00Z"/>
          <bounds minlat="46.70" minlon="-121.80" maxlat="46.80" maxlon="-121.70"/>
          <node id="3" lat="46.7600000" lon="-121.7300000"/>
          <node id="1" lat="46.7512345" lon="-121.7512345">
            <tag k="natural" v="peak"/>
            <tag k="name" v="Synthetic Péak"/>
            <tag k="ele" v="2100"/>
          </node>
          <node id="2" lat="46.7550000" lon="-121.7400000"/>
          <node id="3" lat="46.7600000" lon="-121.7300000"/>
          <way id="20">
            <nd ref="1"/><nd ref="2"/><nd ref="3"/>
            <tag k="highway" v="path"/>
            <tag k="sac_scale" v="alpine_hiking"/>
          </way>
          <way id="10">
            <nd ref="3"/><nd ref="2"/>
            <tag k="natural" v="cliff"/>
          </way>
          <relation id="100">
            <member type="way" ref="20" role=""/>
            <member type="node" ref="1" role="summit"/>
            <member type="relation" ref="999" role="sub"/>
            <tag k="type" v="route"/>
            <tag k="route" v="hiking"/>
          </relation>
        </osm>
    """.trimIndent()

    private fun readBack(pbf: File): List<OsmElement> {
        val all = ArrayList<OsmElement>()
        OsmInputFile(pbf.toPath()).get().use { src -> src.forEachBlock { block -> block.decodeElements().forEach { all += it } } }
        return all
    }

    @Test
    fun convertsNodesWaysAndRelationsThatPlanetilerReads() {
        val xmlFile = File(dir, "box.osm").apply { writeText(xml) }
        val pbf = File(dir, "box.osm.pbf")
        val result = OsmXmlToPbf.convert(xmlFile, pbf, doubleArrayOf(-121.8, 46.7, -121.7, 46.8))
        assertTrue(result.converted)
        assertEquals(3, result.nodes, "duplicate node dropped")
        assertEquals(2, result.ways)
        assertEquals(1, result.relations)

        val elements = readBack(pbf)
        val nodes = elements.filterIsInstance<OsmElement.Node>()
        val ways = elements.filterIsInstance<OsmElement.Way>()
        val relations = elements.filterIsInstance<OsmElement.Relation>()
        assertEquals(listOf(1L, 2L, 3L), nodes.map { it.id() }, "nodes sorted by id")
        assertEquals(listOf(10L, 20L), ways.map { it.id() }, "ways sorted by id")
        val peak = nodes.first()
        assertEquals(46.7512345, peak.lat(), 1e-7)
        assertEquals(-121.7512345, peak.lon(), 1e-7)
        assertEquals("Synthetic Péak", peak.getString("name"))
        assertEquals("2100", peak.getString("ele"))
        assertTrue(nodes[1].tags().isEmpty())
        val trail = ways[1]
        assertEquals("alpine_hiking", trail.getString("sac_scale"))
        assertEquals(listOf(1L, 2L, 3L), (0 until trail.nodes().size()).map { trail.nodes().get(it) })
        val rel = relations.single()
        assertEquals("hiking", rel.getString("route"))
        assertEquals(
            listOf(Triple(OsmElement.Type.WAY, 20L, ""), Triple(OsmElement.Type.NODE, 1L, "summit"), Triple(OsmElement.Type.RELATION, 999L, "sub")),
            rel.members().map { Triple(it.type(), it.ref(), it.role()) },
        )
        val header = OsmInputFile(pbf.toPath()).header
        assertEquals(-121.8, header.bounds().minX, 1e-9)
        assertEquals(46.8, header.bounds().maxY, 1e-9)
    }

    @Test
    fun reusesAnUpToDatePbf() {
        val xmlFile = File(dir, "box.osm").apply { writeText(xml) }
        val pbf = File(dir, "box.osm.pbf")
        OsmXmlToPbf.convert(xmlFile, pbf, null)
        pbf.setLastModified(xmlFile.lastModified() + 1000)
        val again = OsmXmlToPbf.convert(xmlFile, pbf, null)
        assertFalse(again.converted)
    }

    @Test
    fun manyElementsSpanSeveralBlocks() {
        val n = OsmXmlToPbf.BLOCK_SIZE * 2 + 17
        val text = buildString {
            append("<osm version=\"0.6\">")
            for (i in 1..n) append("<node id=\"$i\" lat=\"${46.0 + i * 1e-6}\" lon=\"${-121.0 - i * 1e-6}\"/>")
            append("<way id=\"1\">")
            for (i in 1..n) append("<nd ref=\"$i\"/>")
            append("<tag k=\"highway\" v=\"track\"/></way></osm>")
        }
        val xmlFile = File(dir, "many.osm").apply { writeText(text) }
        val pbf = File(dir, "many.osm.pbf")
        OsmXmlToPbf.convert(xmlFile, pbf, null)
        val elements = readBack(pbf)
        val nodes = elements.filterIsInstance<OsmElement.Node>()
        assertEquals(n, nodes.size)
        assertEquals((1L..n).toList(), nodes.map { it.id() })
        assertEquals(46.0 + n * 1e-6, nodes.last().lat(), 1e-7)
        assertEquals(n, elements.filterIsInstance<OsmElement.Way>().single().nodes().size())
    }

    @Test
    fun refusesAnErrorRemarkAndNonOsmXml() {
        val timedOut = """<osm version="0.6"><node id="1" lat="1" lon="1"/><remark> runtime error: Query timed out in "query" at line 1 after 91 seconds. </remark></osm>"""
        val e = assertFailsWith<OsmXmlToPbf.BadOsmXml> { OsmXmlToPbf.read(timedOut.byteInputStream()) }
        assertTrue("timed out" in e.message!!, e.message)
        assertFailsWith<OsmXmlToPbf.BadOsmXml> { OsmXmlToPbf.read("<html><body>busy</body></html>".byteInputStream()) }
        // A remark without an error is informational.
        val ok = OsmXmlToPbf.read("""<osm version="0.6"><remark>note</remark><node id="5" lat="1" lon="2"/></osm>""".byteInputStream())
        assertEquals(5L, ok.nodes.single().id)
    }

    @Test
    fun extractsResolveOsmXmlBesideItsPbf() {
        val input = Extracts.resolve(File(dir, "overpass-abc.osm").path, File(dir, "osm"))
        assertEquals(ExtractKind.LOCAL_XML, input.kind)
        assertEquals("overpass-abc", input.label)
        assertEquals(File(dir, "overpass-abc.osm.pbf").absolutePath, input.path.absolutePath)
        assertEquals(File(dir, "overpass-abc.osm").path, input.xml!!.path)
    }

    @Test
    fun metadataNamesProfileAndExtraSources() {
        val meta = Attribution.extraMetadata(
            "overpass", doubleArrayOf(-121.8, 46.7, -121.7, 46.8),
            Attribution.OUTDOORS_SOURCE_IDS + "overpass", Attribution.OUTDOORS_SCHEMA, ProfileKind.OUTDOORS.cliName,
        )
        assertEquals("outdoors", meta["dystopo:profile"])
        assertTrue("overpass" in meta["dystopo:provenance"]!!.split(","), meta["dystopo:provenance"])
        assertEquals("openmaptiles", Attribution.extraMetadata("x", null)["dystopo:profile"])
        assertTrue("natural-earth" !in Profiles.outdoorsSourceIds(false))
        assertTrue("natural-earth" in Profiles.outdoorsSourceIds(true))
    }
}
