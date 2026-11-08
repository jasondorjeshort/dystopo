package io.github.jasondorjeshort.dystopo.app

/**
 * T224, the map screen's layout (debug builds only, on the debug port; [MapLayout], shared `ui/OverlayLayout`):
 * - `GET /layout`: `window` (`widthPx`, `heightPx`, `density`, `widthDp`, `heightDp`, `insetsPx` left, top, right, bottom:
 *   the system bars and the display cutout, `orientation`), `fontScale`, `foreground`; `elements`, every element of the map
 *   as it is drawn now (`id` from ADR 0053's controls inventory, or its manifest name for `scaleBar`, `compass`,
 *   `onlineCard` and `firstRunCard`; `name`, `controls` the inventory rows it holds, `rectPx` left, top, right, bottom or
 *   null, `state` `shown`, `compact` or `hidden`, `reason`, and `within` for an element that lies inside another by
 *   design: the Online chip's card in `M8`). T241: every rectangle is read from the element's `Modifier.control` node as
 *   it was placed (`MapLayout.kt`), none worked out from an edge; the compass's from MapLibre's view. `measure`: the
 *   nodes' pass counter (`pass`, and `placements`, how many times a pass's placement was computed). `onlineCard` (T236):
 *   `open` and the card's `rectPx`, as in `elements`. `overlaps`, the pairs of drawn elements that share at least a whole
 *   pixel (`a`, `b`, `aName`, `bName`, `sizePx`; never an element and the one it lies within); `rule`, what the pure function would place from the same measured
 *   sizes (`elements` as above with `rectPx` and `state`; `sideColumn`, `cardColumns`, `buttonsDrawn`, `entriesDrawn`,
 *   `offCentre`, its own `overlaps`, always empty, and `differs`, the elements whose state it would change, with `today`,
 *   `rule` and `reason`). Nothing reads the rule yet: it is the first half of a layout authority, and `differs` and
 *   `overlaps` are the second half's list. The older routes' figures (`GET /nav` `layout`, `GET /route` `ui.layout`,
 *   `GET /scalebar`, `GET /readout` `layout`) still answer as they did.
 */
object LayoutRoutes {
    fun handle(app: DystopoApp, r: Request): Response? = when (r.method to r.path) {
        "GET" to "/layout" -> Response.json(app.map.onMain { MapLayout.json() })
        else -> null
    }
}
