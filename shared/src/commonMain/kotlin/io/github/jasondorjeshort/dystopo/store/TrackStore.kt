package io.github.jasondorjeshort.dystopo.store

import io.github.jasondorjeshort.dystopo.gpx.TrackStats
import io.github.jasondorjeshort.dystopo.model.Track
import io.github.jasondorjeshort.dystopo.model.TrackPoint
import io.github.jasondorjeshort.dystopo.model.Waypoint

/** A stored track without its points: what a list screen or `GET /tracks` needs. */
data class TrackSummary(
    val id: String,
    val name: String,
    /** Epoch millis when the track was created (recording started). */
    val createdAt: Long,
    /** Epoch millis of the last change (point appended, waypoint added, renamed, stopped). */
    val updatedAt: Long,
    /** True while a recorder appends to it (and after a crash, until the app notices; see [TrackRecorder.recoverInterrupted]). */
    val recording: Boolean,
    val points: Int,
    val waypoints: Int,
)

/**
 * Durable storage for recorded tracks and their waypoints (T31), on top of T24's model.
 *
 * Tracks grow one point at a time ([append]) because a recorder writes each GPS fix as it arrives:
 * a crash or a kill loses at most the fix in flight. Points keep their order; a point with
 * `newSegment = true` starts a new [io.github.jasondorjeshort.dystopo.model.TrackSegment] (after a
 * pause or a lost fix). Waypoints belong to a track (marked while recording) or to none (`trackId`
 * null). Ids are the model's UUIDs and are kept as given.
 *
 * Implementations: [InMemoryTrackStore] for tests, [SqlTrackStore] (SQLDelight; SQLite on Android,
 * JDBC SQLite on the JVM) for the app. Implementations are not thread-safe by themselves beyond what
 * the database gives; the app funnels writes through one thread.
 */
interface TrackStore {
    /** Creates an empty track. Throws if [id] exists. */
    fun create(id: String, name: String, now: Long, recording: Boolean = false): TrackSummary

    /** Appends [point] to the track, in a new segment when [newSegment] (always for the first point). Throws if the track is unknown. */
    fun append(trackId: String, point: TrackPoint, newSegment: Boolean = false, now: Long = point.time ?: 0L)

    /** Adds a waypoint, to [trackId] or free-standing when null. */
    fun addWaypoint(trackId: String?, waypoint: Waypoint, now: Long = waypoint.time ?: 0L)

    fun setRecording(trackId: String, recording: Boolean, now: Long)

    fun rename(trackId: String, name: String, now: Long)

    /** Deletes the track, its points and its waypoints; false if it did not exist. */
    fun delete(trackId: String): Boolean

    /** All tracks, oldest first. */
    fun list(): List<TrackSummary>

    fun summary(trackId: String): TrackSummary?

    /** The track with all its points, or null. Empty segments are never returned. */
    fun track(trackId: String): Track?

    /** Waypoints of [trackId] (or the free-standing ones for null), in the order they were added. */
    fun waypoints(trackId: String?): List<Waypoint>

    /**
     * Stores a complete track (an import) and [waypoints] on it, in one transaction: the track's
     * fields and segments as given (empty segments dropped; segment extensions are not stored), not
     * recording. Throws if the track id or a waypoint id exists.
     */
    fun insert(track: Track, waypoints: List<Waypoint>, createdAt: Long, now: Long): TrackSummary

    /**
     * The track's [TrackStats] (default [io.github.jasondorjeshort.dystopo.gpx.StatsOptions]), cached
     * in the store: computed from the points on the first call and kept until a point is appended.
     * Null for an unknown track.
     */
    fun stats(trackId: String): TrackStats?

    /** How many [stats] calls had to compute (cache misses) since this store was opened. */
    val statsComputed: Long

    /** One waypoint by id with the track it belongs to, or null. */
    fun waypoint(id: String): StoredWaypoint?

    /** Renames a waypoint (and touches its track); false if unknown. */
    fun renameWaypoint(id: String, name: String, now: Long): Boolean

    /** Deletes a waypoint (and touches its track); false if unknown. */
    fun deleteWaypoint(id: String, now: Long): Boolean
}

/** A waypoint and the track it belongs to (null: free-standing). */
data class StoredWaypoint(val trackId: String?, val waypoint: Waypoint)

class TrackNotFoundException(id: String) : NoSuchElementException("no track $id")
