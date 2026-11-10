package io.github.jasondorjeshort.dystopo.sources.elevation

import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import io.github.jasondorjeshort.dystopo.tiff.ByteRangeSource
import io.github.jasondorjeshort.dystopo.tiff.Decompressor
import io.github.jasondorjeshort.dystopo.tiff.GeoTiff
import kotlin.concurrent.Volatile
import kotlin.jvm.Synchronized
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * HTTP range reads with an on-disk cache per (offset, length) under [cacheDir], so re-running a render doesn't
 * re-download. [fetches] counts network requests and [fetchedBytes] their bytes. [fetchRange] (url, offset, length,
 * If-Match) and [head] are the network layer; tests swap in fakes.
 *
 * T269: the blocks are validated against the file they came from ([RangeCell]). The first response's ETag (else its
 * Last-Modified with the file's length, else the length) is kept in `validator.json` beside the blocks and sent as
 * `If-Match` on every later range GET; a 412, or a response naming another version of the file, drops the whole cell
 * and fails the read with [CellChanged] ([RemoteTiff] reopens the file and reads again). A cell checked more than a
 * day ago gets one HEAD in the background (a client without HEAD compares the cached header block with a fresh copy);
 * a cell cached before T269 has no validator and is dropped when the file is newer than its oldest block. A check
 * never holds a read up, and a failed one (offline) changes nothing. [cellsChecked], [cellsDropped] and [ifMatch412]
 * count what this reader started or saw; [RangeValidation] has the process totals.
 */
class CachedRangeSource(
    private val url: String,
    private val cacheDir: String,
    private val fetchRange: (String, Long, Int, String?) -> HttpReply = SourceIo::rangeReply,
    private val head: (String) -> HttpReply? = { SourceIo.head(it) },
) : ByteRangeSource {
    @Volatile var fetches = 0L
        private set
    @Volatile var fetchedBytes = 0L
        private set

    internal val checked = Counter()
    internal val dropped = Counter()
    internal val preconditionFailed = Counter()

    /** T269: validity checks of this file this reader made (a HEAD, a header-block compare, or a first response judged). */
    val cellsChecked: Long get() = checked.get()

    /** T269: times this reader dropped its file's cached blocks as stale. */
    val cellsDropped: Long get() = dropped.get()

    /** T269: range GETs of this reader answered 412 (the file changed since its validator). */
    val ifMatch412: Long get() = preconditionFailed.get()

    private val cell = RangeValidation.cell(cacheDir)

    /** T269: the cell's generation; a reader opened at one ([at]) fails with [CellChanged] once the cell is dropped. */
    val generation: Int get() = cell.generation

    @Synchronized
    private fun count(bytes: Int) {
        fetches++
        fetchedBytes += bytes
    }

    override fun read(offset: Long, length: Int): ByteArray = read(offset, length, cell.generation)

    /** T269: this source as seen by a reader opened at generation [gen]. */
    fun at(gen: Int): ByteRangeSource = ByteRangeSource { offset, length -> read(offset, length, gen) }

    /** Bytes [offset, offset + length) for a reader opened at generation [gen]; [CellChanged] when the cell was dropped since. */
    fun read(offset: Long, length: Int, gen: Int): ByteArray {
        cell.maybeCheck(this)
        val path = "$cacheDir/$offset-$length.bin"
        cell.cached(path)?.let { SourceTiming.diskHits.increment(); return cell.ensure(gen, it) }
        // T88: renders of neighbouring tiles start together and want the same blocks: one download, the others wait for it.
        val once = inFlightReads.getOrPut(path) { Once() }
        try {
            return cell.ensure(gen, once.get({ SourceTiming.rangeShared.increment() }) { download(path, offset, length) })
        } finally {
            inFlightReads.remove(path)
        }
    }

    private fun download(path: String, offset: Long, length: Int): ByteArray {
        cell.cached(path)?.let { SourceTiming.diskHits.increment(); return it }
        val (gen, ifMatch) = cell.request()
        val t0 = nanoTime()
        val reply = fetchRange(url, offset, length, ifMatch)
        val took = nanoTime() - t0
        SourceTiming.addRead(took)
        if (reply.status == 412) {
            preconditionFailed.increment()
            RangeValidation.ifMatch412.increment()
            cell.drop(gen, null, this)
            throw CellChanged(url)
        }
        val bytes = reply.body
        SourceTiming.onRead?.invoke(url, bytes.size, took)
        count(bytes.size)
        cell.observe(gen, Validator.of(reply, currentTimeMillis()), this)
        // Parallel tile renders can fetch the same cold range at once: each writes its own temp file, the first
        // to land wins, and the others return the winner's bytes so every caller agrees.
        return cell.store(gen, path, bytes)
    }

    /** T269: one check of the file now (a decode error on cached blocks); true when the cell was dropped since [gen]. */
    fun recheck(gen: Int): Boolean = cell.recheck(gen, this)

    internal fun headNow(): HttpReply? = head(url)

    internal fun rangeNow(offset: Long, length: Int): HttpReply = fetchRange(url, offset, length, null)
}

