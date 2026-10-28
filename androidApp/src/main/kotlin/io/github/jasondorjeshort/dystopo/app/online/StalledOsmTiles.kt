package io.github.jasondorjeshort.dystopo.app.online

/**
 * T138 (T123's leftover): vector tiles the map was answered empty for because their fetch failed (OpenFreeMap's stall
 * guard gave up, the network or the server was down), so they can be fetched again and drawn without a style reload.
 *
 * MapLibre never asks for a tile again once it was answered: an error answer (a 503) keeps it as an errored tile for good
 * (the tile loader drops its request with the answer, so MapLibre's own retry schedule never runs; observed on the
 * emulator, and the map never counts as fully loaded with an errored tile in view), and a 404 is an empty tile. So the
 * loopback server answers a failed vector tile 404 as before, records it here ([failed]), and `OnlineService` fetches the
 * due ones again in the background ([due], first after [firstRetryMs], the online fetcher's per-tile failure memo, then
 * doubling to [maxRetryMs]); when one comes back it refreshes the map's vector source in place ([OsmSourceRefresh]),
 * which asks MapLibre for that source's tiles again (from the disk cache, the recovered ones from the network) without
 * reloading the style. A tile is given up after [giveUpMs], and every entry recorded before a style load is dropped
 * ([styleLoaded]): the reload asked for it again. At most [capacity] entries, the oldest out first. Pure, tested.
 *
 * T155: the same record holds the DEM tiles answered 404 after a failed fetch (`OnlineService.stalledDem`, paths
 * `z/x/y.png`); there a source that answers again ([wake]) makes every entry due at once, and [oldest] is the tile the
 * phone tries while none is due, so the recovery does not wait out the backoff ([DemSourceRefresh]).
 */
class StalledOsmTiles(
    val firstRetryMs: Long = 30_000L,
    val maxRetryMs: Long = 240_000L,
    val giveUpMs: Long = 30 * 60_000L,
    val capacity: Int = 256,
) {
    private class Entry(val failedAt: Long, var nextAt: Long, var waitMs: Long, var attempts: Int = 0)

    private val pending = LinkedHashMap<String, Entry>()

    /** Vector tiles recorded, fetched again and recovered, given up, dropped by a style load (counters for `GET /online`). */
    var recorded = 0L
        private set
    var retried = 0L
        private set
    var recovered = 0L
        private set
    var givenUp = 0L
        private set
    var droppedByStyleLoad = 0L
        private set

    /** Tile [path] (`z/x/y.pbf`) was answered empty at [now] because its fetch failed. A tile already pending keeps its schedule. True when newly recorded. */
    @Synchronized
    fun failed(path: String, now: Long): Boolean {
        if (path in pending) return false
        pending[path] = Entry(now, now + firstRetryMs, firstRetryMs)
        recorded++
        while (pending.size > capacity) pending.remove(pending.keys.first())
        return true
    }

    /** The tiles due for another fetch at [now] (and the ones past [giveUpMs] dropped). */
    @Synchronized
    fun due(now: Long): List<String> {
        val it = pending.entries.iterator()
        val out = ArrayList<String>()
        while (it.hasNext()) {
            val (path, e) = it.next()
            if (now - e.failedAt > giveUpMs) {
                it.remove()
                givenUp++
            } else if (now >= e.nextAt) {
                out += path
            }
        }
        return out
    }

    /** Another fetch of [path] at [now]: [ok] removes it (the map should be refreshed); a failure doubles its wait. */
    @Synchronized
    fun result(path: String, ok: Boolean, now: Long) {
        val e = pending[path] ?: return
        retried++
        e.attempts++
        if (ok) {
            pending.remove(path)
            recovered++
        } else {
            e.waitMs = minOf(e.waitMs * 2, maxRetryMs)
            e.nextAt = now + e.waitMs
        }
    }

    /** The style was loaded at [now]: MapLibre asked for every tile again, so what failed before is not pending any more. */
    @Synchronized
    fun styleLoaded(now: Long) {
        val it = pending.entries.iterator()
        while (it.hasNext()) {
            if (it.next().value.failedAt <= now) {
                it.remove()
                droppedByStyleLoad++
            }
        }
    }

    /** T155: times [wake] made pending tiles due early. */
    var woken = 0L
        private set

    /** T155: the source answered again at [now] (a read after stalls came back): every pending tile is due now. Returns how many. */
    @Synchronized
    fun wake(now: Long): Int {
        if (pending.isEmpty()) return 0
        for (e in pending.values) e.nextAt = minOf(e.nextAt, now)
        woken++
        return pending.size
    }

    /** T155: the tile recorded first (the one tried while none is due), or null. */
    @Synchronized
    fun oldest(): String? = pending.keys.firstOrNull()

    val size: Int @Synchronized get() = pending.size

    @Synchronized
    fun json(): Map<String, Any?> = linkedMapOf(
        "pending" to pending.size, "recorded" to recorded, "retried" to retried, "recovered" to recovered,
        "givenUp" to givenUp, "droppedByStyleLoad" to droppedByStyleLoad, "firstRetryMs" to firstRetryMs, "woken" to woken,
    )
}

