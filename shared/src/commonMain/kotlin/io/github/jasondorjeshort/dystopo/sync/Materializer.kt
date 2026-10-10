package io.github.jasondorjeshort.dystopo.sync

import io.github.jasondorjeshort.dystopo.model.Route
import io.github.jasondorjeshort.dystopo.model.RoutePath
import io.github.jasondorjeshort.dystopo.model.RoutePlan
import io.github.jasondorjeshort.dystopo.model.Track
import io.github.jasondorjeshort.dystopo.model.TrackPoint
import io.github.jasondorjeshort.dystopo.model.TrackSegment
import io.github.jasondorjeshort.dystopo.model.Waypoint

/** One segment of a materialised track with its number (segments of a track are numbered, not necessarily 0..n). */
data class SegmentView(val number: Int, val points: List<TrackPoint>)

/** A track as the log says it is now. */
data class TrackView(
    val id: String,
    val createdAt: Long,
    val name: String,
    val description: String?,
    val comment: String?,
    val source: String?,
    val type: String?,
    val extensions: String?,
    val segments: List<SegmentView>,
    /** The newest live operation (for `updatedAt` on a replica that received it). */
    val lastHlc: Hlc,
) {
    val pointCount: Int get() = segments.sumOf { it.points.size }

    fun toTrack(): Track = Track(id, name, segments.filter { it.points.isNotEmpty() }.map { TrackSegment(it.points) }, description, comment, source, type, extensions)
}

/** A waypoint as the log says it is now; [order] sorts the waypoints of a track (the HLC of its first live put). */
data class WaypointView(val trackId: String?, val waypoint: Waypoint, val order: Hlc, val lastHlc: Hlc)

/**
 * The merge (T44): the state of one entity as a pure function of the set of operations on it, so it
 * does not depend on the order operations arrived in, on duplicates, or on which replica computes it.
 * Rules (docs/PLANNING.md › Sync):
 *
 * - **Tombstones.** The newest [Delete] of an entity hides every operation older than it (by HLC).
 *   An entity exists when a `Put*` newer than its newest delete exists: a delete wins over every
 *   change it could have seen, and a later re-import (a new put) brings the entity back with only
 *   what came after the delete.
 * - **Fields** are last-writer-wins by HLC: a put sets every field, a [SetField] one; the newest wins.
 *   Two concurrent renames resolve to the one with the greater HLC on every replica.
 * - **Track points** are the union of all live [AppendPoints], ordered by (segment number, HLC of the
 *   append, position in it): a device's own appends are in its HLC order, so a recording keeps its
 *   order. Two appends with the same segment and the same points ([OpCodec.digest]), such as the same
 *   GPX imported on two devices, count once (the older one is kept).
 */
object Materializer {
    fun tombstone(ops: Collection<Op>): Hlc? = ops.filter { it.body is Delete }.maxOfOrNull { it.hlc }

    /** Operations newer than the tombstone, deletes excluded, oldest first. */
    fun live(ops: Collection<Op>): List<Op> {
        val t = tombstone(ops)
        return ops.filter { it.body !is Delete && (t == null || it.hlc > t) }.sortedBy { it.hlc }
    }

    /** True if an operation at [hlc] (not a delete) on an entity whose tombstone is [tombstone] can never matter again. */
    fun isDead(hlc: Hlc, tombstone: Hlc?): Boolean = tombstone != null && hlc < tombstone

    private fun lww(kind: EntityKind, live: List<Op>, init: (OpBody) -> Map<String, String?>?): MutableMap<String, String?>? {
        var fields: MutableMap<String, String?>? = null
        for (op in live) {
            val b = op.body
            val put = init(b)
            if (put != null) {
                fields = (fields ?: HashMap()).also { it.putAll(put) }
            } else if (b is SetField && b.field in kind.fields) {
                (fields ?: HashMap<String, String?>().also { fields = it })[b.field] = b.value
            }
        }
        return fields
    }

    fun track(id: String, ops: Collection<Op>): TrackView? {
        val live = live(ops)
        val lastPut = live.lastOrNull { it.body is PutTrack }?.body as PutTrack? ?: return null
        val f = lww(EntityKind.TRACK, live) { b ->
            (b as? PutTrack)?.let { mapOf("name" to it.name, "description" to it.description, "comment" to it.comment, "source" to it.source, "type" to it.trackType, "extensions" to it.extensions) }
        }!!
        return TrackView(
            id, lastPut.createdAt, f["name"] ?: "", f["description"], f["comment"], f["source"], f["type"], f["extensions"],
            segments(live), live.last().hlc,
        )
    }

    fun segments(live: List<Op>): List<SegmentView> {
        val seen = HashSet<Pair<Int, String>>()
        val appends = live.mapNotNull { op -> (op.body as? AppendPoints)?.takeIf { seen.add(it.segment to OpCodec.digest(it)) } }
        // live is in HLC order; a stable sort by segment keeps that order within a segment.
        return appends.sortedBy { it.segment }.groupBy { it.segment }.map { (n, list) -> SegmentView(n, list.flatMap { it.points }) }
            .filter { it.points.isNotEmpty() }
    }

