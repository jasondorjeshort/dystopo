package io.github.jasondorjeshort.dystopo.sync

/**
 * A hybrid logical clock timestamp (T44): wall-clock milliseconds, a counter for events in the same
 * millisecond (or while the wall clock lags a timestamp already seen), and the device that made it.
 * Totally ordered by (wall, counter, device), so two devices never produce equal timestamps and every
 * peer orders every pair of operations the same way. Causality is respected: a device that has seen
 * an operation (see [HlcClock.observe]) stamps everything it does afterwards later than it.
 *
 * [text] is the fixed-width form used as the database key, in version vectors and in URLs:
 * `<wall, 12 hex digits>.<counter, 6 hex digits>.<device>`. Its lexicographic order is the HLC
 * order (for the lowercase UUID device ids we generate).
 */
data class Hlc(val wall: Long, val counter: Int, val device: String) : Comparable<Hlc> {
    init {
        require(wall in 0..MAX_WALL) { "HLC wall $wall out of range" }
        require(counter in 0..MAX_COUNTER) { "HLC counter $counter out of range" }
    }

    override fun compareTo(other: Hlc): Int = when {
        wall != other.wall -> wall.compareTo(other.wall)
        counter != other.counter -> counter.compareTo(other.counter)
        else -> device.compareTo(other.device)
    }

    val text: String get() = hex(wall, 12) + "." + hex(counter.toLong(), 6) + "." + device

    override fun toString(): String = text

    companion object {
        const val MAX_WALL = (1L shl 48) - 1
        const val MAX_COUNTER = (1 shl 24) - 1

        /** The smallest timestamp of [device]: what a version vector holds for a device it has seen nothing from. */
        fun zero(device: String) = Hlc(0, 0, device)

        /** Parses [text]; anything malformed is a [SyncFormatException] (T51: one exception type for bad input). */
        fun parse(text: String): Hlc {
            if (!(text.length > 20 && text[12] == '.' && text[19] == '.')) throw SyncFormatException("not an HLC: ${text.take(80)}")
            val wall = text.substring(0, 12).toLongOrNull(16)
            val counter = text.substring(13, 19).toIntOrNull(16)
            if (wall == null || counter == null || wall < 0 || counter < 0) throw SyncFormatException("not an HLC: ${text.take(80)}")
            return Hlc(wall, counter, text.substring(20))
        }

        /** An HLC read from untrusted bytes: out-of-range parts are a [SyncFormatException], not an [IllegalArgumentException]. */
        fun checked(wall: Long, counter: Long, device: String): Hlc {
            if (wall !in 0..MAX_WALL) throw SyncFormatException("HLC wall $wall out of range")
            if (counter !in 0..MAX_COUNTER.toLong()) throw SyncFormatException("HLC counter $counter out of range")
            return Hlc(wall, counter.toInt(), device)
        }

        private fun hex(v: Long, width: Int): String {
            val s = v.toString(16)
            return if (s.length >= width) s else "0".repeat(width - s.length) + s
        }
    }
}

/**
 * The HLC of one device. [tick] stamps a local event at physical time `now` (whatever the caller
 * considers the current time; the stores pass the `now` of each call, or a wall clock); [observe]
 * applies the receive rule for a timestamp from another device, so the next local event sorts after
 * it even when this device's clock is behind.
 */
class HlcClock(val device: String, last: Hlc? = null) {
    var last: Hlc = last?.let { Hlc(it.wall, it.counter, device) } ?: Hlc.zero(device)
        private set

    fun tick(now: Long): Hlc {
        val physical = now.coerceIn(0, Hlc.MAX_WALL)
        last = when {
            physical > last.wall -> Hlc(physical, 0, device)
            last.counter < Hlc.MAX_COUNTER -> Hlc(last.wall, last.counter + 1, device)
            else -> Hlc(minOf(last.wall + 1, Hlc.MAX_WALL), 0, device) // T51: a full counter moves the wall on instead of failing
        }
        return last
    }

    /** The receive rule: afterwards [last] is at least [remote], so every later [tick] is greater than it. */
    fun observe(remote: Hlc) {
        if (remote.wall > last.wall || (remote.wall == last.wall && remote.counter > last.counter)) {
            last = Hlc(remote.wall, remote.counter, device)
        }
    }

