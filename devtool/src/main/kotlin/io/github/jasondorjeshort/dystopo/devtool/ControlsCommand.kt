package io.github.jasondorjeshort.dystopo.devtool

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import io.github.jasondorjeshort.dystopo.controls.Controls
import io.github.jasondorjeshort.dystopo.controls.ControlsDocument
import io.github.jasondorjeshort.dystopo.controls.Platform
import io.github.jasondorjeshort.dystopo.maplibre.JsonWriter
import io.github.jasondorjeshort.dystopo.server.reportJson
import java.io.File

/**
 * T208 (ADR 0053 §2): writes the controls document, `tools/demo/controls.json`, from the registry in `shared`
 * (`controls/`), and prints one JSON report: the file, whether it changed, the counts, each item's placement per
 * platform and the document itself. `--check` writes nothing and exits 1 when the file differs. `./gradlew
 * updateGoldens` runs it; `ControlsDocumentTest` fails when the committed file is stale.
 */
class ControlsCommand : CliktCommand(name = "controls") {
    override fun help(context: Context) =
        "Write the controls document (tools/demo/controls.json) from the shared registry; --check only compares. Prints a JSON report with the document."

    private val out by option("--out", help = "the document to write").default(ControlsDocument.FILE)
    private val check by option("--check", help = "write nothing; exit 1 when the file differs from the registry").flag()

    override fun run() {
        val file = File(out)
        val text = ControlsDocument.text()
        val before = if (file.isFile) file.readText().replace("\r\n", "\n") else null
        val upToDate = before == text
        val written = !check && !upToDate
        if (written) {
            file.absoluteFile.parentFile?.mkdirs()
            file.writeText(text)
        }
        val report = linkedMapOf<String, Any?>(
            "file" to file.path.replace('\\', '/'),
            "upToDate" to upToDate,
            "written" to written,
            "bytes" to text.encodeToByteArray().size,
            "version" to ControlsDocument.VERSION,
            "categories" to Controls.categories.size,
            "items" to Controls.items.size,
            "placed" to Controls.items.map { item ->
                linkedMapOf<String, Any?>("id" to item.id).apply {
                    for (p in Platform.entries) {
                        val pl = item.placements[p]
                        put(p.key, pl?.let { "${it.category} / ${it.zone}" + (it.row?.let { r -> " / $r" } ?: "") })
                    }
                }
            },
            "document" to ControlsDocument.json(),
        )
        echo(reportJson.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), reportJson.parseToJsonElement(JsonWriter.write(report))))
        if (check && !upToDate) throw ProgramResult(1)
    }
}
