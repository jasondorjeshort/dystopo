package io.github.jasondorjeshort.dystopo.app.launch

import io.github.jasondorjeshort.dystopo.launch.Bound
import io.github.jasondorjeshort.dystopo.launch.Launch
import io.github.jasondorjeshort.dystopo.launch.Runs
import io.github.jasondorjeshort.dystopo.launch.Stage
import io.github.jasondorjeshort.dystopo.launch.markMatches
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T266 (ADR 0061): the app binds every stage the runner runs, each once and with the call its thread wants (`run` for a MAIN
 * stage, `start` for a WORKER stage, `begin` for an observed one); and every mark the app's sources set belongs to exactly one
 * declared stage or is one of the trace's boundaries. A source read, as `ComposeStatePrepareTest`.
 */
class LaunchBindingsTest {
    private val mainDir = File("src/main/kotlin")
    private val sources: List<File> by lazy { mainDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }

    /** `Launch.FOO`'s field name to its stage. */
    private val byField: Map<String, Stage> by lazy {
        Launch::class.java.declaredFields.filter { it.type == Stage::class.java }.associate { f ->
            f.isAccessible = true
            f.name to f.get(Launch) as Stage
        }
    }

    private val BIND = Regex("""\.(run|start|begin)\(\s*Launch\.([A-Z_]+)""")

    @Test fun everyStageTheRunnerRunsIsBoundOnceWithTheRightCall() {
        assertEquals(Launch.stages.toSet(), byField.values.toSet(), "every stage has a field in Launch")
        val bound = ArrayList<Pair<String, Stage>>()
        for (f in sources) for (m in BIND.findAll(f.readText())) {
            val stage = byField[m.groupValues[2]] ?: continue // a value (Launch.TILES ...), not a stage
            bound += m.groupValues[1] to stage
        }
        val names = bound.map { it.second.name }
        assertEquals(names.distinct(), names, "a stage bound twice")
        val want = Launch.stages.filter { it.bound == Bound.RUNNER || it.name == Launch.CACHE_INDEX.name }.map { it.name }.toSet()
        assertEquals(want, names.toSet(), "every stage the runner runs (and the cache's index) is bound, nothing else")
        for ((call, s) in bound) {
            val expected = when {
                s.bound == Bound.OBSERVED -> "begin"
                s.runs == Runs.MAIN -> "run"
                else -> "start"
            }
            assertEquals(expected, call, "${s.name} is bound with $call")
        }
    }

    /** `StartupTrace.mark("…")` and `composing("…")` anywhere; inside `StartupTrace.kt`, its own bare `mark("…")` too. */
    private val MARK = Regex("""StartupTrace\.(?:mark|composing)\("([^"]*)"\)""")
    private val OWN_MARK = Regex("""\bmark\("([^"$]*)"\)""")

    @Test fun everyMarkInTheSourcesBelongsToOneStageOrIsABoundary() {
        val marks = sources.flatMap { f ->
            val text = f.readText()
            (MARK.findAll(text) + if (f.name == "StartupTrace.kt") OWN_MARK.findAll(text) else emptySequence()).map { it.groupValues[1] to f.name }
        }.filter { it.first.isNotEmpty() }
        assertTrue(marks.size > 50, "the read found ${marks.size} marks: has the mark call's shape changed?")
        val orphans = marks.filter { (m, _) ->
            val owners = Launch.stages.filter { s -> s.marks.any { markMatches(it, m) } }
            val boundary = Launch.boundaries.any { markMatches(it, m) }
            !(owners.size == 1 && !boundary || owners.isEmpty() && boundary)
        }
        assertEquals(emptyList(), orphans, "marks in no stage (or in two): declare them in shared launch.Launch")
        // A declared mark that no source sets is stale (patterns excepted: the source builds those).
        val set = marks.map { it.first }.toSet()
        val stale = Launch.stages.flatMap { it.marks }.filter { !it.endsWith("*") && it !in set }
        assertEquals(emptyList(), stale, "declared marks no source sets")
    }
}
