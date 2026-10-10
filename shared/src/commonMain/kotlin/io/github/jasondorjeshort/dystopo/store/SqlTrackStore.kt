package io.github.jasondorjeshort.dystopo.store

import app.cash.sqldelight.db.SqlDriver
import io.github.jasondorjeshort.dystopo.model.Track
import io.github.jasondorjeshort.dystopo.model.TrackPoint
import io.github.jasondorjeshort.dystopo.model.TrackSegment
import io.github.jasondorjeshort.dystopo.model.Waypoint
import io.github.jasondorjeshort.dystopo.store.db.TrackDb

/**
 * The durable [TrackStore]: SQLDelight over SQLite (schema in `src/commonMain/sqldelight/.../Track.sq`).
 * The caller builds the driver with [TrackDb.Schema] so a new database gets its tables: on Android
 * `AndroidSqliteDriver(TrackDb.Schema, context, "tracks.db")`, on the JVM
 * `JdbcSqliteDriver("jdbc:sqlite:tracks.db", Properties(), TrackDb.Schema)`.
 *
 * Each [append] is one INSERT in its own implicit transaction, so every fix is on disk when the
 * call returns (SQLite's journal makes a crash lose at most the write in flight).
 */
class SqlTrackStore(driver: SqlDriver) : TrackStore {
    private val q = TrackDb(driver).trackQueries

    /** Last (seq, segment) per track, so an append doesn't query for it. */
    private val tails = HashMap<String, Pair<Long, Long>>()

    override fun create(id: String, name: String, now: Long, recording: Boolean): TrackSummary {
        require(q.selectTrack(id).executeAsOneOrNull() == null) { "track $id exists" }
        q.insertTrack(id, name, now, now, if (recording) 1L else 0L, null, null, null, null, null)
        tails[id] = -1L to -1L
        return summary(id)!!
    }

    private fun tail(trackId: String): Pair<Long, Long> = tails.getOrPut(trackId) {
        if (q.selectTrack(trackId).executeAsOneOrNull() == null) throw TrackNotFoundException(trackId)
        q.lastPoint(trackId).executeAsOneOrNull()?.let { it.seq to it.segment } ?: (-1L to -1L)
    }

    override fun append(trackId: String, point: TrackPoint, newSegment: Boolean, now: Long) {
        val (seq, segment) = tail(trackId)
        val seg = if (newSegment || segment < 0) segment + 1 else segment
        q.transaction {
            q.insertPoint(trackId, seq + 1, seg, point.lat, point.lon, point.ele, point.time, point.extensions)
            q.touchTrack(now, trackId)
        }
        tails[trackId] = seq + 1 to seg
    }

    override fun addWaypoint(trackId: String?, waypoint: Waypoint, now: Long) {
        q.transaction {
            if (trackId != null) {
                if (q.selectTrack(trackId).executeAsOneOrNull() == null) throw TrackNotFoundException(trackId)
                q.touchTrack(now, trackId)
            }
            val w = waypoint
            q.insertWaypoint(w.id, trackId, w.name, w.lat, w.lon, w.ele, w.time, w.description, w.comment, w.symbol, w.type, w.extensions)
        }
    }

    override fun setRecording(trackId: String, recording: Boolean, now: Long) {
        q.setRecording(if (recording) 1L else 0L, now, trackId)
    }

    override fun rename(trackId: String, name: String, now: Long) {
        q.renameTrack(name, now, trackId)
    }

    override fun delete(trackId: String): Boolean {
        var existed = false
        q.transaction {
            existed = q.selectTrack(trackId).executeAsOneOrNull() != null
            q.deleteTrackPoints(trackId)
            q.deleteTrackWaypoints(trackId)
            q.deleteTrack(trackId)
        }
        tails.remove(trackId)
        return existed
    }

    override fun list(): List<TrackSummary> = q.selectTracks { id, name, createdAt, updatedAt, recording, _, _, _, _, _, points, waypoints ->
        TrackSummary(id, name, createdAt, updatedAt, recording != 0L, points.toInt(), waypoints.toInt())
    }.executeAsList()

    override fun summary(trackId: String): TrackSummary? =
        q.selectTrack(trackId) { id, name, createdAt, updatedAt, recording, _, _, _, _, _, points, waypoints ->
            TrackSummary(id, name, createdAt, updatedAt, recording != 0L, points.toInt(), waypoints.toInt())
        }.executeAsOneOrNull()

    override fun track(trackId: String): Track? {
        val t = q.selectTrack(trackId).executeAsOneOrNull() ?: return null
        val segments = ArrayList<TrackSegment>()
        var current = ArrayList<TrackPoint>()
        var currentSegment = Long.MIN_VALUE
        for (p in q.selectPoints(trackId).executeAsList()) {
            if (p.segment != currentSegment) {
                if (current.isNotEmpty()) segments += TrackSegment(current)
                current = ArrayList()
                currentSegment = p.segment
            }
            current += TrackPoint(p.lat, p.lon, p.ele, p.time, p.extensions)
        }
        if (current.isNotEmpty()) segments += TrackSegment(current)
        return Track(t.id, t.name, segments, t.description, t.comment, t.source, t.type, t.extensions)
    }

    override fun waypoints(trackId: String?): List<Waypoint> =
        q.selectWaypoints(trackId) { id, _, _, name, lat, lon, ele, time, description, comment, symbol, type, extensions ->
            Waypoint(id, name, lat, lon, ele, time, description, comment, symbol, type, extensions)
        }.executeAsList()
}
