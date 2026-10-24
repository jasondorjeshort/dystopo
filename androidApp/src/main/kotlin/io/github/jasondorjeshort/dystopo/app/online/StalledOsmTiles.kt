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

    /** Tile [path] (`z/x/y.pbf`) was answered empty at [now] because its fetch failed. A tile already pending keeps its schedule. */
    @Synchronized
    fun failed(path: String, now: Long) {
        if (path in pending) return
        pending[path] = Entry(now, now + firstRetryMs, firstRetryMs)
        recorded++
        while (pending.size > capacity) pending.remove(pending.keys.first())
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

    val size: Int @Synchronized get() = pending.size

    @Synchronized
    fun json(): Map<String, Any?> = linkedMapOf(
        "pending" to pending.size, "recorded" to recorded, "retried" to retried, "recovered" to recovered,
        "givenUp" to givenUp, "droppedByStyleLoad" to droppedByStyleLoad, "firstRetryMs" to firstRetryMs,
    )
}
