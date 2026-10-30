package io.github.jasondorjeshort.dystopo.sync

import io.github.jasondorjeshort.dystopo.model.Route
import io.github.jasondorjeshort.dystopo.model.RouteDirections
import io.github.jasondorjeshort.dystopo.model.RoutePath
import io.github.jasondorjeshort.dystopo.model.RoutePlan
import io.github.jasondorjeshort.dystopo.model.RouteSummary
import io.github.jasondorjeshort.dystopo.model.Waypoint
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

    /**
     * Every operation this replica holds that [since] does not cover, oldest first. Dead ones (see
     * [Materializer]) may be left out. Whatever this returns may leave the device, so this replica's
     * own operations up to now count as shipped (see run merging in [TrackStore.append]).
     */
    fun opsSince(since: VersionVector): List<Op>

    fun bundle(since: VersionVector = VersionVector.EMPTY): Bundle = Bundle(deviceId, since, vector(), opsSince(since))

    /**
     * One page of [bundle] (T51): the oldest operations after [since], at most [maxOps] of them and
     * about [maxBytes] encoded (always at least one), with the continuation vector and
     * [Bundle.more] (see [Bundle]). Implementations override it to avoid loading the whole log.
     */
    fun page(since: VersionVector, maxOps: Int, maxBytes: Int = Int.MAX_VALUE): Bundle =
        Paging.page(deviceId, since, vector(), opsSince(since), { true }, maxOps, maxBytes)

    /**
     * Merges a bundle from another replica (any order, duplicates and replays are harmless), with the
     * gap rule of [Bundle], and moves this replica's clock past everything in it ([HlcClock.observe])
     * except timestamps more than [HlcClock.MAX_AHEAD_MS] ahead of [now] (the skew guard, T51).
     * [now] is the local physical time.
     */
    fun apply(bundle: Bundle, now: Long): ApplyResult

    /** The synced state in canonical form, for comparing replicas. */
    fun snapshot(): SyncSnapshot

    /**
     * Compaction: tombstones older than [retentionMs] before [now] are dropped (dead operations are
     * already dropped when their tombstone arrives). See docs/PLANNING.md › Sync for the resurrection
     * limit this implies for a replica offline longer than the retention window. T65: a route's field
     * writes superseded by a newer write of the same field are dropped too ([RouteOps.superseded], counted
     * in [CompactResult.opsDropped]), so the log keeps one `path` value per route.
     */
    fun compact(now: Long, retentionMs: Long = DEFAULT_RETENTION_MS): CompactResult

    /** Operations held (live and tombstones). */
    val opCount: Long

    /** Operations held that [peer] does not cover: what a sync with that peer would push (T51). */
    fun pendingFor(peer: VersionVector): Long = opsSince(peer).size.toLong()

    /** What the clock-skew guard has seen on this replica (T51). */
    val skewLog: SkewLog get() = SkewLog()

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
    /** T51: operations merged whose HLC was more than [HlcClock.MAX_AHEAD_MS] ahead of local time (not observed). */
    val clamped: Int = 0,
    /** T51: the largest such lead, ms (0 when none). */
    val maxAheadMs: Long = 0,
    /** T51: fields written concurrently on two devices, found in this merge (see [Conflicts]). */
    val conflicts: List<Conflict> = emptyList(),
) {
    fun json(): Map<String, Any?> = linkedMapOf(
        "received" to received, "applied" to applied, "duplicate" to duplicate, "dead" to dead,
        "gapDevices" to gapDevices, "rejected" to rejected, "touched" to touched,
        "clamped" to clamped, "maxAheadMs" to maxAheadMs, "conflicts" to conflicts.size,
    )

    /** Sums two results (pages of one sync). */
    operator fun plus(o: ApplyResult) = ApplyResult(
        received + o.received, applied + o.applied, duplicate + o.duplicate, dead + o.dead,
        (gapDevices + o.gapDevices).distinct(), rejected + o.rejected, touched + o.touched,
        clamped + o.clamped, maxOf(maxAheadMs, o.maxAheadMs), conflicts + o.conflicts,
    )

    companion object {
        val NONE = ApplyResult(0, 0, 0, 0, emptyList(), 0, 0)
    }
}

data class CompactResult(val tombstonesDropped: Int, val opsDropped: Int)

/** A [TrackStore] that is a materialised view of an operation log, with the other synced user data. */
interface SyncStore : TrackStore, SyncReplica {
    /** Creates or replaces a planned route (its points become one fixed leg; see [RoutePlan]). */
    fun putRoute(route: Route, now: Long)

