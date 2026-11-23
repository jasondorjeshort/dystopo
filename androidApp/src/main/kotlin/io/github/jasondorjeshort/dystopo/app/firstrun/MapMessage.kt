package io.github.jasondorjeshort.dystopo.app.firstrun

import io.github.jasondorjeshort.dystopo.packs.PackBounds

/**
 * T332 (the idea of 2026-11-20, a phone with its surroundings downloaded told it had no map): what the map says about the
 * maps on the phone, from one pure rule ([MapMessage.choose]). The phone **holds a map** when a region (a pack) is open or
 * a downloaded area of tiles is on the phone ([heldArea]); the first-run card and the no-map text are for a phone that
 * holds none, once the packs and the areas have been read. A phone that holds one, with the view wholly outside every
 * region and area and no online source drawing the view, reads [OUTSIDE_TEXT] with [OUTSIDE_ACTION]; an online source
 * draws the view, so it says nothing then.
 *
 * T342: a map switched off is held but covers nothing ([choose]'s `heldOff`): a phone whose maps are all off never sees the
 * first-run card, and with nothing online reads [OUTSIDE_TEXT] wherever the view is.
 */
enum class MapMessage(val code: String, val firstRun: Boolean, val hidesControls: Boolean) {
    /** The packs or the areas are still being read: no card and no text yet. */
    OPENING("opening", firstRun = false, hidesControls = false),
    /** Nothing held and nothing online: the first-run card, after "Not now" the no-map text; Recentre, the scale bar and the switch hide. */
    NOTHING_HELD("nothingHeld", firstRun = true, hidesControls = true),
    /** Nothing held, an online source draws: the first-run card (it offers to save a map); no text after "Not now". */
    NOTHING_HELD_ONLINE("nothingHeldOnline", firstRun = true, hidesControls = false),
    /** A map held, the view outside every one, nothing online: [OUTSIDE_TEXT] and the way to Offline maps. */
    OUTSIDE("outside", firstRun = false, hidesControls = false),
    /** Nothing to say. */
    NONE("none", firstRun = false, hidesControls = false);

    companion object {
        /**
         * Pure, tested. [opened]: the packs and the area records have been read. [regions] and [areas]: the boxes of the
         * open regions and of the downloaded areas. [view]: the visible box (null while the map is not up). [online]: an
         * online source (a server, or the phone's own reading of the public sources) draws the view. T342: [heldOff], a map
         * that is off is on the phone: held, covering nothing.
         */
        fun choose(opened: Boolean, regions: List<PackBounds>, areas: List<PackBounds>, view: PackBounds?, online: Boolean, heldOff: Boolean = false): MapMessage = when {
            !opened -> OPENING
            regions.isEmpty() && areas.isEmpty() && !heldOff -> if (online) NOTHING_HELD_ONLINE else NOTHING_HELD
            online || view == null -> NONE
            covers(regions + areas, view) -> NONE
            else -> OUTSIDE
        }

        /** Pure, tested: [view] shares any part with one of [boxes], edges included (a view straddling an edge is inside). */
        fun covers(boxes: List<PackBounds>, view: PackBounds): Boolean {
            // A view across the antimeridian comes as minLon > maxLon: its two halves.
            val halves = if (view.minLon <= view.maxLon) listOf(view) else listOf(
                PackBounds(view.minLon, view.minLat, 180.0, view.maxLat), PackBounds(-180.0, view.minLat, view.maxLon, view.maxLat),
            )
            return boxes.any { b -> halves.any { v -> v.minLon <= b.maxLon && v.maxLon >= b.minLon && v.minLat <= b.maxLat && v.maxLat >= b.minLat } }
        }

        /** Pure, tested: a downloaded area counts once it holds tiles: done, or stopped part way (an area that became a pack is a region). */
        fun heldArea(state: String, isPack: Boolean): Boolean = !isPack && (state == "done" || state == "partial")
    }
}

/** T332 (ADR 0031: listed for the owner): the map's line when the phone holds maps and none covers the view. */
internal const val OUTSIDE_TEXT = "No offline map covers this view"

/** T332: its action, opening Offline maps. */
internal const val OUTSIDE_ACTION = "Go to my offline maps"
