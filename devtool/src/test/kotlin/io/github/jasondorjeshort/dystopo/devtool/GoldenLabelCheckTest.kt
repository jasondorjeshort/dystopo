package io.github.jasondorjeshort.dystopo.devtool

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The golden label-list check catches what the loose label-region image tolerance would let through. */
class GoldenLabelCheckTest {
    private val case = Goldens.cases.first { Goldens.render(it).labels.size >= 2 }
    private val actual = Goldens.record(Goldens.render(case).labels)

    @Test
    fun formatAndParseRoundTrip() {
        val labels = Goldens.render(case).labels
        assertEquals(Goldens.record(labels), Goldens.parseLabels(Goldens.formatLabels(labels)))
        assertTrue(Goldens.compareLabels(actual, actual).isEmpty())
    }

    @Test
    fun extraLabelIsCaught() {
        val extra = actual + Goldens.RecordedLabel("9999", 10.0, 10.0, 0.0)
        val problems = Goldens.compareLabels(expected = actual, actual = extra)
        assertTrue(problems.any { "unexpected" in it && "9999" in it }, problems.toString())
        assertTrue(problems.any { "label count" in it }, problems.toString())
    }

    @Test
    fun wrongTextIsCaught() {
        val wrong = actual.mapIndexed { i, l -> if (i == 0) l.copy(text = l.text + "0") else l }
        val problems = Goldens.compareLabels(expected = actual, actual = wrong)
        assertTrue(problems.any { "missing" in it && "\"${actual[0].text}\"" in it }, problems.toString())
    }

    @Test
    fun movedOrMissingLabelIsCaught() {
        val moved = actual.mapIndexed { i, l -> if (i == 0) l.copy(x = l.x + 20) else l }
        assertTrue(Goldens.compareLabels(actual, moved).isNotEmpty())
        assertTrue(Goldens.compareLabels(actual, actual.drop(1)).isNotEmpty())
        // Sub-pixel jitter (different rasterizer, rounding) is fine.
        val jitter = actual.map { it.copy(x = it.x + 0.5, angleDeg = it.angleDeg + 0.5) }
        assertTrue(Goldens.compareLabels(actual, jitter).isEmpty())
    }
}
