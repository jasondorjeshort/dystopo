package io.github.jasondorjeshort.dystopo.devtool

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import kotlinx.serialization.json.Json

/** Pretty JSON for reports printed to stdout. Reports are meant to be read by agents and scripts. */
val reportJson = Json { prettyPrint = true; encodeDefaults = true }

class Devtool : CliktCommand(name = "devtool") {
    override fun help(context: Context) =
        "Developer tool for Dystopo: runs shared-core features from the command line and prints JSON reports."

    override fun run() = Unit
}

fun main(args: Array<String>) = Devtool()
    .subcommands(AreasCommand(), RenderCommand(), CompareCommand(), MvtCommand(), PmtilesCommand(), TilesCommand(), SourcesCommand(), TourCommand(), GpxCommand(), ServeCommand(), SearchCommand(), ContoursCommand())
    .main(args)
