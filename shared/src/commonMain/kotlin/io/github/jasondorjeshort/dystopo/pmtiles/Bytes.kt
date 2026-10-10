package io.github.jasondorjeshort.dystopo.pmtiles

/** Compresses a whole buffer (directories, metadata). Platform code supplies gzip etc. */
typealias Compress = (ByteArray) -> ByteArray

/** Decompresses a whole buffer; the uncompressed size is not known in advance. */
typealias Decompress = (ByteArray) -> ByteArray

/** Receives the archive sequentially, in order, as the writer produces it. */
fun interface ByteSink {
    fun write(bytes: ByteArray)
}

/** Growable little-endian byte buffer with the integer encodings PMTiles uses. */
internal class ByteWriter(initialCapacity: Int = 256) {
    private var buf = ByteArray(initialCapacity)
    var size = 0
        private set

    private fun ensure(extra: Int) {
        if (size + extra > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + extra))
    }

    fun u8(v: Int) {
        ensure(1)
        buf[size++] = v.toByte()
    }

    fun u32(v: Long) {
        ensure(4)
        for (i in 0 until 4) buf[size++] = ((v ushr (8 * i)) and 0xFF).toByte()
    }

    fun i32(v: Int) = u32(v.toLong() and 0xFFFFFFFFL)

    fun u64(v: Long) {
        ensure(8)
        for (i in 0 until 8) buf[size++] = ((v ushr (8 * i)) and 0xFF).toByte()
    }

    /** Unsigned LEB128, up to 10 bytes for a full 64-bit value. */
    fun varint(value: Long) {
        var v = value
        ensure(10)
        while ((v and 0x7FL.inv()) != 0L) {
            buf[size++] = ((v and 0x7F) or 0x80).toByte()
            v = v ushr 7
        }
        buf[size++] = v.toByte()
    }

    fun bytes(b: ByteArray) {
        ensure(b.size)
        b.copyInto(buf, size)
        size += b.size
    }

    fun toByteArray(): ByteArray = buf.copyOf(size)
}

/** Sequential little-endian reader over a byte array. */
internal class ByteReader(private val buf: ByteArray, var pos: Int = 0) {
    val remaining: Int get() = buf.size - pos

    fun u8(): Int {
        check(pos < buf.size) { "unexpected end of data" }
        return buf[pos++].toInt() and 0xFF
    }

    fun u32(): Long {
        var v = 0L
        for (i in 0 until 4) v = v or (u8().toLong() shl (8 * i))
        return v
    }

    fun i32(): Int = u32().toInt()

    fun u64(): Long {
        var v = 0L
        for (i in 0 until 8) v = v or (u8().toLong() shl (8 * i))
        return v
    }

    fun varint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val b = u8()
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            check(shift < 64) { "varint too long" }
        }
    }

    fun bytes(n: Int): ByteArray {
        check(pos + n <= buf.size) { "unexpected end of data" }
        return buf.copyOfRange(pos, pos + n).also { pos += n }
    }
}