/** T269: the cached blocks of [what] belonged to a version of the file the server no longer has; they were dropped. */
class CellChanged(what: String) : RuntimeException("the cached blocks of $what were dropped: the file changed on the server")

/**
 * T269: what names one version of a remote file: its ETag verbatim, its Last-Modified and its length (-1 unknown),
 * with [checkedAt] the time it was last confirmed (epoch ms). A client that reports no headers gives an empty one,
 * which still carries the check time.
 */
internal class Validator(val etag: String?, val lastModified: String?, val length: Long, val checkedAt: Long) {
    val usable: Boolean get() = etag != null || lastModified != null || length >= 0

    /** True or false when the two can be compared: ETags when both have one, else Last-Modified with the length, else the length. */
    fun sameFile(other: Validator): Boolean? = when {
        etag != null && other.etag != null -> etag == other.etag
        lastModified != null && other.lastModified != null && length >= 0 && other.length >= 0 ->
            lastModified == other.lastModified && length == other.length
        length >= 0 && other.length >= 0 -> length == other.length
        else -> null
    }

    fun checkedNow(now: Long) = Validator(etag, lastModified, length, now)

    fun json(): ByteArray = MiniJson.encode(
        linkedMapOf<String, Any?>("etag" to etag, "lastModified" to lastModified, "length" to length, "checkedAt" to checkedAt)
            .filterValues { it != null },
    ).encodeToByteArray()

    companion object {
        fun parse(bytes: ByteArray): Validator? = try {
            val m = MiniJson.parseObject(bytes.decodeToString())
            Validator(m["etag"] as? String, m["lastModified"] as? String, (m["length"] as? Number)?.toLong() ?: -1L, (m["checkedAt"] as? Number)?.toLong() ?: 0L)
        } catch (e: IllegalArgumentException) {
            null // a broken file reads as none: the cell is checked as one cached before T269
        }

        /** The validator a response gives: the file's length from Content-Range on a 206, Content-Length on a 200 or a HEAD. */
        fun of(reply: HttpReply, now: Long): Validator {
            val length = reply.header("content-range")?.substringAfterLast('/', "")?.trim()?.toLongOrNull()
                ?: if (reply.status == 200) reply.header("content-length")?.trim()?.toLongOrNull() ?: -1L else -1L
            return Validator(reply.header("etag"), reply.header("last-modified"), length, now)
        }

        private val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

        /** An HTTP date (`Wed, 13 Aug 2025 20:44:12 GMT`) in epoch ms, or null. */
        fun httpDateMs(text: String): Long? {
            val p = text.trim().split(' ', ':').filter { it.isNotEmpty() }
            if (p.size < 7) return null
            val day = p[1].toIntOrNull() ?: return null
            val month = months.indexOf(p[2]) + 1
            val year = p[3].toIntOrNull() ?: return null
            val h = p[4].toIntOrNull() ?: return null
            val mi = p[5].toIntOrNull() ?: return null
            val sec = p[6].toIntOrNull() ?: return null
            if (month == 0) return null
            // Days from the civil date (Howard Hinnant's days_from_civil).
            val y = if (month <= 2) year - 1 else year
            val era = (if (y >= 0) y else y - 399) / 400
            val yoe = y - era * 400
            val doy = (153 * (month + (if (month > 2) -3 else 9)) + 2) / 5 + day - 1
            val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
            val days = era * 146097L + doe - 719468
            return ((days * 24 + h) * 60 + mi) * 60_000L + sec * 1000L
        }
    }
}

/**
 * T269: the range caches' validation, process-wide: one [RangeCell] per cache directory (every reader of a file shares
 * it), the background checks, and the counters (`cellsChecked`, `cellsDropped`, `ifMatch412`, `checkFailures`).
 */
