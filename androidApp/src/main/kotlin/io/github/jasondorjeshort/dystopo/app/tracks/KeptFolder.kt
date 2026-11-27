package io.github.jasondorjeshort.dystopo.app.tracks

import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.Foreground
import io.github.jasondorjeshort.dystopo.app.SaveFile
import io.github.jasondorjeshort.dystopo.app.safeFileStem
import io.github.jasondorjeshort.dystopo.app.tracking.Tracking
import io.github.jasondorjeshort.dystopo.model.RouteGpx
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import io.github.jasondorjeshort.dystopo.store.ShownKind
import io.github.jasondorjeshort.dystopo.store.TrackExport
import io.github.jasondorjeshort.dystopo.store.TrackSummary
import io.github.jasondorjeshort.dystopo.sync.EntityKind
import io.github.jasondorjeshort.dystopo.sync.RouteListItem
import io.github.jasondorjeshort.dystopo.sync.SyncStore
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/*
 * T380: the kept folder. The user picks a folder once ("Keep a folder" on Tracks and routes, the system's folder picker,
 * a persistable permission); from then on the app keeps one GPX file per track and per route there and never reads the
 * folder back. The layout is a format other tools read (ADR 0033; `docs/reference/android-tracks-and-sync.md` ›
 * The kept folder):
 * - names `yyyy-MM-dd HHmm <name>.gpx`, the date and time of the item's start in the phone's zone, refused characters "-";
 *   a short id (`... <first 6 letters or digits of the id>.gpx`) only when another item, or a file the app did not write,
 *   holds the name; tracks flat in the folder, routes in its `routes` folder; waypoints inside their item's file;
 * - a rename renames the file (DocumentsContract.renameDocument, never a second file); a change rewrites it;
 * - **a file is removed only by a delete read from the store**: the track's or route's tombstone in the op log (made by
 *   this phone's Delete, or by another device's, arriving by sync). An item merely missing from the store's list (a store
 *   that opened empty, failed to load, or is mid-migration) removes nothing; a store that cannot be read writes nothing;
 * - a hidden item keeps its file (the owner, 2026-11-26), and is written like any other;
 * - files the app did not write are never touched: the ledger (`files/kept-folder.json`, app-private) is the only list of
 *   what it may rewrite, rename or delete: item, document uri, the name asked and the name the folder gave, a SHA-256 of
 *   the bytes written, the item's version;
 * - a written file whose bytes no longer match the ledger's hash was edited by someone else: a rewrite overwrites it (the
 *   setup sentence says so; counted `overwrittenEdits`), a delete leaves it and lets it go from the ledger (`keptEdited`);
 * - a lost folder or permission stops the writing; the changes wait (they are worked out again from the store and the
 *   ledger) until a folder is picked again; Stop keeping releases the permission and leaves the files;
 * - work runs on screen or under a recording's notification only (ADR 0071): a change that arrives otherwise waits.
 */

/** The folder routes go in, inside the kept folder. */
internal const val ROUTES_DIR = "routes"

private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HHmm", Locale.ROOT)

/** T380: an item's start as its file name begins (`2026-11-27 0930`) in [zone]; null when unknown. Pure, tested. */
internal fun keptStamp(ms: Long, zone: ZoneId): String? = if (ms <= 0) null else STAMP.format(Instant.ofEpochMilli(ms).atZone(zone))

/** T380: the short id a name takes on a collision: the id's first six letters or digits, lower case. Pure, tested. */
internal fun shortId(id: String): String = id.filter { it.isLetterOrDigit() }.take(6).lowercase(Locale.ROOT)

/**
 * T380: the kept file's name: the start's date and time, the item's name without the characters file systems refuse (at
 * most 60), with [id] its short id after it; "Track" or "Route" for a blank name. Pure, tested.
 */
internal fun keptFileName(kind: String, createdAt: Long, name: String, zone: ZoneId, id: String? = null): String {
    val stem = safeFileStem(name, 60).ifEmpty { if (kind == "route") "Route" else "Track" }
    return listOfNotNull(keptStamp(createdAt, zone), stem, id?.let(::shortId)?.takeIf { it.isNotEmpty() }).joinToString(" ") + ".gpx"
}

