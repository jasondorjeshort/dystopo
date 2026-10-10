package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.server.*

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import io.github.jasondorjeshort.dystopo.mvt.Feature
import io.github.jasondorjeshort.dystopo.mvt.Geometry
import io.github.jasondorjeshort.dystopo.mvt.GeometryType
import io.github.jasondorjeshort.dystopo.mvt.Layer
import io.github.jasondorjeshort.dystopo.mvt.TilePoint
import io.github.jasondorjeshort.dystopo.mvt.Value
import io.github.jasondorjeshort.dystopo.mvt.VectorTile
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.zip.GZIPInputStream

class MvtCommand : CliktCommand(name = "mvt") {
    init {
        subcommands(MvtDumpCommand())
    }

    override fun help(context: Context) = "Mapbox Vector Tile tools (shared-core decoder)."

    override fun run() = Unit
}

class MvtDumpCommand : CliktCommand(name = "dump") {
    override fun help(context: Context) =
        "Decode a .mvt/.pbf tile and print a JSON summary: layers, feature counts by geometry type, property keys, sample features."

    private val file by option("--file", help = "Tile file (raw protobuf, or gzip; gzip is auto-detected)").file(mustExist = true, canBeDir = false).required()
    private val gzip by option("--gzip", help = "Force gzip decompression (otherwise detected from the magic bytes)").flag()
    private val samples by option("--samples", help = "Sample features to print per layer").int().default(3)
    private val layerFilter by option("--layer", help = "Only report this layer")

    override fun run() {
        val timings = Timings()
        val raw = timings.time("read") { file.readBytes() }
        val isGzip = gzip || (raw.size >= 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte())
        val bytes = if (isGzip) timings.time("gunzip") { GZIPInputStream(raw.inputStream()).use { it.readAllBytes() } } else raw
        val tile = timings.time("decode") { VectorTile.decode(bytes) }
        val layers = tile.layers.filter { layerFilter == null || it.name == layerFilter }

        val report = buildJsonObject {
            put("file", file.path)
            put("bytes", raw.size)
            put("gzip", isGzip)
            put("decodedBytes", bytes.size)
            put("layerCount", tile.layers.size)
            put("featureCount", tile.layers.sumOf { it.features.size })
            put("vertexCount", tile.layers.sumOf { l -> l.features.sumOf { it.geometry.vertexCount } })
            put("layers", JsonArray(layers.map { layerSummary(it) }))
            put("timingsMs", JsonObject(timings.ms.mapValues { JsonPrimitive(it.value) }))
        }
        echo(reportJson.encodeToString(JsonObject.serializer(), report))
    }

    private fun layerSummary(layer: Layer): JsonObject = buildJsonObject {
        put("name", layer.name)
        put("version", layer.version)
        put("extent", layer.extent)
        put("features", layer.features.size)
        put("featuresByType", buildJsonObject {
            for (t in GeometryType.entries) put(t.name.lowercase(), layer.features.count { it.type == t })
        })
        put("vertices", layer.features.sumOf { it.geometry.vertexCount })
        put("keys", JsonArray(layer.keys.map { JsonPrimitive(it) }))
        put("valueTypes", buildJsonObject {
            layer.features.asSequence().flatMap { it.properties.values }.groupingBy { it::class.simpleName!! }.eachCount()
                .forEach { (k, v) -> put(k, v) }
        })
        put("samples", JsonArray(layer.features.take(samples).map { featureSummary(it) }))
    }

    private fun featureSummary(f: Feature): JsonObject = buildJsonObject {
        put("id", f.id?.let { JsonPrimitive(it) } ?: JsonNull)
        put("type", f.type.name.lowercase())
        put("properties", JsonObject(f.properties.mapValues { jsonValue(it.value) }))
        when (val g = f.geometry) {
            is Geometry.Points -> put("points", g.points.size)
            is Geometry.Lines -> { put("lines", g.lines.size); put("vertices", g.vertexCount) }
            is Geometry.Polygons -> {
                put("polygons", g.polygons.size)
                put("holes", g.polygons.sumOf { it.interiors.size })
                put("vertices", g.vertexCount)
            }
            is Geometry.Unknown -> put("commands", g.commands.size)
        }
        bbox(f.geometry)?.let { put("bbox", JsonArray(it.map { v -> JsonPrimitive(v) })) }
        put("firstPoint", firstPoint(f.geometry)?.let { buildJsonArray { add(JsonPrimitive(it.x)); add(JsonPrimitive(it.y)) } } ?: JsonNull)
    }

    private fun jsonValue(v: Value): JsonElement = when (v) {
        is Value.StringValue -> JsonPrimitive(v.value)
        is Value.FloatValue -> JsonPrimitive(v.value)
        is Value.DoubleValue -> JsonPrimitive(v.value)
        is Value.IntValue -> JsonPrimitive(v.value)
        is Value.UintValue -> if (v.value >= 0) JsonPrimitive(v.value) else JsonPrimitive(v.asText())
        is Value.SintValue -> JsonPrimitive(v.value)
        is Value.BoolValue -> JsonPrimitive(v.value)
    }

    private fun allPoints(g: Geometry): Sequence<TilePoint> = when (g) {
        is Geometry.Points -> g.points.asSequence()
        is Geometry.Lines -> g.lines.asSequence().flatten()
        is Geometry.Polygons -> g.polygons.asSequence().flatMap { p -> sequenceOf(p.exterior).plus(p.interiors).flatten() }
        is Geometry.Unknown -> emptySequence()
    }

    private fun firstPoint(g: Geometry): TilePoint? = allPoints(g).firstOrNull()

    /** minX, minY, maxX, maxY in tile units, or null for empty geometry. */
    private fun bbox(g: Geometry): List<Int>? {
        var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE; var maxX = Int.MIN_VALUE; var maxY = Int.MIN_VALUE
        var any = false
        for (p in allPoints(g)) {
            any = true
            if (p.x < minX) minX = p.x
            if (p.y < minY) minY = p.y
            if (p.x > maxX) maxX = p.x
            if (p.y > maxY) maxY = p.y
        }
        return if (any) listOf(minX, minY, maxX, maxY) else null
    }
}
