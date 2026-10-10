package io.github.jasondorjeshort.dystopo.sync

/**
 * A batch of operations from one replica (T44): what a pull returns, what a push sends, and what a
 * `.dsync` file holds (gzip of [encode]).
 *
 * [base] is the vector the operations were selected against: for every device, the bundle holds
 * all of that device's operations the sender had after `base[device]`, up to `vector[device]`
 * (except dead ones, see [Materializer]). [vector] is everything the sender held. A receiver that
 * already holds a device up to `base[device]` can take that device's operations and then holds it up
 * to `vector[device]`; one that holds less would get a gap and skips that device (the next sync
 * fills it). This keeps each device's operations a prefix on every replica, which is what makes
 * "send me everything after my vector" exact.
 *
 * Wire form: `DSYNC` magic, format version byte (1), sender device id, [base], [vector], operation
 * count, then each operation as a varint length plus [OpCodec] bytes.
 */
class Bundle(val from: String, val base: VersionVector, val vector: VersionVector, val ops: List<Op>) {
    fun encode(): ByteArray {
        val w = ByteWriter(64 + ops.size * 48)
        MAGIC.forEach { w.byte(it.code) }
        w.byte(VERSION)
        w.id(from)
        OpCodec.writeVector(w, base)
        OpCodec.writeVector(w, vector)
        w.uvarint(ops.size.toLong())
        for (op in ops) {
            val b = OpCodec.encode(op)
            w.uvarint(b.size.toLong())
            w.bytes(b)
        }
        return w.toByteArray()
    }

    override fun toString() = "Bundle(from=$from, ops=${ops.size}, base=$base, vector=$vector)"

    companion object {
        const val MAGIC = "DSYNC"
        const val VERSION = 1

        /** Gzip of an encoded bundle starts with 0x1f 0x8b; a raw one with [MAGIC]. */
        fun isRaw(bytes: ByteArray): Boolean = bytes.size >= 5 && MAGIC.indices.all { bytes[it].toInt() == MAGIC[it].code }

        fun decode(bytes: ByteArray): Bundle {
            if (!isRaw(bytes)) throw SyncFormatException("not a sync bundle (no ${MAGIC} header)")
            val r = ByteReader(bytes, MAGIC.length)
            val version = r.byte()
            if (version != VERSION) throw SyncFormatException("bundle format version $version; this build reads $VERSION")
            val from = r.id()
            val base = OpCodec.readVector(r)
            val vector = OpCodec.readVector(r)
            val n = r.count()
            val ops = ArrayList<Op>(n)
            repeat(n) {
                val len = r.count()
                val sub = ByteReader(bytes, r.position, r.position + len)
                ops += OpCodec.readOp(sub).also { sub.end() }
                r.bytes(len)
            }
            r.end()
            return Bundle(from, base, vector, ops)
        }
    }
}
