package io.github.jasondorjeshort.dystopo.sources.elevation

import io.github.jasondorjeshort.dystopo.tiles.ArgbImage
import io.github.jasondorjeshort.dystopo.tiles.ImageDecoder
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import javax.imageio.ImageIO

/**
 * The sources' HTTP on the JVM (devtool, server): java.net.http with redirects followed and a 20 s connect
 * timeout, the same client settings the sources had in serverlib before T80.
 */
internal object JvmSourceHttp : SourceHttp {
    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(20))
        .build()

    private fun request(url: String, timeoutMs: Long) = HttpRequest.newBuilder(URI(url))
        .header("User-Agent", "dystopo-devtool/0.1")
        .timeout(Duration.ofMillis(timeoutMs))

    override fun range(url: String, offset: Long, length: Int): HttpReply {
        val req = request(url, 120_000).header("Range", "bytes=$offset-${offset + length - 1}").build()
        val response = client.send(req, HttpResponse.BodyHandlers.ofByteArray())
        return HttpReply(response.statusCode(), response.body())
    }

    override fun get(url: String, timeoutMs: Long): HttpReply {
        val response = client.send(request(url, timeoutMs).build(), HttpResponse.BodyHandlers.ofByteArray())
        return HttpReply(response.statusCode(), response.body())
    }
}

internal actual fun defaultSourceHttp(): SourceHttp = JvmSourceHttp

actual fun platformImageDecoder(): ImageDecoder? = ImageDecoder { bytes ->
    val img = ImageIO.read(bytes.inputStream()) ?: error("unreadable image (${bytes.size} bytes)")
    ArgbImage(img.width, img.height, img.getRGB(0, 0, img.width, img.height, null, 0, img.width))
}
