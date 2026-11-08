package io.github.jasondorjeshort.dystopo.controls

/**
 * ADR 0053 build step 2 (T238): what only the web page places (`tools/demo/index.html`), by id and placement alone.
 * The page's controls whose values it keeps in the page (the layer switches of its own style, the static demo's
 * archive choice, the operator's server token) and its own disclosures (the panel, the operator's "Build a map here"
 * section, whose fields are that section's flow actions: `data-control="flow:buildMap"`).
 *
 * Ids are the page's own names, in the phone's style; a phone control that gains one of these makes it a shared item
 * ([SharedControls]) under the phone's key (ADR 0053 §3).
 */
object PageControls {
    private fun page(id: String, kind: ControlKind, category: String, order: Int, vararg elements: String) =
        ControlItem(id, kind, placements = mapOf(onWeb(category, order, "panel", *elements)))

    /** The panel's own `<summary>` (its title line), which opens and closes it. */
    val PANEL = page("panel", ControlKind.LINK, "about", 1)
    val CONTOURS = page("contours", ControlKind.SETTING, "map", 11, "contours")
    val OSM = page("osm", ControlKind.SETTING, "map", 12, "osm")
    val OSM_ARCHIVE = page("osmArchive", ControlKind.SETTING, "map", 13, "osmsel")
    val BUILD_MAP = page("buildMap", ControlKind.LINK, "data", 1)
    val SERVER_TOKEN = page("serverToken", ControlKind.SETTING, "data", 2, "packtoken")

    val items: List<ControlItem> = listOf(PANEL, CONTOURS, OSM, OSM_ARCHIVE, BUILD_MAP, SERVER_TOKEN)
}
