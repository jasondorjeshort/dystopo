package io.github.jasondorjeshort.dystopo.app.share

/**
 * T57: reads the text of a sampled QR code matrix (`dark[y][x]`, no quiet zone), the second half of the
 * in-app scanner ([QrDecoder] finds and samples the code in a camera frame). Pure Kotlin, written from
 * ISO/IEC 18004 like the encoder ([QrCode]): format information (BCH(15,5), the closest of the 32 codes
 * within 3 bits, from either copy), version information for versions 7 and up (BCH(18,6)), the function
 * patterns, unmasking, the zigzag codeword order, de-interleaving, Reed-Solomon error correction over
 * GF(256) (Berlekamp-Massey, Chien search, Forney), and the numeric, alphanumeric and byte modes (byte
 * data as UTF-8; ECI headers skipped). Versions 1 to 10 at every error correction level.
 */
object QrMatrixDecoder {
    class Result(val text: String, val version: Int, val level: Char, val mask: Int, val correctedErrors: Int)

    class DecodeException(message: String) : Exception(message)

    const val MAX_VERSION = 10

    /** Decodes [dark] (size 17 + 4v); throws [DecodeException] when it cannot. */
    fun decode(dark: Array<BooleanArray>): Result {
        val size = dark.size
        if (size < 21 || (size - 17) % 4 != 0 || dark.any { it.size != size }) throw DecodeException("not a QR matrix size: $size")
        var version = (size - 17) / 4
        if (version >= 7) version = readVersion(dark) ?: version
        if (version > MAX_VERSION || 17 + 4 * version != size) throw DecodeException("version $version is not supported (1 to $MAX_VERSION)")
        val (level, mask) = readFormat(dark) ?: throw DecodeException("unreadable format information")
        val function = functionMap(version)
        val codewords = readCodewords(dark, function, mask, size)
        val (data, corrected) = correct(codewords, version, level)
        return Result(parseData(data, version), version, level, mask, corrected)
    }

    // ---- Format and version information ----

    private val LEVEL_BITS = mapOf(1 to 'L', 0 to 'M', 3 to 'Q', 2 to 'H')

    /** The 32 valid format codes: (level bits << 3 | mask) BCH-coded and XOR-masked, as the encoder writes them. */
    private val FORMAT_CODES = IntArray(32) { data ->
        var rem = data
        repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
        ((data shl 10) or rem) xor 0x5412
    }

    /** (level, mask) from the closer of the two format copies, or null when neither is within 3 bits of a code. */
    fun readFormat(m: Array<BooleanArray>): Pair<Char, Int>? {
        val size = m.size
        var a = 0
        var b = 0
        fun bitAt(x: Int, y: Int) = if (m[y][x]) 1 else 0
        for (i in 0..14) {
            val (x1, y1) = when {
                i <= 5 -> 8 to i
                i == 6 -> 8 to 7
                i == 7 -> 8 to 8
                i == 8 -> 7 to 8
                else -> (14 - i) to 8
            }
            a = a or (bitAt(x1, y1) shl i)
            val (x2, y2) = if (i <= 7) (size - 1 - i) to 8 else 8 to (size - 15 + i)
            b = b or (bitAt(x2, y2) shl i)
        }
        var best = -1
        var bestDist = 99
        for (data in 0 until 32) for (word in intArrayOf(a, b)) {
            val d = Integer.bitCount(word xor FORMAT_CODES[data])
            if (d < bestDist) {
                bestDist = d
                best = data
            }
        }
        if (bestDist > 3) return null
        return LEVEL_BITS.getValue(best ushr 3) to (best and 7)
    }

