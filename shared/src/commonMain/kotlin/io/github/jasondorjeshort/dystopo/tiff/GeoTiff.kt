package io.github.jasondorjeshort.dystopo.tiff

/** Random-access bytes: a local file, an in-memory array, or HTTP range requests. May return fewer bytes at EOF. */
fun interface ByteRangeSource {
    fun read(offset: Long, length: Int): ByteArray
}

/** Decompresses one tile; [expectedSize] is the uncompressed size. */
typealias Decompressor = (input: ByteArray, expectedSize: Int) -> ByteArray

/**
 * Minimal reader for tiled, single-band, 32-bit float GeoTIFFs, which covers USGS 3DEP and
 * Copernicus DEM Cloud-Optimized GeoTIFFs. Striped files (small grids such as PROJ's Swiss geoid)
 * are read as tiles one strip tall and the full image wide. Reads only the header up front; tiles are fetched
 * on demand through [ByteRangeSource], so remote files work without downloading them whole.
 *
 * Supported: little/big-endian classic TIFF and BigTIFF (e.g. Austria's 7.7 GB ALS DTM tiles); compression none (1) and LZW (5) built in, others
 * via [Decompressor]s (e.g. deflate on the JVM); predictors none (1), horizontal (2) and
 * floating point (3); internal overviews (reduced-resolution IFDs), exposed as [overviews]. 32-bit
 * signed integer samples (PROJ's Canadian geoid grids) are read too, scaled by GDAL's SCALE and
 * OFFSET metadata (tag 42112) into floats; they take predictors 1 and 2.
 */
