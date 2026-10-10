package io.github.jasondorjeshort.dystopo.store

import io.github.jasondorjeshort.dystopo.model.Track
import io.github.jasondorjeshort.dystopo.model.TrackPoint
import io.github.jasondorjeshort.dystopo.model.TrackSegment
import io.github.jasondorjeshort.dystopo.model.Waypoint

/** A [TrackStore] in memory, for tests and as the reference behaviour [SqlTrackStore] is checked against. */
class InMemoryTrackStore : TrackStore {
    private class Entry(var summary: TrackSummary) {
        val segments = ArrayList<ArrayList<TrackPoint>>()
    }

    private val tracks = LinkedHashMap<String, Entry>()
    private val waypoints = ArrayList<Pair<String?, Waypoint>>()

    private fun entry(id: String) = tracks[id] ?: throw TrackNotFoundException(id)

    override fun create(id: String, name: String, now: Long, recording: Boolean): TrackSummary {
        require(id !in tracks) { "track $id exists" }
        val s = TrackSummary(id, name, now, now, recording, 0, 0)
        tracks[id] = Entry(s)
        return s
    }

    override fun append(trackId: String, point: TrackPoint, newSegment: Boolean, now: Long) {
        val e = entry(trackId)
        if (newSegment || e.segments.isEmpty()) e.segments += ArrayList<TrackPoint>()
        e.segments.last() += point
        e.summary = e.summary.copy(points = e.summary.points + 1, updatedAt = now)
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
        return Track(e.summary.id, e.summary.name, e.segments.filter { it.isNotEmpty() }.map { TrackSegment(it.toList()) })
    }

    override fun waypoints(trackId: String?): List<Waypoint> = waypoints.filter { it.first == trackId }.map { it.second }
}