object RangeValidation {
    /** A cell's validator is confirmed at most this often (one behaviour, no setting: ADR 0044). */
    const val CHECK_EVERY_MS = 24L * 3600 * 1000

    /** A check that failed (offline) is tried again after this, at the earliest. */
    const val RETRY_MS = 15L * 60 * 1000

    val cellsChecked = Counter()
    val cellsDropped = Counter()
    val ifMatch412 = Counter()
    val checkFailures = Counter()
    private val running = Counter()
    private val cells = SyncMap<String, RangeCell>()

    internal fun cell(dir: String): RangeCell = cells.getOrPut(dir) { RangeCell(dir) }

    internal fun background(task: () -> Unit) {
        running.increment()
        runInBackground {
            try {
                task()
            } finally {
                running.add(-1)
            }
        }
    }

    /** Waits up to [timeoutMs] for the background checks; false if some still run (`devtool render` waits before its report). */
    fun awaitChecks(timeoutMs: Long): Boolean {
        val until = currentTimeMillis() + timeoutMs
        while (running.get() > 0) {
            if (currentTimeMillis() >= until) return false
            sleepMs(10)
        }
        return true
    }

    fun json(): Map<String, Long> = linkedMapOf(
        "cellsChecked" to cellsChecked.get(), "cellsDropped" to cellsDropped.get(), "ifMatch412" to ifMatch412.get(),
        "checkFailures" to checkFailures.get(),
    )

    /** Tests: forgets every cell's in-memory state, as a new process would start. */
    internal fun forgetCells() = cells.clear()
}

/** The T269 counters of [sources], for a source's report beside its `rangeRequests`. */
fun rangeValidationCounters(sources: Collection<CachedRangeSource>, prefix: String = ""): Map<String, Long> = linkedMapOf(
    "${prefix}cellsChecked" to sources.sumOf { it.cellsChecked },
    "${prefix}cellsDropped" to sources.sumOf { it.cellsDropped },
    "${prefix}ifMatch412" to sources.sumOf { it.ifMatch412 },
)

/** T269: the validation state of one cache directory (one remote file); see [CachedRangeSource]. */
internal class RangeCell(private val dir: String) {
    @Volatile var generation = 0
        private set
    @Volatile private var quietUntil = 0L
    @Volatile private var purgeBefore = 0L
    private var loaded = false
    private var validator: Validator? = null
    private var legacy = false
    private var forced = false
    private val validatorPath = "$dir/validator.json"

    private fun load() {
        if (loaded) return
        loaded = true
        validator = SourceFiles.read(validatorPath)?.let { Validator.parse(it) }
        legacy = validator == null && blocks().isNotEmpty()
    }

    private fun blocks(): List<String> = SourceFiles.list(dir).filter { it.endsWith(".bin") }

    private fun oldestBlockMs(): Long = blocks().minOfOrNull { SourceFiles.lastModifiedMs("$dir/$it") } ?: Long.MAX_VALUE

    /** A cached block's bytes, or null (a block a failed drop left behind is deleted on sight). */
    fun cached(path: String): ByteArray? {
        val bytes = SourceFiles.read(path) ?: return null
        val before = purgeBefore
        if (before > 0 && SourceFiles.lastModifiedMs(path) < before) {
            SourceFiles.delete(path)
            return null
        }
        return bytes
    }

    fun ensure(gen: Int, bytes: ByteArray): ByteArray {
        if (generation != gen) throw CellChanged(dir)
        return bytes
    }

    /** The generation a request starts at and the ETag it sends as If-Match (none when weak: If-Match compares strongly). */
    @Synchronized
    fun request(): Pair<Int, String?> {
        load()
        return generation to validator?.etag?.takeUnless { it.startsWith("W/") }
    }

    /** Writes a downloaded block unless the cell was dropped since its request started; the winner's bytes on a race. */
    @Synchronized
    fun store(gen: Int, path: String, bytes: ByteArray): ByteArray {
        if (generation != gen) return bytes
        return if (SourceFiles.write(path, bytes)) bytes else SourceFiles.read(path) ?: bytes
    }

    /** Starts a background check when one is due: blocks without a validator, or a validator checked over a day ago. */
    fun maybeCheck(source: CachedRangeSource) {
        if (currentTimeMillis() < quietUntil) return
        if (claim(currentTimeMillis())) RangeValidation.background { check(source, force = false) }
    }

