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
 * **Pages** (T51): a bundle with [more] set is one page of a larger one ([SyncReplica.page]). Its
 * operations are the oldest (by HLC) the sender had after [base], and its [vector] is the
 * *continuation vector*: per device, the newest operation in this page (or the sender's whole entry
 * when the page holds the rest of that device). A receiver applies it like any bundle and asks again
 * from its new vector; the last page has [more] false and the sender's full vector.
 *
 * Wire form: `DSYNC` magic, format version byte, sender device id, [base], [vector], (version 2 only:
 * a flags varint, bit 0 = [more]), operation count, then each operation as a varint length plus
 * [OpCodec] bytes. A bundle without [more] is written as version 1, byte for byte what T44 wrote, so
 * older builds still read files and unpaged replies; this build reads both.
 */
class Bundle(val from: String, val base: VersionVector, val vector: VersionVector, val ops: List<Op>, val more: Boolean = false) {
    fun encode(): ByteArray {
        val w = ByteWriter(64 + ops.size * 48)
        MAGIC.forEach { w.byte(it.code) }
        w.byte(if (more) VERSION_PAGED else VERSION)
        w.id(from)
        OpCodec.writeVector(w, base)
        OpCodec.writeVector(w, vector)
        if (more) w.uvarint(FLAG_MORE.toLong())
        w.uvarint(ops.size.toLong())
        for (op in ops) {
            val b = OpCodec.encode(op)
            w.uvarint(b.size.toLong())
            w.bytes(b)
        }
        return w.toByteArray()
    }

    override fun toString() = "Bundle(from=$from, ops=${ops.size}, base=$base, vector=$vector, more=$more)"

    companion object {
        const val MAGIC = "DSYNC"
        const val VERSION = 1

        /** T51: a page with more to follow. */
        const val VERSION_PAGED = 2
        private const val FLAG_MORE = 1

        /** Gzip of an encoded bundle starts with 0x1f 0x8b; a raw one with [MAGIC]. */
        fun isRaw(bytes: ByteArray): Boolean = bytes.size >= 5 && MAGIC.indices.all { bytes[it].toInt() == MAGIC[it].code }

        /** Decodes a raw bundle. Any malformed input (truncated, corrupt, random) is a [SyncFormatException]. */
        fun decode(bytes: ByteArray): Bundle = SyncFormatException.guard {
            if (!isRaw(bytes)) throw SyncFormatException("not a sync bundle (no $MAGIC header)")
            val r = ByteReader(bytes, MAGIC.length)
            val version = r.byte()
            if (version != VERSION && version != VERSION_PAGED) throw SyncFormatException("bundle format version $version; this build reads $VERSION and $VERSION_PAGED")
            val from = r.id()
            val base = OpCodec.readVector(r)
            val vector = OpCodec.readVector(r)
            val more = if (version == VERSION_PAGED) (r.uvarint() and FLAG_MORE.toLong()) != 0L else false
            val n = r.count()
            val ops = ArrayList<Op>(minOf(n, 1 shl 16))
            repeat(n) {
                val len = r.count()
                val sub = ByteReader(bytes, r.position, r.position + len)
                ops += OpCodec.readOp(sub).also { sub.end() }
                r.skip(len)
            }
            r.end()
            Bundle(from, base, vector, ops, more)
        }
    }
}
