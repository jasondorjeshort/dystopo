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
    // T249: the bucket used last, for a run of entries of an older minute (a snapshot's, filed by minute).
    private var lastMinute = Int.MIN_VALUE
    private var lastBucket: Bucket? = null

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

    /** T249: whether [slot]'s key is kept as a string (else [packedKey] is it, with its group in the top bits). */
    fun isStringKey(slot: Int): Boolean = flags[slot].toInt() and STRING != 0

    /** T249: [slot]'s packed key (meaningless for a key kept as a string). */
    fun packedKey(slot: Int): Long = keys[slot]

    fun groupExtOf(group: Int): String = groupExt[group]

    /** T249: the group of [prefix] and [ext], made when new; -1 when the groups are full. */
    fun internGroup(prefix: String, ext: String): Int {
        val name = "$prefix\n$ext"
        groupIds[name]?.let { return it }
        if (groupPrefix.size >= MAX_GROUPS) return -1
        val g = groupPrefix.size
        groupPrefix += prefix
        groupExt += ext
        groupIds[name] = g
        return g
    }

    /** T249: the slot of packed key [k] ([packedOf]), or [NONE]. */
    fun findPackedKey(k: Long): Int = findPacked(k)

    /** T249: adds packed key [k] (not held: the caller found it absent; its group made by [internGroup]) and returns its slot. */
    fun addPacked(k: Long, bytes: Int, minute: Int, pinned: Boolean): Int {
        val s = allocSlot()
        keys[s] = k
        flags[s] = USED.toByte()
        tableInsert(k, s)
        sizes[s] = bytes
        minutes[s] = minute
        prev[s] = NONE
        next[s] = NONE
        if (pinned) flags[s] = (flags[s].toInt() or PINNED).toByte() else link(s, minute)
        count++
        return s
    }

    /**
     * T249: files a snapshot's packed entries `order[from until to]` (indices into [keys] and its companions, sorted by
     * minute by the caller, so each minute's bucket is looked up once), their groups mapped by [groupMap] (none -1); skips a
     * key held already and one [skip] refuses; tells [added] each new slot. Adds to [totals]: entries, bytes, pinned
     * entries, pinned bytes. One call per chunk under the cache's lock: the loop stays in this class.
     */
    fun addLoaded(
        keys: LongArray, sizes: IntArray, minutes: IntArray, pinned: BooleanArray, order: IntArray, from: Int, to: Int,
        groupMap: IntArray, skip: ((Long) -> Boolean)?, added: ((Int) -> Unit)?, totals: LongArray,
    ) {
        for (o in from until to) {
            val j = order[o]
            val k = withGroup(keys[j], groupMap[groupOfKey(keys[j])])
            if (findPacked(k) != NONE) continue
            if (skip != null && skip(k)) continue
            val b = sizes[j]
            val s = addPacked(k, b, minutes[j], pinned[j])
            totals[0]++
            totals[1] += b
            if (pinned[j]) {
                totals[2]++
                totals[3] += b
            }
            added?.invoke(s)
        }
    }

    /** T249: the key a packed long names (its group made already). */
    fun keyOfPacked(k: Long): String {
        val g = groupOfKey(k)
        return StringBuilder(groupPrefix[g].length + 24).append(groupPrefix[g]).append('/').append(zOf(k)).append('/')
            .append(xOf(k)).append('/').append(yOf(k)).append('.').append(groupExt[g]).toString()
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
        if (minute == lastMinute) lastBucket?.let { return it }
        val b = buckets[minute] ?: Bucket().also { buckets[minute] = it }
        lastMinute = minute
        lastBucket = b
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
            if (m == lastMinute) lastBucket = null
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

        /** T249: a packed key from its parts (the layout [pack] makes). */
        fun packedOf(group: Int, z: Int, x: Int, y: Int): Long =
            (group.toLong() shl 53) or (z.toLong() shl 48) or (x.toLong() shl 24) or y.toLong()

        fun groupOfKey(k: Long): Int = (k ushr 53).toInt()
        fun zOf(k: Long): Int = ((k ushr 48) and 31).toInt()
        fun xOf(k: Long): Int = ((k ushr 24) and 0xffffff).toInt()
        fun yOf(k: Long): Int = (k and 0xffffff).toInt()

        /** T249: [k] with its group replaced by [group]. */
        fun withGroup(k: Long, group: Int): Long = (k and ((1L shl 53) - 1)) or (group.toLong() shl 53)

        /** T249: the decimal number of a key's segment as [pack] reads it, or -1 (public for the snapshot's reader). */
        fun numberOf(s: String, from: Int, to: Int): Int = number(s, from, to)

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

/**
 * T249: [TileIndex] kept on disk (`online/tiles.index`, beside the tile directory: [TileCache]'s snapshot), so a launch over
 * a large cache reads one file instead of walking a million. Versioned and self-describing:
 *
 * - a text header: the line [MAGIC], then `name: value` lines (`format`, `written` in epoch ms, `root` the tile directory's
 *   name, `entries`, `bytes`, `directories`, `groups`, and `layout`, the sentence below), then an empty line;
 * - the body ([LAYOUT]): the groups (a packed key's prefix and extension), then one record per directory (its path relative
 *   to the tile directory and its entries: a tile by its group and y, the directory giving z and x, or a name kept as a
 *   string; then its bytes, its touch minute and its flags);
 * - a trailer: the CRC-32 of every byte before it, as 8 bytes.
 *
 * [read] refuses a file of another magic or format, a bad checksum, or one that ends early or late ([Corrupt]); [validate]
 * compares a sample of its directories and entries with the disk. A later format (ADR 0060's ledger) bumps [FORMAT].
 */
internal object TileIndexSnapshot {
    const val MAGIC = "DYSTOPO-TILE-INDEX"
    const val FORMAT = 1
    const val LAYOUT = "UTF: a 2-byte length, then UTF-8; varint groups, each (UTF prefix, UTF ext); varint directories, each (UTF path, varint n, n entries: " +
        "varint tag (0: UTF name; g+1: tile of group g, then varint y), varint bytes, zigzag varint minute, byte flags (1 pinned)); " +
        "then the CRC-32 of all bytes before it as a long"

    class StringEntry(val key: String, val bytes: Int, val minute: Int, val pinned: Boolean)

    /** Entries: the packed keys in parallel arrays (their groups index [groups]) and the keys kept as strings. */
    class Entries(
        val groups: List<Pair<String, String>>,
        var keys: LongArray, var sizes: IntArray, var minutes: IntArray, var pinned: BooleanArray, var count: Int,
        val strings: MutableList<StringEntry>,
    ) {
        val total: Int get() = count + strings.size

        /** The key of packed entry [i]. */
        fun keyOf(i: Int): String {
            val k = keys[i]
            val (p, e) = groups[TileIndex.groupOfKey(k)]
            return "$p/${TileIndex.zOf(k)}/${TileIndex.xOf(k)}/${TileIndex.yOf(k)}.$e"
        }

        fun bytes(): Long {
            var b = 0L
            for (i in 0 until count) b += sizes[i]
            for (s in strings) b += s.bytes
            return b
        }
    }

    /** What [read] found: the header's lines, the entries, and the entries per directory path. */
    class Read(val header: Map<String, String>, val entries: Entries, val dirs: Map<String, Int>)

    class Corrupt(message: String) : java.io.IOException(message)

    /**
     * Writes [e] to [out] (sorted by key in place, a key listed twice once), made in memory first and its checksum taken in
     * one pass. [midway] runs once, after half the bytes are written (a debug hold, so a kill mid-write can be tried).
     * Returns the entries written.
     */
    fun write(out: java.io.OutputStream, e: Entries, writtenAt: Long, root: String, midway: (() -> Unit)? = null): Int {
        sortPacked(e)
        dedupe(e)
        e.strings.sortBy { it.key }
        run { // a key twice (moved while the index was copied in chunks): once
            var i = 1
            while (i < e.strings.size) if (e.strings[i].key == e.strings[i - 1].key) e.strings.removeAt(i) else i++
        }
        // The directory records: runs of packed keys with the same group, z and x; runs of strings with the same parent.
        val packedRuns = ArrayList<Int>()
        var i = 0
        while (i < e.count) {
            val d = e.keys[i] ushr 24
            var j = i + 1
            while (j < e.count && e.keys[j] ushr 24 == d) j++
            packedRuns += i
            i = j
        }
        val stringRuns = ArrayList<Int>()
        i = 0
        while (i < e.strings.size) {
            val d = parentOf(e.strings[i].key)
            var j = i + 1
            while (j < e.strings.size && parentOf(e.strings[j].key) == d) j++
            stringRuns += i
            i = j
        }
        val d = Buf(64 + e.total * 12 + e.groups.size * 64)
        val header = buildString {
            append(MAGIC).append('\n')
            append("format: ").append(FORMAT).append('\n')
            append("written: ").append(writtenAt).append('\n')
            append("root: ").append(root).append('\n')
            append("entries: ").append(e.total).append('\n')
            append("bytes: ").append(e.bytes()).append('\n')
            append("directories: ").append(packedRuns.size + stringRuns.size).append('\n')
            append("groups: ").append(e.groups.size).append('\n')
            append("layout: ").append(LAYOUT).append('\n')
            append('\n')
        }
        d.bytes(header.toByteArray(Charsets.UTF_8))
        d.varint(e.groups.size.toLong())
        for ((p, x) in e.groups) {
            d.utf(p)
            d.utf(x)
        }
        d.varint((packedRuns.size + stringRuns.size).toLong())
        for ((r, start) in packedRuns.withIndex()) {
            val end = if (r + 1 < packedRuns.size) packedRuns[r + 1] else e.count
            val k = e.keys[start]
            d.utf("${e.groups[TileIndex.groupOfKey(k)].first}/${TileIndex.zOf(k)}/${TileIndex.xOf(k)}")
            d.varint((end - start).toLong())
            for (s in start until end) {
                d.varint(TileIndex.groupOfKey(e.keys[s]) + 1L)
                d.varint(TileIndex.yOf(e.keys[s]).toLong())
                d.entryTail(e.sizes[s], e.minutes[s], e.pinned[s])
            }
        }
        for ((r, start) in stringRuns.withIndex()) {
            val end = if (r + 1 < stringRuns.size) stringRuns[r + 1] else e.strings.size
            d.utf(parentOf(e.strings[start].key))
            d.varint((end - start).toLong())
            for (s in start until end) {
                val en = e.strings[s]
                d.varint(0)
                d.utf(en.key.substringAfterLast('/'))
                d.entryTail(en.bytes, en.minute, en.pinned)
            }
        }
        val crc = java.util.zip.CRC32()
        crc.update(d.b, 0, d.n)
        val sum = crc.value
        for (i in 7 downTo 0) d.byte((sum ushr (i * 8)).toInt())
        val half = d.n / 2
        out.write(d.b, 0, half)
        if (midway != null) {
            out.flush()
            midway()
        }
        out.write(d.b, half, d.n - half)
        out.flush()
        return e.total
    }

    /** Reads a snapshot from [input] whole; a [Corrupt] says why it is refused. */
    fun read(input: java.io.InputStream): Read = read(input.readBytes())

    /**
     * Reads a snapshot from [bytes] (the whole file, read in one call): parsed from the array, then the checksum over it in
     * one pass (a stream read byte by byte cost 0.4 s for 50,000 entries on the emulator).
     */
    fun read(bytes: ByteArray): Read {
        val c = Cursor(bytes)
        try {
            if (c.line() != MAGIC) throw Corrupt("not a tile index")
            val header = LinkedHashMap<String, String>()
            while (true) {
                val l = c.line()
                if (l.isEmpty()) break
                if (header.size > 64) throw Corrupt("header too long")
                header[l.substringBefore(": ")] = l.substringAfter(": ", "")
            }
            val format = header["format"]?.toIntOrNull()
            if (format != FORMAT) throw Corrupt("format $format, this build reads $FORMAT")
            val total = header["entries"]?.toLongOrNull()?.takeIf { it in 0..MAX_ENTRIES } ?: throw Corrupt("entries ${header["entries"]}")
            val g = c.count(TileIndex.MAX_GROUPS.toLong(), "groups")
            val groups = ArrayList<Pair<String, String>>(g)
            repeat(g) { groups += c.utf() to c.utf() }
            val n = total.toInt()
            val keys = LongArray(n)
            val sizes = IntArray(n)
            val minutes = IntArray(n)
            val pinned = BooleanArray(n)
            var count = 0
            val strings = ArrayList<StringEntry>()
            val dirs = HashMap<String, Int>()
            val records = c.count(MAX_ENTRIES, "directories")
            repeat(records) {
                val path = c.utf()
                val m = c.count(MAX_ENTRIES, "a directory's entries")
                if (count + strings.size + m > n) throw Corrupt("more entries than the header's $n")
                dirs[path] = (dirs[path] ?: 0) + m
                var zx = -1L
                var prefix: String? = null
                for (e in 0 until m) {
                    val tag = c.count(TileIndex.MAX_GROUPS.toLong() + 1, "a tag")
                    if (tag == 0) {
                        val name = c.utf()
                        val b = c.count(Int.MAX_VALUE.toLong(), "bytes")
                        val minute = unzigzag(c.varint()).toInt()
                        val flags = c.byte()
                        strings += StringEntry(if (path.isEmpty()) name else "$path/$name", b, minute, flags and 1 != 0)
                    } else {
                        val gi = tag - 1
                        if (gi >= g) throw Corrupt("group $gi of $g")
                        if (prefix == null) {
                            val s3 = path.lastIndexOf('/')
                            val s2 = if (s3 > 0) path.lastIndexOf('/', s3 - 1) else -1
                            if (s2 <= 0) throw Corrupt("tile directory '$path'")
                            val x = TileIndex.numberOf(path, s3 + 1, path.length)
                            val z = TileIndex.numberOf(path, s2 + 1, s3)
                            if (x !in 0 until TileIndex.MAX_COORD || z !in 0..TileIndex.MAX_Z) throw Corrupt("tile directory '$path'")
                            prefix = path.substring(0, s2)
                            zx = TileIndex.packedOf(0, z, x, 0)
                        }
                        if (groups[gi].first != prefix) throw Corrupt("group $gi in '$path'")
                        val y = c.count(TileIndex.MAX_COORD - 1L, "y")
                        sizes[count] = c.count(Int.MAX_VALUE.toLong(), "bytes")
                        minutes[count] = unzigzag(c.varint()).toInt()
                        pinned[count] = c.byte() and 1 != 0
                        keys[count] = zx or (gi.toLong() shl 53) or y.toLong()
                        count++
                    }
                }
            }
            if (count + strings.size != n) throw Corrupt("${count + strings.size} entries, the header says $n")
            val end = c.at
            if (bytes.size - end < 8) throw Corrupt("ends early")
            if (bytes.size - end > 8) throw Corrupt("bytes after the checksum")
            val crc = java.util.zip.CRC32()
            crc.update(bytes, 0, end)
            var trailer = 0L
            for (i in end until end + 8) trailer = (trailer shl 8) or (bytes[i].toLong() and 0xff)
            if (trailer != crc.value) throw Corrupt("bad checksum")
            return Read(header, Entries(groups, keys, sizes, minutes, pinned, count, strings), dirs)
        } catch (e: IndexOutOfBoundsException) {
            throw Corrupt("ends early")
        }
    }

    /** A reading position in a snapshot's bytes: varints, `writeUTF` strings (2-byte length, then the bytes) and header lines. */
    private class Cursor(val b: ByteArray) {
        var at = 0

        fun byte(): Int = b[at++].toInt() and 0xff

        fun varint(): Long {
            var v = 0L
            var shift = 0
            while (true) {
                val x = b[at++].toInt()
                v = v or ((x and 0x7f).toLong() shl shift)
                if (x and 0x80 == 0) return v
                shift += 7
                if (shift > 63) throw Corrupt("a bad number")
            }
        }

        fun count(max: Long, what: String): Int {
            val v = varint()
            if (v < 0 || v > max) throw Corrupt("$what $v")
            return v.toInt()
        }

        fun utf(): String {
            val n = ((b[at].toInt() and 0xff) shl 8) or (b[at + 1].toInt() and 0xff)
            at += 2
            if (at + n > b.size) throw IndexOutOfBoundsException()
            return String(b, at, n, Charsets.UTF_8).also { at += n } // tile keys hold no NUL and no surrogates: UTF-8 as written
        }

        fun line(): String {
            val from = at
            while (b[at] != '\n'.code.toByte()) {
                if (at - from >= 4096) throw Corrupt("a header line too long")
                at++
            }
            return String(b, from, at++ - from, Charsets.UTF_8)
        }
    }

    /**
     * Compares a sample of [r] with the directory [root]: every directory above the entries when there are fewer than
     * [dirSample] (else the root and a random [dirSample] less one), [leafSample] random directories holding entries
     * (their file counts), and [entrySample] random entries (each file's size; all of them when there are no more). Null when they agree, else the first
     * disagreement. A directory may hold empty directories the snapshot does not name (a column whose tiles were all
     * evicted); `.tmp` files are not counted.
     */
    fun validate(root: java.io.File, r: Read, random: kotlin.random.Random, dirSample: Int = 64, leafSample: Int = 32, entrySample: Int = 64): String? {
        val children = HashMap<String, HashSet<String>>()
        children[""] = HashSet()
        for (p in r.dirs.keys) {
            var path = p
            while (path.isNotEmpty()) {
                val cut = path.lastIndexOf('/')
                val parent = if (cut < 0) "" else path.substring(0, cut)
                if (!children.getOrPut(parent) { HashSet() }.add(path.substring(cut + 1))) break
                path = parent
            }
        }
        val interior = children.keys.filter { it.isNotEmpty() }
        val dirs = LinkedHashSet<String>()
        dirs += ""
        dirs += if (interior.size < dirSample) interior else interior.shuffled(random).take(dirSample - 1)
        val leaves = r.dirs.keys.toList()
        dirs += if (leaves.size <= leafSample) leaves else leaves.shuffled(random).take(leafSample)
        for (p in dirs) checkDir(root, p, children[p].orEmpty(), r.dirs[p] ?: 0)?.let { return it }
        val e = r.entries
        val all = e.total <= entrySample // a small index: every entry
        if (e.total > 0) repeat(if (all) e.total else entrySample) { n ->
            val i = if (all) n else random.nextInt(e.total)
            val (key, bytes) = if (i < e.count) e.keyOf(i) to e.sizes[i] else e.strings[i - e.count].let { it.key to it.bytes }
            val f = java.io.File(root, key)
            val len = f.length()
            if (len != bytes.toLong() || (len == 0L && !f.isFile)) return "the entry $key: ${if (f.isFile) "$len bytes, the snapshot says $bytes" else "no file"}"
        }
        return null
    }

    private fun checkDir(root: java.io.File, path: String, dirs: Set<String>, files: Int): String? {
        val at = if (path.isEmpty()) root else java.io.File(root, path)
        val names = at.list() ?: return "the directory '$path' is missing"
        var seen = 0
        val others = ArrayList<String>()
        for (n in names) {
            if (n.endsWith(".tmp")) continue
            if (n in dirs) seen++ else others += n
        }
        if (seen < dirs.size) return "the directory '$path' lacks ${dirs.size - seen} of its ${dirs.size} directories"
        if (others.size == files) return null
        if (files == 0) {
            val budget = intArrayOf(64)
            if (others.all { emptyTree(java.io.File(at, it), budget) }) return null
        }
        return "the directory '$path' holds ${others.size} names besides its directories, the snapshot $files files"
    }

    /** Whether [f] is a directory with no file in it (`.tmp` aside), looking at no more than [budget] directories. */
    private fun emptyTree(f: java.io.File, budget: IntArray): Boolean {
        if (--budget[0] < 0) return false
        val names = f.list() ?: return false // a file
        return names.all { it.endsWith(".tmp") || emptyTree(java.io.File(f, it), budget) }
    }

    private const val MAX_ENTRIES = 100_000_000L

    private fun parentOf(key: String): String = key.substringBeforeLast('/', "")

    private fun zigzag(v: Long): Long = (v shl 1) xor (v shr 63)
    private fun unzigzag(v: Long): Long = (v ushr 1) xor -(v and 1)

    /** A growable byte buffer the writer fills: varints, `writeUTF`'s strings (2-byte length, then UTF-8) and raw bytes. */
    private class Buf(size: Int) {
        var b = ByteArray(maxOf(size, 64))
        var n = 0

        private fun room(k: Int) {
            if (n + k > b.size) b = b.copyOf(maxOf(b.size * 2, n + k))
        }

        fun byte(v: Int) {
            room(1)
            b[n++] = v.toByte()
        }

        fun bytes(a: ByteArray) {
            room(a.size)
            System.arraycopy(a, 0, b, n, a.size)
            n += a.size
        }

        fun varint(value: Long) {
            room(10)
            var v = value
            while (v and 0x7fL.inv() != 0L) {
                b[n++] = ((v and 0x7f) or 0x80).toByte()
                v = v ushr 7
            }
            b[n++] = v.toByte()
        }

        fun utf(s: String) {
            val a = s.toByteArray(Charsets.UTF_8)
            require(a.size <= 0xffff) { "a name of ${a.size} bytes" }
            byte(a.size ushr 8)
            byte(a.size and 0xff)
            bytes(a)
        }

        fun entryTail(bytes: Int, minute: Int, pinned: Boolean) {
            varint(bytes.toLong())
            varint(zigzag(minute.toLong()))
            byte(if (pinned) 1 else 0)
        }
    }

    /** Sorts the packed entries of [e] by key (their companions with them): a quicksort with insertion sort for short runs. */
    private fun sortPacked(e: Entries) {
        val k = e.keys
        val s = e.sizes
        val m = e.minutes
        val p = e.pinned
        fun swap(a: Int, b: Int) {
            val t = k[a]; k[a] = k[b]; k[b] = t
            val u = s[a]; s[a] = s[b]; s[b] = u
            val v = m[a]; m[a] = m[b]; m[b] = v
            val w = p[a]; p[a] = p[b]; p[b] = w
        }
        if (e.count < 2) return
        val stack = ArrayDeque<Long>()
        stack.addLast(e.count.toLong() - 1) // (lo shl 32) or hi, lo 0
        while (stack.isNotEmpty()) {
            val r = stack.removeLast()
            var lo = (r ushr 32).toInt()
            var hi = (r and 0xffffffffL).toInt()
            while (hi - lo > 16) {
                val mid = (lo + hi) ushr 1
                if (k[mid] < k[lo]) swap(mid, lo)
                if (k[hi] < k[lo]) swap(hi, lo)
                if (k[hi] < k[mid]) swap(hi, mid)
                val pivot = k[mid]
                var i = lo
                var j = hi
                while (i <= j) {
                    while (k[i] < pivot) i++
                    while (k[j] > pivot) j--
                    if (i <= j) {
                        swap(i, j)
                        i++
                        j--
                    }
                }
                // The smaller side next, the larger on the stack: the stack stays shallow.
                if (j - lo < hi - i) {
                    if (i < hi) stack.addLast((i.toLong() shl 32) or hi.toLong())
                    hi = j
                } else {
                    if (lo < j) stack.addLast((lo.toLong() shl 32) or j.toLong())
                    lo = i
                }
            }
            for (a in lo + 1..hi) {
                var b = a
                while (b > lo && k[b - 1] > k[b]) {
                    swap(b - 1, b)
                    b--
                }
            }
        }
    }

    /** Drops a packed key listed twice (sorted first), keeping the later copy. */
    private fun dedupe(e: Entries) {
        if (e.count < 2) return
        var w = 0
        for (r in 0 until e.count) {
            if (w > 0 && e.keys[w - 1] == e.keys[r]) w--
            e.keys[w] = e.keys[r]
            e.sizes[w] = e.sizes[r]
            e.minutes[w] = e.minutes[r]
            e.pinned[w] = e.pinned[r]
            w++
        }
        e.count = w
    }
}
