package io.github.jasondorjeshort.dystopo.app.share

import io.github.jasondorjeshort.dystopo.app.packs.PackDownloader
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * T78: a nearby phone's pack archive as [PackDownloader] reads it. `peer://<host:port>/peer/file/<file>` opened from
 * byte `have` asks the phone ([PeerServe]) for the file in [CHUNK] pieces over the sealed link, each piece encrypted
 * under the share code, and presents them as one HTTP-like ranged answer: 206 with `Content-Range: bytes have-last/total`
 * and a body stream that keeps [AHEAD] pieces in flight while the current one is written. So the
 * downloader's plan, resume and SHA-256 check apply unchanged: a dropped link leaves the part file where it was and the
 * next attempt asks from there; a file that changed on the other phone (its hash is no longer the catalog's) fails the
 * job as a hash mismatch, with nothing installed.
 */
object PeerFiles {
    /** One sealed reply's piece of a file ([PeerServe.MAX_CHUNK]): the size a peer starts at. */
    const val CHUNK = PeerServe.MAX_CHUNK

    /** The smallest piece [chunkFor] falls back to. */
    const val MIN_CHUNK = 256 shl 10

    /**
     * The piece size in use per peer address. A piece that fails (a reply cut short, a timeout) halves it, down to
     * [MIN_CHUNK], and the downloader's retry resumes from the part file with the smaller pieces. Seen over a slow
     * path (an emulator reaching the other through the host's slirp and an adb forward, about 220 KB/s): every 4 MiB
     * reply arrived shorter than its Content-Length while small replies were whole. A real LAN keeps 4 MiB.
     */
    private val chunkFor = java.util.concurrent.ConcurrentHashMap<String, Int>()

    fun chunk(addr: String): Int = chunkFor[addr] ?: CHUNK

    val shrinks = AtomicLong()

    private fun shrink(addr: String, failed: Int) {
        val smaller = maxOf(MIN_CHUNK, failed / 2)
        if (smaller < failed && chunkFor.put(addr, smaller) != smaller) shrinks.incrementAndGet()
    }

    /** Pieces requested ahead of the one being written. */
    const val AHEAD = 4

    val requests = AtomicLong()
    val bytes = AtomicLong()
    val ms = AtomicLong()

    private val ahead = Executors.newCachedThreadPool { r -> Thread(r, "peer-file-ahead").apply { isDaemon = true } }

    /** One sealed reply: its status, the offset of [body] in the file, and the file's size. */
    internal class Piece(val status: Int, val first: Long, val total: Long?, val body: ByteArray)

    private fun parse(url: String): Pair<String, String> {
        val rest = url.removePrefix(PeerSources.SCHEME)
        val addr = rest.substringBefore('/')
        val path = "/" + rest.substringAfter('/', "").substringBefore('?')
        require(addr.isNotEmpty() && path.startsWith(PeerServe.FILE_PREFIX)) { "not a peer file URL: $url" }
        return addr to path
    }

    private fun piece(addr: String, path: String, from: Long, len: Int, sha: String?): Piece {
        val code = PeerSources.codeFor(addr) ?: throw IOException("peer $addr is not connected (open its Share screen and add it again)")
        val t0 = System.nanoTime()
        val r = try {
            PeerSources.request(addr, code, "GET", "$path?from=$from&len=$len" + (sha?.let { "&sha=$it" } ?: ""), readMs = 60_000)
        } catch (e: IOException) {
            if (e !is LanConnectException) shrink(addr, len) // connected, then the reply failed: try smaller pieces
            throw e
        }
        ms.addAndGet((System.nanoTime() - t0) / 1_000_000)
        requests.incrementAndGet()
        bytes.addAndGet(r.body.size.toLong())
        val params = r.contentType.split(';').drop(1).associate { it.substringBefore('=').trim() to it.substringAfter('=', "").trim() }
        if (r.status == 409) throw PackDownloader.HashMismatch("${path.substringAfterLast('/')}: changed on the other phone since its catalog was read; nothing installed")
        return Piece(r.status, params["first"]?.toLongOrNull() ?: from, params["total"]?.toLongOrNull(), r.body)
    }

    /**
     * T90, a debug check: one piece of [file] from peer [addr] (bytes [from] to [from] + [len]), as the downloader asks for it,
     * with its SHA-256 and timing (`POST /share/peers/piece`). Throws like a piece of a download (a reply cut short).
     */
    fun fetchPiece(addr: String, file: String, from: Long, len: Int): Map<String, Any?> {
        val t0 = System.nanoTime()
        val p = piece(addr, PeerServe.FILE_PREFIX + file, from, len, null)
        val ms = (System.nanoTime() - t0) / 1_000_000
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(p.body).joinToString("") { "%02x".format(it) }
        return linkedMapOf(
            "status" to p.status, "first" to p.first, "total" to p.total, "bytes" to p.body.size, "sha256" to sha,
            "ms" to ms, "bytesPerSec" to if (ms > 0) p.body.size * 1000L / ms else null,
        )
    }

    fun open(url: String, have: Long, expectSha256: String?): PackDownloader.RangeConn {
        val (addr, path) = parse(url)
        return openWith(have, chunk(addr)) { from, len -> piece(addr, path, from, len, expectSha256) }
    }

    /** The ranged answer from byte [have] over pieces [fetch] returns (the request is sent when the status is read). */
    internal fun openWith(have: Long, chunk: Int = CHUNK, fetch: (Long, Int) -> Piece): PackDownloader.RangeConn {
        return object : PackDownloader.RangeConn {
            val first: Piece by lazy { fetch(have, chunk) }
            override val status: Int get() = first.status
            override fun header(name: String): String? = when (name.lowercase()) {
                "content-range" -> first.total?.let { t -> if (first.status == 206) "bytes ${first.first}-${t - 1}/$t" else "bytes */$t" }
                else -> null
            }
            override val contentLength: Long? get() = first.total?.let { if (first.status == 206) it - first.first else null }
            override val redirect: String? get() = null
            override fun body(): InputStream = Stream(first, chunk, fetch)
            override fun close() {}
        }
    }

    /** The pieces from [start] on, [AHEAD] requests in flight (each phone seals and opens on several cores at once). */
    private class Stream(start: Piece, val chunk: Int, val fetch: (Long, Int) -> Piece) : InputStream() {
        private val total = start.total ?: (start.first + start.body.size)
        private var cur = start.body
        private var pos = 0
        /** Where the next piece to read starts, and where the next request asks from. */
        private var next = start.first + start.body.size
        private var asked = next
        private val pending = ArrayDeque<CompletableFuture<Piece>>()

        init {
            fill()
        }

        private fun fill() {
            while (pending.size < AHEAD && asked < total) {
                val from = asked
                pending.addLast(CompletableFuture.supplyAsync({ fetch(from, chunk) }, ahead))
                asked += chunk
            }
        }

        private fun advance(): Boolean {
            val f = pending.removeFirstOrNull() ?: return false
            val p = try {
                f.get()
            } catch (e: ExecutionException) {
                throw (e.cause as? IOException) ?: IOException(e.cause?.message ?: "peer read failed", e.cause)
            }
            if (p.status != 206 || p.first != next || p.body.isEmpty()) throw IOException("peer answered ${p.status} at ${p.first} (wanted $next)")
            cur = p.body
            pos = 0
            next += p.body.size
            if (p.body.size != chunk && next < total) throw IOException("peer sent ${p.body.size} bytes at ${p.first}, expected $chunk")
            fill()
            return true
        }

        override fun read(): Int {
            val b = ByteArray(1)
            return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (pos >= cur.size) if (!advance()) return -1
            val n = minOf(len, cur.size - pos)
            System.arraycopy(cur, pos, b, off, n)
            pos += n
            return n
        }

        override fun close() {
            pending.forEach { it.cancel(true) }
            pending.clear()
        }
    }

    fun json(): Map<String, Any?> = linkedMapOf("requests" to requests.get(), "bytes" to bytes.get(), "msTotal" to ms.get(), "chunk" to CHUNK, "chunkByPeer" to chunkFor.toSortedMap(), "shrinks" to shrinks.get(), "ahead" to AHEAD)
}
