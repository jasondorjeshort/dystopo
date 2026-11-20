package io.github.jasondorjeshort.dystopo.horizon

/** T308: one of the Horizon view's layers, in the order its toggle buttons stand; [key] in routes and settings, [label] on the button. */
enum class HorizonLayer(val key: String, val label: String) {
    SATELLITES("satellites", "Satellites"),
    PEAKS("peaks", "Peaks"),
    SUN("sun", "Sun"),

    /** T312: T311's aircraft at their bearing and elevation angle; its button shows only while Flight tracking is on. */
    FLIGHTS("flights", "Flights");

    companion object {
        fun of(key: String): HorizonLayer? = entries.firstOrNull { it.key == key.trim().lowercase() }
    }
}

/**
 * T308: which of the Horizon view's layers are on. All on by default (the task's word); the app keeps each in a synced
 * setting of its own (`horizonSatellites`, `horizonPeaks`, `horizonSun`, T312's `horizonFlights`), the way the map keeps
 * its layer choices. T312: [flights] draws only while Flight tracking (T311's setting, off by default) is on ([effective]).
 */
data class HorizonLayers(val satellites: Boolean = true, val peaks: Boolean = true, val sun: Boolean = true, val flights: Boolean = true) {
    operator fun get(layer: HorizonLayer): Boolean = when (layer) {
        HorizonLayer.SATELLITES -> satellites
        HorizonLayer.PEAKS -> peaks
        HorizonLayer.SUN -> sun
        HorizonLayer.FLIGHTS -> flights
    }

    /** These layers with [layer] turned [on]. */
    fun with(layer: HorizonLayer, on: Boolean): HorizonLayers = when (layer) {
        HorizonLayer.SATELLITES -> copy(satellites = on)
        HorizonLayer.PEAKS -> copy(peaks = on)
        HorizonLayer.SUN -> copy(sun = on)
        HorizonLayer.FLIGHTS -> copy(flights = on)
    }

    /** The layers on, in button order. */
    val on: List<HorizonLayer> get() = HorizonLayer.entries.filter { this[it] }

    fun json(): Map<String, Boolean> = HorizonLayer.entries.associate { it.key to this[it] }

    /** T312: what the view draws with Flight tracking [flightTracking]: Flights off while tracking is off, its setting kept. */
    fun effective(flightTracking: Boolean): HorizonLayers = if (flightTracking) this else copy(flights = false)

    companion object {
        /** T312: the toggle buttons shown, in order: Flights only while Flight tracking is on. */
        fun buttons(flightTracking: Boolean): List<HorizonLayer> = HorizonLayer.entries.filter { flightTracking || it != HorizonLayer.FLIGHTS }

        val DEFAULT = HorizonLayers()
        val NONE = HorizonLayers(false, false, false, false)

        /**
         * The layers named in [spec], the rest off: `satellites,sun`, `all` or `none` (the debug route's `layers=`); null
         * when a name is not a layer.
         */
        fun parse(spec: String): HorizonLayers? {
            val s = spec.trim().lowercase()
            if (s == "all") return DEFAULT
            if (s == "none" || s.isEmpty()) return NONE
            var out = NONE
            for (part in s.split(',')) out = out.with(HorizonLayer.of(part) ?: return null, true)
            return out
        }
    }
}
