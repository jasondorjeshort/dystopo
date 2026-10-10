package io.github.jasondorjeshort.dystopo.sync.crypto

import io.github.jasondorjeshort.dystopo.packs.Sha256
import io.github.jasondorjeshort.dystopo.sync.Bundle
import io.github.jasondorjeshort.dystopo.sync.EncryptedBundle
import io.github.jasondorjeshort.dystopo.sync.SyncFormatException
import io.github.jasondorjeshort.dystopo.sync.SyncTransport
import io.github.jasondorjeshort.dystopo.sync.VersionVector

/**
 * The server relay's wire protocol (T72; the server side is `serverlib/.../RelayRoutes.kt`). Phones that
 * share a code meet at a *slot* ([ShareCode.slot]) on a server they can both reach; each phone is one
 * *side* (a random id per session) and stores one encrypted blob there, its whole log as a relay frame
 * ([EncryptedBundle.sealBundle]). The server never merges, never sees the code and drops blobs after a
 * short time.
 *
 * `POST /sync/relay?slot=<32 hex>&side=<16 hex>[&after=<seq>][&forget=1]`
 * - a non-empty body replaces this side's blob (it gets the slot's next sequence number);
 * - the answer is every *other* side's blob newer than `after`, as a [encodeReply] body (200), or 204
 *   when there is none; `forget=1` deletes this side's blob instead.
 */
object RelayProtocol {
    const val PATH = "/sync/relay"
    const val MAGIC = "DRLY"
    const val VERSION = 1

    class Blob(val seq: Long, val bytes: ByteArray)

    fun isSlot(s: String?): Boolean = s != null && s.length == 32 && s.all { it in '0'..'9' || it in 'a'..'f' }

    fun isSide(s: String?): Boolean = s != null && s.length in 8..64 && s.all { it in '0'..'9' || it in 'a'..'f' }

    /** A new side id: 8 random bytes as hex. */
    fun newSide(random: (Int) -> ByteArray): String = Sha256.toHex(random(8))

    /** `DRLY | version | count | (seq, length, bytes)*`, LEB128 varints. */
    fun encodeReply(blobs: List<Blob>): ByteArray {
        val head = ArrayList<Byte>()
        MAGIC.forEach { head.add(it.code.toByte()) }
        head.add(VERSION.toByte())
        uvarint(head, blobs.size.toLong())
        val parts = ArrayList<ByteArray>()
        parts.add(head.toByteArray())
        for (b in blobs) {
            val h = ArrayList<Byte>()
            uvarint(h, b.seq)
            uvarint(h, b.bytes.size.toLong())
            parts.add(h.toByteArray())
            parts.add(b.bytes)
        }
        val out = ByteArray(parts.sumOf { it.size })
        var at = 0
        for (p in parts) { p.copyInto(out, at); at += p.size }
        return out
    }

    /** Throws [SyncFormatException] for anything that is not a reply. An empty body (a 204) is no blobs. */
    fun decodeReply(bytes: ByteArray): List<Blob> = SyncFormatException.guard {
        if (bytes.isEmpty()) return@guard emptyList()
        if (bytes.size < 5 || MAGIC.indices.any { bytes[it].toInt() != MAGIC[it].code }) throw SyncFormatException("not a relay reply")
        if (bytes[4].toInt() != VERSION) throw SyncFormatException("relay reply version ${bytes[4]}")
        var i = 5
        fun read(): Long {
            var v = 0L
            var shift = 0
            while (true) {
                if (i >= bytes.size) throw SyncFormatException("relay reply truncated")
                val x = bytes[i++].toInt() and 0xff
                v = v or ((x and 0x7f).toLong() shl shift)
                if (x < 0x80) return v
                shift += 7
                if (shift > 56) throw SyncFormatException("bad varint in relay reply")
            }
        }
        val n = read()
        if (n > 1000) throw SyncFormatException("relay reply with $n blobs")
        List(n.toInt()) {
            val seq = read()
            val len = read()
            if (len > bytes.size - i) throw SyncFormatException("relay reply truncated")
            Blob(seq, bytes.copyOfRange(i, i + len.toInt())).also { i += len.toInt() }
        }
    }

    private fun uvarint(out: MutableList<Byte>, value: Long) {
        var v = value
        while (v >= 0x80) { out.add(((v and 0x7f) or 0x80).toByte()); v = v ushr 7 }
        out.add(v.toByte())
    }
}

