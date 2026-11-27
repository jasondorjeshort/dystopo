package io.github.jasondorjeshort.dystopo.launch

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** ADR 0061 step 1: the declared launch passes its validator; each rule fails on a list that breaks it. */
class LaunchTest {
    @Test fun theDeclaredLaunchIsValid() {
        assertEquals(emptyList(), LaunchCheck.problems(Launch.stages, Launch.boundaries))
    }

    @Test fun everyValueIsPublishedAndEveryStageNameIsDistinct() {
        val published = Launch.stages.flatMap { it.publishes }
        assertEquals(Launch.values.toSet(), published.toSet())
        assertEquals(Launch.stages.size, Launch.stages.map { it.name }.toSet().size)
    }

    @Test fun aStageDeclaredBeforeItsNeedFails() {
        val a = Stage("a", gate = Gate.BEFORE_ACTIVITY)
        val b = Stage("b", needs = listOf("a"), gate = Gate.BEFORE_ACTIVITY)
        assertEquals(emptyList(), LaunchCheck.problems(listOf(a, b)))
        val p = LaunchCheck.problems(listOf(b, a))
        assertTrue(p.any { it.startsWith("b: needs a, which is not declared before it") }, "$p")
        // The real list with one stage moved ahead of its need.
        val moved = Launch.stages.toMutableList().apply { remove(Launch.TILES_SERVICE); add(0, Launch.TILES_SERVICE) }
        assertTrue(LaunchCheck.problems(moved, Launch.boundaries).any { it.startsWith("tiles: needs packs") }, "tiles ahead of packs")
    }

    @Test fun aCycleOrAnUndeclaredNeedFails() {
        val a = Stage("a", needs = listOf("b"), gate = Gate.LATER)
        val b = Stage("b", needs = listOf("a"), gate = Gate.LATER)
        assertTrue(LaunchCheck.problems(listOf(a, b)).isNotEmpty())
        assertTrue(LaunchCheck.problems(listOf(Stage("c", needs = listOf("nowhere"), gate = Gate.LATER))).single().contains("nowhere"))
        assertTrue(LaunchCheck.problems(listOf(Stage("d", needs = listOf("d"), gate = Gate.LATER))).single().contains("needs itself"))
    }

    @Test fun diskOrBinderOnTheMainThreadNeedsAReason() {
        val disk = Stage("disk", work = setOf(Work.DISK), gate = Gate.BEFORE_STYLE)
        assertTrue(LaunchCheck.problems(listOf(disk)).single().contains("main thread"))
        assertEquals(emptyList(), LaunchCheck.problems(listOf(disk.copy(allowed = "step 2 moves it"))))
        assertEquals(emptyList(), LaunchCheck.problems(listOf(disk.copy(runs = Runs.WORKER))))
        assertEquals(emptyList(), LaunchCheck.problems(listOf(Stage("native", work = setOf(Work.NATIVE), gate = Gate.BEFORE_ACTIVITY))))
        assertTrue(LaunchCheck.problems(listOf(Stage("binder", work = setOf(Work.BINDER), gate = Gate.FIRST_FRAME))).isNotEmpty())
    }

    @Test fun gatesAreMonotoneAlongNeeds() {
        val late = Stage("late", runs = Runs.WORKER, gate = Gate.LATER)
        val early = Stage("early", needs = listOf("late"), gate = Gate.BEFORE_ACTIVITY)
        assertTrue(LaunchCheck.problems(listOf(late, early)).single().contains("is later"))
    }

    @Test fun marksBelongToOneStageAndNeverToABoundary() {
        val a = Stage("a", listOf("x"), gate = Gate.LATER)
        val b = Stage("b", listOf("x"), gate = Gate.LATER)
        assertTrue(LaunchCheck.problems(listOf(a, b)).single().contains("also belongs to a"))
        assertTrue(LaunchCheck.problems(listOf(a), boundaries = listOf("x")).single().contains("boundaries"))
        assertEquals("camera", Launch.stageOf("view from saved")?.name)
        assertEquals("composition", Launch.stageOf("compose TrackControls")?.name)
        assertEquals(null, Launch.stageOf("onResume"))
    }

    @Test fun aReaderNeedsItsWriter() {
        val v = Value<Any>("v", Gate.LATER)
        val w = Stage("w", runs = Runs.WORKER, gate = Gate.LATER, publishes = listOf(v))
        val r = Stage("r", gate = Gate.LATER, reads = listOf(v))
        assertTrue(LaunchCheck.problems(listOf(w, r)).single().contains("without needing its writer w"))
        assertEquals(emptyList(), LaunchCheck.problems(listOf(w, r.copy(needs = listOf("w")))))
        assertTrue(LaunchCheck.problems(listOf(r)).single().contains("no stage publishes"))
        val twice = Stage("w2", runs = Runs.WORKER, gate = Gate.LATER, publishes = listOf(v))
        assertTrue(LaunchCheck.problems(listOf(w, twice)).single().contains("also published by w"))
        val wrongGate = Stage("w3", runs = Runs.WORKER, gate = Gate.BEFORE_STYLE, publishes = listOf(v))
        assertTrue(LaunchCheck.problems(listOf(wrongGate)).single().contains("gate"))
    }

