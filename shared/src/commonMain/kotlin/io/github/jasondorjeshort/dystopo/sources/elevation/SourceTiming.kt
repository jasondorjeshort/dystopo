package io.github.jasondorjeshort.dystopo.sources.elevation

/**
 * T88: where a render's time goes inside the sources, summed over every thread since the process started (the
 * phone reports it in `GET /firstrun/direct` `stages`). Times are nanoseconds of wall clock on the thread that did
 * the work, so with several renders at once the sums exceed the elapsed time.
 *
 * - [rangeNanos]: cold range reads (network, including the wait for a request permit), [rangeReads] of them;
 *   [diskHits] ranges answered from the source cache instead.
 * - [decodeNanos]: decompressing and un-predicting COG blocks ([blocksDecoded]), without their reads.
 * - [blockWaits]: a block another render was already reading or decoding, waited for instead of read again.
 * - [geoidNanos]: datum offsets for a window.
 * - [resampleNanos]: sampling the source grids into the Mercator window (3DEP and Copernicus).
 */
object SourceTiming {
    val rangeNanos = Counter()
    val rangeReads = Counter()
    val rangeShared = Counter()
    val diskHits = Counter()
    val decodeNanos = Counter()
    val blocksDecoded = Counter()
    val blockWaits = Counter()
    val geoidNanos = Counter()
    val resampleNanos = Counter()

    /** Nanoseconds this thread spent in cold range reads so far (to take reads out of a decode's time). */
    private val readOnThread = PerThread<LongArray>()

    internal fun threadReadNanos(): Long = readOnThread.get()?.get(0) ?: 0L

    /** Called after every cold range read with the URL, the bytes and the nanoseconds it took (the phone's timeline). */
    var onRead: ((url: String, bytes: Int, nanos: Long) -> Unit)? = null

    internal fun addRead(nanos: Long) {
        rangeNanos.add(nanos)
        rangeReads.increment()
        val a = readOnThread.get() ?: LongArray(1).also { readOnThread.set(it) }
        a[0] += nanos
    }

    inline fun <T> timed(counter: Counter, block: () -> T): T {
        val t0 = nanoTime()
        try {
            return block()
        } finally {
            counter.add(nanoTime() - t0)
        }
    }

    fun json(): Map<String, Any?> = linkedMapOf(
        "rangeReads" to rangeReads.get(), "rangeMs" to rangeNanos.get() / 1_000_000, "rangeShared" to rangeShared.get(), "diskHits" to diskHits.get(),
        "blocksDecoded" to blocksDecoded.get(), "decodeMs" to decodeNanos.get() / 1_000_000, "blockWaits" to blockWaits.get(),
        "geoidMs" to geoidNanos.get() / 1_000_000, "resampleMs" to resampleNanos.get() / 1_000_000,
    )
}
