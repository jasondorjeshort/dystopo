package io.github.jasondorjeshort.dystopo.store

import io.github.jasondorjeshort.dystopo.gpx.TrackStats
import io.github.jasondorjeshort.dystopo.model.Activity
import io.github.jasondorjeshort.dystopo.model.ActivityProfile
import io.github.jasondorjeshort.dystopo.model.TrackGap
import io.github.jasondorjeshort.dystopo.sync.AddGap
import io.github.jasondorjeshort.dystopo.model.Route
import io.github.jasondorjeshort.dystopo.model.RoutePlan
import io.github.jasondorjeshort.dystopo.model.RouteWaypoints
import io.github.jasondorjeshort.dystopo.sync.RouteListItem
import io.github.jasondorjeshort.dystopo.model.Track
import io.github.jasondorjeshort.dystopo.model.TrackPoint
import io.github.jasondorjeshort.dystopo.model.Uuid
import io.github.jasondorjeshort.dystopo.model.Waypoint
import io.github.jasondorjeshort.dystopo.sync.AppendPoints
import io.github.jasondorjeshort.dystopo.sync.ApplyResult
import io.github.jasondorjeshort.dystopo.sync.Bundle
import io.github.jasondorjeshort.dystopo.sync.CompactResult
import io.github.jasondorjeshort.dystopo.sync.Conflict
import io.github.jasondorjeshort.dystopo.sync.Conflicts
import io.github.jasondorjeshort.dystopo.sync.SkewLog
import io.github.jasondorjeshort.dystopo.sync.Delete
import io.github.jasondorjeshort.dystopo.sync.EntityKind
import io.github.jasondorjeshort.dystopo.sync.Hlc
import io.github.jasondorjeshort.dystopo.sync.HlcClock
import io.github.jasondorjeshort.dystopo.sync.Materializer
import io.github.jasondorjeshort.dystopo.sync.Op
import io.github.jasondorjeshort.dystopo.sync.OpBody
import io.github.jasondorjeshort.dystopo.sync.OpCodec
import io.github.jasondorjeshort.dystopo.sync.PutRoute
import io.github.jasondorjeshort.dystopo.sync.RouteOps
import io.github.jasondorjeshort.dystopo.sync.PutSetting
import io.github.jasondorjeshort.dystopo.sync.PutTrack
import io.github.jasondorjeshort.dystopo.sync.PutWaypoint
import io.github.jasondorjeshort.dystopo.sync.SegmentView
import io.github.jasondorjeshort.dystopo.sync.SetField
import io.github.jasondorjeshort.dystopo.sync.SyncRules
import io.github.jasondorjeshort.dystopo.sync.SyncSnapshot
import io.github.jasondorjeshort.dystopo.sync.SyncStore
import io.github.jasondorjeshort.dystopo.sync.TrackView
import io.github.jasondorjeshort.dystopo.sync.VersionVector
import io.github.jasondorjeshort.dystopo.sync.WaypointView

/**
 * A [SyncStore] in memory: for tests, the sync simulator's peers, and as the reference behaviour
 * [SqlTrackStore] is checked against. Every change is an [Op] in an in-memory log; the tracks,
 * waypoints, routes and settings are rematerialised from it with [Materializer] (a local append that
 * sorts last is applied directly, which is what rematerialising would give).
 *
 * [deviceId] defaults to a random UUID; [wallClock] is the physical clock for operations of calls
 * without a `now` (delete); it defaults to 0, so the HLC alone orders them. [maxAheadMs] is the
 * clock-skew guard (T51, see [HlcClock.observe]) uses the default limit.
 */
