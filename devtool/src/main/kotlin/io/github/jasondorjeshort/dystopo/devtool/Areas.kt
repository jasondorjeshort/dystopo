package io.github.jasondorjeshort.dystopo.devtool

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import io.github.jasondorjeshort.dystopo.geo.BoundingBox
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class AreaDef(val bbox: List<Double>, val description: String = "")

@Serializable
data class AreasFile(val areas: Map<String, AreaDef> = emptyMap())

@Serializable
data class NamedArea(val name: String, val bbox: List<Double>, val description: String, val local: Boolean) {
    fun boundingBox(): BoundingBox {
        require(bbox.size == 4) { "area '$name': bbox must be [west, south, east, north]" }
        return BoundingBox(bbox[0], bbox[1], bbox[2], bbox[3])
    }
}

/**
 * Named test areas. Public areas live in the committed `areas.json`. Personal areas
 * (home, current trip) live in the gitignored `devtool.local.json`, same format, and
 * override public areas with the same name. Never commit personal areas.
 */
object Areas {
    const val PUBLIC_FILE = "areas.json"
    const val LOCAL_FILE = "devtool.local.json"

    private val json = Json { ignoreUnknownKeys = true }

    fun load(root: File = File(".")): Map<String, NamedArea> {
        val result = LinkedHashMap<String, NamedArea>()
        for ((fileName, local) in listOf(PUBLIC_FILE to false, LOCAL_FILE to true)) {
            val file = File(root, fileName)
            if (!file.exists()) continue
            val parsed = json.decodeFromString<AreasFile>(file.readText())
            for ((name, def) in parsed.areas) {
                result[name] = NamedArea(name, def.bbox, def.description, local)
            }
        }
        return result
    }

    /** Resolves --area / --bbox options to an optional area name and a bounding box. */
    fun resolve(areaName: String?, bboxText: String?): Pair<String?, BoundingBox> = when {
        areaName != null && bboxText != null -> throw UsageError("use --area or --bbox, not both")
        areaName != null -> {
            val areas = load()
            val found = areas[areaName] ?: throw UsageError("unknown area '$areaName'; known: ${areas.keys.joinToString()}")
            areaName to found.boundingBox()
        }
        bboxText != null -> {
            val parts = bboxText.split(",").map { it.trim().toDoubleOrNull() }
            if (parts.size != 4 || parts.any { it == null }) throw UsageError("--bbox must be west,south,east,north")
            null to BoundingBox(parts[0]!!, parts[1]!!, parts[2]!!, parts[3]!!)
        }
        else -> throw UsageError("one of --area or --bbox is required")
    }
}

class AreasCommand : CliktCommand(name = "areas") {
    override fun help(context: Context) =
        "List named areas from ${Areas.PUBLIC_FILE} and ${Areas.LOCAL_FILE} (local = personal, never committed)."

    override fun run() {
        echo(reportJson.encodeToString(Areas.load().values.toList()))
    }
}