    private fun versionCode(v: Int): Int {
        var rem = v
        repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25) }
        return (v shl 12) or rem
    }

    /** The version from either version block (versions 7 and up), or null when neither is within 3 bits. */
    fun readVersion(m: Array<BooleanArray>): Int? {
        val size = m.size
        var a = 0
        var b = 0
        for (i in 0 until 18) {
            if (m[size - 11 + i % 3][i / 3]) a = a or (1 shl i) // bottom left: 6 columns by 3 rows
            if (m[i / 3][size - 11 + i % 3]) b = b or (1 shl i) // top right: 3 columns by 6 rows
        }
        var best: Int? = null
        var bestDist = 99
        for (v in 7..40) for (word in intArrayOf(a, b)) {
            val d = Integer.bitCount(word xor versionCode(v))
            if (d < bestDist) {
                bestDist = d
                best = v
            }
        }
        return best.takeIf { bestDist <= 3 }
    }

    // ---- Function patterns and data modules ----

    /** Alignment pattern centre coordinates by version (index 0 unused). */
    private val ALIGN = arrayOf(
        intArrayOf(), intArrayOf(), intArrayOf(6, 18), intArrayOf(6, 22), intArrayOf(6, 26), intArrayOf(6, 30), intArrayOf(6, 34),
        intArrayOf(6, 22, 38), intArrayOf(6, 24, 42), intArrayOf(6, 26, 46), intArrayOf(6, 28, 50),
    )

    fun alignmentCentres(version: Int): IntArray = ALIGN[version]

    /** True for modules that are not data: finders and separators, timing, alignment, format and version areas. */
    fun functionMap(version: Int): Array<BooleanArray> {
        val size = 17 + 4 * version
        val f = Array(size) { BooleanArray(size) }
        fun rect(x0: Int, y0: Int, w: Int, h: Int) {
            for (y in y0 until y0 + h) for (x in x0 until x0 + w) if (x in 0 until size && y in 0 until size) f[y][x] = true
        }
        rect(0, 0, 9, 9) // finder, separator and format bits, top left
        rect(size - 8, 0, 8, 9)
        rect(0, size - 8, 9, 8)
        rect(6, 0, 1, size)
        rect(0, 6, size, 1)
        val a = ALIGN[version]
        for (cy in a) for (cx in a) {
            if ((cx == 6 && cy == 6) || (cx == 6 && cy == a.last()) || (cx == a.last() && cy == 6)) continue
            rect(cx - 2, cy - 2, 5, 5)
        }
        if (version >= 7) {
            rect(size - 11, 0, 3, 6)
            rect(0, size - 11, 6, 3)
        }
        return f
    }

    private fun masked(mask: Int, x: Int, y: Int): Boolean = when (mask) {
        0 -> (x + y) % 2 == 0
        1 -> y % 2 == 0
        2 -> x % 3 == 0
        3 -> (x + y) % 3 == 0
        4 -> (x / 3 + y / 2) % 2 == 0
        5 -> x * y % 2 + x * y % 3 == 0
        6 -> (x * y % 2 + x * y % 3) % 2 == 0
        else -> ((x + y) % 2 + x * y % 3) % 2 == 0
    }

    /** The codewords in placement order: two-module columns from the bottom right, zigzagging, unmasked. */
    private fun readCodewords(m: Array<BooleanArray>, function: Array<BooleanArray>, mask: Int, size: Int): IntArray {
        val bits = ArrayList<Boolean>(size * size)
        var right = size - 1
        while (right >= 1) {
            if (right == 6) right = 5
            val upward = ((right + 1) and 2) == 0
            for (vert in 0 until size) {
                val y = if (upward) size - 1 - vert else vert
                for (j in 0..1) {
                    val x = right - j
                    if (!function[y][x]) bits.add(m[y][x] xor masked(mask, x, y))
                }
            }
            right -= 2
        }
        return IntArray(bits.size / 8) { i -> (0 until 8).fold(0) { acc, b -> (acc shl 1) or (if (bits[i * 8 + b]) 1 else 0) } }
    }

    // ---- Error correction ----

    /** EC codewords per block and the block groups (count, data codewords), by version and level. */
    private class Ec(val ecPerBlock: Int, vararg val groups: Pair<Int, Int>)

    private val EC_TABLE: Map<Char, Array<Ec>> = mapOf(
        'L' to arrayOf(
            Ec(0), Ec(7, 1 to 19), Ec(10, 1 to 34), Ec(15, 1 to 55), Ec(20, 1 to 80), Ec(26, 1 to 108),
            Ec(18, 2 to 68), Ec(20, 2 to 78), Ec(24, 2 to 97), Ec(30, 2 to 116), Ec(18, 2 to 68, 2 to 69),
        ),
        'M' to arrayOf(
            Ec(0), Ec(10, 1 to 16), Ec(16, 1 to 28), Ec(26, 1 to 44), Ec(18, 2 to 32), Ec(24, 2 to 43),
            Ec(16, 4 to 27), Ec(18, 4 to 31), Ec(22, 2 to 38, 2 to 39), Ec(22, 3 to 36, 2 to 37), Ec(26, 4 to 43, 1 to 44),
        ),
        'Q' to arrayOf(
            Ec(0), Ec(13, 1 to 13), Ec(22, 1 to 22), Ec(18, 2 to 17), Ec(26, 2 to 24), Ec(18, 2 to 15, 2 to 16),
            Ec(24, 4 to 19), Ec(18, 2 to 14, 4 to 15), Ec(22, 4 to 18, 2 to 19), Ec(20, 4 to 16, 4 to 17), Ec(24, 6 to 19, 2 to 20),
        ),
        'H' to arrayOf(
            Ec(0), Ec(17, 1 to 9), Ec(28, 1 to 16), Ec(22, 2 to 13), Ec(16, 4 to 9), Ec(22, 2 to 11, 2 to 12),
            Ec(28, 4 to 15), Ec(26, 4 to 13, 1 to 14), Ec(26, 4 to 14, 2 to 15), Ec(24, 4 to 12, 4 to 13), Ec(28, 6 to 15, 2 to 16),
        ),
    )

    /** De-interleaves the blocks, corrects each, and returns the data codewords and the number of corrected codewords. */
    private fun correct(codewords: IntArray, version: Int, level: Char): Pair<IntArray, Int> {
        val ec = EC_TABLE.getValue(level)[version]
        val dataLens = ec.groups.flatMap { (n, len) -> List(n) { len } }
        val total = dataLens.sum() + dataLens.size * ec.ecPerBlock
        if (codewords.size < total) throw DecodeException("${codewords.size} codewords, need $total")
        val blocks = dataLens.map { IntArray(it + ec.ecPerBlock) }
        var k = 0
        for (i in 0 until dataLens.max()) for ((b, len) in dataLens.withIndex()) if (i < len) blocks[b][i] = codewords[k++]
        for (i in 0 until ec.ecPerBlock) for ((b, len) in dataLens.withIndex()) blocks[b][len + i] = codewords[k++]
        var corrected = 0
        val out = ArrayList<Int>(dataLens.sum())
        for ((b, len) in dataLens.withIndex()) {
            corrected += ReedSolomon.correct(blocks[b], ec.ecPerBlock)
            for (i in 0 until len) out.add(blocks[b][i])
        }
        return out.toIntArray() to corrected
    }

    // ---- Data segments ----

    private const val ALNUM = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:"

    private fun parseData(data: IntArray, version: Int): String {
        var pos = 0
        val totalBits = data.size * 8
        fun read(n: Int): Int {
            if (pos + n > totalBits) throw DecodeException("data ends inside a segment")
            var v = 0
            repeat(n) {
                v = (v shl 1) or ((data[pos ushr 3] ushr (7 - (pos and 7))) and 1)
                pos++
            }
            return v
        }
        val small = version <= 9
        val bytes = java.io.ByteArrayOutputStream()
        val text = StringBuilder()
        fun flushBytes() {
            if (bytes.size() > 0) {
                text.append(bytes.toByteArray().decodeToString())
                bytes.reset()
            }
        }
        while (totalBits - pos >= 4) {
            when (val mode = read(4)) {
                0 -> break // terminator
                1 -> { // numeric
                    flushBytes()
                    var n = read(if (small) 10 else 12)
                    while (n >= 3) { text.append(read(10).toString().padStart(3, '0')); n -= 3 }
                    if (n == 2) text.append(read(7).toString().padStart(2, '0')) else if (n == 1) text.append(read(4).toString())
                }
                2 -> { // alphanumeric
                    flushBytes()
                    var n = read(if (small) 9 else 11)
                    while (n >= 2) {
                        val v = read(11)
                        if (v >= 45 * 45) throw DecodeException("bad alphanumeric pair")
                        text.append(ALNUM[v / 45]).append(ALNUM[v % 45])
                        n -= 2
                    }
                    if (n == 1) text.append(ALNUM[read(6).also { if (it >= 45) throw DecodeException("bad alphanumeric character") }])
                }
                4 -> { // byte
                    val n = read(if (small) 8 else 16)
                    repeat(n) { bytes.write(read(8)) }
                }
                7 -> { // ECI: designator of 1, 2 or 3 bytes; the text is read as UTF-8 regardless
                    val first = read(8)
                    if (first and 0x80 != 0) read(if (first and 0x40 == 0) 8 else 16)
                }
                else -> throw DecodeException("unsupported segment mode $mode")
            }
        }
        flushBytes()
        return text.toString()
    }
}