class InMemoryTrackStore(
    override val deviceId: String = Uuid.random(),
    private val wallClock: () -> Long = { 0L },
) : SyncStore {
    private val maxAheadMs: Long = HlcClock.MAX_AHEAD_MS
    private data class Key(val kind: EntityKind, val id: String)

    /** Local-only state of a track: not synced. */
    private class Local(var updatedAt: Long, var recording: Boolean = false, var stats: TrackStats? = null)

    /** The run a recorded fix may extend (T51): this device's last single-point append, while it was added. */
    private class Run(val track: String, val segment: Int, val hlc: Hlc, val points: List<TrackPoint>, val digest: String)

    private val clock = HlcClock(deviceId)
    private val ops = LinkedHashMap<Hlc, Op>()
    private val byEntity = HashMap<Key, MutableList<Op>>()
    private val newest = HashMap<Key, Hlc>()
    private val vec = LinkedHashMap<String, Hlc>()

    /** This device's newest operation that may have left in a bundle; runs never grow past it (T51). */
    private var shipped: Hlc? = null
    private var run: Run? = null

    /** Recorded fixes that extended a run instead of adding an operation (T51). */
    var runsExtended = 0L
        private set

    override var skewLog = SkewLog()
        private set

    private val tracks = LinkedHashMap<String, TrackView>()
    private val digests = HashMap<String, HashSet<Pair<Int, String>>>()
    private val local = HashMap<String, Local>()
    private val waypointViews = LinkedHashMap<String, WaypointView>()
    private val routeViews = LinkedHashMap<String, RoutePlan>()
    /** T60: list order (first live put) and last change of each route. */
    private val routeMeta = HashMap<String, Pair<Hlc, Long>>()
    private val settingValues = LinkedHashMap<String, String>()

    override var statsComputed = 0L
        private set

    // ---- log ----

    private fun opsOf(key: Key): List<Op> = byEntity[key] ?: emptyList()

    private fun keyOf(b: OpBody) = Key(b.kind, b.id)

    private fun hold(op: Op) {
        ops[op.hlc] = op
        val key = keyOf(op.body)
        byEntity.getOrPut(key) { ArrayList() } += op
        if (newest[key]?.let { op.hlc > it } != false) newest[key] = op.hlc
    }

    /** Removes one of this device's unshipped operations that a grown run replaces. */
    private fun unhold(op: Op) {
        ops.remove(op.hlc)
        byEntity[keyOf(op.body)]?.removeAll { it.hlc == op.hlc }
    }

    /** Drops everything on the entity older than its new tombstone [t] (they can never matter again). */
    private fun prune(key: Key, t: Hlc): Int {
        val list = byEntity[key] ?: return 0
        val dead = list.filter { it.hlc < t }
        dead.forEach { ops.remove(it.hlc) }
        list.removeAll(dead)
        return dead.size
    }

    private fun emit(body: OpBody, now: Long): Op {
        val physical = maxOf(now, wallClock())
        // T51: the entity-level receive rule (see HlcClock.MAX_SEEN_AHEAD_MS).
        newest[keyOf(body)]?.let { clock.observe(it, physical, HlcClock.MAX_SEEN_AHEAD_MS) }
        val op = Op(clock.tick(physical), body)
        hold(op)
        if (body is Delete) prune(keyOf(body), op.hlc)
        vec[deviceId] = op.hlc
        return op
    }

    // ---- materialisation ----

    private fun rematerialize(key: Key) {
        val list = opsOf(key)
        when (key.kind) {
            EntityKind.TRACK -> {
                val view = Materializer.track(key.id, list)
                if (view == null) {
                    tracks.remove(key.id); digests.remove(key.id); local.remove(key.id)
                } else {
                    val old = tracks[key.id]
                    tracks[key.id] = view
                    digests[key.id] = Materializer.live(list).mapNotNull { (it.body as? AppendPoints)?.let { a -> a.segment to OpCodec.digest(a) } }.toHashSet()
                    val l = local.getOrPut(key.id) { Local(view.lastHlc.wall) }
                    l.updatedAt = maxOf(l.updatedAt, view.lastHlc.wall)
                    if (old?.segments != view.segments) l.stats = null
                }
            }
            EntityKind.WAYPOINT -> Materializer.waypoint(key.id, list)?.let { waypointViews[key.id] = it } ?: waypointViews.remove(key.id)
            EntityKind.ROUTE -> Materializer.routePlan(key.id, list)?.let { plan ->
                routeViews[key.id] = plan
                val live = Materializer.live(list)
                routeMeta[key.id] = live.first { it.body is PutRoute }.hlc to live.last().hlc.wall
            } ?: run { routeViews.remove(key.id); routeMeta.remove(key.id) }
            EntityKind.SETTING -> Materializer.setting(list)?.let { settingValues[key.id] = it } ?: settingValues.remove(key.id)
        }
    }

    private fun visible(w: WaypointView) = w.trackId == null || w.trackId in tracks

    private fun touch(trackId: String?, now: Long) {
        trackId?.let { local[it] }?.updatedAt = now
    }

    // ---- TrackStore ----

    private fun view(id: String) = tracks[id] ?: throw TrackNotFoundException(id)

    override fun create(id: String, name: String, now: Long, recording: Boolean): TrackSummary {
        require(id !in tracks) { "track $id exists" }
        emit(PutTrack(id, now, name), now)
        rematerialize(Key(EntityKind.TRACK, id))
        local.getValue(id).let { it.updatedAt = now; it.recording = recording }
        return summary(id)!!
    }

    override fun append(trackId: String, point: TrackPoint, newSegment: Boolean, now: Long) {
        val v = view(trackId)
        val last = v.segments.lastOrNull()?.number ?: -1
        val seg = if (newSegment || last < 0) last + 1 else last
        val set = digests.getOrPut(trackId) { HashSet() }
        val prev = run
        if (prev != null && prev.track == trackId && prev.segment == seg && prev.points.size < AppendPoints.MAX_RUN &&
            vec[deviceId] == prev.hlc && shipped.let { it == null || prev.hlc > it } && newest[Key(EntityKind.TRACK, trackId)] == prev.hlc
        ) {
            // T51: extend the run. Its points are the last of the segment, and so are the grown run's.
            val grown = AppendPoints(trackId, seg, prev.points + point)
            val gd = OpCodec.digest(grown)
            if (seg to gd !in set) {
                unhold(ops.getValue(prev.hlc))
                set.remove(seg to prev.digest)
                val op = emit(grown, now)
                set.add(seg to gd)
                run = Run(trackId, seg, op.hlc, grown.points, gd)
                runsExtended++
                val segs = v.segments.toMutableList()
                segs[segs.size - 1] = SegmentView(seg, segs.last().points + point)
                tracks[trackId] = v.copy(segments = segs, lastHlc = clock.last)
                local.getValue(trackId).stats = null
                touch(trackId, now)
                return
            }
        }
        val body = AppendPoints(trackId, seg, listOf(point))
        val op = emit(body, now)
        val d = seg to OpCodec.digest(body)
        run = null
        if (newest[Key(EntityKind.TRACK, trackId)] != op.hlc) {
            // T51: the track holds an operation stamped later than this device's clock (merged past the
            // skew guard, not observed), so this append does not sort last: rematerialise.
            rematerialize(Key(EntityKind.TRACK, trackId))
            touch(trackId, now)
            return
        }
        if (set.add(d)) {
            run = Run(trackId, seg, op.hlc, body.points, d.second)
            // Fast path: a local append has the newest HLC, so it sorts after every point of segment >= seg.
            val segs = v.segments.toMutableList()
            if (segs.isNotEmpty() && segs.last().number == seg) segs[segs.size - 1] = SegmentView(seg, segs.last().points + point)
            else segs += SegmentView(seg, listOf(point))
            tracks[trackId] = v.copy(segments = segs, lastHlc = clock.last)
            local.getValue(trackId).stats = null
        } else {
            tracks[trackId] = v.copy(lastHlc = clock.last)
        }
        touch(trackId, now)
    }

    override fun addWaypoint(trackId: String?, waypoint: Waypoint, now: Long) {
        if (trackId != null) view(trackId)
        require(waypoint(waypoint.id) == null) { "waypoint ${waypoint.id} exists" }
        emit(PutWaypoint(trackId, waypoint), now)
        rematerialize(Key(EntityKind.WAYPOINT, waypoint.id))
        touch(trackId, now)
    }

    override fun setRecording(trackId: String, recording: Boolean, now: Long) {
        view(trackId)
        local.getValue(trackId).let { it.recording = recording; it.updatedAt = now }
    }

    override fun rename(trackId: String, name: String, now: Long) {
        setField(EntityKind.TRACK, trackId, "name", name, now)
    }

    override fun setActivity(trackId: String, activity: Activity?, profile: ActivityProfile?, now: Long) {
        val v = view(trackId)
        if (v.activity != activity?.code) setField(EntityKind.TRACK, trackId, "activity", activity?.code, now)
        val encoded = profile?.encode()
        if (v.profile != encoded) setField(EntityKind.TRACK, trackId, "profile", encoded, now)
    }

    override fun addGap(trackId: String, gap: TrackGap, now: Long) {
        view(trackId)
        emit(AddGap(trackId, gap), now)
        rematerialize(Key(EntityKind.TRACK, trackId))
        touch(trackId, now)
    }

    override fun gaps(trackId: String): List<TrackGap> = tracks[trackId]?.gaps ?: emptyList()

    override fun lastProfile(): ActivityProfile? =
        tracks.values.sortedWith(compareBy({ it.createdAt }, { it.id })).asReversed().firstNotNullOfOrNull { ActivityProfile.decode(it.profile) }

    override fun delete(trackId: String): Boolean {
        if (trackId !in tracks) return false
        val now = wallClock()
        for (w in waypointViews.values.filter { it.trackId == trackId }.toList()) {
            emit(Delete(EntityKind.WAYPOINT, w.waypoint.id), now)
            rematerialize(Key(EntityKind.WAYPOINT, w.waypoint.id))
        }
        emit(Delete(EntityKind.TRACK, trackId), now)
        rematerialize(Key(EntityKind.TRACK, trackId))
        return true
    }

    private fun summaryOf(v: TrackView): TrackSummary {
        val l = local.getValue(v.id)
        val wps = waypointViews.values.count { it.trackId == v.id }
        return TrackSummary(v.id, v.name, v.createdAt, l.updatedAt, l.recording, v.pointCount, wps)
    }

    override fun list(): List<TrackSummary> = tracks.values.map { summaryOf(it) }.sortedWith(compareBy({ it.createdAt }, { it.id }))

    override fun summary(trackId: String): TrackSummary? = tracks[trackId]?.let { summaryOf(it) }

    override fun track(trackId: String): Track? = tracks[trackId]?.toTrack()

    override fun waypoints(trackId: String?): List<Waypoint> =
        waypointViews.values.filter { it.trackId == trackId && visible(it) }.sortedBy { it.order }.map { it.waypoint }

    override fun insert(track: Track, waypoints: List<Waypoint>, createdAt: Long, now: Long): TrackSummary {
        require(track.id !in tracks) { "track ${track.id} exists" }
        val ids = waypoints.map { it.id }
        require(ids.toSet().size == ids.size && ids.none { waypoint(it) != null }) { "a waypoint id exists" }
        val t = track
        emit(PutTrack(t.id, createdAt, t.name, t.description, t.comment, t.source, t.type, t.extensions), now)
        t.segments.filter { it.points.isNotEmpty() }.forEachIndexed { i, s -> emit(AppendPoints(t.id, i, s.points), now) }
        t.activity?.let { emit(SetField(EntityKind.TRACK, t.id, "activity", it.code), now) } // T158
        t.profile?.let { emit(SetField(EntityKind.TRACK, t.id, "profile", it.encode()), now) }
        t.gaps.distinct().forEach { emit(AddGap(t.id, it), now) }
        rematerialize(Key(EntityKind.TRACK, t.id))
        check(t.id in tracks) { "track ${t.id} stays hidden by a delete stamped more than 30 days ahead of this device's clock" }
        for (w in waypoints) {
            emit(PutWaypoint(t.id, w), now)
            rematerialize(Key(EntityKind.WAYPOINT, w.id))
        }
        local.getValue(t.id).updatedAt = now
        return summary(t.id)!!
    }

    override fun stats(trackId: String): TrackStats? {
        val v = tracks[trackId] ?: return null
        val l = local.getValue(trackId)
        l.stats?.let { return it }
        statsComputed++
        return TrackStats.of(v.segments.map { it.points }).also { l.stats = it }
    }

    override fun waypoint(id: String): StoredWaypoint? = waypointViews[id]?.takeIf { visible(it) }?.let { StoredWaypoint(it.trackId, it.waypoint) }

    override fun renameWaypoint(id: String, name: String, now: Long): Boolean = setField(EntityKind.WAYPOINT, id, "name", name, now)

    override fun deleteWaypoint(id: String, now: Long): Boolean {
        val w = waypoint(id) ?: return false
        emit(Delete(EntityKind.WAYPOINT, id), now)
        rematerialize(Key(EntityKind.WAYPOINT, id))
        touch(w.trackId, now)
        return true
    }

    // ---- SyncStore ----

    override fun setField(kind: EntityKind, id: String, field: String, value: String?, now: Long): Boolean {
        if (field !in kind.fields) return false
        val exists = when (kind) {
            EntityKind.TRACK -> id in tracks
            EntityKind.WAYPOINT -> waypoint(id) != null
            EntityKind.ROUTE -> id in routeViews
            EntityKind.SETTING -> id in settingValues
        }
        if (!exists) return false
        emit(SetField(kind, id, field, value), now)
        rematerialize(Key(kind, id))
        when (kind) {
            EntityKind.TRACK -> touch(id, now)
            EntityKind.WAYPOINT -> touch(waypointViews[id]?.trackId, now)
            else -> Unit
        }
        return true
    }

    override fun putRoute(route: Route, now: Long) {
        emit(PutRoute(route), now)
        rematerialize(Key(EntityKind.ROUTE, route.id))
    }

    override fun deleteRoute(id: String, now: Long): Boolean {
        if (id !in routeViews) return false
        emit(Delete(EntityKind.ROUTE, id), now)
        rematerialize(Key(EntityKind.ROUTE, id))
        return true
    }

    override fun putRoutePlan(plan: RoutePlan, now: Long) {
        io.github.jasondorjeshort.dystopo.sync.RouteOps.checkPlan(plan) // T65: size cap
        emit(PutRoute(Route(plan.id, plan.name, emptyList(), plan.description, plan.comment, plan.type, plan.extensions)), now)
        emit(SetField(EntityKind.ROUTE, plan.id, "path", plan.path.quantized().encode()), now)
        emit(SetField(EntityKind.ROUTE, plan.id, "waypoints", RouteWaypoints.encode(plan.waypoints)), now)
        plan.directions?.let(io.github.jasondorjeshort.dystopo.model.RouteDirections::encode)?.let { emit(SetField(EntityKind.ROUTE, plan.id, "directions", it), now) } // T108
        rematerialize(Key(EntityKind.ROUTE, plan.id))
    }

    override fun routePlan(id: String): RoutePlan? = routeViews[id]

    override fun routePlans(): List<RoutePlan> = routeViews.values.sortedWith(compareBy({ routeMeta[it.id]?.first }, { it.id }))

    override fun routeList(): List<RouteListItem> = routePlans().map { RouteListItem.of(it, routeMeta[it.id]?.second ?: 0L) }

    override fun setSetting(key: String, value: String?, now: Long) {
        if (value == null) {
            if (key !in settingValues) return
            emit(Delete(EntityKind.SETTING, key), now)
        } else {
            emit(PutSetting(key, value), now)
        }
        rematerialize(Key(EntityKind.SETTING, key))
    }

    override fun settings(): Map<String, String> = settingValues.toList().sortedBy { it.first }.toMap()

    override fun vector(): VersionVector = VersionVector(vec)

    private fun collect(since: VersionVector): List<Op> = ops.values.filter { !since.covers(it.hlc) }.sortedBy { it.hlc }

    override fun opsSince(since: VersionVector): List<Op> {
        shipped = vec[deviceId]
        return collect(since)
    }

    override fun pendingFor(peer: VersionVector): Long = ops.values.count { !peer.covers(it.hlc) }.toLong()

    override val opCount: Long get() = ops.size.toLong()

    override fun apply(bundle: Bundle, now: Long): ApplyResult {
        val plan = SyncRules.plan(bundle, vector())
        val touched = LinkedHashSet<Key>()
        var applied = 0
        var dup = plan.duplicate
        var dead = 0
        val conflicts = ArrayList<Conflict>()
        for (op in plan.take) {
            if (op.hlc in ops) { dup++; continue }
            val key = keyOf(op.body)
            val held = opsOf(key)
            val t = Materializer.tombstone(held)
            if (Materializer.isDead(op.hlc, t)) { dead++; continue }
            if (Conflicts.fields(op.body) != null) conflicts += Conflicts.detect(op, held.filter { it.body !is AppendPoints }, bundle.vector, deviceId)
            hold(op)
            if (op.body is Delete) prune(key, op.hlc)
            applied++
            touched += key
        }
        for (h in plan.raise) {
            if (h > (vec[h.device] ?: Hlc.zero(h.device))) vec[h.device] = h
        }
        val skew = SyncRules.observe(plan, clock, now, maxAheadMs)
        skewLog = skewLog.plus(skew.count, skew.maxAheadMs, skew.device, now)
        // Tracks first: waypoint visibility depends on them.
        touched.sortedBy { it.kind.code }.forEach { rematerialize(it) }
        return ApplyResult(bundle.ops.size, applied, dup, dead, plan.gapDevices, plan.rejected, touched.size, skew.count, skew.maxAheadMs, conflicts)
    }

    /**
     * This store after a process restart (T51, the simulator's crash-restart peer): the log, the
     * vector and the skew log survive; the clock restarts from the vector (this device's entry, and
     * the others within the skew guard), and run merging and local state (recording, `updatedAt`)
     * start afresh, as [SqlTrackStore] does when it is reopened.
     */
    fun restarted(): InMemoryTrackStore = InMemoryTrackStore(deviceId, wallClock).also { it.load(this) }

    private fun load(from: InMemoryTrackStore) {
        from.ops.values.sortedBy { it.hlc }.forEach { hold(it) }
        vec.putAll(from.vec)
        skewLog = from.skewLog
        vec[deviceId]?.let { clock.observe(it) }
        val now = wallClock()
        for (h in vec.values) if (h.device != deviceId) clock.observe(h, now, maxAheadMs)
        byEntity.keys.sortedWith(compareBy({ it.kind.code }, { it.id })).forEach { rematerialize(it) }
    }

    override fun snapshot(): SyncSnapshot = SyncSnapshot.of(tracks.values.toList(), waypointViews.values.filter { visible(it) }, routePlans(), settings())

    override fun compact(now: Long, retentionMs: Long): CompactResult {
        val horizon = now - retentionMs
        var tombstones = 0
        for ((key, list) in byEntity.entries.toList()) {
            val old = list.filter { it.body is Delete && it.hlc.wall < horizon }
            if (old.isEmpty()) continue
            old.forEach { ops.remove(it.hlc) }
            list.removeAll(old)
            tombstones += old.size
            if (list.isEmpty()) {
                byEntity.remove(key)
                newest.remove(key)
            } else {
                newest[key] = list.maxOf { it.hlc }
            }
        }
        // T65: field writes superseded by a newer write of the same field (a route keeps one `path` value).
        val superseded = RouteOps.compactable(byEntity.filterKeys { it.kind == EntityKind.ROUTE }.values).toSet()
        for (list in byEntity.values) list.removeAll { it.hlc in superseded }
        superseded.forEach { ops.remove(it) }
        return CompactResult(tombstones, superseded.size)
    }
}
