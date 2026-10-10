package io.github.jasondorjeshort.dystopo.store

import io.github.jasondorjeshort.dystopo.gpx.Gpx
import io.github.jasondorjeshort.dystopo.model.UuidGenerator
import io.github.jasondorjeshort.dystopo.model.Waypoint

/**
 * GPX into a [TrackStore] (T40). Rules:
 * - Every `trk` becomes a stored track with the id from its `dystopo:id` extension (our own
 *   exports) or a fresh UUID. A track whose id is already stored is skipped, not duplicated, so
 *   importing an export of this store changes nothing.
 * - Name: the track's, else the file's metadata name, else [ImportOptions.fallbackName].
 * - Created at: the metadata time for a one-track file, else the track's first point time, else now.
 * - Waypoints of a one-track file belong to that track (that is how [TrackExport] writes them);
 *   in any other file they are stored free-standing. A waypoint whose id is already stored is skipped.
 * - Routes are not stored yet (counted in [Result.routesIgnored]); empty segments are dropped.
 */
object TrackImport {
    data class Result(
        val imported: List<TrackSummary>,
        /** Track ids already in the store. */
        val skippedTracks: List<String>,
        val waypoints: Int,
        val skippedWaypoints: Int,
        val routesIgnored: Int,
        /** What the GPX reader skipped (see [Gpx.ParseResult.ignored]). */
        val ignored: Map<String, Int>,
    )

    /** Throws [io.github.jasondorjeshort.dystopo.gpx.GpxException] (an IllegalArgumentException) for a file that isn't GPX or holds nothing to import. */
    fun import(store: TrackStore, gpx: String, now: Long, fallbackName: String = "Imported track", ids: UuidGenerator = UuidGenerator()): Result {
        val parsed = Gpx.read(gpx, ids)
        val doc = parsed.document
        if (doc.tracks.isEmpty() && doc.waypoints.isEmpty()) {
            throw io.github.jasondorjeshort.dystopo.gpx.GpxException("no tracks or waypoints")
        }
        val imported = ArrayList<TrackSummary>()
        val skipped = ArrayList<String>()
        var waypoints = 0
        var skippedWaypoints = 0
        val seenWaypoints = HashSet<String>()
        fun fresh(list: List<Waypoint>): List<Waypoint> = list.filter { w ->
            (store.waypoint(w.id) == null && seenWaypoints.add(w.id)).also { if (!it) skippedWaypoints++ }
        }

        val single = doc.tracks.size == 1
        for (t in doc.tracks) {
            if (store.summary(t.id) != null) {
                skipped += t.id
                if (single) skippedWaypoints += doc.waypoints.size
                continue
            }
            val name = t.name.ifBlank { doc.metadata?.name?.takeIf { it.isNotBlank() } ?: fallbackName }
            val createdAt = (if (single) doc.metadata?.time else null) ?: t.points.firstNotNullOfOrNull { it.time } ?: now
            val wps = if (single) fresh(doc.waypoints) else emptyList()
            imported += store.insert(t.copy(name = name), wps, createdAt, now)
            waypoints += wps.size
        }
        if (!single) {
            for (w in fresh(doc.waypoints)) {
                store.addWaypoint(null, w, now)
                waypoints++
            }
        }
        return Result(imported, skipped, waypoints, skippedWaypoints, doc.routes.size, parsed.ignored)
    }
}