    @Synchronized
    private fun claim(now: Long): Boolean {
        if (now < quietUntil) return false
        load()
        if (!due(now)) {
            quietUntil = (validator?.checkedAt ?: now) + RangeValidation.CHECK_EVERY_MS
            return false
        }
        quietUntil = now + RangeValidation.RETRY_MS
        return true
    }

    private fun due(now: Long): Boolean = legacy || validator.let { it != null && now - it.checkedAt >= RangeValidation.CHECK_EVERY_MS }

    @Synchronized
    private fun snapshot(): Triple<Int, Validator?, Boolean> {
        load()
        return Triple(generation, validator, legacy)
    }

    /** One forced check per cell and process, after a decode error on its blocks; true when the cell was dropped since [gen]. */
    fun recheck(gen: Int, source: CachedRangeSource): Boolean {
        if (generation != gen) return true
        if (!claimForced()) return false
        check(source, force = true)
        return generation != gen
    }

    @Synchronized
    private fun claimForced(): Boolean {
        if (forced) return false
        forced = true
        return true
    }

    /**
     * Checks the file: a HEAD's validator against the stored one (or, for a cell cached before T269, its Last-Modified
     * against the oldest block); where the HEAD cannot decide, the cached header block against a fresh read of it.
     * A match stores the check time; a mismatch drops the cell; a failure (offline, an error status) changes nothing.
     */
    private fun check(source: CachedRangeSource, force: Boolean) {
        val (gen, stored, wasLegacy) = snapshot()
        val now = currentTimeMillis()
        // A response confirmed the file while this check waited for its thread.
        if (!force && !wasLegacy && (stored == null || now - stored.checkedAt < RangeValidation.CHECK_EVERY_MS)) return
        var fresh: Validator? = null
        var same: Boolean? = null
        try {
            val headed = source.headNow()?.takeIf { it.status in 200..299 }?.let { Validator.of(it, now) }?.takeIf { it.usable }
            if (headed != null) {
                fresh = headed
                same = when {
                    stored != null -> stored.sameFile(headed)
                    wasLegacy -> headed.lastModified?.let { Validator.httpDateMs(it) }?.let { it <= oldestBlockMs() }
                    else -> true
                }
            }
            if (same == null) {
                val block = firstBlock()
                if (block == null) {
                    // Nothing cached to compare: keep what the HEAD said, or the bare check time.
                    adopt(gen, fresh ?: stored ?: Validator(null, null, -1, now), now)
                    return
                }
                val cachedBytes = SourceFiles.read("$dir/${block.third}") ?: return
                val reply = source.rangeNow(block.first, block.second)
                if (reply.status != 200 && reply.status != 206) throw HttpStatusException(reply.status, dir)
                val body = if (reply.status == 200 && reply.body.size > cachedBytes.size) reply.body.copyOf(cachedBytes.size) else reply.body
                if (fresh == null) fresh = Validator.of(reply, now)
                same = body.contentEquals(cachedBytes)
            }
        } catch (e: Exception) {
            RangeValidation.checkFailures.increment()
            return // offline or refused: the cache is served as it is and the check time does not advance
        }
        source.checked.increment()
        RangeValidation.cellsChecked.increment()
        if (same == true) adopt(gen, fresh?.takeIf { it.usable } ?: stored ?: Validator(null, null, -1, now), now) else drop(gen, fresh, source)
    }

    /** The cached block with the lowest offset (the header block, `0-65536.bin`, once the file was opened): offset, length, name. */
    private fun firstBlock(): Triple<Long, Int, String>? = blocks().mapNotNull { name ->
        val parts = name.removeSuffix(".bin").split('-')
        val offset = parts.getOrNull(0)?.toLongOrNull()
        val length = parts.getOrNull(1)?.toIntOrNull()
        if (parts.size == 2 && offset != null && length != null) Triple(offset, length, name) else null
    }.minByOrNull { it.first }

    @Synchronized
    private fun adopt(gen: Int, v: Validator, now: Long) {
        if (generation != gen) return
        writeValidator(v.checkedNow(now))
    }

    private fun writeValidator(v: Validator) {
        validator = v
        legacy = false
        SourceFiles.replace(validatorPath, v.json())
        quietUntil = v.checkedAt + RangeValidation.CHECK_EVERY_MS
    }