class GeoTiff private constructor(
    private val source: ByteRangeSource,
    private val littleEndian: Boolean,
    val width: Int,
    val height: Int,
    val tileWidth: Int,
    val tileHeight: Int,
    val compression: Int,
    val predictor: Int,
    private val tileOffsets: LongArray,
    private val tileByteCounts: LongArray,
    /** Ground size of one pixel in CRS units (degrees for geographic DEMs, meters for UTM). */
    val pixelScaleX: Double,
    val pixelScaleY: Double,
    /** CRS coordinate of the reference point of pixel (0, 0): its corner, or its center if [pixelIsPoint]. */
    val originX: Double,
    val originY: Double,
    val pixelIsPoint: Boolean,
    val noData: Float?,
    /** EPSG code of the CRS (projected if present, else geographic), e.g. 26913 = NAD83 / UTM 13N. */
    val epsg: Int?,
    /** Reduced-resolution versions of this image, finest first. Empty for an overview itself. */
    val overviews: List<GeoTiff>,
    private val decompressors: Map<Int, Decompressor>,
    /** Samples are signed 32-bit integers, read as `value * intScale + intOffset`. */
    private val intSamples: Boolean = false,
    private val intScale: Double = 1.0,
    private val intOffset: Double = 0.0,
) {
    val tilesAcross: Int = (width + tileWidth - 1) / tileWidth
    val tilesDown: Int = (height + tileHeight - 1) / tileHeight

    private val centerOffset = if (pixelIsPoint) 0.0 else 0.5

    /** This image followed by its overviews, finest first. */
    val levels: List<GeoTiff> get() = listOf(this) + overviews

    /** CRS extent of the image's pixel area: minX, minY, maxX, maxY. */
    val extent: DoubleArray
        get() {
            val x0 = xOf(-0.5)
            val y0 = yOf(-0.5)
            return doubleArrayOf(x0, y0 - height * pixelScaleY, x0 + width * pixelScaleX, y0)
        }

    /** Fractional column whose pixel center is at CRS x. */
    fun colOf(x: Double): Double = (x - originX) / pixelScaleX - centerOffset

    /** Fractional row whose pixel center is at CRS y (rows grow southward). */
    fun rowOf(y: Double): Double = (originY - y) / pixelScaleY - centerOffset

    fun xOf(col: Double): Double = originX + (col + centerOffset) * pixelScaleX

    fun yOf(row: Double): Double = originY - (row + centerOffset) * pixelScaleY

    /** Decodes tile ([tx], [ty]) into tileWidth x tileHeight floats, row-major. Tiles at the edges are padded. */
    fun readTile(tx: Int, ty: Int): FloatArray {
        require(tx in 0 until tilesAcross && ty in 0 until tilesDown) { "tile ($tx,$ty) out of range" }
        val index = ty * tilesAcross + tx
        val count = tileWidth * tileHeight
        val byteCount = tileByteCounts[index].toInt()
        if (byteCount == 0) return FloatArray(count) { noData ?: Float.NaN } // sparse COG: tile not written
        val raw = source.read(tileOffsets[index], byteCount)
        val decompress = decompressors[compression]
            ?: throw UnsupportedOperationException("TIFF compression $compression not supported")
        // A short last strip decodes to fewer bytes than a full tile: pad it (rows past the image are never read).
        val bytes = decompress(raw, count * 4).let { if (it.size < count * 4) it.copyOf(count * 4) else it }
        if (intSamples) {
            val ints = IntArray(count) { u32(bytes, it * 4, littleEndian).toInt() }
            when (predictor) {
                1 -> {}
                2 -> for (y in 0 until tileHeight) for (x in 1 until tileWidth) ints[y * tileWidth + x] += ints[y * tileWidth + x - 1]
                else -> throw UnsupportedOperationException("TIFF predictor $predictor not supported for integer samples")
            }
            return FloatArray(count) { (ints[it] * intScale + intOffset).toFloat() }
        }
        return when (predictor) {
            1 -> FloatArray(count) { Float.fromBits(u32(bytes, it * 4, littleEndian).toInt()) }
            2 -> undoHorizontalPredictor32(bytes, tileWidth, tileHeight, littleEndian)
            3 -> undoFloatPredictor(bytes, tileWidth, tileHeight)
            else -> throw UnsupportedOperationException("TIFF predictor $predictor not supported")
        }
    }

    companion object {
        private const val HEADER_BYTES = 65536

        val builtInDecompressors: Map<Int, Decompressor> = mapOf(
            1 to { input, _ -> input },
            5 to { input, size -> Lzw.decode(input, size) },
        )

        private class Ifd(
            val width: Int,
            val height: Int,
            val tileWidth: Int,
            val tileHeight: Int,
            val compression: Int,
            val predictor: Int,
            val subfileType: Int,
            val intSamples: Boolean,
            val tileOffsets: LongArray,
            val tileByteCounts: LongArray,
            val numbers: (Int) -> DoubleArray?,
            val ascii: (Int) -> String?,
            val next: Long,
        )

        fun open(source: ByteRangeSource, extraDecompressors: Map<Int, Decompressor> = emptyMap()): GeoTiff {
            val head = source.read(0, HEADER_BYTES)
            val le = when {
                head[0] == 'I'.code.toByte() && head[1] == 'I'.code.toByte() -> true
                head[0] == 'M'.code.toByte() && head[1] == 'M'.code.toByte() -> false
                else -> throw IllegalArgumentException("not a TIFF file")
            }
            val magic = u16(head, 2, le)
            require(magic == 42 || magic == 43) { "not a TIFF file (magic $magic)" }
            // BigTIFF: 8-byte offsets and counts, 20-byte IFD entries with 8 bytes of inline value.
            val big = magic == 43
            if (big) require(u16(head, 4, le) == 8 && u16(head, 6, le) == 0) { "unsupported BigTIFF offset size" }
            val entrySize = if (big) 20 else 12
            val inlineSize = if (big) 8 else 4
            val countSize = if (big) 8 else 2
            fun offsetAt(b: ByteArray, p: Int): Long = if (big) u64(b, p, le) else u32(b, p, le)

            fun bytesAt(offset: Long, length: Int): ByteArray =
                if (offset + length <= head.size) head.copyOfRange(offset.toInt(), offset.toInt() + length)
                else source.read(offset, length)

            fun readIfd(offset: Long): Ifd {
                val count = if (big) u64(bytesAt(offset, 8), 0, le).toInt() else u16(bytesAt(offset, 2), 0, le)
                require(count in 1..4096) { "implausible TIFF IFD entry count $count" }
                val ifdBytes = bytesAt(offset + countSize, count * entrySize + inlineSize)
                val tags = HashMap<Int, Entry>()
                for (i in 0 until count) {
                    val p = i * entrySize
                    val n = if (big) u64(ifdBytes, p + 4, le) else u32(ifdBytes, p + 4, le)
                    require(n in 0..Int.MAX_VALUE.toLong()) { "TIFF tag count $n too large" }
                    val valueAt = p + entrySize - inlineSize
                    tags[u16(ifdBytes, p, le)] = Entry(u16(ifdBytes, p + 2, le), n.toInt(), ifdBytes.copyOfRange(valueAt, valueAt + inlineSize))
                }

                fun raw(tag: Int): Pair<Entry, ByteArray>? {
                    val e = tags[tag] ?: return null
                    val size = typeSize(e.type) * e.count
                    val data = if (size <= inlineSize) e.inline else bytesAt(offsetAt(e.inline, 0), size)
                    return e to data
                }

                fun numbers(tag: Int): DoubleArray? {
                    val (e, d) = raw(tag) ?: return null
                    return DoubleArray(e.count) { i ->
                        when (e.type) {
                            1 -> (d[i].toInt() and 0xFF).toDouble()
                            3 -> u16(d, i * 2, le).toDouble()
                            4 -> u32(d, i * 4, le).toDouble()
                            11 -> Float.fromBits(u32(d, i * 4, le).toInt()).toDouble()
                            12 -> Double.fromBits(u64(d, i * 8, le))
                            16 -> u64(d, i * 8, le).toDouble()
                            else -> throw UnsupportedOperationException("TIFF field type ${e.type} for tag $tag")
                        }
                    }
                }

                fun number(tag: Int, default: Double? = null): Double =
                    numbers(tag)?.first() ?: default ?: throw IllegalArgumentException("missing TIFF tag $tag")

                val bits = number(258, 1.0).toInt()
                val sampleFormat = number(339, 1.0).toInt()
                require(bits == 32 && (sampleFormat == 3 || sampleFormat == 2)) {
                    "only 32-bit float or signed integer samples supported (bits $bits, format $sampleFormat)"
                }
                require(number(277, 1.0).toInt() == 1) { "only single-band TIFFs supported" }
                // Strips are tiles the full width wide (RowsPerStrip, default the whole image, tall).
                val tiled = tags.containsKey(322)
                val width = number(256).toInt()
                val height = number(257).toInt()

                return Ifd(
                    width = width,
                    height = height,
                    tileWidth = if (tiled) number(322).toInt() else width,
                    tileHeight = if (tiled) number(323).toInt() else number(278, height.toDouble()).toInt().coerceAtMost(height),
                    compression = number(259, 1.0).toInt(),
                    predictor = number(317, 1.0).toInt(),
                    subfileType = number(254, 0.0).toInt(),
                    intSamples = sampleFormat == 2,
                    tileOffsets = numbers(if (tiled) 324 else 273)!!.map { it.toLong() }.toLongArray(),
                    tileByteCounts = numbers(if (tiled) 325 else 279)!!.map { it.toLong() }.toLongArray(),
                    numbers = ::numbers,
                    ascii = { tag -> raw(tag)?.second?.decodeToString()?.trimEnd('\u0000')?.trim() },
                    next = offsetAt(ifdBytes, count * entrySize),
                )
            }

            val main = readIfd(if (big) u64(head, 8, le) else u32(head, 4, le))
            val scale = main.numbers(33550) ?: throw IllegalArgumentException("missing ModelPixelScale")
            val tie = main.numbers(33922) ?: throw IllegalArgumentException("missing ModelTiepoint")
            // Tiepoint: raster (i, j) maps to model (x, y).
            val originX = tie[3] - tie[0] * scale[0]
            val originY = tie[4] + tie[1] * scale[1]

            // GeoKeys: 1025 GTRasterType (1 PixelIsArea, 2 PixelIsPoint), 2048 GeographicType, 3072 ProjectedCSType.
            val geoKeys = HashMap<Int, Int>()
            main.numbers(34735)?.let { keys ->
                for (k in 1..keys[3].toInt()) {
                    if (keys[k * 4 + 1].toInt() == 0) geoKeys[keys[k * 4].toInt()] = keys[k * 4 + 3].toInt()
                }
            }
            val pixelIsPoint = geoKeys[1025] == 2
            val epsg = (geoKeys[3072] ?: geoKeys[2048])?.takeIf { it in 1..32766 }
            val noData = main.ascii(42113)?.toFloatOrNull()
            val decompressors = builtInDecompressors + extraDecompressors
            // GDAL keeps integer grids' scale and offset in its metadata XML.
            val gdalMetadata = main.ascii(42112).orEmpty()
            fun gdalItem(name: String): Double? =
                Regex("<Item name=\"$name\"[^>]*>([^<]+)</Item>").find(gdalMetadata)?.groupValues?.get(1)?.trim()?.toDoubleOrNull()
            val intScale = gdalItem("SCALE") ?: 1.0
            val intOffset = gdalItem("OFFSET") ?: 0.0

            fun build(ifd: Ifd, overviews: List<GeoTiff>): GeoTiff {
                // Overviews cover the same extent with proportionally larger pixels.
                val sx = scale[0] * main.width / ifd.width
                val sy = scale[1] * main.height / ifd.height
                val ox = if (pixelIsPoint) originX - scale[0] / 2 + sx / 2 else originX
                val oy = if (pixelIsPoint) originY + scale[1] / 2 - sy / 2 else originY
                return GeoTiff(
                    source, le, ifd.width, ifd.height, ifd.tileWidth, ifd.tileHeight, ifd.compression, ifd.predictor,
                    ifd.tileOffsets, ifd.tileByteCounts, sx, sy, ox, oy, pixelIsPoint, noData, epsg, overviews, decompressors,
                    ifd.intSamples, intScale, intOffset,
                )
            }

            // Follow the IFD chain for overviews: reduced-resolution (bit 0), skipping masks (bit 2).
            val overviews = ArrayList<GeoTiff>()
            var next = main.next
            while (next != 0L) {
                val ifd = readIfd(next)
                if (ifd.subfileType and 1 != 0 && ifd.subfileType and 4 == 0) overviews += build(ifd, emptyList())
                next = ifd.next
            }
            return build(main, overviews.sortedByDescending { it.width })
        }

        /** Horizontal differencing (TIFF predictor 2) on 32-bit samples, in file byte order. */
        internal fun undoHorizontalPredictor32(bytes: ByteArray, width: Int, height: Int, littleEndian: Boolean): FloatArray {
            val out = FloatArray(width * height)
            for (y in 0 until height) {
                var acc = 0
                for (x in 0 until width) {
                    val i = y * width + x
                    acc += u32(bytes, i * 4, littleEndian).toInt()
                    out[i] = Float.fromBits(acc)
                }
            }
            return out
        }

        /**
         * Floating-point predictor (TIFF predictor 3): each row is byte-wise delta coded, with
         * bytes regrouped so all most-significant bytes come first.
         */
        internal fun undoFloatPredictor(bytes: ByteArray, width: Int, height: Int): FloatArray {
            val rowBytes = width * 4
            val out = FloatArray(width * height)
            for (y in 0 until height) {
                val base = y * rowBytes
                for (i in 1 until rowBytes) bytes[base + i] = (bytes[base + i] + bytes[base + i - 1]).toByte()
                for (x in 0 until width) {
                    val b0 = bytes[base + x].toInt() and 0xFF
                    val b1 = bytes[base + width + x].toInt() and 0xFF
                    val b2 = bytes[base + 2 * width + x].toInt() and 0xFF
                    val b3 = bytes[base + 3 * width + x].toInt() and 0xFF
                    out[y * width + x] = Float.fromBits((b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3)
                }
            }
            return out
        }

        private class Entry(val type: Int, val count: Int, val inline: ByteArray)

        private fun typeSize(type: Int) = when (type) {
            1, 2, 6, 7 -> 1
            3, 8 -> 2
            4, 9, 11 -> 4
            5, 10, 12, 16, 17, 18 -> 8
            else -> 1
        }

        private fun u16(b: ByteArray, p: Int, le: Boolean): Int {
            val x = b[p].toInt() and 0xFF
            val y = b[p + 1].toInt() and 0xFF
            return if (le) x or (y shl 8) else (x shl 8) or y
        }

        private fun u32(b: ByteArray, p: Int, le: Boolean): Long {
            var v = 0L
            for (i in 0 until 4) {
                val byte = b[p + i].toLong() and 0xFF
                v = if (le) v or (byte shl (8 * i)) else (v shl 8) or byte
            }
            return v
        }

        private fun u64(b: ByteArray, p: Int, le: Boolean): Long {
            var v = 0L
            for (i in 0 until 8) {
                val byte = b[p + i].toLong() and 0xFF
                v = if (le) v or (byte shl (8 * i)) else (v shl 8) or byte
            }
            return v
        }
    }
}
