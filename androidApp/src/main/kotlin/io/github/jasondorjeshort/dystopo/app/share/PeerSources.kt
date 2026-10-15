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
 *
 * T96: which code goes to which phone unasked is [PeerCodes]' (a refused code is never sent to that phone again, by any
 * path; a phone gets at most one code kept from before per session; a kept peer's code goes only to that phone), so a
 * relaunch with kept codes cannot spend the other phone's five failures. A peer whose own code is refused
 * (`codeChanged`) is no longer retried nor a fetcher source, whether the 403 came to a retry, a refresh, a download or a
 * tile; Forget retires its code.
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
         * answer, retried), `codeChanged` (its code no longer opens: not retried, not a source; T96: also for a peer that was
         * connected), `error`.
         */
        val state: String get() = when {
            error == CODE_CHANGED -> "codeChanged"
            restored -> if (error == null) "saved" else "unreachable"
            error != null -> "error"
            else -> "connected"
        }

        /** T96: this peer as [PeerCodes] knows a phone (its address, NSD name and device id). */
        internal val phone: PeerCodes.Phone get() = PeerCodes.Phone(addr, nsdName, device)

        /** T96: when [state] last changed; the Share screen does not show a download that failed before it. */
        @Volatile var stateAt = System.currentTimeMillis()

        /** T96: sets [error] and [restored], noting [stateAt] when that changes [state]. */
        internal fun setState(error: String?, restored: Boolean = this.restored) {
            val before = state
            this.error = error
            this.restored = restored
            if (state != before) stateAt = System.currentTimeMillis()
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
    /** T96: the known codes and which phone refused which ([PeerCodes]); replaces T90's known list and tried set. */
    private val codes = PeerCodes()
    private val worker = Executors.newFixedThreadPool(2) { r -> Thread(r, "dystopo-peer").apply { isDaemon = true } }
    private val retryTimer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "dystopo-peer-retry").apply { isDaemon = true } }
    /** T96: each restored peer's pending retry by address, so a changed code or Forget cancels it. */
    private val retries = ConcurrentHashMap<String, java.util.concurrent.ScheduledFuture<*>>()

    /** T90: where peers are kept ([PeerStore]). */
    const val STORE_FILE = "share/peers.json"

    /** T90: the first retry of a restored peer that did not answer, doubling up to [RETRY_MAX_MS]. */
    const val RETRY_FIRST_MS = 15_000L
    const val RETRY_MAX_MS = 5 * 60_000L

    /** T90: a restored peer's error when its kept code no longer opens the link. */
    const val CODE_CHANGED = "its code changed: enter the code its Share screen shows now"

    /**
     * T90: the most codes kept for discovery ([learnCode]). T96: kept at 4, under the five failures that replace another
     * phone's code, because [PeerCodes] sends each code to a phone at most once: a kept peer's phone gets only its own
     * code from before (one failure in all once its screen is reopened), any other phone at most one stale code per
     * relaunch, so one phone's automatic tries stay at most four on any other.
     */
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

    /** Tries sent unasked (retries and discovery; T96: those [PeerCodes] allowed). */
    val autoTries: Int get() = codes.sent

    private val loaded = java.util.concurrent.atomic.AtomicBoolean()

    init {
        load()
    }

    /**
     * T90: reads [PeerStore] once per process and starts retrying the peers it held. The map screen calls it at launch
     * (`ShareOverlay`), so kept peers become sources again without the Share screen being opened; any first use of this
     * object loads too. T96: a peer whose code its phone refused before (kept in the file) comes back as `codeChanged`
     * and is not retried.
     */
    fun load() {
        if (!loaded.compareAndSet(false, true)) return
        val s = runCatching { PeerStore.read(storeFile) }.getOrNull() ?: return
        codes.restore(s.codes, s.refused)
        via.putAll(s.via)
        s.peers.forEach { peers.putIfAbsent(it.addr, it) }
        restoredPeers = s.peers.size
        s.peers.forEach { p -> if (codes.isRefused(p.phone, p.code)) p.error = CODE_CHANGED }
        Log.i(TAG, "restored ${s.peers.size} peers (${s.peers.count { it.error == CODE_CHANGED }} with a changed code), ${s.codes.size} codes")
        s.peers.filter { it.error != CODE_CHANGED }.forEach { p -> scheduleRetry(p, 0) }
    }

    /** T90: writes the peers, codes and overrides ([PeerStore]); a failure is logged, never thrown. T96: and the refusals. */
    private fun save() {
        synchronized(saveLock) {
            runCatching {
                val list = peers.values.sortedBy { it.addedAt }
                val known = codes.codes
                PeerStore.write(storeFile, PeerStore.encode(list, known, via, codes.refusals(known.toSet() + list.map { it.code })))
            }
                .onSuccess { saves++; savedAt = System.currentTimeMillis() }
                .onFailure { Log.w(TAG, "could not save peers: ${it.message}") }
        }
    }

    /**
     * T90: tries restored peer [p] again after [delayMs] (hello, catalog); on failure the next try is later, up to [RETRY_MAX_MS].
     * T96: the try is one of [PeerCodes]' (its code counts as tried on that phone, so discovery does not send it again).
     */
    private fun scheduleRetry(p: Peer, delayMs: Long) {
        p.nextRetryAt = System.currentTimeMillis() + delayMs
        retries[p.addr] = retryTimer.schedule({
            retries.remove(p.addr)
            if (peers[p.addr] === p && p.restored && p.error != CODE_CHANGED) {
                p.nextRetryAt = null
                val phone = p.phone
                if (!codes.claim(phone, p.code)) {
                    // Refused there before: its Share screen was reopened (a new code). Or a discovery try of the same code
                    // or of another kept code is under way: that one decides.
                    if (codes.isRefused(phone, p.code)) codeChanged(p) else scheduleRetry(p, RETRY_FIRST_MS)
                    return@schedule
                }
                p.retries++
                val outcome = try {
                    add(p.addr, p.code, auto = p.auto)
                    PeerCodes.Outcome.OPENED
                } catch (e: ShareClient.WrongCodeException) {
                    // Its Share screen was reopened (a new code): retrying would only spend that phone's five failures and
                    // make it replace its code again. Listed until the new code is entered (Use its maps) or it is forgotten.
                    Log.i(TAG, "peer ${p.name} at ${p.addr}: the kept code no longer opens; not retried")
                    PeerCodes.Outcome.WRONG
                } catch (e: Exception) {
                    Log.i(TAG, "peer ${p.name} at ${p.addr} did not answer (try ${p.retries}): ${e.message}")
                    if (p.error != CODE_CHANGED) scheduleRetry(p, (RETRY_FIRST_MS shl (p.retries - 1).coerceIn(0, 5)).coerceAtMost(RETRY_MAX_MS))
                    if (e is LanConnectException) PeerCodes.Outcome.UNREACHABLE else PeerCodes.Outcome.FAILED
                }
                codes.done(phone, p.code, outcome)
            }
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    /**
     * T96: [p]'s own code was refused by its phone (a retry, a refresh, a download or a tile got 403): it is `codeChanged`,
     * its retry is cancelled, it stops being a fetcher source, and the refusal is written down so a relaunch does not
     * send that code again. Listed until the code its screen shows now is entered (Use its maps) or it is forgotten.
     */
    private fun codeChanged(p: Peer) {
        if (p.error == CODE_CHANGED) return
        p.setState(CODE_CHANGED)
        p.nextRetryAt = null
        retries.remove(p.addr)?.cancel(false)
        if (peers[p.addr] === p) {
            updateSources()
            save()
        }
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

    /** The peers the online fetcher asks: those with a catalog that answered since the app started (T96: and whose code still opens). */
    private fun updateSources() = OnlineService.get(app).setPeers(peers.values.filter { it.catalog != null && !it.restored && it.error != CODE_CHANGED }.map { it.url })

    fun all(): List<Peer> = peers.values.sortedByDescending { it.addedAt }

    /** A peer by address, `peer://` URL, NSD name or display name. */
    fun find(key: String): Peer? = peers[key.removePrefix(SCHEME)] ?: peers.values.firstOrNull { it.name == key || it.nsdName == key || it.device == key }

    /** The code to reach [addr] with; T96: none for a peer whose code changed (the fetcher and downloads stop there). */
    fun codeFor(addr: String): String? = peers[addr]?.takeIf { it.error != CODE_CHANGED }?.code

    val knownCodes: List<String> get() = codes.codes

    fun learnCode(code: String, tryDiscovered: Boolean = true): String {
        val c = ShareClient.requireCode(code)
        // T90: the newest last, and at most MAX_CODES. T96: learned now, so fresh ([PeerCodes]): tried once on each phone.
        if (codes.learn(c)) {
            save()
            if (!tryDiscovered) return c
            // T90: phones discovery already resolved get the new code too (it may arrive after their announcement).
            runCatching { ShareSession.of(app).peers.peers().forEach(::onDiscovered) }
        }
        return c
    }

    /** T90: [code] is no longer any peer's (the phone it opened shows a new one, T96: or it was forgotten): not tried again. */
    private fun retireCode(code: String): Boolean {
        if (peers.values.any { it.code == code }) return false
        return codes.retire(code)
    }

    fun forgetCodes() {
        codes.clear()
        save()
    }

    fun setVia(from: String, to: String?) {
        if (to.isNullOrBlank()) via.remove(from) else via[from] = to
        save()
    }

    /** T96: the phone at [addr] as [PeerCodes] knows it (with the kept peer's NSD name and device id when there is one). */
    private fun phoneAt(addr: String): PeerCodes.Phone = peers[addr]?.phone ?: PeerCodes.Phone(addr)

    /**
     * One request to [peer] over the sealed link (`by=` added). T96: through [PeerCodes.send], so a code that phone
     * refused before is not sent again (a [ShareClient.WrongCodeException] without a request: each wrong one spends one
     * of its five failures), and a burst (a map view's tiles, a download's pieces) sends one request first when the code
     * has not answered for a second and the rest only once it opened. A refusal is written down; when it is the peer's
     * own code, the peer is `codeChanged` ([codeChanged]).
     */
    fun request(addr: String, code: String, method: String, path: String, body: ByteArray = ByteArray(0), readMs: Int = 30_000): SealedLink.Response {
        val target = path + (if ('?' in path) "&" else "?") + "by=" + self.first
        try {
            return codes.send(phoneAt(addr), code, waitMs = readMs + 15_000L) {
                ShareClient.sealed(ShareClient.Target("http://$addr", code), code, method, target, body, readMs)
            }.also { seen(addr) }
        } catch (e: ShareClient.WrongCodeException) {
            val p = peers[addr]?.takeIf { it.code == code }
            if (p != null) codeChanged(p) else save()
            throw e
        }
    }

    /**
     * Adds (or refreshes) the phone at [addr] (`host[:port]` or a share link) as a source: hello with its name, then its
     * catalog and areas. Throws on a wrong code or no answer (also kept as the peer's `error`; T96: a wrong code typed for
     * a known peer leaves it as it was, and its own code refused makes it `codeChanged`).
     */
    fun add(addr: String, code: String?, nsdName: String? = null, discoveredAt: Long? = null, resolvedAt: Long? = null, auto: Boolean = false): Peer {
        val t = ShareClient.parse(addr)
        val c = ShareClient.requireCode(code?.takeIf { it.isNotBlank() } ?: t.code)
        val hostPort = t.baseUrl.removePrefix("http://")
        val reach = via[hostPort] ?: hostPort
        val existing = peers[reach]
        val p = existing ?: Peer(reach, c)
        nsdName?.let { p.nsdName = it }
        discoveredAt?.let { p.discoveredAt = it }
        resolvedAt?.let { p.resolvedAt = it }
        // T96: a code kept from before counts toward this phone's one per session, also when the user sends it.
        codes.noteSent(phoneAt(reach), c)
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
            // T90: the same phone at another address (the network gave it a new one) replaces its old entry once it answers.
            val elsewhere = peers.values.filter { q -> q !== p && q.addr != reach && ((q.device != null && q.device == p.device) || (q.nsdName != null && q.nsdName == p.nsdName)) }
            // T96 (a T90 follow-up): a peer added by hand stays one when discovery reaches it again; T106: also when
            // discovery reaches it at another address than the one it was added at (the entry it replaces was by hand).
            p.auto = staysAuto(existing?.auto, elsewhere.map { it.auto }, auto)
            codes.opened(p.phone, c)
            learnCode(c, tryDiscovered = false)
            peers[reach] = p
            refresh(p)
            p.setState(null, restored = false)
            p.nextRetryAt = null
            retries.remove(reach)?.cancel(false) // T96: it answered: no retry pending
            elsewhere.forEach { peers.remove(it.addr, it); retries.remove(it.addr)?.cancel(false); retireCode(it.code) }
            if (oldCode != c) retireCode(oldCode)
            updateSources()
            save()
            Log.i(TAG, "peer ${p.name} at $reach: ${p.catalog?.packs?.size} packs, ${p.areas.size} areas")
            return p
        } catch (e: Exception) {
            when {
                e is ShareClient.WrongCodeException -> if (existing == null) p.setState("wrong code") // a kept peer: request() decided
                existing == null || c == p.code -> p.setState(e.message ?: e.javaClass.simpleName)
            }
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

    /**
     * T90: [refresh] for a peer that answered since the app started; a restored one is added again (hello, catalog, a source
     * again). T96: so is one whose code changed, which [request] answers at once (`codeChanged`, nothing sent).
     */
    fun reach(p: Peer): Peer = if (p.restored || p.error == CODE_CHANGED) add(p.addr, p.code, nsdName = p.nsdName, auto = p.auto) else refresh(p)

    /** Stops using [key] and forgets it (Forget on the Share screen); T96: its code is retired too, so discovery does not try it elsewhere. */
    fun remove(key: String): Boolean {
        val p = find(key) ?: return false
        peers.remove(p.addr)
        retries.remove(p.addr)?.cancel(false)
        retireCode(p.code)
        updateSources()
        save()
        return true
    }

    /**
     * Discovery found and resolved [d]: try the codes [PeerCodes] allows on it, one at a time, in the background. T96: the
     * kept peer this phone is (same device id in its NSD record, else same address or NSD name with no other device id)
     * gives its own code first; codes other kept peers hold are not tried here; a refused code is never sent again, and a
     * code kept from before goes at most once per session per phone (the launch retry's included).
     */
    fun onDiscovered(d: PeerList.Peer) {
        val hostPort = d.hostPort ?: return
        if (d.self) return
        // Our own announcement can resolve before NSD reports the name we registered under: skip our own address and port.
        val me = ShareSession.of(app)
        if (d.port == me.port && d.host in me.addresses()) return
        val reach = via[hostPort] ?: hostPort
        val kept = peers.values.firstOrNull { d.device != null && it.device == d.device }
            ?: (listOfNotNull(peers[reach]) + peers.values.filter { it.nsdName == d.name }).firstOrNull { d.device == null || it.device == null || it.device == d.device }
        if (kept != null && kept.addr == reach && kept.catalog != null && !kept.restored && kept.error == null) return // connected
        val phone = PeerCodes.Phone(reach, d.name, d.device ?: kept?.device)
        val plan = codes.plan(phone, kept?.code, peers.values.filter { it !== kept }.map { it.code }.toSet())
        if (plan.isEmpty()) return
        worker.execute {
            codes.attempt(phone, plan) { c ->
                try {
                    add(hostPort, c, d.name, d.firstSeenAt, d.resolvedAt, auto = true)
                    PeerCodes.Outcome.OPENED
                } catch (e: ShareClient.WrongCodeException) {
                    PeerCodes.Outcome.WRONG
                } catch (e: LanConnectException) {
                    Log.w(TAG, "peer ${d.name} at $hostPort: ${e.message}")
                    PeerCodes.Outcome.UNREACHABLE // not reachable now: tried again when it is resolved again
                } catch (e: Exception) {
                    Log.w(TAG, "peer ${d.name} at $hostPort: ${e.message}")
                    PeerCodes.Outcome.FAILED
                }
            }
        }
    }

    /** Downloads pack [id] of peer [key] (resumable, every archive checked against the peer's SHA-256). */
    fun download(key: String, id: String, activate: Boolean = false): PackDownloader.Job {
        val p = find(key) ?: throw NoSuchElementException("no peer '$key'; POST /share/peers/add first")
        // T90: a restored peer is tried at once; when it does not answer, its kept catalog plans the download, which then
        // resumes or fails as usual. T96: a changed code fails here (`codeChanged`) instead of starting a download that cannot.
        val c = try {
            reach(p).catalog
        } catch (e: ShareClient.WrongCodeException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: p.catalog ?: throw IllegalStateException("no catalog from ${p.addr}")
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
        "codes" to codes.json(), // T96: known, fresh, refusals kept, tries sent and held back
    )
}

/**
 * T106 (a T90 follow-up): whether a peer that answered is one discovery found ([PeerSources.Peer.auto]) or one the user
 * added: discovery's add ([auto] true) keeps a peer added by hand as one, whether it is the entry at the same address
 * ([existing]) or the entries of the same phone at other addresses it replaces ([replaced]). `onDiscovered` added the
 * phone at the address NSD resolved; a peer added by hand at another one (a typed address, an adb forward) was
 * replaced by a new entry with `auto` set. Pure, tested.
 */
internal fun staysAuto(existing: Boolean?, replaced: List<Boolean>, auto: Boolean): Boolean =
    auto && existing != false && replaced.all { it }

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
