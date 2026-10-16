package io.github.jasondorjeshort.dystopo.app.sync

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.tracking.Tracking
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import io.github.jasondorjeshort.dystopo.store.SqlTrackStore
import io.github.jasondorjeshort.dystopo.sync.ApplyResult
import io.github.jasondorjeshort.dystopo.sync.Bundle
import io.github.jasondorjeshort.dystopo.sync.CompactResult
import io.github.jasondorjeshort.dystopo.sync.FileSyncTransport
import io.github.jasondorjeshort.dystopo.sync.HttpSyncTransport
import io.github.jasondorjeshort.dystopo.sync.Op
import io.github.jasondorjeshort.dystopo.sync.SkewLog
import io.github.jasondorjeshort.dystopo.sync.Sync
import io.github.jasondorjeshort.dystopo.sync.SyncReplica
import io.github.jasondorjeshort.dystopo.sync.SyncReport
import io.github.jasondorjeshort.dystopo.sync.SyncServer
import io.github.jasondorjeshort.dystopo.sync.SyncSnapshot
import io.github.jasondorjeshort.dystopo.sync.SyncTransport
import io.github.jasondorjeshort.dystopo.sync.VersionVector
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Sync on the phone (T44, T51). The replica is the app's track store ([Tracking.store], a
 * [SqlTrackStore] over `tracks.db`); every store call runs on the tracking thread through
 * [Tracking.call], so a sync never interleaves with a recorded fix.
 *
 * Peers: an HTTP peer (`http://host:port`, the devtool server or any [SyncServer]) or a `.dsync` file
 * in `files/sync/` (a plain name; passed between devices with adb, USB or a file share). The default
 * peer for "Sync now" is the `peer` setting, else the online server ([AppSettings.server]), which is
 * the always-on peer.
 *
 * T51: HTTP syncs go in pages of [Sync.DEFAULT_PAGE_OPS] operations each way (each page is one tracking
 * thread call, so recording carries on between pages); the last sync, the peer's vector (for the
 * pending count) and the fields edited on two devices ([conflicts], the "edited on two devices" notes)
 * are kept across restarts; synced app settings are applied after a merge ([SettingsSync]).
 *
 * T57: [replica] is also what the Share screen's LAN listener serves and its connect syncs with ([run] does
 * the post-merge steps for both: conflicts, synced settings, the result line, the map). Every sync compares
 * clocks with the peer ([ClockCheck]: the server's `Date` header, a nearby phone's `now`) and warns when they
 * differ by minutes or more.
 */
class AppSync private constructor(private val app: DystopoApp) {
    private val tracking = Tracking.of(app)
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Where `.dsync` files are read and written. */
    val filesDir: File get() = File(app.filesDir, "sync").apply { mkdirs() }

    @Volatile var running: Boolean = false
        private set
    @Volatile var last: Map<String, Any?>? = null
        private set
    @Volatile var syncs = 0L
        private set

    /** Fields edited on two devices, newest first, as JSON maps ([io.github.jasondorjeshort.dystopo.sync.Conflict.json] plus `seenAt`); observable by Compose. */
    var conflicts by mutableStateOf<List<Map<String, Any?>>>(emptyList())
        private set

    /** Conflicts found since install (the list keeps the newest [MAX_CONFLICTS]). */
    @Volatile var conflictsTotal = 0L
        private set

    private val conflictsFile: File get() = File(filesDir, "conflicts.json")

    init {
        last = prefs.getString(KEY_LAST, null)?.let { runCatching { MiniJson.parseObject(it) }.getOrNull() }
        runCatching {
            if (conflictsFile.isFile) {
                val o = MiniJson.parseObject(conflictsFile.readText())
                @Suppress("UNCHECKED_CAST")
                conflicts = (o["conflicts"] as? List<Map<String, Any?>>).orEmpty()
                conflictsTotal = (o["total"] as? Number)?.toLong() ?: conflicts.size.toLong()
            }
        }
    }

