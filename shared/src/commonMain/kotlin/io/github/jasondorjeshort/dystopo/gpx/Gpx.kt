package io.github.jasondorjeshort.dystopo.gpx

import io.github.jasondorjeshort.dystopo.model.Route
import io.github.jasondorjeshort.dystopo.model.RoutePoint
import io.github.jasondorjeshort.dystopo.model.Track
import io.github.jasondorjeshort.dystopo.model.TrackPoint
import io.github.jasondorjeshort.dystopo.model.TrackSegment
import io.github.jasondorjeshort.dystopo.model.UuidGenerator
import io.github.jasondorjeshort.dystopo.model.Waypoint

/** GPX `metadata` (GPX 1.0 keeps name, desc and time on the root; they land here too). */
data class GpxMetadata(
    val name: String? = null,
    val description: String? = null,
    val time: Long? = null,
    val keywords: String? = null,
    val extensions: String? = null,
)

/** The contents of a GPX file: waypoints, routes and tracks, plus what's needed to write it back. */
data class GpxDocument(
    val waypoints: List<Waypoint> = emptyList(),
    val routes: List<Route> = emptyList(),
    val tracks: List<Track> = emptyList(),
    val metadata: GpxMetadata? = null,
    /** The `creator` attribute: the software that wrote the file. */
    val creator: String = Gpx.CREATOR,
    /**
     * Namespace prefixes declared on the root besides GPX and XML Schema instance (e.g.
     * `gpxtpx` for Garmin's TrackPointExtension), so [Track.extensions] etc. stay valid on export.
     */
    val namespaces: Map<String, String> = emptyMap(),
    /** Inner XML of the root `<extensions>`. */
    val extensions: String? = null,
)

class GpxException(message: String) : IllegalArgumentException(message)

/**
 * GPX 1.1 import and export (GPX 1.0 files import too).
 *
 * Import keeps everything the model has a field for and the verbatim inner XML of every
 * `<extensions>` element; other elements (`link`, `fix`, `hdop`, GPX 1.0 `speed`/`course`...) are
 * skipped and counted in [ParseResult.ignored]. Points with a missing or out-of-range lat/lon are
 * dropped and counted too. Each waypoint, route and track gets a fresh UUID from the caller's
 * [UuidGenerator]: GPX has no ids, so importing the same file twice gives new entities unless the
 * generator is seeded the same way.
 */
object Gpx {
    const val CREATOR = "Dystopo"
    const val NS_GPX11 = "http://www.topografix.com/GPX/1/1"
    const val NS_GPX10 = "http://www.topografix.com/GPX/1/0"
    const val NS_XSI = "http://www.w3.org/2001/XMLSchema-instance"
    const val NS_GARMIN_TPX1 = "http://www.garmin.com/xmlschemas/TrackPointExtension/v1"
    private const val SCHEMA_LOCATION = "$NS_GPX11 http://www.topografix.com/GPX/1/1/gpx.xsd"

    data class ParseResult(
        val document: GpxDocument,
        /** Skipped elements and dropped points by name ("trkpt (bad lat/lon)"), for reports. */
        val ignored: Map<String, Int>,
    )

    fun parse(text: String, ids: UuidGenerator = UuidGenerator()): GpxDocument = read(text, ids).document

    fun read(text: String, ids: UuidGenerator = UuidGenerator()): ParseResult = Reader(text, ids).read()

    private class Reader(text: String, private val ids: UuidGenerator) {
        private val x = XmlReader(text.removePrefix("﻿"))
        val ignored = LinkedHashMap<String, Int>()

        private fun ignore(what: String) {
            ignored[what] = (ignored[what] ?: 0) + 1
        }

        /** GPX elements are in the GPX namespace; files that omit the namespace are common and accepted. */
        private fun isGpx(): Boolean = x.namespaceUri.let { it == null || it == NS_GPX11 || it == NS_GPX10 }

        private fun child(): String? = if (isGpx()) x.localName else null

