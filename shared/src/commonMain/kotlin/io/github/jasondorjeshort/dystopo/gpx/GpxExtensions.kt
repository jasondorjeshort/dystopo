package io.github.jasondorjeshort.dystopo.gpx

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

    /** A `gpxtpx:TrackPointExtension` fragment (declare [Gpx.NS_GARMIN_TPX1] as `gpxtpx`; [Gpx.write] does). */
    fun trackPointExtension(heartRate: Int? = null, cadence: Int? = null, temperature: Double? = null): String {
        val sb = StringBuilder("<gpxtpx:TrackPointExtension>")
        temperature?.let { sb.append("<gpxtpx:atemp>").append(GpxFormat.formatDecimal(it)).append("</gpxtpx:atemp>") }
        heartRate?.let { sb.append("<gpxtpx:hr>").append(it).append("</gpxtpx:hr>") }
        cadence?.let { sb.append("<gpxtpx:cad>").append(it).append("</gpxtpx:cad>") }
        return sb.append("</gpxtpx:TrackPointExtension>").toString()
    }
}
