package io.github.jasondorjeshort.dystopo.app.share

import android.util.Log
import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.sync.AppSync
import io.github.jasondorjeshort.dystopo.app.sync.ClockCheck
import io.github.jasondorjeshort.dystopo.app.sync.ClockSamples
import io.github.jasondorjeshort.dystopo.sync.crypto.RelayProtocol
import io.github.jasondorjeshort.dystopo.sync.crypto.RelayTransport
import io.github.jasondorjeshort.dystopo.sync.crypto.ShareCode

/**
 * "Via server" on the Share screen (T72): sync with a phone anywhere, through a server both can reach,
 * end to end encrypted. [start] makes (or takes) a share code, posts this phone's whole log to the server's
 * relay sealed under it ([RelayTransport], `/sync/relay`), and merges what the other phone posted there;
 * then it keeps polling every [POLL_MS] while there is a chance the other phone posts (until the relay
 * would have dropped this phone's blob, [ttlMs] after the last post), and syncs again when something new
 * arrives. The other phone does the same with the same code, so the two converge in whichever order they
 * act, and neither needs to be online at the same moment as long as both act within the blob lifetime.
 *
 * The server sees a slot id derived from the code, a random side id, the blob sizes and the timing; never
 * the code or the data (docs/PLANNING.md, Sync, Threat model).
 */
class RelaySession private constructor(private val app: DystopoApp) {
    private val sync: AppSync get() = AppSync.of(app)

    @Volatile private var transport: RelayTransport? = null
    @Volatile var server: String? = null
        private set
    @Volatile var startedAt: Long? = null
        private set
    @Volatile var lastPostAt: Long? = null
        private set
    @Volatile var polls = 0L
        private set
    @Volatile var syncs = 0L
        private set
    @Volatile var lastResult: Map<String, Any?>? = null
        private set
    @Volatile var lastError: String? = null
        private set
    @Volatile private var poller: Thread? = null
    /** The server's `Date` headers during this session, for the clock check (T57). */
    @Volatile private var samples = ClockSamples()
    val ttlMs = DEFAULT_TTL_MS

    val code: String? get() = transport?.code
    val polling: Boolean get() = poller?.isAlive == true

    /** The relay server: [requested], else the sync peer when it is a server URL, else the online server. */
    fun resolveServer(requested: String?): String {
        val s = requested?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
            ?: sync.peerSetting.takeIf { it.startsWith("http://") || it.startsWith("https://") }?.trimEnd('/')
            ?: AppSettings.server.trimEnd('/')
        require(s.startsWith("http://") || s.startsWith("https://")) { "no server for the relay: set the online server, or pass server=http(s)://..." }
        return s
    }

    /**
     * Starts relaying with [code] (null: a new one) through [serverUrl] (see [resolveServer]): one sync now,
     * then polling. Replaces any session running. Returns [status] plus the first sync's result; a failure
     * of that first sync is in `lastError` and polling still starts (the server may be back in a moment).
     */
    fun start(serverUrl: String?, code: String?): Map<String, Any?> {
        val base = resolveServer(serverUrl)
        val c = if (code.isNullOrBlank()) ShareGate.randomCode() else ShareClient.requireCode(code)
        stop(forget = false)
        val samples = ClockSamples().also { this.samples = it }
        val t = RelayTransport(base, c, RelayProtocol.newSide(ShareGate::randomBytes), { u, b -> post(u, b, samples) }, ShareGate::randomBytes, AppSync.gzip, AppSync.gunzip)
        synchronized(this) {
            transport = t
            server = base
            startedAt = System.currentTimeMillis()
            lastResult = null
            lastError = null
            polls = 0
            syncs = 0
        }
        Log.i(TAG, "relay via $base started")
        runCatching { syncNow() }
        startPolling(t)
        return status()
    }

    private fun post(url: String, body: ByteArray, samples: ClockSamples?): ByteArray {
        val bytes = AppSync.post(url, body, clock = samples)
        if (body.isNotEmpty()) lastPostAt = System.currentTimeMillis()
        return bytes
    }

    /** One exchange through the relay now (pull what is there, merge, post the whole log). */
    fun syncNow(): Map<String, Any?> {
        val t = transport ?: throw IllegalStateException("not relaying: POST /share/relay/start")
        val base = server!!
        return try {
            val r = synchronized(t) {
                sync.run("relay:$base", "the relay at ${AppSync.shortName(base)}", t, 0) {
                    ClockCheck.of(samples.all().takeLast(20), AppSync.shortName(base), System.currentTimeMillis())
                }
            }
            syncs++
            lastError = null
            val json = r + mapOf("transport" to "relay", "encrypted" to true, "received" to t.received, "rejected" to t.rejected)
            lastResult = json
            json
        } catch (e: Exception) {
            lastError = "${e.javaClass.simpleName}: ${e.message}"
            Log.w(TAG, "relay sync failed", e)
            throw e
        }
    }

    private fun startPolling(t: RelayTransport) {
        val th = Thread({
            try {
                while (transport === t) {
                    Thread.sleep(POLL_MS)
                    if (transport !== t) break
                    val last = lastPostAt ?: startedAt ?: 0L
                    if (System.currentTimeMillis() - last > ttlMs) {
                        Log.i(TAG, "relay polling ends: nothing posted for ${ttlMs / 1000} s")
                        break
                    }
                    val waiting = try {
                        synchronized(t) { t.fetch() }
                    } catch (e: Exception) {
                        lastError = "poll: ${e.javaClass.simpleName}: ${e.message}"
                        0
                    }
                    polls++
                    if (waiting > 0) runCatching { syncNow() }
                }
            } catch (_: InterruptedException) {
            }
        }, "dystopo-relay")
        th.isDaemon = true
        poller = th
        th.start()
    }

    /** Stops polling; [forget] also deletes this phone's blob from the relay (otherwise it expires there). */
    fun stop(forget: Boolean): Map<String, Any?> {
        val t = transport
        transport = null
        poller?.interrupt()
        poller = null
        var forgotten: Boolean? = null
        if (t != null && forget) forgotten = runCatching { synchronized(t) { t.forget() }; true }.getOrElse { lastError = "forget: ${it.message}"; false }
        return status() + mapOf("forgotten" to forgotten)
    }

    fun status(): Map<String, Any?> {
        val t = transport
        return linkedMapOf(
            "active" to (t != null),
            "code" to t?.code,
            "codeShown" to t?.code?.let(ShareCode::format),
            "server" to server,
            "side" to t?.side,
            "slot" to t?.slot,
            "startedAt" to startedAt,
            "lastPostAt" to lastPostAt,
            "polling" to polling,
            "pollMs" to POLL_MS,
            "pollsUntil" to (lastPostAt ?: startedAt)?.plus(ttlMs)?.takeIf { t != null },
            "polls" to polls,
            "syncs" to syncs,
            "received" to t?.received,
            "rejected" to t?.rejected,
            "seenSeq" to t?.after,
            "posts" to t?.posts,
            "postsSkipped" to t?.postsSkipped,
            "bytesIn" to t?.bytesIn,
            "bytesOut" to t?.bytesOut,
            "lastResult" to lastResult,
            "lastError" to lastError,
        )
    }

    companion object {
        const val TAG = "DystopoRelay"
        const val POLL_MS = 5_000L
        /** The server's default blob lifetime (`relay.ttlSeconds`, 900 s). */
        const val DEFAULT_TTL_MS = 900_000L

        @Volatile private var instance: RelaySession? = null

        fun of(app: DystopoApp): RelaySession = instance ?: synchronized(this) {
            instance ?: RelaySession(app).also { instance = it }
        }
    }
}
