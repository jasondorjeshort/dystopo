package io.github.jasondorjeshort.dystopo.sync.crypto

import io.github.jasondorjeshort.dystopo.packs.Sha256
import io.github.jasondorjeshort.dystopo.sync.EncryptedBundle
import io.github.jasondorjeshort.dystopo.sync.EncryptedBundle.Purpose
import io.github.jasondorjeshort.dystopo.sync.EncryptedBundle.SealException

/**
 * The encrypted LAN link between two phones (T72). Every request of the T54 link (`/share/hello`, the T44
 * `/sync/pull|push|status` routes) travels inside one `POST /share/sealed` whose body is an
 * [EncryptedBundle] frame ([Purpose.LAN_REQUEST]) holding the method, the path with its query, and the
 * body; the reply is a frame ([Purpose.LAN_RESPONSE]) holding the status, content type and body, bound to
 * the request by its salt. The share code never crosses the network; a listener refuses everything else.
 *
 * Plaintext layouts (after the frame is opened): request `1 | method | target | body`, reply
 * `1 | status (2 bytes, big-endian) | content type | body`, where strings are a LEB128 length plus UTF-8
 * and the body is the rest.
 */
object SealedLink {
    class Request(val method: String, val target: String, val body: ByteArray) {
        val path: String get() = target.substringBefore('?')

        /** The query, decoded the simple way (percent escapes are left alone; the link's own clients never send any). */
        val query: Map<String, String>
            get() = target.substringAfter('?', "").split('&').filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
    }

    class Response(val status: Int, val contentType: String, val body: ByteArray)

    private const val V = 1

    fun sealRequest(code: String, request: Request, random: (Int) -> ByteArray): ByteArray {
        val w = Out()
        w.byte(V); w.str(request.method); w.str(request.target); w.raw(request.body)
        return EncryptedBundle.seal(code, Purpose.LAN_REQUEST, w.bytes(), random)
    }

    /** Throws [SealException] (wrong code: [SealException.authFailed]). */
    fun openRequest(code: String, frame: ByteArray): Request {
        val p = EncryptedBundle.open(code, Purpose.LAN_REQUEST, frame)
        return parse(p) {
            if (it.byte() != V) throw SealException("unknown link version", authFailed = false)
            Request(it.str(), it.str(), it.rest())
        }
    }

    fun sealResponse(code: String, requestFrame: ByteArray, response: Response, random: (Int) -> ByteArray): ByteArray {
        val w = Out()
        w.byte(V); w.byte(response.status ushr 8); w.byte(response.status and 0xff); w.str(response.contentType); w.raw(response.body)
        return EncryptedBundle.seal(code, Purpose.LAN_RESPONSE, w.bytes(), random, EncryptedBundle.salt(requestFrame))
    }

    fun openResponse(code: String, requestFrame: ByteArray, frame: ByteArray): Response {
        val p = EncryptedBundle.open(code, Purpose.LAN_RESPONSE, frame, EncryptedBundle.salt(requestFrame))
        return parse(p) {
            if (it.byte() != V) throw SealException("unknown link version", authFailed = false)
            val status = (it.byte() shl 8) or it.byte()
            Response(status, it.str(), it.rest())
        }
    }

    /**
     * Remembers the salts of requests already served (up to [capacity], oldest forgotten first), so a
     * recorded request replayed on the network is refused. The code changes whenever the Share screen
     * reopens, so a session's guard only needs that session's salts.
     */
    class ReplayGuard(private val capacity: Int = 50_000) {
        private val seen = LinkedHashSet<String>()

        /** True the first time [frame]'s salt is seen. */
        fun firstUse(frame: ByteArray): Boolean {
            val key = Sha256.toHex(EncryptedBundle.salt(frame))
            if (key in seen) return false
            seen.add(key)
            if (seen.size > capacity) seen.remove(seen.first())
            return true
        }

        val size: Int get() = seen.size
    }

    private class Out {
        private val b = ArrayList<Byte>()
        private var bulk: ByteArray? = null
        fun byte(v: Int) { b.add(v.toByte()) }
        fun str(s: String) {
            val u = s.encodeToByteArray()
            var n = u.size
            while (n >= 0x80) { byte((n and 0x7f) or 0x80); n = n ushr 7 }
            byte(n)
            u.forEach { b.add(it) }
        }
        fun raw(bytes: ByteArray) { bulk = bytes }
        fun bytes(): ByteArray = b.toByteArray() + (bulk ?: ByteArray(0))
    }

    private class In(private val p: ByteArray) {
        var i = 0
        fun byte(): Int = p[i++].toInt() and 0xff
        fun str(): String {
            var n = 0
            var shift = 0
            while (true) {
                val x = byte()
                n = n or ((x and 0x7f) shl shift)
                if (x < 0x80) break
                shift += 7
                if (shift > 28) throw SealException("bad length", authFailed = false)
            }
            if (n < 0 || n > p.size - i) throw SealException("bad length", authFailed = false)
            return p.decodeToString(i, i + n).also { i += n }
        }
        fun rest(): ByteArray = p.copyOfRange(i, p.size)
    }

    private inline fun <T> parse(p: ByteArray, block: (In) -> T): T = try {
        block(In(p))
    } catch (e: SealException) {
        throw e
    } catch (e: RuntimeException) {
        throw SealException("malformed link message: ${e::class.simpleName}", authFailed = false)
    }
}