/** T380: a track or route as the kept folder sees it; [version] changes whenever its file would. */
internal data class KeptItem(
    val kind: String,
    val id: String,
    val name: String,
    val createdAt: Long,
    val version: String,
    val recording: Boolean = false,
    val hidden: Boolean = false,
) {
    val key: String get() = "$kind:$id"
    val folder: String get() = if (kind == "route") ROUTES_DIR else ""
}

/** T380: one file the app wrote: [asked] is the name it asked for, [name] the one the folder gave. */
internal data class LedgerEntry(val kind: String, val id: String, val uri: String, val asked: String, val name: String, val hash: String, val version: String) {
    val key: String get() = "$kind:$id"
    val folder: String get() = if (kind == "route") ROUTES_DIR else ""

    fun json(): Map<String, Any?> = linkedMapOf("kind" to kind, "id" to id, "uri" to uri, "asked" to asked, "name" to name, "hash" to hash, "version" to version)

    companion object {
        fun of(m: Map<*, *>): LedgerEntry? = runCatching {
            LedgerEntry(m["kind"] as String, m["id"] as String, m["uri"] as String, m["asked"] as String, m["name"] as String, m["hash"] as String, m["version"] as String)
        }.getOrNull()
    }
}

/** T380: what a pass does: write a new file, update (rename and/or rewrite) one the app wrote, remove one on a read delete. */
internal sealed class KeptStep {
    data class Create(val item: KeptItem, val name: String, val withId: String) : KeptStep()
    data class Update(val item: KeptItem, val entry: LedgerEntry, val rename: String?, val withId: String, val rewrite: Boolean) : KeptStep()

    /** [by] "user" (this phone's delete) or "peer" (another device's, by sync). */
    data class Remove(val entry: LedgerEntry, val by: String) : KeptStep()
}

/**
 * T380: the steps that bring the folder to the store. [items] null (the store could not be read) plans nothing.
 * [tombstone] answers the deleting device of a delete the store holds for (kind, id), or null; a ledger entry whose item
 * is absent is removed only when it answers. Names: every written file keeps the name it has; a new or renamed item whose
 * name another item holds takes its short id. Recording tracks wait for their end. Pure, tested.
 */
internal fun keptPlan(items: List<KeptItem>?, ledger: List<LedgerEntry>, tombstone: (String, String) -> String?, deviceId: String, zone: ZoneId): List<KeptStep> {
    if (items == null) return emptyList()
    val byKey = ledger.associateBy { it.key }
    val taken = HashMap<String, HashMap<String, String>>() // folder -> lower-case asked name -> item key
    for (e in ledger) taken.getOrPut(e.folder) { HashMap() }[e.asked.lowercase(Locale.ROOT)] = e.key
    val steps = ArrayList<KeptStep>()
    val present = HashSet<String>()
    for (it in items.sortedWith(compareBy<KeptItem>({ it.createdAt }, { it.id }))) {
        present += it.key
        if (it.recording) continue
        val names = taken.getOrPut(it.folder) { HashMap() }
        val base = keptFileName(it.kind, it.createdAt, it.name, zone)
        val withId = keptFileName(it.kind, it.createdAt, it.name, zone, it.id)
        val owner = names[base.lowercase(Locale.ROOT)]
        val want = if (owner == null || owner == it.key) base else withId
        val entry = byKey[it.key]
        if (entry == null) {
            names[want.lowercase(Locale.ROOT)] = it.key
            steps += KeptStep.Create(it, want, withId)
        } else {
            val rename = want.takeIf { w -> w != entry.asked }
            if (rename != null) {
                if (names[entry.asked.lowercase(Locale.ROOT)] == it.key) names.remove(entry.asked.lowercase(Locale.ROOT))
                names[rename.lowercase(Locale.ROOT)] = it.key
            }
            val rewrite = it.version != entry.version
            if (rename != null || rewrite) steps += KeptStep.Update(it, entry, rename, withId, rewrite)
        }
    }
    for (e in ledger) if (e.key !in present) {
        val device = tombstone(e.kind, e.id) ?: continue // no delete read: the file stays
        steps += KeptStep.Remove(e, if (device == deviceId) "user" else "peer")
    }
    return steps
}

/** T380: the folder as the passes use it (the system's documents provider on the phone; a fake in the tests). Uris are strings. */
internal interface KeptDocs {
    /** Whether the folder is there and the permission still held. */
    fun reachable(): Boolean

    /** The document a file of [sub] ("" or [ROUTES_DIR]) is created in; makes the routes folder when it is missing. */
    fun folderOf(sub: String): String

