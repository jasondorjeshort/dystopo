package io.github.jasondorjeshort.dystopo.app.online

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The bookkeeping around one DEM render from the public sources ([DirectSources]): at most [permits] at once, and T88's
 * coarse-first rule (a render at the view's zoom waits up to [coarseFirstMs] for the coarse renders in flight, and at
 * the start of a burst [coarseGraceMs] for them to be asked for). [rendering] counts the renders queued or running (the
 * map reloads its style once they are done) and [coarseActive] the coarse ones among them.
 *
 * T106 (a T88 follow-up): both counters were incremented before the coarse wait and the permit, outside the `try` that
 * gave them back, so a render interrupted while it waited (a request given up, the pool shut down) left them up for
 * good: the map never counted its renders done, and every later render at the view's zoom waited the full coarse
 * hold. Now every step after the increments is inside the `try`, and a permit is given back only when one was taken.
 *
 * T127: a render also waits until [admit] lets it in (with the renders running, what they may take together by
 * [weightOf], and its own weight; [HeapGuard.admitNow] decides from the free heap), asked again every
 * [LIMIT_RECHECK_MS] while it waits and whenever a render ends; [heapWaits] counts the renders that waited for it
 * rather than for a permit. One render always may run.
 * [onStart] and [onEnd] run around each render (the heap record, [HeapGuard.renderStarted]).
 * Pure JVM, tested ([RenderSlotsTest]).
 */
internal class RenderSlots(
    private val permits: Int,
    private val coarseFirstMs: Long,
    private val coarseGraceMs: Long,
    private val clock: () -> Long = System::currentTimeMillis,
    private val admit: (running: Int, committed: Long, weight: Long) -> Boolean = { _, _, _ -> true },
    private val weightOf: (coarse: Boolean) -> Long = { 0L },
    private val onStart: () -> Unit = {},
    private val onEnd: () -> Unit = {},
) {
    private val gate = Object()
    private var running = 0 // under [gate]
    private var committed = 0L // under [gate]: what the running renders may take ([weightOf])
    val heapWaits = AtomicLong()
    val rendering = AtomicInteger()
    val coarseActive = AtomicInteger()
    private val coarseGate = Object()
    @Volatile private var burstStartedAt = 0L
    val coarseHeldMs = AtomicLong()
    @Volatile var lastDoneAt = 0L
        private set

    /** Permits free now (for tests and reports). */
    val available: Int get() = synchronized(gate) { permits - running }

    /** Renders running now (holding a slot). */
    val runningNow: Int get() = synchronized(gate) { running }

    /** What the running renders may take together (for reports). */
    val committedNow: Long get() = synchronized(gate) { committed }

    /** T127: waits for a slot under [permits] and [admit]; an interrupt throws with nothing taken. */
    private fun acquire(weight: Long) {
        var counted = false
        synchronized(gate) {
            while (true) {
                if (Thread.interrupted()) throw InterruptedException()
                if (running < permits && (running == 0 || admit(running, committed, weight))) {
                    running++
                    committed += weight
                    return
                }
                if (running < permits && !counted) {
                    heapWaits.incrementAndGet()
                    counted = true
                }
                gate.wait(LIMIT_RECHECK_MS)
            }
        }
    }

    private fun release(weight: Long) = synchronized(gate) {
        running--
        committed -= weight
        gate.notifyAll()
    }

    private fun holdForCoarse() {
        val start = clock()
        try {
            synchronized(coarseGate) {
                while (true) {
                    val now = clock()
                    val inGrace = coarseActive.get() == 0 && now - burstStartedAt < coarseGraceMs
                    if (!inGrace && coarseActive.get() == 0) break
                    val left = if (inGrace) burstStartedAt + coarseGraceMs - now else start + coarseFirstMs - now
                    if (left <= 0) break
                    coarseGate.wait(left)
                }
            }
        } finally {
            coarseHeldMs.addAndGet(clock() - start)
        }
    }

    /**
     * Runs [block] (the render) in a slot: counted in [rendering] (and [coarseActive] when [coarse]) from the call until
     * it returns or throws, after the coarse hold (renders at the view's zoom) and with a permit. [block] gets the
     * nanoseconds spent waiting for both. An interrupt while waiting throws [InterruptedException] with every counter
     * and permit as before.
     */
    fun <T> run(coarse: Boolean, block: (waitedNanos: Long) -> T): T {
        val queued = System.nanoTime()
        if (rendering.getAndIncrement() == 0) burstStartedAt = clock()
        if (coarse) coarseActive.incrementAndGet()
        var permit = false
        val weight = weightOf(coarse)
        try {
            if (!coarse) holdForCoarse()
            acquire(weight)
            permit = true
            onStart()
            return block(System.nanoTime() - queued)
        } finally {
            if (permit) {
                release(weight)
                runCatching(onEnd)
            }
            if (coarse) {
                coarseActive.decrementAndGet()
                synchronized(coarseGate) { coarseGate.notifyAll() }
            }
            rendering.decrementAndGet()
            lastDoneAt = clock()
        }
    }

    companion object {
        /** T127: how often a render waiting for the heap asks again (the heap frees without telling anyone). */
        const val LIMIT_RECHECK_MS = 50L
    }
}
