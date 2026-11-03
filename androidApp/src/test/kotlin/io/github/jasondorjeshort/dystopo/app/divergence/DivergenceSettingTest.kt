package io.github.jasondorjeshort.dystopo.app.divergence

import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.app.SettingsRoutes
import io.github.jasondorjeshort.dystopo.app.help.HelpRegistry
import io.github.jasondorjeshort.dystopo.app.settings.SettingsGroup
import io.github.jasondorjeshort.dystopo.app.settings.SettingsLayout
import io.github.jasondorjeshort.dystopo.app.settings.SettingsRow
import io.github.jasondorjeshort.dystopo.app.sync.SettingsSync
import io.github.jasondorjeshort.dystopo.app.tracking.withKnown
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import io.github.jasondorjeshort.dystopo.routing.DivergenceText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * T213: the "Show trail disagreements" switch is a row of the Map page, on by default, a `POST /settings` key and a synced
 * one; the card's and the help's words are hedged; `GET /map/longpress` lists the actions the card draws (T197's follow-up).
 */
class DivergenceSettingTest {
    @Test
    fun theSwitchIsAMapRowOnByDefaultAndSynced() {
        assertEquals(SettingsGroup.MAP, SettingsRow.SHOW_DIVERGENCE.group)
        assertEquals("Show trail disagreements", SettingsRow.SHOW_DIVERGENCE.label)
        assertEquals(SettingsRow.SHOW_DIVERGENCE, SettingsLayout.rows(SettingsGroup.MAP).last())
        assertTrue(AppSettings.showDivergence, "on by default (the survey: a layer switch, default on)")
        assertEquals(true, AppSettings.json()["showDivergence"])
        assertTrue(AppSettings.KEY_SHOW_DIVERGENCE in SettingsRoutes.KEYS)
        val plan = SettingsSync.plan(mapOf("app.showDivergence" to "off"), emptyMap(), SettingsRoutes.KEYS)
        assertEquals(false, assertIs<SettingsRoutes.Parsed.Ok>(SettingsRoutes.parse(plan.params)).showDivergence)
    }

    @Test
    fun theWordsAreHedged() {
        val words = listOf(
            DivergenceText.TITLE, DivergenceText.SETTING, DivergenceText.DISMISS,
            DivergenceText.body(2, 140.0, ContourUnits.METERS), DivergenceText.body(1, 1400.0, ContourUnits.FEET),
        ) + listOf("longPress.divergence", "settings.showDivergence").flatMap { k -> ContourUnits.entries.map { u -> HelpRegistry.byKey(k)!!.text(u) } }
        for (w in words) assertFalse(Regex("\\b(error|errors|wrong|mistake)\\b", RegexOption.IGNORE_CASE).containsMatchIn(w), w)
        assertEquals("One of your tracks follows a line about 0.9 mi long that is not on the map", DivergenceText.body(1, 1400.0, ContourUnits.FEET))
    }

    @Test
    fun theLongPressActionsAreWhatTheCardDraws() {
        // T197's "I am at" item goes before Cancel when the card offers it; the fixed list otherwise.
        val fixed = listOf("Route to here", "Waypoint here", "Cancel")
        assertEquals(fixed, withKnown(fixed, null))
        assertEquals(listOf("Route to here", "Waypoint here", "I am at Panorama Point, 2,070 m", "Cancel"), withKnown(fixed, "I am at Panorama Point, 2,070 m"))
    }
}
