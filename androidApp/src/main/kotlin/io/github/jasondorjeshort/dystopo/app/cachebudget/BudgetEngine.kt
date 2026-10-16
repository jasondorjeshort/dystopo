package io.github.jasondorjeshort.dystopo.app.cachebudget

import io.github.jasondorjeshort.dystopo.app.online.TileCache
import java.io.File

/** T112: one store the cache budget measures and evicts from ([BudgetEngine]); it may hold several kinds. */
interface BudgetStore {
    /** The kinds this store holds. */
    val kinds: Set<CacheKind>

    /** All the bytes that count against the budget (a cache's pinned tiles do not); cheap, read after every write. */
    fun total(): Long

    /** Those bytes per kind (may walk the store's index). */
    fun bytes(): Map<CacheKind, Long>

    /** When the oldest entry of [kind] that may go was last used (ms), or null when none may. */
    fun oldest(kind: CacheKind, spare: (String) -> Boolean): Long?

    /** Bytes of the budgeted entries [spare] keeps (in view or ahead): a run must leave them all. */
    fun sparedBytes(spare: (String) -> Boolean): Long = 0

    /** Brings [total] up to date where it is not kept as entries come and go (a directory's walk); the periodic check calls it. */
    fun refresh() {}

    /**
     * Evicts the least recently used entries of [kind] that [spare] does not keep until [bytes] are freed, stopping at
     * one last used after [notAfter]; returns the bytes freed.
     */
    fun evict(kind: CacheKind, bytes: Long, spare: (String) -> Boolean, notAfter: Long = Long.MAX_VALUE): Long
}

/**
 * T112: a [TileCache] as a budget store: its unpinned entries, [derivedKey] ones of [derived] and the others of
 * [fetched] (one of them null when the cache holds only one kind), evicted oldest-used first ([TileCache.evictOldest]).
 */
class TileCacheStore(
    val cache: TileCache,
    private val fetched: CacheKind?,
    private val derived: CacheKind?,
    private val derivedKey: (String) -> Boolean,
) : BudgetStore {
    override val kinds: Set<CacheKind> = setOfNotNull(fetched, derived)

    override fun total(): Long = cache.bytes - cache.pinnedBytes

    override fun bytes(): Map<CacheKind, Long> {
        if (fetched == null || derived == null) return mapOf((fetched ?: derived!!) to total())
        val d = cache.bytesWhere(pinned = false, matches = derivedKey)
        return mapOf(fetched to total() - d, derived to d)
    }

    private fun of(kind: CacheKind): (String) -> Boolean = when {
        fetched != null && derived != null -> if (kind == derived) derivedKey else { k -> !derivedKey(k) }
        else -> { _ -> true }
    }

    override fun oldest(kind: CacheKind, spare: (String) -> Boolean): Long? = if (kind in kinds) cache.oldestTouch(of(kind), spare) else null

    override fun sparedBytes(spare: (String) -> Boolean): Long = cache.bytesWhere(pinned = false, matches = spare)

    override fun evict(kind: CacheKind, bytes: Long, spare: (String) -> Boolean, notAfter: Long): Long =
        if (kind in kinds) cache.evictOldest(bytes, of(kind), spare, notAfter) else 0
}

/**
 * T112: a plain directory as a budget store (the phone's own source downloads, `cache/sources/`, which have no index):
 * its bytes from a walk at most every [walkEveryMs] (and never on a write's check), evicted oldest-written first (file times are not touched on a
 * read there), except the files named in [keepNames]. Its keys are not tiles, so nothing in it is spared for the view.
 */
class DirStore(
    private val kind: CacheKind,
    private val dir: File,
    private val keepNames: Set<String> = emptySet(),
    private val walkEveryMs: Long = 60_000,
    private val clock: () -> Long = System::currentTimeMillis,
) : BudgetStore {
    override val kinds: Set<CacheKind> = setOf(kind)
    @Volatile private var walked = -1L
    @Volatile private var walkedAt = 0L

    /** The bytes as of the last walk (a write's check must not walk a directory on a tile thread); the first call walks. */
    override fun total(): Long = if (walked < 0) walk() else walked

    /** Walks again when the last walk is older than [walkEveryMs] (a run, the 30 s check and `GET /storage` come here). */
    override fun bytes(): Map<CacheKind, Long> = mapOf(kind to if (walked < 0 || clock() - walkedAt >= walkEveryMs) walk() else walked)

    override fun refresh() {
        bytes()
    }

    private fun walk(): Long {
        walked = files().sumOf { it.length() }
        walkedAt = clock()
        return walked
    }

    private fun files(): List<File> = if (!dir.isDirectory) emptyList() else dir.walkTopDown().filter { it.isFile && it.name !in keepNames }.toList()

    override fun oldest(kind: CacheKind, spare: (String) -> Boolean): Long? =
        if (kind != this.kind) null else files().filter { !spare(it.relativeTo(dir).invariantSeparatorsPath) }.minOfOrNull { it.lastModified() }

    @Synchronized
    override fun evict(kind: CacheKind, bytes: Long, spare: (String) -> Boolean, notAfter: Long): Long {
        if (kind != this.kind || bytes <= 0) return 0
        var freed = 0L
        for (f in files().sortedBy { it.lastModified() }) {
            if (freed >= bytes || f.lastModified() > notAfter) break
            if (spare(f.relativeTo(dir).invariantSeparatorsPath)) continue
            val len = f.length()
            if (f.delete()) freed += len
        }
        walked = -1 // walk again next time
        return freed
    }
}

