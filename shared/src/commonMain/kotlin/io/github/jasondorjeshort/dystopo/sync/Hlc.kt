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

        fun parse(text: String): Hlc {
            require(text.length > 20 && text[12] == '.' && text[19] == '.') { "not an HLC: $text" }
            val wall = text.substring(0, 12).toLong(16)
            val counter = text.substring(13, 19).toInt(16)
            return Hlc(wall, counter, text.substring(20))
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
        last = if (physical > last.wall) Hlc(physical, 0, device) else Hlc(last.wall, last.counter + 1, device)
        return last
    }

    /** The receive rule: afterwards [last] is at least [remote], so every later [tick] is greater than it. */
    fun observe(remote: Hlc) {
        if (remote.wall > last.wall || (remote.wall == last.wall && remote.counter > last.counter)) {
            last = Hlc(remote.wall, remote.counter, device)
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