    /** The peer setting ("" when unset). */
    val peerSetting: String get() = prefs.getString(KEY_PEER, "") ?: ""

    /** The peer "Sync now" uses: the setting, else the online server; "" when neither is set. */
    val defaultPeer: String get() = peerSetting.ifBlank { AppSettings.server }

    fun setPeer(peer: String) = prefs.edit().putString(KEY_PEER, peer.trim()).apply()

    /** Bearer token for an HTTP peer that requires one (the `server` module's `/sync` gate, T52); "" for none. Never reported back. */
    val syncToken: String get() = prefs.getString(KEY_TOKEN, "") ?: ""

    fun setSyncToken(token: String) = prefs.edit().putString(KEY_TOKEN, token.trim()).apply()

    private val store: SqlTrackStore get() = tracking.store as SqlTrackStore

    /** The store seen through the tracking thread (T57: shared with the Share screen's listener and connect). */
    val replica: SyncReplica = object : SyncReplica {
        override val deviceId: String get() = store.deviceId
        override fun vector(): VersionVector = tracking.call { store.vector() }
        override fun opsSince(since: VersionVector): List<Op> = tracking.call { store.opsSince(since) }
        override fun bundle(since: VersionVector): Bundle = tracking.call { store.bundle(since) }
        override fun page(since: VersionVector, maxOps: Int, maxBytes: Int): Bundle = tracking.call { store.page(since, maxOps, maxBytes) }
        override fun apply(bundle: Bundle, now: Long): ApplyResult = tracking.call { store.apply(bundle, now) }
        override fun snapshot(): SyncSnapshot = tracking.call { store.snapshot() }
        override fun compact(now: Long, retentionMs: Long): CompactResult = tracking.call { store.compact(now, retentionMs) }
        override fun pendingFor(peer: VersionVector): Long = tracking.call { store.pendingFor(peer) }
        override val opCount: Long get() = tracking.call { store.opCount }
        override val skewLog: SkewLog get() = tracking.call { store.skewLog }
    }

    /**
     * An HTTP peer for a URL, else a file in [filesDir] (`.dsync` added when the name has no extension). T57:
     * HTTP replies' `Date` headers go to [clock].
     */
    fun transport(peer: String, clock: ClockSamples? = null): SyncTransport {
        if (peer.startsWith("http://") || peer.startsWith("https://")) return HttpSyncTransport(peer, { u, b -> post(u, b, syncToken, clock) }, gzip, gunzip)
        val name = peer.removePrefix("file:")
        require(name.isNotEmpty() && '/' !in name && '\\' !in name && name != "." && name != "..") { "a file peer is a plain name in ${filesDir.path}" }
        val f = File(filesDir, if ('.' in name) name else "$name.dsync")
        return FileSyncTransport("file:${f.name}", { f.takeIf { it.isFile }?.readBytes() }, { f.writeBytes(it) }, gzip, gunzip)
    }

    /**
     * One sync with [peer] (default: [defaultPeer]), in pages of [pageOps] operations (0: one bundle each
     * way); returns the report as JSON. Throws on failure (also kept in [last]).
     */
    fun syncNow(peer: String? = null, pageOps: Int = Sync.DEFAULT_PAGE_OPS): Map<String, Any?> {
        val target = peer?.takeIf { it.isNotBlank() } ?: defaultPeer
        require(target.isNotBlank()) { "no sync peer: pass with=<url|file> or set one (POST /sync/settings?peer=)" }
        val samples = ClockSamples()
        val transport = transport(target, samples)
        return run(target, shortName(target), transport, pageOps) { ClockCheck.of(samples.all(), shortName(target), System.currentTimeMillis()) }
    }

