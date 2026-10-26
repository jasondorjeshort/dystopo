package io.github.jasondorjeshort.dystopo.app.about

import io.github.jasondorjeshort.dystopo.app.PackSet
import io.github.jasondorjeshort.dystopo.attribution.PackMetadata
import io.github.jasondorjeshort.dystopo.boundaries.Countries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppCreditsTest {
    @Test
    fun theBundledCountrySetIsCreditedWithNoPackOpen() {
        val credits = AppCredits.compose(PackSet(emptyList(), null))
        val ne = credits.sources.single { it.source.id == Countries.REGISTRY_ID }
        assertEquals(listOf(AppCredits.BUNDLED_USE), ne.usedBy)
        assertTrue(ne.source.attribution.contains("Natural Earth"), ne.source.attribution)
        assertTrue(AppCredits.homePage(ne.source.attributionHtml)!!.contains("naturalearthdata.com"))
        // The map's compact line is the packs' and the server's, unchanged by a bundled resource.
        assertEquals("Sources", credits.compact)
    }

    @Test
    fun bundledIdsAreAddedOnceBesideWhatThePacksName() {
        val online = listOf(PackMetadata("online-osm", "server", mapOf("provenance" to listOf("osm", "natural-earth"))))
        val credits = AppCredits.compose(PackSet(emptyList(), null), online)
        val ne = credits.sources.single { it.source.id == Countries.REGISTRY_ID }
        assertEquals(listOf("online-osm", AppCredits.BUNDLED_USE), ne.usedBy)
        assertTrue(AppCredits.compose(PackSet(emptyList(), null), bundled = emptyList()).sources.none { it.source.id == Countries.REGISTRY_ID })
    }
}
