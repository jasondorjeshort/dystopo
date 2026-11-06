package io.github.jasondorjeshort.dystopo.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaceKindTest {
    @Test
    fun codesAndLabelsAreUnique() {
        assertEquals(PlaceKind.entries.size, PlaceKind.entries.map { it.code }.toSet().size)
        assertEquals(PlaceKind.entries.size, PlaceKind.entries.map { it.label }.toSet().size)
        for (k in PlaceKind.entries) assertEquals(k, PlaceKind.ofCode(k.code))
    }

    @Test
    fun airportGatesAreTheirOwnKindNamedByRef() {
        // T230: `aeroway` gates in both schemas; the layer's other features (runways, taxiways, aprons) are not indexed.
        assertTrue("aeroway" in PlaceClassifier.LAYERS)
        assertEquals(PlaceKind.GATE, PlaceClassifier.classify("aeroway", "gate", null))
        assertNull(PlaceClassifier.classify("aeroway", "runway", null))
        assertNull(PlaceClassifier.classify("aeroway", "taxiway", null))
        assertNull(PlaceClassifier.classify("aeroway", null, null))
        assertEquals(listOf("ref"), PlaceClassifier.nameKeys("aeroway"))
        assertNull(PlaceClassifier.nameKeys("outdoor_poi"))
        assertEquals(50, PlaceKind.GATE.code) // stored in index files: never changes
        assertEquals(PlaceKind.GATE, PlaceKind.ofLabel("gate"))
        // Other layers keep their kinds.
        assertEquals(PlaceKind.POI, PlaceClassifier.classify("aerodrome_label", "international", null))
        assertEquals(PlaceKind.HUT, PlaceClassifier.classify("outdoor_poi", "hut", null))
    }
}
