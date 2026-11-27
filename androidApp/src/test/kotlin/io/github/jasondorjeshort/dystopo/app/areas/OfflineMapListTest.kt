package io.github.jasondorjeshort.dystopo.app.areas

import io.github.jasondorjeshort.dystopo.app.areas.OfflineMapList as L
import io.github.jasondorjeshort.dystopo.areas.AreaDetail as D // T386
import io.github.jasondorjeshort.dystopo.areas.AreaOfInterest
import io.github.jasondorjeshort.dystopo.areas.AreaPoint
import io.github.jasondorjeshort.dystopo.areas.AreaShape
import io.github.jasondorjeshort.dystopo.packs.CatalogStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * T234 (ADR 0056, B1a): the one list's join and state words ([OfflineMapList]), total over proposal 0056's two tables. Every way
 * a map arrives (imported, from the catalog, from a link, from a nearby phone, a region download, a drawn area, a disc, a
 * corridor, a trip's corridor, a record this build cannot read) is crossed with every pack state, download, region job, record
 * state, error, pin, catalog status, removal, older version, queue and tile download; each input must give one row per map with
 * one word, the word the tables' first matching line names, and the Remove the join table's last column names. A failure prints
 * the input ([Case]) and the rows it gave. Public places only (Rainier).
 */
class OfflineMapListTest {
    private val box = AreaOfInterest(AreaShape.Box(-121.9, 46.7, -121.5, 46.95), 8, 14, "Rainier")
    private val disc = AreaOfInterest(AreaShape.Corridor(listOf(AreaPoint(46.7859, -121.7356)), 5000.0), 8, 15, "Paradise")
    private val corridor = AreaOfInterest(AreaShape.Corridor(listOf(AreaPoint(46.7859, -121.7356), AreaPoint(46.8526, -121.7604)), 500.0), 8, 15, "Muir")
    private val polygon = AreaOfInterest(
        AreaShape.Polygon(listOf(listOf(AreaPoint(46.80, -121.80), AreaPoint(46.80, -121.70), AreaPoint(46.90, -121.70), AreaPoint(46.90, -121.80)))), 8, 15, "Drawn",
    )

    enum class Source(val pack: Boolean, val record: Boolean) {
        IMPORT(true, false), CATALOG(true, false), LINK(true, false), PEER(true, true), REGION(true, true),
        DRAWN(false, true), DISC(false, true), CORRIDOR(false, true), TRIP(false, true), OPAQUE(false, true),
    }

    enum class PackState { ABSENT, USABLE, INCOMPLETE, VECTOR_ONLY, TERRAIN_ONLY }
    enum class JobKind { NONE, QUEUED, RUNNING, PAUSED, FAILED, UPDATE_RUNNING, UPDATE_FAILED, GRAPH_RUNNING, GRAPH_FAILED, DONE }
    enum class RegionKind { NONE, BUILDING, DOWNLOADING, PAUSED, FAILED, IDLE, DONE }
    enum class Removal { NONE, COUNTING, PART }
    enum class AreaJobKind { NONE, NEW, AGAIN, FINISHED }

    /** One input: one map, as [source] says, in every state the code can give it. */
    data class Case(
        val source: Source, val pack: PackState = PackState.ABSENT, val job: JobKind = JobKind.NONE, val region: RegionKind = RegionKind.NONE,
        val catalog: CatalogStatus? = null, val removal: Removal = Removal.NONE, val removing: Boolean = false,
        val state: String = "done", val error: Boolean = false, val pinned: Boolean = true, val twoRecords: Boolean = false,
        val older: Boolean? = null, val queued: Boolean = false, val areaJob: AreaJobKind = AreaJobKind.NONE,
    )

    private val p = "rainier"
    private val recordStates = listOf("pending", "downloading", "done", "failed", "cancelled", "partial", "refused", "unknown")

    private fun inputs(c: Case): L.Inputs {
        val packs = when (c.pack) {
            PackState.ABSENT -> emptyList()
            PackState.USABLE -> listOf(L.Pack(p, 9_000_000, usable = true, hasDem = true, hasVector = true, bounds = listOf(-121.9, 46.7, -121.5, 46.95), minZoom = 8, maxZoom = 14, modifiedAt = 5))
            PackState.INCOMPLETE -> listOf(L.Pack(p, 1_000, usable = false, hasDem = true, hasVector = true, modifiedAt = 5))
            PackState.VECTOR_ONLY -> listOf(L.Pack(p, 2_000_000, usable = false, hasDem = false, hasVector = true, modifiedAt = 5))
            PackState.TERRAIN_ONLY -> listOf(L.Pack(p, 3_000_000, usable = true, hasDem = true, hasVector = false, modifiedAt = 5))
        }
        fun job(state: String, word: String, update: Boolean = false, graph: Boolean = false) =
            L.Job(p, state, word, percent = 40.0, update = update, graphOnly = graph, actions = if (state == "failed") listOf("Retry", "Remove") else if (state == "paused") listOf("Resume now", "Remove") else emptyList(), bytesDone = 4, bytesTotal = 10, createdAt = 7)
        val jobs = when (c.job) {
            JobKind.NONE -> emptyList()
            JobKind.QUEUED -> listOf(job("queued", "waiting"))
            JobKind.RUNNING -> listOf(job("running", "downloading"))
            JobKind.PAUSED -> listOf(job("paused", "paused"))
            JobKind.FAILED -> listOf(job("failed", "stopped"))
            JobKind.UPDATE_RUNNING -> listOf(job("running", "downloading", update = true))
            JobKind.UPDATE_FAILED -> listOf(job("failed", "stopped", update = true))
            JobKind.GRAPH_RUNNING -> listOf(job("running", "downloading", graph = true))
            JobKind.GRAPH_FAILED -> listOf(job("failed", "stopped", graph = true))
            JobKind.DONE -> listOf(job("done", "done"))
        }
        val region = when (c.region) {
            RegionKind.NONE -> null
            else -> L.RegionJob(p, c.region.name.lowercase(), percent = 30.0, actions = if (c.region == RegionKind.FAILED) listOf("Retry", "Remove") else emptyList(), bounds = listOf(-121.9, 46.7, -121.5, 46.95))
        }
        val area = when (c.source) { Source.DISC -> disc; Source.CORRIDOR, Source.TRIP -> corridor; Source.DRAWN -> polygon; else -> box }
        val records = ArrayList<Areas.Record>()
        if (c.source.record) {
            val packName = if (c.source.pack || c.source == Source.OPAQUE) p else null
            records += Areas.Record(
                id = "a0001", name = "Rainier map", area = area, createdAt = 3, pinned = c.pinned, state = c.state, pack = packName,
                source = if (c.source == Source.PEER) "peer://192.0.2.1:8687" else "sources", tripId = if (c.source == Source.TRIP) "t1" else null,
                error = if (c.error) "the connection dropped" else null, opaque = if (c.source == Source.OPAQUE) Areas.Record.Opaque(mapOf("id" to "a0001"), "a newer kind") else null,
            )
            if (c.twoRecords) records += records[0].copy(id = "a0002", name = "Rainier again", createdAt = 4)
        }
        val removals = ArrayList<Areas.Removal>()
        if (c.removal != Removal.NONE && records.isNotEmpty()) {
            val gone = records.removeAt(records.lastIndex)
            removals += Areas.Removal(gone, 9, done = if (c.removal == Removal.PART) 30 else 0, total = if (c.removal == Removal.PART) 100 else null)
        }
        val areaJob = when (c.areaJob) {
            AreaJobKind.NONE -> null
            AreaJobKind.NEW -> L.AreaJob("a0001", true, 40.0, isNew = true)
            AreaJobKind.AGAIN -> L.AreaJob("a0001", true, 40.0, isNew = false)
            AreaJobKind.FINISHED -> L.AreaJob("a0001", false, 100.0)
        }
        val rowIds = buildSet { if (c.removing) { add(L.PACK + p); add(L.AREA + "a0001") } }
        return L.Inputs(
            packs = packs, records = records, removals = removals, jobs = jobs, regionJob = region, areaJob = areaJob,
            queued = if (c.queued) setOf("t1") else emptySet(), catalog = c.catalog?.let { mapOf(p to it) }.orEmpty(),
            older = c.older?.let { mapOf("a0001" to it) }.orEmpty(), active = null, removing = rowIds,
        )
    }

    // --- the tables, as the proposal words them, each line a condition in order: the expected word is the first that holds ---

    private fun jobShown(j: L.Job) = if (j.graphOnly) j.state == "queued" || j.state == "running" else j.state in setOf("queued", "running", "paused", "failed")

    /** The state-word table for a pack row (`pack:P`). */
    private fun packWord(i: L.Inputs): String? {
        val pack = i.packs.firstOrNull()
        val job = i.jobs.firstOrNull { jobShown(it) }
        val region = i.regionJob?.takeIf { it.state in setOf("building", "downloading", "paused", "failed") && job == null }
        val table = listOf<Pair<String, () -> Boolean>>(
            "Removing 30%" to { i.removals.any { it.record.pack == p && it.total == 100 } },
            "Removing" to { i.removals.any { it.record.pack == p } || L.PACK + p in i.removing },
            "Fetching the trail network" to { job?.graphOnly == true && job.state == "running" },
            "Updating 40%" to { job?.update == true && job.state == "running" },
            "Downloading 40%" to { job != null && job.state == "running" },
            "Building" to { region?.state == "building" },
            "Downloading 30%" to { region?.state == "downloading" },
            "Paused at 40%" to { job?.state == "paused" },
            "Paused at 30%" to { region?.state == "paused" },
            "Waiting" to { job?.state == "queued" },
            "Download stopped" to { job?.state == "failed" || region?.state == "failed" },
            "Not on this phone" to { pack == null },
            "Incomplete" to { pack != null && !pack.usable && !(pack.hasVector && !pack.hasDem) },
            "Update available" to { i.catalog[p] == CatalogStatus.UPDATE },
            "Saved" to { pack != null },
        )
        return table.firstOrNull { it.second() }?.first
    }

    /** The state-word table for an area row (`area:<id>`). */
    private fun areaWord(r: Areas.Record?, removal: Areas.Removal?, i: L.Inputs): String? {
        val table = listOf<Pair<String, () -> Boolean>>(
            "Removing 30%" to { removal?.total == 100 },
            "Removing" to { removal != null || L.AREA + "a0001" in i.removing },
            "Needs a newer app" to { r!!.opaque != null },
            "Downloading 40%" to { i.areaJob?.running == true && i.areaJob.isNew },
            "Updating 40%" to { i.areaJob?.running == true },
            "Waiting" to { r!!.state == "pending" || r.state == "downloading" || (r.tripId != null && r.tripId in i.queued) },
            "Download stopped" to { r!!.state in setOf("failed", "cancelled", "refused") || r.error != null },
            "Part saved" to { r!!.state == "partial" },
            "Download stopped" to { r!!.state != "done" },
            "Update available" to { r!!.id in i.older },
            "Saved" to { r!!.pinned },
            "May be cleared" to { true },
        )
        return table.firstOrNull { it.second() }?.first
    }

    private val reached = HashSet<String>()

    /** One input checked: the join (one row per map), the word, the actions and Remove. */
    private fun check(c: Case) {
        val i = inputs(c)
        val rows = L.rows(i)
        fun no(msg: String): Nothing = fail("$msg\n  input: $c\n  rows: ${rows.joinToString("\n    ", "\n    ") { it.toString() }}")
        if (rows.map { it.id }.toSet().size != rows.size) no("a row id repeats")
        // The join: which rows must exist.
        val packNamed = i.packs.isNotEmpty() || i.records.any { it.pack == p && it.opaque == null } || i.jobs.any { jobShown(it) } ||
            i.regionJob?.state in setOf("building", "downloading", "paused", "failed") || i.removals.any { it.record.pack == p && it.record.opaque == null }
        val packRows = rows.filter { it.id == L.PACK + p }
        if (packRows.size != (if (packNamed) 1 else 0)) no("${packRows.size} rows for the pack, want ${if (packNamed) 1 else 0}")
        val areaRecord = i.records.firstOrNull { it.pack == null || it.opaque != null }
        val areaRemoval = i.removals.firstOrNull { it.record.pack == null || it.record.opaque != null }
        val areaRows = rows.filter { it.id.startsWith(L.AREA) }
        val wantAreas = listOfNotNull(areaRecord?.id, areaRemoval?.record?.id).size
        if (areaRows.size != wantAreas) no("${areaRows.size} area rows, want $wantAreas")
        if (rows.size != packRows.size + areaRows.size) no("a row that is neither the pack's nor an area's")
        // Every pack-backed record shows only through its pack's row, named by the newest.
        i.records.filter { it.pack == p && it.opaque == null }.maxByOrNull { it.createdAt }?.let { newest ->
            if (packRows.single().name != newest.name) no("the pack row is not named by the newest record")
        }
        // The words.
        for (row in packRows) {
            val want = packWord(i) ?: no("no line of the table holds for the pack row")
            if (row.state != want) no("pack row reads '${row.state}', the table says '$want'")
            if (L.PACK_WORDS.none { row.state.startsWith(it) }) no("'${row.state}' is not a pack row's word")
            reached += "pack:" + want.substringBefore(" 4").substringBefore(" 3").substringBefore(" at")
        }
        for (row in areaRows) {
            val rec = i.records.firstOrNull { it.id == row.recordId }
            val removal = i.removals.firstOrNull { it.record.id == row.recordId }
            val want = areaWord(rec, removal, i) ?: no("no line of the table holds for the area row")
            if (row.state != want) no("area row reads '${row.state}', the table says '$want'")
            if (L.AREA_WORDS.none { row.state.startsWith(it) }) no("'${row.state}' is not an area row's word")
            reached += "area:" + want.substringBefore(" 4").substringBefore(" 3").substringBefore(" at")
        }
        // Remove, by the join table's last column.
        for (row in rows) {
            val plan = L.removePlan(row, i)
            val busy = row.state.startsWith("Removing")
            if (busy) { if (row.actions.isNotEmpty() || plan != null) no("a row being removed offers ${row.actions}"); continue }
            if (plan == null) no("${row.id} offers no Remove")
            if (row.id.startsWith(L.AREA)) { if (plan != L.RemovePlan(tripRemove = row.recordId)) no("an area row's Remove is $plan"); continue }
            val job = i.jobs.firstOrNull { jobShown(it) }
            val pack = i.packs.firstOrNull()
            if (pack != null && job != null && (job.update || job.graphOnly)) {
                if (plan != L.RemovePlan(cancelJob = p)) no("an update's or a trail fetch's Remove is $plan, want the job stopped only")
            } else {
                val recs = i.records.filter { it.pack == p && it.opaque == null }.map { it.id }
                if (plan.records != recs) no("Remove takes records ${plan.records}, want every record naming the pack $recs")
                if ((plan.deletePack == p) != (pack != null || job != null)) no("Remove deletes the pack: ${plan.deletePack}")
            }
        }
    }

    @Test
    fun everySourceByEveryStateIsOneRowWithTheTablesWord() {
        var n = 0
        for (s in Source.entries) {
            if (s.pack) {
                val states = if (s.record) listOf("done", "failed") else listOf("done")
                for (pk in PackState.entries) for (j in JobKind.entries) for (g in RegionKind.entries) for (cat in listOf(null, CatalogStatus.UPDATE, CatalogStatus.INSTALLED))
                    for (rm in Removal.entries) for (removing in listOf(false, true)) for (st in states) for (err in listOf(false, true).takeIf { s.record } ?: listOf(false))
                        for (two in listOf(false, true).takeIf { s.record } ?: listOf(false)) {
                            check(Case(s, pk, j, g, cat, rm, removing, state = st, error = err, twoRecords = two)); n++
                        }
            } else {
                for (st in recordStates) for (err in listOf(false, true)) for (pin in listOf(false, true)) for (old in listOf(null, false, true))
                    for (q in listOf(false, true)) for (aj in AreaJobKind.entries) for (rm in Removal.entries) for (removing in listOf(false, true)) {
                        check(Case(s, state = st, error = err, pinned = pin, older = old, queued = q, areaJob = aj, removal = rm, removing = removing)); n++
                    }
            }
        }
        // An unreadable record naming a pack: its own row, and the pack's apart from it.
        for (pk in PackState.entries) for (j in JobKind.entries) { check(Case(Source.OPAQUE, pk, j)); n++ }
        // Every word of both tables is reached by some input.
        val packWords = L.PACK_WORDS.map { "pack:$it" }.toSet()
        val areaWords = L.AREA_WORDS.map { "area:$it" }.toSet()
        assertEquals(emptySet(), packWords - reached, "pack words no input reached ($n inputs)")
        assertEquals(emptySet(), areaWords - reached, "area words no input reached ($n inputs)")
    }

    // --- the join table, line by line ---

    private fun rec(id: String, pack: String?, at: Long, name: String = id, area: AreaOfInterest = box) =
        Areas.Record(id = id, name = name, area = area, createdAt = at, state = "done", pack = pack, source = "sources")

    private val usable = L.Pack(p, 9_000_000, usable = true, hasDem = true, hasVector = true, bounds = listOf(-121.9, 46.7, -121.5, 46.95), minZoom = 8, maxZoom = 14, modifiedAt = 5)

    @Test
    fun aRecordNamingAPackOnThePhoneIsOneRowNamedByTheNewestRecord() {
        val i = L.Inputs(packs = listOf(usable), records = listOf(rec("a0001", p, 1, "Old name"), rec("a0002", p, 2, "New name")))
        val row = L.rows(i).single()
        assertEquals(L.PACK + p, row.id)
        assertEquals("New name", row.name)
        assertEquals("Saved", row.state)
        assertEquals("a0002", row.recordId)
        assertEquals(L.RemovePlan(records = listOf("a0001", "a0002"), deletePack = p), L.removePlan(row, i))
    }

    @Test
    fun aPackWithNoRecordIsNamedByItself() {
        val i = L.Inputs(packs = listOf(usable))
        val row = L.rows(i).single()
        assertEquals(p, row.name)
        assertEquals(listOf(L.SHOW_ON_MAP, L.START_HERE, L.REMOVE), row.actions)
        assertEquals(L.RemovePlan(deletePack = p), L.removePlan(row, i))
        assertTrue(L.NEVER_CLEARED in row.detail && L.HOLDS_ALL in row.detail, row.detail.toString())
        assertTrue(row.detail.none { it.startsWith("From ") }, "a pack with no record says nothing of where it came from")
    }

    @Test
    fun aRecordWhosePackIsDownloadingReadsTheJob() {
        val job = L.Job(p, "running", "downloading", percent = 41.7, bytesDone = 4, bytesTotal = 10)
        val i = L.Inputs(records = listOf(rec("a0001", p, 1)), jobs = listOf(job))
        val row = L.rows(i).single()
        assertEquals("Downloading 41%", row.state)
        assertEquals(L.RemovePlan(records = listOf("a0001"), deletePack = p), L.removePlan(row, i))
    }

    @Test
    fun aPresentPackWithAJobIsOneRowByTheJob() {
        for ((job, word) in listOf(
            L.Job(p, "running", "downloading", percent = 40.0) to "Downloading 40%",
            L.Job(p, "running", "downloading", percent = 40.0, update = true) to "Updating 40%",
            L.Job(p, "running", "downloading", graphOnly = true) to "Fetching the trail network",
        )) {
            val i = L.Inputs(packs = listOf(usable), jobs = listOf(job))
            val row = L.rows(i).single()
            assertEquals(word, row.state, job.toString())
            val plan = L.removePlan(row, i)
            if (job.update || job.graphOnly) assertEquals(L.RemovePlan(cancelJob = p), plan, "the pack stays until a second Remove")
            else assertEquals(L.RemovePlan(deletePack = p), plan, "a fresh download's pack goes with it")
        }
    }

    @Test
    fun aRecordWhosePackIsGoneReadsNotOnThisPhoneWithRemoveOnly() {
        val i = L.Inputs(records = listOf(rec("a0001", p, 1)))
        val row = L.rows(i).single()
        assertEquals("Not on this phone", row.state)
        assertEquals(listOf(L.SHOW_ON_MAP, L.REMOVE), row.actions)
        assertEquals(L.RemovePlan(records = listOf("a0001")), L.removePlan(row, i))
    }

    @Test
    fun aRecordWithNoPackIsAnAreaRowAndAnUnreadableOneKeepsItsOwn() {
        val drawn = Areas.Record(id = "a0003", name = "Drawn", area = polygon, createdAt = 1, state = "done", pinned = false)
        val opaque = rec("a0004", p, 2).copy(opaque = Areas.Record.Opaque(mapOf("id" to "a0004"), "a newer kind"))
        val i = L.Inputs(packs = listOf(usable), records = listOf(drawn, opaque))
        val rows = L.rows(i).associateBy { it.id }
        assertEquals(setOf(L.AREA + "a0003", L.AREA + "a0004", L.PACK + p), rows.keys)
        assertEquals("May be cleared", rows.getValue(L.AREA + "a0003").state)
        assertTrue(L.KEEP in rows.getValue(L.AREA + "a0003").actions)
        assertEquals("Needs a newer app", rows.getValue(L.AREA + "a0004").state)
        assertEquals(listOf(L.SHOW_ON_MAP, L.REMOVE), rows.getValue(L.AREA + "a0004").actions)
        assertEquals(p, rows.getValue(L.PACK + p).name, "the unreadable record does not name the pack's row")
    }

    @Test
    fun aRegionJobBeforeItsDownloadIsThePacksRow() {
        val building = L.Inputs(regionJob = L.RegionJob(p, "building", percent = 10.0))
        assertEquals("Building", L.rows(building).single().state)
        assertEquals(L.RemovePlan(cancelRegion = true, dismissRegion = p), L.removePlan(L.rows(building).single(), building))
        val failed = L.Inputs(regionJob = L.RegionJob(p, "failed", actions = listOf("Retry", "Remove")))
        assertEquals("Download stopped", L.rows(failed).single().state)
        assertEquals(L.RemovePlan(dismissRegion = p), L.removePlan(L.rows(failed).single(), failed))
        assertTrue(L.rows(L.Inputs(regionJob = L.RegionJob(p, "done"))).isEmpty(), "a finished region job is its pack's row, not one of its own")
        val downloading = L.Inputs(regionJob = L.RegionJob(p, "downloading", bounds = listOf(-121.76, 46.77, -121.70, 46.80)), jobs = listOf(L.Job(p, "running", "downloading", percent = 10.0)))
        assertEquals(listOf(L.SHOW_ON_MAP, L.REMOVE), L.rows(downloading).single().actions, "the region's box while its download runs")
    }

    @Test
    fun aRemovalKeepsItsPlaceAndTheRunningRowsComeFirst() {
        val old = rec("a0001", null, 1, area = disc)
        val gone = rec("a0002", null, 2, area = disc)
        val newest = rec("a0003", null, 3, area = disc)
        val job = L.Job("other", "running", "downloading", percent = 5.0, createdAt = 0)
        val rows = L.rows(L.Inputs(records = listOf(old, newest), removals = listOf(Areas.Removal(gone, 9)), jobs = listOf(job)))
        assertEquals(listOf(L.PACK + "other", L.AREA + "a0003", L.AREA + "a0002", L.AREA + "a0001"), rows.map { it.id })
        assertEquals("Removing", rows[2].state)
        assertNull(L.removePlan(rows[2], L.Inputs()))
    }

    @Test
    fun aCatalogUpdateIsTheRowsUpdate() {
        val i = L.Inputs(packs = listOf(usable), catalog = mapOf(p to CatalogStatus.UPDATE), active = p)
        val row = L.rows(i).single()
        assertEquals("Update available", row.state)
        assertEquals(listOf(L.SHOW_ON_MAP, L.UPDATE, L.REMOVE), row.actions, "the primary pack has no Start here")
        assertTrue(L.STARTS_HERE in row.detail)
        // T361: the owner's name, and the line that is true since depth comes first (T354).
        assertEquals("Start here", L.START_HERE)
        assertEquals("The app opens on this map. Where another map is just as detailed, this one is shown.", L.STARTS_HERE)
    }

    @Test
    fun percentsNeverReadAHundredBeforeTheEnd() {
        assertEquals("Downloading 99%", L.percentWord(L.DOWNLOADING, 99.96))
        assertEquals("Paused at 0%", L.percentWord(L.PAUSED, 0.4, at = true))
        assertEquals("Downloading", L.percentWord(L.DOWNLOADING, null))
    }

    @Test
    fun eachRowHasItsLevelAndOutline() { // T366
        val fine = L.Pack("rainier-fine", 9_000_000, true, true, true, listOf(-121.82, 46.80, -121.68, 46.90), 8, 14, vectorMaxZoom = 14, demMaxZoom = 14)
        val wide = L.Pack("rainier-wide", 9_000_000, true, true, true, listOf(-121.9, 46.7, -121.5, 46.95), 8, 13, vectorMaxZoom = 13, demMaxZoom = 11)
        val medium = rec("a0001", null, 1, area = box.at(io.github.jasondorjeshort.dystopo.areas.AreaDetail.MEDIUM))
        val rows = L.rows(L.Inputs(packs = listOf(fine, wide), records = listOf(medium), off = setOf("rainier-wide"))).associateBy { it.id }
        assertEquals(DetailOutline.Level.FULL, rows.getValue(L.PACK + "rainier-fine").level)
        assertEquals(DetailOutline.Level.OFF, rows.getValue(L.PACK + "rainier-wide").level, "a map that is off")
        assertEquals(DetailOutline.Level.MEDIUM, rows.getValue(L.AREA + "a0001").level)
        val j = rows.getValue(L.PACK + "rainier-fine").json(night = true)
        assertEquals("full", j["level"])
        val outline = j["outline"] as Map<*, *>
        assertEquals("#f0e6ff", outline["colour"])
        assertEquals(3.0, outline["width"])
        assertNull(outline["dash"])
        assertEquals(true, outline["night"])
        // On again, the wide map is low detail by its archives (map to z13, elevation to z11).
        assertEquals(DetailOutline.Level.LOW, L.rows(L.Inputs(packs = listOf(wide))).single().level)
    }

    // T386: a saved area's detail changed with its shape kept: the row's Detail, the raise and the lower on the row, the record's
    // new field read from files written before it, the resume check leaving a deferred raise alone, and the sheet's sentences.
    private fun drawnAt(d: D, id: String = "a0003", state: String = "done", raise: Areas.Record.Raise? = null, trip: String? = null) =
        Areas.Record(id = id, name = "Drawn", area = polygon.at(d), createdAt = 1, state = state, source = "sources", raise = raise, tripId = trip, bytes = 40L shl 20)

    @Test
    fun aDrawnAreaOffersDetailAndATripsCorridorSaysItsDetailFollowsTheTrip() {
        val drawn = L.rows(L.Inputs(records = listOf(drawnAt(D.FULL)))).single()
        assertTrue(DETAIL in drawn.actions, drawn.actions.toString())
        assertTrue(drawn.actions.indexOf(DETAIL) < drawn.actions.indexOf(L.RENAME))
        val trip = Areas.Record(id = "a0005", name = "Muir", area = corridor, createdAt = 1, state = "done", tripId = "r1", source = "sources")
        val row = L.rows(L.Inputs(records = listOf(trip))).single()
        assertTrue(DETAIL !in row.actions, "no Detail on a trip's corridor: ${row.actions}")
        assertTrue(TRIP_DETAIL_LINE in row.detail, row.detail.toString())
        assertEquals("Its detail follows the trip.", TRIP_DETAIL_LINE)
        assertEquals(TRIP_DETAIL_LINE, AreaDownload.detailRefusal(trip))
        // Not on an area still downloading, nor on a pack's row.
        assertTrue(DETAIL !in L.rows(L.Inputs(records = listOf(drawnAt(D.FULL, state = "partial")))).single().actions)
        assertTrue(L.rows(L.Inputs(packs = listOf(usable))).single().actions.none { it == DETAIL })
    }

    @Test
    fun aRaiseThatWaitsKeepsTheRowSavedWithStartAndItsLine() {
        val waits = drawnAt(D.MEDIUM, raise = Areas.Record.Raise.of(polygon.at(D.LESS), deferred = true))
        val row = L.rows(L.Inputs(records = listOf(waits), raiseBytes = mapOf("a0003" to (12L shl 20)))).single()
        assertEquals(L.SAVED, row.state)
        assertTrue(L.START in row.actions && L.DOWNLOAD_AGAIN !in row.actions && L.RETRY !in row.actions && DETAIL in row.actions, row.actions.toString())
        assertTrue("Medium detail waits: about 12 MB to download" in row.detail, row.detail.toString())
        assertEquals(DetailOutline.Level.LESS, row.level, "the level it holds whole")
        assertTrue("Zooms z8-13" in row.detail, "the zooms it holds whole: ${row.detail}")
        // Running: the word with its percent; cut short: Saved still, the line says it goes on.
        val running = L.rows(L.Inputs(records = listOf(waits.copy(state = "downloading", raise = waits.raise!!.copy(deferred = false))), areaJob = L.AreaJob("a0003", true, 40.0, isNew = false))).single()
        assertEquals("Adding detail 40%", running.state)
        assertTrue("Adding Medium detail" in running.detail)
        val partial = L.rows(L.Inputs(records = listOf(waits.copy(state = "partial", raise = waits.raise!!.copy(deferred = false))))).single()
        assertEquals(L.SAVED, partial.state)
        assertTrue("Adding Medium detail: part downloaded" in partial.detail && "Continues by itself" in partial.detail, partial.detail.toString())
    }

    @Test
    fun aLowerReadsLoweringDetailWithItsProgress() {
        val r = drawnAt(D.LESS)
        val counting = L.rows(L.Inputs(records = listOf(r), lowerings = listOf(Areas.Lowering("a0003", "Less detail", 0)), indexing = true)).single()
        assertEquals(L.LOWERING, counting.state)
        assertTrue(L.INDEXING_LINE in counting.detail, counting.detail.toString())
        assertEquals(listOf(L.SHOW_ON_MAP, L.REMOVE), counting.actions)
        val freeing = L.rows(L.Inputs(records = listOf(r), lowerings = listOf(Areas.Lowering("a0003", "Less detail", 0, done = 1200, total = 4000)))).single()
        assertTrue("Freeing: 1,200 of 4,000 tiles" in freeing.detail, freeing.detail.toString())
        assertTrue(L.DETAIL_WORDS.none { it in L.AREA_WORDS })
    }

    @Test
    fun aRecordSavedBeforeThePendingRaiseReadsNoneAndWritesNone() {
        // v0.1.13's files (before T386): every record reads with no raise, holds its own zooms, and writes no "raise" back.
        val main = javaClass.getResource("/areas/areas-v0.1.13.json")!!.readText()
        val sibling = javaClass.getResource("/areas/areas2-v0.1.13.json")!!.readText()
        val files = AreaFiles.decode(main, sibling)
        assertEquals(5, files.records.size)
        assertTrue(files.records.all { it.raise == null && it.heldArea == it.area })
        assertTrue("raise" !in files.mainText() && "raise" !in files.siblingText().orEmpty())
        // A record with a raise round-trips; its id is the new detail's, and it holds the old one.
        val waits = drawnAt(D.FULL, id = polygon.at(D.FULL).id, raise = Areas.Record.Raise.of(polygon.at(D.MEDIUM), deferred = true))
        val back = AreaFiles.decode(AreaFiles(listOf(waits)).mainText(), AreaFiles(listOf(waits)).siblingText()).records.single()
        assertEquals(waits.raise, back.raise)
        assertEquals(polygon.at(D.MEDIUM).id, back.heldArea.id)
        assertEquals(polygon.at(D.FULL).id, back.id)
        // A "raise" this build cannot read is none (the record reads as its own zooms).
        assertNull(Areas.Record.Raise.parse(mapOf("deferred" to true)))
    }

    @Test
    fun theResumeCheckNeverStartsARaiseLeftForLater() {
        val later = drawnAt(D.MEDIUM, state = "done", raise = Areas.Record.Raise.of(polygon.at(D.LESS), deferred = true))
        assertTrue(!unfinished(later), "a raise left for later is a finished record: nothing to resume")
        val stopped = later.copy(state = "partial")
        for (present in listOf(true, false)) {
            val d = decide(listOf(stopped), null, 0L, 2048, userPresent = present)
            assertEquals("deferred", d.unfinished.single()["why"])
            assertNull(d.start)
        }
        // Started and cut short: it goes on by itself, as any unfinished download.
        val started = later.copy(state = "partial", raise = later.raise!!.copy(deferred = false))
        assertEquals(started.id, decide(listOf(started), null, 0L, 2048, userPresent = true).start)
    }

    @Test
    fun aLowerAsksTheOtherPinsNotTheAreasOwnCover() {
        // The area's own cover still holds a tile below its new deepest zoom (an overzoomed view), so the clear must not ask it:
        // found on the emulator, where a Full to Less lower freed nothing while asking every pin.
        val less = polygon.at(D.LESS)
        val z14 = io.github.jasondorjeshort.dystopo.areas.TileCover.of(polygon.at(D.FULL), 14, 1)
        var x = -1
        var y = -1
        z14.forEachRow { yy, x0, _ -> if (x < 0) { x = x0; y = yy } }
        val key = "sources/dem/14/$x/$y.png"
        assertTrue(io.github.jasondorjeshort.dystopo.areas.AreaPins(listOf(io.github.jasondorjeshort.dystopo.areas.AreaPins.cover(less))).isPinned(key))
        val dropped = io.github.jasondorjeshort.dystopo.areas.TilesItem("dem", "png", "", mapOf(14 to z14), 0)
        assertTrue(removalMatcher(mapOf("sources/dem" to dropped)) { false }(key), "with no other pin it goes")
        val src = java.io.File("src/main/kotlin/io/github/jasondorjeshort/dystopo/app/areas/Areas.kt").readText()
        assertTrue("pinnedByOthers(n.id).isPinned(k)" in src, "the lower's clear asks the other pins")
    }

    @Test
    fun searchKeepsAnAreaWhileItsRaiseRunsWaitsOrWasCutShort() {
        val keep = io.github.jasondorjeshort.dystopo.app.search.AreaTiles::keptAsTiles
        val held = Areas.Record.Raise.of(polygon.at(D.MEDIUM), deferred = false)
        assertTrue(keep(drawnAt(D.MEDIUM)), "a finished area")
        assertTrue(keep(drawnAt(D.FULL, state = "downloading", raise = held)), "a raise downloading")
        assertTrue(keep(drawnAt(D.FULL, state = "done", raise = held.copy(deferred = true))), "a raise left for later")
        assertTrue(keep(drawnAt(D.FULL, state = "partial", raise = held)), "a raise cut short")
        assertTrue(!keep(drawnAt(D.FULL, state = "partial")), "a first download not finished stays out")
        assertTrue(!keep(drawnAt(D.FULL, state = "downloading")), "nor one still downloading")
    }

    @Test
    fun theSheetsSentences() {
        val less = io.github.jasondorjeshort.dystopo.areas.DetailChange.of(polygon, D.FULL, D.LESS, 8)
        val full = io.github.jasondorjeshort.dystopo.areas.DetailChange.of(polygon, D.MEDIUM, D.FULL, 8)
        assertEquals("Lower to Less detail? This frees about 40 MB. Tiles another saved map uses stay on the phone.", detailConfirmLine(less, "Less detail", "Full detail", 40L shl 20, 0, 0))
        assertEquals("Full detail downloads about 160 MB more (1,300 tiles). The map stays at Medium detail until it is done.", detailConfirmLine(full, "Full detail", "Medium detail", 0, 160L shl 20, 1300))
        val back = io.github.jasondorjeshort.dystopo.areas.DetailChange.of(polygon.at(D.LESS), polygon.at(D.LESS), 8, current = polygon.at(D.FULL))
        assertEquals("Keep Less detail and drop the download that waits? This frees about 2.0 MB.", detailConfirmLine(back, "Less detail", "Less detail", 2L shl 20, 0, 0))
        assertEquals("On this phone now: 70 MB", detailOptionLine(now = true, pending = false, bytes = 70L shl 20, delta = 0, addedBytes = 0))
        assertEquals("About 30 MB, 40 MB less", detailOptionLine(false, false, 30L shl 20, -(40L shl 20), 0))
        assertEquals("About 82 MB, 12 MB more to download", detailOptionLine(false, false, 82L shl 20, 12L shl 20, 12L shl 20))
        assertEquals("Waiting: about 12 MB more to download", detailOptionLine(false, true, 82L shl 20, 12L shl 20, 12L shl 20))
        // The verifier's three: no zero figure, the row's formatter under 1 MB, and the area's figure the row's.
        assertEquals("Lower to Less detail? Nothing is freed: another saved map uses these tiles.", detailConfirmLine(less, "Less detail", "Full detail", 0, 0, 0, ownBytes = 3L shl 20))
        assertEquals("Lower to Less detail? Nothing is freed: none of these tiles are on this phone.", detailConfirmLine(less, "Less detail", "Full detail", 0, 0, 0, ownBytes = 0))
        assertEquals("Keep Less detail and drop the download that waits? Nothing is freed: none of these tiles are on this phone.", detailConfirmLine(back, "Less detail", "Less detail", 0, 0, 0))
        assertEquals("About 12 MB, nothing to download", detailOptionLine(false, false, 12L shl 20, 0, 0, raise = false))
        assertEquals("On this phone now: 705 kB", detailOptionLine(true, false, 705L * 1024, 0, 0))
        assertEquals("About 512 kB, 192 kB less", detailOptionLine(false, false, 512L * 1024, -192L * 1024, 0))
        assertEquals(L.sizeText(705L * 1024), detailSize(705L * 1024), "one formatter for the row and the sheet")
        for (line in listOf(detailOptionLine(false, false, 12L shl 20, 0, 0), detailConfirmLine(less, "Less detail", "Full detail", 0, 0, 0)))
            assertTrue("0.0" !in line && " 0 kB" !in line, line)
        assertEquals("Less detail", detailLabel(polygon.at(D.LESS)))
        assertEquals("Medium detail", detailLabel(polygon.at(D.MEDIUM)))
        assertEquals("Full detail", detailLabel(polygon.at(D.FULL)))
        // Over the cap a raise offers a server or a smaller shape, never less detail; the stop line names the remedy that fits.
        val cap = raiseCapRefusalLine(4_601)!!
        assertEquals("Adding this detail needs about 4,700 map tiles, and the public map source allows 3,000 a download. Make the area smaller, or set a server in Settings › Sources.", cap)
        assertNull(raiseCapRefusalLine(3_000))
        assertTrue("less detail" !in capStoppedLine(false) && "server" in capStoppedLine(false))
        assertEquals(CAP_STOPPED, capStoppedLine(true))
        for (s in listOf(cap, capStoppedLine(false), DETAIL_INTRO, LESS_SEARCH_NOTE, TRIP_DETAIL_LINE)) assertTrue("OpenFreeMap" !in s && "OpenStreetMap" !in s, s)
    }
    // end T386
}
