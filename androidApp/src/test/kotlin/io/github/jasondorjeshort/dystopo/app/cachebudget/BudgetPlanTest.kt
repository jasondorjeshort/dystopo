package io.github.jasondorjeshort.dystopo.app.cachebudget

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T112: the budget's presets, floors, migration and eviction order ([BudgetPlan]). */
class BudgetPlanTest {
    private val mb = BudgetPlan.MB

    @Test
    fun presetsAreSmallByDefaultAndLargeForADedicatedHiker() {
        assertEquals(listOf(1024L, 8192L, 131072L), BudgetPlan.Preset.entries.map { it.mb })
        assertEquals(BudgetPlan.Preset.SMALL.mb, BudgetPlan.DEFAULT_MB)
        assertEquals(BudgetPlan.Preset.LARGE, BudgetPlan.presetOf(128 * 1024))
        assertNull(BudgetPlan.presetOf(3000))
        assertEquals(BudgetPlan.Preset.MEDIUM, BudgetPlan.Preset.of("medium"))
        // Every preset is a total the route accepts; the large one is half of a 256 GB phone.
        assertTrue(BudgetPlan.Preset.entries.all { it.mb in BudgetPlan.RANGE_MB })
        assertEquals(128L * 1024 * mb, BudgetPlan.Preset.LARGE.mb * mb)
    }

    @Test
    fun floorsAreSharesOfTheTotalAndLeaveRoomToShare() {
        val total = 1024 * mb
        assertEquals(256 * mb, BudgetPlan.floorBytes(CacheKind.ONLINE, total))
        assertEquals((total * 0.15).toLong(), BudgetPlan.floorBytes(CacheKind.DERIVED, total))
        assertEquals((total * 0.05).toLong(), BudgetPlan.floorBytes(CacheKind.SOURCES, total))
        assertTrue(CacheKind.entries.sumOf { it.floorShare } < 0.5, "most of the total is shared, not reserved")
        assertEquals(listOf(CacheKind.DERIVED), CacheKind.entries.filter { it.derived })
    }

    @Test
    fun aShareOfTheFreeSpaceIsPickedOnceAsAFixedSize() {
        // 25% of 100 GB free plus 1 GB already cached, rounded down to 64 MB.
        assertEquals(25856L, BudgetPlan.percentOfFreeMb(25, 100L * 1024 * mb, 1024 * mb))
        assertEquals(0L, BudgetPlan.percentOfFreeMb(10, 1000 * mb, 0) % 64)
        assertEquals(BudgetPlan.RANGE_MB.first, BudgetPlan.percentOfFreeMb(10, 100 * mb, 0)) // never below the range
        assertEquals(BudgetPlan.RANGE_MB.last, BudgetPlan.percentOfFreeMb(90, 4L shl 50, 0)) // nor above
        assertFailsWith<IllegalArgumentException> { BudgetPlan.percentOfFreeMb(95, 1, 1) }
    }

    @Test
    fun anUpgradeKeepsCapsThatWereRaised() {
        assertEquals(1024L, BudgetPlan.migrate(256, 256)) // the defaults before T112: the small preset
        assertEquals(1024L, BudgetPlan.migrate(128, 512))
        assertEquals(4096L, BudgetPlan.migrate(2048, 2048)) // raised: nothing shrinks
    }

    @Test
    fun derivedDataGoesFirstFromTheKindFurthestAboveItsFloor() {
        val total = 1000L
        // Floors: online 250, derived 150, sources 50.
        val used = mapOf(CacheKind.ONLINE to 700L, CacheKind.DERIVED to 300L, CacheKind.SOURCES to 100L)
        assertEquals(BudgetPlan.Step(CacheKind.DERIVED, 100), BudgetPlan.next(total, used))
        // Far over: derived only down to its floor, not further.
        val far = mapOf(CacheKind.ONLINE to 900L, CacheKind.DERIVED to 300L, CacheKind.SOURCES to 100L)
        assertEquals(BudgetPlan.Step(CacheKind.DERIVED, 150), BudgetPlan.next(total, far))
        // Derived at its floor: source data from the kind furthest above its floor.
        val atFloor = mapOf(CacheKind.ONLINE to 900L, CacheKind.DERIVED to 150L, CacheKind.SOURCES to 100L)
        assertEquals(BudgetPlan.Step(CacheKind.ONLINE, 150), BudgetPlan.next(total, atFloor))
        val sourcesFar = mapOf(CacheKind.ONLINE to 300L, CacheKind.DERIVED to 150L, CacheKind.SOURCES to 700L)
        assertEquals(BudgetPlan.Step(CacheKind.SOURCES, 150), BudgetPlan.next(total, sourcesFar))
    }

    @Test
    fun nothingGoesBelowAFloorAndAnExhaustedKindIsSkipped() {
        val total = 1000L
        assertNull(BudgetPlan.next(total, mapOf(CacheKind.ONLINE to 500L, CacheKind.DERIVED to 400L)), "within the total")
        // Over, but every kind at or under its floor (the pinned and protected rest is outside): nothing to do.
        assertNull(BudgetPlan.next(100L, mapOf(CacheKind.ONLINE to 25L, CacheKind.DERIVED to 15L, CacheKind.SOURCES to 5L), targetBytes = 10L))
        val used = mapOf(CacheKind.ONLINE to 700L, CacheKind.DERIVED to 400L)
        assertEquals(CacheKind.ONLINE, BudgetPlan.next(total, used, exhausted = setOf(CacheKind.DERIVED))?.kind)
        assertNull(BudgetPlan.next(total, used, exhausted = setOf(CacheKind.DERIVED, CacheKind.ONLINE)))
        // The target (total less the slack) sets how much a step frees; floors stay shares of the total.
        assertEquals(BudgetPlan.Step(CacheKind.DERIVED, 150), BudgetPlan.next(total, mapOf(CacheKind.ONLINE to 600L, CacheKind.DERIVED to 450L), targetBytes = 900L))
    }

    @Test
    fun theSlackIsOnePercentAtLeastFourMegabytes() {
        assertEquals(1024 * mb / 100, BudgetPlan.slackBytes(1024 * mb))
        assertEquals(4 * mb, BudgetPlan.slackBytes(64 * mb))
        assertEquals(128L * 1024 * mb / 100, BudgetPlan.slackBytes(128L * 1024 * mb))
        assertEquals(250L, BudgetPlan.slackBytes(1000L)) // a quarter at most
    }
}
