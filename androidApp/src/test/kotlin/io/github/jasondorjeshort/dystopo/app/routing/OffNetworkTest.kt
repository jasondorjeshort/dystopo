package io.github.jasondorjeshort.dystopo.app.routing

import io.github.jasondorjeshort.dystopo.app.SettingsRoutes
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import io.github.jasondorjeshort.dystopo.routing.WayClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OffNetworkTest {
    @Test
    fun namesTheNearestTrailWithDistanceAndDirection() {
        val m = OffNetwork.message("start", 300.0, 1000.0, "Skyline Trail", 420.0, "north-east", ContourUnits.METERS)
        assertEquals(
            "No trail or road within 300 m of the start. Nearest: Skyline Trail, 420 m to the north-east. " +
                "Long-press closer to it, or raise Route snap in Settings to 500 m.",
            m,
        )
    }

    @Test
    fun beyondTheLargestSnapOnlySuggestsMovingCloser() {
        val m = OffNetwork.message("end", 1000.0, 1000.0, "an unnamed path", 2400.0, "west", ContourUnits.FEET)
        assertTrue(m.startsWith("No trail or road within 0.62 mi of the end. Nearest: an unnamed path, 1.49 mi to the west."), m)
        assertTrue(m.endsWith("Long-press closer to it."), m)
    }

    @Test
    fun nothingNearby() {
        assertEquals("No trail or road within 300 m of the start (none within 10.0 km).", OffNetwork.message("start", 300.0, 1000.0, null, null, null, ContourUnits.METERS))
    }

    @Test
    fun unnamedLinesAreDescribedByKind() {
        assertEquals("Skyline Trail", OffNetwork.lineName("Skyline Trail", WayClass.PATH))
        assertEquals("an unnamed track", OffNetwork.lineName(null, WayClass.TRACK))
        assertEquals("an unnamed road", OffNetwork.lineName(null, WayClass.TERTIARY))
    }

    @Test
    fun compassDirections() {
        assertEquals("north", OffNetwork.direction(46.0, 7.0, 46.01, 7.0))
        assertEquals("east", OffNetwork.direction(46.0, 7.0, 46.0, 7.01))
        assertEquals("south-west", OffNetwork.direction(46.0, 7.0, 45.99, 6.9856))
    }

    @Test
    fun snapSettingIsValidated() {
        val ok = SettingsRoutes.parse(mapOf("routeSnapM" to "750"))
        assertIs<SettingsRoutes.Parsed.Ok>(ok)
        assertEquals(750, ok.routeSnapM)
        assertIs<SettingsRoutes.Parsed.Error>(SettingsRoutes.parse(mapOf("routeSnapM" to "1500")))
        assertIs<SettingsRoutes.Parsed.Error>(SettingsRoutes.parse(mapOf("routeSnapM" to "20")))
        assertIs<SettingsRoutes.Parsed.Error>(SettingsRoutes.parse(mapOf("routeSnapM" to "far")))
        val fixed = SettingsRoutes.parse(mapOf("debugFixedPort" to "1"))
        assertIs<SettingsRoutes.Parsed.Ok>(fixed)
        assertEquals(true, fixed.debugFixedPort)
        assertIs<SettingsRoutes.Parsed.Error>(SettingsRoutes.parse(mapOf("debugFixedPort" to "maybe")))
    }

    @Test
    fun snapStepsUpInHundreds() {
        assertEquals(500.0, OffNetwork.snapUp(420.0, 1000.0))
        assertEquals(1000.0, OffNetwork.snapUp(990.0, 1000.0))
        assertEquals(1000.0, OffNetwork.snapUp(1200.0, 1000.0))
    }
}
