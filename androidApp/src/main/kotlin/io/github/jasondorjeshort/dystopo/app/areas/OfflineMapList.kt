package io.github.jasondorjeshort.dystopo.app.areas

import io.github.jasondorjeshort.dystopo.app.packs.DownloadStatus
import io.github.jasondorjeshort.dystopo.areas.AreaShape
import io.github.jasondorjeshort.dystopo.areas.NeedsNewerApp
import io.github.jasondorjeshort.dystopo.areas.PolygonRings
import io.github.jasondorjeshort.dystopo.areas.TripCorridor
import io.github.jasondorjeshort.dystopo.packs.CatalogStatus
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import java.util.Locale
import kotlin.math.floor

/**
 * T234 (ADR 0056, proposal 0056's B1a and its L1 section): the one list of offline maps. Pure: [rows] joins the packs on the
 * phone with the area records, the downloads under way and the removals, so a map shows once however it arrived (imported,
 * from the server's catalog, from a link, from a nearby phone, a region download, a drawn area or a trip's corridor). It
 * reads nothing itself: the Areas sheet's poll and `GET /areas/rows` gather the [Inputs] ([offlineMapInputs]).
 *
 * **The join**, one row per pack name and one per record without a pack:
 * - `pack:P` for every name P that a pack on the phone, a record, a download, the region job or a removal names; named by
 *   the newest record naming P, else P. Its state comes from the removal, then the download, then the pack itself.
 * - `area:<id>` for a record with no pack (a drawn area, a disc, a corridor, a trip's), and for every unreadable record
 *   ([Areas.Record.opaque]), whatever it names.
 *
 * **One state word per row**, by the first of the proposal's table that holds ([PACK_WORDS] and [AREA_WORDS] in that order):
 * Removing, Needs a newer app, Downloading / Updating / Building / Fetching the trail network, Paused, Waiting, Download
 * stopped, Not on this phone, Part saved, Incomplete, Update available, Saved, May be cleared.
 *
 * **Remove** ([removePlan]): an area row's record goes as today ([TripCorridors.userRemove]); a pack row's every record naming
 * P, then P and its download; an update or a trail-network fetch is only stopped, and P stays until a second Remove.
 *
 * The inputs are one class with defaults, so a third store (proposal 0055's dwell box) is one more field, not a new signature.
 */
object OfflineMapList {
    /** A pack on the phone ([io.github.jasondorjeshort.dystopo.app.packs.PackManager.list]). [bounds] west, south, east, north. */
    data class Pack(
        val name: String, val bytes: Long, val usable: Boolean, val hasDem: Boolean, val hasVector: Boolean,
        val bounds: List<Double>? = null, val minZoom: Int? = null, val maxZoom: Int? = null, val attribution: String = "",
        val modifiedAt: Long = 0L, val errors: List<String> = emptyList(),
    )

    /** A pack download ([io.github.jasondorjeshort.dystopo.app.packs.PackDownloader.Job.json]), with T222's word and controls. */
    data class Job(
        val name: String, val state: String, val word: String, val percent: Double? = null, val update: Boolean = false,
        val graphOnly: Boolean = false, val sentence: String? = null, val error: String? = null, val actions: List<String> = emptyList(),
        val bytesDone: Long = 0L, val bytesTotal: Long? = null, val createdAt: Long = 0L,
    ) {
        /** A download this list shows: waiting, running, paused or stopped; a trail-network fetch only while it runs. */
        val shown: Boolean get() = if (graphOnly) state == "queued" || state == "running" else state in LIVE_JOB_STATES

        companion object {
            /** From a job's JSON (`GET /packs/download`'s `jobs`). */
            fun of(m: Map<String, Any?>): Job? {
                val name = m["name"] as? String ?: return null
                return Job(
                    name = name, state = m["state"] as? String ?: "", word = m["word"] as? String ?: "", percent = (m["percent"] as? Number)?.toDouble(),
                    update = m["update"] == true, graphOnly = m["graphOnly"] == true, sentence = m["sentence"] as? String,
                    error = (m["errorDetail"] ?: m["error"]) as? String, actions = (m["actions"] as? List<*>).orEmpty().map { it.toString() },
                    bytesDone = (m["bytesDone"] as? Number)?.toLong() ?: 0L, bytesTotal = (m["bytesTotal"] as? Number)?.toLong(),
                    createdAt = (m["createdAt"] as? Number)?.toLong() ?: 0L,
                )
            }
        }
    }

