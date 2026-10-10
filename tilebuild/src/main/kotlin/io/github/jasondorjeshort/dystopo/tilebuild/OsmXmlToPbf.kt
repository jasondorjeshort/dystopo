package io.github.jasondorjeshort.dystopo.tilebuild

import com.google.protobuf.ByteString
import crosby.binary.Fileformat
import crosby.binary.Osmformat
import kotlinx.serialization.Serializable
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.Deflater
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import kotlin.math.roundToLong

/** What [OsmXmlToPbf.convert] read and wrote. */
@Serializable
data class XmlConversion(
    val xml: String,
    val xmlBytes: Long,
    val pbf: String,
    val pbfBytes: Long,
    val nodes: Int,
    val ways: Int,
    val relations: Int,
    /** False when an up-to-date `.osm.pbf` from an earlier run was reused. */
    val converted: Boolean,
    val seconds: Double,
)

/**
 * OSM XML (`.osm`, as the Overpass API answers `[out:xml]`) to `.osm.pbf`, because Planetiler only reads PBF.
 *
 * Written against the PBF format spec (wiki.openstreetmap.org/wiki/PBF_Format) with the protobuf classes
 * Planetiler itself bundles (`crosby.binary.*`, generated from the MIT-licensed `osmformat.proto` and
 * `fileformat.proto`), so no new dependency: an `OSMHeader` blob, then `OSMData` blobs of at most
 * [BLOCK_SIZE] entities each, zlib-compressed. Nodes go out as `DenseNodes`, then ways, then relations,
 * each sorted by id, which is what Planetiler's node map (`sortedtable`) needs. Element metadata
 * (version, user, timestamp) is not carried: the tiles don't use it.
 *
 * The whole extract is held in memory; it is meant for the small boxes Overpass serves (megabytes), not
 * for Geofabrik regions.
 */
object OsmXmlToPbf {
    const val BLOCK_SIZE = 8000
    private const val GRANULARITY = 100 // nanodegrees per unit: coordinates are stored as degrees * 1e7

    class Node(val id: Long, val lat: Double, val lon: Double, val tags: List<Pair<String, String>>)
    class Way(val id: Long, val refs: LongArray, val tags: List<Pair<String, String>>)
    class Member(val type: Osmformat.Relation.MemberType, val ref: Long, val role: String)
    class Relation(val id: Long, val members: List<Member>, val tags: List<Pair<String, String>>)
    class Extract(val nodes: List<Node>, val ways: List<Way>, val relations: List<Relation>)

    /** Thrown for input that is not OSM XML, or an Overpass answer that carries a runtime error remark. */
    class BadOsmXml(message: String) : IllegalArgumentException(message)

