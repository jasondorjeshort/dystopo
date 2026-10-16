package io.github.jasondorjeshort.dystopo.tiff

/**
 * TIFF-flavored LZW decoder (MSB-first codes, 9-12 bits, "early change").
 *
 * T110: one loop over plain locals. It used local functions (read a code, write a string, add an entry) over captured
 * variables, which Kotlin turns into boxed references read and written through on every code; a USGS 3DEP block
 * (512 x 512 floats, LZW with the floating-point predictor) is about 300,000 codes, and on a phone running the debug
 * build's interpreter, or a just-booted emulator, each block took seconds (T88, T105). Same output, byte for byte
 * (`LzwTest` checks it against the old decoder on encoded data).
 */
object Lzw {
    private const val CLEAR = 256
    private const val EOI = 257
    private const val FIRST_FREE = 258
    private const val MAX_CODES = 4096

    fun decode(input: ByteArray, expectedSize: Int): ByteArray {
        val out = ByteArray(expectedSize)
        val prefix = IntArray(MAX_CODES)
        val suffix = ByteArray(MAX_CODES)
        val first = ByteArray(MAX_CODES)
        val length = IntArray(MAX_CODES)
        for (i in 0 until 256) {
            prefix[i] = -1
            suffix[i] = i.toByte()
            first[i] = i.toByte()
            length[i] = 1
        }
        val n = input.size
        var outPos = 0
        var next = FIRST_FREE
        var width = 9
        var mask = (1 shl width) - 1
        var old = -1
        var pos = 0
        var bitBuf = 0 // at most 19 pending bits: fewer than 12 before a byte is added
        var bitCount = 0
        while (outPos < expectedSize) {
            // The next code, MSB first; the input running out reads as end of information.
            while (bitCount < width && pos < n) {
                bitBuf = (bitBuf shl 8) or (input[pos++].toInt() and 0xFF)
                bitCount += 8
            }
            if (bitCount < width) break
            bitCount -= width
            var code = (bitBuf ushr bitCount) and mask
            bitBuf = bitBuf and ((1 shl bitCount) - 1)
            if (code == EOI) break
            if (code == CLEAR) {
                next = FIRST_FREE
                width = 9
                mask = (1 shl width) - 1
                while (bitCount < width && pos < n) {
                    bitBuf = (bitBuf shl 8) or (input[pos++].toInt() and 0xFF)
                    bitCount += 8
                }
                if (bitCount < width) break
                bitCount -= width
                code = (bitBuf ushr bitCount) and mask
                bitBuf = bitBuf and ((1 shl bitCount) - 1)
                if (code == EOI) break
                // After a clear the code is a single byte (a root entry).
                if (code >= next) throw IllegalArgumentException("invalid LZW code $code after a clear")
                val len = length[code]
                if (outPos + len > expectedSize) throw IllegalArgumentException("LZW output exceeds $expectedSize bytes")
                var c = code
                var p = outPos + len - 1
                while (c != -1) {
                    out[p--] = suffix[c]
                    c = prefix[c]
                }
                outPos += len
                old = code
                continue
            }
            if (old < 0) throw IllegalArgumentException("LZW stream doesn't start with a clear code")
            val known = code < next
            if (!known && code != next) throw IllegalArgumentException("invalid LZW code $code (next free $next)")
            // The new entry: the previous string plus the first byte of this one (of the previous one for code == next).
            if (next < MAX_CODES) {
                prefix[next] = old
                suffix[next] = if (known) first[code] else first[old]
                first[next] = first[old]
                length[next] = length[old] + 1
                next++
                if (next == mask && width < 12) {
                    width++
                    mask = (1 shl width) - 1
                }
            }
            val len = length[code]
            if (outPos + len > expectedSize) throw IllegalArgumentException("LZW output exceeds $expectedSize bytes")
            var c = code
            var p = outPos + len - 1
            while (c != -1) {
                out[p--] = suffix[c]
                c = prefix[c]
            }
            outPos += len
            old = code
        }
        return out
    }
}
