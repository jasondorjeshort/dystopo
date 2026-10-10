package io.github.jasondorjeshort.dystopo.pmtiles

import kotlin.math.roundToInt

/** Compression of directories/metadata or of tile contents, as stored in the header. */
enum class Compression(val code: Int, val label: String) {
    UNKNOWN(0, "unknown"), NONE(1, "none"), GZIP(2, "gzip"), BROTLI(3, "brotli"), ZSTD(4, "zstd");

    companion object {
        fun of(code: Int): Compression = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

enum class TileType(val code: Int, val label: String, val extension: String) {
    UNKNOWN(0, "unknown", "bin"), MVT(1, "mvt", "mvt"), PNG(2, "png", "png"), JPEG(3, "jpeg", "jpg"),
    WEBP(4, "webp", "webp"), AVIF(5, "avif", "avif");

    companion object {
        fun of(code: Int): TileType = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

/**
 * The fixed 127-byte PMTiles v3 header. Offsets are absolute positions in the archive; lengths are
 * in bytes. Longitudes and latitudes are stored as 32-bit integers in 1e-7 degree units.
 */
data class PmtilesHeader(
    val rootDirectoryOffset: Long,
    val rootDirectoryLength: Long,
    val metadataOffset: Long,
    val metadataLength: Long,
    val leafDirectoriesOffset: Long,
    val leafDirectoriesLength: Long,
    val tileDataOffset: Long,
    val tileDataLength: Long,
    /** Tiles addressable in the archive (run lengths count once per tile). */
    val addressedTiles: Long,
    /** Entries in all directories that point at tile data. */
    val tileEntries: Long,
    /** Distinct tile blobs stored. */
    val tileContents: Long,
    /** True if tile data is laid out in tile-ID order. */
    val clustered: Boolean,
    val internalCompression: Compression,
    val tileCompression: Compression,
    val tileType: TileType,
    val minZoom: Int,
    val maxZoom: Int,
    val minLon: Double,
    val minLat: Double,
    val maxLon: Double,
    val maxLat: Double,
    val centerZoom: Int,
    val centerLon: Double,
    val centerLat: Double,
) {
    fun encode(): ByteArray {
        val w = ByteWriter(SIZE)
        w.bytes(MAGIC)
        w.u8(VERSION)
        w.u64(rootDirectoryOffset)
        w.u64(rootDirectoryLength)
        w.u64(metadataOffset)
        w.u64(metadataLength)
        w.u64(leafDirectoriesOffset)
        w.u64(leafDirectoriesLength)
        w.u64(tileDataOffset)
        w.u64(tileDataLength)
        w.u64(addressedTiles)
        w.u64(tileEntries)
        w.u64(tileContents)
        w.u8(if (clustered) 1 else 0)
        w.u8(internalCompression.code)
        w.u8(tileCompression.code)
        w.u8(tileType.code)
        w.u8(minZoom)
        w.u8(maxZoom)
        w.i32(e7(minLon))
        w.i32(e7(minLat))
        w.i32(e7(maxLon))
        w.i32(e7(maxLat))
        w.u8(centerZoom)
        w.i32(e7(centerLon))
        w.i32(e7(centerLat))
        check(w.size == SIZE)
        return w.toByteArray()
    }

    companion object {
        const val SIZE = 127
        const val VERSION = 3
        private val MAGIC = "PMTiles".encodeToByteArray()

        private fun e7(degrees: Double): Int = (degrees * 1e7).roundToInt()

        fun decode(bytes: ByteArray): PmtilesHeader {
            require(bytes.size >= SIZE) { "PMTiles header needs $SIZE bytes, got ${bytes.size}" }
            require(MAGIC.indices.all { bytes[it] == MAGIC[it] }) { "not a PMTiles archive (bad magic)" }
            val r = ByteReader(bytes, MAGIC.size)
            val version = r.u8()
            require(version == VERSION) { "PMTiles version $version not supported (want $VERSION)" }
            return PmtilesHeader(
                rootDirectoryOffset = r.u64(),
                rootDirectoryLength = r.u64(),
                metadataOffset = r.u64(),
                metadataLength = r.u64(),
                leafDirectoriesOffset = r.u64(),
                leafDirectoriesLength = r.u64(),
                tileDataOffset = r.u64(),
                tileDataLength = r.u64(),
                addressedTiles = r.u64(),
                tileEntries = r.u64(),
                tileContents = r.u64(),
                clustered = r.u8() == 1,
                internalCompression = Compression.of(r.u8()),
                tileCompression = Compression.of(r.u8()),
                tileType = TileType.of(r.u8()),
                minZoom = r.u8(),
                maxZoom = r.u8(),
                minLon = r.i32() / 1e7,
                minLat = r.i32() / 1e7,
                maxLon = r.i32() / 1e7,
                maxLat = r.i32() / 1e7,
                centerZoom = r.u8(),
                centerLon = r.i32() / 1e7,
                centerLat = r.i32() / 1e7,
            )
        }
    }
}