    /** The region job ([io.github.jasondorjeshort.dystopo.app.online.RegionDownload.json]): a row only while it has no download of its own. */
    data class RegionJob(
        val name: String, val state: String, val percent: Double? = null, val sentence: String? = null, val error: String? = null,
        val actions: List<String> = emptyList(), val bounds: List<Double>? = null, val startedAt: Long = 0L,
    ) {
        val shown: Boolean get() = state in REGION_STATES

        companion object {
            fun of(m: Map<String, Any?>, now: Long): RegionJob? {
                val name = m["name"] as? String ?: return null
                return RegionJob(
                    name = name, state = m["state"] as? String ?: "idle", percent = (m["percent"] as? Number)?.toDouble(), sentence = m["sentence"] as? String,
                    error = (m["errorDetail"] ?: m["error"]) as? String, actions = (m["actions"] as? List<*>).orEmpty().map { it.toString() },
                    bounds = (m["bbox"] as? List<*>)?.mapNotNull { (it as? Number)?.toDouble() }?.takeIf { it.size == 4 },
                    startedAt = (m["elapsedMs"] as? Number)?.toLong()?.let { now - it } ?: now,
                )
            }
        }
    }

    /** The tile area download now ([AreaDownload.job]): [id] is its record's. [isNew] false when it downloads a record again. */
    data class AreaJob(val id: String, val running: Boolean, val percent: Double? = null, val isNew: Boolean = true)

    /**
     * What [rows] joins. [coverage] is the share on the phone per record id (records with no pack only); [queued] the trips whose
     * corridor waits for another download ([TripCorridors.queued]); [older] the records [Areas.older] names, to whether they came
     * from another source ([Areas.fromOtherSource]); [catalog] the server catalog's status per pack name, from the catalog held;
     * [removing] the rows whose Remove runs.
     */
    data class Inputs(
        val packs: List<Pack> = emptyList(),
        val records: List<Areas.Record> = emptyList(),
        val removals: List<Areas.Removal> = emptyList(),
        val coverage: Map<String, Double?> = emptyMap(),
        val jobs: List<Job> = emptyList(),
        val regionJob: RegionJob? = null,
        val areaJob: AreaJob? = null,
        val queued: Set<String> = emptySet(),
        val catalog: Map<String, CatalogStatus> = emptyMap(),
        val older: Map<String, Boolean> = emptyMap(),
        val active: String? = null,
        val units: ContourUnits = ContourUnits.METERS,
        val now: Long = 0L,
        /** Row ids whose Remove runs now (the sheet's; a pack's removal has no [Areas.Removal]): they read Removing. */
        val removing: Set<String> = emptySet(),
    )

    /** How a row's state word reads: [BUSY] rows go first; [BAD] and [GOOD] colour the word. */
    enum class Tone { BUSY, BAD, GOOD, PLAIN }

    /**
     * One row: [id] `pack:<name>` or `area:<record id>`; [bytes] on the phone (or the download's size); [state] the one word;
     * [detail] what a tap opens; [actions] the labels the opened row offers, in order; [bounds] west, south, east, north.
     */
    data class Row(
        val id: String, val name: String, val bytes: Long, val state: String, val tone: Tone, val detail: List<String>, val actions: List<String>,
        val bounds: List<Double>?, val pack: String?, val recordId: String?, val sortAt: Long,
    ) {
        /** The key a tap toggles in [Areas.detailOpen]: the record id for an area row (as T221's `POST /areas/ui?detail=`), else [id]. */
        val detailKey: String get() = if (id.startsWith(AREA)) recordId ?: id else id

        fun json(open: Boolean = false): Map<String, Any?> = linkedMapOf(
            "id" to id, "name" to name, "bytes" to bytes, "state" to state, "tone" to tone.name.lowercase(), "detail" to detail, "actions" to actions,
            "pack" to pack, "recordId" to recordId, "bounds" to bounds, "detailOpen" to open,
        )
    }

