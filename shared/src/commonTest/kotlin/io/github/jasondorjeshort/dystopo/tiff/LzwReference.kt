package io.github.jasondorjeshort.dystopo.tiff

/** T110: the LZW decoder as it was before T110 (local functions over captured variables), kept to check the new one against. */
object LzwReference {
    private const val CLEAR = 256
    private const val EOI = 257
    private const val FIRST_FREE = 258
    private const val MAX_CODES = 4096

    fun decode(input: ByteArray, expectedSize: Int): ByteArray {
        val out = ByteArray(expectedSize)
        var outPos = 0

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

        var next = FIRST_FREE
        var width = 9
        var old = -1

        var pos = 0
        var bitBuf = 0L
        var bitCount = 0
        fun readCode(): Int {
            while (bitCount < width) {
                if (pos >= input.size) return EOI
                bitBuf = (bitBuf shl 8) or (input[pos++].toLong() and 0xFF)
                bitCount += 8
            }
            bitCount -= width
            val code = ((bitBuf ushr bitCount) and ((1L shl width) - 1)).toInt()
            bitBuf = bitBuf and ((1L shl bitCount) - 1)
            return code
        }

        fun write(code: Int) {
            val len = length[code]
            if (outPos + len > out.size) throw IllegalArgumentException("LZW output exceeds $expectedSize bytes")
            var c = code
            var p = outPos + len - 1
            while (c != -1) {
                out[p--] = suffix[c]
                c = prefix[c]
            }
            outPos += len
        }

        fun add(prefixCode: Int, byte: Byte) {
            if (next >= MAX_CODES) return
            prefix[next] = prefixCode
            suffix[next] = byte
            first[next] = first[prefixCode]
            length[next] = length[prefixCode] + 1
            next++
            if (next == (1 shl width) - 1 && width < 12) width++
        }

        while (outPos < expectedSize) {
            var code = readCode()
            if (code == EOI) break
            if (code == CLEAR) {
                next = FIRST_FREE
                width = 9
                code = readCode()
                if (code == EOI) break
                write(code)
                old = code
                continue
            }
            if (old < 0) throw IllegalArgumentException("LZW stream doesn't start with a clear code")
            if (code < next) {
                write(code)
                add(old, first[code])
            } else if (code == next) {
                add(old, first[old])
                write(code)
            } else {
                throw IllegalArgumentException("invalid LZW code $code (next free $next)")
            }
            old = code
        }
        return out
    }
}
