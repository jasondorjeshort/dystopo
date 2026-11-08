package io.github.jasondorjeshort.dystopo.controls

/**
 * ADR 0053: one definition of the controls two clients share (the registry, option C2) and where each client puts
 * them (the placement manifest, P1). Build step 1 (T208) holds the map options ([MapControls]); step 2 (T238) adds the
 * rest of the shared surface ([SharedControls]: units, text scale, the scale bar, position and orientation, the credit
 * line, print, the trip flow's entry points) and places every item of one client too: the phone's settings rows and
 * the elements of its map screen ([AndroidControls]), the web page's own controls ([PageControls]). A registry item
 * (one carrying values: type, choices, default, range) enters only when a second client gains it; an item of one
 * client records only its id, kind and placement.
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
 * `data-control="<item id>"` ([elements]; `maplibregl.X` for a control the page adds with `addControl(new
 * maplibregl.X)`; an element without an id, such as a section's `<summary>`, carries the attribute and is not listed);
 * on Android the settings sheet row that draws it ([row], a `SettingsRow.id`) for zone `settings`, and elsewhere the
 * element's tag (`Modifier.control("<item id>")` in `app/MapLayout.kt`). [inventory]: the rows of ADR 0053's controls
 * inventory the element holds, as `GET /layout` lists them (`OverlayIds.CONTROLS`).
 */
data class Placement(
    val category: String,
    val order: Int,
    val zone: String,
    val elements: List<String> = emptyList(),
    val row: String? = null,
    val inventory: List<String> = emptyList(),
)

/** A phone placement ([Placement] on [Platform.ANDROID]). */
internal fun onAndroid(category: String, order: Int, zone: String, row: String? = null, inventory: List<String>? = null): Pair<Platform, Placement> =
    Platform.ANDROID to Placement(category, order, zone, row = row, inventory = inventory.orEmpty())

/** A web placement ([Placement] on [Platform.WEB]). */
internal fun onWeb(category: String, order: Int, zone: String, vararg elements: String): Pair<Platform, Placement> =
    Platform.WEB to Placement(category, order, zone, elements = elements.toList())

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

    /** The manifest's source files, under [DIR], each with the items it declares (refusal messages name the file). */
    const val DIR = "shared/src/commonMain/kotlin/io/github/jasondorjeshort/dystopo/controls"

    private val sources: List<Pair<String, List<ControlItem>>> by lazy {
        listOf(
            "MapControls.kt" to MapControls.items,
            "SharedControls.kt" to SharedControls.items,
            "AndroidControls.kt" to AndroidControls.items,
            "PageControls.kt" to PageControls.items,
        )
    }

    /** Every item, in document order: the map options, the rest of the shared surface, the phone's, the page's. */
    val items: List<ControlItem> by lazy { sources.flatMap { it.second } }

    fun item(id: String): ControlItem? = items.firstOrNull { it.id == id }

    /** The file under [DIR] that declares [id], or the directory when no file does (a refusal names where to add it). */
    fun sourceOf(id: String): String = sources.firstOrNull { (_, l) -> l.any { it.id == id } }?.let { "$DIR/${it.first}" } ?: "$DIR/"

    /** The items [platform] places, in document order. */
    fun placedOn(platform: Platform): List<ControlItem> = items.filter { platform in it.placements }

    fun category(id: String): Category? = categories.firstOrNull { it.id == id }

    /** The web element id → item id map the page's `data-control` attributes must follow. */
    fun webElements(): Map<String, String> = buildMap {
        for (item in items) for (el in item.placements[Platform.WEB]?.elements.orEmpty()) put(el, item.id)
    }
}
