package io.github.jasondorjeshort.dystopo.app.packs

import io.github.jasondorjeshort.dystopo.packs.PackNames
import io.github.jasondorjeshort.dystopo.packs.PackRole
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.net.ProtocolException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import javax.net.ssl.SSLException
import kotlin.math.ceil
import kotlin.math.floor

/**
 * T222: what a download's row says, in plain words, and what can be done about it. Pure: every sentence the pack rows, the
 * map's overlay and `GET /packs/download` show comes from here. The wording follows the wave 28 vocabulary survey's failure
 * practice (a stopped download says why and offers Retry; a paused one says when it goes on; a full phone says how much to
 * free), with the survey's nouns left for T225.
 */
object DownloadStatus {
    /** Why a download stopped, each with the words after "Download stopped: ". [SPACE]'s sentence is [spaceSentence]. */
    enum class Failure(val key: String, val reason: String) {
        CONNECTION("connection", "the connection dropped"),
        SERVER("server", "the server is not answering"),
        NOT_FOUND("notFound", "the server no longer has this file"),
        DAMAGED("damaged", "the file arrived damaged"),
        REFUSED("refused", "the server sent it to an address this app refuses"),
        BUILD("build", "the map could not be built"),
        SPACE("space", "there is not enough space"),
        OTHER("other", "something went wrong"),
    }

    /** What a row offers, by its label. */
    enum class Action(val label: String) { RESUME_NOW("Resume now"), RETRY("Retry"), REMOVE("Remove"), STORAGE("Storage") }

    /** A download's one state word, its sentence when it needs one, and its controls. */
    data class Row(val word: String, val sentence: String?, val actions: List<Action> = emptyList()) {
        fun json(): Map<String, Any?> = linkedMapOf("word" to word, "sentence" to sentence, "actions" to actions.map { it.label })
    }

    const val WORD_BUILDING = "building"
    const val WORD_DOWNLOADING = "downloading"
    const val WORD_WAITING = "waiting"
    const val WORD_PAUSED = "paused"
    const val WORD_STOPPED = "stopped"

    /**
     * The row of a download in [state] (a [PackDownloader.Job]'s, or a region job's): [awaiting] is a job picked up again at
     * launch that has received nothing yet, which reads as paused until the bytes flow.
     */
    fun of(state: String?, failure: Failure? = null, shortBytes: Long? = null, percent: Double? = null, awaiting: Boolean = false): Row = when {
        state == "paused" || (awaiting && (state == "queued" || state == "running" || state == "downloading")) ->
            Row(WORD_PAUSED, pausedSentence(percent), listOf(Action.RESUME_NOW, Action.REMOVE))
        state == "queued" -> Row(WORD_WAITING, null)
        state == "running" || state == "downloading" -> Row(WORD_DOWNLOADING, null)
        state == "building" -> Row(WORD_BUILDING, null)
        state == "failed" -> if (failure == Failure.SPACE) Row(WORD_STOPPED, spaceSentence(shortBytes), listOf(Action.STORAGE, Action.RETRY, Action.REMOVE))
            else Row(WORD_STOPPED, stoppedSentence(failure), listOf(Action.RETRY, Action.REMOVE))
        else -> Row(state ?: "", null)
    }

    /** "Paused at 40%. Continues when you are online"; the percent stays out when the total is not known yet. */
    fun pausedSentence(percent: Double?): String {
        val at = percent?.let { " at ${floor(it).toInt().coerceIn(0, 99)}%" }.orEmpty()
        return "Paused$at. Continues when you are online"
    }

    fun stoppedSentence(failure: Failure?): String = when (failure) {
        null -> "Download stopped."
        Failure.SPACE -> spaceSentence(null)
        else -> "Download stopped: ${failure.reason}."
    }

    /** "Not enough space. Free 300 MB or remove a download."; without the figure when the shortfall is not known. */
    fun spaceSentence(shortBytes: Long?): String =
        if (shortBytes != null && shortBytes > 0) "Not enough space. Free ${roundedUp(shortBytes)} or remove a download."
        else "Not enough space. Free some space or remove a download."

    /** Bytes short of [needBytes] when [freeBytes] are free, or null when it fits. */
    fun shortBy(needBytes: Long, freeBytes: Long): Long? = (needBytes - freeBytes).takeIf { needBytes > 0 && it > 0 }

