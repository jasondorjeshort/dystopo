package io.github.jasondorjeshort.dystopo.store

import io.github.jasondorjeshort.dystopo.gpx.TrackStats
import io.github.jasondorjeshort.dystopo.model.Route
import io.github.jasondorjeshort.dystopo.model.Track
import io.github.jasondorjeshort.dystopo.model.TrackPoint
import io.github.jasondorjeshort.dystopo.model.Uuid
import io.github.jasondorjeshort.dystopo.model.Waypoint
import io.github.jasondorjeshort.dystopo.sync.AppendPoints
import io.github.jasondorjeshort.dystopo.sync.ApplyResult
import io.github.jasondorjeshort.dystopo.sync.Bundle
import io.github.jasondorjeshort.dystopo.sync.CompactResult
import io.github.jasondorjeshort.dystopo.sync.Delete
import io.github.jasondorjeshort.dystopo.sync.EntityKind
import io.github.jasondorjeshort.dystopo.sync.Hlc
import io.github.jasondorjeshort.dystopo.sync.HlcClock
import io.github.jasondorjeshort.dystopo.sync.Materializer
import io.github.jasondorjeshort.dystopo.sync.Op
import io.github.jasondorjeshort.dystopo.sync.OpBody
import io.github.jasondorjeshort.dystopo.sync.OpCodec
import io.github.jasondorjeshort.dystopo.sync.PutRoute
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
 * without a `now` (delete); it defaults to 0, so the HLC alone orders them.
 */
class InMemoryTrackStore(
    override val deviceId: String = Uuid.random(),
    private val wallClock: () -> Long = { 0L },
) : SyncStore {
    private data class Key(val kind: EntityKind, val id: String)

    /** Local-only state of a track: not synced. */
    private class Local(var updatedAt: Long, var recording: Boolean = false, var stats: TrackStats? = null)

    private val clock = HlcClock(deviceId)
    private val ops = LinkedHashMap<Hlc, Op>()
    private val byEntity = HashMap<Key, MutableList<Op>>()
    private val vec = LinkedHashMap<String, Hlc>()

    private val tracks = LinkedHashMap<String, TrackView>()
    private val digests = HashMap<String, HashSet<Pair<Int, String>>>()
    private val local = HashMap<String, Local>()
    private val waypointViews = LinkedHashMap<String, WaypointView>()
    private val routeViews = LinkedHashMap<String, Route>()
    private val settingValues = LinkedHashMap<String, String>()

    override var statsComputed = 0L
        private set

    // ---- log ----

    private fun opsOf(key: Key): List<Op> = byEntity[key] ?: emptyList()

    private fun keyOf(b: OpBody) = Key(b.kind, b.id)

    private fun hold(op: Op) {
        ops[op.hlc] = op
        byEntity.getOrPut(keyOf(op.body)) { ArrayList() } += op
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
        val op = Op(clock.tick(maxOf(now, wallClock())), body)
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
            EntityKind.ROUTE -> Materializer.route(key.id, list)?.let { routeViews[key.id] = it } ?: routeViews.remove(key.id)
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
        val body = AppendPoints(trackId, seg, listOf(point))
        emit(body, now)
        val d = seg to OpCodec.digest(body)
        val set = digests.getOrPut(trackId) { HashSet() }
        if (set.add(d)) {
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
        rematerialize(Key(EntityKind.TRACK, t.id))
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

    override fun route(id: String): Route? = routeViews[id]

    override fun routes(): List<Route> = routeViews.values.sortedBy { it.id }

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

    override fun opsSince(since: VersionVector): List<Op> = ops.values.filter { !since.covers(it.hlc) }.sortedBy { it.hlc }

    override val opCount: Long get() = ops.size.toLong()

    override fun apply(bundle: Bundle, now: Long): ApplyResult {
        val plan = SyncRules.plan(bundle, vector())
        val touched = LinkedHashSet<Key>()
        var applied = 0
        var dup = plan.duplicate
        var dead = 0
        for (op in plan.take) {
            if (op.hlc in ops) { dup++; continue }
            val key = keyOf(op.body)
            val t = Materializer.tombstone(opsOf(key))
            if (Materializer.isDead(op.hlc, t)) { dead++; continue }
            hold(op)
            if (op.body is Delete) prune(key, op.hlc)
            applied++
            touched += key
        }
        for (h in plan.raise) {
            if (h > (vec[h.device] ?: Hlc.zero(h.device))) vec[h.device] = h
            clock.observe(h)
        }
        // Tracks first: waypoint visibility depends on them.
        touched.sortedBy { it.kind.code }.forEach { rematerialize(it) }
        return ApplyResult(bundle.ops.size, applied, dup, dead, plan.gapDevices, plan.rejected, touched.size)
    }

    override fun snapshot(): SyncSnapshot = SyncSnapshot.of(tracks.values.toList(), waypointViews.values.filter { visible(it) }, routes(), settings())

    override fun compact(now: Long, retentionMs: Long): CompactResult {
        val horizon = now - retentionMs
        var tombstones = 0
        for ((key, list) in byEntity.entries.toList()) {
            val old = list.filter { it.body is Delete && it.hlc.wall < horizon }
            if (old.isEmpty()) continue
            old.forEach { ops.remove(it.hlc) }
            list.removeAll(old)
            tombstones += old.size
            if (list.isEmpty()) byEntity.remove(key)
        }
        return CompactResult(tombstones, 0)
    }
}