    /**
     * What a response said about the file: the first response's validator is kept; a cell cached before T269 is judged
     * by its Last-Modified; another version of the file drops the cell and fails the read ([CellChanged]).
     */
    @Synchronized
    fun observe(gen: Int, seen: Validator, source: CachedRangeSource) {
        if (generation != gen) return
        load()
        val stored = validator
        val now = seen.checkedAt
        if (stored == null) {
            if (legacy) {
                val lm = seen.lastModified?.let { Validator.httpDateMs(it) } ?: return // the background check decides
                source.checked.increment()
                RangeValidation.cellsChecked.increment()
                if (lm > oldestBlockMs()) {
                    drop(gen, seen, source)
                    throw CellChanged(dir)
                }
            }
            writeValidator(seen)
            return
        }
        when (stored.sameFile(seen)) {
            false -> {
                drop(gen, seen, source)
                throw CellChanged(dir)
            }
            true -> if (now - stored.checkedAt >= RangeValidation.CHECK_EVERY_MS) writeValidator(stored.checkedNow(now))
            null -> {}
        }
    }

    /**
     * Drops the cell (unless already dropped since [gen]): every block deleted, none kept because it happens to match
     * (offsets point into the whole file); [fresh], the new version's validator, stored when known, else the next
     * response's is.
     */
    @Synchronized
    fun drop(gen: Int, fresh: Validator?, source: CachedRangeSource) {
        if (generation != gen) return
        generation++
        val at = currentTimeMillis()
        for (name in SourceFiles.list(dir)) if (name.endsWith(".bin") || name.endsWith(".part")) SourceFiles.delete("$dir/$name")
        // A file a reader holds open cannot be deleted on Windows: what is left is skipped and deleted on sight.
        if (blocks().isNotEmpty()) purgeBefore = at + 1
        source.dropped.increment()
        RangeValidation.cellsDropped.increment()
        legacy = false
        if (fresh != null && fresh.usable) {
            writeValidator(fresh.checkedNow(at))
        } else {
            validator = null
            SourceFiles.delete(validatorPath)
            quietUntil = at + RangeValidation.CHECK_EVERY_MS
        }
    }
}

/**
 * A Cloud-Optimized GeoTIFF read over HTTP range requests, with raw bytes cached on disk and decoded tiles
 * cached in memory per resolution level. [extraDecompressors] add codecs by TIFF compression code (LERC for New
 * Zealand's DEM).
 */
class RemoteTiff(url: String, cacheDir: String, extraDecompressors: Map<Int, Decompressor> = emptyMap()) {
    companion object {
        /** T88: forgets this thread's last sampled block (see [LastBlock]); sources call it when a window is done. */
        fun releaseThreadBlock() {
            lastBlock.get()?.apply { tiff = null; values = null; level = -1; key = -1; id = -1 }
        }

        /** A [RemoteTiff] for [url]: from [SourceIo.tiffPool] when one is set (the phone), else a new one (the server, as before). */
        fun open(url: String, cacheDir: String, extraDecompressors: Map<Int, Decompressor> = emptyMap()): RemoteTiff =
            SourceIo.tiffPool?.get(url) { RemoteTiff(url, cacheDir, extraDecompressors) } ?: RemoteTiff(url, cacheDir, extraDecompressors)

        /** [RemoteTiff.levelFor] over a file's level pixel sizes ([scales], CRS units, finest first). */
        fun levelFor(scales: List<Double>, targetMeters: Double, metersPerUnit: Double): Int {
            var best = 0
            for ((i, scale) in scales.withIndex()) if (scale * metersPerUnit <= targetMeters) best = i
            return best
        }

        /**
         * T87: one level for a window read from several files ([cells], each its level pixel sizes): the finest of
         * their own [levelFor] choices, which every file has. Files differ in how many overviews they carry (3DEP's
         * mostly-ocean Hawaii cells have fewer), so one file's choice can be past another's last level.
         */
        fun commonLevel(cells: List<List<Double>>, targetMeters: Double, metersPerUnit: Double): Int =
            cells.minOfOrNull { levelFor(it, targetMeters, metersPerUnit) } ?: 0
    }

    val source = CachedRangeSource(url, cacheDir)
    private val codecs = mapOf(8 to zlibDecompressor, 32946 to zlibDecompressor) + extraDecompressors

