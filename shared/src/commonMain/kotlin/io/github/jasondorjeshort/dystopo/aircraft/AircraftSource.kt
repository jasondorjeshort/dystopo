package io.github.jasondorjeshort.dystopo.aircraft

import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson

/** T311: one poll's outcome. */
sealed class AircraftFetch {
    /** The feed answered: [ms] the request's time, [url] what was asked (null for the replay). */
    data class Ok(val poll: AircraftPoll, val ms: Long, val url: String?) : AircraftFetch()

    /** No connection could be made (no network, the host unreachable): "No flight data: offline". */
    data class Offline(val reason: String) : AircraftFetch()

    /** The feed answered with an error or something unreadable. */
    data class Failed(val status: Int?, val reason: String) : AircraftFetch()

    /** HTTP 429: the day's allowance is spent; [retryAfterS] from the Retry-After header. */
    data class Limited(val retryAfterS: Long?) : AircraftFetch()
}

/**
 * T311: where aircraft come from. Two: [AdsbLolSource] (the live feed, point and radius) and [ReplaySource] (a recorded
 * file, for tests, the devtool and the debug route). A second live feed would be a third implementation (the finding:
 * readsb's JSON fits adsb.fi and airplanes.live alike), after its terms allow it and its `sources.json` entry exists.
 */
interface AircraftSource {
    /** The registry id of what this source reads (`sources.json`), or null for a recorded file. */
    val registryId: String?
    val name: String
    fun poll(query: AircraftQuery, nowMs: Long): AircraftFetch
}

/** T311: an HTTP answer, as the platform's client gives it. */
class AircraftHttpReply(val status: Int, val body: ByteArray, val retryAfter: String? = null)

/** Thrown by an [AircraftHttp] when no connection was made at all. */
class AircraftNoConnection(message: String) : Exception(message)

/** T311: the platform's GET (the phone's binds `online/OnlineFetcher.kt`'s `HttpGet`; the devtool's is its own). */
fun interface AircraftHttp {
    fun get(url: String, headers: Map<String, String>): AircraftHttpReply
}

/**
 * T311: adsb.lol's live feed, point and radius (ODbL 1.0; `sources.json` `adsblol-live`). No key, no device id: the only
 * header is a generic User-Agent `Dystopo/<version>`. [clock] gives epoch ms.
 */
class AdsbLolSource(
    private val http: AircraftHttp,
    private val version: String,
    private val clock: () -> Long,
    val base: String = BASE,
) : AircraftSource {
    override val registryId: String = REGISTRY_ID
    override val name: String = "adsb.lol"

    override fun poll(query: AircraftQuery, nowMs: Long): AircraftFetch {
        val url = base + query.path
        val t0 = clock()
        val reply = try {
            http.get(url, mapOf("User-Agent" to "Dystopo/$version", "Accept" to "application/json"))
        } catch (e: AircraftNoConnection) {
            return AircraftFetch.Offline(e.message ?: "no connection")
        } catch (e: Exception) {
            return AircraftFetch.Failed(null, "${e::class.simpleName}: ${e.message}")
        }
        val ms = clock() - t0
        return when {
            reply.status == 429 -> AircraftFetch.Limited(AircraftQuota.retryAfter(reply.retryAfter))
            reply.status !in 200..299 -> AircraftFetch.Failed(reply.status, "HTTP ${reply.status}")
            else -> try {
                AircraftFetch.Ok(ReadsbParser.parse(reply.body.decodeToString(), clock()), ms, url)
            } catch (e: IllegalArgumentException) {
                AircraftFetch.Failed(reply.status, "unreadable answer: ${e.message}")
            }
        }
    }

    companion object {
        const val BASE = "https://api.adsb.lol"
        const val REGISTRY_ID = "adsblol-live"
    }
}

/**
 * T311: a recorded flight set (JSON) played back on the phone's clock: `{"loopS": <s>, "frames": [{"t": <s>, "ac":
 * [<readsb aircraft>...]}, ...]}`. A poll at [nowMs] answers the last frame at or before the time since [startMs] (looping
 * after `loopS`); the frame's `seen_pos` values are kept, so a position that stops updating ages as it would live. The
 * fixture is `shared/src/commonTest/resources/aircraft/rainier-replay.json`, synthetic aircraft near the `rainier` area.
 */
class ReplaySource(val replay: Replay, val startMs: Long, override val name: String = "replay") : AircraftSource {
    override val registryId: String? = null

    override fun poll(query: AircraftQuery, nowMs: Long): AircraftFetch {
        val f = replay.frameAt((nowMs - startMs) / 1000.0)
        return AircraftFetch.Ok(AircraftPoll(nowMs, f.aircraft, 0), 0, null)
    }

    class Frame(val t: Double, val aircraft: List<AircraftReport>)

    class Replay(val loopS: Double, val frames: List<Frame>) {
        fun frameAt(elapsedS: Double): Frame {
            val t = if (loopS > 0) ((elapsedS % loopS) + loopS) % loopS else elapsedS
            return frames.lastOrNull { it.t <= t } ?: frames.first()
        }

        companion object {
            fun parse(text: String): Replay {
                val root = MiniJson.parseObject(text)
                val frames = (root["frames"] as? List<*> ?: throw IllegalArgumentException("replay: no frames")).map { f ->
                    @Suppress("UNCHECKED_CAST")
                    val m = f as? Map<String, Any?> ?: throw IllegalArgumentException("replay: a frame is not an object")
                    val ac = (m["ac"] as? List<*>).orEmpty().mapNotNull { e ->
                        @Suppress("UNCHECKED_CAST")
                        (e as? Map<String, Any?>)?.let { ReadsbParser.parseOne(it) }
                    }
                    Frame((m["t"] as? Number)?.toDouble() ?: 0.0, ac)
                }.sortedBy { it.t }
                require(frames.isNotEmpty()) { "replay: no frames" }
                return Replay((root["loopS"] as? Number)?.toDouble() ?: 0.0, frames)
            }
        }
    }
}
