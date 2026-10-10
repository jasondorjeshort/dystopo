package io.github.jasondorjeshort.dystopo.tilebuild

import com.github.ajalt.clikt.core.UsageError
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
private data class AreaDef(val bbox: List<Double>, val description: String = "")

@Serializable
private data class AreasFile(val areas: Map<String, AreaDef> = emptyMap())

/**
 * Named clip areas: the public `areas.json` in the repo root, overridden by the gitignored
 * `devtool.local.json` (personal areas; never committed). Same files and format as devtool.
 */
object Areas {
    const val PUBLIC_FILE = "areas.json"
    const val LOCAL_FILE = "devtool.local.json"
    private val json = Json { ignoreUnknownKeys = true }

    fun load(root: File = File(".")): Map<String, DoubleArray> = loadFiles(root, PUBLIC_FILE, LOCAL_FILE)

    private fun loadFiles(root: File, vararg fileNames: String): Map<String, DoubleArray> {
        val result = LinkedHashMap<String, DoubleArray>()
        for (fileName in fileNames) {
            val file = File(root, fileName)
            if (!file.isFile) continue
            for ((name, def) in json.decodeFromString<AreasFile>(file.readText()).areas) {
                result[name] = validate(def.bbox, "area '$name'")
            }
        }
        return result
    }

    /**
     * The bbox of a public or local area. The error for an unknown name lists only the public
     * areas: error text ends up in terminals, logs and PR descriptions, and must never reveal the
     * names of personal areas.
     */
    fun bbox(name: String, root: File = File(".")): DoubleArray {
        return load(root)[name] ?: throw UsageError(
            "unknown area '$name'; public areas: ${loadFiles(root, PUBLIC_FILE).keys.joinToString()} " +
                "(personal areas from $LOCAL_FILE also work but are not listed)"
        )
    }

    /** Parses `west,south,east,north`. */
    fun parseBbox(text: String): DoubleArray {
        val parts = text.split(",").map { it.trim().toDoubleOrNull() }
        if (parts.size != 4 || parts.any { it == null }) throw UsageError("--bbox must be west,south,east,north")
        return validate(parts.map { it!! }, "--bbox")
    }

    private fun validate(bbox: List<Double>, what: String): DoubleArray {
        if (bbox.size != 4) throw UsageError("$what: bbox must be [west, south, east, north]")
        val (w, s, e, n) = bbox
        if (w !in -180.0..180.0 || e !in -180.0..180.0 || s !in -90.0..90.0 || n !in -90.0..90.0 || w >= e || s >= n) {
            throw UsageError("$what: bbox $bbox is not a valid west,south,east,north box")
        }
        return doubleArrayOf(w, s, e, n)
    }
}
