package io.github.jasondorjeshort.dystopo.store

import io.github.jasondorjeshort.dystopo.gpx.TrackStats
import io.github.jasondorjeshort.dystopo.model.Activity
import io.github.jasondorjeshort.dystopo.model.ActivityProfile
import io.github.jasondorjeshort.dystopo.model.GapReason
import io.github.jasondorjeshort.dystopo.model.TrackGap
import io.github.jasondorjeshort.dystopo.model.TrackPoint
import io.github.jasondorjeshort.dystopo.model.UuidGenerator
import io.github.jasondorjeshort.dystopo.model.Waypoint

/**
 * One position report from any location source: the phone's GPS, a fix injected through the debug
 * server, or a GPX replay. [time] is epoch millis (a replay keeps the file's own times, so a
 * replayed track has the original's durations); [ele] is meters above mean sea level when the
 * source knows it; [accuracyM] is the horizontal accuracy radius.
 *
 * T158: [speedMps] (the receiver's Doppler ground speed), [courseDeg] (course over ground, degrees from true
 * north) and [verticalAccuracyM], each null when the source gave none (T159 fills them from the platform). An
 * accuracy of 0 or less (network fixes report 0) is read as unknown. [source] "network" or "passive" marks a
 * fallback fix ([TrackPoint.NETWORK]).
 */
data class LocationFix(
    val lat: Double,
    val lon: Double,
    val ele: Double? = null,
    val time: Long,
    val accuracyM: Double? = null,
    val source: String = "gps",
    val speedMps: Double? = null,
    val courseDeg: Double? = null,
    val verticalAccuracyM: Double? = null,
)

/**
 * Turns a stream of [LocationFix]es into a track in a [TrackStore]: [start] creates the track,
 * [onFix] appends each kept fix (written through at once, so a kill loses nothing already
 * kept), [pause] and [resume] hold recording, [stop] closes it. It also keeps the active track's points in
 * memory so [liveStats] doesn't reread the store. Not thread-safe: the app calls it from one thread.
 *
 * T158, what happens to a fix while recording, by the [profile] (stored on the track at [start]):
 * - **Kept, with its accuracy, speed, course and vertical accuracy.** A fix worse than
 *   [ActivityProfile.accuracyBoundM] is kept and flagged ([TrackPoint.INACCURATE], counted in [flagged]); a
 *   network or passive fix is flagged [TrackPoint.NETWORK]. The owner's rule (2026-10-25): poor fixes are
 *   weighted later (T171), never dropped.
 * - **Dropped** only when not newer than the last kept fix ([rejectedTime]; duplicates and out-of-order reports),
 *   while paused or auto-paused ([skippedPaused], [skippedAutoPaused]), or as a teleport ([rejectedSpeed]): it
 *   would need more than [ActivityProfile.speedBoundMps] from the last kept fix after both accuracy radii are
 *   allowed for. After [REANCHOR_AFTER] teleports in a row the last kept fix is taken to be the stray one: the
 *   fix is kept after a [GapReason.REJECTED] gap.
 * - **Gaps** ([TrackGap], [TrackStore.addGap]) are written when the stream resumes: after the profile's
 *   [ActivityProfile.gap] rule ([GapReason.NO_FIX], or [GapReason.REJECTED] when fixes in the hole were all
 *   teleports; counted in [gapSplits]), after a pause or an auto-pause, after a process death
 *   ([recoverInterrupted]) or an [interrupt]. The fix after a gap starts a new segment.
 * - **Auto-pause** ([ActivityProfile.autoPause], [ActivityProfile.autoPauseRule]): the fix's own speed (else the
 *   speed between the last two fixes) under the still threshold for the rule's time pauses ([autoPaused]); over
 *   the resume threshold for its time resumes, with a [GapReason.AUTO_PAUSED] gap.
 */
