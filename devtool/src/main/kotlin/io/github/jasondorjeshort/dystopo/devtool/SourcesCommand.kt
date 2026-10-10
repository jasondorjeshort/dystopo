package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.server.*

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.split
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import io.github.jasondorjeshort.dystopo.sources.SourceRegistry
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Prints the data source registry (shared `sources.json`), or with `--ids` the composed attribution
 * for a set of registry ids, plus which registry ids each elevation source can draw on.
 */
class SourcesCommand : CliktCommand(name = "sources") {
    override fun help(context: Context) =
        "Print the data source registry as JSON (licenses, attribution, coverage). " +
            "With --ids a,b print the composed attribution for those registry ids."

    private val ids by option("--ids", help = "Comma-separated registry ids (or devtool elevation source ids) to compose attribution for")
        .split(",")

    override fun run() {
        val registry = SourceRegistry.builtIn
        val selected = ids
        val out: JsonObject = if (selected == null) {
            buildJsonObject {
                put("sourceCount", registry.sources.size)
                put("sources", reportJson.parseToJsonElement(MiniJson.encode(registry.sources.map { it.raw })))
                put("elevationSources", buildJsonObject {
                    for (id in ElevationSource.ids) putJsonArray(id) { ElevationSource.byId(id).registryIds.forEach { add(it) } }
                })
            }
        } else {
            // Elevation source ids (best, 3dep-best...) expand to the registry ids they draw on.
            val expanded = selected.map { it.trim() }.filter { it.isNotEmpty() }.flatMap { id ->
                when {
                    id in registry -> listOf(id)
                    id in ElevationSource.ids -> ElevationSource.byId(id).registryIds
                    else -> throw UsageError("unknown id '$id'; registry ids: ${registry.ids.joinToString()}")
                }
            }
            val ordered = registry.ordered(expanded)
            buildJsonObject {
                putJsonArray("ids") { ordered.forEach { add(it) } }
                putJsonArray("licenses") {
                    ordered.map { registry.getValue(it) }.forEach { s ->
                        add(buildJsonObject {
                            put("id", s.id)
                            put("license", s.license)
                            put("licenseUrl", s.licenseUrl)
                        })
                    }
                }
                put("attribution", registry.attribution(ordered))
                put("attributionHtml", registry.attributionHtml(ordered))
            }
        }
        echo(reportJson.encodeToString(JsonObject.serializer(), out))
    }
}
