package io.github.jasondorjeshort.dystopo.sources.elevation

import io.github.jasondorjeshort.dystopo.tiles.BitmapImageDecoder
import io.github.jasondorjeshort.dystopo.tiles.ImageDecoder
import java.net.HttpURLConnection
import java.net.URL

/**
 * The sources' HTTP on Android when the app installed nothing else: HttpURLConnection with redirects followed.
 * The app replaces it ([SourceIo.http]) with one that applies its cleartext policy and byte caps.
 */
internal object UrlConnectionSourceHttp : SourceHttp {
    private fun open(url: String, timeoutMs: Long): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = timeoutMs.toInt()
            useCaches = false
            setRequestProperty("User-Agent", "dystopo-android/0.1")
        }

    private fun send(conn: HttpURLConnection): HttpReply = try {
        val status = conn.responseCode
        val body = if (status in 200..299) conn.inputStream.use { it.readBytes() } else ByteArray(0)
        HttpReply(status, body)
    } finally {
        conn.disconnect()
    }

    override fun range(url: String, offset: Long, length: Int): HttpReply =
        send(open(url, 120_000).apply { setRequestProperty("Range", "bytes=$offset-${offset + length - 1}") })

    override fun get(url: String, timeoutMs: Long): HttpReply = send(open(url, timeoutMs))
}

internal actual fun defaultSourceHttp(): SourceHttp = UrlConnectionSourceHttp

actual fun platformImageDecoder(): ImageDecoder? = BitmapImageDecoder