/**
 * Sync through a server relay (T72): a [SyncTransport] that behaves like a `.dsync` file shared through
 * the server, end-to-end encrypted with the share [code]. [pull] returns the other sides' newest logs
 * (merged into one bundle; an empty one when nobody else has posted yet), [push] stores this replica's
 * whole log, so two phones converge after each has synced once after the other posted. [post] is an HTTP
 * POST returning the body (empty for a 204) and throwing on any other non-2xx status.
 *
 * The transport keeps the sequence number it has seen ([after]) across calls; keep one instance per
 * relay session so a poll ([fetch]) downloads only blobs that changed.
 */
class RelayTransport(
    baseUrl: String,
    code: String,
    val side: String,
    private val post: (url: String, body: ByteArray) -> ByteArray,
    private val random: (Int) -> ByteArray,
    private val gzip: (ByteArray) -> ByteArray,
    private val gunzip: (ByteArray) -> ByteArray,
) : SyncTransport {
    val code: String = ShareCode.normalize(code) ?: throw IllegalArgumentException("a share code is ${ShareCode.LENGTH} characters of 0-9 and A-Z (no I, L, O, U)")
    val slot: String = ShareCode.slot(this.code)
    private val base = baseUrl.trimEnd('/')

    override val description: String get() = "relay:$base"
    override val keepsEverything: Boolean get() = true

    override var bytesIn = 0L
        private set
    override var bytesOut = 0L
        private set

    /** The newest sequence number seen from another side. */
    var after = 0L
        private set
    /** Blobs from other sides opened so far, and those that did not open (another code in the same slot is all but impossible; a corrupt blob is not). */
    var received = 0
        private set
    var rejected = 0
        private set
    var lastError: String? = null
        private set

    private val pending = ArrayList<Bundle>()

    private fun url(extra: String = "") = "$base${RelayProtocol.PATH}?slot=$slot&side=$side&after=$after$extra"

    private fun take(reply: ByteArray): Int {
        bytesIn += reply.size
        var opened = 0
        for (b in RelayProtocol.decodeReply(reply)) {
            if (b.seq > after) after = b.seq
            try {
                pending.add(EncryptedBundle.openBundle(b.bytes, code, gunzip))
                received++
                opened++
            } catch (e: IllegalArgumentException) { // SealException or SyncFormatException
                rejected++
                lastError = e.message
            }
        }
        return opened
    }

    /** Asks the relay for other sides' blobs newer than [after] without posting; returns how many new bundles are waiting for [pull]. */
    fun fetch(): Int {
        val reply = post(url(), ByteArray(0))
        take(reply)
        return pending.size
    }

    /** True when [fetch] (or a push's answer) brought bundles [pull] has not returned yet. */
    val hasPending: Boolean get() = pending.isNotEmpty()

    /** The vector of what the last [pull] returned (null: nothing), and of what this side last posted. */
    private var pulledVector: VersionVector? = null
    private var postedVector: VersionVector? = null

    /** Pushes that posted nothing because the relay already held everything this replica has (see [push]). */
    var postsSkipped = 0
        private set
    var posts = 0
        private set

    override fun pull(since: VersionVector): Bundle {
        if (pending.isEmpty()) fetch()
        val all = pending.toList()
        pending.clear()
        if (all.isEmpty()) {
            pulledVector = null
            return Bundle("relay", VersionVector.EMPTY, VersionVector.EMPTY, emptyList())
        }
        if (all.size == 1) return all[0].also { pulledVector = it.vector }
        // Several other sides: each blob is a whole log (every device's operations from the start), so their union is one too.
        var vector = VersionVector.EMPTY
        for (b in all) for (h in b.vector.entries.values) vector = vector.with(h)
        pulledVector = vector
        return Bundle(all[0].from, VersionVector.EMPTY, vector, all.flatMap { it.ops }.distinctBy { it.hlc })
    }

    /**
     * Posts this replica's whole log, unless that would tell the other sides nothing: when it is what this side
     * posted last time, or when what was just pulled already holds all of it (the other side has everything).
     * Without this, two polling phones would answer each other's identical logs forever.
     */
    override fun push(bundle: Bundle): VersionVector {
        if (bundle.vector == postedVector || pulledVector?.dominates(bundle.vector) == true) {
            postsSkipped++
            return bundle.vector
        }
        val body = EncryptedBundle.sealBundle(bundle, code, random, gzip)
        bytesOut += body.size
        take(post(url(), body))
        postedVector = bundle.vector
        posts++
        return bundle.vector
    }

    /** Deletes this side's blob from the relay (the user is done; otherwise it expires on its own). */
    fun forget() {
        post(url("&forget=1"), ByteArray(0))
    }
}
