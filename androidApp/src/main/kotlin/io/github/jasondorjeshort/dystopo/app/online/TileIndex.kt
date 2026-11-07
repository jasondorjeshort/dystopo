package io.github.jasondorjeshort.dystopo.app.online

/**
 * T242: [TileCache]'s index, compact: a struct of arrays rather than an object per entry. Every entry is a slot in parallel
 * primitive arrays (its key, bytes, touch minute, flags and the two links of its bucket); a tile key
 * `<prefix>/<z>/<x>/<y>.<ext>` is packed into one long, its prefix and extension interned as a group (at most
 * [MAX_GROUPS]); a key that does not pack (a glyph range, a contour tile's `.halo-` variant, a bare test key, a zoom past
 * [MAX_Z] or a coordinate from [MAX_COORD]) keeps its string in a side map. Packed keys are found through an
 * open-addressing table of slot numbers (linear probing, backward-shift deletion).
 *
 * Eviction order: the unpinned entries sit in touch-minute buckets (a sorted map from the minute an entry was last used to a
 * doubly linked list of its entries), oldest bucket first, and a touch moves the entry to the tail of the current minute's
 * bucket, so the order is least recently used first, exactly within the process and to the minute after a restart (the
 * walk files an entry in the bucket of its file's time), with no sort. Pinned entries are in no bucket.
 *
 * Not thread-safe: [TileCache] calls it under its lock.
 */
internal class TileIndex {
    private class Bucket {
        var head = NONE
        var tail = NONE
    }

    // The entries, by slot, in pages of [PAGE] slots (no array copied whole as it grows, none past a few hundred kB).
    private val keys = Longs()
    private val sizes = Ints()
    private val minutes = Ints()
    private val flags = Bytes()
    private val prev = Ints()
    private val next = Ints()
    private var pages = 0
    /** Slots handed out so far (the used ones and the free list are below it). */
    var high = 0
        private set
    private var freeHead = NONE

    /** Entries held. */
    var count = 0
        private set

    // Packed keys to slot + 1 (0: empty).
    private var table = IntArray(16)
    private var shift = 64 - 4
    private var tableUsed = 0

    // The keys that do not pack.
    private val stringSlots = HashMap<String, Int>()
    private val slotStrings = HashMap<Int, String>()

    // The groups: a tile key's prefix and extension.
    private val groupPrefix = ArrayList<String>()
    private val groupExt = ArrayList<String>()
    private val groupIds = HashMap<String, Int>()
    private var lastGroup = -1

    private val buckets = java.util.TreeMap<Int, Bucket>()
    private var nowMinute = Int.MIN_VALUE
    private var nowBucket: Bucket? = null

    val groups: Int get() = groupPrefix.size
    val stringKeys: Int get() = stringSlots.size
    val bucketCount: Int get() = buckets.size

    fun isUsed(slot: Int): Boolean = slot in 0 until high && flags[slot].toInt() and USED != 0
    fun size(slot: Int): Int = sizes[slot]
    fun minute(slot: Int): Int = minutes[slot]
    fun isPinned(slot: Int): Boolean = flags[slot].toInt() and PINNED != 0

    /** The group of [slot]'s key ([groupPrefixOf]), or -1 for a key kept as a string. */
    fun groupOf(slot: Int): Int = if (flags[slot].toInt() and STRING != 0) -1 else (keys[slot] ushr 53).toInt()

    fun groupPrefixOf(group: Int): String = groupPrefix[group]

    /** The key of [slot] (made again from the packed long, or the string kept). */
    fun keyOf(slot: Int): String {
        if (flags[slot].toInt() and STRING != 0) return slotStrings.getValue(slot)
        val k = keys[slot]
        val g = (k ushr 53).toInt()
        return StringBuilder(groupPrefix[g].length + 24).append(groupPrefix[g]).append('/')
            .append(((k ushr 48) and 31).toInt()).append('/').append(((k ushr 24) and 0xffffff).toInt()).append('/')
            .append((k and 0xffffff).toInt()).append('.').append(groupExt[g]).toString()
    }

