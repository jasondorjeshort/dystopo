package io.github.jasondorjeshort.dystopo.store

import io.github.jasondorjeshort.dystopo.gpx.Gpx
import io.github.jasondorjeshort.dystopo.gpx.GpxDocument
import io.github.jasondorjeshort.dystopo.gpx.GpxFormat
import io.github.jasondorjeshort.dystopo.gpx.GpxMetadata
import io.github.jasondorjeshort.dystopo.model.Track
import io.github.jasondorjeshort.dystopo.model.TrackPoint
import kotlin.math.roundToLong

/** Stored tracks out as GPX (T24's writer), and GPX in as a timed fix sequence for replays. */
object TrackExport {
    /** The track and its waypoints as a GPX document (metadata name = track name, time = creation time); null if unknown. */
    fun document(store: TrackStore, trackId: String): GpxDocument? {
        val summary = store.summary(trackId) ?: return null
        val track = store.track(trackId) ?: return null
        return GpxDocument(
            waypoints = store.waypoints(trackId),
            tracks = listOf(track),
            metadata = GpxMetadata(name = summary.name, time = summary.createdAt),
        )
    }

    fun gpx(store: TrackStore, trackId: String): String? = document(store, trackId)?.let(Gpx::write)

    /** A file name for the export: the track name reduced to safe characters, its start date and the id's first 8 characters. */
    fun fileName(summary: TrackSummary): String {
        val safe = summary.name.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '-' }
            .joinToString("").replace(Regex("-+"), "-").trim('-').take(40).ifEmpty { "track" }
        val date = GpxFormat.formatTime(summary.createdAt).take(10)
        return "$safe-$date-${summary.id.take(8)}.gpx"
    }

    /**
     * [timed] moved later so its first fix comes [stepMs] after [after] (the time of the last fix the
     * recorder kept), keeping every interval between fixes; unchanged when it already starts later or
     * [after] is null. A replay keeps the file's old times, and the recorder drops a fix not newer than
     * the last one it kept, so a replay into a recording that already holds a fix stamped "now" (started
     * the same second, or a GPS fix just before) would record nothing after that point (the single-point
     * replay of T31). Shifting the replay onto the recording's timeline keeps every point.
     */
    fun continueAfter(timed: List<Timed>, after: Long?, stepMs: Long = 1000): List<Timed> {
        val first = timed.firstOrNull()?.fix?.time ?: return timed
        if (after == null || first > after) return timed
        val shift = after + stepMs - first
        return timed.map { it.copy(fix = it.fix.copy(time = it.fix.time + shift)) }
    }

    /** One fix of a replay, due [atMs] after the replay starts. */
    data class Timed(val atMs: Long, val fix: LocationFix)

    /**
     * The points of [tracks] as fixes for a replay at [rate] times real time: a point is due
     * (its time - first time) / rate after the start. Points without a time follow the previous one
     * after [untimedStepMs] / rate. Fixes keep the file's own times (so the recorded track's
     * durations equal the original's), with [accuracyM] (or the point's own, T158) and source "replay". A new segment in the
     * file is not marked in the fixes; the recorder sees one continuous stream.
     */
    fun replay(tracks: List<Track>, rate: Double, accuracyM: Double = 5.0, untimedStepMs: Long = 1000): List<Timed> {
        require(rate > 0 && rate.isFinite()) { "rate must be positive" }
        val points: List<TrackPoint> = tracks.flatMap { it.points }
        val first = points.firstOrNull { it.time != null }?.time
        val out = ArrayList<Timed>(points.size)
        var lastAt = 0L
        var lastTime = first ?: 0L
        for (p in points) {
            val time = p.time ?: (lastTime + untimedStepMs)
            val at = if (p.time != null && first != null) ((p.time - first) / rate).roundToLong() else lastAt + (untimedStepMs / rate).roundToLong()
            lastAt = maxOf(lastAt, at)
            lastTime = time
            // T158: a point's own accuracy, speed and course when it has them (an exported recording).
            out += Timed(lastAt, LocationFix(p.lat, p.lon, p.ele, time, p.accuracyM ?: accuracyM, "replay", p.speedMps, p.courseDeg, p.verticalAccuracyM))
        }
        return out
    }
}
