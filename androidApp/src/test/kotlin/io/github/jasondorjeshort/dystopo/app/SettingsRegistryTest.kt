package io.github.jasondorjeshort.dystopo.app

import android.content.SharedPreferences
import io.github.jasondorjeshort.dystopo.app.sync.SettingsSync
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * T248: the phone's settings registry changes no stored or synced byte (ADR 0033). A store seeded with a non-default
 * value of every stored key, in the type the app wrote it with, loads into the same `SettingsSync.current()` and
 * `GET /settings` as before the registry (the expected text was captured from `main` at e184b663 by this same test).
 */
class SettingsRegistryTest {
    /** An in-memory [SharedPreferences]: a read in another type than the stored one throws, as Android's does. */
    class FakePrefs(seed: Map<String, Any>) : SharedPreferences {
        val values = LinkedHashMap<String, Any>(seed)
        val writes = ArrayList<Pair<String, Any?>>()
        override fun getAll(): MutableMap<String, *> = LinkedHashMap(values)
        override fun getString(key: String, defValue: String?): String? = values[key]?.let { it as String } ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String, defValue: Int): Int = values[key]?.let { it as Int } ?: defValue
        override fun getLong(key: String, defValue: Long): Long = values[key]?.let { it as Long } ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = values[key]?.let { it as Float } ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key]?.let { it as Boolean } ?: defValue
        override fun contains(key: String): Boolean = key in values
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private fun put(k: String, v: Any?) = apply { writes += k to v; if (v == null) values.remove(k) else values[k] = v }
            override fun putString(key: String, value: String?) = put(key, value)
            override fun putStringSet(key: String, values: MutableSet<String>?) = put(key, values)
            override fun putInt(key: String, value: Int) = put(key, value)
            override fun putLong(key: String, value: Long) = put(key, value)
            override fun putFloat(key: String, value: Float) = put(key, value)
            override fun putBoolean(key: String, value: Boolean) = put(key, value)
            override fun remove(key: String) = put(key, null)
            override fun clear() = apply { values.clear() }
            override fun commit() = true
            override fun apply() {}
        }
    }

    /** Every key the app stores, in its stored type, each away from its default. */
    private val seed: Map<String, Any> = linkedMapOf(
        "textScale" to 1.4f, "units" to "m", "unitsSource" to "user", "warmup" to false, "server" to "",
        "map.hillshade" to "multi", "map.night" to "true", "map.trails" to "false", "map.contourInterval" to "50",
        "onlineCacheMb" to 2048L, "contourCacheMb" to 512L, "pinnedCacheMb" to 4096L, "cacheBudgetMb" to 8192L, "tripCorridorM" to 500,
        "routeSnapM" to 450, "debugFixedPort" to true, "navStaleS" to 60, "navStartM" to 3000, "navSnoozeMin" to 20,
        "mapOrientation" to "facing", "navSpeech" to true,
        "paceStandard" to true, "paceFit" to "1;hike:1.0734,2.8029,-0.0575,5,22788,0.057", "paceSince" to 1_790_000_000_000L,
        "turnDeadline" to "17:45", "turnMarginMin" to 45, "turnTypedTime" to "17:45",
        "trackActivity" to "bike", "trackProfile.bike" to "acc=20;autopause=1;net=0;interval=2000",
        "readoutBarometerMode" to "never", "readout.foot.speed" to "shown=1;pace=1;damping=high;pm=0;knots=0",
        "mapControls" to 3, "recenterZoom" to 16, "orientationLock" to true,
        "readoutBar" to false, "climbWindow" to 300, "pressureFallWarning" to "fewer",
        "waterAlongRoute" to false, "waterSpoken" to true, "viewRange" to 100, "timeEstimateForm" to "one",
        "shadeSpoken" to "always", "estimatePosition" to false, "showDivergence" to false,
        "tripDetailsOpen" to true, // T278
        "recordAskActivity" to false, // T263
        "drivePreference" to "quick", // T282
        "skyPlot" to "north", // T307
        "horizonSatellites" to false, "horizonPeaks" to false, "horizonSun" to false, // T308
        // T311
        "aircraft" to true, "aircraftRefreshS" to 30, "aircraftAirports" to "codes",
        // end T311
    )

    /** Maps sorted at every depth, so the text does not depend on insertion order. */
    private fun canon(v: Any?): String = when (v) {
        is Map<*, *> -> v.entries.sortedBy { it.key.toString() }.joinToString(", ", "{", "}") { "${it.key}=${canon(it.value)}" }
        is List<*> -> v.joinToString(", ", "[", "]") { canon(it) }
        else -> v.toString()
    }

    private val locale = java.util.Locale.getDefault()

    @BeforeTest
    fun usLocale() = java.util.Locale.setDefault(java.util.Locale.US) // the units before a choice follow the locale

    @AfterTest
    fun reset() {
        java.util.Locale.setDefault(locale)
        AppSettings.load(FakePrefs(mapOf("server" to "", "cacheBudgetMb" to io.github.jasondorjeshort.dystopo.app.cachebudget.BudgetPlan.DEFAULT_MB)))
    }

    /**
     * T257 (ADR 0033: keys added, none changed): the readout's pressure, GPS accuracy and fix age entries add
     * `app.readout.<activity>.<entry>` for each of six activities and three entries in `GET /settings`' `readout` map;
     * the snapshot predates them, so they are taken out before the comparison and checked on their own.
     */
    private val t257Entries = listOf("pressure", "accuracy", "fixAge")

    // T278 (ADR 0033: one key added, per device): `tripDetailsOpen` in `GET /settings` is taken out too, checked on its own.
    // T263 (ADR 0033: one key added, synced): `recordAskActivity` and `app.recordAskActivity` too.
    // T282 (ADR 0033: one key added, synced): `drivePreference` and `app.drivePreference` too.
    // T307 (ADR 0033: one key added, synced): `skyPlot` and `app.skyPlot` too.
    // T308 (ADR 0033: three keys added, synced): the Horizon view's toggles, `horizonSatellites`, `horizonPeaks`, `horizonSun`.
    private val t308Keys = listOf("horizonSatellites", "horizonPeaks", "horizonSun")
    // T311
    private val t311Keys = listOf("aircraft", "aircraftRefreshS", "aircraftAirports")
    // end T311
    private val laterKeys = setOf("tripDetailsOpen", "recordAskActivity", "app.recordAskActivity", "drivePreference", "app.drivePreference", "skyPlot", "app.skyPlot") +
        t308Keys + t308Keys.map { "app.$it" } +
        // T311 (ADR 0033: three keys added, synced): Flight tracking, its refresh and airport names.
        t311Keys + t311Keys.map { "app.$it" }
        // end T311
    private fun beforeT257(m: Map<String, Any?>): Map<String, Any?> =
        m.filterKeys { k -> !(k.startsWith("app.readout.") && t257Entries.any { k.endsWith(".$it") }) && k !in laterKeys }
            .mapValues { (k, v) -> if (k == "readout" && v is Map<*, *>) v.filterKeys { it !in t257Entries } else v }

    private fun t257Synced(): Map<String, Any?> = SettingsSync.current().filterKeys { it !in beforeT257(SettingsSync.current()) }

    @Test
    fun aSeededStoreSyncsAndReportsAsBefore() {
        AppSettings.load(FakePrefs(seed))
        assertEquals(SEEDED_SYNCED, canon(beforeT257(SettingsSync.current())))
        assertEquals(68 + 18 + 1 + 1 + 1 + 3 + 3, SettingsSync.current().size, "every synced key, none lost; T257 added 18, T263 one, T282 one, T307 one, T308 three, T311 three")
        assertEquals(18 + 1 + 1 + 1 + 3 + 3, t257Synced().size)
        assertTrue(t257Synced().filterKeys { it != "app.recordAskActivity" && it != "app.drivePreference" && it != "app.skyPlot" && it.removePrefix("app.") !in t308Keys && it.removePrefix("app.") !in t311Keys }.values.all { it == null }, "nothing stored for the new entries: their defaults")
        assertEquals(listOf("on", "30", "codes"), t311Keys.map { SettingsSync.current()["app.$it"] }, "T311: the seeded Flights settings sync as stored")
        assertEquals(listOf("off", "off", "off"), t308Keys.map { SettingsSync.current()["app.$it"] }, "T308: the seeded toggles sync as off")
        assertEquals("north", SettingsSync.current()["app.skyPlot"], "T307: the seeded north syncs as north")
        assertEquals("quick", SettingsSync.current()["app.drivePreference"], "T282: the seeded quick syncs as quick")
        assertEquals("off", SettingsSync.current()["app.recordAskActivity"], "T263: the seeded off syncs as off")
        assertEquals(SEEDED_JSON, canon(beforeT257(AppSettings.json())))
    }

    @Test
    fun anEmptyStoreHasTheSameDefaults() {
        val empty = FakePrefs(mapOf("server" to ""))
        AppSettings.load(empty)
        assertEquals(DEFAULT_SYNCED, canon(beforeT257(SettingsSync.current())))
        assertEquals(DEFAULT_JSON, canon(beforeT257(AppSettings.json())))
        @Suppress("UNCHECKED_CAST") val readout = AppSettings.json()["readout"] as Map<String, Any?>
        assertEquals("shown=1;pace=0;damping=medium;pm=0;knots=0", readout["pressure"], "T257: the pressure on by default")
        assertEquals("shown=0;pace=0;damping=medium;pm=0;knots=0", readout["accuracy"])
        assertEquals(listOf<Pair<String, Any?>>("cacheBudgetMb" to 1024L), empty.writes, "loading writes only the budget's migration")
    }

    /** T278: Trip details' open state is a plain per-device key: folded by default, stored, never synced. */
    @Test
    fun tripDetailsOpenIsPerDevice() {
        AppSettings.load(FakePrefs(mapOf("server" to "")))
        assertEquals(false, AppSettings.json()["tripDetailsOpen"])
        assertTrue(SettingsSync.current().keys.none { "tripDetails" in it }, "not synced")
        AppSettings.load(FakePrefs(seed))
        assertEquals(true, AppSettings.json()["tripDetailsOpen"])
        assertTrue(!AppSettings.TRIP_DETAILS_OPEN.synced)
    }

    /** T263: "Ask which activity when a recording starts": on by default (the owner, 2026-11-11), synced, a row under the activity. */
    @Test
    fun recordAskActivityIsSyncedAndOnByDefault() {
        AppSettings.load(FakePrefs(mapOf("server" to "")))
        assertEquals(true, AppSettings.recordAskActivity)
        assertEquals(true, AppSettings.json()["recordAskActivity"])
        assertEquals("on", SettingsSync.current()["app.recordAskActivity"])
        assertTrue(AppSettings.RECORD_ASK_ACTIVITY.synced)
        val plan = SettingsSync.plan(mapOf("app.recordAskActivity" to "off"), emptyMap(), SettingsRoutes.KEYS)
        assertEquals(false, assertIs<SettingsRoutes.Parsed.Ok>(SettingsRoutes.parse(plan.params))[AppSettings.RECORD_ASK_ACTIVITY])
        val rows = io.github.jasondorjeshort.dystopo.app.settings.SettingsLayout.rows(io.github.jasondorjeshort.dystopo.app.settings.SettingsGroup.NAVIGATION)
        assertEquals(rows.indexOf(io.github.jasondorjeshort.dystopo.app.settings.SettingsRow.TRACK_ACTIVITY) + 1, rows.indexOf(io.github.jasondorjeshort.dystopo.app.settings.SettingsRow.RECORD_ASK_ACTIVITY))
        assertEquals("Ask which activity when a recording starts", io.github.jasondorjeshort.dystopo.app.settings.SettingsRow.RECORD_ASK_ACTIVITY.label)
    }

    /**
     * T282: "Plan drives for" (ADR 0063 Decision 1): least time by default, synced, the Navigation page's last row; a stored value
     * this build does not know (a later `shortest`) reads as least time and is not written back; a synced one is not taken.
     * T285: `fuel` (Least fuel) is the third value: stored, synced and posted like the other two.
     */
    @Test
    fun drivePreferenceReadsAnUnknownValueAsLeastTime() {
        AppSettings.load(FakePrefs(mapOf("server" to "")))
        assertEquals(io.github.jasondorjeshort.dystopo.routing.DrivePreference.TIME, AppSettings.drivePreference)
        assertEquals("time", AppSettings.json()["drivePreference"])
        assertEquals("time", SettingsSync.current()["app.drivePreference"])
        assertTrue(AppSettings.DRIVE_PREFERENCE.synced)
        val later = FakePrefs(mapOf("server" to "", "drivePreference" to "shortest"))
        AppSettings.load(later)
        assertEquals(io.github.jasondorjeshort.dystopo.routing.DrivePreference.TIME, AppSettings.drivePreference)
        assertTrue(later.writes.none { it.first == "drivePreference" }, "not written back: ${later.writes}")
        assertEquals("shortest", later.values["drivePreference"])
        val plan = SettingsSync.plan(mapOf("app.drivePreference" to "quick"), emptyMap(), SettingsRoutes.KEYS)
        assertEquals(io.github.jasondorjeshort.dystopo.routing.DrivePreference.QUICK, assertIs<SettingsRoutes.Parsed.Ok>(SettingsRoutes.parse(plan.params))[AppSettings.DRIVE_PREFERENCE])
        assertIs<SettingsRoutes.Parsed.Error>(SettingsRoutes.parse(mapOf("drivePreference" to "shortest")))
        val rows = io.github.jasondorjeshort.dystopo.app.settings.SettingsLayout.rows(io.github.jasondorjeshort.dystopo.app.settings.SettingsGroup.NAVIGATION)
        assertEquals(rows[rows.size - 3], io.github.jasondorjeshort.dystopo.app.settings.SettingsRow.DRIVE_PREFERENCE, "the page's last row before T307's two")
        assertEquals("Plan drives for", io.github.jasondorjeshort.dystopo.app.settings.SettingsRow.DRIVE_PREFERENCE.label)
        assertEquals(listOf("Least time", "Least fuel", "Quicker planning"), io.github.jasondorjeshort.dystopo.app.settings.DRIVE_PREFERENCE_CHOICES.map { it.second })
    }

    /**
     * T307: "Sky plot" (ADR 0044): Follow the phone by default, synced, posted and read back; a stored value this build does
     * not know reads as the default; the Horizon view's link (T308; "GPS status" before) and the setting are the
     * Navigation page's last two rows.
     */
    @Test
    fun skyPlotFollowsThePhoneByDefault() {
        AppSettings.load(FakePrefs(mapOf("server" to "")))
        assertEquals(io.github.jasondorjeshort.dystopo.gnss.SkyPlotOrientation.PHONE, AppSettings.skyPlot)
        assertEquals("phone", AppSettings.json()["skyPlot"])
        assertEquals("phone", SettingsSync.current()["app.skyPlot"])
        assertTrue(AppSettings.SKY_PLOT.synced)
        val later = FakePrefs(mapOf("server" to "", "skyPlot" to "south"))
        AppSettings.load(later)
        assertEquals(io.github.jasondorjeshort.dystopo.gnss.SkyPlotOrientation.PHONE, AppSettings.skyPlot)
        assertTrue(later.writes.none { it.first == "skyPlot" })
        val posted = assertIs<SettingsRoutes.Parsed.Ok>(SettingsRoutes.parse(mapOf("skyPlot" to "north")))
        assertEquals(io.github.jasondorjeshort.dystopo.gnss.SkyPlotOrientation.NORTH, posted[AppSettings.SKY_PLOT])
        assertIs<SettingsRoutes.Parsed.Error>(SettingsRoutes.parse(mapOf("skyPlot" to "south")))
        val synced = SettingsSync.plan(mapOf("app.skyPlot" to "north"), emptyMap(), SettingsRoutes.KEYS)
        assertEquals(io.github.jasondorjeshort.dystopo.gnss.SkyPlotOrientation.NORTH, assertIs<SettingsRoutes.Parsed.Ok>(SettingsRoutes.parse(synced.params))[AppSettings.SKY_PLOT])
        val rows = io.github.jasondorjeshort.dystopo.app.settings.SettingsLayout.rows(io.github.jasondorjeshort.dystopo.app.settings.SettingsGroup.NAVIGATION)
        assertEquals(listOf(io.github.jasondorjeshort.dystopo.app.settings.SettingsRow.GNSS_STATUS, io.github.jasondorjeshort.dystopo.app.settings.SettingsRow.SKY_PLOT), rows.takeLast(2))
        assertEquals("Horizon view", io.github.jasondorjeshort.dystopo.app.settings.SettingsRow.GNSS_STATUS.label)
        assertEquals(listOf("Follow the phone", "North up"), io.github.jasondorjeshort.dystopo.app.gnss.SKY_PLOT_CHOICES.map { it.second })
    }

    /**
     * T308: the Horizon view's three toggles are on by default, each a synced switch of its own, posted and read back, and
     * [AppSettings.horizonLayers] follows them.
     */
    @Test
    fun theHorizonViewsTogglesAreOnByDefaultAndSynced() {
        AppSettings.load(FakePrefs(mapOf("server" to "")))
        assertEquals(io.github.jasondorjeshort.dystopo.horizon.HorizonLayers.DEFAULT, AppSettings.horizonLayers)
        for (k in t308Keys) {
            assertEquals(true, AppSettings.json()[k], k)
            assertEquals("on", SettingsSync.current()["app.$k"], k)
        }
        assertTrue(listOf(AppSettings.HORIZON_SATELLITES, AppSettings.HORIZON_PEAKS, AppSettings.HORIZON_SUN).all { it.synced })
        val posted = assertIs<SettingsRoutes.Parsed.Ok>(SettingsRoutes.parse(mapOf("horizonSatellites" to "0")))
        assertEquals(false, posted[AppSettings.HORIZON_SATELLITES])
        for ((s, v) in posted.plain) @Suppress("UNCHECKED_CAST") AppSettings.set(s as Setting<Any>, v)
        assertEquals(io.github.jasondorjeshort.dystopo.horizon.HorizonLayers(satellites = false), AppSettings.horizonLayers)
        assertEquals(AppSettings.HORIZON_PEAKS, AppSettings.horizonLayer(io.github.jasondorjeshort.dystopo.horizon.HorizonLayer.PEAKS))
        val synced = SettingsSync.plan(mapOf("app.horizonSun" to "off"), emptyMap(), SettingsRoutes.KEYS)
        assertEquals(false, assertIs<SettingsRoutes.Parsed.Ok>(SettingsRoutes.parse(synced.params))[AppSettings.HORIZON_SUN])
    }

    /** T285: Least fuel is stored, read back, synced and posted (the guard of T284 is gone). */
    @Test
    fun leastFuelIsTheThirdValue() {
        val stored = FakePrefs(mapOf("server" to "", "drivePreference" to "fuel"))
        AppSettings.load(stored)
        assertEquals(io.github.jasondorjeshort.dystopo.routing.DrivePreference.FUEL, AppSettings.drivePreference)
        assertEquals("fuel", AppSettings.json()["drivePreference"])
        assertEquals("fuel", SettingsSync.current()["app.drivePreference"])
        val posted = assertIs<SettingsRoutes.Parsed.Ok>(SettingsRoutes.parse(mapOf("drivePreference" to "fuel")))
        assertEquals(io.github.jasondorjeshort.dystopo.routing.DrivePreference.FUEL, posted[AppSettings.DRIVE_PREFERENCE])
        val synced = SettingsSync.plan(mapOf("app.drivePreference" to "fuel"), emptyMap(), SettingsRoutes.KEYS)
        assertEquals(io.github.jasondorjeshort.dystopo.routing.DrivePreference.FUEL, assertIs<SettingsRoutes.Parsed.Ok>(SettingsRoutes.parse(synced.params))[AppSettings.DRIVE_PREFERENCE])
        val p = FakePrefs(mapOf("server" to ""))
        AppSettings.load(p)
        AppSettings.set(AppSettings.DRIVE_PREFERENCE, io.github.jasondorjeshort.dystopo.routing.DrivePreference.FUEL)
        assertEquals("fuel", p.values["drivePreference"])
        val line = io.github.jasondorjeshort.dystopo.app.settings.DRIVE_PREFERENCE_CHOICES.first { it.first == io.github.jasondorjeshort.dystopo.routing.DrivePreference.FUEL }.third
        assertEquals("Favours steady roads and fewer climbs and turns. An estimate for an average car; it can take longer.", line)
    }

    @Test
    fun aChangeIsStoredUnderItsKeyInItsType() {
        val p = FakePrefs(seed)
        AppSettings.load(p)
        for (s in AppSettings.PLAIN) {
            // T250: a setting is stored under its store key (its key unless its stored type changed, ADR 0033).
            assertTrue(s.storeKey in seed, "the seed holds ${s.storeKey}, so the snapshot covers it")
            p.writes.clear()
            @Suppress("UNCHECKED_CAST")
            AppSettings.set(s as Setting<Any>, s.value)
            assertEquals(listOf(s.storeKey), p.writes.map { it.first }, "${s.key}: one write, under its own key")
            assertEquals(seed.getValue(s.storeKey)::class, p.writes.single().second!!::class, "${s.key}: stored in the type it always had")
            assertEquals(seed.getValue(s.storeKey), p.values[s.storeKey], "${s.key}: the same stored value back")
        }
    }

    @Test
    fun everyLineReadsItsOwnValuesAndNamesItsKey() {
        AppSettings.load(FakePrefs(seed))
        val keys = AppSettings.PLAIN.map { it.key }
        assertEquals(keys.toSet().size, keys.size, "one line per key")
        assertTrue(keys.none { it in SettingsRoutes.COMPOSITE_KEYS }, "a key is plain or composite, not both")
        assertEquals(SettingsRoutes.COMPOSITE_KEYS + keys, SettingsRoutes.KEYS)
        for (s in AppSettings.PLAIN) {
            @Suppress("UNCHECKED_CAST") val t = s as Setting<Any>
            for (v in listOf(t.default, t.value)) {
                // What a pull hands to POST /settings' parser (the synced value), and what GET /settings shows, both read back.
                assertEquals(v, assertIs<SettingsRoutes.Parsed.Ok>(SettingsRoutes.parse(mapOf(s.key to t.syncValue(v))))[t], "${s.key} from ${t.syncValue(v)}")
                assertEquals(v, t.parse(t.jsonValue(v)), "${s.key} from ${t.jsonValue(v)}")
            }
            val refused = assertIs<SettingsRoutes.Parsed.Error>(SettingsRoutes.parse(mapOf(s.key to "nonsense")))
            assertTrue(s.key in refused.message, "the 400 names ${s.key}: ${refused.message}")
        }
    }

    /**
     * T250 (ADR 0033): a store from before T250 holds T161's barometer switch as a boolean under `readoutBarometer`; it
     * loads as Automatic (on) or Never (off), written under `readoutBarometerMode` with the old key removed, and syncs and
     * reports as the seeded store with the new key does (`app.readoutBarometer` `off`, as before T250).
     */
    @Test
    fun theOldBarometerSwitchMigratesToTheMode() {
        val old = FakePrefs(seed - "readoutBarometerMode" + ("readoutBarometer" to false))
        AppSettings.load(old)
        assertEquals(io.github.jasondorjeshort.dystopo.readout.BarometerMode.NEVER, AppSettings.barometerMode)
        assertEquals(listOf<Pair<String, Any?>>("readoutBarometerMode" to "never", "readoutBarometer" to null), old.writes.filter { it.first.startsWith("readoutBarometer") })
        assertEquals("never", old.values["readoutBarometerMode"])
        assertTrue("readoutBarometer" !in old.values)
        assertEquals(SEEDED_SYNCED, canon(beforeT257(SettingsSync.current())))
        assertEquals(SEEDED_JSON, canon(beforeT257(AppSettings.json())))
        val on = FakePrefs(mapOf("server" to "", "readoutBarometer" to true))
        AppSettings.load(on)
        assertEquals(io.github.jasondorjeshort.dystopo.readout.BarometerMode.AUTO, AppSettings.barometerMode)
        assertEquals("auto", on.values["readoutBarometerMode"])
        assertEquals("on", SettingsSync.current()["app.readoutBarometer"])
        // A store that has the mode keeps it whatever an old key says.
        val both = FakePrefs(mapOf("server" to "", "readoutBarometer" to false, "readoutBarometerMode" to "always"))
        AppSettings.load(both)
        assertEquals(io.github.jasondorjeshort.dystopo.readout.BarometerMode.ALWAYS, AppSettings.barometerMode)
        assertEquals("always", SettingsSync.current()["app.readoutBarometer"], "Always syncs as always; a build before T250 skips it")
    }

    @Test
    fun aRequestMadeInCodeCarriesItsSettings() {
        val ok = SettingsRoutes.Parsed.Ok.of(AppSettings.NAV_SPEECH, true).and(AppSettings.TURN_MARGIN_MIN, 45)
        assertEquals(true, ok[AppSettings.NAV_SPEECH])
        assertEquals(45, ok[AppSettings.TURN_MARGIN_MIN])
        assertEquals(null, ok[AppSettings.WATER_SPOKEN])
        assertEquals(setOf("navSpeech", "turnMarginMin"), ok.plain.keys.map { it.key }.toSet())
    }

    private companion object {
        private const val SEEDED_SYNCED = """{app.climbWindow=300, app.estimatePosition=off, app.map.airportGates=true, app.map.boundaries=true, app.map.contourInterval=50, app.map.contourLabels=normal, app.map.drainage=false, app.map.hillshade=multi, app.map.hillshadeIntensity=1.0, app.map.landcover=true, app.map.night=on, app.map.pois=true, app.map.slope=off, app.map.trails=false, app.map.water=true, app.mapControls=3, app.navSpeech=on, app.orientationLock=on, app.paceFit=1;hike:1.0734,2.8029,-0.0575,5,22788,0.057, app.paceSince=1790000000000, app.paceStandard=on, app.pressureFallWarning=fewer, app.readout.air.climb=null, app.readout.air.course=null, app.readout.air.elevation=null, app.readout.air.speed=null, app.readout.bike.climb=null, app.readout.bike.course=null, app.readout.bike.elevation=null, app.readout.bike.speed=null, app.readout.boat.climb=null, app.readout.boat.course=null, app.readout.boat.elevation=null, app.readout.boat.speed=null, app.readout.foot.climb=null, app.readout.foot.course=null, app.readout.foot.elevation=null, app.readout.foot.speed=shown=1;pace=1;damping=high;pm=0;knots=0, app.readout.rail.climb=null, app.readout.rail.course=null, app.readout.rail.elevation=null, app.readout.rail.speed=null, app.readout.road.climb=null, app.readout.road.course=null, app.readout.road.elevation=null, app.readout.road.speed=null, app.readoutBar=off, app.readoutBarometer=off, app.recenterZoom=16, app.scaleBar.both=off, app.shadeSpoken=always, app.showDivergence=off, app.textScale=1.4, app.timeEstimateForm=one, app.trackActivity=bike, app.trackProfile.air=null, app.trackProfile.bike=acc=20;autopause=1;net=0;interval=2000, app.trackProfile.boat=null, app.trackProfile.foot=null, app.trackProfile.rail=null, app.trackProfile.road=null, app.tripCorridorM=500, app.turnDeadline=17:45, app.turnMarginMin=45, app.units=m, app.viewRange=100, app.waterAlongRoute=off, app.waterSpoken=on}"""
        private const val SEEDED_JSON = """{airportGates=true, baseTextScale=1.35, boundaries=true, cacheBudgetMb=8192, climbWindow=300, contourCacheMb=512, contourInterval=50, contourIntervalChoices=[auto, 2, 5, 10, 20, 25, 50, 100, 200, 250], contourIntervalUnits=m, contourLabels=normal, debugFixedPort=true, drainage=false, estimatePosition=false, hillshade=multi, hillshadeIntensity=1.0, landcover=true, mapControls=3, navSnoozeMin=20, navSpeech=true, navStaleS=60, navStartM=3000, night=on, onlineCacheMb=2048, orientationLock=true, paceFit=1;hike:1.0734,2.8029,-0.0575,5,22788,0.057, paceSince=1790000000000, paceStandard=true, pinnedCacheMb=4096, pois=true, pressureFallWarning=fewer, readout={climb=shown=0;pace=0;damping=medium;pm=0;knots=0, course=shown=1;pace=0;damping=medium;pm=0;knots=0, elevation=shown=1;pace=0;damping=medium;pm=0;knots=0, speed=shown=1;pace=0;damping=medium;pm=0;knots=0}, readoutBar=false, readoutBarometer=never, readoutStored={readout.foot.speed=shown=1;pace=1;damping=high;pm=0;knots=0}, recenterZoom=16, routeSnapM=450, routeSnapRange=[50, 1000], server=, shadeSpoken=always, shadeTiles=true, showDivergence=false, slope=off, slopeChoices=[off, avalanche, steepness], textScale=1.4, textScaleRange=[0.5, 3.0], timeEstimateForm=one, trackActivity=bike, trackProfile={accuracyM=20.0, activity=bike, autoPause=true, intervalS=2.0, networkFallback=false, value=acc=20;autopause=1;net=0;interval=2000}, trackProfiles={air={accuracyM=null, activity=air, autoPause=false, intervalS=1.0, networkFallback=true, stored=null, value=acc=none;autopause=0;net=1;interval=1000}, bike={accuracyM=20.0, activity=bike, autoPause=true, intervalS=2.0, networkFallback=false, stored=acc=20;autopause=1;net=0;interval=2000, value=acc=20;autopause=1;net=0;interval=2000}, boat={accuracyM=50.0, activity=boat, autoPause=false, intervalS=1.0, networkFallback=true, stored=null, value=acc=50;autopause=0;net=1;interval=1000}, foot={accuracyM=50.0, activity=foot, autoPause=false, intervalS=1.0, networkFallback=true, stored=null, value=acc=50;autopause=0;net=1;interval=1000}, rail={accuracyM=100.0, activity=rail, autoPause=false, intervalS=1.0, networkFallback=true, stored=null, value=acc=100;autopause=0;net=1;interval=1000}, road={accuracyM=100.0, activity=road, autoPause=false, intervalS=1.0, networkFallback=true, stored=null, value=acc=100;autopause=0;net=1;interval=1000}}, trails=false, tripCorridorM=500, turnDeadline=17:45, turnMarginMin=45, units=m, unitsSet=true, unitsSource=user, viewRange=100, warmup=false, water=true, waterAlongRoute=false, waterSpoken=true}"""
        private const val DEFAULT_SYNCED = """{app.climbWindow=60, app.estimatePosition=on, app.map.airportGates=true, app.map.boundaries=true, app.map.contourInterval=auto, app.map.contourLabels=normal, app.map.drainage=false, app.map.hillshade=single, app.map.hillshadeIntensity=1.0, app.map.landcover=true, app.map.night=off, app.map.pois=true, app.map.slope=off, app.map.trails=true, app.map.water=true, app.mapControls=1, app.navSpeech=off, app.orientationLock=off, app.paceFit=, app.paceSince=0, app.paceStandard=off, app.pressureFallWarning=standard, app.readout.air.climb=null, app.readout.air.course=null, app.readout.air.elevation=null, app.readout.air.speed=null, app.readout.bike.climb=null, app.readout.bike.course=null, app.readout.bike.elevation=null, app.readout.bike.speed=null, app.readout.boat.climb=null, app.readout.boat.course=null, app.readout.boat.elevation=null, app.readout.boat.speed=null, app.readout.foot.climb=null, app.readout.foot.course=null, app.readout.foot.elevation=null, app.readout.foot.speed=null, app.readout.rail.climb=null, app.readout.rail.course=null, app.readout.rail.elevation=null, app.readout.rail.speed=null, app.readout.road.climb=null, app.readout.road.course=null, app.readout.road.elevation=null, app.readout.road.speed=null, app.readoutBar=on, app.readoutBarometer=on, app.recenterZoom=14, app.scaleBar.both=off, app.shadeSpoken=hot, app.showDivergence=on, app.textScale=1.0, app.timeEstimateForm=range, app.trackActivity=foot, app.trackProfile.air=null, app.trackProfile.bike=null, app.trackProfile.boat=null, app.trackProfile.foot=null, app.trackProfile.rail=null, app.trackProfile.road=null, app.tripCorridorM=1000, app.turnDeadline=sunset, app.turnMarginMin=30, app.units=null, app.viewRange=30, app.waterAlongRoute=on, app.waterSpoken=off}"""
        private const val DEFAULT_JSON = """{airportGates=true, baseTextScale=1.35, boundaries=true, cacheBudgetMb=1024, climbWindow=60, contourCacheMb=1024, contourInterval=auto, contourIntervalChoices=[auto, 10, 20, 40, 50, 80, 100, 200, 250, 500, 1000], contourIntervalUnits=ft, contourLabels=normal, debugFixedPort=false, drainage=false, estimatePosition=true, hillshade=single, hillshadeIntensity=1.0, landcover=true, mapControls=1, navSnoozeMin=10, navSpeech=false, navStaleS=30, navStartM=2000, night=off, onlineCacheMb=1024, orientationLock=false, paceFit=, paceSince=0, paceStandard=false, pinnedCacheMb=2048, pois=true, pressureFallWarning=standard, readout={climb=shown=1;pace=0;damping=medium;pm=0;knots=0, course=shown=0;pace=0;damping=medium;pm=0;knots=0, elevation=shown=1;pace=0;damping=medium;pm=0;knots=0, speed=shown=0;pace=0;damping=medium;pm=0;knots=0}, readoutBar=true, readoutBarometer=auto, readoutStored={}, recenterZoom=14, routeSnapM=300, routeSnapRange=[50, 1000], server=, shadeSpoken=hot, shadeTiles=false, showDivergence=true, slope=off, slopeChoices=[off, avalanche, steepness], textScale=1.0, textScaleRange=[0.5, 3.0], timeEstimateForm=range, trackActivity=foot, trackProfile={accuracyM=50.0, activity=foot, autoPause=false, intervalS=1.0, networkFallback=true, value=acc=50;autopause=0;net=1;interval=1000}, trackProfiles={air={accuracyM=null, activity=air, autoPause=false, intervalS=1.0, networkFallback=true, stored=null, value=acc=none;autopause=0;net=1;interval=1000}, bike={accuracyM=50.0, activity=bike, autoPause=false, intervalS=1.0, networkFallback=true, stored=null, value=acc=50;autopause=0;net=1;interval=1000}, boat={accuracyM=50.0, activity=boat, autoPause=false, intervalS=1.0, networkFallback=true, stored=null, value=acc=50;autopause=0;net=1;interval=1000}, foot={accuracyM=50.0, activity=foot, autoPause=false, intervalS=1.0, networkFallback=true, stored=null, value=acc=50;autopause=0;net=1;interval=1000}, rail={accuracyM=100.0, activity=rail, autoPause=false, intervalS=1.0, networkFallback=true, stored=null, value=acc=100;autopause=0;net=1;interval=1000}, road={accuracyM=100.0, activity=road, autoPause=false, intervalS=1.0, networkFallback=true, stored=null, value=acc=100;autopause=0;net=1;interval=1000}}, trails=true, tripCorridorM=1000, turnDeadline=sunset, turnMarginMin=30, units=ft, unitsSet=false, unitsSource=locale default (not set yet), viewRange=30, warmup=true, water=true, waterAlongRoute=true, waterSpoken=false}"""
    }

    // T311
    /**
     * T311: Flight tracking is off by default, its refresh 20 s and airport names "both"; each synced, posted and read back;
     * a refresh outside the choices is refused with the key named; the three rows are the Map page's last.
     */
    @Test
    fun flightTrackingIsOffByDefaultAndSynced() {
        AppSettings.load(FakePrefs(mapOf("server" to "")))
        assertEquals(false, AppSettings.AIRCRAFT.value)
        assertEquals(20, AppSettings.AIRCRAFT_REFRESH_S.value)
        assertEquals(io.github.jasondorjeshort.dystopo.aircraft.AirportNaming.BOTH, AppSettings.AIRCRAFT_AIRPORTS.value)
        assertEquals(listOf("off", "20", "both"), t311Keys.map { SettingsSync.current()["app.$it"] })
        assertTrue(listOf(AppSettings.AIRCRAFT, AppSettings.AIRCRAFT_REFRESH_S, AppSettings.AIRCRAFT_AIRPORTS).all { it.synced })
        val posted = assertIs<SettingsRoutes.Parsed.Ok>(SettingsRoutes.parse(mapOf("aircraft" to "1", "aircraftRefreshS" to "60", "aircraftAirports" to "cities")))
        for ((s, v) in posted.plain) @Suppress("UNCHECKED_CAST") AppSettings.set(s as Setting<Any>, v)
        assertEquals(true, AppSettings.AIRCRAFT.value)
        assertEquals(60, AppSettings.AIRCRAFT_REFRESH_S.value)
        assertEquals(io.github.jasondorjeshort.dystopo.aircraft.AirportNaming.CITIES, AppSettings.AIRCRAFT_AIRPORTS.value)
        val refused = assertIs<SettingsRoutes.Parsed.Error>(SettingsRoutes.parse(mapOf("aircraftRefreshS" to "15")))
        assertTrue("aircraftRefreshS" in refused.message, refused.message)
        val synced = SettingsSync.plan(mapOf("app.aircraft" to "off", "app.aircraftRefreshS" to "10"), emptyMap(), SettingsRoutes.KEYS)
        val back = assertIs<SettingsRoutes.Parsed.Ok>(SettingsRoutes.parse(synced.params))
        assertEquals(false, back[AppSettings.AIRCRAFT])
        assertEquals(10, back[AppSettings.AIRCRAFT_REFRESH_S])
        val rows = io.github.jasondorjeshort.dystopo.app.settings.SettingsLayout.rows(io.github.jasondorjeshort.dystopo.app.settings.SettingsGroup.MAP)
        assertEquals(listOf("Flight tracking", "Flight tracking: Refresh", "Flight tracking: Airport names"), rows.takeLast(3).map { it.label })
    }
    // end T311
}