    /** The runner's policy on a model clock, many seeds: no stage starts before its needs end; the main thread runs one at a time. */
    @Test fun aSeededSimulationNeverStartsAStageBeforeItsNeeds() {
        for (seed in 0 until 200) {
            val rnd = Random(seed)
            val lengths = Launch.stages.associate { it.name to rnd.nextLong(0, 500) }
            val t = LaunchCheck.schedule(Launch.stages) { lengths.getValue(it.name) }
            assertEquals(Launch.stages.map { it.name }.toSet(), t.keys, "seed $seed: every stage ran")
            for (s in Launch.stages) for (n in s.needs) {
                assertTrue(t.getValue(s.name).first >= t.getValue(n).last, "seed $seed: ${s.name} started before $n ended")
            }
            val main = Launch.stages.filter { it.runs == Runs.MAIN }.map { t.getValue(it.name) }
            main.zipWithNext().forEach { (a, b) -> assertTrue(b.first >= a.last, "seed $seed: two main-thread stages overlap") }
        }
    }

    // ---------- T387 (ADR 0071): the gate of a start without a screen ----------

    @Test fun theDeclaredLaunchPassesTheGatesRule() {
        assertEquals(emptyList(), LaunchGate.problems(Launch.stages))
    }

    /** ADR 0071 decision 2: a start without a screen does not copy the overview, check for an update, run the budget, sample or resume map work. */
    @Test fun aStartWithoutAScreenDefersTheStagesTheRuleNames() {
        val deferred = LaunchGate.deferredWithoutScreen(Launch.stages).map { it.name }.toSet()
        assertEquals(setOf("world", "updater", "budget", "sampler", "pending"), deferred)
        // What such a start still runs: the settings, a recording's tracker, the packs, the tile service and the servers.
        for (s in listOf(Launch.SETTINGS, Launch.TRACKING, Launch.PACKS, Launch.ONLINE_SERVICE, Launch.TILES_SERVICE, Launch.SERVER, Launch.DOWNLOADS_STAGE)) {
            assertEquals(Waits.NOTHING, s.waits, "${s.name} runs at every start")
        }
        // Under a notified job (a recording restarted by the system) only the sampler opens; the rest waits for the screen.
        val underJob = Launch.stages.filter { !LaunchGate.opens(it.waits, screenSeen = false, workAllowed = true) }.map { it.name }.toSet()
        assertEquals(setOf("world", "updater", "budget", "pending"), underJob)
        assertTrue(Launch.stages.all { LaunchGate.opens(it.waits, screenSeen = true, workAllowed = false) }, "after the first screen every stage may start")
    }

    @Test fun opensFollowsTheScreenAndTheNotifiedWork() {
        for (screen in listOf(false, true)) for (work in listOf(false, true)) {
            assertTrue(LaunchGate.opens(Waits.NOTHING, screen, work))
            assertEquals(screen || work, LaunchGate.opens(Waits.WORK, screen, work), "WORK screen=$screen work=$work")
            assertEquals(screen, LaunchGate.opens(Waits.SCREEN, screen, work), "SCREEN screen=$screen work=$work")
        }
    }

    @Test fun aWaitingStageMustBeALateWorkerTheRunnerRuns() {
        val ok = Stage("w", runs = Runs.WORKER, gate = Gate.LATER, waits = Waits.SCREEN)
        assertEquals(emptyList(), LaunchGate.problems(listOf(ok)))
        assertTrue(LaunchGate.problems(listOf(ok.copy(runs = Runs.MAIN))).single().contains("main thread"))
        assertTrue(LaunchGate.problems(listOf(ok.copy(gate = Gate.FIRST_FRAME))).single().contains("not LATER"))
        assertTrue(LaunchGate.problems(listOf(ok.copy(bound = Bound.OBSERVED))).single().contains("does not run it"))
    }

    @Test fun aStageWaitsAtLeastAsLongAsItsNeeds() {
        val screen = Stage("s", runs = Runs.WORKER, gate = Gate.LATER, waits = Waits.SCREEN)
        val work = Stage("w", runs = Runs.WORKER, gate = Gate.LATER, waits = Waits.WORK)
        val now = Stage("n", needs = listOf("s"), runs = Runs.WORKER, gate = Gate.LATER)
        assertTrue(LaunchGate.problems(listOf(screen, now)).single().contains("needs s, which waits for SCREEN"))
        assertTrue(LaunchGate.problems(listOf(screen, work.copy(needs = listOf("s")))).single().contains("waits for WORK but needs s"))
        assertEquals(emptyList(), LaunchGate.problems(listOf(work, screen.copy(needs = listOf("w")))))
        // A stage added after the real list that needs the updater must wait for the screen too.
        val needsUpdater = Stage("after", needs = listOf("updater"), runs = Runs.WORKER, gate = Gate.LATER)
        assertTrue(LaunchGate.problems(Launch.stages + needsUpdater).any { it.startsWith("after: waits for NOTHING but needs updater") })
    }

    @Test fun theStateReadsOneWordFromTheFigures() {
        fun st(bound: Boolean = true, needs: Boolean = true, started: Boolean = false, ended: Boolean = false, failed: Boolean = false, waits: Waits = Waits.SCREEN, screen: Boolean = false, work: Boolean = false) =
            LaunchGate.state(bound, needs, started, ended, failed, waits, screen, work)
        assertEquals(StageState.NOT_REACHED, st(bound = false, waits = Waits.NOTHING))
        assertEquals(StageState.WAITING, st(needs = false))
        assertEquals(StageState.DEFERRED, st())
        assertEquals(StageState.DEFERRED, st(work = true), "a notified job does not open a stage that waits for the screen")
        assertEquals(StageState.RUNNING, st(waits = Waits.WORK, work = true))
        assertEquals(StageState.RUNNING, st(started = true))
        assertEquals(StageState.DONE, st(started = true, ended = true))
        assertEquals(StageState.FAILED, st(started = true, ended = true, failed = true))
        assertEquals("deferred", StageState.DEFERRED.json)
    }
}