    const val PACK = "pack:"
    const val AREA = "area:"

    // The state words (ADR 0031: the owner adjusts them after the build). A percent follows the first three where known.
    const val REMOVING = "Removing"
    const val NEEDS_NEWER = "Needs a newer app"
    const val DOWNLOADING = "Downloading"
    const val UPDATING = "Updating"
    const val BUILDING = "Building"
    const val FETCHING_TRAILS = "Fetching the trail network"
    const val PAUSED = "Paused"
    const val WAITING = "Waiting"
    const val STOPPED = "Download stopped"
    const val NOT_ON_PHONE = "Not on this phone"
    const val PART_SAVED = "Part saved"
    const val INCOMPLETE = "Incomplete"
    const val UPDATE_AVAILABLE = "Update available"
    const val SAVED = "Saved"
    const val MAY_BE_CLEARED = "May be cleared"

    /** The words a pack row can take, in the table's order; [AREA_WORDS] an area row's. */
    val PACK_WORDS = listOf(REMOVING, DOWNLOADING, UPDATING, BUILDING, FETCHING_TRAILS, PAUSED, WAITING, STOPPED, NOT_ON_PHONE, INCOMPLETE, UPDATE_AVAILABLE, SAVED)
    val AREA_WORDS = listOf(REMOVING, NEEDS_NEWER, DOWNLOADING, UPDATING, WAITING, STOPPED, PART_SAVED, UPDATE_AVAILABLE, SAVED, MAY_BE_CLEARED)

    // The actions, by their labels.
    const val SHOW_ON_MAP = "Show on map"
    const val SHOW_FIRST = "Show first"
    const val UPDATE = "Update"
    const val DOWNLOAD_AGAIN = "Download again"
    const val RETRY = "Retry"
    const val RESUME_NOW = "Resume now"
    const val STORAGE = "Storage"
    const val DISMISS = "Dismiss"
    const val RENAME = "Rename"
    const val KEEP = "Keep on this phone"
    const val ALLOW_CLEARING = "Allow clearing"
    const val REMOVE = "Remove"

    // The detail lines that are not a record's own (ADR 0031).
    const val HOLDS_ALL = "With routing and place search"
    const val HOLDS_VECTOR = "With routing and place search, no terrain"
    const val HOLDS_TERRAIN = "Terrain only"
    const val HOLDS_TILES = "Map only"
    const val NEVER_CLEARED = "Never cleared; outside the budget"
    const val IN_BUDGET = "Counts in the budget"
    const val CLEARED_FIRST = "Counts in the budget; cleared first when space runs short"
    const val SHOWN_FIRST = "Shown first where maps overlap"
    const val REMOVE_STOPS_ONLY = "Remove stops this download; the map stays until you remove it again."

    private val LIVE_JOB_STATES = setOf("queued", "running", "paused", "failed")
    private val REGION_STATES = setOf("building", "downloading", "paused", "failed")
    private val FAILED_RECORD = setOf("failed", "cancelled", "refused")