/**
 * T112: the budget's core over its [stores], pure apart from them so it is unit-tested with real caches in a temporary
 * directory (`CacheBudgetTest`): [enforce] runs [BudgetPlan.next] until the kinds fit under [totalBytes] less the slack,
 * or nothing above a floor may go; [guard] spares the view and the route ahead. A kind kept in two stores (contour and
 * shade tiles from packs and from downloaded DEM) gives up its oldest-used entries across both, in chunks.
 */
class BudgetEngine(
    val stores: List<BudgetStore>,
    private val totalBytes: () -> Long,
    val guard: CacheGuard,
    /** What a run frees below the total ([BudgetPlan.slackBytes]; tests pass none). */
    private val slack: (Long) -> Long = BudgetPlan::slackBytes,
) {
    /** One run of [enforce], for `GET /storage`. */
    data class Run(
        val at: Long, val ms: Long, val totalBytes: Long, val usedBefore: Long, val usedAfter: Long,
        val freedByKind: Map<String, Long>, val steps: Int, val fits: Boolean, val stuck: List<String>,
        /** Bytes of the tiles in view or ahead before and after the run (equal: none of them went). */
        val sparedBefore: Long = 0, val sparedAfter: Long = 0,
    ) {
        fun json(): Map<String, Any?> = linkedMapOf(
            "at" to at, "ms" to ms, "totalBytes" to totalBytes, "usedBefore" to usedBefore, "usedAfter" to usedAfter,
            "freedByKind" to freedByKind, "steps" to steps, "fits" to fits, "stuck" to stuck,
            "viewAndAheadBytesBefore" to sparedBefore, "viewAndAheadBytesAfter" to sparedAfter,
        )
    }

    fun total(): Long = totalBytes()

    /** Bytes per kind over every store (walks the indexes; a run and `GET /storage` use it). */
    fun used(): Map<CacheKind, Long> {
        val out = LinkedHashMap<CacheKind, Long>()
        for (k in CacheKind.entries) out[k] = 0L
        for (s in stores) for ((k, b) in s.bytes()) out[k] = out.getValue(k) + b
        return out
    }

    /** What the stores hold over the total (cheap: no index walk); a write checks it. */
    fun overBytes(): Long = stores.sumOf { it.total() } - total()

    /** [overBytes] after the stores that need it walked again (the periodic check, off the tile threads). */
    fun refreshedOverBytes(): Long {
        stores.forEach { it.refresh() }
        return overBytes()
    }

    /**
     * Evicts [bytes] of [kind], oldest-used first across the stores that hold it (the store with the oldest entry gives up
     * what is older than the other's oldest, and so on); returns the bytes freed.
     */
    private fun evict(kind: CacheKind, bytes: Long, spare: (String) -> Boolean): Long {
        val holders = stores.filter { kind in it.kinds }
        if (holders.size == 1) return holders[0].evict(kind, bytes, spare)
        var freed = 0L
        var rounds = 0
        while (freed < bytes) {
            val heads = holders.mapNotNull { h -> h.oldest(kind, spare)?.let { h to it } }.sortedBy { it.second }
            if (heads.isEmpty()) break
            // After many alternations, finish from the oldest store without a bound (each round walks the indexes).
            val bound = if (++rounds > MAX_MERGE_ROUNDS) Long.MAX_VALUE else heads.getOrNull(1)?.second ?: Long.MAX_VALUE
            val n = heads[0].first.evict(kind, bytes - freed, spare, bound)
            if (n <= 0) break
            freed += n
        }
        return freed
    }

    /** Evicts until the kinds fit (less the slack), derived data first, each kind down to its floor at most. */
    fun enforce(clock: () -> Long = System::currentTimeMillis): Run {
        val started = clock()
        val total = total()
        val before = used()
        val usedBefore = before.values.sum()
        val freed = LinkedHashMap<String, Long>()
        val exhausted = HashSet<CacheKind>()
        var steps = 0
        var sparedBefore = 0L
        var sparedAfter = 0L
        if (usedBefore > total) {
            val target = total - slack(total)
            val spare = guard.predicate()
            sparedBefore = stores.sumOf { it.sparedBytes(spare) }
            val used = HashMap(before)
            while (steps < MAX_STEPS) {
                val step = BudgetPlan.next(total, used, exhausted, target) ?: break
                val n = evict(step.kind, step.bytes, spare)
                steps++
                if (n <= 0) exhausted += step.kind
                else {
                    freed[step.kind.key] = (freed[step.kind.key] ?: 0L) + n
                    used[step.kind] = used.getValue(step.kind) - n
                }
            }
            sparedAfter = stores.sumOf { it.sparedBytes(spare) }
        }
        val usedAfter = used().values.sum()
        return Run(started, clock() - started, total, usedBefore, usedAfter, freed, steps, usedAfter <= total, exhausted.map { it.key }, sparedBefore, sparedAfter)
    }

    companion object {
        const val MAX_STEPS = 64
        private const val MAX_MERGE_ROUNDS = 32
    }
}
