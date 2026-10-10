package io.github.jasondorjeshort.dystopo.server

import io.github.jasondorjeshort.dystopo.pmtiles.Compress
import io.github.jasondorjeshort.dystopo.pmtiles.Compression
import io.github.jasondorjeshort.dystopo.pmtiles.Decompress
import io.github.jasondorjeshort.dystopo.tiff.ByteRangeSource
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** JVM gzip for PMTiles directories, metadata and MVT tiles. */
object Gzip {
    val compress: Compress = { input ->
        ByteArrayOutputStream(input.size / 2 + 64).also { out -> GZIPOutputStream(out).use { it.write(input) } }.toByteArray()
    }
    val decompress: Decompress = { input -> GZIPInputStream(input.inputStream()).use { it.readAllBytes() } }
    val decompressors: Map<Compression, Decompress> = mapOf(Compression.GZIP to decompress)
}

/** Random access into a local file. */
class FileRangeSource(private val file: File) : ByteRangeSource {
    override fun read(offset: Long, length: Int): ByteArray = RandomAccessFile(file, "r").use { raf ->
        val n = minOf(length.toLong(), maxOf(0L, raf.length() - offset)).toInt()
        val out = ByteArray(n)
        raf.seek(offset)
        raf.readFully(out)
        out
    }
}

/** Counts reads and bytes going through to [inner]. */
class CountingRangeSource(private val inner: ByteRangeSource) : ByteRangeSource {
    var reads = 0L
    var bytes = 0L

    override fun read(offset: Long, length: Int): ByteArray = inner.read(offset, length).also { reads++; bytes += it.size }
}
