package io.github.jasondorjeshort.dystopo.store

import io.github.jasondorjeshort.dystopo.gpx.TrackStats
import io.github.jasondorjeshort.dystopo.model.TrackPoint
import io.github.jasondorjeshort.dystopo.model.UuidGenerator
import io.github.jasondorjeshort.dystopo.model.Waypoint

/**
 * One position report from any location source: the phone's GPS, a fix injected through the debug
 * server, or a GPX replay. [time] is epoch millis (a replay keeps the file's own times, so a
 * replayed track has the original's durations); [ele] is meters above mean sea level when the
 * source knows it; [accuracyM] is the horizontal accuracy radius.
 */
data class LocationFix(
    val lat: Double,
    val lon: Double,
    val ele: Double? = null,
    val time: Long,
    val accuracyM: Double? = null,
    val source: String = "gps",
)

/** Which fixes a [TrackRecorder] keeps. */
data class RecorderOptions(
    /** Fixes less accurate than this are dropped (a phone indoors or just after a cold start reports 100 m and more). */
    val maxAccuracyM: Double = 50.0,
)

/**
 * Turns a stream of [LocationFix]es into a track in a [TrackStore]: [start] creates the track,
 * [onFix] appends each accepted fix (written through at once, so a kill loses nothing already
 * accepted), [pause] makes the next fix start a new segment, [stop] closes it. It also keeps the
 * active track's points in memory so [liveStats] doesn't reread the store.
 *
 * Rejected fixes are counted, not stored: worse than [RecorderOptions.maxAccuracyM], or not newer
 * than the last accepted fix (duplicates and out-of-order reports, which GPS chipsets and
 * replays both produce). Not thread-safe: the app calls it from one thread.
 */
class TrackRecorder(
    private val store: TrackStore,
    private val options: RecorderOptions = RecorderOptions(),
    private val ids: UuidGenerator = UuidGenerator(),
    private val clock: () -> Long,
) {
    var activeId: String? = null
        private set
    /** The last fix seen from any source (accepted or not, recording or not): where "here" is for a waypoint. */
    var lastFix: LocationFix? = null
        private set

    private var lastAccepted: LocationFix? = null
    private var newSegment = true
    private val live = ArrayList<ArrayList<TrackPoint>>()

    var accepted = 0L
        private set
    var rejectedAccuracy = 0L
        private set
    var rejectedTime = 0L
        private set

    val recording: Boolean get() = activeId != null

    /** Starts recording into a new track (stopping the current one first). */
    fun start(name: String): TrackSummary {
        stop()
        val now = clock()
        val s = store.create(ids.next(), name, now, recording = true)
        activeId = s.id
        lastAccepted = null
        newSegment = true
        live.clear()
        accepted = 0
        rejectedAccuracy = 0
        rejectedTime = 0
        return s
    }

    /** Feeds one fix; returns true when it was appended to the active track. */
    fun onFix(fix: LocationFix): Boolean {
        lastFix = fix
        val id = activeId ?: return false
        val acc = fix.accuracyM
        if (acc != null && acc > options.maxAccuracyM) {
            rejectedAccuracy++
            return false
        }
        val prev = lastAccepted
        if (prev != null && fix.time <= prev.time) {
            rejectedTime++
            return false
        }
        val p = TrackPoint(fix.lat, fix.lon, fix.ele, fix.time)
        store.append(id, p, newSegment, clock())
        if (newSegment || live.isEmpty()) live += ArrayList<TrackPoint>()
        live.last() += p
        newSegment = false
        lastAccepted = fix
        accepted++
        return true
    }

    /** The next accepted fix starts a new segment (recording paused, or the fix was lost for a while). */
    fun pause() {
        newSegment = true
    }

    /** Stops recording; returns the finished track's summary, or null when nothing was recording. */
    fun stop(): TrackSummary? {
        val id = activeId ?: return null
        store.setRecording(id, false, clock())
        activeId = null
        return store.summary(id)
    }

    /** Stats of the active track from the in-memory copy, or null when not recording. */
    fun liveStats(): TrackStats? = if (activeId == null) null else TrackStats.of(live)

    /** Points of the active track so far (for drawing it), segment by segment. */
    fun livePoints(): List<List<TrackPoint>> = live.map { it.toList() }

    /**
     * Marks a waypoint on [trackId] (default: the active track; free-standing when there is none) at
     * the given position, or at the last fix. Returns null when there is no position at all.
     */
    fun waypoint(name: String?, trackId: String? = activeId, lat: Double? = null, lon: Double? = null, ele: Double? = null): Waypoint? {
        val fix = lastFix
        val la = lat ?: fix?.lat ?: return null
        val lo = lon ?: fix?.lon ?: return null
        val e = if (lat != null) ele else ele ?: fix?.ele
        val now = clock()
        val time = if (lat == null && fix != null) fix.time else now
        val n = name ?: "Waypoint ${(trackId?.let { store.summary(it)?.waypoints } ?: store.waypoints(null).size) + 1}"
        val w = Waypoint(ids.next(), n, la, lo, e, time)
        store.addWaypoint(trackId, w, now)
        return w
    }

    /**
     * Tracks still marked as recording that this recorder is not recording: the process died while
     * recording. They are closed (kept as they are) and their ids returned.
     */
    fun recoverInterrupted(): List<String> {
        val stale = store.list().filter { it.recording && it.id != activeId }.map { it.id }
        val now = clock()
        for (id in stale) store.setRecording(id, false, now)
        return stale
    }
}
