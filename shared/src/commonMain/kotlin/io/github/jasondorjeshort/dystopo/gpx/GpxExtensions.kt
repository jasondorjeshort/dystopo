package io.github.jasondorjeshort.dystopo.gpx

import io.github.jasondorjeshort.dystopo.model.Activity
import io.github.jasondorjeshort.dystopo.model.ActivityProfile
import io.github.jasondorjeshort.dystopo.model.GapReason
import io.github.jasondorjeshort.dystopo.model.Track
import io.github.jasondorjeshort.dystopo.model.TrackGap
import io.github.jasondorjeshort.dystopo.model.TrackPoint

/**
 * Reads and builds the common Garmin TrackPointExtension fields inside the verbatim `extensions`
 * XML the model keeps (see [io.github.jasondorjeshort.dystopo.model.TrackPoint.extensions]).
 * Elements are matched by local name, so any prefix (`gpxtpx:`, `ns3:`) and v1/v2 of the schema work.
 */
object GpxExtensions {
    /** Heart rate, beats per minute. */
    fun heartRate(extensions: String?): Double? = value(extensions, "hr")

    /** Cadence, revolutions (or steps) per minute. */
    fun cadence(extensions: String?): Double? = value(extensions, "cad")

    /** Air temperature, degrees Celsius. */
    fun temperature(extensions: String?): Double? = value(extensions, "atemp")

    /** The number in the first element named [localName] (any prefix), or null. Unparseable XML gives null. */
    fun value(extensions: String?, localName: String): Double? {
        if (extensions == null || localName !in extensions) return null
        try {
            val x = XmlReader("<x>$extensions</x>")
            var event = x.next()
            while (event != XmlEvent.EOF) {
                if (event == XmlEvent.START && x.localName == localName) return x.readElementText().trim().toDoubleOrNull()
                event = x.next()
            }
        } catch (e: XmlException) {
            // fall through: unreadable extensions carry no value
        }
        return null
    }

    /**
     * Our entity ids in GPX (T40): `<dystopo:id>uuid</dystopo:id>` as the first child of the
     * `<extensions>` of a `wpt`, `rte` or `trk`, with `dystopo` bound to [NS_DYSTOPO] on the root.
     * [Gpx.write] adds it, [Gpx.read] takes it out again (so the model's `extensions` never holds
     * it) and uses it as the entity's id when it is a valid UUID. Other apps keep unknown extensions
     * or drop them; either way the file stays valid GPX 1.1.
     */
    const val NS_DYSTOPO = "https://jasondorjeshort.github.io/dystopo/gpx/1"
    const val DYSTOPO_PREFIX = "dystopo"

    /** [extensions] with our id element in front. */
    fun withId(id: String, extensions: String?): String = "<$DYSTOPO_PREFIX:id>$id</$DYSTOPO_PREFIX:id>" + (extensions ?: "")

    private val idElement = Regex("""<([A-Za-z_][\w.-]*):id>\s*([0-9A-Fa-f-]{36})\s*</\1:id>""")

    /**
     * Splits our id out of [extensions]: the id (lowercase; null when absent or not a valid UUID)
     * and the remaining extensions (null when nothing is left). Only elements whose prefix is in
     * [prefixes] (those bound to [NS_DYSTOPO] in the file) count.
     */
    fun takeId(extensions: String?, prefixes: Set<String>): Pair<String?, String?> {
        if (extensions == null || prefixes.isEmpty() || ":id>" !in extensions) return null to extensions
        val m = idElement.findAll(extensions).firstOrNull { it.groupValues[1] in prefixes } ?: return null to extensions
        val id = m.groupValues[2].lowercase()
        if (!io.github.jasondorjeshort.dystopo.model.Uuid.isValid(id)) return null to extensions
        val rest = extensions.removeRange(m.range).trim().ifEmpty { null }
        return id to rest
    }

    // ---- T158: fix fields, activity, profile and gaps in our namespace ----

    /**
     * [extensions] with the point's T158 fix fields in front, as `<dystopo:fix acc=".." speed=".." course=".."
     * vacc=".." flags=".."/>` (metres, m/s, degrees, metres, [TrackPoint.flags]; absent attributes are unknown).
     * Unchanged when the point has none. [Gpx.write] declares the namespace.
     */
    fun withFix(p: TrackPoint, extensions: String?): String? {
        if (!p.hasFixFields) return extensions
        val sb = StringBuilder("<$DYSTOPO_PREFIX:fix")
        p.accuracyM?.let { sb.append(" acc=\"").append(GpxFormat.formatDecimal(it)).append('"') }
        p.speedMps?.let { sb.append(" speed=\"").append(GpxFormat.formatDecimal(it)).append('"') }
        p.courseDeg?.let { sb.append(" course=\"").append(GpxFormat.formatDecimal(it)).append('"') }
        p.verticalAccuracyM?.let { sb.append(" vacc=\"").append(GpxFormat.formatDecimal(it)).append('"') }
        if (p.flags != 0) sb.append(" flags=\"").append(p.flags).append('"')
        return sb.append("/>").toString() + (extensions ?: "")
    }