    /** T269: the file as opened at one generation of its cache, with its decoded tiles; replaced when the cache is dropped. */
    private class Opened(val gen: Int, val tiff: GeoTiff) {
        val levels = tiff.levels
        val caches = levels.map { SyncMap<Int, FloatArray>() }
        val id = nextId()
    }

    @Volatile private var opened: Opened = open()
    val tiff: GeoTiff get() = opened.tiff
    private val levels: List<GeoTiff> get() = opened.levels
    private val shared = SourceIo.decodedBlocks
    private var decoded = 0

    /**
     * T269: opens the file from its cache. Stale cached blocks (the cell dropped while opening, or a decode error that a
     * recheck finds is a republished file) are dropped and the file is opened once more from the server.
     */
    private fun open(): Opened {
        val gen = source.generation
        try {
            return Opened(gen, GeoTiff.open(source.at(gen), codecs))
        } catch (e: Exception) {
            if (!(e is CellChanged || (isDecodeError(e) && source.recheck(gen)))) throw e
        }
        val again = source.generation
        return Opened(again, GeoTiff.open(source.at(again), codecs))
    }

    /** T269: a new [Opened] after [stale]'s cache was dropped (once, whichever thread gets here first). */
    @Synchronized
    private fun reopen(stale: Opened): Opened {
        val now = opened
        if (now !== stale) return now
        return open().also { opened = it }
    }

    @Synchronized
    private fun countDecoded() {
        decoded++
    }

    /** Tiles decoded by this instance (each once, unless [SourceIo.decodedBlocks] evicted it in between). */
    val tilesDecoded: Int get() = if (shared == null) opened.caches.sumOf { it.size } else decodedCount()

    @Synchronized
    private fun decodedCount() = decoded

    fun level(index: Int): GeoTiff = levels[index]

    /** The level pixel sizes in CRS units, finest first ([commonLevel]). */
    val levelScales: List<Double> get() = levels.map { it.pixelScaleY }

    /**
     * Coarsest level whose pixels are no bigger than [targetMeters], so we never read more detail than the
     * output can show. [metersPerUnit] converts CRS units to meters (1 for UTM).
     */
    fun levelFor(targetMeters: Double, metersPerUnit: Double): Int = levelFor(levels.map { it.pixelScaleY }, targetMeters, metersPerUnit)

    /**
     * [decodeIn] at [o]; T269: when the cell was dropped meanwhile, or a decode error is a republished file (one recheck),
     * the file is reopened and the tile read again, once.
     */
    private fun decode(o: Opened, levelIndex: Int, tx: Int, ty: Int): FloatArray {
        try {
            return decodeIn(o.levels[levelIndex], tx, ty)
        } catch (e: Exception) {
            if (!(e is CellChanged || (isDecodeError(e) && source.recheck(o.gen)))) throw e
        }
        return decodeIn(reopen(o).levels[levelIndex], tx, ty)
    }

    /** T88: [GeoTiff.readTile] with its decode time (without the read) in [SourceTiming]. */
    private fun decodeIn(l: GeoTiff, tx: Int, ty: Int): FloatArray {
        val t0 = nanoTime()
        val r0 = SourceTiming.threadReadNanos()
        val out = l.readTile(tx, ty)
        SourceTiming.decodeNanos.add(nanoTime() - t0 - (SourceTiming.threadReadNanos() - r0))
        SourceTiming.blocksDecoded.increment()
        return out
    }

    private fun tile(levelIndex: Int, tx: Int, ty: Int): FloatArray {
        val o = opened
        val l = o.levels[levelIndex]
        val key = ty * l.tilesAcross + tx
        val memo = lastBlock.get() ?: LastBlock().also { lastBlock.set(it) }
        if (memo.tiff === this && memo.id == o.id && memo.level == levelIndex && memo.key == key) memo.values?.let { return it }
        val values = lookup(o, levelIndex, l, tx, ty, key)
        memo.tiff = this
        memo.id = o.id
        memo.level = levelIndex
        memo.key = key
        memo.values = values
        return values
    }

