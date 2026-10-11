package io.github.jasondorjeshort.dystopo.sources.elevation

import io.github.jasondorjeshort.dystopo.dem.Datums
import io.github.jasondorjeshort.dystopo.dem.ElevationGrid
import io.github.jasondorjeshort.dystopo.geo.MercatorWindow
import io.github.jasondorjeshort.dystopo.geo.Utm
import io.github.jasondorjeshort.dystopo.tiff.Decompressor
import kotlin.math.floor

/**
 * LINZ's New Zealand LiDAR 1m DEM: the national bare-earth mosaic of every LINZ lidar survey,
 * NZVD2016 heights on NZTM2000 (EPSG:2193), CC BY 4.0, published on the AWS open data registry
 * (`s3://nz-elevation`) as one Cloud-Optimized GeoTIFF per NZTopo50 sheet (24 x 36 km, 512 px
 * tiles, overviews, sparse where no lidar was flown).
 *
 * The tiles are LERC-compressed (TIFF compression 34887, 1 mm maximum error), which [Lerc2]
 * decodes. A window reads only the blocks it needs, from the overview level that matches its cells.
 * Output pixels are projected with [nztm] and sampled bilinearly; heights are converted
 * NZVD2016 -> EGM2008 with LINZ's NZGeoid2016 and EGM2008.
 */
class NewZealandSource(private val cacheDir: String = SourceIo.dir("linz-dem")) : ElevationSource {
    override val id = "linz"
    override val registryIds = listOf("linz-dem", Datums.NEW_ZEALAND_GEOID.registryId, "egm2008")

    override fun fetch(window: MercatorWindow): ElevationSource.Result {
        val box = NationalDem.bounds(window)
        if (!NationalDem.intersects(box, COVERAGE)) return NationalDem.nanResult(window, mapOf("outsideCoverage" to 1L))
        // Read on this thread: tile exports override it per thread (see OverviewLevels).
        val targetMeters = OverviewLevels.targetMeters(window)
        if (!NationalDem.useful(window)) return NationalDem.nanResult(window, mapOf("skippedCoarse" to 1L))

        val w = window.width
        val h = window.height
        val xs = DoubleArray(w * h)
        val ys = DoubleArray(w * h)
        val lons = DoubleArray(w) { window.lonAt(it) }
        for (j in 0 until h) {
            val lat = window.latAt(j)
            for (i in 0 until w) {
                val p = nztm(lat, lons[i])
                xs[j * w + i] = p.easting
                ys[j * w + i] = p.northing
            }
        }

        class Need(var minX: Double, var minY: Double, var maxX: Double, var maxY: Double)
        val needs = HashMap<String, Need>()
        for (k in xs.indices) {
            val s = sheetOf(xs[k], ys[k]) ?: continue
            val need = needs.getOrPut(s) { Need(xs[k], ys[k], xs[k], ys[k]) }
            if (xs[k] < need.minX) need.minX = xs[k]
            if (xs[k] > need.maxX) need.maxX = xs[k]
            if (ys[k] < need.minY) need.minY = ys[k]
            if (ys[k] > need.maxY) need.maxY = ys[k]
        }
        if (needs.isEmpty()) return NationalDem.nanResult(window, mapOf("outsideSheets" to 1L))

        val readStarted = nanoTime()
        val opened = io.map(needs.keys) { s -> s to open(s) }.toMap()
        val levels = opened.mapValues { (_, tiff) -> tiff.levelFor(targetMeters, 1.0) }
        val blocks = ArrayList<() -> Unit>()
        for ((s, need) in needs) {
            val tiff = opened.getValue(s)
            val level = levels.getValue(s)
            val l = tiff.level(level)
            val c0 = (floor(l.colOf(need.minX)).toInt() - 1).coerceIn(0, l.width - 1)
            val c1 = (floor(l.colOf(need.maxX)).toInt() + 1).coerceIn(0, l.width - 1)
            val r0 = (floor(l.rowOf(need.maxY)).toInt() - 1).coerceIn(0, l.height - 1)
            val r1 = (floor(l.rowOf(need.minY)).toInt() + 1).coerceIn(0, l.height - 1)
            for (ty in r0 / l.tileHeight..r1 / l.tileHeight) for (tx in c0 / l.tileWidth..c1 / l.tileWidth) {
                blocks += { tiff.at(level, tx * l.tileWidth, ty * l.tileHeight) }
            }
        }
        io.map(blocks) { it() }
        val readMs = (nanoTime() - readStarted) / 1_000_000

        val values = FloatArray(w * h) { Float.NaN }
        for (k in xs.indices) {
            val s = sheetOf(xs[k], ys[k]) ?: continue
            val tiff = opened[s] ?: continue
            values[k] = tiff.sample(levels.getValue(s), xs[k], ys[k])
        }

        // NZVD2016 -> EGM2008.
        val any = values.any { !it.isNaN() }
        val (meanOffset, converted, unconverted) =
            if (any) NationalDem.applyOffsets(values, NationalDem.offsets(window, Datums.NEW_ZEALAND_GEOID)) else Triple(0.0, 0L, 0L)

        val grid = ElevationGrid(w, h, window.cellSizeMeters, values)
        val counters = LinkedHashMap<String, Long>()
        counters["sheetsUsed"] = opened.size.toLong()
        counters["overviewLevelMax"] = (levels.values.maxOrNull() ?: 0).toLong()
        counters["pixelMeters"] = opened.entries.maxOfOrNull { (s, tiff) -> tiff.level(levels.getValue(s)).pixelScaleX }?.toLong() ?: 0L
        counters["blocksDecoded"] = opened.values.sumOf { it.tilesDecoded }.toLong()
        counters["rangeRequests"] = opened.values.sumOf { it.source.fetches }
        counters["readMs"] = readMs
        counters["datumOffsetMeanCm"] = roundL(meanOffset * 100)
        counters["datumUnconvertedCells"] = unconverted
        counters["coveragePermille"] = (grid.values.count { !it.isNaN() } * 1000L) / grid.values.size
        counters["noDataCells"] = grid.values.count { it.isNaN() }.toLong()
        val ids = if (converted > 0) registryIds else emptyList()
        return ElevationSource.Result(grid, counters, ElevationSource.provenanceIfData(grid, ids))
    }