    /**
     * T60: stores a planned route: a put with its GPX fields, then its `path` and `waypoints` fields, in
     * one transaction. Replaces a route with the same id (a re-import). T108: then its `directions`
     * ([RouteDirections]) when the plan has them (a value over [RouteDirections.MAX_CHARS] is left out).
     */
    fun putRoutePlan(plan: RoutePlan, now: Long)

    fun deleteRoute(id: String, now: Long): Boolean

    /** The route as GPX sees it (its path's points as route points). */
    fun route(id: String): Route? = routePlan(id)?.toRoute()

    /** All routes, oldest first. */
    fun routes(): List<Route> = routePlans().map { it.toRoute() }

    /** T60: the planned route with its path and waypoints, or null. */
    fun routePlan(id: String): RoutePlan?

    /** T60: all planned routes, oldest first. */
    fun routePlans(): List<RoutePlan>

    /** T60: the Routes list: every route with its summary numbers, oldest first. */
    fun routeList(): List<RouteListItem> = routePlans().map { RouteListItem.of(it, 0L) }

    /**
     * T60: replaces the route's path (one field write); false if the route does not exist. T65: a path over
     * [RouteOps.MAX_PATH_CHARS] encoded throws [RouteOps.RouteTooLargeException].
     */
    fun setRoutePath(id: String, path: RoutePath, now: Long): Boolean {
        val q = path.quantized()
        val value = q.encode()
        RouteOps.checkPath(id, value, q.points.size)
        return setField(EntityKind.ROUTE, id, "path", value, now)
    }

    /**
     * Changes the route's waypoints to [waypoints]; false if the route does not exist. T65: one `wpt:<id>`
     * write per added, changed or removed waypoint ([RouteOps.waypointWrites]), so concurrent edits of
     * different waypoints on two devices both survive; nothing is written when nothing changed.
     */
    fun setRouteWaypoints(id: String, waypoints: List<Waypoint>, now: Long): Boolean {
        val old = routePlan(id)?.waypoints ?: return false
        var ok = true
        for ((field, value) in RouteOps.waypointWrites(old, waypoints)) ok = setField(EntityKind.ROUTE, id, field, value, now) && ok
        return ok
    }

    /**
     * T108: saves [directions] with route [id] for [path] (one `directions` write: [RouteDirections.encode]); false if
     * the route does not exist or the list is too long to save. Null clears them. They count only while [path] is
     * the route's path ([Materializer.routePlan]), so write the path first.
     */
    fun setRouteDirections(id: String, directions: List<io.github.jasondorjeshort.dystopo.routing.Instruction>?, path: RoutePath, now: Long): Boolean {
        val value = if (directions == null) null else RouteDirections.encode(directions, path) ?: return false
        return setField(EntityKind.ROUTE, id, "directions", value, now)
    }

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

    /** The skew guard's findings for one merge: operations past the limit, the largest lead, a device that was ahead. */
    data class Skew(val count: Int, val maxAheadMs: Long, val device: String?)

    /**
     * The skew guard (T51) for one merge: observes the raised vector entries on [clock] except those
     * more than [maxAheadMs] ahead of [now], and counts the taken operations past that limit.
     */
    fun observe(plan: Plan, clock: HlcClock, now: Long, maxAheadMs: Long): Skew {
        for (h in plan.raise) clock.observe(h, now, maxAheadMs)
        var count = 0
        var lead = 0L
        var device: String? = null
        for (op in plan.take) {
            val ahead = op.hlc.wall - now
            if (ahead > maxAheadMs) {
                count++
                if (ahead > lead) { lead = ahead; device = op.device }
            }
        }
        return Skew(count, lead, device)
    }
}

/**
 * Pages of a bundle (T51), shared by every replica: [candidates] are operations after `since` in HLC
 * order, and `exhausted` says whether they hold *all* of a device's remaining operations (a store
 * that read at most N per device passes false for a device that hit N).
 */
object Paging {
    fun page(
        from: String,
        since: VersionVector,
        full: VersionVector,
        candidates: List<Op>,
        exhausted: (String) -> Boolean,
        maxOps: Int,
        maxBytes: Int,
    ): Bundle {
        require(maxOps > 0) { "maxOps must be positive" }
        val taken = ArrayList<Op>()
        var bytes = 0
        for (op in candidates) {
            if (taken.size >= maxOps) break
            val size = OpCodec.encode(op).size + 3
            if (taken.isNotEmpty() && bytes + size > maxBytes) break
            taken += op
            bytes += size
        }
        val lastTaken = HashMap<String, Hlc>()
        for (op in taken) lastTaken[op.device] = op.hlc
        val leftOver = HashSet<String>()
        for (i in taken.size until candidates.size) leftOver += candidates[i].device
        val v = LinkedHashMap<String, Hlc>()
        var more = false
        for ((device, top) in full.entries) {
            val complete = device !in leftOver && exhausted(device)
            val entry = if (complete) top else lastTaken[device] ?: since.entries[device]
            if (entry != null) v[device] = entry
            if (entry != top) more = true
        }
        return Bundle(from, since, VersionVector(v), taken, more)
    }
}

