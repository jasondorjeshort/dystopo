package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.server.*

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
        return BoundingBox.unwrapped(bbox[0], bbox[1], bbox[2], bbox[3]) // west > east crosses the antimeridian
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

    /**
     * Error text for an unknown area. Lists only public areas: the message ends up in terminals,
     * logs and PR descriptions, and must never reveal the names of personal areas.
     */
    fun unknownAreaMessage(name: String, areas: Map<String, NamedArea>): String =
        "unknown area '$name'; public areas: ${areas.values.filterNot { it.local }.joinToString { it.name }} " +
            "(personal areas from $LOCAL_FILE also work but are not listed)"

    /** Resolves --area / --bbox options to an optional area name and a bounding box. */
    fun resolve(areaName: String?, bboxText: String?): Pair<String?, BoundingBox> = when {
        areaName != null && bboxText != null -> throw UsageError("use --area or --bbox, not both")
        areaName != null -> {
            val areas = load()
            val found = areas[areaName] ?: throw UsageError(unknownAreaMessage(areaName, areas))
            areaName to found.boundingBox()
        }
        bboxText != null -> {
            val parts = bboxText.split(",").map { it.trim().toDoubleOrNull() }
            if (parts.size != 4 || parts.any { it == null }) throw UsageError("--bbox must be west,south,east,north")
            null to BoundingBox.unwrapped(parts[0]!!, parts[1]!!, parts[2]!!, parts[3]!!)
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