    /**
     * The receive rule with the clock-skew guard (T51): a [remote] timestamp more than [maxAheadMs]
     * ahead of the physical time [now] is not observed (the clock stays where it is) and the call
     * returns false. The operation itself is still merged (every replica must hold the same set, or
     * they could not converge); the guard only stops a device whose clock runs far ahead from pinning
     * every replica's clock to its future (and, at the extreme, exhausting the counter).
     */
    fun observe(remote: Hlc, now: Long, maxAheadMs: Long): Boolean {
        if (remote.wall - now > maxAheadMs) return false
        observe(remote)
        return true
    }

    companion object {
        /** How far ahead of local physical time a received HLC may be and still move the clock: one day. */
        const val MAX_AHEAD_MS = 24L * 3600 * 1000

        /**
         * The entity-level receive rule (T51): a local change to an entity is stamped after every
         * operation this replica holds on it (the user saw them), even one past [MAX_AHEAD_MS], up to
         * this far ahead of local time: 30 days. A change to an entity touched from further ahead may
         * lose to that operation until real time catches up.
         */
        const val MAX_SEEN_AHEAD_MS = 30L * 24 * 3600 * 1000
    }
}

/**
 * What the clock-skew guard saw on one replica (T51): received operations stamped more than
 * [HlcClock.MAX_AHEAD_MS] ahead of local time (merged, not observed). Shown in `/sync/status`.
 */
data class SkewLog(
    val clamped: Long = 0,
    /** The largest lead seen, ms. */
    val maxAheadMs: Long = 0,
    /** The device of the last such operation, and the local time it arrived. */
    val lastDevice: String? = null,
    val lastAt: Long? = null,
) {
    fun plus(count: Int, aheadMs: Long, device: String?, at: Long): SkewLog =
        if (count == 0) this else SkewLog(clamped + count, maxOf(maxAheadMs, aheadMs), device ?: lastDevice, at)

    fun json(): Map<String, Any?> = linkedMapOf(
        "clamped" to clamped, "maxAheadMs" to maxAheadMs, "lastDevice" to lastDevice, "lastAt" to lastAt,
        "limitMs" to HlcClock.MAX_AHEAD_MS,
    )

    /** For a store's metadata table. */
    fun encode(): String = "$clamped,$maxAheadMs,${lastDevice?.replace(',', '_')?.take(100) ?: ""},${lastAt ?: ""}"

    companion object {
        fun decode(s: String?): SkewLog {
            val p = s?.split(',') ?: return SkewLog()
            if (p.size != 4) return SkewLog()
            return SkewLog(p[0].toLongOrNull() ?: 0, p[1].toLongOrNull() ?: 0, p[2].ifEmpty { null }, p[3].toLongOrNull())
        }
    }
}

/**
 * What a replica holds: for every device, the greatest HLC of that device's operations it has
 * applied (or deliberately dropped as dead). Operations of one device always reach a replica as a
 * prefix in HLC order (see [SyncReplica.apply]), so "everything after the vector" is exactly what a
 * replica is missing. Encoded as the comma-separated [Hlc.text]s (each carries its device).
 */
class VersionVector(entries: Map<String, Hlc> = emptyMap()) {
    val entries: Map<String, Hlc> = entries.toList().sortedBy { it.first }.toMap()

    operator fun get(device: String): Hlc = entries[device] ?: Hlc.zero(device)

    fun covers(op: Hlc): Boolean = entries[op.device]?.let { op <= it } ?: false

    fun with(h: Hlc): VersionVector = if (covers(h)) this else VersionVector(entries + (h.device to h))

    /** True when this vector has everything [other] has. */
    fun dominates(other: VersionVector): Boolean = other.entries.values.all { covers(it) }

    val text: String get() = entries.values.joinToString(",") { it.text }

    override fun equals(other: Any?): Boolean = other is VersionVector && other.entries == entries
    override fun hashCode(): Int = entries.hashCode()
    override fun toString(): String = text

    companion object {
        val EMPTY = VersionVector()

        fun parse(text: String?): VersionVector {
            if (text.isNullOrBlank()) return EMPTY
            val m = LinkedHashMap<String, Hlc>()
            for (part in text.split(',')) {
                val p = part.trim()
                if (p.isEmpty()) continue
                val h = Hlc.parse(p)
                m[h.device] = maxOf(h, m[h.device] ?: h)
            }
            return VersionVector(m)
        }
    }
}
