package io.github.jasondorjeshort.dystopo.app.trips

import io.github.jasondorjeshort.dystopo.app.Pack

/**
 * T175: one value made from an opened [Pack] (the DEM sampler over its tiles), kept while that same pack object is the one
 * asked for and made again for any other. Keyed by identity, not by the file path: a reload
 * ([io.github.jasondorjeshort.dystopo.app.DystopoApp.reload]) opens the same file as a new [Pack] and closes the old one,
 * and the sampler keyed by path went on reading the closed channel. That was the crow routes' 500
 * (`ClosedChannelException` on `POST /route?mode=crow`) in the wave 22 sweep, once T161's readout made the sampler before
 * a reload; `PerPackTest`.
 */
class PerPack<T : Any>(private val make: (Pack) -> T) {
    @Volatile private var held: Pair<Pack, T>? = null

    /** The value for [p] (made now when [p] is not the pack the held value was made from), or null without a pack. */
    fun get(p: Pack?): T? {
        if (p == null) return null
        held?.takeIf { it.first === p }?.let { return it.second }
        return make(p).also { held = p to it }
    }
}