class TrackRecorder(
    private val store: TrackStore,
    profile: ActivityProfile? = null,
    private val ids: UuidGenerator = UuidGenerator(),
    private val clock: () -> Long,
) {
    /**
     * The profile [start] stores on a new track (unless given another) and [onFix] applies; changes take effect
     * from the next fix. Initially the one passed in, else the remembered one ([TrackStore.lastProfile]: the newest
     * track's), else [Activity.FOOT]'s defaults. [start] with a profile makes it the remembered one.
     */
    var profile: ActivityProfile = profile ?: store.lastProfile() ?: ActivityProfile.of(Activity.FOOT)

    var activeId: String? = null
        private set
    /** The last fix seen from any source (kept or not, recording or not): where "here" is for a waypoint. */
    var lastFix: LocationFix? = null
        private set
    /** True between [pause] and [resume] (or [stop]): fixes are counted in [skippedPaused], not stored. */
    var paused = false
        private set
    /** True while auto-pause holds recording (see the class comment); fixes are counted in [skippedAutoPaused]. */
    var autoPaused = false
        private set

    /** A hole that is written as a gap when the next fix is kept. */
    private class Pending(val reason: GapReason, val start: Long?)

    private var pending: Pending? = null
    private var lastAccepted: LocationFix? = null
    private var newSegment = true
    private var rejectedRun = 0
    private var rejectedSinceKept = false
    private var stillSince: Long? = null
    private var movingSince: Long? = null
    private var prevSeen: LocationFix? = null
    private val live = ArrayList<ArrayList<TrackPoint>>()

    var accepted = 0L
        private set
    /** Fixes kept but worse than the profile's accuracy bound (T158; before T158 they were dropped). */
    var flagged = 0L
        private set
    /** Teleports dropped (T158). */
    var rejectedSpeed = 0L
        private set
    var rejectedTime = 0L
        private set
    var skippedPaused = 0L
        private set
    var skippedAutoPaused = 0L
        private set
    /** Segments started by the profile's gap rule (not counting the first segment, pauses or resumes). */
    var gapSplits = 0L
        private set
    /** Gap records written on the active track since [start] (or [recoverInterrupted]). */
    var gapsWritten = 0L
        private set

    val recording: Boolean get() = activeId != null

    /** Time of the last fix appended to the active track: a fix must be newer than this to be kept. */
    val lastAcceptedTime: Long? get() = lastAccepted?.time

    /** The reason of the gap the next kept fix will close (a pause, an auto-pause, a restart), or null. */
    val pendingGap: GapReason? get() = pending?.reason

    private fun reset() {
        lastAccepted = null
        newSegment = true
        paused = false
        autoPaused = false
        pending = null
        rejectedRun = 0
        rejectedSinceKept = false
        stillSince = null
        movingSince = null
        prevSeen = null
        live.clear()
        accepted = 0
        flagged = 0
        rejectedSpeed = 0
        rejectedTime = 0
        skippedPaused = 0
        skippedAutoPaused = 0
        gapSplits = 0
        gapsWritten = 0
    }

    /** Starts recording into a new track (stopping the current one first) with [profile], which is remembered. */
    fun start(name: String, profile: ActivityProfile = this.profile): TrackSummary {
        stop()
        this.profile = profile
        val now = clock()
        val s = store.create(ids.next(), name, now, recording = true)
        store.setActivity(s.id, profile.activity, profile, now)
        activeId = s.id
        reset()
        return store.summary(s.id) ?: s
    }

    /** Feeds one fix; returns true when it was appended to the active track. */
    fun onFix(fix: LocationFix): Boolean {
        lastFix = fix
        val id = activeId ?: return false
        val seen = prevSeen
        if (seen == null || fix.time > seen.time) prevSeen = fix
        if (paused) {
            skippedPaused++
            return false
        }
        val prev = lastAccepted
        if (prev != null && fix.time <= prev.time) {
            rejectedTime++
            return false
        }
        val p = profile
        if (!p.autoPause && autoPaused) autoPaused = false // switched off while paused: the pending gap stays
        if (p.autoPause && autoPauseHolds(fix, seen, p)) {
            skippedAutoPaused++
            return false
        }
        val acc = fix.accuracyM?.takeIf { it > 0 && it.isFinite() }
        var reason: GapReason? = null
        if (prev != null) {
            val d = TrackStats.distanceM(prev.lat, prev.lon, fix.lat, fix.lon)
            val excess = d - (prev.accuracyM ?: 0.0) - (acc ?: 0.0)
            if (excess > 0 && excess > p.speedBoundMps * (fix.time - prev.time) / 1000.0) {
                rejectedSinceKept = true
                if (++rejectedRun < REANCHOR_AFTER) {
                    rejectedSpeed++
                    return false
                }
                reason = GapReason.REJECTED
            }
            val gap = p.gap
            if (reason == null && !newSegment && gap.timeMs != null && fix.time - prev.time > gap.timeMs &&
                (gap.minDistanceM == null || d > gap.minDistanceM)
            ) {
                reason = if (rejectedSinceKept) GapReason.REJECTED else GapReason.NO_FIX
                gapSplits++
            }
        }
        val hole = pending
        if (prev != null && (hole != null || reason != null)) {
            val start = hole?.start ?: prev.time
            store.addGap(id, TrackGap(minOf(start, fix.time), fix.time, hole?.reason ?: reason!!), clock())
            gapsWritten++
            newSegment = true
        }
        pending = null
        var flags = 0
        val bound = p.accuracyBoundM
        if (acc != null && bound != null && acc > bound) {
            flags = flags or TrackPoint.INACCURATE
            flagged++
        }
        if (fix.source == "network" || fix.source == "passive") flags = flags or TrackPoint.NETWORK
        val point = TrackPoint(
            fix.lat, fix.lon, fix.ele, fix.time, null, acc,
            fix.speedMps?.takeIf { it.isFinite() && it >= 0 },
            fix.courseDeg?.takeIf { it.isFinite() },
            fix.verticalAccuracyM?.takeIf { it.isFinite() && it > 0 },
            flags,
        )
        store.append(id, point, newSegment, clock())
        if (newSegment || live.isEmpty()) live += ArrayList<TrackPoint>()
        live.last() += point
        newSegment = false
        lastAccepted = fix.copy(accuracyM = acc)
        accepted++
        rejectedRun = 0
        rejectedSinceKept = false
        return true
    }

    /** Auto-pause (see the class comment): true when [fix] is held back. */
    private fun autoPauseHolds(fix: LocationFix, seen: LocationFix?, p: ActivityProfile): Boolean {
        val speed = fix.speedMps?.takeIf { it.isFinite() && it >= 0 }
            ?: seen?.takeIf { fix.time > it.time }?.let { TrackStats.distanceM(it.lat, it.lon, fix.lat, fix.lon) * 1000.0 / (fix.time - it.time) }
        val r = p.autoPauseRule
        if (!autoPaused) {
            if (speed == null) return false
            if (speed >= r.stillMps) {
                stillSince = null
                return false
            }
            val since = stillSince ?: fix.time.also { stillSince = it }
            val last = lastAccepted
            if (fix.time - since < r.pauseAfterMs || last == null) return false
            autoPaused = true
            movingSince = null
            if (pending == null) pending = Pending(GapReason.AUTO_PAUSED, last.time)
            return true
        }
        if (speed == null || speed <= r.resumeMps) {
            movingSince = null
            return true
        }
        val since = movingSince ?: fix.time.also { movingSince = it }
        if (fix.time - since < r.resumeAfterMs) return true
        autoPaused = false
        stillSince = null
        movingSince = null
        return false
    }

    /** Holds recording: fixes are ignored until [resume], which closes a [GapReason.PAUSED] gap. No effect when not recording. */
    fun pause() {
        if (activeId == null || paused) return
        paused = true
        if (pending == null) lastAccepted?.let { pending = Pending(GapReason.PAUSED, it.time) }
    }

    /** Continues after [pause]; the next kept fix starts a new segment after the gap. */
    fun resume() {
        if (activeId == null || !paused) return
        paused = false
        newSegment = true
        stillSince = null
    }

    /**
     * T158: the stream was interrupted for [reason] (the app restarted the recording itself, T159): the next kept
     * fix closes a gap from the last kept one. No effect when not recording, before the first fix, or when a gap is
     * already pending.
     */
    fun interrupt(reason: GapReason) {
        if (activeId == null || pending != null) return
        lastAccepted?.let { pending = Pending(reason, it.time) }
    }

    /**
     * Stops recording; returns the finished track's summary, or null when nothing was recording. A pending
     * "stopped by the system" or "restarted" gap that no fix closed is written up to now: the track ended in it.
     */
    fun stop(): TrackSummary? {
        val id = activeId ?: return null
        val now = clock()
        pending?.let { h ->
            val start = h.start
            if ((h.reason == GapReason.STOPPED_BY_SYSTEM || h.reason == GapReason.RESTARTED) && start != null && now > start) {
                store.addGap(id, TrackGap(start, now, h.reason), now)
                gapsWritten++
            }
        }
        store.setRecording(id, false, now)
        activeId = null
        paused = false
        autoPaused = false
        pending = null
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
     * Tracks still marked as recording that this recorder is not recording: the process died while recording.
     * T158: the newest of them stays open and becomes this recorder's active track (when it has none) with a
     * pending [GapReason.STOPPED_BY_SYSTEM] gap from its last point, so the next kept fix continues it in a new
     * segment after the gap (T159 restarts the location stream; [stop] instead ends it in the gap). Any older ones
     * are closed as they are. Returns all their ids, oldest first.
     */
    fun recoverInterrupted(): List<String> {
        val stale = store.list().filter { it.recording && it.id != activeId }
        val keep = if (activeId == null) stale.lastOrNull() else null
        val now = clock()
        for (s in stale) if (s.id != keep?.id) store.setRecording(s.id, false, now)
        if (keep != null) adopt(keep)
        return stale.map { it.id }
    }

    private fun adopt(s: TrackSummary) {
        val t = store.track(s.id) ?: return
        reset()
        activeId = s.id
        t.profile?.let { profile = it }
        t.segments.forEach { live += ArrayList(it.points) }
        val last = t.points.lastOrNull()
        val lastTime = last?.time ?: s.updatedAt
        lastAccepted = last?.let { LocationFix(it.lat, it.lon, it.ele, lastTime, it.accuracyM) }
        lastFix = lastAccepted
        pending = Pending(GapReason.STOPPED_BY_SYSTEM, lastTime)
    }

    companion object {
        /** Teleports in a row after which the last kept fix is taken to be the stray one (see the class comment). */
        const val REANCHOR_AFTER = 5
    }
}

