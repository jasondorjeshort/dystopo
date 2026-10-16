package io.github.jasondorjeshort.dystopo.sync

import io.github.jasondorjeshort.dystopo.model.Route
import io.github.jasondorjeshort.dystopo.model.TrackPoint
import io.github.jasondorjeshort.dystopo.model.Waypoint

/** The kinds of user data in the operation log. Codes are part of the wire format; never reuse one. */
enum class EntityKind(val code: Int) {
    TRACK(1), WAYPOINT(2), ROUTE(3), SETTING(4);

    /**
     * The fields a [SetField] may change; others are ignored when materialising (newer peers may know more).
     * Iterating gives the named fields; `in` also accepts a family of fields named by a prefix (T65: a
     * route's `wpt:<waypoint id>`, one waypoint each, see [RouteOps]).
     */
    val fields: Set<String>
        get() = when (this) {
            TRACK -> TRACK_FIELDS
            WAYPOINT -> WAYPOINT_FIELDS
            ROUTE -> ROUTE_FIELDS
            SETTING -> SETTING_FIELDS
        }

    companion object {
        fun of(code: Int): EntityKind = entries.firstOrNull { it.code == code } ?: throw SyncFormatException("unknown entity kind $code")

        private val TRACK_FIELDS = FieldSet(setOf("name", "description", "comment", "source", "type", "extensions"))
        private val WAYPOINT_FIELDS = FieldSet(setOf("name", "description", "comment", "symbol", "type", "extensions"))
        // T60: `path` (RoutePath.encode) and `waypoints` (RouteWaypoints.encode) are the route's shape and waypoints;
        // T65: `wpt:<id>` is one waypoint (RouteOps.encodeWaypoint; null = removed), so concurrent waypoint edits merge;
        // T108: `directions` (RouteDirections.encode) are the turn directions for one path value (builds before T108 ignore it).
        private val ROUTE_FIELDS = FieldSet(setOf("name", "description", "comment", "type", "extensions", "path", "waypoints", "directions"), RouteOps.WAYPOINT_FIELD_PREFIX)
        private val SETTING_FIELDS = FieldSet(setOf("value"))
    }
}

/**
 * The fields of an [EntityKind] (T65): [names], plus, with [prefix], every `<prefix><non-empty suffix>` field
 * (a family such as a route's `wpt:<waypoint id>`). Iteration lists [names] only.
 */
class FieldSet(private val names: Set<String>, private val prefix: String? = null) : AbstractSet<String>() {
    override val size: Int get() = names.size
    override fun iterator(): Iterator<String> = names.iterator()
    override fun contains(element: String): Boolean =
        element in names || (prefix != null && element.length > prefix.length && element.startsWith(prefix) && element.length <= MAX_FIELD_LENGTH)

    companion object {
        /** Longest field name accepted in a family (a prefix plus a UUID is about 40). */
        const val MAX_FIELD_LENGTH = 100
    }
}

/**
 * One change to the user data (T44). Operations are small and typed; every one names the entity it
 * changes ([kind], [id]). The log only ever grows by appending operations; the stores' tables are a
 * view materialised from it ([Materializer]).
 *
 * - `Put*` creates an entity, or replaces all its fields (a re-import, a revived entity after a delete).
 * - [SetField] changes one field (rename): last writer wins per field by HLC.
 * - [AppendPoints] adds recorded points to a track segment: points from all appends are kept (union).
 * - [Delete] removes an entity and everything older than it (a tombstone).
 */
sealed class OpBody {
    abstract val kind: EntityKind
    abstract val id: String

    /** The track a waypoint or an append belongs to, else null. */
    open val parent: String? get() = null

    /** Codes are part of the wire format. */
    abstract val type: Int
}

data class PutTrack(
    override val id: String,
    val createdAt: Long,
    val name: String,
    val description: String? = null,
    val comment: String? = null,
    val source: String? = null,
    val trackType: String? = null,
    val extensions: String? = null,
) : OpBody() {
    override val kind get() = EntityKind.TRACK
    override val type get() = TYPE_PUT
}

data class PutWaypoint(val trackId: String?, val waypoint: Waypoint) : OpBody() {
    override val kind get() = EntityKind.WAYPOINT
    override val id get() = waypoint.id
    override val parent get() = trackId
    override val type get() = TYPE_PUT
}

data class PutRoute(val route: Route) : OpBody() {
    override val kind get() = EntityKind.ROUTE
    override val id get() = route.id
    override val type get() = TYPE_PUT
}

/** A setting: [id] is its key. */
data class PutSetting(override val id: String, val value: String) : OpBody() {
    override val kind get() = EntityKind.SETTING
    override val type get() = TYPE_PUT
}

data class SetField(override val kind: EntityKind, override val id: String, val field: String, val value: String?) : OpBody() {
    override val type get() = TYPE_SET
}

/** Points appended to segment [segment] of track [id], in order. */
data class AppendPoints(override val id: String, val segment: Int, val points: List<TrackPoint>) : OpBody() {
    override val kind get() = EntityKind.TRACK
    override val parent get() = id
    override val type get() = TYPE_APPEND

    companion object {
        /**
         * Run merging (T51): a recorded fix extends this device's previous append (same track and
         * segment) instead of adding an operation, while that append is still unshipped and the newest
         * operation on the track; a run holds at most this many points.
         */
        const val MAX_RUN = 64
    }
}

data class Delete(override val kind: EntityKind, override val id: String) : OpBody() {
    override val type get() = TYPE_DELETE
}

const val TYPE_PUT = 1
const val TYPE_SET = 2
const val TYPE_APPEND = 3
const val TYPE_DELETE = 4

/** An operation: its unique, totally ordered timestamp and what it does. */
data class Op(val hlc: Hlc, val body: OpBody) {
    val device: String get() = hlc.device
}

/**
 * Malformed sync input (a bundle, an operation, an HLC, a vector). T51: every parser in this package
 * throws only this type for bad bytes, whatever is wrong with them, so callers need one catch.
 */
class SyncFormatException(message: String) : IllegalArgumentException(message) {
    companion object {
        /** Runs a parser and turns any runtime failure it did not anticipate into a [SyncFormatException]. */
        inline fun <T> guard(block: () -> T): T = try {
            block()
        } catch (e: SyncFormatException) {
            throw e
        } catch (e: RuntimeException) {
            throw SyncFormatException("corrupt sync data: ${e::class.simpleName}: ${e.message}")
        }
    }
}

/**
 * A field written on two devices without either seeing the other's write (T51): found when merging a
 * bundle, kept by the app for the "edited on two devices" note. Last writer wins as always; this only
 * records that a value was overwritten. [kept] is the winning value (greater HLC) and [lost] the other.
 */
data class Conflict(
    val kind: EntityKind,
    val id: String,
    val field: String,
    val kept: String?,
    val keptDevice: String,
    val lost: String?,
    val lostDevice: String,
    /** The HLC of the winning write. */
    val at: Hlc,
    /** Neither writer had seen the other's value (see [Conflicts]); false: a later edit on another device replaced this device's value. */
    val concurrent: Boolean = true,
) {
    fun json(): Map<String, Any?> = linkedMapOf(
        "kind" to kind.name.lowercase(), "id" to id, "field" to field, "kept" to kept, "keptDevice" to keptDevice,
        "lost" to lost, "lostDevice" to lostDevice, "at" to at.text, "concurrent" to concurrent,
    )
}
