package io.github.jasondorjeshort.dystopo.mvt

/**
 * Protocol Buffers wire-format reader over a byte range. Nested messages are read as views
 * on the same array, so a tile is parsed without copying its geometry.
 *
 * Wire types: 0 varint, 1 fixed64, 2 length-delimited, 5 fixed32. Groups (3, 4) are rejected.
 */
internal class ProtoReader(private val buf: ByteArray, private var pos: Int, private val end: Int) {
    constructor(buf: ByteArray) : this(buf, 0, buf.size)

    val hasMore: Boolean get() = pos < end

    private fun byte(): Int {
        if (pos >= end) throw IllegalArgumentException("truncated protobuf message")
        return buf[pos++].toInt() and 0xFF
    }

    fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val b = byte()
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            if (shift >= 64) throw IllegalArgumentException("varint longer than 10 bytes")
        }
    }

    /** Reads a field key; split it with [fieldNumber] and [wireType]. */
    fun readKey(): Int {
        val key = readVarint()
        if (key < 8 || key > Int.MAX_VALUE) throw IllegalArgumentException("invalid protobuf field key $key")
        return key.toInt()
    }

    fun readFixed32(): Int {
        var v = 0
        for (i in 0 until 4) v = v or (byte() shl (8 * i))
        return v
    }

    fun readFixed64(): Long {
        var v = 0L
        for (i in 0 until 8) v = v or (byte().toLong() shl (8 * i))
        return v
    }

    private fun readLength(): Int {
        val n = readVarint()
        if (n < 0 || n > end - pos) throw IllegalArgumentException("protobuf length $n exceeds remaining ${end - pos} bytes")
        return n.toInt()
    }

    fun readString(): String {
        val n = readLength()
        val s = buf.decodeToString(pos, pos + n)
        pos += n
        return s
    }

    fun readMessage(): ProtoReader {
        val n = readLength()
        val r = ProtoReader(buf, pos, pos + n)
        pos += n
        return r
    }

    /**
     * Reads one occurrence of a `repeated uint32` field into [out]: a packed run (wire type 2) or a
     * single expanded value (wire type 0). Parsers must accept both forms for packable fields.
     */
    fun readUint32s(wireType: Int, out: IntVec) {
        when (wireType) {
            2 -> {
                val n = readLength()
                val stop = pos + n
                while (pos < stop) out.add(readVarint().toInt())
                if (pos != stop) throw IllegalArgumentException("packed field overran its length")
            }
            0 -> out.add(readVarint().toInt())
            else -> throw IllegalArgumentException("wire type $wireType is not a repeated varint")
        }
    }

    fun skip(wireType: Int) {
        when (wireType) {
            0 -> readVarint()
            1 -> advance(8)
            2 -> advance(readLength())
            5 -> advance(4)
            else -> throw IllegalArgumentException("unsupported protobuf wire type $wireType")
        }
    }

    private fun advance(n: Int) {
        if (n > end - pos) throw IllegalArgumentException("truncated protobuf message")
        pos += n
    }

    companion object {
        fun fieldNumber(key: Int): Int = key ushr 3
        fun wireType(key: Int): Int = key and 7

        fun zigzagDecode(n: Int): Int = (n ushr 1) xor -(n and 1)
        fun zigzagDecode(n: Long): Long = (n ushr 1) xor -(n and 1L)
        fun zigzagEncode(n: Int): Int = (n shl 1) xor (n shr 31)
        fun zigzagEncode(n: Long): Long = (n shl 1) xor (n shr 63)
    }
}

/** Protocol Buffers wire-format writer; enough for the vector tile schema. */
internal class ProtoWriter(capacity: Int = 256) {
    private var buf = ByteArray(capacity)
    var size = 0
        private set

    private fun ensure(extra: Int) {
        if (size + extra > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + extra))
    }

    fun varint(v: Long) {
        var x = v
        while (x and 0x7FL.inv() != 0L) {
            byte(((x and 0x7F) or 0x80).toInt())
            x = x ushr 7
        }
        byte(x.toInt())
    }

    private fun byte(b: Int) {
        ensure(1)
        buf[size++] = b.toByte()
    }

    fun key(field: Int, wireType: Int) = varint(((field shl 3) or wireType).toLong())

    fun varintField(field: Int, v: Long) {
        key(field, 0)
        varint(v)
    }

    fun fixed32Field(field: Int, v: Int) {
        key(field, 5)
        ensure(4)
        for (i in 0 until 4) buf[size++] = (v shr (8 * i)).toByte()
    }

    fun fixed64Field(field: Int, v: Long) {
        key(field, 1)
        ensure(8)
        for (i in 0 until 8) buf[size++] = (v shr (8 * i)).toByte()
    }

    fun bytesField(field: Int, b: ByteArray, count: Int = b.size) {
        key(field, 2)
        varint(count.toLong())
        ensure(count)
        b.copyInto(buf, size, 0, count)
        size += count
    }

    fun stringField(field: Int, s: String) = bytesField(field, s.encodeToByteArray())

    fun messageField(field: Int, m: ProtoWriter) = bytesField(field, m.buf, m.size)

    /** Appends another writer's bytes as-is (already-encoded fields). */
    fun raw(m: ProtoWriter) {
        ensure(m.size)
        m.buf.copyInto(buf, size, 0, m.size)
        size += m.size
    }

    /** Writes a `repeated uint32 [packed = true]` field; nothing at all when empty. */
    fun packedUint32Field(field: Int, values: IntArray, count: Int = values.size) {
        if (count == 0) return
        val tmp = ProtoWriter(count * 2)
        for (i in 0 until count) tmp.varint(values[i].toLong() and 0xFFFFFFFFL)
        messageField(field, tmp)
    }

    fun toByteArray(): ByteArray = buf.copyOf(size)
}

/** Growable int array, for command streams. */
internal class IntVec(capacity: Int = 16) {
    var data = IntArray(capacity)
        private set
    var size = 0
        private set

    fun add(v: Int) {
        if (size == data.size) data = data.copyOf(maxOf(16, size * 2))
        data[size++] = v
    }

    operator fun get(i: Int): Int = data[i]

    fun toIntArray(): IntArray = data.copyOf(size)
}
