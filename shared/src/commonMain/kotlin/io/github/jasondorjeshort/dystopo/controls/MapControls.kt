package io.github.jasondorjeshort.dystopo.controls

import io.github.jasondorjeshort.dystopo.maplibre.ContourLabelDensity
import io.github.jasondorjeshort.dystopo.render.SlopeShading
import io.github.jasondorjeshort.dystopo.tiles.HillshadeMode

/**
 * ADR 0053 build step 1 (T208): the map options of `/style.json`, the phone's Map options and the web page's Map
 * options section, as registry items. [io.github.jasondorjeshort.dystopo.maplibre.MapOptions] reads its keys
 * ([ids]), its defaults and its choices from here and keeps its parse (the error messages, `on` for avalanche, the
 * interval checked against the units).
 *
 * Choices come from the enums that parse them (one list each); the contour intervals depend on the units, so the
 * registry records both lists ([FEET_INTERVALS], [METRE_INTERVALS]) under `choicesByUnits`, after the fixed `auto`.
 *
 * Placement: every item is in the placeholder category `map`. Android: zone `settings`, drawn by the settings row
 * `mapOptions` (`MapOptionsSection`), in the order of that section. Web: zone `panel`, the Map options section of
 * `tools/demo/index.html` in page order, plus the panel's own Hillshade checkbox (`hill`); `hillshadeIntensity`
 * (a URL parameter there), `contourLabels` and `drainage` have no web control, so no web placement.
 */
object MapControls {
    val FEET_INTERVALS = listOf(10.0, 20.0, 40.0, 50.0, 80.0, 100.0, 200.0, 250.0, 500.0, 1000.0)
    val METRE_INTERVALS = listOf(2.0, 5.0, 10.0, 20.0, 25.0, 50.0, 100.0, 200.0, 250.0)

    /** `contourInterval`'s fixed choice ahead of the units' intervals: the per-zoom table. */
    const val AUTO = "auto"

    private const val CATEGORY = "map"
    private const val ROW = "mapOptions"

    private fun syncKey(id: String) = "app.map.$id"

    private fun place(android: Int, web: Int? = null, vararg elements: String): Map<Platform, Placement> = buildMap {
        put(Platform.ANDROID, Placement(CATEGORY, android, "settings", row = ROW))
        if (web != null) put(Platform.WEB, Placement(CATEGORY, web, "panel", elements = elements.toList()))
    }

    private fun choice(id: String, choices: List<String>, default: String, placements: Map<Platform, Placement>) =
        ControlItem(id, ControlKind.SETTING, ValueType.CHOICE, choices = choices, default = default, syncKey = syncKey(id), placements = placements)

    private fun toggle(id: String, default: Boolean, placements: Map<Platform, Placement>) =
        ControlItem(id, ControlKind.SETTING, ValueType.BOOLEAN, default = default, syncKey = syncKey(id), placements = placements)

    val HILLSHADE = choice("hillshade", HillshadeMode.entries.map { it.key }, HillshadeMode.SINGLE.key, place(1, 1, "opt-hillshade", "hill"))
    val HILLSHADE_INTENSITY = ControlItem(
        "hillshadeIntensity", ControlKind.SETTING, ValueType.NUMBER, default = 1.0, min = 0.0, max = 1.0,
        syncKey = syncKey("hillshadeIntensity"), placements = place(2),
    )
    val SLOPE = choice("slope", SlopeShading.entries.map { it.key }, SlopeShading.OFF.key, place(3, 2, "opt-slope"))
    val CONTOUR_INTERVAL = ControlItem(
        "contourInterval", ControlKind.SETTING, ValueType.CHOICE, choices = listOf(AUTO),
        choicesByUnits = linkedMapOf("ft" to FEET_INTERVALS, "m" to METRE_INTERVALS), default = AUTO,
        syncKey = syncKey("contourInterval"), placements = place(4, 3, "opt-interval"),
    )
    val CONTOUR_LABELS = choice("contourLabels", ContourLabelDensity.entries.map { it.key }, ContourLabelDensity.NORMAL.key, place(5))
    val TRAILS = toggle("trails", true, place(6, 5, "opt-trails"))
    val WATER = toggle("water", true, place(7, 6, "opt-water"))
    val LANDCOVER = toggle("landcover", true, place(8, 7, "opt-landcover"))
    val POIS = toggle("pois", true, place(9, 8, "opt-pois"))
    val BOUNDARIES = toggle("boundaries", true, place(10, 9, "opt-boundaries"))
    val DRAINAGE = toggle("drainage", false, place(11))
    val NIGHT = toggle("night", false, place(12, 4, "opt-night"))

    /** In `MapOptions.KEYS` order (the order the phone stores and syncs them in). */
    val items: List<ControlItem> = listOf(
        HILLSHADE, HILLSHADE_INTENSITY, SLOPE, CONTOUR_INTERVAL, CONTOUR_LABELS,
        TRAILS, WATER, LANDCOVER, POIS, BOUNDARIES, DRAINAGE, NIGHT,
    )

    /** The map option keys, in [items] order. */
    val ids: Set<String> = items.mapTo(LinkedHashSet()) { it.id }

    fun item(id: String): ControlItem = items.first { it.id == id }

    /** A choice item's default; throws for an id that is not one. */
    fun choiceDefault(id: String): String = item(id).default as String

    fun booleanDefault(id: String): Boolean = item(id).default as Boolean

    fun numberDefault(id: String): Double = item(id).default as Double

    /** "off, single or multi": a choice item's choices as an error message lists them. */
    fun choicesText(id: String): String = item(id).choices.let { c -> if (c.size < 2) c.joinToString() else c.dropLast(1).joinToString(", ") + " or " + c.last() }
}
