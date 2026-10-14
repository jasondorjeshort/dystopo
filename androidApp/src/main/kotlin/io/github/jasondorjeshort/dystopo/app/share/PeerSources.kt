package io.github.jasondorjeshort.dystopo.app.share

import android.util.Log
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.areas.AreaDownload
import io.github.jasondorjeshort.dystopo.app.areas.Areas
import io.github.jasondorjeshort.dystopo.app.online.ConnectFailed
import io.github.jasondorjeshort.dystopo.app.online.HttpGet
import io.github.jasondorjeshort.dystopo.app.online.HttpReply
import io.github.jasondorjeshort.dystopo.app.online.OnlineService
import io.github.jasondorjeshort.dystopo.app.packs.PackDownloader
import io.github.jasondorjeshort.dystopo.app.packs.PackManager
import io.github.jasondorjeshort.dystopo.areas.AreaManifest
import io.github.jasondorjeshort.dystopo.areas.FileItem
import io.github.jasondorjeshort.dystopo.packs.PackCatalog
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import io.github.jasondorjeshort.dystopo.sync.crypto.SealedLink
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * T78, the receiving side of peer sources: the nearby phones this phone uses as map sources ("one phone with the
 * sources serves the others", Design goals, principle 6). A peer is added with its address and share code
 * ([add]: hello, then its catalog and area list over the sealed link), or found by discovery: when NSD resolves a
 * phone ([PeerList.onResolved]) every code this phone knows ([knownCodes]: added with [learnCode], and every code a
 * peer was added with) is tried once on it, so a phone that has the trip's code sees a new phone's catalog seconds
 * after it appears (`discoveredAt` and `catalogAt` in [json]).
 *
 * Each usable peer is a source of the online fetcher ([OnlineService.setPeers], URL `peer://<host:port>`, tried before
 * the network: [PeerHttpGet]); its packs download through [PackDownloader] with the same resume and SHA-256 checks as a
 * server's ([download]); its areas through [area].
 *
 * T90: peers are kept across restarts ([PeerStore], `files/share/peers.json`: address, name, code and the last catalog,
 * plus the known codes and address overrides), read once when this object is first used. A restored peer is listed at
 * once (`state` `saved`, its packs from the kept catalog) and becomes a fetcher source again only after it answers: it is
 * retried in the background (at once, then after 15 s doubling to 5 min, [RETRY_MAX_MS]), on every download or refresh,
 * and whenever discovery resolves a phone at its address or with its NSD name; a peer that does not answer stays listed
 * (`unreachable`, with `lastSeenAt`) until it is removed. A download interrupted with the peer gone resumes from its part
 * file once the peer answers again, with the code the other phone still shows.
 */
object PeerSources {
    const val SCHEME = "peer://"
    const val TAG = "DystopoPeer"

    class Peer(val addr: String, @Volatile var code: String) {
        @Volatile var name: String? = null
        @Volatile var device: String? = null
        @Volatile var nsdName: String? = null
        @Volatile var discoveredAt: Long? = null
        @Volatile var resolvedAt: Long? = null
        @Volatile var addedAt = System.currentTimeMillis()
        @Volatile var helloAt: Long? = null
        @Volatile var catalogAt: Long? = null
        @Volatile var catalogMs: Long? = null
        @Volatile var catalog: PackCatalog? = null
        @Volatile var areas: List<Map<String, Any?>> = emptyList()
        @Volatile var error: String? = null
        @Volatile var auto = false
        /** T90: the last time this peer answered a request (any: hello, catalog, a tile, a piece of a file). */
        @Volatile var lastSeenAt: Long? = null
        /** T90: read from [PeerStore] and not reached since this process started (listed, but not a fetcher source yet). */
        @Volatile var restored = false
        @Volatile var retries = 0
        @Volatile var nextRetryAt: Long? = null

        val url: String get() = SCHEME + addr

        /**
         * T90: `connected` (answered since the app started), `saved` (restored, being tried), `unreachable` (restored, did not
         * answer, retried), `codeChanged` (restored, its kept code no longer opens: not retried), `error`.
         */
        val state: String get() = when {
            restored -> if (error == null) "saved" else if (error == CODE_CHANGED) "codeChanged" else "unreachable"
            error != null -> "error"
            else -> "connected"
        }

        fun json(): Map<String, Any?> = linkedMapOf(
            "addr" to addr, "url" to url, "name" to name, "device" to device, "nsdName" to nsdName, "auto" to auto,
            "state" to state, "lastSeenAt" to lastSeenAt, "retries" to retries, "nextRetryAt" to nextRetryAt, // T90
            "discoveredAt" to discoveredAt, "resolvedAt" to resolvedAt, "addedAt" to addedAt, "helloAt" to helloAt, "catalogAt" to catalogAt, "catalogMs" to catalogMs,
            "catalogAfterDiscoveryMs" to discoveredAt?.let { d -> catalogAt?.let { it - d } },
            "packs" to catalog?.packs?.map { mapOf("id" to it.id, "name" to it.name, "bytes" to it.bytes, "hash" to it.hash, "bounds" to it.bounds?.toList(), "files" to it.files.size) },
            "areas" to areas.map { it.filterKeys { k -> k in setOf("id", "name", "kind", "content", "pack", "bytes", "tiles", "state") } },
            "error" to error,
        )
    }

    private val app: DystopoApp get() = DystopoApp.instance
    private val peers = ConcurrentHashMap<String, Peer>()
    private val known = LinkedHashSet<String>()
    private val tried = ConcurrentHashMap.newKeySet<String>()
    private val worker = Executors.newFixedThreadPool(2) { r -> Thread(r, "dystopo-peer").apply { isDaemon = true } }
    private val retryTimer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "dystopo-peer-retry").apply { isDaemon = true } }

    /** T90: where peers are kept ([PeerStore]). */
    const val STORE_FILE = "share/peers.json"

    /** T90: the first retry of a restored peer that did not answer, doubling up to [RETRY_MAX_MS]. */
    const val RETRY_FIRST_MS = 15_000L
    const val RETRY_MAX_MS = 5 * 60_000L

    /** T90: a restored peer's error when its kept code no longer opens the link. */
    const val CODE_CHANGED = "its code changed: enter the code its Share screen shows now"

    /** T90: the most codes kept for discovery ([learnCode]). */
    const val MAX_CODES = 4

    /** T90: [Peer.lastSeenAt] is written to the file at most this often while a peer is in use. */
    private const val SEEN_SAVE_MS = 60_000L

    private val storeFile: File get() = File(app.filesDir, STORE_FILE)
    private val saveLock = Any()
    @Volatile var restoredPeers = 0
        private set
    @Volatile var saves = 0
        private set
    @Volatile private var savedAt = 0L

    /**
     * Emulators and odd networks: a discovered address that does not connect, mapped to one that does (`10.0.2.16:8841` to
     * `10.0.2.2:8842` through an adb forward on the host). Set with `POST /share/peers/via`; empty on a real network.
     */
    private val via = ConcurrentHashMap<String, String>()

    /** Who this phone is, for the sharing phone's counters (`by=`, `POST /peer/hello`). */
    private val self: Pair<String, String> by lazy {
        val s = ShareSession.of(app)
        s.deviceId.take(8) to s.deviceName
    }

    @Volatile var autoTries = 0
        private set

    private val loaded = java.util.concurrent.atomic.AtomicBoolean()

    init {
        load()
    }

    /**
     * T90: reads [PeerStore] once per process and starts retrying the peers it held. The map screen calls it at launch
     * (`ShareOverlay`), so kept peers become sources again without the Share screen being opened; any first use of this
     * object loads too.
     */
    fun load() {
        if (!loaded.compareAndSet(false, true)) return
        val s = runCatching { PeerStore.read(storeFile) }.getOrNull() ?: return
        synchronized(known) { known.addAll(s.codes) }
        via.putAll(s.via)
        s.peers.forEach { peers.putIfAbsent(it.addr, it) }
        restoredPeers = s.peers.size
        Log.i(TAG, "restored ${s.peers.size} peers, ${s.codes.size} codes")
        s.peers.forEach { p -> scheduleRetry(p, 0) }
    }

    /** T90: writes the peers, codes and overrides ([PeerStore]); a failure is logged, never thrown. */
    private fun save() {
        synchronized(saveLock) {
            runCatching { PeerStore.write(storeFile, PeerStore.encode(peers.values.sortedBy { it.addedAt }, knownCodes, via)) }
                .onSuccess { saves++; savedAt = System.currentTimeMillis() }
                .onFailure { Log.w(TAG, "could not save peers: ${it.message}") }
        }
    }

    /** T90: tries restored peer [p] again after [delayMs] (hello, catalog); on failure the next try is later, up to [RETRY_MAX_MS]. */
    private fun scheduleRetry(p: Peer, delayMs: Long) {
        p.nextRetryAt = System.currentTimeMillis() + delayMs
        retryTimer.schedule({
            if (peers[p.addr] === p && p.restored) {
                p.nextRetryAt = null
                p.retries++
                try {
                    add(p.addr, p.code, auto = p.auto)
                } catch (e: ShareClient.WrongCodeException) {
                    // Its Share screen was reopened (a new code): retrying would only spend that phone's five failures and
                    // make it replace its code again. Listed until the new code is entered (Use its maps) or it is forgotten.
                    p.error = CODE_CHANGED
                    Log.i(TAG, "peer ${p.name} at ${p.addr}: the kept code no longer opens; not retried")
                } catch (e: Exception) {
                    Log.i(TAG, "peer ${p.name} at ${p.addr} did not answer (try ${p.retries}): ${e.message}")
                    scheduleRetry(p, (RETRY_FIRST_MS shl (p.retries - 1).coerceIn(0, 5)).coerceAtMost(RETRY_MAX_MS))
                }
            }
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    /** T90: [addr] answered: the time is kept (and written now and then, so the list's "last seen" survives a restart). */
    private fun seen(addr: String) {
        val p = peers[addr] ?: return
        val now = System.currentTimeMillis()
        p.lastSeenAt = now
        if (now - savedAt > SEEN_SAVE_MS) {
            savedAt = now
            worker.execute(::save)
        }
    }

    /** The peers the online fetcher asks: those with a catalog that answered since the app started. */
    private fun updateSources() = OnlineService.get(app).setPeers(peers.values.filter { it.catalog != null && !it.restored }.map { it.url })

    fun all(): List<Peer> = peers.values.sortedByDescending { it.addedAt }

    /** A peer by address, `peer://` URL, NSD name or display name. */
    fun find(key: String): Peer? = peers[key.removePrefix(SCHEME)] ?: peers.values.firstOrNull { it.name == key || it.nsdName == key || it.device == key }

    fun codeFor(addr: String): String? = peers[addr]?.code

    val knownCodes: List<String> get() = synchronized(known) { known.toList() }

    fun learnCode(code: String, tryDiscovered: Boolean = true): String {
        val c = ShareClient.requireCode(code)
        val changed = synchronized(known) {
            // T90: the newest last, and at most MAX_CODES: every known code is tried on each phone discovery finds, and a
            // wrong one costs that phone one of the five failures that replace its code, so dead codes must not pile up.
            val had = known.remove(c)
            known.add(c)
            while (known.size > MAX_CODES) known.remove(known.first())
            !had
        }
        if (changed) {
            save()
            if (!tryDiscovered) return c
            // T90: phones discovery already resolved get the new code too (it may arrive after their announcement).
            runCatching { ShareSession.of(app).peers.peers().forEach(::onDiscovered) }
        }
        return c
    }

    /** T90: [code] is no longer any peer's (the phone it opened shows a new one): it is not tried on discovered phones again. */
    private fun retireCode(code: String) {
        if (peers.values.any { it.code == code }) return
        if (synchronized(known) { known.remove(code) }) save()
    }

    fun forgetCodes() {
        synchronized(known) { known.clear() }
        save()
    }

    fun setVia(from: String, to: String?) {
        if (to.isNullOrBlank()) via.remove(from) else via[from] = to
        save()
    }

    /** One request to [peer] over the sealed link (`by=` added). */
    fun request(addr: String, code: String, method: String, path: String, body: ByteArray = ByteArray(0), readMs: Int = 30_000): SealedLink.Response {
        val target = path + (if ('?' in path) "&" else "?") + "by=" + self.first
        return ShareClient.sealed(ShareClient.Target("http://$addr", code), code, method, target, body, readMs).also { seen(addr) }
    }

    /**
     * Adds (or refreshes) the phone at [addr] (`host[:port]` or a share link) as a source: hello with its name, then its
     * catalog and areas. Throws on a wrong code or no answer (also kept as the peer's `error`).
     */
    fun add(addr: String, code: String?, nsdName: String? = null, discoveredAt: Long? = null, resolvedAt: Long? = null, auto: Boolean = false): Peer {
        val t = ShareClient.parse(addr)
        val c = ShareClient.requireCode(code?.takeIf { it.isNotBlank() } ?: t.code)
        val hostPort = t.baseUrl.removePrefix("http://")
        val reach = via[hostPort] ?: hostPort
        val p = peers[reach] ?: Peer(reach, c)
        nsdName?.let { p.nsdName = it }
        discoveredAt?.let { p.discoveredAt = it }
        resolvedAt?.let { p.resolvedAt = it }
        p.auto = auto
        try {
            val hello = request(reach, c, "POST", "/peer/hello", MiniJson.encode(mapOf("device" to self.first, "name" to self.second)).toByteArray(), readMs = 15_000)
            if (hello.status == 404) throw java.io.IOException("$reach shares sync only (an app without peer sources)")
            if (hello.status != 200) throw java.io.IOException("$reach: hello HTTP ${hello.status}")
            val h = MiniJson.parseObject(hello.body.decodeToString())
            val oldCode = p.code
            p.code = c // T90: only once the code worked, so a wrong one typed for a known peer does not replace its working one
            p.name = h["name"] as? String
            p.device = h["device"] as? String
            p.helloAt = System.currentTimeMillis()
            learnCode(c, tryDiscovered = false)
            peers[reach] = p
            refresh(p)
            p.error = null
            p.restored = false
            p.nextRetryAt = null
            // T90: the same phone at another address (the network gave it a new one) replaces its old entry once it answers.
            peers.values.filter { q -> q !== p && q.addr != reach && ((q.device != null && q.device == p.device) || (q.nsdName != null && q.nsdName == p.nsdName)) }
                .forEach { peers.remove(it.addr, it); retireCode(it.code) }
            if (oldCode != c) retireCode(oldCode)
            updateSources()
            save()
            Log.i(TAG, "peer ${p.name} at $reach: ${p.catalog?.packs?.size} packs, ${p.areas.size} areas")
            return p
        } catch (e: Exception) {
            p.error = if (e is ShareClient.WrongCodeException) "wrong code" else (e.message ?: e.javaClass.simpleName)
            throw e
        }
    }

    /** Fetches [p]'s catalog and area list again. */
    fun refresh(p: Peer): Peer {
        val t0 = System.currentTimeMillis()
        val cat = request(p.addr, p.code, "GET", "/peer/catalog", readMs = 120_000)
        if (cat.status != 200) throw java.io.IOException("${p.addr}: catalog HTTP ${cat.status}")
        val skipped = ArrayList<String>()
        p.catalog = PackCatalog.parse(MiniJson.parseObject(cat.body.decodeToString()), skipped)
        p.catalogAt = System.currentTimeMillis()
        p.catalogMs = p.catalogAt!! - t0
        val areas = runCatching { request(p.addr, p.code, "GET", "/peer/areas") }.getOrNull()
        @Suppress("UNCHECKED_CAST")
        if (areas?.status == 200) p.areas = (MiniJson.parseObject(areas.body.decodeToString())["areas"] as? List<Map<String, Any?>>).orEmpty()
        return p
    }

    /** T90: [refresh] for a peer that answered since the app started; a restored one is added again (hello, catalog, a source again). */
    fun reach(p: Peer): Peer = if (p.restored) add(p.addr, p.code, nsdName = p.nsdName, auto = p.auto) else refresh(p)

    fun remove(key: String): Boolean {
        val p = find(key) ?: return false
        peers.remove(p.addr)
        updateSources()
        save()
        return true
    }

    /** Discovery found and resolved [d]: try each known code on it once (in the background). */
    fun onDiscovered(d: PeerList.Peer) {
        val hostPort = d.hostPort ?: return
        if (d.self) return
        // Our own announcement can resolve before NSD reports the name we registered under: skip our own address and port.
        val me = ShareSession.of(app)
        if (d.port == me.port && d.host in me.addresses()) return
        val codes = knownCodes
        if (codes.isEmpty()) return
        val reach = via[hostPort] ?: hostPort
        val here = peers[reach]
        if (here?.catalog != null && !here.restored) return
        // T90: a restored peer at this address or under this NSD name: its own code first, now rather than at its next retry.
        val first = (here ?: peers.values.firstOrNull { it.restored && it.nsdName == d.name })?.code?.takeIf { it in codes }
        worker.execute {
            // T90: the newest code first (the trip's current one), so a phone is not handed old codes before the right one.
            for (c in listOfNotNull(first) + (codes.reversed() - setOfNotNull(first))) {
                if (!tried.add("${d.name}|$reach|$c")) continue
                autoTries++
                try {
                    add(hostPort, c, d.name, d.firstSeenAt, d.resolvedAt, auto = true)
                    return@execute
                } catch (e: ShareClient.WrongCodeException) {
                    continue
                } catch (e: Exception) {
                    tried.remove("${d.name}|$reach|$c") // not reachable now: may be tried again when it is resolved again
                    Log.w(TAG, "peer ${d.name} at $hostPort: ${e.message}")
                    return@execute
                }
            }
        }
    }

    /** Downloads pack [id] of peer [key] (resumable, every archive checked against the peer's SHA-256). */
    fun download(key: String, id: String, activate: Boolean = false): PackDownloader.Job {
        val p = find(key) ?: throw NoSuchElementException("no peer '$key'; POST /share/peers/add first")
        // T90: a restored peer is tried at once; when it does not answer, its kept catalog plans the download, which then resumes or fails as usual.
        val c = runCatching { reach(p) }.getOrNull()?.catalog ?: p.catalog ?: throw IllegalStateException("no catalog from ${p.addr}")
        return PackManager.get(app).catalogClient.downloadFrom(p.url, c, id, activate, label = "peer ${p.name ?: p.addr}")
    }

    /**
     * Downloads area [id] of peer [key]: a pack-backed area as its pack (above), recorded as a pinned area; a corridor
     * through [AreaDownload] (the peer is a fetcher source, so its tiles come from the peer). Returns what started.
     */
    fun area(key: String, id: String): Map<String, Any?> {
        val p = find(key) ?: throw NoSuchElementException("no peer '$key'; POST /share/peers/add first")
        val r = request(p.addr, p.code, "GET", "/peer/areas/$id")
        if (r.status != 200) throw NoSuchElementException("${p.addr} has no area '$id' (HTTP ${r.status})")
        val m = AreaManifest.parse(r.body.decodeToString())
        val packItem = m.items.filterIsInstance<FileItem>().firstOrNull()
        if (packItem != null) {
            val job = download(key, packItem.pack)
            Areas.put(Areas.Record(id = m.id, name = m.name, area = m.area, createdAt = System.currentTimeMillis(), pinned = true, state = "done", source = p.url, pack = packItem.pack, bytes = m.bytes, estimateBytes = m.bytes))
            return linkedMapOf("kind" to "pack", "area" to m.id, "download" to job.json())
        }
        return linkedMapOf("kind" to "corridor", "area" to m.id, "manifestTiles" to m.tileCount) + AreaDownload.start(m.area, m.name)
    }

    fun json(): Map<String, Any?> = linkedMapOf(
        "peers" to all().map { it.json() },
        "knownCodes" to knownCodes.size, "autoTries" to autoTries, "via" to via.toSortedMap(),
        "restoredPeers" to restoredPeers, "saves" to saves, "file" to STORE_FILE, // T90
    )
}

/**
 * T78: [HttpGet] for the online fetcher: `peer://<host:port>/<path>` goes to that phone over the sealed link (its code
 * from [PeerSources]); every other URL to [delegate] (the server, or the phone's own source readers).
 */
class PeerHttpGet(private val delegate: HttpGet?) : HttpGet {
    override fun get(url: String): HttpReply {
        if (!url.startsWith(PeerSources.SCHEME)) return delegate?.get(url) ?: throw ConnectFailed("no source for $url")
        val rest = url.removePrefix(PeerSources.SCHEME)
        val addr = rest.substringBefore('/')
        val path = "/" + rest.substringAfter('/', "")
        val code = PeerSources.codeFor(addr) ?: throw ConnectFailed("no code for peer $addr")
        val r = try {
            PeerSources.request(addr, code, "GET", path, readMs = 25_000)
        } catch (e: LanConnectException) {
            throw ConnectFailed(e.message ?: "connect failed", e)
        } catch (e: ShareClient.WrongCodeException) {
            throw ConnectFailed("peer $addr refused the code (its Share screen was reopened?)", e)
        } catch (e: LanHttpException) {
            return HttpReply(e.status, ByteArray(0))
        }
        return HttpReply(r.status, if (r.status in 200..299) r.body else ByteArray(0))
    }
}
