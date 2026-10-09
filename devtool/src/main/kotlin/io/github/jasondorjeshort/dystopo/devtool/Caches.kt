package io.github.jasondorjeshort.dystopo.devtool

import java.io.File

/**
 * Where downloaded data is cached. Defaults to `cache/` under the working directory. Set the
 * DYSTOPO_CACHE environment variable to share one cache between checkouts and worktrees, so
 * parallel agents don't each re-download the same elevation data.
 */
object Caches {
    val root: File = File(System.getenv("DYSTOPO_CACHE")?.takeIf { it.isNotBlank() } ?: "cache")

    fun dir(name: String): File = File(root, name)
}
