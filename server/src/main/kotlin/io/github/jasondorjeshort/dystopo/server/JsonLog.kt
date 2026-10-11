package io.github.jasondorjeshort.dystopo.server

import io.github.jasondorjeshort.dystopo.maplibre.JsonWriter
import java.io.File
import java.io.FileOutputStream
import java.io.PrintStream
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * Structured logs: one JSON object per line on stdout (what container log collectors expect), always
 * with `ts` (ISO-8601 UTC), `level` (`info`, `warn`, `error`) and `event`, then the event's fields.
 *
 * T82, the log cap: string fields longer than [maxFieldChars] are cut (a client controls the request path
 * and headers, so one request must not write kilobytes), and with a [file] the log goes there instead of
 * stdout and rotates when it reaches [maxBytes]: `server.log` becomes `server.log.1`, the older ones shift
 * up and the oldest beyond [keep] is deleted, so the log never takes more than about `(keep + 1) * maxBytes`.
 * On stdout, cap the collector instead (journald, `docker run --log-opt max-size=`).
 */
class JsonLog(
    private val out: PrintStream = System.out,
    private val clock: () -> Instant = Instant::now,
    private val file: File? = null,
    private val maxBytes: Long = 64L shl 20,
    private val keep: Int = 5,
    private val maxFieldChars: Int = MAX_FIELD_CHARS,
) {
    private val lock = Any()
    private var stream: FileOutputStream? = null
    private var written = 0L
    val lines = AtomicLong()
    val bytes = AtomicLong()
    val rotations = AtomicLong()
    val truncated = AtomicLong()
    val writeErrors = AtomicLong()

    init {
        if (file != null) {
            file.absoluteFile.parentFile?.mkdirs()
            written = if (file.isFile) file.length() else 0L
        }
    }

    fun info(event: String, fields: Map<String, Any?> = emptyMap()) = write("info", event, fields)
    fun warn(event: String, fields: Map<String, Any?> = emptyMap()) = write("warn", event, fields)
    fun error(event: String, fields: Map<String, Any?> = emptyMap()) = write("error", event, fields)

    fun write(level: String, event: String, fields: Map<String, Any?>) {
        val capped = fields.mapValues { (_, v) -> cap(v) }
        val line = JsonWriter.write(linkedMapOf<String, Any?>("ts" to clock().toString(), "level" to level, "event" to event) + capped)
        lines.incrementAndGet()
        if (file == null) {
            bytes.addAndGet(line.length + 1L)
            synchronized(out) { out.println(line) }
            return
        }
        val data = (line + "\n").toByteArray(Charsets.UTF_8)
        bytes.addAndGet(data.size.toLong())
        synchronized(lock) {
            try {
                if (written > 0 && written + data.size > maxBytes) rotate()
                val s = stream ?: FileOutputStream(file, true).also { stream = it }
                s.write(data)
                s.flush()
                written += data.size
            } catch (e: Exception) {
                // A full disk must not take the server down with it: count it and fall back to stderr.
                writeErrors.incrementAndGet()
                runCatching { stream?.close() }
                stream = null
                System.err.println(line)
            }
        }
    }

    /** Shifts `log.N` to `log.N+1` (dropping the one beyond [keep]) and starts a new file. */
    private fun rotate() {
        runCatching { stream?.close() }
        stream = null
        val f = file!!
        File(f.path + ".$keep").delete()
        for (i in keep - 1 downTo 1) File(f.path + ".$i").takeIf { it.exists() }?.renameTo(File(f.path + ".${i + 1}"))
        if (keep >= 1) {
            if (!f.renameTo(File(f.path + ".1"))) f.delete()
        } else f.delete()
        written = 0
        rotations.incrementAndGet()
    }

    /** The log's own numbers, for `/metrics`. */
    fun stats(): Map<String, Any?> = linkedMapOf(
        "file" to file?.path, "maxBytes" to file?.let { maxBytes }, "keep" to file?.let { keep },
        "lines" to lines.get(), "bytes" to bytes.get(), "rotations" to rotations.get(),
        "truncatedFields" to truncated.get(), "writeErrors" to writeErrors.get(),
        "currentFileBytes" to file?.let { synchronized(lock) { written } },
    )

    fun close() = synchronized(lock) { runCatching { stream?.close() }; stream = null }

    private fun cap(v: Any?): Any? = when (v) {
        is String -> if (v.length > maxFieldChars) { truncated.incrementAndGet(); v.take(maxFieldChars) + "...(${v.length} chars)" } else v
        is List<*> -> if (v.size > MAX_LIST) { truncated.incrementAndGet(); v.take(MAX_LIST).map { cap(it) } + "...(${v.size} items)" } else v.map { cap(it) }
        is Map<*, *> -> v.mapValues { cap(it.value) }
        else -> v
    }

    companion object {
        /** Longest string field written; the rest is replaced by its length. */
        const val MAX_FIELD_CHARS = 512
        const val MAX_LIST = 100
    }
}
