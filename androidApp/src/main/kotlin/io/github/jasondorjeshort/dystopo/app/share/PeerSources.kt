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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

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
 * server's ([download]); its areas through [area]. Peers live for the app's run (a share code lives as long as the other
 * phone's Share screen); a download interrupted with the peer gone resumes when the peer is added again.
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

        val url: String get() = SCHEME + addr

        fun json(): Map<String, Any?> = linkedMapOf(
            "addr" to addr, "url" to url, "name" to name, "device" to device, "nsdName" to nsdName, "auto" to auto,
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

    fun all(): List<Peer> = peers.values.sortedByDescending { it.addedAt }

    /** A peer by address, `peer://` URL, NSD name or display name. */
    fun find(key: String): Peer? = peers[key.removePrefix(SCHEME)] ?: peers.values.firstOrNull { it.name == key || it.nsdName == key || it.device == key }

    fun codeFor(addr: String): String? = peers[addr]?.code

    val knownCodes: List<String> get() = synchronized(known) { known.toList() }

    fun learnCode(code: String): String {
        val c = ShareClient.requireCode(code)
        synchronized(known) { known.add(c) }
        return c
    }

    fun forgetCodes() = synchronized(known) { known.clear() }

    fun setVia(from: String, to: String?) {
        if (to.isNullOrBlank()) via.remove(from) else via[from] = to
    }

    /** One request to [peer] over the sealed link (`by=` added). */
    fun request(addr: String, code: String, method: String, path: String, body: ByteArray = ByteArray(0), readMs: Int = 30_000): SealedLink.Response {
        val target = path + (if ('?' in path) "&" else "?") + "by=" + self.first
        return ShareClient.sealed(ShareClient.Target("http://$addr", code), code, method, target, body, readMs)
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
        val p = peers[reach]?.also { it.code = c } ?: Peer(reach, c)
        nsdName?.let { p.nsdName = it }
        discoveredAt?.let { p.discoveredAt = it }
        resolvedAt?.let { p.resolvedAt = it }
        p.auto = auto
        try {
            val hello = request(reach, c, "POST", "/peer/hello", MiniJson.encode(mapOf("device" to self.first, "name" to self.second)).toByteArray(), readMs = 15_000)
            if (hello.status == 404) throw java.io.IOException("$reach shares sync only (an app without peer sources)")
            if (hello.status != 200) throw java.io.IOException("$reach: hello HTTP ${hello.status}")
            val h = MiniJson.parseObject(hello.body.decodeToString())
            p.name = h["name"] as? String
            p.device = h["device"] as? String
            p.helloAt = System.currentTimeMillis()
            learnCode(c)
            peers[reach] = p
            refresh(p)
            p.error = null
            OnlineService.get(app).setPeers(peers.values.filter { it.catalog != null }.map { it.url })
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

    fun remove(key: String): Boolean {
        val p = find(key) ?: return false
        peers.remove(p.addr)
        OnlineService.get(app).setPeers(peers.values.filter { it.catalog != null }.map { it.url })
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
        if (peers[reach]?.catalog != null) return
        worker.execute {
            for (c in codes) {
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
        val c = runCatching { refresh(p) }.getOrNull()?.catalog ?: p.catalog ?: throw IllegalStateException("no catalog from ${p.addr}")
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
