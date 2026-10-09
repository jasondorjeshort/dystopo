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
        val ids = listOf("terrarium", "3dep")

        fun byId(id: String): ElevationSource = when (id) {
            "terrarium" -> TerrariumSource()
            "3dep" -> ThreeDepSource()
            else -> throw IllegalArgumentException("unknown source '$id'; known: ${ids.joinToString()}")
        }
    }
}

object Http {
    val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(20))
        .build()

    private fun request(url: String) = HttpRequest.newBuilder(URI(url))
        .header("User-Agent", "dystopo-devtool/0.1")
        .timeout(Duration.ofSeconds(120))

    /** Downloads [url] to [file] atomically. */
    fun download(url: String, file: File) {
        file.parentFile.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.part")
        val response = client.send(request(url).build(), HttpResponse.BodyHandlers.ofFile(tmp.toPath()))
        check(response.statusCode() == 200) { "HTTP ${response.statusCode()} for $url" }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    fun range(url: String, offset: Long, length: Int): ByteArray {
        val req = request(url).header("Range", "bytes=$offset-${offset + length - 1}").build()
        val response = client.send(req, HttpResponse.BodyHandlers.ofByteArray())
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
        val tmp = File(cacheDir, "$offset-$length.part")
        tmp.writeBytes(bytes)
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        return bytes
    }
}
