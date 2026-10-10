package io.github.jasondorjeshort.dystopo.app.sync

import android.content.Context
import android.os.SystemClock
import android.util.Log
import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.tracking.Tracking
import io.github.jasondorjeshort.dystopo.store.SqlTrackStore
import io.github.jasondorjeshort.dystopo.sync.ApplyResult
import io.github.jasondorjeshort.dystopo.sync.Bundle
import io.github.jasondorjeshort.dystopo.sync.CompactResult
import io.github.jasondorjeshort.dystopo.sync.FileSyncTransport
import io.github.jasondorjeshort.dystopo.sync.HttpSyncTransport
import io.github.jasondorjeshort.dystopo.sync.Op
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
 * Sync on the phone (T44). The replica is the app's track store ([Tracking.store], a
 * [SqlTrackStore] over `tracks.db`); every store call runs on the tracking thread through
 * [Tracking.call], so a sync never interleaves with a recorded fix.
 *
 * Peers: an HTTP peer (`http://host:port`, the devtool server or any [SyncServer]) or a `.dsync` file
 * in `files/sync/` (a plain name; passed between devices with adb, USB or a file share). The default
 * peer for "Sync now" is the `peer` setting, else the online server ([AppSettings.server]), which is
 * the always-on peer.
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

    /** The peer setting ("" when unset). */
    val peerSetting: String get() = prefs.getString(KEY_PEER, "") ?: ""

    /** The peer "Sync now" uses: the setting, else the online server; "" when neither is set. */
    val defaultPeer: String get() = peerSetting.ifBlank { AppSettings.server }

    fun setPeer(peer: String) = prefs.edit().putString(KEY_PEER, peer.trim()).apply()

    private val store: SqlTrackStore get() = tracking.store as SqlTrackStore

    /** The store seen through the tracking thread. */
    private val replica = object : SyncReplica {
        override val deviceId: String get() = store.deviceId
        override fun vector(): VersionVector = tracking.call { store.vector() }
        override fun opsSince(since: VersionVector): List<Op> = tracking.call { store.opsSince(since) }
        override fun bundle(since: VersionVector): Bundle = tracking.call { store.bundle(since) }
        override fun apply(bundle: Bundle, now: Long): ApplyResult = tracking.call { store.apply(bundle, now) }
        override fun snapshot(): SyncSnapshot = tracking.call { store.snapshot() }
        override fun compact(now: Long, retentionMs: Long): CompactResult = tracking.call { store.compact(now, retentionMs) }
        override val opCount: Long get() = tracking.call { store.opCount }
    }

    /** An HTTP peer for a URL, else a file in [filesDir] (`.dsync` added when the name has no extension). */
    fun transport(peer: String): SyncTransport {
        if (peer.startsWith("http://") || peer.startsWith("https://")) return HttpSyncTransport(peer, ::post, gzip, gunzip)
        val name = peer.removePrefix("file:")
        require(name.isNotEmpty() && '/' !in name && '\\' !in name && name != "." && name != "..") { "a file peer is a plain name in ${filesDir.path}" }
        val f = File(filesDir, if ('.' in name) name else "$name.dsync")
        return FileSyncTransport("file:${f.name}", { f.takeIf { it.isFile }?.readBytes() }, { f.writeBytes(it) }, gzip, gunzip)
    }

    /** One sync with [peer] (default: [defaultPeer]); returns the report as JSON. Throws on failure (also kept in [last]). */
    fun syncNow(peer: String? = null): Map<String, Any?> {
        val target = peer?.takeIf { it.isNotBlank() } ?: defaultPeer
        require(target.isNotBlank()) { "no sync peer: pass with=<url|file> or set one (POST /sync/settings?peer=)" }
        synchronized(this) {
            running = true
            try {
                val r: SyncReport = Sync.exchange(replica, transport(target), System::currentTimeMillis, SystemClock::elapsedRealtime)
                syncs++
                val json = linkedMapOf<String, Any?>("ok" to true, "peer" to target, "at" to System.currentTimeMillis()) + r.json()
                last = json
                val m = r.merged
                tracking.refreshUi("Synced with ${shortName(target)}: ${m.applied} in, ${r.pushed} out")
                if (m.applied > 0) tracking.refreshOverlay(force = true)
                return json
            } catch (e: Exception) {
                Log.w(TAG, "sync with $target failed", e)
                last = linkedMapOf("ok" to false, "peer" to target, "at" to System.currentTimeMillis(), "error" to "${e.javaClass.simpleName}: ${e.message}")
                tracking.refreshUi("Sync failed: ${e.message}")
                throw e
            } finally {
                running = false
            }
        }
    }

    fun status(): Map<String, Any?> = linkedMapOf<String, Any?>(
        "peer" to defaultPeer, "peerSetting" to peerSetting, "running" to running, "syncs" to syncs, "last" to last,
        "files" to filesDir.listFiles { f -> f.isFile }.orEmpty().sortedBy { it.name }.map { mapOf("name" to it.name, "bytes" to it.length()) },
        "seededFromTables" to store.seededFromTables,
    ) + tracking.call { SyncServer.status(store) + mapOf("tablesMatchLog" to (store.snapshot() == store.snapshotFromLog())) }

    companion object {
        const val TAG = "DystopoSync"
        private const val PREFS = "dystopo-sync"
        private const val KEY_PEER = "peer"

        @Volatile private var instance: AppSync? = null

        fun of(context: Context): AppSync = instance ?: synchronized(this) {
            instance ?: AppSync(context.applicationContext as DystopoApp).also { instance = it }
        }

        private fun shortName(peer: String) = peer.removePrefix("http://").removePrefix("https://").removePrefix("file:")

        val gzip: (ByteArray) -> ByteArray = { b -> ByteArrayOutputStream(b.size / 2 + 64).also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray() }
        val gunzip: (ByteArray) -> ByteArray = { b -> GZIPInputStream(b.inputStream()).use { it.readBytes() } }

        fun post(url: String, body: ByteArray): ByteArray {
            val c = URI(url).toURL().openConnection() as HttpURLConnection
            try {
                c.requestMethod = "POST"
                c.connectTimeout = 10_000
                c.readTimeout = 60_000
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/octet-stream")
                c.setFixedLengthStreamingMode(body.size)
                c.outputStream.use { it.write(body) }
                val code = c.responseCode
                val bytes = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
                check(code in 200..299) { "POST $url: HTTP $code ${bytes.decodeToString().take(300)}" }
                return bytes
            } finally {
                c.disconnect()
            }
        }
    }
}