    /**
     * Converts [xml] to [pbf] unless [pbf] already exists and is newer. The file is written next to its
     * final name and moved into place, so a crash never leaves a truncated `.pbf` that a later run reuses.
     */
    fun convert(xml: File, pbf: File, bbox: DoubleArray?): XmlConversion {
        val started = System.nanoTime()
        val upToDate = pbf.isFile && pbf.lastModified() >= xml.lastModified() && pbf.length() > 0
        var counts = Triple(0, 0, 0)
        if (!upToDate) {
            val extract = BufferedInputStream(xml.inputStream(), 1 shl 16).use { read(it) }
            pbf.absoluteFile.parentFile?.mkdirs()
            val tmp = File(pbf.absoluteFile.parentFile, pbf.name + ".${ProcessHandle.current().pid()}.part")
            try {
                BufferedOutputStream(tmp.outputStream(), 1 shl 16).use { write(extract, it, bbox) }
                Files.move(tmp.toPath(), pbf.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } finally {
                tmp.delete()
            }
            counts = Triple(extract.nodes.size, extract.ways.size, extract.relations.size)
        }
        return XmlConversion(
            xml = xml.path, xmlBytes = xml.length(), pbf = pbf.path, pbfBytes = pbf.length(),
            nodes = counts.first, ways = counts.second, relations = counts.third,
            converted = !upToDate, seconds = (System.nanoTime() - started) / 1e9,
        )
    }

    /** Parses OSM XML (API 0.6 / Overpass). DTDs and external entities are refused. */
    fun read(input: InputStream): Extract {
        val factory = XMLInputFactory.newFactory().apply {
            setProperty(XMLInputFactory.SUPPORT_DTD, false)
            setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        }
        val r = factory.createXMLStreamReader(input)
        val nodes = ArrayList<Node>()
        val ways = ArrayList<Way>()
        val relations = ArrayList<Relation>()
        var sawRoot = false
        // The element being read: its kind, id, coordinates, tags, refs, members.
        var kind: String? = null
        var id = 0L
        var lat = 0.0
        var lon = 0.0
        var tags = ArrayList<Pair<String, String>>()
        var refs = LongArrayList()
        var members = ArrayList<Member>()
        var remark: StringBuilder? = null
        fun attr(name: String): String? = r.getAttributeValue(null, name)
        fun need(name: String): String = attr(name) ?: throw BadOsmXml("<${r.localName}> without $name at line ${r.location.lineNumber}")
        try {
            while (r.hasNext()) {
                when (r.next()) {
                    XMLStreamConstants.START_ELEMENT -> when (val el = r.localName) {
                        "osm" -> sawRoot = true
                        "node", "way", "relation" -> {
                            kind = el
                            id = need("id").toLong()
                            if (el == "node") {
                                lat = need("lat").toDouble()
                                lon = need("lon").toDouble()
                            }
                            tags = ArrayList()
                            refs = LongArrayList()
                            members = ArrayList()
                        }
                        "tag" -> if (kind != null) tags += need("k") to (attr("v") ?: "")
                        "nd" -> if (kind == "way") refs.add(need("ref").toLong())
                        "member" -> if (kind == "relation") {
                            val type = when (val t = need("type")) {
                                "node" -> Osmformat.Relation.MemberType.NODE
                                "way" -> Osmformat.Relation.MemberType.WAY
                                "relation" -> Osmformat.Relation.MemberType.RELATION
                                else -> throw BadOsmXml("member type '$t' at line ${r.location.lineNumber}")
                            }
                            members += Member(type, need("ref").toLong(), attr("role") ?: "")
                        }
                        "remark" -> remark = StringBuilder()
                    }
                    XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> remark?.append(r.text)
                    XMLStreamConstants.END_ELEMENT -> when (r.localName) {
                        "node" -> { nodes += Node(id, lat, lon, tags); kind = null }
                        "way" -> { ways += Way(id, refs.toArray(), tags); kind = null }
                        "relation" -> { relations += Relation(id, members, tags); kind = null }
                        "remark" -> {
                            // Overpass reports timeouts and memory exhaustion as a remark in a 200 answer; the data is then incomplete.
                            val text = remark.toString().trim()
                            if (text.contains("error", ignoreCase = true)) throw BadOsmXml("Overpass answered with an error remark: $text")
                            remark = null
                        }
                    }
                }
            }
        } finally {
            r.close()
        }
        if (!sawRoot) throw BadOsmXml("not OSM XML: no <osm> root element")
        // Overpass prints duplicates when an element is reached twice (directly and by recursion); keep one.
        return Extract(
            nodes.distinctBy { it.id }.sortedBy { it.id },
            ways.distinctBy { it.id }.sortedBy { it.id },
            relations.distinctBy { it.id }.sortedBy { it.id },
        )
    }

    /** Writes [extract] as an OSM PBF file to [out]. */
    fun write(extract: Extract, out: OutputStream, bbox: DoubleArray?) {
        val data = DataOutputStream(out)
        val header = Osmformat.HeaderBlock.newBuilder()
            .addRequiredFeatures("OsmSchema-V0.6")
            .addRequiredFeatures("DenseNodes")
            .setWritingprogram("dystopo-tilebuild")
        if (bbox != null) {
            val (w, s, e, n) = bbox.toList()
            header.setBbox(
                Osmformat.HeaderBBox.newBuilder()
                    .setLeft(nano(w)).setRight(nano(e)).setTop(nano(n)).setBottom(nano(s)),
            )
        }
        writeBlob(data, "OSMHeader", header.build().toByteArray())
        for (chunk in extract.nodes.chunked(BLOCK_SIZE)) writeBlob(data, "OSMData", nodeBlock(chunk))
        for (chunk in extract.ways.chunked(BLOCK_SIZE)) writeBlob(data, "OSMData", wayBlock(chunk))
        for (chunk in extract.relations.chunked(BLOCK_SIZE)) writeBlob(data, "OSMData", relationBlock(chunk))
        data.flush()
    }

    private fun nano(deg: Double): Long = (deg * 1e9).roundToLong()

    /** Coordinate in units of [GRANULARITY] nanodegrees. */
    private fun units(deg: Double): Long = (deg * 1e9 / GRANULARITY).roundToLong()

    /** A block's string table: index 0 is reserved (the empty string). */
    private class Strings {
        private val index = LinkedHashMap<String, Int>().apply { put("", 0) }
        fun id(s: String): Int = index.getOrPut(s) { index.size }
        fun table(): Osmformat.StringTable = Osmformat.StringTable.newBuilder()
            .addAllS(index.keys.map { ByteString.copyFromUtf8(it) }).build()
    }

    private fun block(strings: Strings, group: Osmformat.PrimitiveGroup): ByteArray =
        Osmformat.PrimitiveBlock.newBuilder()
            .setStringtable(strings.table())
            .setGranularity(GRANULARITY)
            // Planetiler's copy of osmformat.proto declares the groups as bytes (it decodes them lazily); the
            // wire format is the same, so the serialized group goes in as is.
            .addPrimitivegroup(group.toByteString())
            .build().toByteArray()

    private fun nodeBlock(nodes: List<Node>): ByteArray {
        val strings = Strings()
        val dense = Osmformat.DenseNodes.newBuilder()
        var lastId = 0L
        var lastLat = 0L
        var lastLon = 0L
        for (n in nodes) {
            val lat = units(n.lat)
            val lon = units(n.lon)
            dense.addId(n.id - lastId).addLat(lat - lastLat).addLon(lon - lastLon)
            lastId = n.id
            lastLat = lat
            lastLon = lon
            for ((k, v) in n.tags) dense.addKeysVals(strings.id(k)).addKeysVals(strings.id(v))
            dense.addKeysVals(0)
        }
        return block(strings, Osmformat.PrimitiveGroup.newBuilder().setDense(dense).build())
    }

    private fun wayBlock(ways: List<Way>): ByteArray {
        val strings = Strings()
        val group = Osmformat.PrimitiveGroup.newBuilder()
        for (w in ways) {
            val b = Osmformat.Way.newBuilder().setId(w.id)
            for ((k, v) in w.tags) b.addKeys(strings.id(k)).addVals(strings.id(v))
            var last = 0L
            for (ref in w.refs) {
                b.addRefs(ref - last)
                last = ref
            }
            group.addWays(b)
        }
        return block(strings, group.build())
    }

    private fun relationBlock(relations: List<Relation>): ByteArray {
        val strings = Strings()
        val group = Osmformat.PrimitiveGroup.newBuilder()
        for (rel in relations) {
            val b = Osmformat.Relation.newBuilder().setId(rel.id)
            for ((k, v) in rel.tags) b.addKeys(strings.id(k)).addVals(strings.id(v))
            var last = 0L
            for (m in rel.members) {
                b.addRolesSid(strings.id(m.role)).addMemids(m.ref - last).addTypes(m.type)
                last = m.ref
            }
            group.addRelations(b)
        }
        return block(strings, group.build())
    }

    /** One file block: 4-byte big-endian header length, `BlobHeader`, zlib `Blob`. */
    private fun writeBlob(out: DataOutputStream, type: String, raw: ByteArray) {
        val deflater = Deflater()
        val compressed = try {
            deflater.setInput(raw)
            deflater.finish()
            val buf = java.io.ByteArrayOutputStream(raw.size / 2 + 64)
            val chunk = ByteArray(1 shl 16)
            while (!deflater.finished()) buf.write(chunk, 0, deflater.deflate(chunk))
            buf.toByteArray()
        } finally {
            deflater.end()
        }
        val blob = Fileformat.Blob.newBuilder().setRawSize(raw.size).setZlibData(ByteString.copyFrom(compressed)).build().toByteArray()
        val header = Fileformat.BlobHeader.newBuilder().setType(type).setDatasize(blob.size).build().toByteArray()
        out.writeInt(header.size)
        out.write(header)
        out.write(blob)
    }

    /** A growable primitive long list, so way refs don't box. */
    private class LongArrayList {
        private var a = LongArray(16)
        private var n = 0
        fun add(v: Long) {
            if (n == a.size) a = a.copyOf(n * 2)
            a[n++] = v
        }
        fun toArray(): LongArray = a.copyOf(n)
    }
}