    fun waypoint(id: String, ops: Collection<Op>): WaypointView? {
        val live = live(ops)
        val puts = live.filter { it.body is PutWaypoint }
        if (puts.isEmpty()) return null
        val last = puts.last().body as PutWaypoint
        val f = lww(EntityKind.WAYPOINT, live) { b ->
            (b as? PutWaypoint)?.waypoint?.let { mapOf("name" to it.name, "description" to it.description, "comment" to it.comment, "symbol" to it.symbol, "type" to it.type, "extensions" to it.extensions) }
        }!!
        val w = last.waypoint.copy(
            id = id, name = f["name"] ?: "", description = f["description"], comment = f["comment"], symbol = f["symbol"], type = f["type"], extensions = f["extensions"],
        )
        return WaypointView(last.trackId, w, puts.first().hlc, live.last().hlc)
    }

    /** The route as GPX sees it: [routePlan] with its path's points as route points. */
    fun route(id: String, ops: Collection<Op>): Route? = routePlan(id, ops)?.toRoute()

    /**
     * The fields a route put writes (T60): its GPX fields, and `path` and `waypoints` reset to null (a
     * put replaces every field; the put's own points stand for the path until a `path` write follows).
     */
    fun routePutFields(put: PutRoute): Map<String, String?> = put.route.let {
        mapOf(
            "name" to it.name, "description" to it.description, "comment" to it.comment, "type" to it.type, "extensions" to it.extensions,
            "path" to null, "waypoints" to null,
        )
    }

    /**
     * A planned route (T60): GPX fields and `path` last writer wins like any field (T65: waypoints per
     * waypoint, [RouteOps.waypoints]). The
     * path is the newest live `path` write when it decodes, else the newest put's points as one fixed
     * leg ([RoutePath.fixed]); a value that does not decode (a newer format) counts as absent, the same
     * way on every replica.
     */
    fun routePlan(id: String, ops: Collection<Op>): RoutePlan? {
        val live = live(ops)
        val last = live.lastOrNull { it.body is PutRoute }?.body as PutRoute? ?: return null
        val f = lww(EntityKind.ROUTE, live) { b -> (b as? PutRoute)?.let(::routePutFields) }!!
        val path = RoutePath.decodeOrNull(f["path"]) ?: RoutePath.fixed(last.route.points, modeOf(last.route.type))
        // T65: the `waypoints` list plus the per-waypoint `wpt:` writes, in HLC order (RouteOps).
        val waypoints = RouteOps.waypoints(live)
        return RoutePlan(id, f["name"] ?: "", path, waypoints, f["description"], f["comment"], f["type"], f["extensions"])
    }

    private fun modeOf(type: String?): String = if (type?.lowercase() == "bike") "bike" else "hike"

    fun setting(ops: Collection<Op>): String? {
        val live = live(ops)
        if (live.none { it.body is PutSetting }) return null
        return lww(EntityKind.SETTING, live) { b -> (b as? PutSetting)?.let { mapOf("value" to it.value) } }!!["value"]
    }
}

/**
 * The synced state of a replica in a canonical form: what two replicas must agree on after they
 * have exchanged everything. Local-only state (which track is recording, `updatedAt`, caches) is
 * not part of it.
 */
data class SyncSnapshot(
    val tracks: List<TrackView>,
    val waypoints: List<WaypointView>,
    val routes: List<RoutePlan>,
    val settings: Map<String, String>,
) {
    companion object {
        /** A snapshot with the bookkeeping HLCs ([TrackView.lastHlc], [WaypointView.lastHlc]) cleared, so equal states compare equal. */
        fun of(tracks: List<TrackView>, waypoints: List<WaypointView>, routes: List<RoutePlan>, settings: Map<String, String>): SyncSnapshot {
            val none = Hlc.zero("")
            return SyncSnapshot(
                tracks.map { it.copy(lastHlc = none) }.sortedBy { it.id },
                waypoints.map { it.copy(lastHlc = none) }.sortedBy { it.waypoint.id },
                routes.sortedBy { it.id },
                settings.toList().sortedBy { it.first }.toMap(),
            )
        }
    }

    /** A canonical text per entity (stable across replicas), keyed `<kind>:<id>`. */
    fun entities(): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        tracks.sortedBy { it.id }.forEach { t -> m["track:${t.id}"] = t.copy(lastHlc = Hlc.zero("")).toString() }
        waypoints.sortedBy { it.waypoint.id }.forEach { w -> m["waypoint:${w.waypoint.id}"] = w.copy(lastHlc = Hlc.zero("")).toString() }
        routes.sortedBy { it.id }.forEach { r -> m["route:${r.id}"] = r.toString() }
        settings.toList().sortedBy { it.first }.forEach { (k, v) -> m["setting:$k"] = v }
        return m
    }

    /** 64-bit FNV-1a over [entities], as hex. */
    fun digest(): String {
        var h = -0x340d631b7bdddcdbL
        for ((k, v) in entities()) for (b in "$k=$v\n".encodeToByteArray()) {
            h = h xor (b.toLong() and 0xff)
            h *= 0x100000001b3L
        }
        return h.toULong().toString(16).padStart(16, '0')
    }

    /** The first entity (in key order) where this and [other] differ, or null. */
    fun firstDifference(other: SyncSnapshot): Triple<String, String?, String?>? {
        val a = entities()
        val b = other.entities()
        val key = (a.keys + b.keys).sorted().firstOrNull { a[it] != b[it] } ?: return null
        return Triple(key, a[key], b[key])
    }
}