    /** The rows, running or stopped first (a removal keeps its place: T221), then newest first. */
    fun rows(i: Inputs): List<Row> {
        val live = i.records.map { it.id }.toSet()
        val removals = i.removals.filter { it.record.id !in live }
        val names = LinkedHashSet<String>()
        i.packs.forEach { names += it.name }
        i.records.forEach { r -> if (r.opaque == null) r.pack?.let { names += it } }
        i.jobs.filter { it.shown }.forEach { names += it.name }
        i.regionJob?.takeIf { it.shown }?.let { names += it.name }
        removals.forEach { x -> if (x.record.opaque == null) x.record.pack?.let { names += it } }
        val out = ArrayList<Row>()
        for (p in names) out += packRow(p, i, removals)
        for (r in i.records) if (r.pack == null || r.opaque != null) out += areaRow(r, i)
        for (x in removals) if (x.record.pack == null || x.record.opaque != null) out += removingAreaRow(x, i)
        for ((k, row) in out.withIndex()) if (row.id in i.removing && !row.state.startsWith(REMOVING)) out[k] = row.copy(state = REMOVING, tone = Tone.BUSY, actions = emptyList())
        return out.sortedWith(compareByDescending<Row> { it.tone == Tone.BUSY && !it.state.startsWith(REMOVING) }.thenByDescending { it.sortAt }.thenBy { it.id })
    }

    private fun packRow(p: String, i: Inputs, removals: List<Areas.Removal>): Row {
        val pack = i.packs.firstOrNull { it.name == p }
        val recs = i.records.filter { it.pack == p && it.opaque == null }.sortedByDescending { it.createdAt }
        val newest = recs.firstOrNull()
        val removal = removals.firstOrNull { it.record.pack == p && it.record.opaque == null }
        val job = i.jobs.firstOrNull { it.name == p && it.shown }
        val region = i.regionJob?.takeIf { it.name == p && it.shown && job == null }
        val usable = pack != null && (pack.usable || (pack.hasVector && !pack.hasDem))
        val update = i.catalog[p] == CatalogStatus.UPDATE
        val (state, tone) = when {
            removal != null -> removingWord(removal) to Tone.BUSY
            job != null -> jobWord(job)
            region != null -> regionWord(region)
            pack == null -> NOT_ON_PHONE to Tone.BAD
            !usable -> INCOMPLETE to Tone.BAD
            update -> UPDATE_AVAILABLE to Tone.PLAIN
            else -> SAVED to Tone.GOOD
        }
        val bounds = pack?.bounds ?: newest?.area?.bounds?.toList() ?: region?.bounds
        val detail = ArrayList<String>()
        if (pack != null) detail += if (pack.hasVector && pack.hasDem) HOLDS_ALL else if (pack.hasVector) HOLDS_VECTOR else HOLDS_TERRAIN
        detail += NEVER_CLEARED
        if (pack?.name == i.active && pack != null) detail += SHOWN_FIRST
        val zooms = if (pack?.minZoom != null && pack.maxZoom != null) "z${pack.minZoom}-${pack.maxZoom}" else newest?.area?.let { zoomText(it) }
        zooms?.let { detail += "Zooms $it" }
        bounds?.let { detail += boundsLine(it) }
        if (pack != null && pack.attribution.isNotEmpty()) detail += pack.attribution
        newest?.source?.let { detail += "From $it" }
        if (removal != null) detail += removingLine(removal.done, removal.total)
        job?.let { j ->
            j.sentence?.let { detail += it }
            if (j.word == DownloadStatus.WORD_STOPPED) j.error?.let { detail += it }
            if (j.bytesDone > 0 || j.bytesTotal != null) detail += "${sizeText(j.bytesDone)} of ${j.bytesTotal?.let(::sizeText) ?: "?"}"
            if (pack != null && (j.update || j.graphOnly)) detail += REMOVE_STOPS_ONLY
        }
        region?.let { g -> g.sentence?.let { detail += it }; if (g.state == "failed") g.error?.let { detail += it } }
        pack?.errors?.let { detail += it }
        val actions = ArrayList<String>()
        if (removal == null) {
            if (bounds != null) actions += SHOW_ON_MAP
            if (pack != null && pack.usable && p != i.active) actions += SHOW_FIRST
            job?.actions?.forEach { a -> if (a != DownloadStatus.Action.REMOVE.label && a !in actions) actions += a }
            region?.actions?.forEach { a -> if (a != DownloadStatus.Action.REMOVE.label && a !in actions) actions += a }
            if (update && job == null && pack != null) actions += UPDATE
            actions += REMOVE
        }
        val sortAt = listOfNotNull(newest?.createdAt, pack?.modifiedAt, job?.createdAt, region?.startedAt, removal?.record?.createdAt).maxOrNull() ?: 0L
        val bytes = pack?.bytes ?: job?.let { it.bytesTotal ?: it.bytesDone } ?: newest?.bytes ?: removal?.record?.bytes ?: 0L
        return Row(PACK + p, newest?.name ?: removal?.record?.name ?: p, bytes, state, tone, detail, actions, bounds, p, newest?.id, sortAt)
    }