    /**
     * T57: one exchange with [transport] (the default peer's, or a nearby phone's over the LAN link) and what
     * follows a merge: the peer's vector, conflicts, synced settings, the clock check ([clock], read after the
     * exchange), the remembered result and the line in the tracks list. [target] keys the peer's vector,
     * [label] names the peer to the user. Throws on failure (also kept in [last]).
     */
    fun run(target: String, label: String, transport: SyncTransport, pageOps: Int, clock: () -> ClockCheck?): Map<String, Any?> {
        synchronized(this) {
            running = true
            SyncUi.refresh()
            try {
                val r: SyncReport = Sync.exchange(replica, transport, System::currentTimeMillis, SystemClock::elapsedRealtime, pageOps)
                syncs++
                r.peerVector?.let { prefs.edit().putString(KEY_PEER_VECTOR + target, it.text).apply() }
                if (r.merged.conflicts.isNotEmpty()) addConflicts(r)
                val settings = if (r.merged.applied > 0) runCatching { SettingsSync.pull(app) }.getOrElse { mapOf("error" to it.message) } else null
                val check = runCatching(clock).getOrNull()
                check?.let { rememberClock(it) }
                val json = linkedMapOf<String, Any?>("ok" to true, "peer" to target, "at" to System.currentTimeMillis()) + r.json() +
                    mapOf("settings" to settings, "clock" to check?.json())
                remember(json)
                val m = r.merged
                tracking.refreshUi(
                    "Synced with $label: ${m.applied} in, ${r.pushed} out" +
                        (if (m.conflicts.isNotEmpty()) ", ${m.conflicts.size} edited on two devices" else "") +
                        (check?.message()?.let { ". $it" } ?: ""),
                )
                if (m.applied > 0) tracking.refreshOverlay(force = true)
                return json
            } catch (e: Exception) {
                Log.w(TAG, "sync with $target failed", e)
                remember(linkedMapOf("ok" to false, "peer" to target, "at" to System.currentTimeMillis(), "error" to "${e.javaClass.simpleName}: ${e.message}"))
                tracking.refreshUi("Sync failed: ${e.message}")
                throw e
            } finally {
                running = false
                SyncUi.refresh()
            }
        }
    }

    /** T57: the last clock comparison with any peer (kept across restarts); null before the first sync. */
    @Volatile var clockCheck: ClockCheck? = loadClock()
        private set

    private fun rememberClock(c: ClockCheck) {
        clockCheck = c
        prefs.edit().putString(KEY_CLOCK, MiniJson.encode(c.json())).apply()
    }

    private fun loadClock(): ClockCheck? = prefs.getString(KEY_CLOCK, null)?.let { text ->
        runCatching {
            val o = MiniJson.parseObject(text)
            ClockCheck(
                (o["offsetMs"] as Number).toLong(), (o["uncertaintyMs"] as Number).toLong(), o["peer"] as String,
                (o["at"] as Number).toLong(), (o["samples"] as? Number)?.toInt() ?: 1,
            )
        }.getOrNull()
    }

    /** Keeps the last result (without the long per-conflict list) across restarts. */
    private fun remember(json: Map<String, Any?>) {
        last = json
        val short = json - "conflicts" - "settings" - "clock"
        prefs.edit().putString(KEY_LAST, MiniJson.encode(short)).apply()
    }

    private fun addConflicts(r: SyncReport) {
        val now = System.currentTimeMillis()
        val added = r.merged.conflicts.map { it.json() + mapOf("seenAt" to now, "peer" to r.transport) }
        synchronized(conflictsFile) {
            conflictsTotal += added.size
            conflicts = (added.reversed() + conflicts).take(MAX_CONFLICTS)
            saveConflicts()
        }
    }

    private fun saveConflicts() {
        runCatching { conflictsFile.writeText(MiniJson.encode(mapOf("total" to conflictsTotal, "conflicts" to conflicts))) }
    }

    /** Conflicts on one entity (the "edited on two devices" note). */
    fun conflictsFor(id: String): List<Map<String, Any?>> = conflicts.filter { it["id"] == id }

