package io.github.jasondorjeshort.dystopo.server

import io.github.jasondorjeshort.dystopo.maplibre.JsonWriter
import java.io.PrintStream
import java.time.Instant

/**
 * Structured logs: one JSON object per line on stdout (what container log collectors expect), always
 * with `ts` (ISO-8601 UTC), `level` (`info`, `warn`, `error`) and `event`, then the event's fields.
 */
class JsonLog(private val out: PrintStream = System.out, private val clock: () -> Instant = Instant::now) {
    fun info(event: String, fields: Map<String, Any?> = emptyMap()) = write("info", event, fields)
    fun warn(event: String, fields: Map<String, Any?> = emptyMap()) = write("warn", event, fields)
    fun error(event: String, fields: Map<String, Any?> = emptyMap()) = write("error", event, fields)

    fun write(level: String, event: String, fields: Map<String, Any?>) {
        val line = JsonWriter.write(linkedMapOf<String, Any?>("ts" to clock().toString(), "level" to level, "event" to event) + fields)
        synchronized(out) { out.println(line) }
    }
}
