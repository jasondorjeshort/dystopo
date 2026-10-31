package io.github.jasondorjeshort.dystopo.app.packs

import android.util.Log
import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.app.PackFiles
import io.github.jasondorjeshort.dystopo.packs.CatalogPack
import io.github.jasondorjeshort.dystopo.packs.CatalogStatus
import io.github.jasondorjeshort.dystopo.packs.DerivedFile
import io.github.jasondorjeshort.dystopo.packs.InstalledPack
import io.github.jasondorjeshort.dystopo.packs.PackCatalog
import io.github.jasondorjeshort.dystopo.packs.PackNames
import io.github.jasondorjeshort.dystopo.packs.PackRole
import io.github.jasondorjeshort.dystopo.packs.ShippedGraph
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * The server's pack catalog on the phone (T53): fetches `GET /catalog` (T52's server, or `devtool serve`;
 * `/out/catalog.json` on a plain static host), compares each entry's content hash with what the device has,
 * and starts downloads and updates through [PackDownloader.startCatalog], which verifies every archive's
 * SHA-256 before it is used.
 *
 * What the device has: the SHA-256 of each installed archive, recorded in `packs/.catalog/<name>.json`
 * when a catalog download finishes, or computed in the background (one file at a time) for packs that
 * arrived another way (pushed, built per view, an older download). A record is trusted while the file's
 * path, size and mtime are unchanged.
 *
 * T189 (ADR 0049's B): an entry's shipped routing graph (`derived`, [CatalogPack.graph]) rides along with a download,
 * an update and a peer's download ([PackDownloader.startCatalog] fetches it after the archives when it matches them);
 * [offeredGraph] answers the route planner's question for a pack already on the device (the third fetch point), and
 * [recordGraph] keeps where an installed graph came from (`graph` in the pack's record: path, size, mtime, SHA-256, the
 * server and its `builtAtMs`), so `GET /route/status` can say `source: shipped` while the file is unchanged.
 */
class PackCatalogClient(private val manager: PackManager, packsDir: File) {
    private val storeDir = File(packsDir, ".catalog").apply { mkdirs() }
    private val hasher = Executors.newSingleThreadExecutor { r -> Thread(r, "dystopo-pack-hash").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    private val hashing = ConcurrentHashMap.newKeySet<String>()

    @Volatile var catalog: PackCatalog? = null
        private set
    @Volatile var server: String? = null
        private set
    @Volatile var fetchedAt = 0L
        private set
    @Volatile var fetchMs = 0L
        private set
    @Volatile var error: String? = null
        private set
    @Volatile var skipped: List<String> = emptyList()
        private set
    @Volatile var source: String? = null
        private set

    /** The server set in the app ([AppSettings.server]), or null. */
    fun configuredServer(): String? = AppSettings.server.ifEmpty { null }

    /** Fetches the catalog from [server] (default: the app's server). Throws when there is no server or no catalog. */
    fun refresh(server: String? = null): PackCatalog {
        val s = (server ?: configuredServer())?.trimEnd('/') ?: throw IllegalArgumentException("no server set: POST /settings?server=http://host:port")
        val started = System.currentTimeMillis()
        try {
            var lastError: String? = null
            for (path in listOf("/catalog", "/out/catalog.json")) {
                val (status, text) = get("$s$path")
                if (status != 200) { lastError = "$path: HTTP $status"; continue }
                val skip = ArrayList<String>()
                val c = PackCatalog.parse(MiniJson.parseObject(text), skip)
                catalog = c
                this.server = s
                skipped = skip
                source = path
                error = null
                fetchedAt = System.currentTimeMillis()
                fetchMs = fetchedAt - started
                return c
            }
            throw IOException("no catalog on $s ($lastError)")
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
            Log.w(PackManager.TAG, "catalog from $s failed: $error")
            throw e
        }
    }

    /** The cached catalog when it is from [server] and younger than [maxAgeMs], else a fresh one. */
    fun current(maxAgeMs: Long = 30_000, server: String? = null): PackCatalog {
        val s = (server ?: configuredServer())?.trimEnd('/')
        val c = catalog
        if (c != null && s == this.server && System.currentTimeMillis() - fetchedAt < maxAgeMs) return c
        return refresh(s)
    }

    private fun get(url: String): Pair<Int, String> {
        val conn = io.github.jasondorjeshort.dystopo.app.firstrun.CleartextPolicy.open(url) // T79: plain http only to home-network hosts
        conn.connectTimeout = 5_000
        conn.readTimeout = 60_000 // the first catalog request hashes the server's packs
        try {
            val status = conn.responseCode
            val text = (if (status < 400) conn.inputStream else conn.errorStream)?.use { it.readBytes().decodeToString() }.orEmpty()
            return status to text
        } finally {
            conn.disconnect()
        }
    }

    // --- what the device has ---

    private fun recordFile(name: String) = File(storeDir, "$name.json")

    private fun readRecord(name: String): Map<String, Any?> =
        runCatching { MiniJson.parseObject(recordFile(name).readText()) }.getOrDefault(emptyMap())

    @Synchronized
    private fun writeRecord(name: String, m: Map<String, Any?>) {
        val f = recordFile(name)
        val tmp = File(storeDir, "$name.json.tmp")
        tmp.writeText(MiniJson.encode(m))
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    private fun fileFacts(f: File, sha: String) = linkedMapOf("path" to f.absolutePath, "bytes" to f.length(), "mtime" to f.lastModified(), "sha256" to sha)

    /** The recorded SHA-256 of [f] (role [role] of pack [name]) when the file is unchanged since; else null. */
    private fun recorded(name: String, role: PackRole, f: File): String? {
        @Suppress("UNCHECKED_CAST")
        val r = ((readRecord(name)["files"] as? Map<String, Any?>)?.get(role.label) as? Map<String, Any?>) ?: return null
        val same = r["path"] == f.absolutePath && (r["bytes"] as? Number)?.toLong() == f.length() && (r["mtime"] as? Number)?.toLong() == f.lastModified()
        return if (same) r["sha256"] as? String else null
    }

    @Synchronized
    private fun store(name: String, role: PackRole, f: File, sha: String, catalogInfo: Map<String, Any?>? = null) {
        val old = readRecord(name)
        @Suppress("UNCHECKED_CAST")
        val files = LinkedHashMap((old["files"] as? Map<String, Any?>).orEmpty())
        files[role.label] = fileFacts(f, sha)
        val m = LinkedHashMap(old)
        m["name"] = name
        m["files"] = files
        catalogInfo?.let { m.putAll(it) }
        writeRecord(name, m)
    }

    /** Called by the downloader once a catalog download or update is installed: the hashes it verified. */
    fun recordInstalled(name: String, catalogInfo: Map<String, Any?>, files: Map<PackRole, Pair<File, String>>) {
        for ((role, p) in files) store(name, role, p.first, p.second)
        synchronized(this) { writeRecord(name, LinkedHashMap(readRecord(name)).apply { putAll(catalogInfo); put("installedAt", System.currentTimeMillis()) }) }
    }

    fun forget(name: String) {
        if (PackNames.isValidName(name)) recordFile(name).delete()
    }

    /**
     * What the device has of pack [files]: each archive's SHA-256 when known. With [compute], unknown ones
     * are hashed in the background (the next call sees them).
     */
    fun installed(files: PackFiles, compute: Boolean = true): InstalledPack {
        val m = LinkedHashMap<PackRole, String?>()
        for ((role, f) in listOf(PackRole.DEM to files.dem, PackRole.VECTOR to files.vector)) {
            f ?: continue
            val sha = recorded(files.name, role, f)
            m[role] = sha
            if (sha == null && compute) hashLater(files.name, role, f)
        }
        return InstalledPack(files.name, m)
    }

    /** [installed] with every hash computed now (for an update decision). */
    fun installedNow(files: PackFiles): InstalledPack {
        for ((role, f) in listOf(PackRole.DEM to files.dem, PackRole.VECTOR to files.vector)) {
            f ?: continue
            if (recorded(files.name, role, f) == null) store(files.name, role, f, PackDownloader.sha256(f))
        }
        return installed(files, compute = false)
    }

    private fun hashLater(name: String, role: PackRole, f: File) {
        val key = f.absolutePath
        if (!hashing.add(key)) return
        hasher.execute {
            try {
                if (f.isFile && recorded(name, role, f) == null) {
                    val t = System.currentTimeMillis()
                    store(name, role, f, PackDownloader.sha256(f))
                    Log.i(PackManager.TAG, "hashed ${f.name}: ${f.length()} bytes in ${System.currentTimeMillis() - t} ms")
                }
            } catch (e: Exception) {
                Log.w(PackManager.TAG, "hashing ${f.name} failed", e)
            } finally {
                hashing.remove(key)
            }
        }
    }

    // --- catalog rows ---

    /** One catalog pack as the device sees it. */
    class Row(val pack: CatalogPack, val status: CatalogStatus, val installed: InstalledPack?, val job: Map<String, Any?>?) {
        fun json(): Map<String, Any?> = pack.json() + linkedMapOf(
            "status" to status.label,
            "installedHash" to installed?.hash,
            "installedFiles" to installed?.sha256?.mapKeys { it.key.label },
            "download" to job,
        )
    }

    /** Every pack of [c] with its status on this device. */
    fun rows(c: PackCatalog? = catalog): List<Row> {
        c ?: return emptyList()
        val local = manager.catalog().associateBy { it.name }
        val jobs = manager.downloads.snapshot().associateBy { it["name"] as? String }
        return c.packs.map { p ->
            val job = jobs[p.name]
            val running = job?.get("state").let { it == "queued" || it == "running" }
            val inst = local[p.name]?.takeIf { it.dem != null || it.vector != null }?.let { installed(it) }
            Row(p, CatalogStatus.of(p, inst, running), inst, job)
        }
    }

    private fun catalogInfo(p: CatalogPack) = linkedMapOf<String, Any?>("server" to server, "id" to p.id, "hash" to p.hash, "builtAt" to p.builtAt)

    /** Downloads catalog pack [id] (not on the device yet; an installed one is updated instead). */
    fun download(id: String, activate: Boolean = false): PackDownloader.Job {
        val p = current().find(id) ?: throw NoSuchElementException("no pack '$id' in the catalog of $server")
        if (manager.catalog().any { it.name == p.name && (it.dem != null || it.vector != null) }) return update(p.name) // T65: vector-only packs too
        val s = server ?: throw IllegalStateException("no server")
        val files = p.files.associate { it.role to Triple(PackCatalog.resolveUrl(s, it.url), it.sha256, it.bytes) }
        val g = p.graph // T189
        return manager.downloads.startCatalog(p.name, files, emptySet(), update = false, activate = activate, catalog = catalogInfo(p), shipped = g, shippedUrl = g?.let { PackCatalog.resolveUrl(s, it.url) })
    }

    /**
     * T78: downloads (or updates) pack [id] of a nearby phone's [catalog] (its `/peer/catalog`), the archives from
     * [source] (`peer://<host:port>`) through the same downloader: resumable, and every archive checked against the
     * SHA-256 the other phone published. [label] names the phone in the job's catalog record (`via`).
     */
    fun downloadFrom(source: String, catalog: PackCatalog, id: String, activate: Boolean = false, label: String? = null): PackDownloader.Job {
        val p = catalog.find(id) ?: throw NoSuchElementException("no pack '$id' on $source")
        val info = linkedMapOf<String, Any?>("server" to source, "id" to p.id, "hash" to p.hash, "builtAt" to p.builtAt, "via" to label)
        val local = manager.catalog().firstOrNull { it.name == p.name && (it.dem != null || it.vector != null) }
        val g = p.graph // T189: a peer's matching graph rides along too
        val gUrl = g?.let { PackCatalog.resolveUrl(source, it.url) }
        if (local == null) {
            val files = p.files.associate { it.role to Triple(PackCatalog.resolveUrl(source, it.url), it.sha256, it.bytes) }
            return manager.downloads.startCatalog(p.name, files, emptySet(), update = false, activate = activate, catalog = info, shipped = g, shippedUrl = gUrl)
        }
        val inst = installedNow(local)
        val changed = CatalogStatus.changedRoles(p, inst)
        require(changed.isNotEmpty()) { "pack '${p.name}' is already on this phone with the same content (${p.hash})" }
        val files = p.files.filter { it.role in changed }.associate { it.role to Triple(PackCatalog.resolveUrl(source, it.url), it.sha256, it.bytes) }
        return manager.downloads.startCatalog(p.name, files, p.files.map { it.role }.toSet() - changed, update = true, activate = false, catalog = info, shipped = g, shippedUrl = gUrl)
    }

    /** Updates installed pack [name] to the catalog's content: only the archives whose SHA-256 differs are fetched. */
    fun update(name: String): PackDownloader.Job {
        val p = current(maxAgeMs = 0).packs.firstOrNull { it.name == name } ?: throw NoSuchElementException("no pack '$name' in the catalog of $server")
        val local = manager.catalog().firstOrNull { it.name == name } ?: throw NoSuchElementException("pack '$name' is not on the device; download it")
        val inst = installedNow(local)
        val changed = CatalogStatus.changedRoles(p, inst)
        val removed = inst.sha256.keys - p.files.map { it.role }.toSet()
        require(changed.isNotEmpty() || removed.isNotEmpty()) { "pack '$name' is up to date (${p.hash})" }
        val s = server ?: throw IllegalStateException("no server")
        val files = p.files.filter { it.role in changed }.associate { it.role to Triple(PackCatalog.resolveUrl(s, it.url), it.sha256, it.bytes) }
        val unchanged = p.files.map { it.role }.toSet() - changed
        val g = p.graph // T189: the second fetch point, with a pack update
        return manager.downloads.startCatalog(name, files, unchanged, update = true, activate = false, catalog = catalogInfo(p), shipped = g, shippedUrl = g?.let { PackCatalog.resolveUrl(s, it.url) })
    }

    // --- T189: shipped routing graphs ---

    /** T189: what [offeredGraph] found: the entry and its absolute URL, or why there is none. */
    class Offer(val entry: DerivedFile?, val url: String?, val reason: String?)

    /**
     * T189, the planner's third fetch point: the graph the configured server's catalog ships for pack [name], when its
     * format, version, tile zoom and both fingerprints match the archives on the device ([vector], [dem]). The cached
     * catalog when it is from this server and younger than [maxAgeMs], else one fetch (errors are a reason, not thrown).
     */
    fun offeredGraph(name: String, vector: File, dem: File?, maxAgeMs: Long = 10 * 60_000L): Offer {
        val s = configuredServer() ?: return Offer(null, null, "no server set")
        val c = try {
            current(maxAgeMs, s)
        } catch (e: Exception) {
            return Offer(null, null, "the server's catalog could not be read (${e.message})")
        }
        val p = c.packs.firstOrNull { it.name == name } ?: return Offer(null, null, "the server has no pack '$name'")
        val readable = p.graph ?: return Offer(null, null, if (p.derived.isEmpty()) "the server ships no graph with this pack" else "the server's graph is a version this app does not read")
        val why = try {
            ShippedGraph.mismatch(readable, PackDownloader.fingerprint(vector), dem?.let { PackDownloader.fingerprint(it) }.orEmpty())
        } catch (e: Exception) {
            "the archives could not be read (${e.message})"
        }
        if (why != null) return Offer(null, null, why)
        return Offer(readable, PackCatalog.resolveUrl(server ?: s, readable.url), null)
    }

    /** T189: called by the downloader once a shipped graph is installed as [file]: where it came from, so the status can say `shipped`. */
    fun recordGraph(name: String, file: File, sha256: String, entry: DerivedFile, catalogInfo: Map<String, Any?>?) {
        val builtAtMs = runCatching {
            io.github.jasondorjeshort.dystopo.app.FileRangeSource(file).use { src ->
                io.github.jasondorjeshort.dystopo.routing.GraphTileFile.open { o, l -> src.read(o, l) }.source.builtAtMs
            }
        }.getOrNull()
        synchronized(this) {
            writeRecord(name, LinkedHashMap(readRecord(name)).apply {
                put("name", name)
                put("graph", linkedMapOf(
                    "path" to file.absolutePath, "bytes" to file.length(), "mtime" to file.lastModified(), "sha256" to sha256,
                    "version" to entry.version, "fingerprint" to entry.fingerprint, "demFingerprint" to entry.demFingerprint,
                    "builtAtMs" to builtAtMs, "server" to catalogInfo?.get("server"), "via" to catalogInfo?.get("via"), "installedAt" to System.currentTimeMillis(),
                ))
            })
        }
    }

    /** T189: the record of the shipped graph of pack [name] when [file] is still that file (path, size, mtime), else null. */
    fun shippedGraph(name: String, file: File): Map<String, Any?>? {
        if (!PackNames.isValidName(name) || !file.isFile) return null
        @Suppress("UNCHECKED_CAST")
        val r = readRecord(name)["graph"] as? Map<String, Any?> ?: return null
        val same = r["path"] == file.absolutePath && (r["bytes"] as? Number)?.toLong() == file.length() && (r["mtime"] as? Number)?.toLong() == file.lastModified()
        return r.takeIf { same }
    }

    /** The installed packs whose catalog entry has other content (fresh catalog). */
    fun updates(): List<Row> = rows(current()).filter { it.status == CatalogStatus.UPDATE }

    fun json(rows: List<Row> = rows()): Map<String, Any?> = linkedMapOf(
        "server" to server, "configuredServer" to configuredServer(), "source" to source,
        "fetchedAt" to fetchedAt.takeIf { it > 0 }, "fetchMs" to fetchMs.takeIf { fetchedAt > 0 },
        "error" to error, "skipped" to skipped.ifEmpty { null }, "generatedAt" to catalog?.generatedAt,
        "count" to rows.size,
        "statusCounts" to rows.groupingBy { it.status.label }.eachCount(),
        "hashing" to hashing.size,
        "packs" to rows.map { it.json() },
        "overlay" to CatalogOverlay.json(),
    )
}
