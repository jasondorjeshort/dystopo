package io.github.jasondorjeshort.dystopo.app.identity

import io.github.jasondorjeshort.dystopo.app.firstrun.CleartextPolicy
import io.github.jasondorjeshort.dystopo.identity.AttestationFormatException
import io.github.jasondorjeshort.dystopo.identity.Der
import io.github.jasondorjeshort.dystopo.identity.RevocationList
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/**
 * T216: this phone's copy of Google's attestation revocation list (ADR 0051), the one request the identity code
 * sends: `GET https://android.googleapis.com/attestation/status`, about 180 kB, at most once a day while online
 * ([IdentityRules.revocationDue]), triggered by the identity's use (the Share screen, `GET /identity`), never on a
 * timer. The file is kept in `files/identity/revocation.json` with its fetch time beside it, so the age survives a
 * restart; a download that does not parse keeps the old copy. The age is what the policy reads (a stranger's note
 * needs a list under 30 days, a group member any age) and what `GET /identity` shows.
 */
class RevocationCache(private val dir: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val file = File(dir, "revocation.json")
    private val stamp = File(dir, "revocation-fetched.txt")

    @Volatile private var loaded: RevocationList? = null
    @Volatile private var loadedOnce = false

    /** The last fetch attempt: when, the outcome, bytes, ms, or the error. */
    @Volatile var lastFetch: Map<String, Any?>? = null
        private set

    val fetches: Int get() = fetchCount
    @Volatile private var fetchCount = 0

    /** The cached list, read from disk once; null when none was ever fetched (or the copy is unreadable). */
    @Synchronized fun current(): RevocationList? {
        if (!loadedOnce) {
            loadedOnce = true
            loaded = runCatching {
                if (file.isFile) RevocationList.parse(file.readText(), stamp.takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull() ?: file.lastModified()) else null
            }.getOrNull()
        }
        return loaded
    }

    /** Fetches the list when [online] and it is due ([force]: regardless of age); returns what happened. */
    @Synchronized fun refreshIfDue(online: Boolean, force: Boolean = false): String {
        val now = clock()
        if (!force && !IdentityRules.revocationDue(current()?.fetchedAt, now)) return "fresh"
        if (!online) return "offline"
        val t0 = System.nanoTime()
        fetchCount++
        return try {
            val text = download()
            val list = RevocationList.parse(text, now)
            dir.mkdirs()
            val tmp = File(dir, "revocation.json.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                file.delete()
                if (!tmp.renameTo(file)) throw IOException("could not replace ${file.name}")
            }
            stamp.writeText(now.toString())
            loaded = list
            loadedOnce = true
            lastFetch = linkedMapOf("at" to Der.iso(now), "ok" to true, "bytes" to text.length, "entries" to list.entries, "ms" to (System.nanoTime() - t0) / 1_000_000)
            "fetched"
        } catch (e: Exception) {
            val why = if (e is AttestationFormatException) "unreadable list: ${e.message}" else e.message ?: e.javaClass.simpleName
            lastFetch = linkedMapOf("at" to Der.iso(now), "ok" to false, "error" to why, "ms" to (System.nanoTime() - t0) / 1_000_000)
            "failed"
        }
    }

    private fun download(): String {
        val conn = CleartextPolicy.open(URL)
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        try {
            val status = conn.responseCode
            if (status != 200) throw IOException("HTTP $status from $URL")
            val out = ByteArrayOutputStream()
            conn.inputStream.use { input ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > RevocationList.MAX_TEXT) throw IOException("the list is larger than ${RevocationList.MAX_TEXT} bytes")
                }
            }
            return out.toString("UTF-8")
        } finally {
            conn.disconnect()
        }
    }

    fun json(): Map<String, Any?> {
        val list = current()
        val now = clock()
        return linkedMapOf(
            "source" to URL,
            "entries" to list?.entries,
            "fetchedAt" to list?.fetchedAt?.let { Der.iso(it) },
            "ageDays" to list?.ageMillis(now)?.let { it / IdentityRules.DAY },
            "ageHours" to list?.ageMillis(now)?.let { it / 3_600_000L },
            "fetches" to fetchCount,
            "lastFetch" to lastFetch,
        )
    }

    companion object {
        const val URL = "https://android.googleapis.com/attestation/status"
    }
}