        fun read(): ParseResult {
            while (true) {
                when (x.next()) {
                    XmlEvent.START -> break
                    XmlEvent.EOF -> throw GpxException("no root element")
                    else -> Unit
                }
            }
            if (x.localName != "gpx") throw GpxException("root element is <${x.name}>, not <gpx>")
            val creator = x.attributes["creator"] ?: CREATOR
            val namespaces = LinkedHashMap<String, String>()
            for ((k, v) in x.attributes) {
                if (k.startsWith("xmlns:") && v != NS_XSI && v != NS_GPX11 && v != NS_GPX10) namespaces[k.substring(6)] = v
            }
            val waypoints = ArrayList<Waypoint>()
            val routes = ArrayList<Route>()
            val tracks = ArrayList<Track>()
            var metadata: GpxMetadata? = null
            var extensions: String? = null
            // GPX 1.0 root-level metadata
            var name10: String? = null
            var desc10: String? = null
            var time10: Long? = null
            var keywords10: String? = null
            while (x.nextChild()) {
                when (child()) {
                    "metadata" -> metadata = metadata()
                    "wpt" -> waypoint()?.let { waypoints += it }
                    "rte" -> routes += route()
                    "trk" -> tracks += track()
                    "extensions" -> extensions = extensions()
                    "name" -> name10 = str()
                    "desc" -> desc10 = str()
                    "time" -> time10 = time()
                    "keywords" -> keywords10 = str()
                    else -> skip()
                }
            }
            if (metadata == null && (name10 != null || desc10 != null || time10 != null || keywords10 != null)) {
                metadata = GpxMetadata(name10, desc10, time10, keywords10)
            }
            val doc = GpxDocument(waypoints, routes, tracks, metadata, creator, namespaces, extensions)
            return ParseResult(doc, ignored)
        }

        private fun skip() {
            ignore(x.name)
            x.skipElement()
        }

        private fun extensions(): String? = x.readInnerXml().trim().ifEmpty { null }

        private fun time(): Long? {
            val t = str()
            return GpxFormat.parseTime(t).also { if (it == null) ignore("time (unparsed)") }
        }

        /** Element text, trimmed: pretty-printers sometimes wrap values in whitespace. */
        private fun str(): String = x.readElementText().trim()

        private fun number(): Double? = str().toDoubleOrNull()?.takeIf { it.isFinite() }

        private fun metadata(): GpxMetadata {
            var m = GpxMetadata()
            while (x.nextChild()) {
                when (child()) {
                    "name" -> m = m.copy(name = str())
                    "desc" -> m = m.copy(description = str())
                    "time" -> m = m.copy(time = time())
                    "keywords" -> m = m.copy(keywords = str())
                    "extensions" -> m = m.copy(extensions = extensions())
                    else -> skip()
                }
            }
            return m
        }

        /** lat/lon of the current point element, or null (and counted) when missing or out of range. */
        private fun latLon(): Pair<Double, Double>? {
            val lat = x.attributes["lat"]?.trim()?.toDoubleOrNull()
            val lon = x.attributes["lon"]?.trim()?.toDoubleOrNull()
            if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
                ignore("${x.localName} (bad lat/lon)")
                return null
            }
            return lat to lon
        }

        /** Fields shared by wpt, rtept and trkpt (all GPX `wptType`). */
        private class Point(val lat: Double, val lon: Double) {
            var ele: Double? = null
            var time: Long? = null
            var name: String? = null
            var cmt: String? = null
            var desc: String? = null
            var sym: String? = null
            var type: String? = null
            var extensions: String? = null
        }

        private fun point(): Point? {
            val ll = latLon()
            if (ll == null) {
                x.skipElement()
                return null
            }
            val p = Point(ll.first, ll.second)
            while (x.nextChild()) {
                when (child()) {
                    "ele" -> p.ele = number()
                    "time" -> p.time = time()
                    "name" -> p.name = str()
                    "cmt" -> p.cmt = str()
                    "desc" -> p.desc = str()
                    "sym" -> p.sym = str()
                    "type" -> p.type = str()
                    "extensions" -> p.extensions = extensions()
                    else -> skip()
                }
            }
            return p
        }

        private fun waypoint(): Waypoint? {
            val p = point() ?: return null
            return Waypoint(ids.next(), p.name ?: "", p.lat, p.lon, p.ele, p.time, p.desc, p.cmt, p.sym, p.type, p.extensions)
        }

        private fun route(): Route {
            var r = Route(ids.next(), "", emptyList())
            val points = ArrayList<RoutePoint>()
            while (x.nextChild()) {
                when (child()) {
                    "name" -> r = r.copy(name = str())
                    "cmt" -> r = r.copy(comment = str())
                    "desc" -> r = r.copy(description = str())
                    "type" -> r = r.copy(type = str())
                    "extensions" -> r = r.copy(extensions = extensions())
                    "rtept" -> point()?.let { points += RoutePoint(it.lat, it.lon, it.ele, it.time, it.name, it.desc, it.sym, it.extensions) }
                    else -> skip()
                }
            }
            return r.copy(points = points)
        }

        private fun track(): Track {
            var t = Track(ids.next(), "", emptyList())
            val segments = ArrayList<TrackSegment>()
            while (x.nextChild()) {
                when (child()) {
                    "name" -> t = t.copy(name = str())
                    "cmt" -> t = t.copy(comment = str())
                    "desc" -> t = t.copy(description = str())
                    "src" -> t = t.copy(source = str())
                    "type" -> t = t.copy(type = str())
                    "extensions" -> t = t.copy(extensions = extensions())
                    "trkseg" -> segments += segment()
                    else -> skip()
                }
            }
            return t.copy(segments = segments)
        }