    private fun open(sheet: String): RemoteTiff = RemoteTiff.open(sheetUrl(sheet), "$cacheDir/sheets/$sheet", mapOf(LERC to lercDecompressor))

    companion object {
        const val BASE = "https://nz-elevation.s3.ap-southeast-2.amazonaws.com/new-zealand/new-zealand/dem_1m/2193"

        /** TIFF compression code for LERC. */
        const val LERC = 34887

        /** The North, South and Stewart Islands with margin: west, south, east, north. */
        val COVERAGE = doubleArrayOf(166.3, -47.4, 178.6, -34.3)

        /** NZTopo50 sheet edges in NZTM metres, and the grid origin (column 0 west edge, row 0 north edge). */
        const val SHEET_WIDTH = 24_000
        const val SHEET_HEIGHT = 36_000
        const val ORIGIN_EASTING = 988_000
        const val ORIGIN_NORTHING = 6_810_000

        /** Sheet row letters: the alphabet without I and O. */
        private const val LETTERS = "ABCDEFGHJKLMNPQRSTUVWXYZ"

        /**
         * The sheets of the mosaic, listed from the bucket (the `.tiff` files under `dem_1m/2193/`, 424 sheets, September
         * 2026). Sheets without any lidar are absent; a new survey can add one, which needs a line here.
         */
        val SHEETS: Set<String> = (
            "AS21 AS22 AT24 AT25 AU25 AU26 AU27 AU28 AU29 AV25 AV26 AV27 AV28 AV29 AV30 AW26 AW27 AW28 AW29 AW30 AW31 AW32 " +
                "AX27 AX28 AX29 AX30 AX31 AX32 AY28 AY29 AY30 AY31 AY32 AY33 AY34 AZ29 AZ30 AZ31 AZ32 AZ34 AZ35 BA30 BA31 BA32 " +
                "BA33 BA34 BA35 BA36 BB30 BB31 BB32 BB33 BB34 BB35 BB36 BB37 BC31 BC32 BC33 BC34 BC35 BC36 BC37 BC40 BD31 BD32 " +
                "BD33 BD34 BD35 BD36 BD37 BD38 BD39 BD40 BD42 BD43 BD44 BD45 BD46 BE31 BE32 BE33 BE34 BE35 BE36 BE37 BE38 BE39 " +
                "BE40 BE41 BE42 BE43 BE44 BE45 BF31 BF32 BF33 BF34 BF35 BF36 BF37 BF38 BF39 BF40 BF41 BF42 BF43 BF44 BF45 BG30 " +
                "BG31 BG32 BG33 BG34 BG35 BG36 BG37 BG38 BG39 BG40 BG41 BG42 BG43 BG44 BH28 BH29 BH30 BH31 BH32 BH33 BH34 BH35 " +
                "BH36 BH37 BH38 BH39 BH40 BH41 BH42 BH43 BJ28 BJ29 BJ30 BJ31 BJ32 BJ33 BJ34 BJ35 BJ36 BJ37 BJ38 BJ39 BJ40 BJ42 " +
                "BJ43 BK28 BK29 BK30 BK31 BK32 BK33 BK34 BK35 BK36 BK37 BK38 BK39 BK40 BL31 BL32 BL33 BL34 BL35 BL36 BL37 BL38 " +
                "BL39 BM24 BM25 BM33 BM34 BM35 BM36 BM37 BM38 BM39 BN22 BN23 BN24 BN25 BN28 BN29 BN32 BN33 BN34 BN35 BN36 BN37 " +
                "BN38 BP22 BP23 BP24 BP25 BP26 BP27 BP28 BP29 BP30 BP31 BP32 BP33 BP34 BP35 BP36 BQ21 BQ22 BQ23 BQ24 BQ25 BQ26 " +
                "BQ27 BQ28 BQ29 BQ30 BQ31 BQ32 BQ33 BQ34 BQ35 BQ36 BR20 BR21 BR22 BR23 BR24 BR25 BR26 BR27 BR28 BR29 BR33 BR34 " +
                "BS19 BS20 BS21 BS22 BS23 BS24 BS25 BS26 BS27 BS28 BS29 BT19 BT20 BT21 BT22 BT23 BT24 BT25 BT26 BT27 BT28 BU18 " +
                "BU19 BU20 BU21 BU22 BU23 BU24 BU25 BU26 BU27 BV16 BV17 BV18 BV19 BV20 BV21 BV22 BV23 BV24 BV25 BV26 BW14 BW15 " +
                "BW16 BW17 BW18 BW19 BW20 BW21 BW22 BW23 BW24 BW25 BX12 BX13 BX14 BX15 BX16 BX17 BX18 BX19 BX20 BX21 BX22 BX23 " +
                "BX24 BX25 BY10 BY11 BY12 BY13 BY14 BY15 BY16 BY17 BY18 BY19 BY20 BY21 BY22 BY23 BY24 BY25 BZ09 BZ10 BZ11 BZ12 " +
                "BZ13 BZ14 BZ15 BZ16 BZ17 BZ18 BZ19 BZ20 BZ21 CA10 CA11 CA12 CA13 CA14 CA15 CA16 CA17 CA18 CA19 CB08 CB09 CB11 " +
                "CB12 CB13 CB14 CB15 CB16 CB17 CB18 CB19 CC07 CC08 CC09 CC10 CC11 CC12 CC13 CC14 CC15 CC16 CC17 CC18 CC19 CD06 " +
                "CD07 CD08 CD09 CD10 CD11 CD12 CD13 CD14 CD15 CD16 CD17 CD18 CE06 CE07 CE08 CE09 CE10 CE11 CE12 CE13 CE14 CE15 " +
                "CE16 CE17 CE18 CF07 CF08 CF09 CF10 CF11 CF12 CF13 CF14 CF15 CF16 CG08 CG09 CG10 CG11 CG12 CG13 CG14 CG15 CH09 " +
                "CH10 CH11 CH12 CH13 CJ09 CJ10"
            ).split(' ').toSet()

        private val io = NationalDem.ioPool("linz-io")

        private val lercDecompressor: Decompressor = { input, size -> Lerc2.decodeToFloatBytes(input, size) }

        /**
         * NZTM2000 (EPSG:2193) from latitude and longitude. NZTM is a transverse Mercator on GRS80 with
         * UTM's scale (0.9996) and southern false northing (10 000 km), central meridian 173 E and false
         * easting 1600 km; UTM zone 59 has central meridian 171 E, so shifting the longitude by 2 degrees and
         * the easting by 1100 km gives NZTM exactly.
         */
        fun nztm(lat: Double, lon: Double): Utm.Point {
            val p = Utm.forward(lat, lon - 2.0, 59, north = false)
            return Utm.Point(p.easting + 1_100_000.0, p.northing)
        }

        /** NZTopo50 sheet code (e.g. BX15) of NZTM point ([x], [y]), whether or not the mosaic has it. */
        fun sheetCode(x: Double, y: Double): String? {
            val col = floor((x - ORIGIN_EASTING) / SHEET_WIDTH).toInt()
            val row = floor((ORIGIN_NORTHING - y) / SHEET_HEIGHT).toInt()
            if (col !in 0..99 || row !in 0 until LETTERS.length * LETTERS.length) return null
            return "${LETTERS[row / LETTERS.length]}${LETTERS[row % LETTERS.length]}${col.toString().padStart(2, '0')}"
        }

        /** The mosaic sheet holding NZTM point ([x], [y]), or null where LINZ has none. */
        fun sheetOf(x: Double, y: Double): String? = sheetCode(x, y)?.takeIf { it in SHEETS }

        fun sheetUrl(sheet: String): String = "$BASE/$sheet.tiff"
    }
}