    /** A new file [name] holding [bytes]: its uri and the name the folder gave it. */
    fun create(parent: String, name: String, bytes: ByteArray): Pair<String, String>

    /** The file's bytes, or null when it is gone. */
    fun read(uri: String): ByteArray?

    fun write(uri: String, bytes: ByteArray)

    /** Renames the file: its (perhaps new) uri and the name the folder gave; null when it is gone. */
    fun rename(uri: String, name: String): Pair<String, String>?

    fun delete(uri: String): Boolean
}

/** T380: what the passes did, kept with the ledger (`GET /tracks/ui` `keptFolder`). */
internal class KeptCounts {
    var writes = 0
    var renames = 0
    var deletedByUser = 0
    var deletedByPeer = 0
    var overwrittenEdits = 0
    var keptEdited = 0
    var recreated = 0
    var errors = 0
    var lastError: String? = null

    fun json(): Map<String, Any?> = linkedMapOf(
        "writes" to writes, "renames" to renames, "deletedByUser" to deletedByUser, "deletedByPeer" to deletedByPeer,
        "overwrittenEdits" to overwrittenEdits, "keptEdited" to keptEdited, "recreated" to recreated, "errors" to errors, "lastError" to lastError,
    )

    fun read(m: Map<*, *>) {
        fun n(k: String) = (m[k] as? Number)?.toInt() ?: 0
        writes = n("writes"); renames = n("renames"); deletedByUser = n("deletedByUser"); deletedByPeer = n("deletedByPeer")
        overwrittenEdits = n("overwrittenEdits"); keptEdited = n("keptEdited"); recreated = n("recreated"); errors = n("errors")
        lastError = m["lastError"] as? String
    }
}

/** T380: a pass's outcome: steps done, steps left (failed or not reached), and whether the folder was lost. */
internal data class KeptPass(val done: Int, val left: Int, val lost: Boolean)

internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/**
 * T380: runs [steps] against [docs], keeping [ledger] (the files the app wrote) and [counts]. Nothing is done when the
 * folder cannot be reached; a step that fails while the folder is gone ends the pass as lost, else it is counted and
 * left for the next pass. [gpx] makes an item's bytes (null: it went meanwhile, the step is skipped). Tested with a fake.
 */
internal fun keptApply(steps: List<KeptStep>, ledger: MutableList<LedgerEntry>, docs: KeptDocs, gpx: (KeptItem) -> ByteArray?, counts: KeptCounts): KeptPass {
    if (steps.isEmpty()) return KeptPass(0, 0, false)
    if (!docs.reachable()) return KeptPass(0, steps.size, true)
    var done = 0
    var failed = 0
    fun put(e: LedgerEntry) {
        val i = ledger.indexOfFirst { it.key == e.key }
        if (i >= 0) ledger[i] = e else ledger += e
    }
    fun create(item: KeptItem, name: String, withId: String, bytes: ByteArray): LedgerEntry {
        var (uri, given) = docs.create(docs.folderOf(item.folder), name, bytes)
        if (given != name && name != withId) docs.rename(uri, withId)?.let { uri = it.first; given = it.second } // a file the app did not write holds the name
        return LedgerEntry(item.kind, item.id, uri, name, given, sha256(bytes), item.version)
    }
    for ((i, s) in steps.withIndex()) {
        try {
            when (s) {
                is KeptStep.Create -> {
                    val bytes = gpx(s.item) ?: continue
                    put(create(s.item, s.name, s.withId, bytes))
                    counts.writes++
                }
                is KeptStep.Update -> {
                    var e: LedgerEntry? = s.entry
                    if (s.rename != null) {
                        val r = try { docs.rename(s.entry.uri, s.rename) } catch (x: Exception) {
                            if (!docs.reachable()) throw x
                            docs.rename(s.entry.uri, s.withId) // the folder refused the name (a file it holds): the short id
                        }
                        e = r?.let { (u, n0) ->
                            var u1 = u
                            var n = n0
                            if (n != s.rename && s.rename != s.withId) docs.rename(u1, s.withId)?.let { u1 = it.first; n = it.second }
                            counts.renames++
                            s.entry.copy(uri = u1, asked = s.rename, name = n)
                        }
                    }
                    if (e == null) { // the file went (someone removed it): written again under the name it wants
                        val bytes = gpx(s.item) ?: continue
                        put(create(s.item, s.rename ?: s.entry.asked, s.withId, bytes))
                        counts.recreated++
                        counts.writes++
                    } else {
                        if (s.rewrite) {
                            val bytes = gpx(s.item) ?: continue
                            val h = sha256(bytes)
                            if (h != e.hash) {
                                val now = docs.read(e.uri)
                                if (now == null) {
                                    e = create(s.item, e.asked, s.withId, bytes)
                                    counts.recreated++
                                } else {
                                    if (sha256(now) != e.hash) counts.overwrittenEdits++ // edited by someone else: the setup sentence says it is overwritten
                                    docs.write(e.uri, bytes)
                                }
                                counts.writes++
                            }
                            e = e.copy(hash = h, version = s.item.version)
                        }
                        put(e)
                    }
                }
                is KeptStep.Remove -> {
                    val now = docs.read(s.entry.uri)
                    when {
                        now == null -> Unit // already gone
                        sha256(now) == s.entry.hash -> {
                            docs.delete(s.entry.uri)
                            if (s.by == "user") counts.deletedByUser++ else counts.deletedByPeer++
                        }
                        else -> counts.keptEdited++ // someone else's edit: the file stays, the ledger lets it go
                    }
                    ledger.removeAll { it.key == s.entry.key }
                }
            }
            done++
        } catch (x: Exception) {
            if (!docs.reachable()) return KeptPass(done, steps.size - i + failed, true)
            failed++
            counts.errors++
            counts.lastError = (x.message ?: x.javaClass.simpleName)
        }
    }
    return KeptPass(done, failed, false)
}

