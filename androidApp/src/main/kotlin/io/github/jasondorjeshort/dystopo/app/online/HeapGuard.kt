package io.github.jasondorjeshort.dystopo.app.online

import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * T127 (finding 2026-10-21-direct-view-out-of-memory-after-a-tour): the Java heap against the phone's own DEM renders.
 *
 * A cold direct view (no pack, no server) renders up to [DirectSources.MAX_TILES_IN_FLIGHT] DEM tiles at once, each
 * holding decoded COG blocks and its window while it reads; on the 192 MB heap a fresh process barely fitted a Mont
 * Blanc z9 view, and one that had run the device tour, its decode caches full, ran out. Three things here bound it:
 *
 * - **The render limit** ([renderLimit], called by [RenderSlots] before each render takes a slot): how many renders may
 *   run, from the heap the process holds without renders ([baseBytes]: read when a burst starts and after each trim),
 *   the decoded blocks' cap and a reserve, at [RENDER_BYTES] a render; and none more while less than one render's worth
 *   is free now. At least one always runs. A first try that counted only the heap used at admission let ten renders in
 *   at the start of a cold view, before any had allocated, and they grew past the heap together (T127's PR).
 * - **Trims**: the in-memory caches outside the disk caches (the decoded DEM tiles of the packs' and the direct
 *   pyramids, the decoded COG blocks and open GeoTIFFs of the sources, the cliff tiles' memo) are dropped by [trim]:
 *   on the application's `onTrimMemory` (`DystopoApp`; the activity is stopped while the app is in the background, so
 *   its own callback is not reached) and before a render when the heap is short ([renderLimit] schedules one, at most
 *   every [PRESSURE_TRIM_EVERY_MS], followed by a collection). Every cache refills from its disk cache.
 * - **The record** ([json], `GET /online` `direct.memory`): the heap now, the process's peak, the peak of each render
 *   burst (sampled every [SAMPLE_MS] while renders run, and at each render's start and end), the limit and the trims.
 *
 * The caches register their trim with [register] (the tile service, the direct sources); a hook returns the bytes it
 * dropped, as well as it knows them. The policy is pure and tested ([renderLimit] with [Heap], `HeapGuardTest`).
 */
object HeapGuard {
    const val MB = 1L shl 20

    /**
     * What one direct render takes of the heap while it runs, besides the shared caches. Measured (T127): nine renders of
     * a cold Mont Blanc z8 view held 100 MB after a collection and churned 4 MB blocks on top; 16 MB a render still let
     * the heap reach its max.
     */
    const val RENDER_BYTES = 32L * MB

    /** Heap kept free besides the renders: for MapLibre's Java side, the request threads and the encoders. */
    const val RESERVE_MIN_BYTES = 24L * MB

    /** Below this much free heap (after the reserve) a render first asks for a trim. */
    const val PRESSURE_BYTES = RENDER_BYTES

    const val PRESSURE_TRIM_EVERY_MS = 5_000L
    const val SAMPLE_MS = 100L

    /** A heap reading: what is used now (garbage not yet collected included) and the most it may grow to. */
    data class Heap(val usedBytes: Long, val maxBytes: Long) {
        val reserveBytes: Long get() = maxOf(RESERVE_MIN_BYTES, maxBytes / 8)

        /** Free heap for renders: the max less what is used and the reserve (negative when short). */
        val spareBytes: Long get() = maxBytes - usedBytes - reserveBytes

        companion object {
            fun now(): Heap = Runtime.getRuntime().let { Heap(it.totalMemory() - it.freeMemory(), it.maxMemory()) }
        }
    }

    /**
     * How many direct renders may run with [running] running now: one per [renderBytes] of what the heap's max leaves
     * after the reserve, [baseBytes] (the process without renders) and [cacheBytes] (the decoded blocks' cap); no more
     * than run now while [heap] has less than [renderBytes] spare; from 1 to [permits]. Pure, tested.
     */
    fun renderLimit(heap: Heap, running: Int, permits: Int, baseBytes: Long, cacheBytes: Long, renderBytes: Long = RENDER_BYTES): Int {
        var n = Math.floorDiv(heap.maxBytes - heap.reserveBytes - baseBytes - cacheBytes, renderBytes).toInt()
        if (heap.spareBytes < renderBytes) n = minOf(n, running)
        return n.coerceIn(1, permits)
    }

    /** Whether a render should first ask for a trim: the spare heap below [PRESSURE_BYTES] and none in the last [PRESSURE_TRIM_EVERY_MS]. Pure, tested. */
    fun wantsTrim(heap: Heap, now: Long, lastTrimAt: Long): Boolean =
        heap.spareBytes < PRESSURE_BYTES && now - lastTrimAt >= PRESSURE_TRIM_EVERY_MS

    // ---- trims ----

    private class Hook(val name: String, val trim: () -> Long)

    private val hooks = CopyOnWriteArrayList<Hook>()

    /** A cache's trim: drops what it holds in memory (it refills from disk) and returns the bytes dropped, or 0. */
    fun register(name: String, trim: () -> Long) {
        hooks.removeAll { it.name == name }
        hooks += Hook(name, trim)
    }

    /** One trim: why, when, the heap before and after (after a collection when [collect]) and what each cache dropped. */
    data class Trim(val reason: String, val at: Long, val beforeBytes: Long, val afterBytes: Long, val dropped: Map<String, Long>, val ms: Long) {
        fun json(): Map<String, Any?> = linkedMapOf("reason" to reason, "at" to at, "heapBeforeBytes" to beforeBytes, "heapAfterBytes" to afterBytes,
            "droppedBytes" to dropped, "ms" to ms)
    }

    private val recentTrims = ArrayDeque<Trim>()
    val trims = AtomicLong()
    val pressureTrims = AtomicLong()
    @Volatile var lastTrimAt = 0L
        private set

    /** Runs every registered trim (a failing one is logged and skipped), then a collection when [collect]. */
    @Synchronized
    fun trim(reason: String, collect: Boolean = true): Trim {
        val t0 = System.nanoTime()
        val before = Heap.now().usedBytes
        val dropped = LinkedHashMap<String, Long>()
        for (h in hooks) dropped[h.name] = runCatching(h.trim).onFailure { Log.w(TAG, "trim ${h.name} failed", it) }.getOrDefault(0L)
        if (collect) {
            System.gc()
            System.runFinalization()
        }
        val t = Trim(reason, System.currentTimeMillis(), before, Heap.now().usedBytes, dropped, (System.nanoTime() - t0) / 1_000_000)
        if (collect && runningNow() == 0) baseBytes = t.afterBytes // what the caches held is gone (read only with no render holding heap)
        lastTrimAt = t.at
        trims.incrementAndGet()
        synchronized(recentTrims) {
            recentTrims.addLast(t)
            while (recentTrims.size > 8) recentTrims.removeFirst()
        }
        Log.i(TAG, "trim ($reason): heap ${before / MB} -> ${t.afterBytes / MB} MB in ${t.ms} ms, dropped ${dropped.mapValues { it.value / MB }} MB")
        return t
    }

    /**
     * The application's `onTrimMemory` ([level] as `ComponentCallbacks2` gives it): from `TRIM_MEMORY_UI_HIDDEN` (the
     * app left the screen) up every in-memory cache goes; a level below (running, still in front) trims only while the
     * system says memory is low there (`TRIM_MEMORY_RUNNING_LOW` and above).
     */
    fun onTrimMemory(level: Int) {
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) worker.execute { trim("onTrimMemory $level") }
    }

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "heap-trim").apply { isDaemon = true } }
    private val pressureQueued = AtomicBoolean()

    /**
     * The heap the process holds without direct renders: read when a render is admitted with none running, lowered after
     * a trim (the first render of a process always finds none running, so it is read before any limit is taken).
     */
    @Volatile var baseBytes = 0L
        private set

    /** The decoded blocks' cap, set by the direct sources ([DirectSources.decodedCapBytes]). */
    @Volatile var cacheBytes = 0L

    @Volatile private var runningNow: () -> Int = { 0 }

    /** [renderLimit] for the direct renders now; asks for a trim (on its own thread) when the heap is short. */
    fun limitNow(running: Int, permits: Int, runningGetter: () -> Int): Int {
        runningNow = runningGetter
        val heap = Heap.now()
        if (running == 0) baseBytes = heap.usedBytes
        sample(heap.usedBytes)
        if (wantsTrim(heap, System.currentTimeMillis(), lastTrimAt) && pressureQueued.compareAndSet(false, true)) {
            worker.execute {
                try {
                    // Another thread's trim may have freed enough meanwhile.
                    if (wantsTrim(Heap.now(), System.currentTimeMillis(), lastTrimAt)) {
                        pressureTrims.incrementAndGet()
                        trim("heap short before a render")
                    }
                } finally {
                    pressureQueued.set(false)
                }
            }
        }
        return renderLimit(heap, running, permits, baseBytes, cacheBytes).also { lastLimit = it }
    }

    @Volatile var lastLimit = 0
        private set

    // ---- the record: peaks per render burst ----

    /** A burst: renders from the first after an idle spell to the last before the next [BURST_GAP_MS] of none. */
    class Burst(val seq: Int, val startedAt: Long) {
        @Volatile var peakBytes = 0L
        @Volatile var endedAt = 0L
        val renders = AtomicLong()
        fun json(): Map<String, Any?> = linkedMapOf("seq" to seq, "startedAt" to startedAt, "endedAt" to endedAt.takeIf { it > 0 }, "renders" to renders.get(), "peakHeapBytes" to peakBytes)
    }

    const val BURST_GAP_MS = 2_000L
    private val bursts = ArrayDeque<Burst>()
    @Volatile private var current: Burst? = null
    @Volatile var peakBytes = 0L
        private set
    @Volatile private var busy: () -> Int = { 0 }
    private val sampling = AtomicBoolean()

    /** A heap reading into the process's peak and the current burst's. */
    fun sample(used: Long = Heap.now().usedBytes) {
        if (used > peakBytes) peakBytes = used
        current?.let { if (used > it.peakBytes) it.peakBytes = used }
    }

    /** A render starts ([rendering] counts the renders queued or running): opens a burst when none is, and samples while any runs. */
    fun renderStarted(rendering: () -> Int) {
        busy = rendering
        synchronized(bursts) {
            val b = current
            if (b == null || (b.endedAt > 0 && System.currentTimeMillis() - b.endedAt > BURST_GAP_MS)) {
                val n = Burst((bursts.lastOrNull()?.seq ?: 0) + 1, System.currentTimeMillis())
                bursts.addLast(n)
                while (bursts.size > 16) bursts.removeFirst()
                current = n
            } else {
                b.endedAt = 0
            }
            current?.renders?.incrementAndGet()
        }
        sample()
        if (sampling.compareAndSet(false, true)) Thread(::sampleWhileBusy, "heap-sample").apply { isDaemon = true }.start()
    }

    /** A render ended: one more reading. */
    fun renderEnded() = sample()

    private fun sampleWhileBusy() {
        try {
            while (true) {
                sample()
                if (busy() == 0) {
                    current?.let { if (it.endedAt == 0L) it.endedAt = System.currentTimeMillis() }
                    val ended = current?.endedAt ?: 0L
                    if (System.currentTimeMillis() - ended > BURST_GAP_MS) return
                }
                Thread.sleep(SAMPLE_MS)
            }
        } finally {
            sampling.set(false)
        }
    }

    /** `GET /online` `direct.memory`. */
    fun json(): Map<String, Any?> {
        val h = Heap.now()
        return linkedMapOf(
            "heapUsedBytes" to h.usedBytes, "heapMaxBytes" to h.maxBytes, "reserveBytes" to h.reserveBytes, "spareBytes" to h.spareBytes,
            "peakHeapBytes" to peakBytes, "renderBytes" to RENDER_BYTES, "renderLimit" to lastLimit, "baseBytes" to baseBytes, "cacheBytes" to cacheBytes,
            "burstSeq" to synchronized(bursts) { bursts.lastOrNull()?.seq ?: 0 },
            "bursts" to synchronized(bursts) { bursts.map { it.json() } },
            "trims" to trims.get(), "pressureTrims" to pressureTrims.get(),
            "recentTrims" to synchronized(recentTrims) { recentTrims.map { it.json() } },
            "hooks" to hooks.map { it.name },
        )
    }

    private const val TAG = "DystopoHeap"
}
