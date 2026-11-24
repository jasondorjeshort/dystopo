package io.github.jasondorjeshort.dystopo.app.help

import io.github.jasondorjeshort.dystopo.readout.ReadoutEntry
import io.github.jasondorjeshort.dystopo.readout.ReadoutFault
import io.github.jasondorjeshort.dystopo.readout.ReadoutReason
import io.github.jasondorjeshort.dystopo.readout.ReadoutReasons
import io.github.jasondorjeshort.dystopo.readout.ReasonKind
import io.github.jasondorjeshort.dystopo.readout.ValueState
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T363: the readout's reason table. It fails when a reason a value can show has no sentence: every (entry, reason) of
 * [ReadoutReasons.POSSIBLE] (which `ReadoutReasonsTest` reaches with fixtures) in every state, every reason that explains
 * anything, every fault kind and restart cause of the record, and the pin's end. Each sentence says the state and what
 * ends it in one short line; no other product's name.
 */
class ReasonTextTest {
    @Test
    fun everyReasonAValueCanShowHasASentenceInEveryState() {
        for ((e, r) in ReadoutReasons.POSSIBLE) for (s in ValueState.entries + listOf(null)) {
            assertNotNull(ReasonText.sentence(e, s, r), "no sentence for ${e.code} ${s?.code} ${r.code}")
        }
        // Every reason that explains anything has a sentence somewhere and words for a screen reader.
        for (r in ReadoutReason.entries.filter { it.kind != ReasonKind.NONE }) {
            assertTrue(ReasonText.TABLE.keys.any { it.second == r }, "no sentence for ${r.code}")
            assertTrue(ReasonText.words(r).isNotBlank())
        }
        // Nothing in the table that no value can show.
        assertEquals(emptySet(), ReasonText.TABLE.keys.filter { it.second.kind != ReasonKind.NONE }.toSet() - ReadoutReasons.POSSIBLE -
            ReadoutEntry.entries.flatMap { e -> listOf(e to ReadoutReason.NO_FIX, e to ReadoutReason.RESUMING, e to ReadoutReason.ACTIVITY) }.toSet())
        assertNull(ReasonText.sentence(ReadoutEntry.ELEVATION, ValueState.SHOWN, null))
    }

    @Test
    fun eachSentenceIsOneShortLine() {
        val all = ReasonText.TABLE.values + ReasonText.NO_FIX_FIGURE
        for (t in all) {
            assertTrue(t.length <= ReasonText.MAX_SENTENCE, "${t.length} characters: $t")
            assertTrue(t.endsWith("."), t)
            assertTrue(':' in t, "the state, then what ends it: $t")
            assertFalse("  " in t || '\n' in t, t)
        }
        val names = File("../tools/user-text-names.txt").readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        for (t in all + ReadoutReason.entries.map { ReasonText.words(it) }) for (n in names) {
            assertFalse(Regex("(?<![A-Za-z0-9])" + Regex.escape(n) + "(?![A-Za-z0-9])", RegexOption.IGNORE_CASE).containsMatchIn(t), "$t names $n")
        }
    }

    @Test
    fun theDimmedFigureAndTheDashSayDifferentThings() {
        assertEquals(ReasonText.NO_FIX_FIGURE, ReasonText.sentence(ReadoutEntry.SPEED, ValueState.SHOWN, ReadoutReason.NO_FIX))
        assertEquals("No GPS fix: shown once fixes come.", ReasonText.sentence(ReadoutEntry.SPEED, ValueState.HIDDEN, ReadoutReason.NO_FIX))
        assertEquals("No GPS fix: the last height, until a fix comes.", ReasonText.sentence(ReadoutEntry.ELEVATION, ValueState.SHOWN, ReadoutReason.NO_FIX))
        assertEquals("No GPS fix: shown once fixes come.", ReasonText.sentence(ReadoutEntry.ELEVATION, null, ReadoutReason.NO_FIX), "before the first readout")
    }

    @Test
    fun everyFaultHasALineAndTheLastMinuteOnly() {
        val now = 500_000L
        for (k in ReasonText.FAULT_KINDS) {
            if (k == ReadoutFault.RESTART) for (c in ReasonText.RESTART_CAUSES) assertNotNull(ReasonText.fault(ReadoutFault(k, now - 5_000, cause = c, detail = 10.0), now, ContourUnits.METERS), "$k $c")
            else assertNotNull(ReasonText.fault(ReadoutFault(k, now - 5_000, lengthMs = 4_000), now, ContourUnits.METERS), k)
        }
        for (p in ReasonText.PIN_ENDS) assertTrue(ReasonText.pinEnd(p, 3_000)!!.startsWith("Set height no longer used, 3 s ago: "))
        assertNull(ReasonText.pinEnd("replaced", 3_000))
        // A 9 s gap: named while it ended within 60 s, gone after.
        val gap = ReadoutFault(ReadoutFault.GAP, 100_000, lengthMs = 9_000)
        assertEquals(listOf("GPS lost for 9 s, 0 s ago"), ReasonText.recent(listOf(gap), 109_000, ContourUnits.METERS))
        assertEquals(listOf("GPS lost for 9 s, 1 min ago"), ReasonText.recent(listOf(gap), 169_000, ContourUnits.METERS))
        assertEquals(emptyList(), ReasonText.recent(listOf(gap), 169_001, ContourUnits.METERS))
        // Late fixes are one line with their count; a jump in the user's units.
        val late = (1..3).map { ReadoutFault(ReadoutFault.LATE_FIX, 100_000L + it * 1000) }
        assertEquals(listOf("3 late GPS fixes skipped, last 7 s ago"), ReasonText.recent(late, 110_000, ContourUnits.METERS))
        val jump = ReadoutFault(ReadoutFault.RESTART, 100_000, cause = ReadoutFault.CAUSE_JUMP, detail = 3002.0)
        assertEquals("Position jumped 1.9 mi, 20 s ago", ReasonText.fault(jump, 120_000, ContourUnits.FEET))
        val clock = ReadoutFault(ReadoutFault.RESTART, 100_000, cause = ReadoutFault.CAUSE_CLOCK, detail = 3600.0)
        assertEquals("Phone clock went back 1 h, 20 s ago", ReasonText.fault(clock, 120_000, ContourUnits.FEET))
    }

    @Test
    fun theBarsLeftOutEntries() {
        assertNull(ReasonText.leftOut(emptyList(), false))
        assertEquals("Left off the bar for room: the course's degrees, fix age.", ReasonText.leftOut(listOf("courseDegrees", "fixAge"), false))
        assertEquals("The bar is hidden for room: every value is here.", ReasonText.leftOut(emptyList(), true))
    }
}
