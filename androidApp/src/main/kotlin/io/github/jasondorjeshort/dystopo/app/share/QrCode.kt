package io.github.jasondorjeshort.dystopo.app.share

/**
 * A small QR code encoder (T54), pure Kotlin with no dependency: byte mode, error correction level M,
 * versions 1 to 4 (up to 62 bytes), which is enough for a share link such as
 * `dystopo://share?addr=192.168.1.23:8841&code=123456`. Written from ISO/IEC 18004: Reed-Solomon over
 * GF(256) (polynomial 0x11D), finder, timing and alignment patterns, zigzag data placement, the eight
 * masks scored with the standard penalty rules, and the BCH-coded format information.
 *
 * `modules[y][x]` is true for a dark module; draw it with a light quiet zone of 4 modules around it.
 */
class QrCode private constructor(val version: Int, val mask: Int, private val modules: Array<BooleanArray>) {
    val size: Int get() = modules.size

    operator fun get(x: Int, y: Int): Boolean = modules[y][x]

    /** One string per row, `#` dark and `.` light (tests and debugging). */
    fun rows(): List<String> = modules.map { row -> buildString { row.forEach { append(if (it) '#' else '.') } } }

    companion object {
        /** Data codewords, EC codewords per block and blocks at level M, by version (index 0 unused). */
        private val DATA = intArrayOf(0, 16, 28, 44, 64)
        private val ECC = intArrayOf(0, 10, 16, 26, 18)
        private val BLOCKS = intArrayOf(0, 1, 1, 1, 2)

        /** The second alignment pattern coordinate (the first is 6, whose patterns overlap the finders). */
        private val ALIGN = intArrayOf(0, 0, 18, 22, 26)

        const val MAX_BYTES = 62

        fun encode(text: String, forcedMask: Int = -1): QrCode = encode(text.encodeToByteArray(), forcedMask)

        /** Encodes [data] in the smallest version that holds it; [forcedMask] 0..7 skips the mask choice. */
        fun encode(data: ByteArray, forcedMask: Int = -1): QrCode {
            require(forcedMask in -1..7) { "mask must be 0..7" }
            val version = (1..4).firstOrNull { 4 + 8 + data.size * 8 <= DATA[it] * 8 }
                ?: throw IllegalArgumentException("${data.size} bytes do not fit a version 4-M QR code ($MAX_BYTES at most)")
            val codewords = interleave(version, dataCodewords(version, data))
            val base = Matrix(version)
            base.drawFunctionPatterns()
            base.placeData(codewords)
            val masks = if (forcedMask >= 0) listOf(forcedMask) else (0..7).toList()
            var best: Matrix? = null
            var bestMask = 0
            var bestScore = Int.MAX_VALUE
            for (m in masks) {
                val c = base.copy()
                c.applyMask(m)
                c.drawFormat(m)
                val s = c.penalty()
                if (s < bestScore) {
                    best = c
                    bestMask = m
                    bestScore = s
                }
            }
            return QrCode(version, bestMask, best!!.modules)
        }

        /** Mode, count, bytes, terminator and pad codewords: exactly DATA[version] codewords. */
        private fun dataCodewords(version: Int, data: ByteArray): IntArray {
            val capacity = DATA[version] * 8
            val bits = ArrayList<Boolean>(capacity)
            fun put(value: Int, n: Int) { for (i in n - 1 downTo 0) bits.add((value ushr i) and 1 == 1) }
            put(0b0100, 4)
            put(data.size, 8)
            data.forEach { put(it.toInt() and 0xFF, 8) }
            put(0, minOf(4, capacity - bits.size))
            while (bits.size % 8 != 0) bits.add(false)
            var pad = 0xEC
            while (bits.size < capacity) {
                put(pad, 8)
                pad = pad xor 0xEC xor 0x11
            }
            return IntArray(DATA[version]) { i -> (0 until 8).fold(0) { acc, b -> (acc shl 1) or (if (bits[i * 8 + b]) 1 else 0) } }
        }

        /** Splits into blocks, adds Reed-Solomon codewords, interleaves data then EC codewords. */
        private fun interleave(version: Int, data: IntArray): IntArray {
            val blocks = BLOCKS[version]
            val per = data.size / blocks // all blocks are the same length at level M for versions 1..4
            val divisor = rsDivisor(ECC[version])
            val dataBlocks = (0 until blocks).map { b -> data.copyOfRange(b * per, (b + 1) * per) }
            val eccBlocks = dataBlocks.map { rsRemainder(it, divisor) }
            val out = ArrayList<Int>(data.size + blocks * ECC[version])
            for (i in 0 until per) dataBlocks.forEach { out.add(it[i]) }
            for (i in 0 until ECC[version]) eccBlocks.forEach { out.add(it[i]) }
            return out.toIntArray()
        }

        private fun gfMul(x: Int, y: Int): Int {
            var z = 0
            for (i in 7 downTo 0) {
                z = (z shl 1) xor ((z ushr 7) * 0x11D)
                z = z xor (((y ushr i) and 1) * x)
            }
            return z
        }

        /** Generator polynomial with roots a^0..a^(degree-1), leading coefficient dropped. */
        private fun rsDivisor(degree: Int): IntArray {
            val result = IntArray(degree)
            result[degree - 1] = 1
            var root = 1
            repeat(degree) {
                for (j in 0 until degree) {
                    result[j] = gfMul(result[j], root)
                    if (j + 1 < degree) result[j] = result[j] xor result[j + 1]
                }
                root = gfMul(root, 0x02)
            }
            return result
        }

        private fun rsRemainder(data: IntArray, divisor: IntArray): IntArray {
            val result = IntArray(divisor.size)
            for (b in data) {
                val factor = b xor result[0]
                for (i in 0 until result.size - 1) result[i] = result[i + 1]
                result[result.size - 1] = 0
                for (i in result.indices) result[i] = result[i] xor gfMul(divisor[i], factor)
            }
            return result
        }
    }

