package io.github.jasondorjeshort.dystopo.app

import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
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
 *   name no manifest item. T241 adds whether each element is on screen.
 */
object ControlsRoutes {
    fun handle(app: DystopoApp, r: Request): Response? = when (r.method to r.path) {
        "GET" to "/controls" -> Response.json(app.map.onMain { json(tags()) })
        else -> null
    }

    /** `GET /controls`'s answer from the tags [tagged] counts (id to nodes). Pure. */
    fun json(tagged: Map<String, Int>): Map<String, Any?> {
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
                    }
                }
            },
            "strayTags" to tagged.keys.filter { it !in ids }.sorted(),
        )
    }

    /** Every test tag in the window's Compose roots, with how many nodes carry it. Main thread. */
    private fun tags(): Map<String, Int> {
        val out = HashMap<String, Int>()
        fun walk(n: SemanticsNode) {
            n.config.getOrNull(SemanticsProperties.TestTag)?.let { out[it] = (out[it] ?: 0) + 1 }
            n.children.forEach(::walk)
        }
        fun views(v: View) {
            if (v is RootForTest) walk(v.semanticsOwner.unmergedRootSemanticsNode)
            if (v is ViewGroup) for (i in 0 until v.childCount) views(v.getChildAt(i))
        }
        DystopoApp.instance.map.mapView?.rootView?.let(::views)
        return out
    }
}
