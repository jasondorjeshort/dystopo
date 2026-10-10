package io.github.jasondorjeshort.dystopo.app.share

import android.os.Build
import android.os.SystemClock
import android.util.Log
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.LocalServer
import io.github.jasondorjeshort.dystopo.app.Request
import io.github.jasondorjeshort.dystopo.app.Response
import io.github.jasondorjeshort.dystopo.app.tracking.Tracking
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import io.github.jasondorjeshort.dystopo.store.SqlTrackStore
import io.github.jasondorjeshort.dystopo.sync.ApplyResult
import io.github.jasondorjeshort.dystopo.sync.Bundle
import io.github.jasondorjeshort.dystopo.sync.CompactResult
import io.github.jasondorjeshort.dystopo.sync.Op
import io.github.jasondorjeshort.dystopo.sync.SyncReplica
import io.github.jasondorjeshort.dystopo.sync.SyncServer
import io.github.jasondorjeshort.dystopo.sync.SyncSnapshot
import io.github.jasondorjeshort.dystopo.sync.VersionVector
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Phone-to-phone sync on a local network (T54). While the Share screen is open ([start] .. [stop]):
 * - a second [LocalServer] listens on all interfaces (port [ShareClient.DEFAULT_PORT], else any free
 *   one), separate from the loopback tile server, and answers only what [ShareGate] allows: the T44
 *   sync routes through a [SyncServer] over the app's track store, each guarded by the 6-digit code;
 * - [ShareNsd] announces the phone as `_dystopo._tcp` and lists other phones doing the same.
 * [connect] is the other side: check the code, then one pull-merge-push exchange with the peer.
 * Store calls go through the tracking thread ([Tracking.call]), like [io.github.jasondorjeshort.dystopo.app.sync.AppSync].
 */
class ShareSession private constructor(private val app: DystopoApp) {
    private val tracking = Tracking.of(app)
    val peers = PeerList()
    private val nsd = ShareNsd(app, peers)

    private var server: LocalServer? = null
    private var gate: ShareGate? = null
    private var syncServer: SyncServer? = null

    @Volatile var startedAt: Long? = null
        private set
    @Volatile var lastIncoming: Map<String, Any?>? = null
        private set
    @Volatile var lastConnect: Map<String, Any?>? = null
        private set
    @Volatile var connecting = false
        private set
    @Volatile var stops = 0
        private set

    private val store: SqlTrackStore get() = tracking.store as SqlTrackStore

    /** The track store seen through the tracking thread. */
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

    val listening: Boolean get() = server != null
    val port: Int? get() = server?.localPort
    val code: String? get() = gate?.code

    /** Shown on the screen and announced: the model and the start of the device id. */
    val deviceName: String get() = "Dystopo ${Build.MODEL} ${store.deviceId.take(4)}".take(60)

    /** Starts the listener and NSD (idempotent); returns [status]. */
    @Synchronized
    fun start(): Map<String, Any?> {
        if (server == null) {
            val g = ShareGate(hello = { mapOf("device" to store.deviceId, "name" to deviceName) }, sync = ::serveSync)
            syncServer = SyncServer(replica, System::currentTimeMillis, ShareClient.gzip, ShareClient.gunzip)
            gate = g
            val s = listen(ShareClient.DEFAULT_PORT, g) ?: listen(0, g) ?: throw java.io.IOException("could not open a LAN port")
            server = s
            startedAt = System.currentTimeMillis()
            nsd.register(deviceName, s.localPort, mapOf("id" to store.deviceId.take(8)))
            nsd.startDiscovery()
            Log.i(TAG, "share listener on port ${s.localPort}")
        }
        return status()
    }

    private fun listen(port: Int, g: ShareGate): LocalServer? {
        val s = LocalServer(port, threads = 2, maxQueued = 8, readers = 2, debugThreads = 1, isMapRequest = { true }, handler = g::handle, bindAddress = "0.0.0.0")
        return try {
            s.start()
            s
        } catch (e: java.io.IOException) {
            Log.w(TAG, "share port $port: ${e.message}")
            s.stop()
            null
        }
    }

    /** Stops the listener and NSD (the screen closed); the port is closed when this returns. */
    @Synchronized
    fun stop(): Map<String, Any?> {
        server?.let {
            it.stop()
            stops++
            Log.i(TAG, "share listener on port ${it.localPort} stopped")
        }
        server = null
        gate = null
        syncServer = null
        startedAt = null
        nsd.unregister()
        nsd.stopDiscovery()
        return status()
    }