/**
 * T380: the kept folder's own state, persisted as one JSON file ([json]): the folder, the ledger, the counts, and whether
 * the folder was lost. Per device, never synced.
 */
internal class KeptState {
    var tree: String? = null
    var folderName: String? = null
    var routesDir: String? = null
    var lost = false

    /** The folder Stop keeping let go of: picked again, its ledger still holds (the files are rewritten in place, not doubled). */
    var stoppedTree: String? = null
    val entries = ArrayList<LedgerEntry>()
    val counts = KeptCounts()

    /** A folder picked and confirmed: the one kept or let go keeps its ledger, another starts an empty one; returns the folder let go, if another. */
    fun keep(uri: String, name: String): String? {
        val old = tree?.takeIf { it != uri }
        if (uri != tree && uri != stoppedTree) {
            entries.clear()
            routesDir = null
        }
        tree = uri
        folderName = name
        stoppedTree = null
        lost = false
        return old
    }

    /** Stop keeping: the folder forgotten, its files and ledger left (for the same folder picked again); returns the folder. */
    fun stop(): String? {
        val t = tree
        if (t != null) stoppedTree = t
        tree = null
        lost = false
        return t
    }

    fun json(): Map<String, Any?> = linkedMapOf(
        "version" to 1, "tree" to tree, "folderName" to folderName, "routesDir" to routesDir, "lost" to lost, "stoppedTree" to stoppedTree,
        "entries" to entries.map { it.json() }, "counts" to counts.json(),
    )

    companion object {
        fun of(m: Map<*, *>): KeptState = KeptState().apply {
            tree = m["tree"] as? String
            folderName = m["folderName"] as? String
            routesDir = m["routesDir"] as? String
            lost = m["lost"] == true
            stoppedTree = m["stoppedTree"] as? String
            (m["entries"] as? List<*>)?.forEach { e -> (e as? Map<*, *>)?.let(LedgerEntry::of)?.let(entries::add) }
            (m["counts"] as? Map<*, *>)?.let(counts::read)
        }
    }
}

/** T380: a track's version: what its file holds changes only when one of these does. Pure. */
internal fun trackVersion(t: TrackSummary): String = listOf(t.name, t.updatedAt, t.points, t.waypoints, t.activity?.name).joinToString("|")

/** T380: a route's version. Pure. */
internal fun routeVersion(r: RouteListItem): String =
    listOf(r.name, r.updatedAt, r.distanceM, r.waypoints, r.viaPoints, r.mode, r.tripKind, r.ascentM, r.descentM).joinToString("|")

// ---- the words (ADR 0031: the owner's to adjust) ----

internal const val KEEP_FOLDER = "Keep a folder"
internal const val KEPT_OFF_LINE = "A GPX copy of every track and route, in a folder you pick"
internal const val KEPT_SETUP = "Every track and route on this phone is written to this folder as a GPX file, and each new recording when it ends. " +
    "Other apps, such as those that sync folders, can read it. The app only writes there: a change made to one of its files is overwritten, " +
    "and files it did not write are left alone."