    private fun areaRow(r: Areas.Record, i: Inputs): Row {
        val job = i.areaJob?.takeIf { it.id == r.id && it.running }
        val olderFrom = i.older[r.id]
        val (state, tone) = when {
            r.opaque != null -> NEEDS_NEWER to Tone.BAD
            job != null -> percentWord(if (job.isNew) DOWNLOADING else UPDATING, job.percent) to Tone.BUSY
            r.state == "pending" || r.state == "downloading" || (r.tripId != null && r.tripId in i.queued) -> WAITING to Tone.BUSY
            r.state in FAILED_RECORD || r.error != null -> STOPPED to Tone.BAD
            r.state == "partial" -> PART_SAVED to Tone.BUSY
            r.state != "done" -> STOPPED to Tone.BAD // a state this build does not write
            olderFrom != null -> UPDATE_AVAILABLE to Tone.PLAIN
            r.pinned -> SAVED to Tone.GOOD
            else -> MAY_BE_CLEARED to Tone.PLAIN
        }
        val detail = ArrayList<String>()
        val actions = ArrayList<String>()
        if (r.opaque != null) {
            detail += NeedsNewerApp.USER_SENTENCE
            detail += r.opaque.reason
            detail += areaDetailLines(r.area)
            actions += SHOW_ON_MAP
            actions += REMOVE
        } else {
            detail += HOLDS_TILES
            detail += if (r.pinned) IN_BUDGET else CLEARED_FIRST
            shapeLine(r, i.units)?.let { detail += it }
            detail += areaDetailLines(r.area)
            val share = i.coverage[r.id]
            detail += "${sizeText(r.bytes)} downloaded (about ${sizeText(r.estimateBytes)} planned)" + (share?.let { String.format(Locale.US, " · %.0f%% on this phone", it * 100) } ?: "")
            r.source?.let { detail += "From $it" }
            olderFrom?.let { detail += if (it) OTHER_SOURCE_NOTE else OLDER_NOTE }
            r.error?.let { detail += it }
            resumeLine(r, i.now)?.let { detail += it }
            val failed = state == STOPPED || state == PART_SAVED
            actions += SHOW_ON_MAP
            if (job == null) {
                actions += if (r.pinned) ALLOW_CLEARING else KEEP
                actions += if (failed) RETRY else if (olderFrom != null) UPDATE else DOWNLOAD_AGAIN
                if (failed) actions += DISMISS
                actions += RENAME
            }
            actions += REMOVE
        }
        return Row(AREA + r.id, r.name, r.bytes, state, tone, detail, actions, r.area.bounds.toList(), null, r.id, r.createdAt)
    }

    private fun removingAreaRow(x: Areas.Removal, i: Inputs): Row =
        Row(AREA + x.record.id, x.record.name, x.record.bytes, removingWord(x), Tone.BUSY, listOf(removingLine(x.done, x.total)), emptyList(), x.record.area.bounds.toList(), null, x.record.id, x.record.createdAt)

    /** "Removing", or "Removing 30%" once the tiles are counted (T221). */
    fun removingWord(x: Areas.Removal): String = x.total?.takeIf { it > 0 }?.let { percentWord(REMOVING, x.done * 100.0 / it) } ?: REMOVING

