package io.github.jasondorjeshort.dystopo.maplibre

/**
 * Serializes plain Kotlin values as JSON: maps (string keys, insertion order kept), lists, strings,
 * numbers, booleans and null. Whole doubles print without a fraction; non-finite numbers are null.
 */
object JsonWriter {
    fun write(value: Any?): String = StringBuilder().also { append(it, value) }.toString()

    private fun append(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> string(sb, v)
            is Boolean -> sb.append(v)
            is Int, is Long, is Short, is Byte -> sb.append(v)
            is Number -> number(sb, v.toDouble())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(',')
                    first = false
                    string(sb, k as? String ?: throw IllegalArgumentException("JSON keys must be strings, got $k"))
                    sb.append(':')
                    append(sb, value)
                }
                sb.append('}')
            }
            is List<*> -> {
                sb.append('[')
                v.forEachIndexed { i, e ->
                    if (i > 0) sb.append(',')
                    append(sb, e)
                }
                sb.append(']')
            }
            else -> throw IllegalArgumentException("cannot write ${v::class.simpleName} as JSON")
        }
    }

    private fun number(sb: StringBuilder, d: Double) {
        when {
            !d.isFinite() -> sb.append("null")
            d == kotlin.math.floor(d) && kotlin.math.abs(d) < 1e15 -> sb.append(d.toLong())
            else -> sb.append(d)
        }
    }

    private fun string(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }
}
