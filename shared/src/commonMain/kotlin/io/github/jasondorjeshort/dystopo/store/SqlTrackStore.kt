package io.github.jasondorjeshort.dystopo.store

import app.cash.sqldelight.db.SqlDriver
import io.github.jasondorjeshort.dystopo.geo.BoundingBox
import io.github.jasondorjeshort.dystopo.gpx.TrackStats
import io.github.jasondorjeshort.dystopo.model.Activity
import io.github.jasondorjeshort.dystopo.model.ActivityProfile
import io.github.jasondorjeshort.dystopo.model.GapReason
import io.github.jasondorjeshort.dystopo.model.TrackGap
import io.github.jasondorjeshort.dystopo.sync.AddGap
import io.github.jasondorjeshort.dystopo.model.Route
import io.github.jasondorjeshort.dystopo.model.RoutePath
import io.github.jasondorjeshort.dystopo.model.RoutePlan
import io.github.jasondorjeshort.dystopo.model.RouteWaypoints
import io.github.jasondorjeshort.dystopo.model.Track
import io.github.jasondorjeshort.dystopo.model.TrackPoint
import io.github.jasondorjeshort.dystopo.model.TrackSegment
import io.github.jasondorjeshort.dystopo.model.UuidGenerator
import io.github.jasondorjeshort.dystopo.model.Waypoint
import io.github.jasondorjeshort.dystopo.store.db.TrackDb
import io.github.jasondorjeshort.dystopo.sync.AppendPoints
import io.github.jasondorjeshort.dystopo.sync.ApplyResult
import io.github.jasondorjeshort.dystopo.sync.Bundle
import io.github.jasondorjeshort.dystopo.sync.CompactResult
import io.github.jasondorjeshort.dystopo.sync.Conflict
import io.github.jasondorjeshort.dystopo.sync.Conflicts
import io.github.jasondorjeshort.dystopo.sync.Paging
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
import io.github.jasondorjeshort.dystopo.sync.RouteListItem
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
 * The durable [TrackStore]: SQLDelight over SQLite (schema in `src/commonMain/sqldelight/.../Track.sq`).
 * The caller builds the driver with [TrackDb.Schema] so a new database gets its tables (and an old one
 * its migrations): on Android `AndroidSqliteDriver(TrackDb.Schema, context, "tracks.db")`, on the JVM
 * `JdbcSqliteDriver("jdbc:sqlite:tracks.db", Properties(), TrackDb.Schema)`.
 *
 * Since T44 it is a [SyncStore]: every change is first an [Op] in the `sync_op` table, and the
 * `track`, `track_point` and `waypoint` tables are its materialised view, updated in the same
 * transaction. Local changes take a direct path (a recorded fix is one op row plus one point row);
 * operations merged from another replica rematerialise the entities they touch with [Materializer],
 * which is what makes every replica agree. Routes and settings live only in the log and are
 * materialised when read. The device id is created on first open and kept in `sync_meta`; a database
 * from before T44 gets its log seeded from its rows on that first open (same ids, same data).
 *
 * [wallClock] is the physical clock under the HLC for calls without a `now` (delete) and a floor for
 * the others; the app passes `System::currentTimeMillis`.
 *
 * Each [append] is one transaction, so every fix is on disk when the call returns (SQLite's journal
 * makes a crash lose at most the write in flight).
 */
