package io.github.jasondorjeshort.dystopo.devtool

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import kotlinx.serialization.json.Json
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream

/** Pretty JSON for reports printed to stdout. Reports are meant to be read by agents and scripts. */
val reportJson = Json { prettyPrint = true; encodeDefaults = true }

class Devtool : CliktCommand(name = "devtool") {
    override fun help(context: Context) =
        "Developer tool for Dystopo: runs shared-core features from the command line and prints JSON reports."

    override fun run() = Unit
}

/**
 * Reports are UTF-8 ("©" in attributions, names in any script) whatever the platform charset: on Windows
 * the JVM otherwise encodes redirected stdout in the ANSI code page (cp1252), which agents then fail to parse.
 */
fun useUtf8Output() {
    System.setOut(PrintStream(FileOutputStream(FileDescriptor.out), true, Charsets.UTF_8))
    System.setErr(PrintStream(FileOutputStream(FileDescriptor.err), true, Charsets.UTF_8))
}

fun main(args: Array<String>) = useUtf8Output().let { Devtool() }
    .subcommands(AreasCommand(), RenderCommand(), CompareCommand(), MvtCommand(), PmtilesCommand(), TilesCommand(), SourcesCommand(), TourCommand(), GpxCommand(), ServeCommand(), SearchCommand(), ContoursCommand(), SpriteCommand())
    .main(args)
