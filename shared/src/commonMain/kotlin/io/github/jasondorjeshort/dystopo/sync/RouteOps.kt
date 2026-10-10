package io.github.jasondorjeshort.dystopo.sync

import io.github.jasondorjeshort.dystopo.model.RoutePlan
import io.github.jasondorjeshort.dystopo.model.RouteWaypoints
import io.github.jasondorjeshort.dystopo.model.Waypoint

/**
 * T65: route operations beyond T60's two fields.
 *
 * **Per-waypoint writes.** T60 kept a route's waypoints as one `waypoints` value, so two devices adding a
 * waypoint at the same time kept only the later list. Now every waypoint edit is its own field write,
 * `wpt:<waypoint id>` ([waypointField]), holding that one waypoint ([encodeWaypoint]) or null when it was
 * removed. [Materializer.routePlan] replays a route's live operations in HLC order: a put empties the
 * list, a `waypoints` write replaces it (route plans, imports), a `wpt:` write sets or removes one entry
 * in place (a new one goes at the end, so the order follows each waypoint's first write). Concurrent edits of different waypoints all survive; two edits
 * of the same waypoint are last writer wins like any field. Builds before T65 ignore `wpt:` fields (they
 * see the last full list).
 *
 * **Compaction of superseded writes** ([superseded]): a field write older than a newer write of the same
 * field on the same entity can never decide anything again (last writer wins), so compaction drops it;
 * waypoint writes are kept, since a waypoint's first write sets its place in the list.
 * That keeps one `path` value per route in the log instead of one per edit (about 2.5 kB each).
 *
 * **Size cap** ([checkPath]): a `path` value over [MAX_PATH_CHARS] is refused with [RouteTooLargeException].
 */
object RouteOps {
    const val WAYPOINT_FIELD_PREFIX = "wpt:"

    /** The longest `path` value a device writes: 256 KiB of base64url, about 40,000 route points. */
    const val MAX_PATH_CHARS = 256 * 1024

    fun waypointField(id: String): String = WAYPOINT_FIELD_PREFIX + id

    /** The waypoint id of a `wpt:` field, or null for any other field. */
    fun waypointIdOf(field: String): String? = if (field.startsWith(WAYPOINT_FIELD_PREFIX) && field.length > WAYPOINT_FIELD_PREFIX.length) field.substring(WAYPOINT_FIELD_PREFIX.length) else null

    /** One waypoint as a `wpt:` value (the [RouteWaypoints] encoding of a one-item list, exact doubles). */
    fun encodeWaypoint(w: Waypoint): String = RouteWaypoints.encode(listOf(w))

    /** A `wpt:` value back to its waypoint; null for null (removed) or a value that does not decode (a newer format). */
    fun decodeWaypoint(text: String?): Waypoint? = RouteWaypoints.decodeOrNull(text)?.singleOrNull()

    /**
     * The `wpt:` writes that turn [old] into [new], in [new]'s order then removals: one per added or changed
     * waypoint (its encoding) and one per removed waypoint (null). Unchanged waypoints are not written.
     */
    fun waypointWrites(old: List<Waypoint>, new: List<Waypoint>): List<Pair<String, String?>> {
        val before = old.associateBy { it.id }
        val after = new.associateBy { it.id }
        require(after.size == new.size) { "two waypoints with one id" }
        val out = ArrayList<Pair<String, String?>>()
        for (w in new) if (before[w.id] != w) out += waypointField(w.id) to encodeWaypoint(w)
        for (w in old) if (w.id !in after) out += waypointField(w.id) to null
        return out
    }

    /**
     * A route's waypoints from its live operations ([Materializer.live], oldest first): see the class
     * comment. Deterministic, so every replica lists them in the same order.
     */
    fun waypoints(live: List<Op>): List<Waypoint> {
        var list = LinkedHashMap<String, Waypoint>()
        for (op in live) {
            val b = op.body
            when {
                b is PutRoute -> list = LinkedHashMap()
                b is SetField && b.field == "waypoints" ->
                    list = LinkedHashMap<String, Waypoint>().also { m -> RouteWaypoints.decodeOrNull(b.value)?.forEach { m[it.id] = it } }
                b is SetField -> {
                    val id = waypointIdOf(b.field) ?: continue
                    if (b.field !in EntityKind.ROUTE.fields) continue
                    val w = decodeWaypoint(b.value)
                    if (w == null) list.remove(id) else list[id] = w.copy(id = id)
                }
            }
        }
        return list.values.toList()
    }

    /** Thrown for a route whose `path` value would exceed [MAX_PATH_CHARS]. */
    class RouteTooLargeException(message: String) : IllegalArgumentException(message)

    /** Refuses a `path` value over [MAX_PATH_CHARS] with a message a person can act on. */
    fun checkPath(routeId: String, encoded: String, points: Int) {
        if (encoded.length > MAX_PATH_CHARS) {
            throw RouteTooLargeException(
                "route $routeId is too large to store: its path is ${encoded.length / 1024} KiB ($points points), over the " +
                    "${MAX_PATH_CHARS / 1024} KiB limit; split it into shorter routes",
            )
        }
    }

    /** [checkPath] for a whole plan (the stores' `putRoutePlan`). */
    fun checkPlan(plan: RoutePlan) = plan.path.quantized().let { checkPath(plan.id, it.encode(), it.points.size) }

    /**
     * Compaction of one replica's route operations: the [superseded] field writes of every route in
     * [byRoute] (each route's operations), to delete from the log.
     */
    fun compactable(byRoute: Collection<List<Op>>): List<Hlc> = byRoute.flatMap { superseded(it) }

    /**
     * The HLCs of field writes ([SetField]) in [ops] (one entity's operations, any order) that a newer write
     * of the same field supersedes: compaction may drop them. Field writes under a tombstone are already
     * dropped by the tombstone rule.
     */
    fun superseded(ops: Collection<Op>): List<Hlc> {
        val newest = HashMap<String, Hlc>()
        for (op in ops) {
            val b = op.body as? SetField ?: continue
            // A waypoint's writes all stay: its first write decides where it sits in the list (RouteOps.waypoints),
            // so dropping it would reorder the list on this replica only (found by the simulator, seed 59).
            if (waypointIdOf(b.field) != null) continue
            val h = newest[b.field]
            if (h == null || op.hlc > h) newest[b.field] = op.hlc
        }
        return ops.mapNotNull { op -> (op.body as? SetField)?.let { b -> newest[b.field]?.let { n -> op.hlc.takeIf { it < n } } } }
    }
}