    /** What a failure reads as, from the exception or anything it wraps. */
    fun classify(e: Throwable): Failure {
        generateSequence(e) { it.cause }.take(8).forEach { t ->
            when {
                t is PackDownloader.NotEnoughSpace -> return Failure.SPACE
                t is PackDownloader.HashMismatch -> return Failure.DAMAGED
                t is PackDownloader.RedirectRefused -> return Failure.REFUSED
                t.message?.contains("No space left", ignoreCase = true) == true || t.message?.contains("ENOSPC") == true -> return Failure.SPACE
                t is SocketException || t is SocketTimeoutException || t is UnknownHostException || t is EOFException || t is SSLException || t is ProtocolException -> return Failure.CONNECTION
            }
        }
        val text = e.message.orEmpty()
        return when {
            "not found (HTTP" in text -> Failure.NOT_FOUND
            "server build" in text || "server refused" in text || "build status" in text -> Failure.BUILD
            "not a PMTiles archive" in text || "the header needs" in text -> Failure.DAMAGED
            Regex("""HTTP \d{3}""").containsMatchIn(text) -> Failure.SERVER
            e is IOException && text.contains("connection", ignoreCase = true) -> Failure.CONNECTION
            else -> Failure.OTHER
        }
    }

    /** The word on a pack's row: one of PRIMARY, ON MAP, MAP ONLY, INCOMPLETE. */
    fun packWord(primary: Boolean, onMap: Boolean, usable: Boolean, hasDem: Boolean, hasVector: Boolean): String = when {
        !usable && hasDem -> "INCOMPLETE"
        primary -> "PRIMARY"
        onMap -> "ON MAP"
        !hasDem && hasVector -> "MAP ONLY"
        else -> "INCOMPLETE"
    }

    /** What the row's detail says the pack holds. */
    fun contentsWord(hasDem: Boolean, hasVector: Boolean): String = if (!hasVector) "terrain only" else if (!hasDem) "map only" else "terrain + map"

    /** A count with its singular: "1 pack", "3 packs". */
    fun count(n: Int, singular: String): String = if (n == 1) "1 $singular" else "$n ${singular}s"

    /** A size for a row: "3.4 MB", "212 MB", "1.2 GB". */
    fun sizeText(bytes: Long): String {
        val mb = bytes / MB
        return when {
            mb >= 1024 -> String.format(Locale.US, "%.1f GB", mb / 1024)
            mb >= 10 -> String.format(Locale.US, "%.0f MB", mb)
            else -> String.format(Locale.US, "%.1f MB", mb)
        }
    }

    /** A size to free, never less than what is needed: "300 MB", "1.2 GB". */
    fun roundedUp(bytes: Long): String {
        val mb = ceil(bytes / MB)
        return if (mb >= 1024) String.format(Locale.US, "%.1f GB", ceil(mb / 1024 * 10) / 10) else String.format(Locale.US, "%.0f MB", mb)
    }

    private const val MB = 1_048_576.0

    /**
     * A state file of `packs/.downloads/` as [PackDownloader] writes it: what the next launch resumes from. [progress] reads
     * the partial files beside it, so a row can say how far the download got before anything runs.
     */
    class Saved(private val m: Map<String, Any?>) {
        val name: String = m["name"] as String
        val demUrl: String = m["demUrl"] as String
        val vectorUrl: String = m["vectorUrl"] as String
        val activate: Boolean get() = m["activate"] == true
        val createdAt: Long get() = (m["createdAt"] as? Number)?.toLong() ?: 0L
        val restarts: Int get() = (m["restarts"] as? Number)?.toInt() ?: 0
        val update: Boolean get() = m["update"] == true
        val graphOnly: Boolean get() = m["graphOnly"] == true

        @Suppress("UNCHECKED_CAST")
        val catalog: Map<String, Any?>? get() = m["catalog"] as? Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        val graph: Map<String, Any?>? get() = m["graph"] as? Map<String, Any?>

        /** The region job's wrap-up, kept so that it runs when the download finishes after a kill ([io.github.jasondorjeshort.dystopo.app.online.RegionDownload]). */
        @Suppress("UNCHECKED_CAST")
        val region: Map<String, Any?>? get() = m["region"] as? Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        val files: List<Map<String, Any?>> get() = (m["files"] as? List<Map<String, Any?>>).orEmpty()

        /** Bytes on disk and bytes wanted over the archives of the job (total null until every archive's size is known). */
        fun progress(dir: File): Pair<Long, Long?> {
            var done = 0L
            var total: Long? = 0L
            for (f in files) {
                val role = PackRole.entries.firstOrNull { it.label == f["role"] } ?: continue
                val status = f["status"]
                if (status == "missing" || status == "unchanged") continue
                val target = File(dir, PackNames.fileName(name, role))
                val part = File(dir, target.name + ".part")
                done += if (!update && target.isFile) target.length() else part.length()
                val size = (f["total"] as? Number)?.toLong() ?: (f["expectBytes"] as? Number)?.toLong()
                total = if (size == null) null else total?.plus(size)
            }
            return done to total
        }

        /** The percent [progress] comes to, or null when a size is not known yet. */
        fun percent(dir: File): Double? = progress(dir).let { (done, total) -> total?.takeIf { it > 0 }?.let { done * 100.0 / it } }

        companion object {
            fun parse(text: String): Saved = Saved(MiniJson.parseObject(text))
        }
    }
}
