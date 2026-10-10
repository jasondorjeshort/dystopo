package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.dem.ElevationGrid
import io.github.jasondorjeshort.dystopo.geo.MercatorWindow
import io.github.jasondorjeshort.dystopo.tiff.ByteRangeSource
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration

/** Something that can fill a Web Mercator window with elevations (meters, NAVD88/EGM-ish orthometric heights). */
interface ElevationSource {
    val id: String
    val attribution: String

    /** [counters] carries source-specific numbers for the JSON report (tiles fetched, nodata cells...). */
    data class Result(val grid: ElevationGrid, val counters: Map<String, Long>)

    fun fetch(window: MercatorWindow): Result

    companion object {
        val ids = listOf("best", "copernicus", "terrarium", "3dep", "3dep-1m", "3dep-best")

        fun byId(id: String): ElevationSource = when (id) {
            "best" -> BestSource()
            "copernicus" -> CopernicusSource()
            "terrarium" -> TerrariumSource()
            "3dep" -> ThreeDepSource()
            "3dep-1m" -> LidarSource()
            "3dep-best" -> ThreeDepBestSource()
            else -> throw IllegalArgumentException("unknown source '$id'; known: ${ids.joinToString()}")
        }
    }
}

/**
 * A source that blends layers at coverage edges. The blend band is sized in cells from a ground
 * cell size; [ElevationSource.fetch] uses the window's own. Tile exports pass one reference cell
 * size for a whole zoom level (see `TileWindows.referenceCellSizeMeters`) so adjacent tiles, whose
 * windows sit at slightly different latitudes, weight the same cell identically.
 */
interface FeatheredSource : ElevationSource {
    fun fetch(window: MercatorWindow, featherCellSizeMeters: Double): ElevationSource.Result

    override fun fetch(window: MercatorWindow): ElevationSource.Result = fetch(window, window.cellSizeMeters)
}

object Http {
    val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(20))
        .build()

    private fun request(url: String) = HttpRequest.newBuilder(URI(url))
        .header("User-Agent", "dystopo-devtool/0.1")
        .timeout(Duration.ofSeconds(120))

    /**
     * Retries dropped connections (EOF, reset), which happen under many parallel requests to one
     * host. HTTP error statuses are not retried.
     */
    private fun <T> withRetry(attempts: Int = 4, block: () -> T): T {
        var delay = 300L
        var last: java.io.IOException? = null
        repeat(attempts) {
            try {
                return block()
            } catch (e: java.io.IOException) {
                last = e
                Thread.sleep(delay)
                delay *= 2
            }
        }
        throw last!!
    }

    /** Downloads [url] to [file] atomically. */
    fun download(url: String, file: File) {
        file.parentFile.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.${Thread.currentThread().threadId()}.part")
        val response = withRetry { client.send(request(url).build(), HttpResponse.BodyHandlers.ofFile(tmp.toPath())) }
        check(response.statusCode() == 200) { "HTTP ${response.statusCode()} for $url" }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    fun range(url: String, offset: Long, length: Int): ByteArray {
        val req = request(url).header("Range", "bytes=$offset-${offset + length - 1}").build()
        val response = withRetry { client.send(req, HttpResponse.BodyHandlers.ofByteArray()) }
        check(response.statusCode() == 206 || response.statusCode() == 200) { "HTTP ${response.statusCode()} for $url" }
        return response.body()
    }
}

/**
 * HTTP range reads with an on-disk cache per (offset, length), so re-running a render
 * doesn't re-download. [fetches] counts network requests.
 */
class CachedHttpRangeSource(private val url: String, private val cacheDir: File) : ByteRangeSource {
    @Volatile var fetches = 0L
        private set

    override fun read(offset: Long, length: Int): ByteArray {
        val file = File(cacheDir, "$offset-$length.bin")
        if (file.exists()) return file.readBytes()
        val bytes = Http.range(url, offset, length)
        synchronized(this) { fetches++ }
        file.parentFile.mkdirs()
        // Unique temp name: parallel tile renders can fetch the same range at the same time.
        val tmp = File(cacheDir, "$offset-$length.${Thread.currentThread().threadId()}.part")
        tmp.writeBytes(bytes)
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        return bytes
    }
}
