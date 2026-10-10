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

/** Which fixes a [TrackRecorder] keeps, and when it starts a new segment. */
data class RecorderOptions(
    /** Fixes less accurate than this are dropped (a phone indoors or just after a cold start reports 100 m and more). */
    val maxAccuracyM: Double = 50.0,
    /**
     * A fix whose time is more than this after the previous accepted fix starts a new segment (the
     * receiver lost the sky, or the phone slept), so distance and moving time never bridge the gap.
     * Null: never split on time. Measured on the fixes' own times, so a GPX replay splits where the file has a gap.
     */
    val segmentGapMs: Long? = DEFAULT_SEGMENT_GAP_MS,
    /**
     * T65: ...and only when the fix is also more than this many metres from the previous one. A gap spent in
     * one place (a long stop: phones deliver no fixes while still, and the synthetic Skyline fixture has
     * none for its 15 minutes at Panorama Point) is a pause, not a hole in the line, so the track keeps one
     * segment; a gap that moved the walker leaves a hole stats must not bridge.
     */
    val segmentGapMinM: Double = DEFAULT_SEGMENT_GAP_MIN_M,
) {
    init {
        require(segmentGapMs == null || segmentGapMs > 0) { "segmentGapMs must be positive" }
        require(segmentGapMinM >= 0) { "segmentGapMinM must not be negative" }
    }

    companion object {
        const val DEFAULT_SEGMENT_GAP_MS = 5 * 60_000L
        const val DEFAULT_SEGMENT_GAP_MIN_M = 50.0
    }
}

/**
 * Turns a stream of [LocationFix]es into a track in a [TrackStore]: [start] creates the track,
 * [onFix] appends each accepted fix (written through at once, so a kill loses nothing already
 * accepted), [pause] and [resume] hold recording (the first fix after a resume starts a new
 * segment), [stop] closes it. It also keeps the active track's points in memory so [liveStats]
 * doesn't reread the store.
 *
 * Rejected fixes are counted, not stored: worse than [RecorderOptions.maxAccuracyM], not newer
 * than the last accepted fix (duplicates and out-of-order reports, which GPS chipsets and replays
 * both produce), or arriving while paused. A fix more than [RecorderOptions.segmentGapMs] after the
 * last accepted one and more than [RecorderOptions.segmentGapMinM] from it starts a new segment. Not thread-safe: the app calls it from one thread.
 */
class TrackRecorder(
    private val store: TrackStore,
    options: RecorderOptions = RecorderOptions(),
    private val ids: UuidGenerator = UuidGenerator(),
    private val clock: () -> Long,
) {
    /** Takes effect from the next fix (the app changes the gap from its settings). */
    var options: RecorderOptions = options

    var activeId: String? = null
        private set
    /** The last fix seen from any source (accepted or not, recording or not): where "here" is for a waypoint. */
    var lastFix: LocationFix? = null
        private set
    /** True between [pause] and [resume] (or [stop]): fixes are counted in [skippedPaused], not stored. */
    var paused = false
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
    var skippedPaused = 0L
        private set
    /** Segments started because of a time gap (not counting the first segment or resumes). */
    var gapSplits = 0L
        private set

    val recording: Boolean get() = activeId != null

    /** Time of the last fix appended to the active track: a fix must be newer than this to be kept. */
    val lastAcceptedTime: Long? get() = lastAccepted?.time

    /** Starts recording into a new track (stopping the current one first). */
    fun start(name: String): TrackSummary {
        stop()
        val now = clock()
        val s = store.create(ids.next(), name, now, recording = true)
        activeId = s.id
        lastAccepted = null
        newSegment = true
        paused = false
        live.clear()
        accepted = 0
        rejectedAccuracy = 0
        rejectedTime = 0
        skippedPaused = 0
        gapSplits = 0
        return s
    }

    /** Feeds one fix; returns true when it was appended to the active track. */
    fun onFix(fix: LocationFix): Boolean {
        lastFix = fix
        val id = activeId ?: return false
        if (paused) {
            skippedPaused++
            return false
        }
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
        val gap = options.segmentGapMs
        if (prev != null && !newSegment && gap != null && fix.time - prev.time > gap &&
            TrackStats.distanceM(prev.lat, prev.lon, fix.lat, fix.lon) > options.segmentGapMinM
        ) {
            newSegment = true
            gapSplits++
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

    /** Holds recording: fixes are ignored until [resume]. No effect when not recording. */
    fun pause() {
        if (activeId != null) paused = true
    }

    /** Continues after [pause]; the next accepted fix starts a new segment. */
    fun resume() {
        if (activeId == null || !paused) return
        paused = false
        newSegment = true
    }

    /** Stops recording; returns the finished track's summary, or null when nothing was recording. */
    fun stop(): TrackSummary? {
        val id = activeId ?: return null
        store.setRecording(id, false, clock())
        activeId = null
        paused = false
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