/**
 * A decoder for LERC2 blobs (Esri's Limited Error Raster Compression, versions 3 to 5 of the blob
 * format, one band, one value per pixel), as GDAL writes them into TIFF tiles. Written from the
 * format as documented by Esri's open-source LERC project: a header, a run-length coded validity
 * mask, then either all valid values raw or the image in micro blocks, each constant, raw, or an
 * offset plus bit-stuffed integer multiples of twice the maximum error (optionally through a lookup
 * table). Only what float elevation tiles need is supported: one depth, no Huffman coding (that is
 * used for 8-bit data only).
 */
object Lerc2 {
    class Image(val width: Int, val height: Int, val values: FloatArray)

    private const val KEY = "Lerc2 "

    /** Decodes [blob] into [expectedSize] bytes of little-endian float32 (invalid pixels NaN), padded with NaN. */
    fun decodeToFloatBytes(blob: ByteArray, expectedSize: Int): ByteArray {
        val image = decode(blob)
        val out = ByteArray(expectedSize)
        val n = expectedSize / 4
        for (k in 0 until n) {
            val bits = (if (k < image.values.size) image.values[k] else Float.NaN).toRawBits()
            out[k * 4] = bits.toByte()
            out[k * 4 + 1] = (bits ushr 8).toByte()
            out[k * 4 + 2] = (bits ushr 16).toByte()
            out[k * 4 + 3] = (bits ushr 24).toByte()
        }
        return out
    }

