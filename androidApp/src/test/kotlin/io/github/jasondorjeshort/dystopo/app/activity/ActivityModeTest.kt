package io.github.jasondorjeshort.dystopo.app.activity

import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.app.SettingsRegistryTest
import io.github.jasondorjeshort.dystopo.model.Activity
import io.github.jasondorjeshort.dystopo.routing.TravelMode
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** T263: the planner's default mode follows the app's current activity. */
class ActivityModeTest {
    @AfterTest
    fun reset() = AppSettings.load(SettingsRegistryTest.FakePrefs(mapOf("server" to "")))

    @Test
    fun eachActivityHasARouteMode() {
        assertEquals(TravelMode.HIKE, ActivityMode.routeMode(Activity.FOOT), "on foot: Hike, which the planner reads as Walk or Hike")
        assertEquals(TravelMode.BIKE, ActivityMode.routeMode(Activity.BIKE))
        assertEquals(TravelMode.DRIVE, ActivityMode.routeMode(Activity.ROAD))
        for (a in listOf(Activity.RAIL, Activity.BOAT, Activity.AIR)) assertEquals(TravelMode.HIKE, ActivityMode.routeMode(a), "$a: the walk at the other end")
    }

    @Test
    fun thePlannersDefaultFollowsTheStoredActivity() {
        for ((code, mode) in listOf("bike" to TravelMode.BIKE, "road" to TravelMode.DRIVE, "foot" to TravelMode.HIKE)) {
            AppSettings.load(SettingsRegistryTest.FakePrefs(mapOf("server" to "", "trackActivity" to code)))
            assertEquals(mode, ActivityMode.routeMode(AppSettings.trackActivity), code)
        }
        AppSettings.load(SettingsRegistryTest.FakePrefs(mapOf("server" to "")))
        assertEquals(TravelMode.HIKE, ActivityMode.routeMode(AppSettings.trackActivity), "the default activity, on foot")
    }

    @Test
    fun theChipNamesTheActivity() {
        assertEquals("Bike", ActivityChip.label(Activity.BIKE))
        assertEquals("Activity: On foot. Change the activity", ActivityChip.description(Activity.FOOT, recording = false))
        assertEquals("Recording's activity: Car or bus. Change the activity", ActivityChip.description(Activity.ROAD, recording = true))
    }
}