/**
 * Fields edited on two devices (T51), found when a merge brings a write of a field whose current value
 * (the newest live write this replica holds) came from another device and differs. Two cases are
 * reported, the same way on every replica:
 * - **concurrent**: the sender of the bundle did not hold the current write ([Bundle.vector] does not
 *   cover it), so neither writer saw the other's value. The merge point (the first replica holding
 *   both) finds these; it is a lower bound, since operations carry no causal history beyond their HLC.
 * - **overwritten here**: the current value was written by this replica's own device and the incoming
 *   write replaces it, so the device whose value lost always learns of it, concurrent or not.
 * Merge results are unchanged (the greater HLC wins); this only lets the app say so.
 */
object Conflicts {
    /** The LWW fields an operation writes (puts write all of them), or null for appends and deletes. */
    fun fields(body: OpBody): Map<String, String?>? = when (body) {
        is PutTrack -> mapOf("name" to body.name, "description" to body.description, "comment" to body.comment, "source" to body.source, "type" to body.trackType, "extensions" to body.extensions)
        is PutWaypoint -> body.waypoint.let { mapOf("name" to it.name, "description" to it.description, "comment" to it.comment, "symbol" to it.symbol, "type" to it.type, "extensions" to it.extensions) }
        is PutRoute -> Materializer.routePutFields(body)
        is PutSetting -> mapOf("value" to body.value)
        is SetField -> if (body.field in body.kind.fields) mapOf(body.field to body.value) else null
        is AppendPoints, is AddGap, is Delete -> null // T158: gaps are a union, like appends
    }

    /**
     * Conflicts between [incoming] and the operations [held] on its entity (before it was added; any
     * order), given the vector of the bundle it came in.
     */
    fun detect(incoming: Op, held: Collection<Op>, sender: VersionVector, self: String): List<Conflict> {
        val mine = fields(incoming.body) ?: return emptyList()
        val live = Materializer.live(held)
        val out = ArrayList<Conflict>()
        for ((field, value) in mine) {
            val current = live.lastOrNull { fields(it.body)?.containsKey(field) == true } ?: continue
            if (current.device == incoming.device) continue
            val currentValue = fields(current.body)!![field]
            if (currentValue == value) continue
            val concurrent = !sender.covers(current.hlc)
            val incomingWins = incoming.hlc > current.hlc
            if (!concurrent && !(incomingWins && current.device == self)) continue
            out += if (incomingWins) {
                Conflict(incoming.body.kind, incoming.body.id, field, value, incoming.device, currentValue, current.device, incoming.hlc, concurrent)
            } else {
                Conflict(incoming.body.kind, incoming.body.id, field, currentValue, current.device, value, incoming.device, current.hlc, concurrent)
            }
        }
        return out
    }
}

/** T60: one row of the Routes list: a route's name and summary numbers (no geometry). */
data class RouteListItem(
    val id: String,
    val name: String,
    val mode: String,
    val distanceM: Double,
    val ascentM: Double?,
    val descentM: Double?,
    val timeS: Double,
    val viaPoints: Int,
    val waypoints: Int,
    val straightM: Double,
    /** Epoch ms of the last change this replica saw (local bookkeeping, not synced; 0 when unknown). */
    val updatedAt: Long,
) {
    fun json(): Map<String, Any?> = linkedMapOf(
        "id" to id, "name" to name, "mode" to mode, "distanceM" to RouteSummary.r1(distanceM), "ascentM" to ascentM?.let(RouteSummary::r1),
        "descentM" to descentM?.let(RouteSummary::r1), "timeS" to kotlin.math.round(timeS).toLong(), "time" to RouteSummary.hm(timeS),
        "viaPoints" to viaPoints, "waypoints" to waypoints, "straightM" to RouteSummary.r1(straightM), "updatedAt" to updatedAt,
    )

    companion object {
        fun of(plan: RoutePlan, updatedAt: Long): RouteListItem {
            val s = RouteSummary.of(plan, samples = 0)
            return RouteListItem(plan.id, plan.name, s.mode, s.distanceM, s.ascentM, s.descentM, s.timeS, s.viaPoints, plan.waypoints.size, s.straightM, updatedAt)
        }
    }
}
