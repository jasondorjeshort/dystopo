package io.github.jasondorjeshort.dystopo.server

import io.github.jasondorjeshort.dystopo.sources.elevation.HttpReply
import io.github.jasondorjeshort.dystopo.sources.elevation.SourceHttp
import io.github.jasondorjeshort.dystopo.sources.elevation.SourceIo
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

/**
 * T92: a byte budget on the server's own source downloads (the elevation readers under `cache/`: 3DEP,
 * Copernicus, the national lidar sources, geoid grids), which T74 left uncapped while capping the tile stores.
 * Two limits, each 0 for none:
 * - [maxRequestBytes]: the most one request may bring in. A range request asking for more is refused before
 *   it is sent; a whole-file download is cut off as it streams past the cap (nothing larger is held in memory).
 * - [maxHourBytes]: the most all requests together may bring in over the last 60 minutes (one-minute buckets).
 *   Past it, new requests are refused until older minutes drop out of the window.
 *
 * A refusal is a [SourceBudgetException] (not an I/O error, so the readers do not retry it): the tile that
 * needed the data fails, the server stays up, and the next hour's requests go through. `/stats` reports the
 * budget under `sourceDownloads` ([json]); the standalone server's config block is `sources`
 * (`maxRequestMb`, `maxHourMb`), `devtool serve` reads `DYSTOPO_SOURCE_CAPS=request=256,hour=8192`.
 *
 * [install] puts a [Client] under [SourceIo.http]; the same java.net.http settings as the readers' default
 * client (redirects followed, 20 s connect timeout).
 */
class SourceBudget(
    val maxRequestBytes: Long,
    val maxHourBytes: Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    class SourceBudgetException(message: String) : IllegalStateException(message)

    private val minuteBytes = LongArray(60)
    private val minuteOf = LongArray(60) { -1 }
    val requests = AtomicLong()
    val bytes = AtomicLong()
    val refusedRequestCap = AtomicLong()
    val refusedHourCap = AtomicLong()
    val largestRequest = AtomicLong()

    /** Bytes brought in over the last 60 minutes. */
    @Synchronized
    fun lastHourBytes(): Long {
        val now = clock() / 60_000
        var sum = 0L
        for (i in 0 until 60) if (minuteOf[i] > now - 60) sum += minuteBytes[i]
        return sum
    }

    @Synchronized
    private fun add(n: Long) {
        val m = clock() / 60_000
        val i = (m % 60).toInt()
        if (minuteOf[i] != m) { minuteOf[i] = m; minuteBytes[i] = 0 }
        minuteBytes[i] += n
    }

    /** Throws when the hourly budget is spent (checked before every request). */
    fun admit(url: String) {
        if (maxHourBytes > 0 && lastHourBytes() >= maxHourBytes) {
            refusedHourCap.incrementAndGet()
            throw SourceBudgetException("source download budget spent: ${maxHourBytes shr 20} MB in the last hour (sources.maxHourMb); not fetching $url")
        }
    }

    /** Throws when a range of [length] bytes is over the per-request cap. */
    fun admitRange(url: String, length: Int) {
        if (maxRequestBytes > 0 && length > maxRequestBytes) {
            refusedRequestCap.incrementAndGet()
            throw SourceBudgetException("source request of $length bytes is over the ${maxRequestBytes shr 20} MB per-request cap (sources.maxRequestMb): $url")
        }
        admit(url)
    }

    /** Counts a finished request of [n] bytes. */
    fun record(n: Long) {
        requests.incrementAndGet()
        bytes.addAndGet(n)
        largestRequest.accumulateAndGet(n, ::maxOf)
        add(n)
    }

    /** Reads [input] up to the per-request cap; one byte more is a [SourceBudgetException] (what was read still counts). */
    fun readCapped(url: String, input: java.io.InputStream): ByteArray = input.use { s ->
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 shl 10)
        var total = 0L
        while (true) {
            val n = s.read(buf)
            if (n < 0) break
            total += n
            if (maxRequestBytes > 0 && total > maxRequestBytes) {
                record(total)
                refusedRequestCap.incrementAndGet()
                throw SourceBudgetException("source download passed the ${maxRequestBytes shr 20} MB per-request cap (sources.maxRequestMb): $url")
            }
            out.write(buf, 0, n)
        }
        record(total)
        out.toByteArray()
    }

    fun json(): Map<String, Any?> = linkedMapOf(
        "maxRequestMb" to maxRequestBytes / (1 shl 20), "maxHourMb" to maxHourBytes / (1 shl 20),
        "requests" to requests.get(), "bytes" to bytes.get(), "lastHourBytes" to lastHourBytes(),
        "largestRequestBytes" to largestRequest.get(),
        "refused" to linkedMapOf("requestCap" to refusedRequestCap.get(), "hourCap" to refusedHourCap.get()),
    )

    /** The sources' HTTP client under this budget. */
    inner class Client : SourceHttp {
        private val client: HttpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build()

        private fun request(url: String, timeoutMs: Long) = HttpRequest.newBuilder(URI(url))
            .header("User-Agent", "dystopo-devtool/0.1")
            .timeout(Duration.ofMillis(timeoutMs))

        override fun range(url: String, offset: Long, length: Int): HttpReply {
            admitRange(url, length)
            val response = client.send(request(url, 120_000).header("Range", "bytes=$offset-${offset + length - 1}").build(), HttpResponse.BodyHandlers.ofInputStream())
            return HttpReply(response.statusCode(), readCapped(url, response.body()))
        }

        override fun get(url: String, timeoutMs: Long): HttpReply {
            admit(url)
            val response = client.send(request(url, timeoutMs).build(), HttpResponse.BodyHandlers.ofInputStream())
            return HttpReply(response.statusCode(), readCapped(url, response.body()))
        }
    }

    companion object {
        const val DEFAULT_REQUEST_MB = 256L
        const val DEFAULT_HOUR_MB = 8192L

        /** The budget [install] put in place, for `/stats`. */
        @Volatile var current: SourceBudget? = null
            internal set

        /** Installs [budget] under the elevation readers (process-wide) and returns it. */
        fun install(budget: SourceBudget): SourceBudget {
            SourceIo.http = budget.Client()
            current = budget
            return budget
        }

        fun ofMb(requestMb: Long, hourMb: Long) = SourceBudget(requestMb.coerceAtLeast(0) shl 20, hourMb.coerceAtLeast(0) shl 20)

        /** From `DYSTOPO_SOURCE_CAPS` (`request=256,hour=8192`, any subset; 0 is no cap), else the defaults. */
        fun fromEnv(env: String? = System.getenv("DYSTOPO_SOURCE_CAPS")): SourceBudget {
            var request = DEFAULT_REQUEST_MB
            var hour = DEFAULT_HOUR_MB
            for (part in env.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
                val (k, v) = part.split('=', limit = 2).let { it[0].trim() to it.getOrNull(1)?.trim()?.toLongOrNull() }
                require(v != null && v >= 0) { "DYSTOPO_SOURCE_CAPS: '$part' is not key=<MB>" }
                when (k) {
                    "request" -> request = v
                    "hour" -> hour = v
                    else -> throw IllegalArgumentException("DYSTOPO_SOURCE_CAPS: unknown key '$k' (request, hour)")
                }
            }
            return ofMb(request, hour)
        }
    }
}
