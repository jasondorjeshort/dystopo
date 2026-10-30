package io.github.jasondorjeshort.dystopo.app.tracking

import io.github.jasondorjeshort.dystopo.app.SettingsRoutes
import io.github.jasondorjeshort.dystopo.app.sync.SettingsSync
import io.github.jasondorjeshort.dystopo.model.Activity
import io.github.jasondorjeshort.dystopo.model.ActivityProfile
import io.github.jasondorjeshort.dystopo.store.InMemoryTrackStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T172: one synced profile value per activity, holding only the four choices the settings rows set, over T158's defaults;
 * the remembered activity; the rows' own keys; and the values a sync brings.
 */
class RecordingProfilesTest {
    private fun ok(vararg p: Pair<String, Any?>) = assertIs<SettingsRoutes.Parsed.Ok>(SettingsRoutes.parse(mapOf(*p)))
    private fun bad(vararg p: Pair<String, Any?>) = assertIs<SettingsRoutes.Parsed.Error>(SettingsRoutes.parse(mapOf(*p)))

    @Test
    fun unsetIsTheResearchedDefaults() {
        for (a in Activity.entries) assertEquals(ActivityProfile.of(a), RecordingProfiles.profile(a, null))
        assertEquals("acc=50;autopause=0;net=1;interval=1000", RecordingProfiles.encode(ActivityProfile.of(Activity.FOOT)))
        assertEquals("acc=100;autopause=0;net=1;interval=1000", RecordingProfiles.encode(ActivityProfile.of(Activity.ROAD)))
        assertEquals("acc=none;autopause=0;net=1;interval=1000", RecordingProfiles.encode(ActivityProfile.of(Activity.AIR)))
    }

    @Test
    fun aValueSetsOnlyTheFourChoices() {
        val p = RecordingProfiles.profile(Activity.BIKE, "acc=20;autopause=1;net=0;interval=5000")
        val d = ActivityProfile.of(Activity.BIKE)
        assertEquals(d.copy(accuracyBoundM = 20.0, autoPause = true, networkFallback = false, intervalMs = 5000), p)
        assertNull(RecordingProfiles.profile(Activity.FOOT, "acc=none").accuracyBoundM)
        // A synced value cannot move a bound the rows do not show: other keys are dropped.
        assertEquals("acc=50;autopause=1;net=1;interval=1000", RecordingProfiles.parseValue(Activity.FOOT, "autopause=1;speed=1;gap=5;watchdog=1"))
        assertEquals(d.speedBoundMps, RecordingProfiles.profile(Activity.BIKE, RecordingProfiles.parseValue(Activity.BIKE, "speed=1;acc=20")).speedBoundMps)
        // Out of range or with nothing the rows set: refused.
        for (v in listOf("acc=0", "acc=-5", "acc=big", "interval=10", "interval=99999999", "autopause=2", "net=yes", "speed=3", "")) {
            assertNull(RecordingProfiles.parseValue(Activity.FOOT, v), v)
        }
    }

    @Test
    fun theRowKeysEditAndTheRememberedActivityParses() {
        assertEquals(Activity.BOAT, ok("trackActivity" to "boat").trackActivity)
        assertTrue(bad("trackActivity" to "skate").message.contains("trackActivity"))
        val e = ok("trackAccuracyM" to "off", "trackAutoPause" to "1", "trackNetworkFallback" to "0", "trackIntervalS" to "10").trackEdit!!
        assertEquals(RecordingProfiles.Edit(accuracyGiven = true, accuracyM = null, autoPause = true, networkFallback = false, intervalMs = 10_000), e)
        assertEquals(200.0, ok("trackAccuracyM" to 200).trackEdit!!.accuracyM)
        assertNull(ok("units" to "m").trackEdit)
        bad("trackAccuracyM" to "20000")
        bad("trackAutoPause" to "maybe")
        bad("trackIntervalS" to "0")
        // An edit applies to a profile's four fields only.
        val p = e.applyTo(ActivityProfile.of(Activity.ROAD))
        assertEquals(ActivityProfile.of(Activity.ROAD).copy(accuracyBoundM = null, autoPause = true, networkFallback = false, intervalMs = 10_000), p)
        assertFailsWith<IllegalArgumentException> { RecordingProfiles.parseEdit(mapOf("trackIntervalS" to "x")) }
    }

    @Test
    fun wholeValuesParseAsASyncBringsThem() {
        val parsed = ok("trackProfile.air" to "acc=200;interval=60000", "trackProfile.foot" to "autopause=1")
        assertEquals(
            mapOf(Activity.AIR to "acc=200;autopause=0;net=1;interval=60000", Activity.FOOT to "acc=50;autopause=1;net=1;interval=1000"),
            parsed.trackProfiles,
        )
        assertTrue(bad("trackProfile.foot" to "acc=0").message.contains("trackProfile.foot"))
        bad("trackProfile.skate" to "acc=20")
        assertEquals(Activity.entries.map { "trackProfile.${it.code}" }.toSet(), RecordingProfiles.KEYS)
    }

    @Test
    fun theKeysSyncToAnotherDevice() {
        var clock = 1_000L
        val a = InMemoryTrackStore("aaaa", wallClock = { clock })
        val b = InMemoryTrackStore("bbbb", wallClock = { clock })
        val act = SettingsSync.syncedKey(SettingsSync.TRACK_ACTIVITY)
        val prof = SettingsSync.syncedKey(RecordingProfiles.key(Activity.BIKE))
        assertEquals("app.trackActivity", act)
        assertEquals("app.trackProfile.bike", prof)
        val values = mapOf(act to "bike", prof to "acc=20;autopause=1;net=1;interval=2000")
        assertEquals(2, SettingsSync.write(a, listOf(act, prof), values, clock))
        clock += 1_000
        b.apply(a.bundle(b.vector()), clock)
        val plan = SettingsSync.plan(b.settings(), mapOf(act to "foot", prof to null), SettingsRoutes.KEYS)
        assertEquals(mapOf<String, Any?>("trackActivity" to "bike", "trackProfile.bike" to "acc=20;autopause=1;net=1;interval=2000"), plan.params)
        val parsed = assertIs<SettingsRoutes.Parsed.Ok>(SettingsRoutes.parse(plan.params))
        assertEquals(Activity.BIKE, parsed.trackActivity)
        assertEquals(2000L, RecordingProfiles.profile(Activity.BIKE, parsed.trackProfiles[Activity.BIKE]).intervalMs)
        assertTrue(SettingsSync.plan(b.settings(), values, SettingsRoutes.KEYS).params.isEmpty(), "already the same: nothing to apply")
    }

    @Test
    fun everyActivityHasALabel() {
        val labels = Activity.entries.map { RecordingProfiles.label(it) }
        assertEquals(labels.size, labels.toSet().size)
        assertEquals("On foot", RecordingProfiles.label(Activity.FOOT))
        assertEquals(listOf(null, 20.0, 50.0, 100.0, 200.0), RecordingProfiles.ACCURACY_CHOICES)
        assertEquals(listOf(1, 2, 5, 10, 30, 60), RecordingProfiles.INTERVAL_CHOICES_S)
    }
}
