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
    private val bulkFlag = ThreadLocal<Boolean?>()

    /** Ground size in meters the overview level should match for [window]. */
    fun targetMeters(window: MercatorWindow): Double = override.get() ?: window.cellSizeMeters

    /**
     * True inside a pack export (`tiles`, T58): sources that pay per request may then choose a coarser
     * product than an interactive view would (the Swiss source reads its 0.5 m tiles only below 1 m
     * cells instead of 2 m, so z16 exports read the 2 m product).
     */
    fun bulk(): Boolean = bulkFlag.get() == true

    /** Runs [block] with every [targetMeters] call on this thread answering [meters] (and [bulk] as given). */
    fun <T> withTarget(meters: Double, bulk: Boolean = false, block: () -> T): T {
        require(meters > 0) { "overview target must be positive" }
        val previous = override.get()
        val previousBulk = bulkFlag.get()
        override.set(meters)
        bulkFlag.set(bulk)
        try {
            return block()
        } finally {
            override.set(previous)
            bulkFlag.set(previousBulk)
        }
    }
}