    private fun lookup(o: Opened, levelIndex: Int, l: GeoTiff, tx: Int, ty: Int, key: Int): FloatArray {
        if (shared != null) {
            val k = "${o.id}/$levelIndex/$key"
            shared[k]?.let { return it }
            // T88: a block another render is reading or decoding is waited for, not read and decoded twice.
            val once = inFlightBlocks.getOrPut(k) { Once<FloatArray>() }
            try {
                return once.get({ SourceTiming.blockWaits.increment() }) {
                    shared[k] ?: decode(o, levelIndex, tx, ty).let { decoded ->
                        countDecoded()
                        shared.put(k, decoded)
                    }
                }
            } finally {
                inFlightBlocks.remove(k)
            }
        }
        val cache = o.caches[levelIndex]
        cache[key]?.let { return it }
        // Decoded outside the lock (two threads may decode the same tile once each; the bytes come from the disk cache).
        val decoded = decode(o, levelIndex, tx, ty)
        return cache.getOrPut(key) { decoded }
    }

    /** Decodes, in parallel, every tile of [levelIndex] overlapping the CRS rectangle. */
    fun prefetch(levelIndex: Int, minX: Double, minY: Double, maxX: Double, maxY: Double) {
        parallelForEach(prefetchTasks(levelIndex, minX, minY, maxX, maxY)) { it() }
    }

    /** T88: [prefetch] as one task per block, so a caller can run them together with other reads (the geoid). */
    fun prefetchTasks(levelIndex: Int, minX: Double, minY: Double, maxX: Double, maxY: Double): List<() -> Unit> {
        val l = levels[levelIndex]
        val c0 = floor(l.colOf(minX)).toInt().coerceIn(0, l.width - 1)
        val c1 = ceil(l.colOf(maxX)).toInt().coerceIn(0, l.width - 1)
        val r0 = floor(l.rowOf(maxY)).toInt().coerceIn(0, l.height - 1)
        val r1 = ceil(l.rowOf(minY)).toInt().coerceIn(0, l.height - 1)
        val needed = (r0 / l.tileHeight..r1 / l.tileHeight).flatMap { ty ->
            (c0 / l.tileWidth..c1 / l.tileWidth).map { tx -> tx to ty }
        }
        // Not through [tile]: prefetch threads keep no block of their own (see [LastBlock]).
        return needed.map { (tx, ty) -> { val o = opened; lookup(o, levelIndex, o.levels[levelIndex], tx, ty, ty * l.tilesAcross + tx); Unit } }
    }

    /** True if CRS point (x, y) is inside the image's pixel area. */
    fun contains(x: Double, y: Double): Boolean {
        val e = tiff.extent
        return x >= e[0] && x < e[2] && y > e[1] && y <= e[3]
    }

    /**
     * Bilinear sample at CRS (x, y). NaN if any neighbor is no-data, or more than one pixel beyond the outermost
     * pixel centers. Within that pixel the edge value is extended, which closes the sliver between adjacent
     * pixel-is-point files (e.g. Copernicus 1° cells).
     */
    fun sample(levelIndex: Int, x: Double, y: Double): Float {
        val l = levels[levelIndex]
        val col = l.colOf(x)
        val row = l.rowOf(y)
        if (col < -1.0 || row < -1.0 || col > l.width.toDouble() || row > l.height.toDouble()) return Float.NaN
        val c0 = floor(col).toInt()
        val r0 = floor(row).toInt()
        val fx = (col - c0).toFloat()
        val fy = (row - r0).toFloat()
        // T88: the four neighbours inside one block (most samples): one block lookup instead of four.
        if (c0 >= 0 && r0 >= 0 && c0 + 1 < l.width && r0 + 1 < l.height) {
            val tw = l.tileWidth
            val th = l.tileHeight
            val ic = c0 % tw
            val ir = r0 % th
            if (ic + 1 < tw && ir + 1 < th) {
                val block = tile(levelIndex, c0 / tw, r0 / th)
                val k = ir * tw + ic
                val a = valueOf(l, block[k])
                val b = valueOf(l, block[k + 1])
                val c = valueOf(l, block[k + tw])
                val d = valueOf(l, block[k + tw + 1])
                return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy
            }
        }
        val a = at(levelIndex, c0, r0)
        val b = at(levelIndex, c0 + 1, r0)
        val c = at(levelIndex, c0, r0 + 1)
        val d = at(levelIndex, c0 + 1, r0 + 1)
        return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy
    }

    /** Value of pixel ([col], [row]) at [levelIndex], clamped to the image; NaN for no-data. */
    fun at(levelIndex: Int, col: Int, row: Int): Float {
        val l = levels[levelIndex]
        val c = col.coerceIn(0, l.width - 1)
        val r = row.coerceIn(0, l.height - 1)
        return valueOf(l, tile(levelIndex, c / l.tileWidth, r / l.tileHeight)[(r % l.tileHeight) * l.tileWidth + c % l.tileWidth])
    }

