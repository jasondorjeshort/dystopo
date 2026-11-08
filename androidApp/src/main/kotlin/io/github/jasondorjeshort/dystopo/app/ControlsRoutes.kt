package io.github.jasondorjeshort.dystopo.app

import io.github.jasondorjeshort.dystopo.controls.Controls
import io.github.jasondorjeshort.dystopo.controls.ControlsDocument
import io.github.jasondorjeshort.dystopo.controls.Platform

/**
 * T238 (ADR 0053 §5 part c, build step 2; debug builds only, on the debug port): the phone's half of the controls
 * manifest (`shared/.../controls/`, the same document as `/controls.json` on the servers).
 * - `GET /controls`: `version` (the document's), `zonesVersion` and `zones` (the phone's zone list), `count`,
 *   `byZone` (items per zone), and `items`, every item the manifest places on the phone in document order: `id`,
 *   `kind`, `zone`, `category`, `order`, and `row` (zone `settings`: the settings sheet row that draws it) or
 *   `inventory` (a map element: the controls inventory's rows `GET /layout` lists for it) with `tagged`, how many
 *   nodes carry its `Modifier.control` tag in the window's Compose semantics now (composed, on screen or not: a card
 *   that is down reads 0; the compass is MapLibre's own view and always reads 0). `strayTags`: tags in the window that
 *   name no manifest item. T241: `onScreen` beside `tagged`, whether the element is drawn in the window now (its
 *   `Modifier.control` node placed since its last measure, inside a parent control that is on screen, with an area; the
 *   compass while MapLibre draws it): `tagged` counts a card that is composed but not placed, `onScreen` does not.
 */
object ControlsRoutes {
    fun handle(app: DystopoApp, r: Request): Response? = when (r.method to r.path) {
        "GET" to "/controls" -> Response.json(app.map.onMain { MapLayout.tagsInWindow().let { t -> json(t.counts, onScreen(t.laidOut)) } })
        else -> null
    }

    /** `GET /controls`'s answer from the tags [tagged] counts (id to nodes) and the ids [onScreen] now. Pure. */
    fun json(tagged: Map<String, Int>, onScreen: Set<String> = emptySet()): Map<String, Any?> {
        val placed = Controls.placedOn(Platform.ANDROID)
        val ids = placed.mapTo(HashSet()) { it.id }
        return linkedMapOf(
            "version" to ControlsDocument.VERSION,
            "zonesVersion" to Platform.ANDROID.zonesVersion,
            "zones" to Platform.ANDROID.zones,
            "count" to placed.size,
            "byZone" to Platform.ANDROID.zones.associateWith { z -> placed.count { it.placements.getValue(Platform.ANDROID).zone == z } },
            "items" to placed.map { item ->
                val p = item.placements.getValue(Platform.ANDROID)
                linkedMapOf<String, Any?>("id" to item.id, "kind" to item.kind.key, "zone" to p.zone, "category" to p.category, "order" to p.order).apply {
                    if (p.row != null) put("row", p.row) else {
                        put("inventory", p.inventory)
                        put("tagged", tagged[item.id] ?: 0)
                        put("onScreen", item.id in onScreen) // T241
                    }
                }
            },
            "strayTags" to tagged.keys.filter { it !in ids }.sorted(),
        )
    }

    /** T241: the ids drawn in the window now, from their nodes ([MapLayout.controls]) whose layout nodes are placed ([laidOut]), and the compass. Main thread. */
    private fun onScreen(laidOut: Set<String>): Set<String> = MapLayout.controls.onScreen(laidOut) +
        listOfNotNull(io.github.jasondorjeshort.dystopo.ui.OverlayIds.COMPASS.takeIf { io.github.jasondorjeshort.dystopo.app.readout.CompassView.boundsPx() != null })
}