    private class Reader(val b: ByteArray, var pos: Int, val end: Int) {
        fun need(n: Int) = require(pos + n <= end) { "LERC blob truncated at byte $pos" }
        fun u8(): Int { need(1); return b[pos++].toInt() and 0xFF }
        fun i16(): Int { need(2); val v = (b[pos].toInt() and 0xFF) or (b[pos + 1].toInt() shl 8); pos += 2; return v.toShort().toInt() }
        fun u16(): Int = i16() and 0xFFFF
        fun i32(): Int {
            need(4)
            val v = (b[pos].toInt() and 0xFF) or ((b[pos + 1].toInt() and 0xFF) shl 8) or
                ((b[pos + 2].toInt() and 0xFF) shl 16) or (b[pos + 3].toInt() shl 24)
            pos += 4
            return v
        }
        fun i64(): Long = (i32().toLong() and 0xFFFFFFFFL) or (i32().toLong() shl 32)
        fun f32(): Float = Float.fromBits(i32())
        fun f64(): Double = Double.fromBits(i64())

        /** One value of LERC data type [dt] (0 char, 1 byte, 2 short, 3 ushort, 4 int, 5 uint, 6 float, 7 double). */
        fun value(dt: Int): Double = when (dt) {
            0 -> u8().toByte().toDouble()
            1 -> u8().toDouble()
            2 -> i16().toDouble()
            3 -> u16().toDouble()
            4 -> i32().toDouble()
            5 -> (i32().toLong() and 0xFFFFFFFFL).toDouble()
            6 -> f32().toDouble()
            7 -> f64()
            else -> throw IllegalArgumentException("LERC data type $dt")
        }
    }

    /** The narrower type an offset is stored in, from the 2 high bits of a block's flag byte. */
    private fun typeUsed(dt: Int, tc: Int): Int = when (dt) {
        2, 4 -> dt - tc
        3, 5 -> dt - 2 * tc
        6 -> if (tc == 0) 6 else if (tc == 1) 2 else 1
        7 -> if (tc == 0) 7 else dt - 2 * tc + 1
        else -> dt
    }