class SqlTrackStore(
    driver: SqlDriver,
    private val wallClock: () -> Long = { 0L },
    /** The clock-skew guard's limit (T51, see [HlcClock.observe]). */
    private val maxAheadMs: Long = HlcClock.MAX_AHEAD_MS,
    /** The device id for a new database (an existing one keeps its own); a random UUID by default. */
    newDeviceId: () -> String = { UuidGenerator().next() },
) : SyncStore {
    private val q = TrackDb(driver).trackQueries

    /** Last (seq, segment) per track, so an append doesn't query for it. */
    private val tails = HashMap<String, Pair<Long, Long>>()

    override val deviceId: String
    private val clock: HlcClock

    /** True when this open seeded the log from pre-T44 rows. */
    val seededFromTables: Boolean

    /** The run a recorded fix may extend (T51): this device's last single-point append, while it was added. */
    private class Run(val track: String, val segment: Long, val hlc: Hlc, val points: List<TrackPoint>)

    /** This device's newest operation that may have left in a bundle; runs never grow past it (T51). In memory: a restart starts no run. */
    private var shipped: Hlc? = null
    private var run: Run? = null

    /** Recorded fixes that extended a run instead of adding an operation, since open (T51). */
    var runsExtended = 0L
        private set

    override var skewLog: SkewLog
        private set

    /**
     * T60: routes written to the route table from the log on this open (after the schema-4 migration; T108: after the
     * schema-5 one, for the directions column); 0 otherwise. Declared before `init`, which sets it.
     */
    var routesMaterialized = 0
        private set

    /** T158: track rows whose fields were rewritten from the log on this open (after the schema-6 migration); 0 otherwise. */
    var tracksRefreshed = 0
        private set

    init {
        var seeded = false
        var device = ""
        q.transaction {
            device = q.getMeta("device").executeAsOneOrNull() ?: newDeviceId().also { q.putMeta("device", it) }
        }
        deviceId = device
        // T51: the clock resumes after this device's newest operation and every other device's entry
        // within the skew guard (a far-future entry merged earlier must not pin it on reopen either).
        val vector = vector()
        clock = HlcClock(deviceId, vector.entries[deviceId])
        val now = wallClock()
        for (h in vector.entries.values) if (h.device != deviceId) clock.observe(h, now, maxAheadMs)
        skewLog = SkewLog.decode(q.getMeta("skew").executeAsOneOrNull())
        q.transaction {
            if (q.getMeta("log").executeAsOneOrNull() == null) {
                seeded = seedLog()
                q.putMeta("log", "1")
            }
            // T60 (schema 4): the route table is filled from the log once, after the migration created it.
            // T108 (schema 5): again once, for the directions column (a log synced from a newer peer may hold them).
            if (q.getMeta("routes").executeAsOneOrNull() != ROUTES_TABLE) {
                var n = 0
                for (id in q.routeIdsInLog().executeAsList()) { rebuildRoute(id); n++ }
                routesMaterialized = n
                q.putMeta("routes", ROUTES_TABLE)
            }
            // T158 (schema 6): once, every track's fields from the log, for `activity` and `profile` writes an older
            // build held without showing them.
            if (q.getMeta("tracks").executeAsOneOrNull() != TRACKS_TABLE) {
                var n = 0
                for (t in q.selectTracks().executeAsList()) {
                    val view = Materializer.track(t.id, opsOf(EntityKind.TRACK, t.id)) ?: continue
                    if (view.activity != null || view.profile != null) q.setTrackActivity(view.activity, view.profile, t.id)
                    n++
                }
                tracksRefreshed = n
                q.putMeta("tracks", TRACKS_TABLE)
            }
        }
        seededFromTables = seeded
    }

    // ---- log ----

    private fun hold(op: Op) {
        val b = op.body
        q.insertOp(
            op.hlc.text, op.device, b.type.toLong(), b.kind.code.toLong(), b.id, b.parent,
            (b as? AppendPoints)?.let(OpCodec::digest), OpCodec.encode(op),
        )
        if (b is Delete) q.pruneEntity(b.kind.code.toLong(), b.id, op.hlc.text)
    }

    private fun emit(body: OpBody, now: Long): Op {
        val physical = maxOf(now, wallClock())
        // T51: the entity-level receive rule (see HlcClock.MAX_SEEN_AHEAD_MS).
        q.newestOfEntity(body.kind.code.toLong(), body.id).executeAsOneOrNull()?.let { clock.observe(Hlc.parse(it), physical, HlcClock.MAX_SEEN_AHEAD_MS) }
        val op = Op(clock.tick(physical), body)
        hold(op)
        q.putVector(deviceId, op.hlc.text)
        return op
    }

    private fun opsOf(kind: EntityKind, id: String): List<Op> = q.opsOfEntity(kind.code.toLong(), id).executeAsList().map(OpCodec::decode)

    private fun tombstone(kind: EntityKind, id: String): Hlc? = q.tombstoneOf(kind.code.toLong(), id).executeAsOneOrNull()?.let(Hlc::parse)

    /**
     * Writes ops for the rows of a version-2 database (tracks in creation order with their segments,
     * then every waypoint in its order), stamped by this device. Returns false when there was nothing.
     */
    private fun seedLog(): Boolean {
        val tracks = q.selectTracks().executeAsList()
        val waypoints = q.selectAllWaypoints().executeAsList()
        if (tracks.isEmpty() && waypoints.isEmpty()) return false
        val now = maxOf(wallClock(), tracks.maxOfOrNull { it.updated_at } ?: 0L)
        for (t in tracks) {
            emit(PutTrack(t.id, t.created_at, t.name, t.description, t.comment, t.source, t.type, t.extensions), now)
            q.selectPoints(t.id).executeAsList().groupBy { it.segment }.toList().sortedBy { it.first }.forEach { (seg, pts) ->
                emit(AppendPoints(t.id, seg.toInt(), pts.map { pointOf(it.lat, it.lon, it.ele, it.time, it.extensions, it.accuracy, it.speed, it.course, it.vertical_accuracy, it.flags) }), now)
            }
        }
        for (w in waypoints) {
            val op = emit(PutWaypoint(w.track_id, Waypoint(w.id, w.name, w.lat, w.lon, w.ele, w.time, w.description, w.comment, w.symbol, w.type, w.extensions)), now)
            q.setWaypointHlc(op.hlc.text, w.id)
        }
        return true
    }

    // ---- materialisation ----

    /** Rewrites the track's row (and its points unless [fieldsOnly]) from the log. */
    private fun rebuildTrack(id: String, fieldsOnly: Boolean = false) {
        val view = Materializer.track(id, opsOf(EntityKind.TRACK, id))
        val exists = q.selectTrack(id).executeAsOneOrNull() != null
        tails.remove(id)
        if (view == null) {
            if (exists) {
                q.deleteTrackPoints(id)
                q.deleteTrackGaps(id)
                q.deleteTrack(id)
            }
            return
        }
        if (!exists) {
            q.insertTrack(id, view.name, view.createdAt, view.lastHlc.wall, 0L, view.description, view.comment, view.source, view.type, view.extensions)
        }
        q.updateTrackFields(view.name, view.createdAt, view.description, view.comment, view.source, view.type, view.extensions, view.activity, view.profile, view.lastHlc.wall, id)
        if (!fieldsOnly || !exists) {
            q.deleteTrackPoints(id)
            var seq = 0L
            for (s in view.segments) for (p in s.points) insertPoint(id, seq++, s.number.toLong(), p)
            q.invalidateStats(id)
            q.deleteTrackGaps(id)
            for (g in view.gaps) q.insertGap(id, g.start, g.end, g.reason.code)
        }
    }

    private fun insertPoint(trackId: String, seq: Long, segment: Long, p: TrackPoint) =
        q.insertPoint(trackId, seq, segment, p.lat, p.lon, p.ele, p.time, p.extensions, p.accuracyM, p.speedMps, p.courseDeg, p.verticalAccuracyM, p.flags.toLong())

    private fun pointOf(lat: Double, lon: Double, ele: Double?, time: Long?, ext: String?, acc: Double?, speed: Double?, course: Double?, vacc: Double?, flags: Long) =
        TrackPoint(lat, lon, ele, time, ext, acc, speed, course, vacc, flags.toInt())

    private fun rebuildWaypoint(id: String) {
        val view = Materializer.waypoint(id, opsOf(EntityKind.WAYPOINT, id))
        if (view == null) {
            q.deleteWaypoint(id)
            return
        }
        val w = view.waypoint
        q.upsertWaypoint(id, view.trackId, w.name, w.lat, w.lon, w.ele, w.time, w.description, w.comment, w.symbol, w.type, w.extensions, view.order.text)
    }

    // ---- TrackStore ----

    override fun create(id: String, name: String, now: Long, recording: Boolean): TrackSummary {
        q.transaction {
            require(q.selectTrack(id).executeAsOneOrNull() == null) { "track $id exists" }
            emit(PutTrack(id, now, name), now)
            rebuildTrack(id)
            q.setRecording(if (recording) 1L else 0L, now, id)
        }
        return summary(id)!!
    }

    private fun tail(trackId: String): Pair<Long, Long> = tails.getOrPut(trackId) {
        if (q.selectTrack(trackId).executeAsOneOrNull() == null) throw TrackNotFoundException(trackId)
        q.lastPoint(trackId).executeAsOneOrNull()?.let { it.seq to it.segment } ?: (-1L to -1L)
    }

    override fun append(trackId: String, point: TrackPoint, newSegment: Boolean, now: Long) {
        val (seq, segment) = tail(trackId)
        val seg = if (newSegment || segment < 0) segment + 1 else segment
        var added = true
        val prev = run
        run = null
        var next: Run? = null
        q.transaction {
            if (prev != null && prev.track == trackId && prev.segment == seg && prev.points.size < AppendPoints.MAX_RUN &&
                vector().entries[deviceId] == prev.hlc && shipped.let { it == null || prev.hlc > it } &&
                q.newestOfEntity(EntityKind.TRACK.code.toLong(), trackId).executeAsOneOrNull() == prev.hlc.text
            ) {
                // T51: extend the run (one op row rewritten, one point row added). Its points are the
                // last of the segment, and so are the grown run's.
                val grown = AppendPoints(trackId, seg.toInt(), prev.points + point)
                if (q.countDigest(trackId, OpCodec.digest(grown)).executeAsOne() == 0L) {
                    q.deleteOp(prev.hlc.text)
                    val op = emit(grown, now)
                    insertPoint(trackId, seq + 1, seg, point)
                    q.touchTrackPoints(now, trackId)
                    next = Run(trackId, seg, op.hlc, grown.points)
                    return@transaction
                }
            }
            val body = AppendPoints(trackId, seg.toInt(), listOf(point))
            val op = emit(body, now)
            if (q.newestOfEntity(EntityKind.TRACK.code.toLong(), trackId).executeAsOneOrNull() != op.hlc.text) {
                // T51: the track holds an operation stamped later than this device's clock (merged past
                // the skew guard, not observed), so this append does not sort last: rebuild from the log.
                rebuildTrack(trackId)
                q.touchTrack(now, trackId)
                added = false
                return@transaction
            }
            // The same segment and points appended before (and still live) count once (see Materializer).
            val same = q.sameDigest(trackId, OpCodec.digest(body), op.hlc.text).executeAsList()
            val t = if (same.isEmpty()) null else tombstone(EntityKind.TRACK, trackId)
            added = same.none { t == null || Hlc.parse(it) > t }
            if (added) {
                insertPoint(trackId, seq + 1, seg, point)
                q.touchTrackPoints(now, trackId)
                next = Run(trackId, seg, op.hlc, body.points)
            } else {
                q.touchTrack(now, trackId)
            }
        }
        if (next != null && next!!.points.size > 1) runsExtended++
        run = next
        if (added) tails[trackId] = seq + 1 to seg
    }

    override fun insert(track: Track, waypoints: List<Waypoint>, createdAt: Long, now: Long): TrackSummary {
        q.transaction {
            require(q.selectTrack(track.id).executeAsOneOrNull() == null) { "track ${track.id} exists" }
            val ids = waypoints.map { it.id }
            require(ids.toSet().size == ids.size) { "duplicate waypoint ids" }
            for (w in waypoints) require(q.selectWaypoint(w.id).executeAsOneOrNull() == null) { "waypoint ${w.id} exists" }
            val t = track
            emit(PutTrack(t.id, createdAt, t.name, t.description, t.comment, t.source, t.type, t.extensions), now)
            t.segments.filter { it.points.isNotEmpty() }.forEachIndexed { i, s -> emit(AppendPoints(t.id, i, s.points), now) }
            t.activity?.let { emit(SetField(EntityKind.TRACK, t.id, "activity", it.code), now) } // T158
            t.profile?.let { emit(SetField(EntityKind.TRACK, t.id, "profile", it.encode()), now) }
            t.gaps.distinct().forEach { emit(AddGap(t.id, it), now) }
            rebuildTrack(t.id)
            check(q.selectTrack(t.id).executeAsOneOrNull() != null) { "track ${t.id} stays hidden by a delete stamped more than 30 days ahead of this device's clock" }
            for (w in waypoints) {
                emit(PutWaypoint(t.id, w), now)
                rebuildWaypoint(w.id)
            }
            q.touchTrack(now, t.id)
        }
        return summary(track.id)!!
    }

    override var statsComputed = 0L
        private set

    override fun stats(trackId: String): TrackStats? {
        val row = q.selectStats(trackId).executeAsOneOrNull() ?: return null
        val cached = row.stats_points?.let {
            val bounds = if (row.stats_west != null && row.stats_south != null && row.stats_east != null && row.stats_north != null) {
                BoundingBox(row.stats_west, row.stats_south, row.stats_east, row.stats_north)
            } else null
            TrackStats(
                segments = row.stats_segments!!.toInt(), points = it.toInt(), distanceM = row.stats_distance_m!!,
                ascentM = row.stats_ascent_m, descentM = row.stats_descent_m, minEleM = row.stats_min_ele_m, maxEleM = row.stats_max_ele_m,
                startTime = row.stats_start_time, endTime = row.stats_end_time, durationS = row.stats_duration_s,
                movingTimeS = row.stats_moving_time_s, movingSpeedMps = row.stats_moving_speed_mps,
                movingPaceSPerKm = row.stats_moving_pace_s_per_km, overallPaceSPerKm = row.stats_overall_pace_s_per_km,
                avgHeartRate = row.stats_avg_heart_rate, maxHeartRate = row.stats_max_heart_rate, bounds = bounds,
            )
        }
        if (cached != null) return cached
        val track = track(trackId) ?: return null
        val s = TrackStats.of(track)
        statsComputed++
        val b = s.bounds
        q.putStats(
            s.points.toLong(), s.segments.toLong(), s.distanceM, s.ascentM, s.descentM, s.minEleM, s.maxEleM,
            s.startTime, s.endTime, s.durationS, s.movingTimeS, s.movingSpeedMps, s.movingPaceSPerKm, s.overallPaceSPerKm,
            s.avgHeartRate, s.maxHeartRate, b?.west, b?.south, b?.east, b?.north, trackId,
        )
        return s
    }

    override fun waypoint(id: String): StoredWaypoint? =
        q.selectWaypoint(id) { wid, trackId, _, name, lat, lon, ele, time, description, comment, symbol, type, extensions, _ ->
            StoredWaypoint(trackId, Waypoint(wid, name, lat, lon, ele, time, description, comment, symbol, type, extensions))
        }.executeAsOneOrNull()

    override fun renameWaypoint(id: String, name: String, now: Long): Boolean = setField(EntityKind.WAYPOINT, id, "name", name, now)

    override fun deleteWaypoint(id: String, now: Long): Boolean {
        var found = false
        q.transaction {
            val w = waypoint(id) ?: return@transaction
            found = true
            emit(Delete(EntityKind.WAYPOINT, id), now)
            rebuildWaypoint(id)
            w.trackId?.let { q.touchTrack(now, it) }
        }
        return found
    }

    override fun addWaypoint(trackId: String?, waypoint: Waypoint, now: Long) {
        q.transaction {
            if (trackId != null && q.selectTrack(trackId).executeAsOneOrNull() == null) throw TrackNotFoundException(trackId)
            require(waypoint(waypoint.id) == null) { "waypoint ${waypoint.id} exists" }
            emit(PutWaypoint(trackId, waypoint), now)
            rebuildWaypoint(waypoint.id)
            trackId?.let { q.touchTrack(now, it) }
        }
    }

    override fun setRecording(trackId: String, recording: Boolean, now: Long) {
        // Local state of this device's recorder, not synced.
        q.setRecording(if (recording) 1L else 0L, now, trackId)
    }

    override fun rename(trackId: String, name: String, now: Long) {
        setField(EntityKind.TRACK, trackId, "name", name, now)
    }

    override fun setActivity(trackId: String, activity: Activity?, profile: ActivityProfile?, now: Long) {
        q.transaction {
            val t = q.selectTrack(trackId).executeAsOneOrNull() ?: throw TrackNotFoundException(trackId)
            if (t.activity != activity?.code) setField(EntityKind.TRACK, trackId, "activity", activity?.code, now)
            val encoded = profile?.encode()
            if (t.profile != encoded) setField(EntityKind.TRACK, trackId, "profile", encoded, now)
        }
    }

    override fun addGap(trackId: String, gap: TrackGap, now: Long) {
        q.transaction {
            if (q.selectTrack(trackId).executeAsOneOrNull() == null) throw TrackNotFoundException(trackId)
            emit(AddGap(trackId, gap), now)
            // A union: the row is the same whichever order gap operations arrive in.
            q.insertGap(trackId, gap.start, gap.end, gap.reason.code)
            q.touchTrack(now, trackId)
        }
    }

    override fun gaps(trackId: String): List<TrackGap> =
        q.selectGaps(trackId).executeAsList().mapNotNull { g -> GapReason.of(g.reason)?.let { TrackGap(g.start_time, g.end_time, it) } }

    override fun lastProfile(): ActivityProfile? = q.recentProfiles().executeAsList().firstNotNullOfOrNull { ActivityProfile.decode(it) }

    override fun delete(trackId: String): Boolean {
        var existed = false
        q.transaction {
            existed = q.selectTrack(trackId).executeAsOneOrNull() != null
            if (!existed) return@transaction
            val now = wallClock()
            for (wid in q.selectWaypointsOfTrack(trackId).executeAsList()) {
                emit(Delete(EntityKind.WAYPOINT, wid), now)
                rebuildWaypoint(wid)
            }
            emit(Delete(EntityKind.TRACK, trackId), now)
            rebuildTrack(trackId)
        }
        tails.remove(trackId)
        return existed
    }

    override fun list(): List<TrackSummary> = q.selectTracks { id, name, createdAt, updatedAt, recording, _, _, _, _, _, points, waypoints, _, _ ->
        TrackSummary(id, name, createdAt, updatedAt, recording != 0L, points.toInt(), waypoints.toInt())
    }.executeAsList()

    override fun summary(trackId: String): TrackSummary? =
        q.selectTrack(trackId) { id, name, createdAt, updatedAt, recording, _, _, _, _, _, points, waypoints, _, _ ->
            TrackSummary(id, name, createdAt, updatedAt, recording != 0L, points.toInt(), waypoints.toInt())
        }.executeAsOneOrNull()

    override fun track(trackId: String): Track? {
        val t = q.selectTrack(trackId).executeAsOneOrNull() ?: return null
        val segments = segmentsOf(trackId).map { TrackSegment(it.points) }
        return Track(t.id, t.name, segments, t.description, t.comment, t.source, t.type, t.extensions, Activity.of(t.activity), ActivityProfile.decode(t.profile), gaps(trackId))
    }

    private fun segmentsOf(trackId: String): List<SegmentView> {
        val segments = ArrayList<SegmentView>()
        var current = ArrayList<TrackPoint>()
        var currentSegment = Long.MIN_VALUE
        for (p in q.selectPoints(trackId).executeAsList()) {
            if (p.segment != currentSegment) {
                if (current.isNotEmpty()) segments += SegmentView(currentSegment.toInt(), current)
                current = ArrayList()
                currentSegment = p.segment
            }
            current += pointOf(p.lat, p.lon, p.ele, p.time, p.extensions, p.accuracy, p.speed, p.course, p.vertical_accuracy, p.flags)
        }
        if (current.isNotEmpty()) segments += SegmentView(currentSegment.toInt(), current)
        return segments
    }

    override fun waypoints(trackId: String?): List<Waypoint> {
        // Rows of a track that does not exist (added concurrently with its deletion) stay hidden.
        if (trackId != null && q.selectTrack(trackId).executeAsOneOrNull() == null) return emptyList()
        return q.selectWaypoints(trackId) { id, _, _, name, lat, lon, ele, time, description, comment, symbol, type, extensions, _ ->
            Waypoint(id, name, lat, lon, ele, time, description, comment, symbol, type, extensions)
        }.executeAsList()
    }

    // ---- SyncStore ----

    override fun setField(kind: EntityKind, id: String, field: String, value: String?, now: Long): Boolean {
        if (field !in kind.fields) return false
        var ok = false
        q.transaction {
            val trackOf: String? = when (kind) {
                EntityKind.TRACK -> if (q.selectTrack(id).executeAsOneOrNull() == null) return@transaction else id
                EntityKind.WAYPOINT -> (waypoint(id) ?: return@transaction).trackId
                EntityKind.ROUTE -> { q.selectRoute(id).executeAsOneOrNull() ?: return@transaction; null }
                EntityKind.SETTING -> { if (id !in settings()) return@transaction; null }
            }
            emit(SetField(kind, id, field, value), now)
            when (kind) {
                EntityKind.TRACK -> rebuildTrack(id, fieldsOnly = true)
                EntityKind.WAYPOINT -> rebuildWaypoint(id)
                EntityKind.ROUTE -> rebuildRoute(id)
                else -> Unit
            }
            trackOf?.let { q.touchTrack(now, it) }
            ok = true
        }
        return ok
    }

    override fun putRoute(route: Route, now: Long) {
        q.transaction {
            emit(PutRoute(route), now)
            rebuildRoute(route.id)
        }
    }

    override fun deleteRoute(id: String, now: Long): Boolean {
        var ok = false
        q.transaction {
            if (q.selectRoute(id).executeAsOneOrNull() == null) return@transaction
            emit(Delete(EntityKind.ROUTE, id), now)
            rebuildRoute(id)
            ok = true
        }
        return ok
    }

    override fun putRoutePlan(plan: RoutePlan, now: Long) {
        io.github.jasondorjeshort.dystopo.sync.RouteOps.checkPlan(plan) // T65: size cap
        q.transaction {
            emit(PutRoute(Route(plan.id, plan.name, emptyList(), plan.description, plan.comment, plan.type, plan.extensions)), now)
            emit(SetField(EntityKind.ROUTE, plan.id, "path", plan.path.quantized().encode()), now)
            emit(SetField(EntityKind.ROUTE, plan.id, "waypoints", RouteWaypoints.encode(plan.waypoints)), now)
            plan.directions?.let(io.github.jasondorjeshort.dystopo.model.RouteDirections::encode)?.let { emit(SetField(EntityKind.ROUTE, plan.id, "directions", it), now) } // T108
            rebuildRoute(plan.id)
            check(q.selectRoute(plan.id).executeAsOneOrNull() != null) { "route ${plan.id} stays hidden by a delete stamped more than 30 days ahead of this device's clock" }
        }
    }

    /** T60: rewrites the route's row from the log (or removes it), with its summary numbers. */
    private fun rebuildRoute(id: String) {
        val ops = opsOf(EntityKind.ROUTE, id)
        val plan = Materializer.routePlan(id, ops)
        if (plan == null) {
            q.deleteRouteRow(id)
            return
        }
        val live = Materializer.live(ops)
        val s = RouteListItem.of(plan, live.last().hlc.wall)
        q.upsertRoute(
            id, plan.name, plan.description, plan.comment, plan.type, plan.extensions, plan.path.encode(), RouteWaypoints.encode(plan.waypoints),
            live.first { it.body is PutRoute }.hlc.text, s.updatedAt, s.distanceM, s.ascentM, s.descentM, s.timeS,
            s.viaPoints.toLong(), s.waypoints.toLong(), s.straightM, s.mode,
            plan.directions?.let(io.github.jasondorjeshort.dystopo.model.RouteDirections::encode), // T108: only directions made for this path
        )
    }

    private fun planOf(r: io.github.jasondorjeshort.dystopo.store.db.Route): RoutePlan = RoutePlan(
        r.id, r.name, RoutePath.decode(r.path), RouteWaypoints.decode(r.waypoints), r.description, r.comment, r.type, r.extensions,
        io.github.jasondorjeshort.dystopo.model.RouteDirections.decodeOrNull(r.directions), // T108
    )

    override fun routePlan(id: String): RoutePlan? = q.selectRoute(id).executeAsOneOrNull()?.let(::planOf)

    override fun routePlans(): List<RoutePlan> = q.selectRoutes().executeAsList().map(::planOf)

    override fun routeList(): List<RouteListItem> = q.selectRoutes().executeAsList().map { r ->
        RouteListItem(r.id, r.name, r.mode, r.distance_m, r.ascent_m, r.descent_m, r.time_s, r.via_count.toInt(), r.waypoint_count.toInt(), r.straight_m, r.updated_at)
    }

    /** The routes as the log says they are (every route rematerialised): equal to [routePlans] when the table is faithful. */
    private fun routePlansFromLog(): List<RoutePlan> = ofKind(EntityKind.ROUTE) { id, ops -> Materializer.routePlan(id, ops) }.map { it.second }

    private fun <T : Any> ofKind(kind: EntityKind, f: (String, List<Op>) -> T?): List<Pair<String, T>> =
        q.opsOfKind(kind.code.toLong()).executeAsList().groupBy({ it.entity }, { OpCodec.decode(it.body) })
            .mapNotNull { (id, ops) -> f(id, ops)?.let { id to it } }.sortedBy { it.first }

    override fun setSetting(key: String, value: String?, now: Long) {
        q.transaction {
            if (value == null) {
                if (key in settings()) emit(Delete(EntityKind.SETTING, key), now)
            } else {
                emit(PutSetting(key, value), now)
            }
        }
    }

    override fun settings(): Map<String, String> = ofKind(EntityKind.SETTING) { _, ops -> Materializer.setting(ops) }.toMap()

    override fun vector(): VersionVector = VersionVector(q.selectVector().executeAsList().associate { it.device to Hlc.parse(it.hlc) })

    override fun opsSince(since: VersionVector): List<Op> {
        val vector = vector()
        shipped = vector.entries[deviceId]
        val out = ArrayList<Op>()
        for (device in vector.entries.keys) {
            q.opsOfDevice(device, since[device].text).executeAsList().mapTo(out, OpCodec::decode)
        }
        return out.sortedBy { it.hlc }
    }

    /** T51: reads at most [maxOps] operations per device, not the whole log. */
    override fun page(since: VersionVector, maxOps: Int, maxBytes: Int): Bundle {
        val full = vector()
        shipped = full.entries[deviceId]
        val candidates = ArrayList<Op>()
        val exhausted = HashSet<String>()
        for (device in full.entries.keys) {
            val rows = q.opsOfDeviceLimit(device, since[device].text, maxOps.toLong()).executeAsList()
            if (rows.size < maxOps) exhausted += device
            rows.mapTo(candidates, OpCodec::decode)
        }
        candidates.sortBy { it.hlc }
        return Paging.page(deviceId, since, full, candidates, { it in exhausted }, maxOps, maxBytes)
    }

    override fun pendingFor(peer: VersionVector): Long =
        vector().entries.keys.sumOf { q.countOpsOfDevice(it, peer[it].text).executeAsOne() }

    override val opCount: Long get() = q.countOps().executeAsOne()

    override fun apply(bundle: Bundle, now: Long): ApplyResult {
        var applied = 0
        var dup = 0
        var dead = 0
        var gaps = emptyList<String>()
        var rejected = 0
        var skew = SyncRules.Skew(0, 0, null)
        val conflicts = ArrayList<Conflict>()
        val touched = LinkedHashMap<Pair<EntityKind, String>, Boolean>() // value: fields only
        q.transaction {
            val plan = SyncRules.plan(bundle, vector())
            dup = plan.duplicate
            gaps = plan.gapDevices
            rejected = plan.rejected
            val tombstones = HashMap<Pair<EntityKind, String>, Hlc?>()
            for (op in plan.take) {
                if (q.hasOp(op.hlc.text).executeAsOne() > 0) { dup++; continue }
                val key = op.body.kind to op.body.id
                val t = tombstones.getOrPut(key) { tombstone(key.first, key.second) }
                if (Materializer.isDead(op.hlc, t)) { dead++; continue }
                if (Conflicts.fields(op.body) != null) {
                    val held = q.fieldOpsOfEntity(key.first.code.toLong(), key.second).executeAsList().map(OpCodec::decode)
                    conflicts += Conflicts.detect(op, held, bundle.vector, deviceId)
                }
                hold(op)
                if (op.body is Delete) tombstones[key] = op.hlc
                applied++
                touched[key] = (touched[key] ?: true) && op.body is SetField
            }
            for (h in plan.raise) {
                val mine = vector()[h.device]
                if (h > mine) q.putVector(h.device, h.text)
            }
            skew = SyncRules.observe(plan, clock, now, maxAheadMs)
            if (skew.count > 0) {
                skewLog = skewLog.plus(skew.count, skew.maxAheadMs, skew.device, now)
                q.putMeta("skew", skewLog.encode())
            }
            for ((key, fieldsOnly) in touched.entries.sortedBy { it.key.first.code }) {
                when (key.first) {
                    EntityKind.TRACK -> rebuildTrack(key.second, fieldsOnly)
                    EntityKind.WAYPOINT -> rebuildWaypoint(key.second)
                    EntityKind.ROUTE -> rebuildRoute(key.second) // T60
                    else -> Unit // settings are materialised on read
                }
            }
        }
        return ApplyResult(bundle.ops.size, applied, dup, dead, gaps, rejected, touched.size, skew.count, skew.maxAheadMs, conflicts)
    }

    /** The synced state as the tables show it (what the app displays). */
    override fun snapshot(): SyncSnapshot {
        val tracks = q.selectTracks().executeAsList().map { t ->
            TrackView(t.id, t.created_at, t.name, t.description, t.comment, t.source, t.type, t.extensions, segmentsOf(t.id), Hlc.zero(""), t.activity, t.profile, gaps(t.id))
        }.sortedBy { it.id }
        val waypoints = q.selectVisibleWaypoints().executeAsList().map { w ->
            WaypointView(
                w.track_id, Waypoint(w.id, w.name, w.lat, w.lon, w.ele, w.time, w.description, w.comment, w.symbol, w.type, w.extensions),
                w.created_hlc?.let(Hlc::parse) ?: Hlc.zero(""), Hlc.zero(""),
            )
        }
        return SyncSnapshot.of(tracks, waypoints, routePlans(), settings())
    }

    /**
     * The synced state computed from the log alone (every entity rematerialised): equal to [snapshot]
     * when the tables are a faithful view of the log. Used by tests, `sync-sim --sql` and `/sync/status`.
     */
    fun snapshotFromLog(): SyncSnapshot {
        val tracks = ofKind(EntityKind.TRACK) { id, ops -> Materializer.track(id, ops) }.map { it.second }
        val trackIds = tracks.map { it.id }.toSet()
        val waypoints = ofKind(EntityKind.WAYPOINT) { id, ops -> Materializer.waypoint(id, ops) }.map { it.second }
            .filter { it.trackId == null || it.trackId in trackIds }
        return SyncSnapshot.of(tracks, waypoints, routePlansFromLog(), settings())
    }

    override fun compact(now: Long, retentionMs: Long): CompactResult {
        var dropped = 0
        var superseded = 0
        q.transaction {
            val horizon = Hlc((now - retentionMs).coerceIn(0, Hlc.MAX_WALL), 0, "").text
            for (h in q.oldTombstones(horizon).executeAsList()) {
                q.deleteOp(h)
                dropped++
            }
            // T65: field writes superseded by a newer write of the same field (a route keeps one `path` value).
            val routeOps = q.opsOfKind(EntityKind.ROUTE.code.toLong()).executeAsList().groupBy({ it.entity }, { OpCodec.decode(it.body) })
            RouteOps.compactable(routeOps.values).forEach { q.deleteOp(it.text); superseded++ }
        }
        return CompactResult(dropped, superseded)
    }

    private companion object {
        /** T108: sync_meta `routes` once the route table holds every route with its directions (schema 5); "1" was T60's. */
        const val ROUTES_TABLE = "2"

        /** T158: sync_meta `tracks` once every track row's fields were rewritten from the log (schema 6). */
        const val TRACKS_TABLE = "6"
    }
}