    /** Dismisses the notes on [id] (all when null). */
    fun dismiss(id: String?) {
        synchronized(conflictsFile) {
            conflicts = if (id == null) emptyList() else conflicts.filter { it["id"] != id }
            saveConflicts()
        }
    }

    /** The peer's vector after the last sync with [peer], or empty when never synced. */
    fun peerVector(peer: String = defaultPeer): VersionVector =
        prefs.getString(KEY_PEER_VECTOR + peer, null)?.let { runCatching { VersionVector.parse(it) }.getOrNull() } ?: VersionVector.EMPTY

    /** Operations the default peer did not have at the last sync, plus everything since. */
    fun pending(): Long = replica.pendingFor(peerVector())

    fun status(): Map<String, Any?> = linkedMapOf<String, Any?>(
        "peer" to defaultPeer, "peerSetting" to peerSetting, "tokenSet" to syncToken.isNotEmpty(), "running" to running, "syncs" to syncs, "last" to last,
        "pendingOps" to pending(),
        "clockCheck" to clockCheck?.json(), // T57: the last clock comparison with a peer
        "conflicts" to linkedMapOf("total" to conflictsTotal, "concurrent" to conflicts.count { it["concurrent"] == true }, "recent" to conflicts.take(10)),
        "settingsSync" to SettingsSync.json(runCatching { tracking.call { store.settings() } }.getOrNull()), // T110: and what the log holds
        "files" to filesDir.listFiles { f -> f.isFile && f.name.endsWith(".dsync") }.orEmpty().sortedBy { it.name }.map { mapOf("name" to it.name, "bytes" to it.length()) },
        "seededFromTables" to store.seededFromTables,
    ) + tracking.call {
        SyncServer.status(store) + mapOf("tablesMatchLog" to (store.snapshot() == store.snapshotFromLog()), "runsExtended" to store.runsExtended)
    }

    companion object {
        const val TAG = "DystopoSync"
        private const val PREFS = "dystopo-sync"
        private const val KEY_PEER = "peer"
        private const val KEY_LAST = "last"
        private const val KEY_TOKEN = "token"
        private const val KEY_PEER_VECTOR = "peerVector:"
        private const val KEY_CLOCK = "clockCheck"
        const val MAX_CONFLICTS = 100

        @Volatile private var instance: AppSync? = null

        fun of(context: Context): AppSync = instance ?: synchronized(this) {
            instance ?: AppSync(context.applicationContext as DystopoApp).also { instance = it }
        }

        fun shortName(peer: String) = peer.removePrefix("http://").removePrefix("https://").removePrefix("file:")

        val gzip: (ByteArray) -> ByteArray = { b -> ByteArrayOutputStream(b.size / 2 + 64).also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray() }
        val gunzip: (ByteArray) -> ByteArray = { b -> GZIPInputStream(b.inputStream()).use { it.readBytes() } }

        fun post(url: String, body: ByteArray, token: String = "", clock: ClockSamples? = null): ByteArray {
            val c = io.github.jasondorjeshort.dystopo.app.firstrun.CleartextPolicy.open(url) // T79
            val sent = System.currentTimeMillis()
            try {
                c.requestMethod = "POST"
                c.connectTimeout = 10_000
                c.readTimeout = 60_000
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/octet-stream")
                if (token.isNotEmpty()) c.setRequestProperty("Authorization", "Bearer $token")
                c.setFixedLengthStreamingMode(body.size)
                c.outputStream.use { it.write(body) }
                val code = c.responseCode
                // T57: the server's clock, from its Date header (whole seconds).
                ClockCheck.httpDate(c.getHeaderField("Date"))?.let { clock?.add(ClockSample(it, sent, System.currentTimeMillis(), 1000)) }
                val bytes = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
                check(code in 200..299) { "POST $url: HTTP $code ${bytes.decodeToString().take(300)}" }
                return bytes
            } finally {
                c.disconnect()
            }
        }
    }
}
