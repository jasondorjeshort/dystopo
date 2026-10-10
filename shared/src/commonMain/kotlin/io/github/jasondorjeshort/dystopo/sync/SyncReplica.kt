package io.github.jasondorjeshort.dystopo.sync

import io.github.jasondorjeshort.dystopo.model.Route
import io.github.jasondorjeshort.dystopo.store.TrackStore

/**
 * A replica of the operation log (T44): a device's store, the server peer's store, or a simulated
 * peer. Replicas exchange [Bundle]s; nothing else about them needs to match.
 */
interface SyncReplica {
    /** This install's device id: a random UUID, created with the store and kept in it. */
    val deviceId: String

    /** For every device, the newest operation of it this replica holds. */
    fun vector(): VersionVector

    /** Every operation this replica holds that [since] does not cover, oldest first. Dead ones (see [Materializer]) may be left out. */
    fun opsSince(since: VersionVector): List<Op>

    fun bundle(since: VersionVector = VersionVector.EMPTY): Bundle = Bundle(deviceId, since, vector(), opsSince(since))

    /**
     * Merges a bundle from another replica (any order, duplicates and replays are harmless), with the
     * gap rule of [Bundle], and moves this replica's clock past everything in it ([HlcClock.observe]).
     * [now] is the local physical time.
     */
    fun apply(bundle: Bundle, now: Long): ApplyResult

    /** The synced state in canonical form, for comparing replicas. */
    fun snapshot(): SyncSnapshot

    /**
     * Compaction: tombstones older than [retentionMs] before [now] are dropped (dead operations are
     * already dropped when their tombstone arrives). See docs/PLANNING.md › Sync for the resurrection
     * limit this implies for a replica offline longer than the retention window.
     */
    fun compact(now: Long, retentionMs: Long = DEFAULT_RETENTION_MS): CompactResult

    /** Operations held (live and tombstones). */
    val opCount: Long

    companion object {
        /** How long a tombstone is kept: 180 days. */
        const val DEFAULT_RETENTION_MS = 180L * 24 * 3600 * 1000
    }
}

data class ApplyResult(
    /** Operations in the bundle. */
    val received: Int,
    /** New operations stored. */
    val applied: Int,
    /** Already held (or already known dead). */
    val duplicate: Int,
    /** Older than the entity's tombstone: dropped. */
    val dead: Int,
    /** Devices whose operations were skipped because taking them would leave a gap (see [Bundle]). */
    val gapDevices: List<String>,
    /** Malformed: operations of a device past the bundle's own vector for it. */
    val rejected: Int,
    /** Entities rematerialised. */
    val touched: Int,
) {
    fun json(): Map<String, Any?> = linkedMapOf(
        "received" to received, "applied" to applied, "duplicate" to duplicate, "dead" to dead,
        "gapDevices" to gapDevices, "rejected" to rejected, "touched" to touched,
    )
}

data class CompactResult(val tombstonesDropped: Int, val opsDropped: Int)

/** A [TrackStore] that is a materialised view of an operation log, with the other synced user data. */
interface SyncStore : TrackStore, SyncReplica {
    /** Creates or replaces a planned route. */
    fun putRoute(route: Route, now: Long)

    fun deleteRoute(id: String, now: Long): Boolean

    fun route(id: String): Route?

    /** All routes, by id. */
    fun routes(): List<Route>

    /** Sets a synced setting; null removes it. */
    fun setSetting(key: String, value: String?, now: Long)

    fun settings(): Map<String, String>

    /**
     * Changes one field of a track, waypoint or route ([EntityKind.fields], e.g. `description`);
     * false if the entity does not exist or the field is unknown.
     */
    fun setField(kind: EntityKind, id: String, field: String, value: String?, now: Long): Boolean
}

/** The gap rule of [Bundle], shared by every replica. */
object SyncRules {
    class Plan(
        /** Operations to merge (devices without a gap, not already covered). */
        val take: List<Op>,
        /** Vector entries to raise afterwards. */
        val raise: List<Hlc>,
        val duplicate: Int,
        val gapDevices: List<String>,
        val rejected: Int,
    )

    fun plan(bundle: Bundle, mine: VersionVector): Plan {
        val ok = HashSet<String>()
        val gaps = ArrayList<String>()
        val raise = ArrayList<Hlc>()
        for ((device, top) in bundle.vector.entries) {
            if (mine.covers(top)) { ok += device; continue }
            if (bundle.base[device] <= mine[device]) { ok += device; raise += top } else gaps += device
        }
        val take = ArrayList<Op>()
        var dup = 0
        var rejected = 0
        for (op in bundle.ops) {
            val d = op.device
            when {
                d !in bundle.vector.entries || op.hlc > bundle.vector[d] -> rejected++
                d !in ok -> Unit
                mine.covers(op.hlc) -> dup++
                else -> take += op
            }
        }
        return Plan(take.sortedBy { it.hlc }, raise, dup, gaps, rejected)
    }
}