    /**
     * [extensions] with the track's T158 fields in front: `<dystopo:activity>`, `<dystopo:profile>`
     * ([ActivityProfile.encode]) and one `<dystopo:gap start=".." end=".." reason=".."/>` per gap (ISO-8601 times,
     * [GapReason.code]). Unchanged when the track has none.
     */
    fun withTrackFields(t: Track, extensions: String?): String? {
        if (t.activity == null && t.profile == null && t.gaps.isEmpty()) return extensions
        val sb = StringBuilder()
        t.activity?.let { sb.append("<$DYSTOPO_PREFIX:activity>").append(it.code).append("</$DYSTOPO_PREFIX:activity>") }
        t.profile?.let { sb.append("<$DYSTOPO_PREFIX:profile>").append(XmlWriter.escape(it.encode(), attribute = false)).append("</$DYSTOPO_PREFIX:profile>") }
        for (g in t.gaps) {
            sb.append("<$DYSTOPO_PREFIX:gap start=\"").append(GpxFormat.formatTime(g.start)).append("\" end=\"").append(GpxFormat.formatTime(g.end))
                .append("\" reason=\"").append(g.reason.code).append("\"/>")
        }
        return sb.toString() + (extensions ?: "")
    }

    /** `<p:name .../>` (or `<p:name ...></p:name>`) elements of ours: their attributes, and the ranges they cover. */
    private fun ourElements(extensions: String, name: String, prefixes: Set<String>): List<Pair<Map<String, String>, IntRange>> =
        Regex("""<([A-Za-z_][\w.-]*):$name\b([^>]*?)(?:/>|>\s*</\1:$name>)""").findAll(extensions)
            .filter { it.groupValues[1] in prefixes }
            .map { m ->
                Regex("""([\w.-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')""").findAll(m.groupValues[2])
                    .associate { a -> a.groupValues[1] to a.groupValues[2].ifEmpty { a.groupValues[3] } } to m.range
            }
            .toList()

    private fun withoutRanges(extensions: String, ranges: List<IntRange>): String? {
        var s = extensions
        for (r in ranges.sortedByDescending { it.first }) s = s.removeRange(r)
        return s.trim().ifEmpty { null }
    }

    /**
     * Takes our `fix` element out of a point's extensions (only prefixes in [prefixes], those bound to
     * [NS_DYSTOPO]): [p] with the fields it gives (a value that is not a finite number is left unknown; flag bits this
     * build does not know are dropped) and the remaining extensions. Unchanged when there is none.
     */
    fun takeFix(p: TrackPoint, prefixes: Set<String>): TrackPoint {
        val ext = p.extensions
        if (ext == null || prefixes.isEmpty() || ":fix" !in ext) return p
        val found = ourElements(ext, "fix", prefixes)
        val a = found.firstOrNull()?.first ?: return p
        fun d(k: String) = a[k]?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() }
        return p.copy(
            extensions = withoutRanges(ext, found.map { it.second }),
            accuracyM = d("acc")?.takeIf { it > 0 }, speedMps = d("speed")?.takeIf { it >= 0 }, courseDeg = d("course"),
            verticalAccuracyM = d("vacc")?.takeIf { it > 0 },
            flags = (a["flags"]?.trim()?.toIntOrNull() ?: 0) and TrackPoint.KNOWN_FLAGS,
        )
    }

    /**
     * Takes our activity, profile and gap elements out of a track's extensions (prefixes as [takeFix]): [t] with
     * them as fields and the remaining extensions. An unknown activity or gap reason, or an unreadable time, is
     * skipped.
     */
    fun takeTrackFields(t: Track, prefixes: Set<String>): Track {
        val ext = t.extensions
        if (ext == null || prefixes.isEmpty() || (":activity" !in ext && ":profile" !in ext && ":gap" !in ext)) return t
        val ranges = ArrayList<IntRange>()
        fun text(name: String): String? {
            val m = Regex("""<([A-Za-z_][\w.-]*):$name>([^<]*)</\1:$name>""").findAll(ext).firstOrNull { it.groupValues[1] in prefixes } ?: return null
            ranges += m.range
            return m.groupValues[2].trim().replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
        }
        val activity = text("activity")
        val profile = text("profile")
        val gapElements = ourElements(ext, "gap", prefixes)
        ranges += gapElements.map { it.second }
        val gaps = gapElements.mapNotNull { (a, _) ->
            val start = a["start"]?.let(GpxFormat::parseTime) ?: return@mapNotNull null
            val end = a["end"]?.let(GpxFormat::parseTime) ?: return@mapNotNull null
            val reason = GapReason.of(a["reason"]) ?: return@mapNotNull null
            if (end < start) null else TrackGap(start, end, reason)
        }
        return t.copy(
            extensions = withoutRanges(ext, ranges),
            activity = Activity.of(activity) ?: t.activity,
            profile = ActivityProfile.decode(profile) ?: t.profile,
            gaps = (t.gaps + gaps).distinct().sortedWith(compareBy({ it.start }, { it.end }, { it.reason.code })),
        )
    }

    /** A `gpxtpx:TrackPointExtension` fragment (declare [Gpx.NS_GARMIN_TPX1] as `gpxtpx`; [Gpx.write] does). */
    fun trackPointExtension(heartRate: Int? = null, cadence: Int? = null, temperature: Double? = null): String {
        val sb = StringBuilder("<gpxtpx:TrackPointExtension>")
        temperature?.let { sb.append("<gpxtpx:atemp>").append(GpxFormat.formatDecimal(it)).append("</gpxtpx:atemp>") }
        heartRate?.let { sb.append("<gpxtpx:hr>").append(it).append("</gpxtpx:hr>") }
        cadence?.let { sb.append("<gpxtpx:cad>").append(it).append("</gpxtpx:cad>") }
        return sb.append("</gpxtpx:TrackPointExtension>").toString()
    }
}