/** Reed-Solomon decoding over GF(256) with the QR polynomial 0x11D and generator roots a^0 .. a^(ec-1). */
object ReedSolomon {
    private val EXP = IntArray(512)
    private val LOG = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            EXP[i] = x
            LOG[x] = i
            x = x shl 1
            if (x and 0x100 != 0) x = x xor 0x11D
        }
        for (i in 255 until 512) EXP[i] = EXP[i - 255]
    }

    private fun mul(a: Int, b: Int) = if (a == 0 || b == 0) 0 else EXP[LOG[a] + LOG[b]]
    private fun div(a: Int, b: Int): Int {
        require(b != 0)
        return if (a == 0) 0 else EXP[(LOG[a] + 255 - LOG[b]) % 255]
    }

    /** a^e for any integer e. */
    private fun pow(e: Int) = EXP[((e % 255) + 255) % 255]

    /** Polynomial (coefficient i of x^i) at x. */
    private fun eval(p: IntArray, x: Int): Int {
        var r = 0
        for (i in p.indices.reversed()) r = mul(r, x) xor p[i]
        return r
    }

    /**
     * Corrects [block] (data then [ec] EC codewords; the first codeword is the highest power) in place;
     * returns the number of codewords changed. Throws [QrMatrixDecoder.DecodeException] when there are more
     * errors than the block can correct.
     */
    fun correct(block: IntArray, ec: Int): Int {
        val n = block.size
        // Syndromes S_j = r(a^j), with r(x) = sum block[i] x^(n-1-i).
        val s = IntArray(ec) { j ->
            var r = 0
            for (c in block) r = mul(r, pow(j)) xor c
            r
        }
        if (s.all { it == 0 }) return 0
        // Berlekamp-Massey: the error locator C(x) (C[0] = 1).
        var c = IntArray(ec + 1).also { it[0] = 1 }
        var b = IntArray(ec + 1).also { it[0] = 1 }
        var l = 0
        var m = 1
        var bScale = 1
        for (k in 0 until ec) {
            var d = s[k]
            for (i in 1..l) d = d xor mul(c[i], s[k - i])
            if (d == 0) {
                m++
                continue
            }
            val coef = div(d, bScale)
            val t = c.copyOf()
            for (i in 0..ec - m) if (b[i] != 0) c[i + m] = c[i + m] xor mul(coef, b[i])
            if (2 * l <= k) {
                l = k + 1 - l
                b = t
                bScale = d
                m = 1
            } else {
                m++
            }
        }
        if (2 * l > ec) throw QrMatrixDecoder.DecodeException("too many errors")
        val locator = c.copyOf(l + 1)
        // Chien search: C(a^-p) = 0 for an error at power p, i.e. codeword index n - 1 - p.
        val positions = ArrayList<Int>()
        for (p in 0 until n) if (eval(locator, pow(-p)) == 0) positions.add(p)
        if (positions.size != l) throw QrMatrixDecoder.DecodeException("error locator has ${positions.size} roots for $l errors")
        // Forney: Omega(x) = S(x) C(x) mod x^ec; e = X * Omega(1/X) / C'(1/X) for generator roots from a^0.
        val omega = IntArray(ec)
        for (i in 0 until ec) for (j in 0..minOf(i, l)) omega[i] = omega[i] xor mul(s[i - j], locator[j])
        val derivative = IntArray(maxOf(l, 1)) { i -> if ((i + 1) % 2 == 1 && i + 1 <= l) locator[i + 1] else 0 }
        for (p in positions) {
            val xInv = pow(-p)
            val den = eval(derivative, xInv)
            if (den == 0) throw QrMatrixDecoder.DecodeException("Forney: zero derivative")
            val e = mul(pow(p), div(eval(omega, xInv), den))
            block[n - 1 - p] = block[n - 1 - p] xor e
        }
        return positions.size
    }
}
