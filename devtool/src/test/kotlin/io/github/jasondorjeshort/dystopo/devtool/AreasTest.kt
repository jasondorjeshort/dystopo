package io.github.jasondorjeshort.dystopo.devtool

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AreasTest {
    @Test
    fun unknownAreaMessageListsOnlyPublicAreas() {
        val areas = listOf(
            NamedArea("rainier", listOf(-121.9, 46.75, -121.6, 46.95), "", local = false),
            NamedArea("secret-spot", listOf(1.0, 2.0, 3.0, 4.0), "", local = true),
        ).associateBy { it.name }
        val message = Areas.unknownAreaMessage("nowhere", areas)
        assertTrue("rainier" in message, message)
        assertFalse("secret-spot" in message, message)
    }
}
