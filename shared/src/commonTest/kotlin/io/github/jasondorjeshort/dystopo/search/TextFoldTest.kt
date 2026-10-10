package io.github.jasondorjeshort.dystopo.search

import kotlin.test.Test
import kotlin.test.assertEquals

class TextFoldTest {
    @Test
    fun foldsAccentsCaseAndLigatures() {
        assertEquals("monch", TextFold.fold("Mönch"))
        assertEquals("monch", TextFold.fold("MÖNCH"))
        assertEquals("aiguille du midi", TextFold.fold("Aiguille du Midi"))
        assertEquals("grossglockner", TextFold.fold("Großglockner"))
        assertEquals("aeolus oeuvre", TextFold.fold("Æolus Œuvre"))
        assertEquals("zurich lodz sao", TextFold.fold("Zürich Łódź São"))
        assertEquals("cesky krumlov", TextFold.fold("Český Krumlov"))
    }

    @Test
    fun dropsCombiningMarksFromDecomposedText() {
        // "e" + U+0301 (combining acute), as text from a decomposing keyboard arrives.
        assertEquals("cafe", TextFold.fold("Café"))
    }

    @Test
    fun leavesOtherScriptsLowercased() {
        assertEquals("эльбрус", TextFold.fold("Эльбрус"))
        assertEquals(listOf("富士山"), TextFold.tokens("富士山"))
    }

    @Test
    fun tokensSplitOnPunctuationAndJoinApostrophes() {
        assertEquals(listOf("camp", "muir"), TextFold.tokens("Camp Muir"))
        assertEquals(listOf("st", "andrews", "lake"), TextFold.tokens("St. Andrew's Lake"))
        assertEquals(listOf("col", "de", "lepee", "epee"), TextFold.tokens("Col de l'Épée"))
        assertEquals(listOf("wonderland", "trail", "northern", "loop"), TextFold.tokens("Wonderland Trail;Northern-Loop"))
        assertEquals(listOf("4392", "m"), TextFold.tokens("4392 m"))
        assertEquals(emptyList(), TextFold.tokens("  -- ' "))
    }

    @Test
    fun normalizedJoinsTokensWithoutElisions() {
        assertEquals("col de lepee", TextFold.normalized("Col  de l'Épée!"))
    }
}