/**
 * T144 (T138's follow-ups): when the map's vector source is replaced for recovered tiles ([OsmSourceRefresh]), which
 * redraws every layer that uses it. Recoveries that trickle in (each stalled tile's retry comes on its own schedule) are
 * coalesced: the first opens a window of [settleMs]; the refresh comes when no recovery came for [quietMs], or when the
 * window ends, whichever is first; and never twice within [settleMs]. So a lone recovery is drawn about [quietMs] after it
 * came, and a trickle gets one refresh per window (T138 refreshed every 5 to 6 s while retries came in). A refresh the
 * map skipped because its style was loading ([skipped]) stays wanted and is tried at the next look, so a recovery that
 * landed during a style load is not lost. Pure, tested.
 *
 * T155: the DEM tiles' refreshes ([DemSourceRefresh]) use one with [quietMs] 0: a DEM retry reports its recoveries as one
 * batch when the batch ends, so the refresh follows the batch at once, still never twice within [settleMs].
 */
class OsmRefreshCoalescer(val settleMs: Long = 10_000L, val quietMs: Long = 2_000L) {
    private var wanted = false
    private var windowStart = 0L
    private var lastRecovery = 0L
    private var lastRefresh = Long.MIN_VALUE / 2

    /** Refreshes done, recoveries that joined a refresh already wanted, refreshes the map skipped and tried again. */
    var refreshes = 0L
        private set
    var coalesced = 0L
        private set
    var skippedRetried = 0L
        private set

    /** [count] tiles came back at [now]. */
    @Synchronized
    fun recovered(now: Long, count: Int = 1) {
        if (count <= 0) return
        if (wanted) coalesced += count else {
            wanted = true
            windowStart = now
            coalesced += count - 1
        }
        lastRecovery = now
    }

    /** True when the source should be replaced now; the caller then calls [refreshed] or [skipped]. */
    @Synchronized
    fun due(now: Long): Boolean {
        if (!wanted || now - lastRefresh < settleMs) return false
        return now - lastRecovery >= quietMs || now - windowStart >= settleMs
    }

    /** T155: [due] and [refreshed] in one step, so two threads looking at once (the probe's tick and a retry ending) refresh once. */
    @Synchronized
    fun claim(now: Long): Boolean {
        if (!due(now)) return false
        refreshed(now)
        return true
    }

    /** The source was replaced at [now]. */
    @Synchronized
    fun refreshed(now: Long) {
        wanted = false
        lastRefresh = now
        refreshes++
    }

    /** The refresh counted by [refreshed] was skipped by the map (its style was loading): not counted, still wanted, tried at the next look. */
    @Synchronized
    fun skipped() {
        if (!wanted) {
            wanted = true
            windowStart = lastRecovery
        }
        refreshes--
        lastRefresh = Long.MIN_VALUE / 2
        skippedRetried++
    }

    val pending: Boolean @Synchronized get() = wanted

    @Synchronized
    fun json(): Map<String, Any?> = linkedMapOf(
        "settleMs" to settleMs, "quietMs" to quietMs, "refreshes" to refreshes, "coalesced" to coalesced,
        "refreshPending" to wanted, "skippedRetried" to skippedRetried,
    )
}