    /** The slot of [key], or [NONE]. */
    fun find(key: String): Int {
        val p = pack(key, create = false)
        if (p == NOT_PACKED || p == NO_GROUP) return stringSlots[key] ?: NONE
        return findPacked(p)
    }

    /** Adds [key] (not held: the caller found it absent) and returns its slot. */
    fun add(key: String, bytes: Int, minute: Int, pinned: Boolean): Int {
        val s = allocSlot()
        val p = pack(key, create = true)
        if (p == NOT_PACKED || p == NO_GROUP) {
            keys[s] = 0
            flags[s] = (USED or STRING).toByte()
            stringSlots[key] = s
            slotStrings[s] = key
        } else {
            keys[s] = p
            flags[s] = USED.toByte()
            tableInsert(p, s)
        }
        sizes[s] = bytes
        minutes[s] = minute
        prev[s] = NONE
        next[s] = NONE
        if (pinned) flags[s] = (flags[s].toInt() or PINNED).toByte() else link(s, minute)
        count++
        return s
    }

    /** Takes [slot] out (its key, its bucket) and frees it. */
    fun remove(slot: Int) {
        if (!isPinned(slot)) unlink(slot)
        if (flags[slot].toInt() and STRING != 0) {
            slotStrings.remove(slot)?.let { stringSlots.remove(it) }
        } else {
            tableRemove(keys[slot])
        }
        flags[slot] = 0
        next[slot] = freeHead
        freeHead = slot
        count--
    }

    /** Sets [slot]'s bytes (a put over a held key). */
    fun setSize(slot: Int, bytes: Int) {
        sizes[slot] = bytes
    }

    /** [slot] was used at [minute]: the tail of that minute's bucket (an unpinned entry); a minute never goes back. */
    fun touch(slot: Int, minute: Int) {
        val m = maxOf(minute, minutes[slot])
        if (isPinned(slot)) {
            minutes[slot] = m
            return
        }
        unlink(slot)
        minutes[slot] = m
        link(slot, m)
    }

    /** Pins or unpins [slot]: a pinned entry leaves its bucket; an unpinned one joins the bucket of its minute (at its tail). */
    fun setPinned(slot: Int, pinned: Boolean) {
        if (pinned == isPinned(slot)) return
        if (pinned) {
            unlink(slot)
            flags[slot] = (flags[slot].toInt() or PINNED).toByte()
        } else {
            flags[slot] = (flags[slot].toInt() and PINNED.inv()).toByte()
            link(slot, minutes[slot])
        }
    }

    /**
     * The unpinned slots, least recently used first, to [f] until it answers false. [f] may remove or pin the slot it is
     * given (the next one is read first), nothing else.
     */
    inline fun forEachOldest(f: (Int) -> Boolean) {
        var minute = firstMinute()
        while (minute != null) {
            var s = headOf(minute)
            while (s != NONE) {
                val n = nextOf(s)
                if (!f(s)) return
                s = n
            }
            minute = minuteAfter(minute)
        }
    }

    fun firstMinute(): Int? = if (buckets.isEmpty()) null else buckets.firstKey()
    fun minuteAfter(minute: Int): Int? = buckets.higherKey(minute)
    fun headOf(minute: Int): Int = buckets[minute]?.head ?: NONE
    fun nextOf(slot: Int): Int = next[slot]

    /**
     * The heap the index takes, estimated (bytes): its pages, the hash table, the bucket map and the side maps of the keys
     * that do not pack. Read without the lock (sizes only).
     */
    fun heapBytes(): Long {
        val arrays = pages.toLong() * PAGE * (8 + 4 + 4 + 1 + 4 + 4) + table.size * 4L
        // Sizes only (read without the lock): a key kept as a string about 200 B with its two map entries, a group 150 B.
        return arrays + stringSlots.size * 200L + groupPrefix.size * 150L + buckets.size * 80L
    }

    // --- slots ---

