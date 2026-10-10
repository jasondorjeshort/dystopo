package io.github.jasondorjeshort.dystopo.server

import io.github.jasondorjeshort.dystopo.geo.MercatorWindow

/**
 * Which ground resolution a source picks its GeoTIFF overview level for.
 *
 * By default that is the window's own cell size, taken at its middle row. Web Mercator cells shrink
 * toward the poles, so in a tall tile export two neighbouring tiles can fall on either side of an
 * overview threshold and read different levels: a resampling seam at some latitude. A tile export
 * therefore picks one target per zoom level and runs every tile's fetch inside [withTarget], and
 * sources ask [targetMeters] instead of reading `window.cellSizeMeters` directly.
 *
 * The override is per thread, so it must be read on the thread that runs the fetch, before any
 * fan-out into parallel streams.
 */
object OverviewLevels {
    private val override = ThreadLocal<Double?>()

    /** Ground size in meters the overview level should match for [window]. */
    fun targetMeters(window: MercatorWindow): Double = override.get() ?: window.cellSizeMeters

    /** Runs [block] with every [targetMeters] call on this thread answering [meters]. */
    fun <T> withTarget(meters: Double, block: () -> T): T {
        require(meters > 0) { "overview target must be positive" }
        val previous = override.get()
        override.set(meters)
        try {
            return block()
        } finally {
            override.set(previous)
        }
    }
}
