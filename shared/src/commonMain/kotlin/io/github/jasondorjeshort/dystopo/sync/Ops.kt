package io.github.jasondorjeshort.dystopo.sync

import io.github.jasondorjeshort.dystopo.model.Route
import io.github.jasondorjeshort.dystopo.model.TrackPoint
import io.github.jasondorjeshort.dystopo.model.Waypoint

/** The kinds of user data in the operation log. Codes are part of the wire format; never reuse one. */
enum class EntityKind(val code: Int) {
    TRACK(1), WAYPOINT(2), ROUTE(3), SETTING(4);

    /** The fields a [SetField] may change; others are ignored when materialising (newer peers may know more). */
    val fields: Set<String>
        get() = when (this) {
            TRACK -> setOf("name", "description", "comment", "source", "type", "extensions")
            WAYPOINT -> setOf("name", "description", "comment", "symbol", "type", "extensions")
            ROUTE -> setOf("name", "description", "comment", "type", "extensions")
            SETTING -> setOf("value")
        }

    companion object {
        fun of(code: Int): EntityKind = entries.firstOrNull { it.code == code } ?: throw SyncFormatException("unknown entity kind $code")
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

class SyncFormatException(message: String) : IllegalArgumentException(message)