    private fun valueOf(l: GeoTiff, v: Float): Float {
        val nd = l.noData
        return if (v.isNaN() || (nd != null && abs(v - nd) < 1e-3f)) Float.NaN else v
    }
}

/**
 * T88: the block this thread sampled last (one per thread over every file, so at most one block per thread is held
 * outside [DecodedBlocks]' cap), letting a window's per-pixel samples skip the shared cache's lock. A source drops it
 * when its window is done ([RemoteTiff.releaseThreadBlock]).
 */
private class LastBlock {
    var tiff: RemoteTiff? = null
    var id = -1 // T269: the opening of [tiff] the block came from
    var level = -1
    var key = -1
    var values: FloatArray? = null
}

private val lastBlock = PerThread<LastBlock>()

/** T269: a failure decoding cached bytes (a garbled tile or header), as opposed to the network's: worth one recheck of the file. */
private fun isDecodeError(e: Exception): Boolean =
    e is IllegalArgumentException || e is IndexOutOfBoundsException || (e is IllegalStateException && e !is HttpStatusException) ||
        e::class.simpleName == "DataFormatException"

/**
 * T88: one computation shared by the callers that ask for it while it runs: the first runs [get]'s block, the
 * others wait on the lock and take its value (or run it themselves when it failed).
 */
internal class Once<T : Any> {
    private var value: T? = null

    @Synchronized
    fun get(onShared: () -> Unit, make: () -> T): T {
        value?.let { onShared(); return it }
        return make().also { value = it }
    }
}

private val inFlightReads = SyncMap<String, Once<ByteArray>>()
private val inFlightBlocks = SyncMap<String, Once<FloatArray>>()

/** Pooled [RemoteTiff]s and decoded blocks for long-running readers (the phone): see [SourceIo.tiffPool]. */
private var lastId = 0

@Synchronized
private fun nextId(): Int = ++lastId

/**
 * The most recently used open [RemoteTiff]s, by URL, so tile after tile reuses one file's header and decoded
 * blocks instead of reopening it per window. Opening happens outside the lock (it may read the header over the
 * network); two threads opening the same file at once keep the first.
 */
class TiffPool(private val size: Int) {
    private val open = LinkedHashMap<String, RemoteTiff>(16, 0.75f, true)
    var opened = 0L
        private set
    var reused = 0L
        private set

    @Synchronized
    private fun lookup(url: String): RemoteTiff? = open[url]?.also { reused++ }

    @Synchronized
    private fun store(url: String, tiff: RemoteTiff): RemoteTiff {
        open[url]?.let { return it }
        open[url] = tiff
        opened++
        while (open.size > size) open.remove(open.keys.first())
        return tiff
    }

    fun get(url: String, make: () -> RemoteTiff): RemoteTiff = lookup(url) ?: store(url, make())
}

/** Decoded GeoTIFF blocks shared by every pooled [RemoteTiff], least recently used out first, at most [capBytes]. */
class DecodedBlocks(private val capBytes: Long) {
    private val blocks = LinkedHashMap<String, FloatArray>(64, 0.75f, true)
    private var bytes = 0L
    var hits = 0L
        private set
    var misses = 0L
        private set

    @Synchronized
    operator fun get(key: String): FloatArray? = blocks[key].also { if (it == null) misses++ else hits++ }

    @Synchronized
    fun put(key: String, values: FloatArray): FloatArray {
        blocks[key]?.let { return it }
        blocks[key] = values
        bytes += values.size * 4L
        while (bytes > capBytes && blocks.size > 1) {
            val eldest = blocks.keys.first()
            bytes -= (blocks.remove(eldest)?.size ?: 0) * 4L
        }
        return values
    }

    val sizeBytes: Long @Synchronized get() = bytes

    /**
     * T138: drops every decoded block and returns the bytes they held. The cache itself stays: every [RemoteTiff] keeps
     * the [DecodedBlocks] it was opened with (the geoid grids' included), so a trim empties this one in place rather than
     * putting a new one beside it (T127's heap dump found the replaced cache's blocks still held). Blocks are decoded again
     * from the source range cache on the next read.
     */
    @Synchronized
    fun clear(): Long {
        val dropped = bytes
        blocks.clear()
        bytes = 0
        return dropped
    }
}
