package io.github.jasondorjeshort.dystopo.app.online

import java.util.concurrent.Semaphore
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
 * Pure JVM, tested ([RenderSlotsTest]).
 */
internal class RenderSlots(
    permits: Int,
    private val coarseFirstMs: Long,
    private val coarseGraceMs: Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val slots = Semaphore(permits)
    val rendering = AtomicInteger()
    val coarseActive = AtomicInteger()
    private val coarseGate = Object()
    @Volatile private var burstStartedAt = 0L
    val coarseHeldMs = AtomicLong()
    @Volatile var lastDoneAt = 0L
        private set

    /** Permits free now (for tests and reports). */
    val available: Int get() = slots.availablePermits()

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
        try {
            if (!coarse) holdForCoarse()
            slots.acquire()
            permit = true
            return block(System.nanoTime() - queued)
        } finally {
            if (permit) slots.release()
            if (coarse) {
                coarseActive.decrementAndGet()
                synchronized(coarseGate) { coarseGate.notifyAll() }
            }
            rendering.decrementAndGet()
            lastDoneAt = clock()
        }
    }
}