    /** The sync routes behind the code check; one call at a time per store. */
    private fun serveSync(r: Request): Response? {
        val s = syncServer ?: return Response.json(mapOf("error" to "not sharing"), 503)
        val reply = synchronized(s) { s.handle(r.method, r.path, r.query, r.body) } ?: return null
        if (r.path == "/sync/push" && reply.status == 200) {
            val result = runCatching { MiniJson.parseObject(reply.body.decodeToString()) }.getOrNull().orEmpty()
            val applied = (result["applied"] as? Number)?.toInt() ?: 0
            lastIncoming = linkedMapOf("at" to System.currentTimeMillis(), "applied" to applied, "received" to result["received"])
            tracking.refreshUi("Received $applied changes from a nearby phone")
            if (applied > 0) tracking.refreshOverlay(force = true)
        }
        return Response(reply.status, reply.contentType, reply.body)
    }

    /** One sync with the phone at [addr] (`host[:port]` or a share link) using [code]. Throws on failure (also kept in [lastConnect]). */
    fun connect(addr: String, code: String?): Map<String, Any?> {
        val target = ShareClient.parse(addr)
        val c = code?.trim()?.takeIf { it.isNotEmpty() } ?: target.code ?: throw IllegalArgumentException("pass code=<6 digits>")
        connecting = true
        try {
            val (peer, report) = ShareClient.exchange(replica, target, c, System::currentTimeMillis, SystemClock::elapsedRealtime)
            val json = linkedMapOf<String, Any?>("ok" to true, "peer" to target.baseUrl.removePrefix("http://"), "peerName" to peer["name"], "peerDevice" to peer["device"], "at" to System.currentTimeMillis()) + report.json()
            lastConnect = json
            tracking.refreshUi("Synced with ${peer["name"] ?: addr}: ${report.merged.applied} in, ${report.pushed} out")
            if (report.merged.applied > 0) tracking.refreshOverlay(force = true)
            return json
        } catch (e: Exception) {
            Log.w(TAG, "share sync with $addr failed", e)
            lastConnect = linkedMapOf("ok" to false, "peer" to target.baseUrl.removePrefix("http://"), "at" to System.currentTimeMillis(),
                "wrongCode" to (e is ShareClient.WrongCodeException), "error" to (e.message ?: e.javaClass.simpleName))
            throw e
        } finally {
            connecting = false
        }
    }

    /** This phone's addresses that another phone could reach: non-loopback IPv4, Wi-Fi first. */
    fun addresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { if (it.name.startsWith("wlan")) 0 else if (it.name.startsWith("eth")) 1 else 2 }
            .flatMap { i -> i.inetAddresses.toList().filterIsInstance<Inet4Address>().filter { !it.isLoopbackAddress }.map { it.hostAddress!! } }
            .distinct()
    }.getOrDefault(emptyList())

    /** The link the QR code holds, for the first address. */
    fun shareUri(): String? {
        val p = port ?: return null
        val c = code ?: return null
        return ShareClient.shareUri("${addresses().firstOrNull() ?: "127.0.0.1"}:$p", c)
    }

    fun status(): Map<String, Any?> = linkedMapOf(
        "listening" to listening,
        "port" to port,
        "code" to code,
        "addresses" to addresses(),
        "uri" to shareUri(),
        "name" to deviceName,
        "startedAt" to startedAt,
        "stops" to stops,
        "gate" to gate?.json(),
        "served" to syncServer?.let { mapOf("pulls" to it.pulls, "pushes" to it.pushes) },
        "lastIncoming" to lastIncoming,
        "nsd" to mapOf("registeredAs" to nsd.registeredName, "discovering" to nsd.discovering, "resolves" to nsd.resolves, "lastError" to nsd.lastError),
        "discovered" to peers.all().map { it.json() },
        "connecting" to connecting,
        "lastConnect" to lastConnect,
    )

    companion object {
        const val TAG = "DystopoShare"

        @Volatile private var instance: ShareSession? = null

        fun of(app: DystopoApp): ShareSession = instance ?: synchronized(this) {
            instance ?: ShareSession(app).also { instance = it }
        }
    }
}