    private fun allocSlot(): Int {
        if (freeHead != NONE) {
            val s = freeHead
            freeHead = next[s]
            return s
        }
        if (high == pages * PAGE) {
            keys.grow(); sizes.grow(); minutes.grow(); flags.grow(); prev.grow(); next.grow()
            pages++
        }
        return high++
    }

    /** A paged array of longs (ints, bytes) by slot: it grows a page at a time, no copy of the entries. */
    private class Longs {
        private var p = arrayOfNulls<LongArray>(0)
        fun grow() { p = p.copyOf(p.size + 1).also { it[it.size - 1] = LongArray(PAGE) } }
        operator fun get(i: Int): Long = p[i ushr PAGE_BITS]!![i and PAGE_MASK]
        operator fun set(i: Int, v: Long) { p[i ushr PAGE_BITS]!![i and PAGE_MASK] = v }
    }

    private class Ints {
        private var p = arrayOfNulls<IntArray>(0)
        fun grow() { p = p.copyOf(p.size + 1).also { it[it.size - 1] = IntArray(PAGE) } }
        operator fun get(i: Int): Int = p[i ushr PAGE_BITS]!![i and PAGE_MASK]
        operator fun set(i: Int, v: Int) { p[i ushr PAGE_BITS]!![i and PAGE_MASK] = v }
    }

    private class Bytes {
        private var p = arrayOfNulls<ByteArray>(0)
        fun grow() { p = p.copyOf(p.size + 1).also { it[it.size - 1] = ByteArray(PAGE) } }
        operator fun get(i: Int): Byte = p[i ushr PAGE_BITS]!![i and PAGE_MASK]
        operator fun set(i: Int, v: Byte) { p[i ushr PAGE_BITS]!![i and PAGE_MASK] = v }
    }

    // --- buckets ---

    private fun bucketFor(minute: Int): Bucket {
        if (minute == nowMinute) nowBucket?.let { return it }
        val b = buckets[minute] ?: Bucket().also { buckets[minute] = it }
        if (minute >= nowMinute) {
            nowMinute = minute
            nowBucket = b
        }
        return b
    }

    private fun link(slot: Int, minute: Int) {
        val b = bucketFor(minute)
        prev[slot] = b.tail
        next[slot] = NONE
        if (b.tail != NONE) next[b.tail] = slot else b.head = slot
        b.tail = slot
    }

    private fun unlink(slot: Int) {
        val m = minutes[slot]
        val b = (if (m == nowMinute) nowBucket else null) ?: buckets[m] ?: return
        val p = prev[slot]
        val n = next[slot]
        if (p != NONE) next[p] = n else b.head = n
        if (n != NONE) prev[n] = p else b.tail = p
        prev[slot] = NONE
        next[slot] = NONE
        if (b.head == NONE) {
            buckets.remove(m)
            if (m == nowMinute) nowBucket = null
        }
    }

    // --- packed keys ---

    /**
     * [key] packed: the group in the top 11 bits, z in 5, x and y in 24 each; [NOT_PACKED] for a key of another shape (or
     * past the limits, or with a leading zero, so that [keyOf] gives back the same string), [NO_GROUP] when its group is
     * new and [create] is false or the groups are full.
     */
    private fun pack(key: String, create: Boolean): Long {
        val s3 = key.lastIndexOf('/')
        if (s3 <= 0) return NOT_PACKED
        val s2 = key.lastIndexOf('/', s3 - 1)
        if (s2 <= 0) return NOT_PACKED
        val s1 = key.lastIndexOf('/', s2 - 1)
        if (s1 <= 0) return NOT_PACKED
        val dot = key.indexOf('.', s3 + 1)
        if (dot < 0 || dot == key.length - 1 || key.indexOf('.', dot + 1) >= 0) return NOT_PACKED
        val y = number(key, s3 + 1, dot)
        val x = number(key, s2 + 1, s3)
        val z = number(key, s1 + 1, s2)
        if (y < 0 || x < 0 || z < 0 || z > MAX_Z || x >= MAX_COORD || y >= MAX_COORD) return NOT_PACKED
        val g = group(key, s1, dot + 1, create)
        if (g < 0) return NO_GROUP
        return (g.toLong() shl 53) or (z.toLong() shl 48) or (x.toLong() shl 24) or y.toLong()
    }

