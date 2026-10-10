package io.github.jasondorjeshort.dystopo.store

import io.github.jasondorjeshort.dystopo.gpx.TrackStats
import io.github.jasondorjeshort.dystopo.model.Track
import io.github.jasondorjeshort.dystopo.model.TrackPoint
import io.github.jasondorjeshort.dystopo.model.TrackSegment
import io.github.jasondorjeshort.dystopo.model.Waypoint

/** A [TrackStore] in memory, for tests and as the reference behaviour [SqlTrackStore] is checked against. */
class InMemoryTrackStore : TrackStore {
    private class Entry(var summary: TrackSummary, var meta: Track) {
        val segments = ArrayList<ArrayList<TrackPoint>>()
        var stats: TrackStats? = null
    }

    private val tracks = LinkedHashMap<String, Entry>()
    private val waypoints = ArrayList<Pair<String?, Waypoint>>()

    override var statsComputed = 0L
        private set

    private fun entry(id: String) = tracks[id] ?: throw TrackNotFoundException(id)

    override fun create(id: String, name: String, now: Long, recording: Boolean): TrackSummary {
        require(id !in tracks) { "track $id exists" }
        val s = TrackSummary(id, name, now, now, recording, 0, 0)
        tracks[id] = Entry(s, Track(id, name, emptyList()))
        return s
    }

    override fun append(trackId: String, point: TrackPoint, newSegment: Boolean, now: Long) {
        val e = entry(trackId)
        if (newSegment || e.segments.isEmpty()) e.segments += ArrayList<TrackPoint>()
        e.segments.last() += point
        e.summary = e.summary.copy(points = e.summary.points + 1, updatedAt = now)
        e.stats = null
    }

    override fun addWaypoint(trackId: String?, waypoint: Waypoint, now: Long) {
        require(waypoints.none { it.second.id == waypoint.id }) { "waypoint ${waypoint.id} exists" }
        if (trackId != null) {
            val e = entry(trackId)
            e.summary = e.summary.copy(waypoints = e.summary.waypoints + 1, updatedAt = now)
        }
        waypoints += trackId to waypoint
    }

    override fun setRecording(trackId: String, recording: Boolean, now: Long) {
        val e = entry(trackId)
        e.summary = e.summary.copy(recording = recording, updatedAt = now)
    }

    override fun rename(trackId: String, name: String, now: Long) {
        val e = entry(trackId)
        e.summary = e.summary.copy(name = name, updatedAt = now)
    }

    override fun delete(trackId: String): Boolean {
        waypoints.removeAll { it.first == trackId }
        return tracks.remove(trackId) != null
    }

    override fun list(): List<TrackSummary> = tracks.values.map { it.summary }.sortedWith(compareBy({ it.createdAt }, { it.id }))

    override fun summary(trackId: String): TrackSummary? = tracks[trackId]?.summary

    override fun track(trackId: String): Track? {
        val e = tracks[trackId] ?: return null
        return e.meta.copy(name = e.summary.name, segments = e.segments.filter { it.isNotEmpty() }.map { TrackSegment(it.toList()) })
    }

    override fun waypoints(trackId: String?): List<Waypoint> = waypoints.filter { it.first == trackId }.map { it.second }

    override fun insert(track: Track, waypoints: List<Waypoint>, createdAt: Long, now: Long): TrackSummary {
        require(track.id !in tracks) { "track ${track.id} exists" }
        val ids = waypoints.map { it.id }
        require(ids.toSet().size == ids.size && this.waypoints.none { it.second.id in ids }) { "a waypoint id exists" }
        val segments = track.segments.filter { it.points.isNotEmpty() }
        val e = Entry(
            TrackSummary(track.id, track.name, createdAt, now, false, segments.sumOf { it.points.size }, waypoints.size),
            track.copy(segments = emptyList()),
        )
        segments.forEach { e.segments += ArrayList(it.points) }
        tracks[track.id] = e
        waypoints.forEach { this.waypoints += track.id to it }
        return e.summary
    }

    override fun stats(trackId: String): TrackStats? {
        val e = tracks[trackId] ?: return null
        e.stats?.let { return it }
        statsComputed++
        return TrackStats.of(e.segments.filter { it.isNotEmpty() }).also { e.stats = it }
    }

    override fun waypoint(id: String): StoredWaypoint? = waypoints.firstOrNull { it.second.id == id }?.let { StoredWaypoint(it.first, it.second) }

    override fun renameWaypoint(id: String, name: String, now: Long): Boolean {
        val i = waypoints.indexOfFirst { it.second.id == id }
        if (i < 0) return false
        val (trackId, w) = waypoints[i]
        waypoints[i] = trackId to w.copy(name = name)
        touch(trackId, now)
        return true
    }

    override fun deleteWaypoint(id: String, now: Long): Boolean {
        val i = waypoints.indexOfFirst { it.second.id == id }
        if (i < 0) return false
        val trackId = waypoints.removeAt(i).first
        if (trackId != null) tracks[trackId]?.let { it.summary = it.summary.copy(waypoints = it.summary.waypoints - 1) }
        touch(trackId, now)
        return true
    }

    private fun touch(trackId: String?, now: Long) {
        val e = trackId?.let { tracks[it] } ?: return
        e.summary = e.summary.copy(updatedAt = now)
    }
}
