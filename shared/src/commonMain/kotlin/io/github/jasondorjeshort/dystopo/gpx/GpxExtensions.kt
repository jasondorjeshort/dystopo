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

    /** A `gpxtpx:TrackPointExtension` fragment (declare [Gpx.NS_GARMIN_TPX1] as `gpxtpx`; [Gpx.write] does). */
    fun trackPointExtension(heartRate: Int? = null, cadence: Int? = null, temperature: Double? = null): String {
        val sb = StringBuilder("<gpxtpx:TrackPointExtension>")
        temperature?.let { sb.append("<gpxtpx:atemp>").append(GpxFormat.formatDecimal(it)).append("</gpxtpx:atemp>") }
        heartRate?.let { sb.append("<gpxtpx:hr>").append(it).append("</gpxtpx:hr>") }
        cadence?.let { sb.append("<gpxtpx:cad>").append(it).append("</gpxtpx:cad>") }
        return sb.append("</gpxtpx:TrackPointExtension>").toString()
    }
}
