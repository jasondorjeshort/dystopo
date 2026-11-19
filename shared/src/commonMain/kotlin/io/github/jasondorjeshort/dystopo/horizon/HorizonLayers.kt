package io.github.jasondorjeshort.dystopo.horizon

/** T308: one of the Horizon view's layers, in the order its toggle buttons stand; [key] in routes and settings, [label] on the button. */
enum class HorizonLayer(val key: String, val label: String) {
    SATELLITES("satellites", "Satellites"),
    PEAKS("peaks", "Peaks"),
    SUN("sun", "Sun");

    companion object {
        fun of(key: String): HorizonLayer? = entries.firstOrNull { it.key == key.trim().lowercase() }
    }
}

/**
 * T308: which of the Horizon view's layers are on. All three by default (the task's word); the app keeps each in a synced
 * setting of its own (`horizonSatellites`, `horizonPeaks`, `horizonSun`), the way the map keeps its layer choices.
 */
data class HorizonLayers(val satellites: Boolean = true, val peaks: Boolean = true, val sun: Boolean = true) {
    operator fun get(layer: HorizonLayer): Boolean = when (layer) {
        HorizonLayer.SATELLITES -> satellites
        HorizonLayer.PEAKS -> peaks
        HorizonLayer.SUN -> sun
    }

    /** These layers with [layer] turned [on]. */
    fun with(layer: HorizonLayer, on: Boolean): HorizonLayers = when (layer) {
        HorizonLayer.SATELLITES -> copy(satellites = on)
        HorizonLayer.PEAKS -> copy(peaks = on)
        HorizonLayer.SUN -> copy(sun = on)
    }

    /** The layers on, in button order. */
    val on: List<HorizonLayer> get() = HorizonLayer.entries.filter { this[it] }

    fun json(): Map<String, Boolean> = HorizonLayer.entries.associate { it.key to this[it] }

    companion object {
        val DEFAULT = HorizonLayers()

        /**
         * The layers named in [spec], the rest off: `satellites,sun`, `all` or `none` (the debug route's `layers=`); null
         * when a name is not a layer.
         */
        fun parse(spec: String): HorizonLayers? {
            val s = spec.trim().lowercase()
            if (s == "all") return DEFAULT
            if (s == "none" || s.isEmpty()) return HorizonLayers(false, false, false)
            var out = HorizonLayers(false, false, false)
            for (part in s.split(',')) out = out.with(HorizonLayer.of(part) ?: return null, true)
            return out
        }
    }
}