    /** A download's word: T222's paused, waiting and stopped readings, else by its kind with the percent. */
    fun jobWord(j: Job): Pair<String, Tone> = when {
        j.word == DownloadStatus.WORD_PAUSED || j.state == "paused" -> percentWord(PAUSED, j.percent, at = true) to Tone.BUSY
        j.state == "queued" -> WAITING to Tone.BUSY
        j.state == "failed" || j.word == DownloadStatus.WORD_STOPPED -> STOPPED to Tone.BAD
        j.graphOnly -> FETCHING_TRAILS to Tone.BUSY
        else -> percentWord(if (j.update) UPDATING else DOWNLOADING, j.percent) to Tone.BUSY
    }

    /** The region job's word while it has no download of its own (its build, or a stop before the download). */
    fun regionWord(g: RegionJob): Pair<String, Tone> = when (g.state) {
        "building" -> BUILDING to Tone.BUSY
        "paused" -> percentWord(PAUSED, g.percent, at = true) to Tone.BUSY
        "downloading" -> percentWord(DOWNLOADING, g.percent) to Tone.BUSY
        else -> STOPPED to Tone.BAD
    }

    /** "Downloading 40%", "Paused at 40%"; the word alone when the percent is not known. Never 100% before the end. */
    fun percentWord(word: String, percent: Double?, at: Boolean = false): String =
        percent?.let { "$word ${if (at) "at " else ""}${floor(it).toInt().coerceIn(0, 99)}%" } ?: word

    /**
     * What Remove does to a row, step by step (the join table's last column): [records] removed through [Areas.remove] (an area
     * row's through [TripCorridors.userRemove], [tripRemove]), then [deletePack] ([io.github.jasondorjeshort.dystopo.app.packs.PackManager.delete],
     * which cancels the download), or only [cancelJob] for an update or a trail-network fetch of a pack that stays;
     * [cancelRegion] stops the region job's build, [dismissRegion] clears its line.
     */
    data class RemovePlan(
        val records: List<String> = emptyList(), val tripRemove: String? = null, val deletePack: String? = null, val cancelJob: String? = null,
        val cancelRegion: Boolean = false, val dismissRegion: String? = null,
    )

    /** [row]'s Remove under [i]; null when the row offers none (a removal under way). */
    fun removePlan(row: Row, i: Inputs): RemovePlan? {
        if (REMOVE !in row.actions) return null
        val p = row.pack ?: return RemovePlan(tripRemove = row.recordId)
        val pack = i.packs.firstOrNull { it.name == p }
        val job = i.jobs.firstOrNull { it.name == p && it.shown }
        if (pack != null && job != null && (job.update || job.graphOnly)) return RemovePlan(cancelJob = p)
        val region = i.regionJob?.takeIf { it.name == p && it.shown }
        return RemovePlan(
            records = i.records.filter { it.pack == p && it.opaque == null }.map { it.id },
            deletePack = p.takeIf { pack != null || job != null },
            cancelRegion = region != null && (region.state == "building" || region.state == "downloading" || region.state == "paused"),
            dismissRegion = p.takeIf { region != null },
        )
    }

    /** An area row's shape: a disc by its radius, a corridor by its length and width, a polygon by its area; nothing for a box. */
    private fun shapeLine(r: Areas.Record, units: ContourUnits): String? = when (val s = r.area.shape) {
        is AreaShape.Corridor -> if (s.points.size == 1 && r.tripId == null) discLine(s.bufferM, units)
            else corridorLine(r.area.lengthM, if (r.tripId != null) TripCorridor.widthOf(s.bufferM).toDouble() else s.bufferM, units, trip = r.tripId != null)
        is AreaShape.Polygon -> polygonLine(PolygonRings.areaM2(s.rings), units)
        else -> null
    }

    private fun boundsLine(b: List<Double>): String = String.format(Locale.US, "Bounds %.2f, %.2f to %.2f, %.2f", b[1], b[0], b[3], b[2])

    /** A row's size: "3.4 MB", "212 MB", "1.2 GB", "48 kB". */
    fun sizeText(bytes: Long): String = if (bytes < 1_048_576) String.format(Locale.US, "%d kB", bytes / 1024) else DownloadStatus.sizeText(bytes)
}