internal const val KEEP_THIS = "Keep this folder"
internal const val STOP_KEEPING = "Stop keeping"
internal const val PICK_AGAIN = "Pick it again"

private fun count(n: Int, one: String) = "$n $one" + if (n == 1) "" else "s"

/** T380: the confirm's count after the pick ("3 tracks and 1 route will be written to Tracks."); [again]: the same folder picked again. Pure, tested. */
internal fun keptCount(tracks: Int, routes: Int, folder: String, again: Boolean = false, changes: Int = 0): String = when {
    again && changes > 0 -> "${count(changes, "change")} will be written to $folder."
    again -> "Nothing waits; new recordings will be written to $folder."
    tracks + routes == 0 -> "Nothing to write yet; new recordings will be written to $folder."
    else -> listOfNotNull(count(tracks, "track").takeIf { tracks > 0 }, count(routes, "route").takeIf { routes > 0 }).joinToString(" and ") +
        " will be written to $folder."
}

/** T380: the row while a folder is kept ("Kept in Tracks · 12 files · 2 to write"). Pure, tested. */
internal fun keptLine(folder: String, files: Int, waiting: Int): String =
    "Kept in $folder · ${count(files, "file")}" + if (waiting > 0) " · $waiting to write" else ""

/** T380: the line when the folder or its permission is lost. Pure, tested. */
internal fun keptLost(folder: String, waiting: Int): String =
    "The folder $folder can't be reached, so nothing is written there. " +
        when (waiting) { 0 -> "Nothing is written until you pick it again."; 1 -> "1 change waits until you pick it again."; else -> "$waiting changes wait until you pick it again." }

/** T380: the lines after Stop keeping and after a first write. Pure, tested. */
internal fun keptStopped(folder: String): String = "Stopped keeping $folder. Its files stay there."
internal fun keptWrote(n: Int, folder: String): String = "Wrote ${count(n, "GPX file")} to $folder"

/** T380: the help beside the row (declared with the row's icon, `TracksScreen.kt`'s list). */
internal const val KEPT_HELP = "One GPX file per track and route, named by date and time, then name; routes go in its routes folder. " +
    "A rename renames the file; a delete, here or on a device you sync with, deletes it. A hidden one keeps its file. " +
    "Edits to its files are overwritten. Stop keeping leaves the files. Nothing is written off screen unless recording."

/** T380: the Tracks screen's row state (main thread, Compose). */
internal data class KeptUi(
    val mode: String = "off", // off, keeping, lost
    val folderName: String? = null,
    val files: Int = 0,
    val waiting: Int = 0,
    val ask: KeptAsk? = null,
)

/** T380: the row's Compose state, made and changed on the main thread (T240: a `*UiState` object). */
internal object KeptUiState {
    var ui by mutableStateOf(KeptUi())
}

/** T380: the confirm after a pick: the folder, what would be written. */
internal data class KeptAsk(val tree: String, val folderName: String, val tracks: Int, val routes: Int, val again: Boolean, val changes: Int)

/**
 * T380: the kept folder on the phone: the ledger's file, the one worker thread that writes, the gate (ADR 0071: on screen
 * or under the recording's notification), the system's documents provider, and the row's state. The change signal is
 * [changed], from the tracker's list refresh and the routes' (one line each in `Tracking.kt` and `Trips.kt`), and the
 * screen coming back.
 */
