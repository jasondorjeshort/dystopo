package io.github.jasondorjeshort.dystopo.controls

/**
 * ADR 0053: one definition of the controls two clients share (the registry, option C2) and where each client puts
 * them (the placement manifest, P1). Build step 1 (T208) holds the map options ([MapControls]); step 2 adds the rest
 * of the shared surface (units, text scale, the scale bar's both units, position and orientation, print, the trip
 * flow's entry points) and places every item of both clients. An item enters only when a second client gains it.
 *
 * [ControlsDocument] writes the registry as `tools/demo/controls.json`, the contract the web page and a later iOS app
 * read; a devtool test fails when the committed file differs (`./gradlew updateGoldens` refreshes it).
 *
 * Rules (ADR 0053 §1, §3, §4):
 * - An item's id is its existing phone settings key; a web element id (`hill`, `opt-hillshade`) maps to it in
 *   the web [Placement]. No stored or synced key is renamed for the manifest (a rename is a migration, ADR 0033).
 * - A shared item whose values the registry cannot express keeps its parse in code and records only its id, kind
 *   and placement, never a second copy of its values.
 * - The categories are placeholders (today's six phone settings groups); the owner renames and reorders them
 *   afterwards (ADR 0031), and an agent transcribes that here.
 */
enum class ControlKind(val key: String) { SETTING("setting"), ACTION("action"), LINK("link"), DISPLAY("display") }

/** What a setting holds: one of [ControlItem.choices], on or off, or a number in [ControlItem.min]..[ControlItem.max]. */
enum class ValueType(val key: String) { CHOICE("choice"), BOOLEAN("boolean"), NUMBER("number") }

/**
 * A client and its zones, versioned per platform ([zonesVersion]): an iOS client's zones come as a new platform, and a
 * change to a platform's zone list raises its version.
 */
enum class Platform(val key: String, val zonesVersion: Int, val zones: List<String>) {
    WEB("web", 1, listOf("panel", "map-corner", "trips", "map")),
    ANDROID("android", 1, listOf("settings", "map-top", "map-right", "map-lower-right", "map-lower-left", "card", "menu", "screen")),
    ;

    companion object {
        fun byKey(key: String): Platform? = entries.firstOrNull { it.key == key }
    }
}

/** A category of the manifest: [order] is its place in each client's list of categories. */
data class Category(val id: String, val title: String, val order: Int)

/**
 * Where one client puts an item: its [category], its [order] within the category on that client, its [zone] (one of
 * [Platform.zones]) and what names it there: on the web the ids of the page elements that carry
 * `data-control="<item id>"` ([elements]); on Android the settings sheet row that draws it ([row], a `SettingsRow.id`)
 * for zone `settings`.
 */
data class Placement(
    val category: String,
    val order: Int,
    val zone: String,
    val elements: List<String> = emptyList(),
    val row: String? = null,
)

/**
 * One shared control. [default] is a [String] for a choice, a [Boolean] or a [Double]. [choicesByUnits] holds a
 * choice list that depends on the user's units (`ft`, `m`), offered after the fixed [choices]. [syncKey] is the key a
 * phone syncs it under; [placements] says where each client that has a control for it puts it ([platforms]).
 */
data class ControlItem(
    val id: String,
    val kind: ControlKind,
    val type: ValueType? = null,
    val choices: List<String> = emptyList(),
    val choicesByUnits: Map<String, List<Double>> = emptyMap(),
    val default: Any? = null,
    val min: Double? = null,
    val max: Double? = null,
    val syncKey: String? = null,
    val placements: Map<Platform, Placement> = emptyMap(),
) {
    val platforms: List<Platform> get() = Platform.entries.filter { it in placements }
}

/** The registry: the categories in order and every shared item. */
object Controls {
    /**
     * The placeholder categories: the phone's six `SettingsGroup`s (ids and titles as `SettingsLayout.kt` has them;
     * `SettingsLayoutTest` checks they stay equal) until the owner's Category column replaces them (ADR 0053, D1).
     */
    val categories: List<Category> = listOf(
        Category("data", "Data and storage", 1),
        Category("map", "Map options", 2),
        Category("display", "Units and display", 3),
        Category("navigation", "Navigation and tracking", 4),
        Category("sync", "Sync and sharing", 5),
        Category("about", "About and advanced", 6),
    )

    /** Every item, in document order. */
    val items: List<ControlItem> get() = MapControls.items

    fun item(id: String): ControlItem? = items.firstOrNull { it.id == id }

    fun category(id: String): Category? = categories.firstOrNull { it.id == id }

    /** The web element id → item id map the page's `data-control` attributes must follow. */
    fun webElements(): Map<String, String> = buildMap {
        for (item in items) for (el in item.placements[Platform.WEB]?.elements.orEmpty()) put(el, item.id)
    }
}