    /** Fletcher-32 as LERC computes it (big-endian 16-bit words, odd tail byte as the high byte). */
    fun fletcher32(b: ByteArray, from: Int, to: Int): Int {
        var sum1 = 0xFFFFL
        var sum2 = 0xFFFFL
        var p = from
        var words = (to - from) / 2
        while (words > 0) {
            var t = minOf(words, 359)
            words -= t
            while (t-- > 0) {
                sum1 += (b[p].toInt() and 0xFF) shl 8
                sum1 += b[p + 1].toInt() and 0xFF
                sum2 += sum1
                p += 2
            }
            sum1 = (sum1 and 0xFFFF) + (sum1 shr 16)
            sum2 = (sum2 and 0xFFFF) + (sum2 shr 16)
        }
        if ((to - from) and 1 == 1) {
            sum1 += (b[p].toInt() and 0xFF) shl 8
            sum2 += sum1
        }
        sum1 = (sum1 and 0xFFFF) + (sum1 shr 16)
        sum2 = (sum2 and 0xFFFF) + (sum2 shr 16)
        return ((sum2 shl 16) or sum1).toInt()
    }

    fun decode(blob: ByteArray): Image {
        val r = Reader(blob, 0, blob.size)
        require(blob.size >= 14 && blob.decodeToString(0, 6) == KEY) { "not a LERC2 blob" }
        r.pos = 6
        val version = r.i32()
        require(version in 3..5) { "LERC2 version $version not supported" }
        val checksum = r.i32()
        val height = r.i32()
        val width = r.i32()
        val depth = if (version >= 4) r.i32() else 1
        val numValid = r.i32()
        val microBlock = r.i32()
        val blobSize = r.i32()
        val dt = r.i32()
        val maxZError = r.f64()
        val zMin = r.f64()
        val zMax = r.f64()
        require(depth == 1) { "LERC2 with $depth values per pixel not supported" }
        require(width > 0 && height > 0 && microBlock > 0 && blobSize <= blob.size) { "bad LERC2 header" }
        require(fletcher32(blob, 14, blobSize) == checksum) { "LERC2 checksum mismatch" }
        val body = Reader(blob, r.pos, blobSize)
        val count = width * height
        val values = FloatArray(count) { Float.NaN }

        // Validity mask: none stored means all valid (or none, when numValid is 0).
        val maskBytes = body.i32()
        val valid: BooleanArray = when {
            numValid == 0 -> BooleanArray(count)
            maskBytes == 0 -> {
                require(numValid == count) { "LERC2 mask missing" }
                BooleanArray(count) { true }
            }
            else -> {
                val bits = ByteArray((count + 7) / 8)
                val endMask = body.pos + maskBytes
                var o = 0
                while (true) {
                    val n = body.i16()
                    if (n == -32768) break
                    if (n > 0) {
                        repeat(n) { bits[o++] = body.u8().toByte() }
                    } else {
                        val v = body.u8().toByte()
                        repeat(-n) { bits[o++] = v }
                    }
                }
                body.pos = endMask
                BooleanArray(count) { k -> (bits[k shr 3].toInt() and (0x80 ushr (k and 7))) != 0 }
            }
        }
        if (numValid == 0) return Image(width, height, values)
        if (zMin == zMax) {
            for (k in 0 until count) if (valid[k]) values[k] = zMin.toFloat()
            return Image(width, height, values)
        }
        var bandMax = zMax
        if (version >= 4) {
            val lo = body.value(dt)
            val hi = body.value(dt)
            bandMax = hi
            if (lo == hi) {
                for (k in 0 until count) if (valid[k]) values[k] = lo.toFloat()
                return Image(width, height, values)
            }
        }
        val oneSweep = body.u8()
        if (oneSweep != 0) {
            for (k in 0 until count) if (valid[k]) values[k] = body.value(dt).toFloat()
            return Image(width, height, values)
        }
        require(!(dt <= 1 && maxZError == 0.5)) { "LERC2 Huffman-coded 8-bit data not supported" }

        val scale = 2 * maxZError
        val buffer = IntArray(microBlock * microBlock)
        val blocksDown = (height + microBlock - 1) / microBlock
        val blocksAcross = (width + microBlock - 1) / microBlock
        for (bi in 0 until blocksDown) {
            val i0 = bi * microBlock
            val i1 = minOf(i0 + microBlock, height)
            for (bj in 0 until blocksAcross) {
                val j0 = bj * microBlock
                val j1 = minOf(j0 + microBlock, width)
                val flag = body.u8()
                val bits67 = flag shr 6
                if (version >= 5) {
                    require((flag shr 3) and 7 == (j0 shr 3) and 7) { "LERC2 block check failed at row $i0 col $j0" }
                    require(flag and 4 == 0) { "LERC2 depth difference coding not supported" }
                } else {
                    require((flag shr 2) and 15 == (j0 shr 3) and 15) { "LERC2 block check failed at row $i0 col $j0" }
                }
                when (flag and 3) {
                    2 -> for (i in i0 until i1) for (j in j0 until j1) if (valid[i * width + j]) values[i * width + j] = 0f
                    0 -> for (i in i0 until i1) for (j in j0 until j1) if (valid[i * width + j]) values[i * width + j] = body.value(dt).toFloat()
                    else -> {
                        val offset = body.value(typeUsed(dt, bits67))
                        if (flag and 3 == 3) {
                            for (i in i0 until i1) for (j in j0 until j1) if (valid[i * width + j]) values[i * width + j] = offset.toFloat()
                        } else {
                            val n = unstuffBlock(body, buffer)
                            var m = 0
                            for (i in i0 until i1) for (j in j0 until j1) {
                                val k = i * width + j
                                if (!valid[k]) continue
                                require(m < n) { "LERC2 block has fewer values than valid pixels" }
                                values[k] = minOf(offset + buffer[m++] * scale, bandMax).toFloat()
                            }
                        }
                    }
                }
            }
        }
        return Image(width, height, values)
    }