        private fun segment(): TrackSegment {
            val points = ArrayList<TrackPoint>()
            var ext: String? = null
            while (x.nextChild()) {
                when (child()) {
                    "trkpt" -> point()?.let { points += TrackPoint(it.lat, it.lon, it.ele, it.time, it.extensions) }
                    "extensions" -> ext = extensions()
                    else -> skip()
                }
            }
            return TrackSegment(points, ext)
        }
    }

    /** Writes [doc] as GPX 1.1. Unknown extensions are copied verbatim, with their namespace prefixes declared on the root. */
    fun write(doc: GpxDocument): String {
        val namespaces = LinkedHashMap(doc.namespaces)
        if ("gpxtpx" !in namespaces && usesPrefix(doc, "gpxtpx:")) namespaces["gpxtpx"] = NS_GARMIN_TPX1
        val rootAttrs = ArrayList<Pair<String, String>>()
        rootAttrs += "version" to "1.1"
        rootAttrs += "creator" to doc.creator
        rootAttrs += "xmlns" to NS_GPX11
        rootAttrs += "xmlns:xsi" to NS_XSI
        for ((prefix, uri) in namespaces) rootAttrs += "xmlns:$prefix" to uri
        rootAttrs += "xsi:schemaLocation" to SCHEMA_LOCATION
        val w = XmlWriter()
        w.start("gpx", rootAttrs)
        doc.metadata?.let { m ->
            w.start("metadata")
            w.element("name", m.name)
            w.element("desc", m.description)
            w.element("time", m.time?.let(GpxFormat::formatTime))
            w.element("keywords", m.keywords)
            w.rawElement("extensions", m.extensions)
            w.end()
        }
        for (p in doc.waypoints) {
            point(w, "wpt", p.lat, p.lon, p.ele, p.time, p.name.ifEmpty { null }, p.comment, p.description, p.symbol, p.type, p.extensions)
        }
        for (r in doc.routes) {
            w.start("rte")
            w.element("name", r.name.ifEmpty { null })
            w.element("cmt", r.comment)
            w.element("desc", r.description)
            w.element("type", r.type)
            w.rawElement("extensions", r.extensions)
            for (p in r.points) point(w, "rtept", p.lat, p.lon, p.ele, p.time, p.name, null, p.description, p.symbol, null, p.extensions)
            w.end()
        }
        for (t in doc.tracks) {
            w.start("trk")
            w.element("name", t.name.ifEmpty { null })
            w.element("cmt", t.comment)
            w.element("desc", t.description)
            w.element("src", t.source)
            w.element("type", t.type)
            w.rawElement("extensions", t.extensions)
            for (s in t.segments) {
                w.start("trkseg")
                for (p in s.points) point(w, "trkpt", p.lat, p.lon, p.ele, p.time, null, null, null, null, null, p.extensions)
                w.rawElement("extensions", s.extensions)
                w.end()
            }
            w.end()
        }
        w.rawElement("extensions", doc.extensions)
        w.end()
        return w.toString()
    }

    /** One `wptType` element, children in schema order. */
    private fun point(
        w: XmlWriter, tag: String, lat: Double, lon: Double, ele: Double?, time: Long?,
        name: String?, cmt: String?, desc: String?, sym: String?, type: String?, extensions: String?,
    ) {
        val attrs = listOf("lat" to GpxFormat.formatDecimal(lat), "lon" to GpxFormat.formatDecimal(lon))
        if (ele == null && time == null && name == null && cmt == null && desc == null && sym == null && type == null && extensions == null) {
            // Compact form for bare points: <trkpt lat=".." lon=".."></trkpt> stays valid and short.
            w.start(tag, attrs).end()
            return
        }
        w.start(tag, attrs)
        w.element("ele", ele?.let(GpxFormat::formatDecimal))
        w.element("time", time?.let(GpxFormat::formatTime))
        w.element("name", name)
        w.element("cmt", cmt)
        w.element("desc", desc)
        w.element("sym", sym)
        w.element("type", type)
        w.rawElement("extensions", extensions)
        w.end()
    }

    private fun usesPrefix(doc: GpxDocument, prefix: String): Boolean {
        val all = sequence {
            yield(doc.extensions)
            yield(doc.metadata?.extensions)
            doc.waypoints.forEach { yield(it.extensions) }
            doc.routes.forEach { r -> yield(r.extensions); r.points.forEach { yield(it.extensions) } }
            doc.tracks.forEach { t ->
                yield(t.extensions)
                t.segments.forEach { s -> yield(s.extensions); s.points.forEach { yield(it.extensions) } }
            }
        }
        return all.any { it != null && prefix in it }
    }
}