    private class Matrix(val version: Int) {
        val size = 17 + 4 * version
        var modules = Array(size) { BooleanArray(size) }
        var function = Array(size) { BooleanArray(size) }

        fun copy(): Matrix = Matrix(version).also { m ->
            m.modules = Array(size) { modules[it].copyOf() }
            m.function = function
        }

        private fun set(x: Int, y: Int, dark: Boolean) {
            modules[y][x] = dark
            function[y][x] = true
        }

        fun drawFunctionPatterns() {
            for (i in 0 until size) {
                set(6, i, i % 2 == 0)
                set(i, 6, i % 2 == 0)
            }
            finder(3, 3)
            finder(size - 4, 3)
            finder(3, size - 4)
            if (version >= 2) alignment(ALIGN[version], ALIGN[version])
            drawFormat(0) // reserves the format areas; redrawn after masking
        }

        /** Finder pattern centred on (cx, cy) with its light separator. */
        private fun finder(cx: Int, cy: Int) {
            for (dy in -4..4) for (dx in -4..4) {
                val x = cx + dx
                val y = cy + dy
                if (x !in 0 until size || y !in 0 until size) continue
                val d = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                set(x, y, d != 2 && d != 4)
            }
        }

        private fun alignment(cx: Int, cy: Int) {
            for (dy in -2..2) for (dx in -2..2) set(cx + dx, cy + dy, maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) != 1)
        }

        /** Format information: level M (bits 00) and the mask, BCH(15,5) coded, XOR 0x5412. */
        fun drawFormat(mask: Int) {
            val data = (0b00 shl 3) or mask
            var rem = data
            repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
            val bits = ((data shl 10) or rem) xor 0x5412
            fun bit(i: Int) = (bits ushr i) and 1 == 1
            for (i in 0..5) set(8, i, bit(i))
            set(8, 7, bit(6))
            set(8, 8, bit(7))
            set(7, 8, bit(8))
            for (i in 9..14) set(14 - i, 8, bit(i))
            for (i in 0..7) set(size - 1 - i, 8, bit(i))
            for (i in 8..14) set(8, size - 15 + i, bit(i))
            set(8, size - 8, true) // the dark module
        }

        /** Zigzag placement in two-module columns from the bottom right, skipping the vertical timing column. */
        fun placeData(codewords: IntArray) {
            var i = 0
            val total = codewords.size * 8
            var right = size - 1
            while (right >= 1) {
                if (right == 6) right = 5
                for (vert in 0 until size) {
                    for (j in 0..1) {
                        val x = right - j
                        val upward = ((right + 1) and 2) == 0
                        val y = if (upward) size - 1 - vert else vert
                        if (!function[y][x] && i < total) {
                            modules[y][x] = (codewords[i ushr 3] ushr (7 - (i and 7))) and 1 == 1
                            i++
                        }
                    }
                }
                right -= 2
            }
        }

        fun applyMask(mask: Int) {
            for (y in 0 until size) for (x in 0 until size) {
                if (function[y][x]) continue
                val invert = when (mask) {
                    0 -> (x + y) % 2 == 0
                    1 -> y % 2 == 0
                    2 -> x % 3 == 0
                    3 -> (x + y) % 3 == 0
                    4 -> (x / 3 + y / 2) % 2 == 0
                    5 -> x * y % 2 + x * y % 3 == 0
                    6 -> (x * y % 2 + x * y % 3) % 2 == 0
                    else -> ((x + y) % 2 + x * y % 3) % 2 == 0
                }
                if (invert) modules[y][x] = !modules[y][x]
            }
        }

        /** The standard penalty: runs of 5+, 2x2 blocks, finder-like 1:1:3:1:1 runs, dark/light balance. */
        fun penalty(): Int {
            var score = 0
            fun line(get: (Int) -> Boolean) {
                var run = 1
                for (i in 1 until size) {
                    if (get(i) == get(i - 1)) run++ else {
                        if (run >= 5) score += 3 + run - 5
                        run = 1
                    }
                }
                if (run >= 5) score += 3 + run - 5
                val pattern = booleanArrayOf(true, false, true, true, true, false, true)
                for (i in 0..size - 7) {
                    if ((0 until 7).all { get(i + it) == pattern[it] }) {
                        val lightBefore = (1..4).all { i - it < 0 || !get(i - it) }
                        val lightAfter = (0 until 4).all { i + 7 + it >= size || !get(i + 7 + it) }
                        if (lightBefore || lightAfter) score += 40
                    }
                }
            }
            for (y in 0 until size) line { modules[y][it] }
            for (x in 0 until size) line { modules[it][x] }
            for (y in 0 until size - 1) for (x in 0 until size - 1) {
                val c = modules[y][x]
                if (c == modules[y][x + 1] && c == modules[y + 1][x] && c == modules[y + 1][x + 1]) score += 3
            }
            val dark = modules.sumOf { r -> r.count { it } }
            val total = size * size
            val k = (kotlin.math.abs(dark * 20 - total * 10) + total - 1) / total - 1
            score += maxOf(0, k) * 10
            return score
        }
    }
}
