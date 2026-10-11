package io.github.jasondorjeshort.dystopo.sources.elevation

import io.github.jasondorjeshort.dystopo.tiff.Decompressor
import io.github.jasondorjeshort.dystopo.tiles.ImageDecoder

// T80: the platform pieces the elevation readers need. Everything else in this package is pure Kotlin, so the
// phone runs the same readers as the server. The actuals for the JVM and Android are one shared source set
// (`jvmCommonMain`: java.io files, thread pools, java.util.zip), except the default HTTP client, which is
// java.net.http on the JVM and HttpURLConnection on Android. Callers may replace the HTTP client
// ([SourceIo.http]); the phone does, to apply its cleartext policy and its byte and concurrency caps.

/** The platform's HTTP client for the sources when nobody installed another ([SourceIo.http]). */
internal expect fun defaultSourceHttp(): SourceHttp

/**
 * The on-disk cache under the sources: plain files addressed by path (`/`-separated, absolute or relative to
 * the working directory). Writes are atomic: a reader never sees half a file, and when two threads or
 * processes write the same path the first one to land wins ([write] returns false for the others).
 */
expect object SourceFiles {
    /** The file's bytes, or null when it does not exist. */
    fun read(path: String): ByteArray?

    /** Writes [bytes] at [path] atomically (parent directories made); false when another writer got there first. */
    fun write(path: String, bytes: ByteArray): Boolean

    /** Writes [bytes] at [path] atomically, replacing whatever is there. */
    fun replace(path: String, bytes: ByteArray)

    fun exists(path: String): Boolean

    /** Size in bytes, or -1 when the file does not exist. */
    fun length(path: String): Long

    /** Last modification time in epoch milliseconds, or 0 when the file does not exist. */
    fun lastModifiedMs(path: String): Long

    /** Sets the modification time (tests). */
    fun setLastModifiedMs(path: String, ms: Long)

    fun delete(path: String)
}

/** A fixed pool of [threads] daemon threads named [name], so one source never has more requests in flight to its host. */
expect class IoPool(name: String, threads: Int) {
    /** [f] over [items] on the pool, in order; rethrows the first failure. Tasks must not wait on other tasks of the same pool. */
    fun <T, R> map(items: Collection<T>, f: (T) -> R): List<R>
}

/** Runs [f] for every item, in parallel where the platform can (the JVM's common pool), and returns when all are done. */
expect fun <T> parallelForEach(items: List<T>, f: (T) -> Unit)

/** A value per thread (the tile export's overview override, [OverviewLevels]). */
expect class PerThread<T : Any>() {
    fun get(): T?
    fun set(value: T?)
}

/** A monotonic clock in nanoseconds (timings only). */
expect fun nanoTime(): Long

/** Wall-clock milliseconds since the epoch. */
expect fun currentTimeMillis(): Long

expect fun sleepMs(ms: Long)

/** An environment variable, or null. */
expect fun environment(name: String): String?

/** True for I/O failures worth a retry (the platform's IOException: dropped connections, timeouts). */
expect fun isIoException(e: Throwable): Boolean

/** zlib/deflate for GeoTIFF tiles (TIFF compression 8 and 32946): java.util.zip on the JVM and Android. */
expect val zlibDecompressor: Decompressor

/** The platform's general image decoder (ImageIO, BitmapFactory), for PNGs [io.github.jasondorjeshort.dystopo.png.PngDecoder] does not read. */
expect fun platformImageDecoder(): ImageDecoder?