    /** The group of [key]'s prefix (to [prefixEnd]) and extension (from [extStart]), made when [create]; -1 when none. */
    private fun group(key: String, prefixEnd: Int, extStart: Int, create: Boolean): Int {
        val extLen = key.length - extStart
        val last = lastGroup
        if (last >= 0) {
            val p = groupPrefix[last]
            val e = groupExt[last]
            if (p.length == prefixEnd && e.length == extLen && key.regionMatches(0, p, 0, prefixEnd) && key.regionMatches(extStart, e, 0, extLen)) return last
        }
        val name = key.substring(0, prefixEnd) + "\n" + key.substring(extStart)
        groupIds[name]?.let { lastGroup = it; return it }
        if (!create || groupPrefix.size >= MAX_GROUPS) return -1
        val g = groupPrefix.size
        groupPrefix += key.substring(0, prefixEnd)
        groupExt += key.substring(extStart)
        groupIds[name] = g
        lastGroup = g
        return g
    }

    // --- the table ---

    private fun home(k: Long): Int = ((k * -7046029254386353131L) ushr shift).toInt()

    private fun findPacked(k: Long): Int {
        val mask = table.size - 1
        var i = home(k)
        while (true) {
            val s = table[i]
            if (s == 0) return NONE
            if (keys[s - 1] == k) return s - 1
            i = (i + 1) and mask
        }
    }

    private fun tableInsert(k: Long, slot: Int) {
        if ((tableUsed + 1) * 4L > table.size * 3L) rehash(table.size * 2)
        val mask = table.size - 1
        var i = home(k)
        while (table[i] != 0) i = (i + 1) and mask
        table[i] = slot + 1
        tableUsed++
    }

    private fun tableRemove(k: Long) {
        val mask = table.size - 1
        var i = home(k)
        while (true) {
            val s = table[i]
            if (s == 0) return
            if (keys[s - 1] == k) break
            i = (i + 1) and mask
        }
        // Backward shift: move later entries of the run that may sit at i into the hole.
        var j = i
        while (true) {
            j = (j + 1) and mask
            val s = table[j]
            if (s == 0) break
            val h = home(keys[s - 1])
            val stays = if (i <= j) h in (i + 1)..j else h > i || h <= j
            if (!stays) {
                table[i] = s
                i = j
            }
        }
        table[i] = 0
        tableUsed--
    }

    private fun rehash(size: Int) {
        val old = table
        table = IntArray(size)
        shift = 64 - Integer.numberOfTrailingZeros(size)
        val mask = size - 1
        for (s in old) {
            if (s == 0) continue
            var i = home(keys[s - 1])
            while (table[i] != 0) i = (i + 1) and mask
            table[i] = s
        }
    }

    companion object {
        const val NONE = -1
        private const val PAGE_BITS = 14
        private const val PAGE = 1 shl PAGE_BITS
        private const val PAGE_MASK = PAGE - 1
        private const val USED = 1
        private const val PINNED = 2
        private const val STRING = 4

        /** Groups a packed key can name (11 bits; the twelfth value is the two markers' group). */
        const val MAX_GROUPS = 2047
        const val MAX_Z = 31
        const val MAX_COORD = 1 shl 24
        private const val NOT_PACKED = 2047L shl 53
        private const val NO_GROUP = (2047L shl 53) or 1L

        /** The decimal number in [s] from [from] to [to], or -1 (empty, a non-digit, a leading zero, more than 9 digits). */
        private fun number(s: String, from: Int, to: Int): Int {
            val n = to - from
            if (n <= 0 || n > 9 || (n > 1 && s[from] == '0')) return -1
            var v = 0
            for (i in from until to) {
                val c = s[i]
                if (c !in '0'..'9') return -1
                v = v * 10 + (c - '0')
            }
            return v
        }
    }
}
