package io.github.jasondorjeshort.dystopo.server

import io.github.jasondorjeshort.dystopo.sources.elevation.SourceIo
import java.io.File

/**
 * Where downloaded data is cached. Defaults to `cache/` under the working directory. Set the
 * DYSTOPO_CACHE environment variable to share one cache between checkouts and worktrees, so
 * parallel agents don't each re-download the same elevation data.
 *
 * T80: the elevation readers moved to shared and read their root from [SourceIo.cacheRoot]; this is the same
 * setting seen as a [File], so setting it here (devtool's `--cache`, the server's config) moves both.
 */
object Caches {
    /** The standalone server sets this from its config file at startup, before any source is made (T52). */
    var root: File
        get() = File(SourceIo.cacheRoot)
        set(value) {
            SourceIo.cacheRoot = value.path
        }

    fun dir(name: String): File = File(root, name)
}