object KeptFolder {
    private const val TAG = "DystopoKeptFolder"
    private const val FILE = "kept-folder.json"
    private const val DELAY_MS = 1500L

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "dystopo-kept-folder").apply { isDaemon = true } }
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private var loaded: KeptState? = null
    private val state: KeptState get() = loaded ?: load().also { loaded = it }

    /** The row's state: Compose state, read and written on the main thread only ([KeptUiState]). */
    internal val ui: KeptUi get() = KeptUiState.ui

    /** The confirm, mirrored for the worker's JSON (which never touches Compose state). */
    @Volatile private var askNow: KeptAsk? = null

    private fun setUi(u: KeptUi) { // main thread
        KeptUiState.ui = u
        askNow = u.ask
    }

    @Volatile private var started = false
    @Volatile private var scheduled = false
    @Volatile private var tracksPrint = 0
    @Volatile private var routesPrint = 0
    @Volatile private var waiting = 0
    @Volatile private var waitingFor: String? = null
    @Volatile private var passes = 0
    @Volatile private var lastPassMs: Long? = null
    @Volatile private var pickedWrote: String? = null

    /** The gate (ADR 0071): an activity on screen, or the recording's notification up. */
    private fun gateOpen(): Boolean = Foreground.started || "recording" in Foreground.notifiedNow

    /**
     * T380: the change signal: [tracks] from the tracker's list refresh, [routes] from the routes' (any thread; cheap: a
     * pass is scheduled only when what the folder holds may have changed).
     */
    fun changed(tracks: List<TrackSummary>? = null, routes: List<RouteListItem>? = null) {
        ensureStarted()
        var any = false
        tracks?.let { l ->
            val p = l.map { if (it.recording) "r:${it.id}" else trackVersion(it) + it.id }.hashCode()
            if (p != tracksPrint) { tracksPrint = p; any = true }
        }
        routes?.let { l ->
            val p = l.hashCode()
            if (p != routesPrint) { routesPrint = p; any = true }
        }
        if (any) schedule()
    }

    private fun ensureStarted() {
        if (started) return
        started = true
        MainScope().launch {
            Foreground.flow.collect { on -> if (on) schedule() } // the screen back: what waited is written
        }
        schedule()
    }

    private fun schedule() {
        if (scheduled) return
        scheduled = true
        main.postDelayed({
            scheduled = false
            worker.execute { runCatching { pass() }.onFailure { Log.w(TAG, "pass", it) } }
        }, DELAY_MS)
    }

    private fun file(): File = File(DystopoApp.instance.filesDir, FILE)

    private fun load(): KeptState = runCatching {
        val f = file()
        if (f.exists()) KeptState.of(MiniJson.parseObject(f.readText())) else KeptState()
    }.getOrElse {
        Log.w(TAG, "the ledger did not read; keeping nothing until a folder is picked", it)
        KeptState()
    }

    private fun save() {
        val f = file()
        val tmp = File(f.parentFile, "$FILE.tmp")
        tmp.writeText(MiniJson.encode(state.json()))
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    // ---- the store, as the passes read it ----

    private class Source(val tracking: Tracking) {
        val store get() = tracking.store as SyncStore
        val deviceId: String get() = tracking.call { store.deviceId }

        /** Every track and route (hidden ones too), or null when the store cannot be read. */
        fun items(): List<KeptItem>? = runCatching {
            tracking.call {
                val hiddenT = store.hidden(ShownKind.TRACK)
                val hiddenR = store.hidden(ShownKind.ROUTE)
                store.list().map { KeptItem("track", it.id, it.name, it.createdAt, trackVersion(it), it.recording, it.id in hiddenT) } +
                    store.routeList().map { KeptItem("route", it.id, it.name.ifBlank { io.github.jasondorjeshort.dystopo.app.trips.Trips.UNNAMED }, if (it.createdAt > 0) it.createdAt else it.updatedAt, routeVersion(it), hidden = it.id in hiddenR) }
            }
        }.onFailure { Log.w(TAG, "the store did not read: nothing is planned", it) }.getOrNull()

        fun tombstone(kind: String, id: String): String? =
            tracking.call { store.tombstoneOf(if (kind == "route") EntityKind.ROUTE else EntityKind.TRACK, id)?.device }

        fun gpx(item: KeptItem): ByteArray? = runCatching {
            if (item.kind == "route") tracking.call { store.routePlan(item.id) }?.let { RouteGpx.gpx(it) }
            else tracking.call { TrackExport.gpx(store, item.id) }
        }.getOrNull()?.toByteArray(Charsets.UTF_8)
    }

    private fun plan(s: KeptState, src: Source): List<KeptStep> =
        keptPlan(src.items(), s.entries, src::tombstone, src.deviceId, ZoneId.systemDefault())

    /** One pass on the worker: plan, and write when the folder is there and the gate is open. */
    private fun pass() {
        val s = state
        val tracking = Tracking.readyOrNull
        if (s.tree == null || tracking == null) { waiting = 0; waitingFor = null; publish(); return }
        if (!gateOpen()) { waitingFor = "the screen"; publish(); return } // ADR 0071: not even the store is read unseen
        val src = Source(tracking)
        val steps = plan(s, src)
        waiting = steps.size
        waitingFor = when {
            s.lost -> "a folder picked again"
            !gateOpen() -> "the screen"
            else -> null
        }
        if (waitingFor != null || steps.isEmpty()) { publish(); return }
        val before = s.counts.writes
        val r = keptApply(steps, s.entries, Saf(Uri.parse(s.tree), s), src::gpx, s.counts)
        passes++
        lastPassMs = System.currentTimeMillis()
        waiting = r.left
        if (r.lost) {
            s.lost = true
            waitingFor = "a folder picked again"
            s.counts.lastError = "the folder can't be reached"
        }
        save()
        publish()
        val wrote = s.counts.writes - before
        if (pickedWrote != null && wrote > 0) {
            val name = pickedWrote!!
            pickedWrote = null
            Tracking.readyOrNull?.refreshUi(keptWrote(wrote, name))
        }
    }

    private fun publish() {
        val s = state
        val u = KeptUi(
            mode = when { s.tree == null -> "off"; s.lost -> "lost"; else -> "keeping" },
            folderName = s.folderName, files = s.entries.size, waiting = waiting,
        )
        main.post { setUi(u.copy(ask = ui.ask)) }
    }

    // ---- the user's actions (main thread) ----

    /** The folder picker's answer: the permission taken, then the confirm with its count. Null: cancelled. */
    internal fun picked(uri: Uri?) {
        if (uri == null) return
        ensureStarted()
        worker.execute {
            val app = DystopoApp.instance
            runCatching { app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                .onFailure { Log.w(TAG, "no persistable permission for $uri", it) }
            val s = state
            val tree = uri.toString()
            val again = tree == s.tree || tree == s.stoppedTree
            val tracking = Tracking.readyOrNull
            val items = tracking?.let { Source(it).items() }.orEmpty()
            val changes = if (again && tracking != null) {
                val src = Source(tracking)
                keptPlan(src.items(), s.entries, src::tombstone, src.deviceId, ZoneId.systemDefault()).size
            } else 0
            val a = KeptAsk(tree, folderName(uri), items.count { it.kind == "track" }, items.count { it.kind == "route" }, again, changes)
            main.post { setUi(ui.copy(ask = a)) }
        }
    }

    /** The confirm's answer: [yes] keeps the folder and writes; no lets go of a folder that is not the one kept. */
    internal fun confirm(yes: Boolean) {
        val a = ui.ask ?: return
        setUi(ui.copy(ask = null))
        worker.execute {
            val s = state
            if (yes) {
                s.keep(a.tree, a.folderName)?.let(::release)
                pickedWrote = a.folderName
                save()
                pass()
            } else if (a.tree != s.tree) {
                release(a.tree)
                publish()
            }
        }
    }

    /** Stop keeping: the permission released, the files left. */
    internal fun stop() {
        worker.execute {
            val s = state
            val name = s.folderName ?: return@execute
            s.stop()?.let(::release)
            waiting = 0
            save()
            publish()
            Tracking.readyOrNull?.refreshUi(keptStopped(name))
        }
    }

    /** Debug (`POST /tracks/keptfolder?tree=`): keeps [uri] with no picker and no confirm; a permission it was not granted reads as lost. */
    internal fun keepNow(uri: Uri): Map<String, Any?> = onWorker {
        runCatching { DystopoApp.instance.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        state.keep(uri.toString(), folderName(uri))?.let(::release)
        save()
        pass()
        jsonNow()
    }

    /** Debug (`POST /tracks/keptfolder?pass=1`): a pass now, then the state. */
    internal fun passNow(): Map<String, Any?> = onWorker { pass(); jsonNow() }

    private fun release(tree: String) {
        runCatching {
            DystopoApp.instance.contentResolver.releasePersistableUriPermission(Uri.parse(tree), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }.onFailure { Log.w(TAG, "release $tree", it) }
    }

    /** The folder's name as the provider gives it; else the last part of its path. */
    private fun folderName(uri: Uri): String {
        val fallback = runCatching { DocumentsContract.getTreeDocumentId(uri).substringAfterLast('/').substringAfterLast(':') }.getOrNull()?.ifBlank { null } ?: "the folder"
        return runCatching {
            val doc = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
            DystopoApp.instance.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()?.ifBlank { null } ?: fallback
    }

    private fun <T> onWorker(block: () -> T): T = worker.submit(Callable(block)).get(60, TimeUnit.SECONDS)

    /** `GET /tracks/ui` `keptFolder`. */
    internal fun json(): Map<String, Any?> = runCatching { onWorker { jsonNow() } }.getOrElse { mapOf("error" to (it.message ?: it.javaClass.simpleName)) }

    private fun jsonNow(): Map<String, Any?> {
        val s = state
        val ask = askNow
        return linkedMapOf(
            "state" to when { s.tree == null -> "off"; s.lost -> "lost"; else -> "keeping" },
            "folder" to s.tree, "folderName" to s.folderName, "routesDir" to s.routesDir, "stoppedFolder" to s.stoppedTree,
            "files" to s.entries.size, "tracks" to s.entries.count { it.kind == "track" }, "routes" to s.entries.count { it.kind == "route" },
            "waiting" to waiting, "waitingFor" to waitingFor, "gate" to linkedMapOf("open" to gateOpen(), "why" to Foreground.workWhy()),
            "passes" to passes, "lastPassMs" to lastPassMs,
            "entries" to s.entries.map { linkedMapOf("kind" to it.kind, "id" to it.id, "name" to it.name, "asked" to it.asked, "folder" to it.folder) },
            "ask" to ask?.let { linkedMapOf("folderName" to it.folderName, "tracks" to it.tracks, "routes" to it.routes, "again" to it.again, "changes" to it.changes, "count" to keptCount(it.tracks, it.routes, it.folderName, it.again, it.changes)) },
            "row" to when {
                ask != null -> KEPT_SETUP
                s.tree == null -> KEPT_OFF_LINE
                s.lost -> keptLost(s.folderName ?: "", waiting)
                else -> keptLine(s.folderName ?: "", s.entries.size, waiting)
            },
        ) + s.counts.json()
    }

    /** The system's documents provider under the kept tree. */
    private class Saf(val tree: Uri, val state: KeptState) : KeptDocs {
        private val resolver = DystopoApp.instance.contentResolver
        private val rootId = DocumentsContract.getTreeDocumentId(tree)
        private val root = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)

        private fun exists(doc: Uri): Boolean = runCatching {
            resolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { it.moveToFirst() } ?: false
        }.getOrDefault(false)

        override fun reachable(): Boolean =
            resolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission } && exists(root)

        override fun folderOf(sub: String): String {
            if (sub.isEmpty()) return root.toString()
            state.routesDir?.let { if (exists(Uri.parse(it))) return it }
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, rootId)
            val found = resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { c ->
                var id: String? = null
                while (id == null && c.moveToNext()) if (c.getString(1) == sub && c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) id = c.getString(0)
                id
            }
            val dir = found?.let { DocumentsContract.buildDocumentUriUsingTree(tree, it) }
                ?: checkNotNull(DocumentsContract.createDocument(resolver, root, DocumentsContract.Document.MIME_TYPE_DIR, sub)) { "the folder refused the $sub folder" }
            state.routesDir = dir.toString()
            return dir.toString()
        }

        private fun nameOf(doc: Uri): String =
            resolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: ""

        override fun create(parent: String, name: String, bytes: ByteArray): Pair<String, String> {
            val doc = checkNotNull(DocumentsContract.createDocument(resolver, Uri.parse(parent), SaveFile.MIME, name)) { "the folder refused the file" }
            try {
                checkNotNull(resolver.openOutputStream(doc, "wt")) { "the file did not open" }.use { it.write(bytes) }
            } catch (e: Exception) {
                runCatching { DocumentsContract.deleteDocument(resolver, doc) }
                throw e
            }
            return doc.toString() to nameOf(doc)
        }

        override fun read(uri: String): ByteArray? {
            val doc = Uri.parse(uri)
            if (!exists(doc)) return null
            return try { resolver.openInputStream(doc)?.use { it.readBytes() } } catch (e: FileNotFoundException) { null }
        }

        override fun write(uri: String, bytes: ByteArray) {
            checkNotNull(resolver.openOutputStream(Uri.parse(uri), "wt")) { "the file did not open" }.use { it.write(bytes) }
        }

        override fun rename(uri: String, name: String): Pair<String, String>? {
            val doc = Uri.parse(uri)
            if (!exists(doc)) return null
            val to = DocumentsContract.renameDocument(resolver, doc, name) ?: doc
            return to.toString() to nameOf(to)
        }

        override fun delete(uri: String): Boolean = DocumentsContract.deleteDocument(resolver, Uri.parse(uri))
    }
}