    /** Reads one bit-stuffed array into [out]; returns its length. */
    private fun unstuffBlock(r: Reader, out: IntArray): Int {
        val head = r.u8()
        val bits67 = head shr 6
        val countBytes = if (bits67 == 0) 4 else 3 - bits67
        val useLut = head and 32 != 0
        val numBits = head and 31
        val n = when (countBytes) {
            1 -> r.u8()
            2 -> r.u16()
            else -> r.i32()
        }
        require(n in 0..out.size) { "LERC2 block of $n values" }
        if (!useLut) {
            if (numBits == 0) out.fill(0, 0, n) else unstuff(r, out, n, numBits)
            return n
        }
        val lutSize = r.u8() - 1
        require(lutSize in 1..255) { "LERC2 lookup table of $lutSize" }
        val lut = IntArray(lutSize + 1)
        val raw = IntArray(lutSize)
        unstuff(r, raw, lutSize, numBits)
        raw.copyInto(lut, 1)
        var lutBits = 0
        while (lutSize shr lutBits != 0) lutBits++
        unstuff(r, out, n, lutBits)
        for (i in 0 until n) out[i] = lut[out[i]]
        return n
    }

    /**
     * [n] unsigned integers of [numBits] bits each, packed into little-endian 32-bit words, the last
     * word stored only as far as it holds bits; values fill each word from its low bits up (the
     * layout from blob version 3 on).
     */
    private fun unstuff(r: Reader, out: IntArray, n: Int, numBits: Int) {
        val totalBits = n.toLong() * numBits
        val words = ((totalBits + 31) / 32).toInt()
        val tailBits = (totalBits and 31).toInt()
        val tailBytes = (tailBits + 7) shr 3
        val notStored = if (tailBytes > 0) 4 - tailBytes else 0
        val stored = words * 4 - notStored
        r.need(stored)
        fun word(w: Int): Long {
            var v = 0L
            for (k in 0 until 4) {
                val idx = w * 4 + k
                val byte = if (idx < stored) (r.b[r.pos + idx].toLong() and 0xFF) else 0L
                v = v or (byte shl (8 * k))
            }
            return v
        }
        val mask = (1L shl numBits) - 1
        for (i in 0 until n) {
            val bit = i.toLong() * numBits
            val w = (bit shr 5).toInt()
            val s = (bit and 31).toInt()
            var v = word(w) ushr s
            if (s + numBits > 32) v = v or (word(w + 1) shl (32 - s))
            out[i] = (v and mask).toInt()
        }
        r.pos += stored
    }
}
