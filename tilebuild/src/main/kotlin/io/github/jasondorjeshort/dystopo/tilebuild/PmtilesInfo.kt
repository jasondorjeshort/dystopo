package io.github.jasondorjeshort.dystopo.tilebuild

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream

/**
 * The fixed 127-byte PMTiles v3 header plus the archive's metadata JSON. Enough to verify what
 * Planetiler wrote (tile counts, zoom range, bounds, attribution) without a full reader; the shared
 * PMTiles implementation (task T3) is the one to use for anything beyond that.
 */
@Serializable
data class PmtilesInfo(
    val version: Int,
    val addressedTiles: Long,
    val tileEntries: Long,
    val tileContents: Long,
    val clustered: Boolean,
    val internalCompression: String,
    val tileCompression: String,
    val tileType: String,
    val minZoom: Int,
    val maxZoom: Int,
    /** west, south, east, north in degrees. */
    val bounds: List<Double>,
    val centerZoom: Int,
    val center: List<Double>,
    val rootDirectoryBytes: Long,
    val leafDirectoriesBytes: Long,
    val tileDataBytes: Long,
    val metadataBytes: Long,
    /** Metadata JSON with `vector_layers` (long) replaced by [vectorLayers]; null if it could not be decoded. */
    val metadata: JsonObject?,
    /** Layer names from `vector_layers` (or the MBTiles-style `json` string), in archive order. */
    val vectorLayers: List<String>,
) {
    companion object {
        const val HEADER_BYTES = 127
        private const val MAGIC = "PMTiles"

        fun read(file: File): PmtilesInfo = RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(HEADER_BYTES)
            raf.readFully(header)
            val info = parseHeader(header)
            val (metadataOffset, metadataLength) = metadataRange(header)
            val metadata = if (metadataLength in 1..(64L shl 20)) {
                val bytes = ByteArray(metadataLength.toInt())
                raf.seek(metadataOffset)
                raf.readFully(bytes)
                decodeMetadata(bytes, info.internalCompression)
            } else {
                null
            }
            info.withMetadata(metadata)
        }

        /** Offset and length of the metadata section, needed to read it from the file. */
        fun metadataRange(header: ByteArray): Pair<Long, Long> {
            val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            return buf.getLong(24) to buf.getLong(32)
        }

        /** Parses the fixed header; [metadata] and [vectorLayers] are left empty. */
        fun parseHeader(header: ByteArray): PmtilesInfo {
            require(header.size >= HEADER_BYTES) { "PMTiles header needs $HEADER_BYTES bytes, got ${header.size}" }
            val magic = String(header, 0, 7, Charsets.US_ASCII)
            require(magic == MAGIC) { "not a PMTiles file (magic '$magic')" }
            val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            fun e7(offset: Int) = buf.getInt(offset) / 1e7
            return PmtilesInfo(
                version = header[7].toInt(),
                rootDirectoryBytes = buf.getLong(16),
                metadataBytes = buf.getLong(32),
                leafDirectoriesBytes = buf.getLong(48),
                tileDataBytes = buf.getLong(64),
                addressedTiles = buf.getLong(72),
                tileEntries = buf.getLong(80),
                tileContents = buf.getLong(88),
                clustered = header[96].toInt() == 1,
                internalCompression = compressionName(header[97].toInt()),
                tileCompression = compressionName(header[98].toInt()),
                tileType = when (header[99].toInt()) {
                    0 -> "unknown"; 1 -> "mvt"; 2 -> "png"; 3 -> "jpeg"; 4 -> "webp"; 5 -> "avif"
                    else -> "type-${header[99]}"
                },
                minZoom = header[100].toInt() and 0xff,
                maxZoom = header[101].toInt() and 0xff,
                bounds = listOf(e7(102), e7(106), e7(110), e7(114)),
                centerZoom = header[118].toInt() and 0xff,
                center = listOf(e7(119), e7(123)),
                metadata = null,
                vectorLayers = emptyList(),
            )
        }

        fun compressionName(code: Int): String = when (code) {
            0 -> "unknown"; 1 -> "none"; 2 -> "gzip"; 3 -> "brotli"; 4 -> "zstd"
            else -> "compression-$code"
        }

        /** Decompresses and parses the metadata section; returns null for compressions we don't handle. */
        fun decodeMetadata(bytes: ByteArray, compression: String): JsonObject? {
            val text = when (compression) {
                "none" -> bytes.decodeToString()
                "gzip" -> GZIPInputStream(bytes.inputStream()).use { it.readBytes() }.decodeToString()
                else -> return null
            }
            return runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
        }

        private fun layerNames(layers: JsonArray): List<String> =
            layers.mapNotNull { (it as? JsonObject)?.get("id")?.jsonPrimitive?.content }
    }

    fun withMetadata(raw: JsonObject?): PmtilesInfo {
        if (raw == null) return this
        val layers: List<String> = when (val vl = raw["vector_layers"]) {
            is JsonArray -> layerNames(vl)
            else -> {
                // MBTiles-style: a "json" string holding {"vector_layers": [...]}
                val jsonText = (raw["json"] as? JsonPrimitive)?.content
                val parsed = jsonText?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
                (parsed?.get("vector_layers") as? JsonArray)?.let { layerNames(it) } ?: emptyList()
            }
        }
        val trimmed = JsonObject(raw.filterKeys { it != "vector_layers" && it != "json" })
        return copy(metadata = trimmed, vectorLayers = layers)
    }

    /** The `attribution` metadata field, or null if the archive has none. */
    val attribution: String? get() = (metadata?.get("attribution") as? JsonPrimitive)?.content
}
