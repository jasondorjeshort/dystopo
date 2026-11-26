package io.github.jasondorjeshort.dystopo.packs

import io.github.jasondorjeshort.dystopo.areas.AreaDetail
import io.github.jasondorjeshort.dystopo.geo.WebMercator
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * T343: what the phone already holds over a box at a chosen detail, and by whom. One rule for the status chip, the map's
 * "Download this area" button and the Download card (`app/online/OnlineOverlay.kt`, `app/areas/AreaPicker.kt`), so the
 * three never disagree about a view.
 *
 * A box and a detail in ([held]); what is held and by whom out ([Held]). The holdings are the offline maps on the phone
 * (a map holds its box, at the zooms of its archives) and the downloaded areas (an area holds its own cells, not its box:
 * ADR 0066), each with its detail ([detailOf]) and whether it brings routing and place search. A map that is off (T342)
 * holds nothing for the verdict; it is reported apart ([Held.off]) so the chip can say "A map here is off".
 *
 * The box is cut into cells (the tiles of the request's deepest zoom, or a coarser zoom when that would pass [MAX_CELLS]); a
 * cell is held by a map when the lookup has the map answer the cell (or, at the map's edge, the part of the cell inside the
 * box lies inside the map's box), and by an area when its cells hold the tile (past its depth, the tile's ancestor there). A cell is held **at the detail** when a holding of that detail or finer holds it and, when the download
 * would bring routing and place search ([Want.routing]), that holding brings them too. [Verdict.ALL] (nothing new) is every
 * cell held at the detail; the card then offers no Download of that detail and the button reads "Already on this phone".
 *
 * Details compare as the app's three ([AreaDetail]) by their least depth ([wantOf]): full is elevation and map to z14 (the
 * pack path's full detail; the area path fetches to z15, the same detail in the card's words), medium is the map to z14 with
 * elevation to z13, less both to z13. A holding shallower than less detail is [LOW].
 *
 * T359 (ADR 0066 build task 5): whether a holding holds a cell is the shared lookup's answer ([Holdings.tile], through
 * [lookup]), so the card, the button and the chip read what `GET /holdings` reads; the signature (a box and a detail in, what
 * is held and by whom out) is T343's. Pure, tested (`HeldHereTest`).
 */
object HeldHere {
    /** At most this many cells per box; a wider box is cut at a coarser zoom. */
    const val MAX_CELLS = 16_384

    /** [detailOf]'s word for a holding shallower than less detail. */
    const val LOW = "low"

    enum class Kind { MAP, AREA }

    /**
     * One thing on the phone: an offline map ([Kind.MAP], holding [bounds]) or a downloaded area ([Kind.AREA], holding the
     * tiles [cells] answers true for; [bounds] its outline's box). [vectorMaxZoom] and [demMaxZoom] are its deepest zooms
     * (null: it has none of that kind); [routing] it brings routing and place search; [off] it is switched off (T342).
     */
    class Holding(
        val name: String,
        val kind: Kind,
        val bounds: PackBounds,
        val vectorMaxZoom: Int?,
        val demMaxZoom: Int?,
        val routing: Boolean,
        val off: Boolean = false,
        val cells: ((z: Int, x: Int, y: Int) -> Boolean)? = null,
    ) {
        /** The detail it holds: `full`, `medium`, `less` or [LOW]. */
        val detail: String get() = detailOf(vectorMaxZoom, demMaxZoom)
    }

    /** What a download would take: a detail ([AreaDetail.key]) and whether it brings routing and place search. */
    class Want(val detail: String, val routing: Boolean)

    enum class Verdict {
        /** Nothing on the phone (that is on) holds any of the box. */
        NOTHING,
        /** Something is held, but none of the box at the detail. */
        COARSER,
        /** Part of the box is held at the detail. */
        PART,
        /** Every cell is held at the detail: nothing new. */
        ALL,
    }

    /** One holding's part of the box: the [share] of its cells it holds (any detail), and whether it is of the detail. */
    class Holder(val name: String, val kind: Kind, val detail: String, val routing: Boolean, val share: Double, val atDetail: Boolean) {
        fun json(): Map<String, Any?> = linkedMapOf(
            "name" to name, "kind" to kind.name.lowercase(), "detail" to detail, "routing" to routing, "share" to round(share), "atDetail" to atDetail,
        )
    }

    /**
     * The answer: [verdict]; [shareAtDetail] of the box's cells held at the detail, [share] held at any detail; [holders]
     * the holdings that hold part of it, finest first (then the larger share, then the name); [off] the maps that are off
     * and would hold part of it; [cells] counted at [zoom].
     */
    class Held(
        val want: Want, val verdict: Verdict, val shareAtDetail: Double, val share: Double, val holders: List<Holder>, val off: List<Holder>,
        val cells: Int, val zoom: Int,
    ) {
        /** The finest holder, the one the card's line names; null when nothing is held. */
        val finest: Holder? get() = holders.firstOrNull()

        /** The holder that holds the whole box at the detail ([Verdict.ALL]'s map), else null. */
        val wholeAtDetail: Holder? get() = holders.firstOrNull { it.atDetail && it.share >= WHOLE }

        fun json(): Map<String, Any?> = linkedMapOf(
            "detail" to want.detail, "routing" to want.routing, "verdict" to verdict.name.lowercase(), "shareAtDetail" to round(shareAtDetail),
            "share" to round(share), "holders" to holders.map { it.json() }, "off" to off.map { it.json() }, "cells" to cells, "zoom" to zoom,
        )
    }

    /** A share this close to 1 is the whole box (a cell's rounding at the box's edge). */
    const val WHOLE = 0.999

    /** [detail]'s rank: less 0, medium 1, full 2; [LOW] and anything else -1. */
    fun rank(detail: String): Int = when (detail) {
        AreaDetail.FULL.key -> 2
        AreaDetail.MEDIUM.key -> 1
        AreaDetail.LESS.key -> 0
        else -> -1
    }

    /**
     * The detail of a holding with the map to [vectorMaxZoom] and elevation to [demMaxZoom]: full from both at z14, medium
     * from the map at z14 and elevation at z13, less from both at z13, else [LOW] (also with either missing).
     */
    fun detailOf(vectorMaxZoom: Int?, demMaxZoom: Int?): String {
        val v = vectorMaxZoom ?: -1
        val d = demMaxZoom ?: -1
        return when {
            v >= 14 && d >= 14 -> AreaDetail.FULL.key
            v >= 14 && d >= 13 -> AreaDetail.MEDIUM.key
            v >= 13 && d >= 13 -> AreaDetail.LESS.key
            else -> LOW
        }
    }

    /** The deepest zoom a download at [detail] takes (the cells are cut there). */
    fun zoomOf(detail: String): Int = when (detail) {
        AreaDetail.LESS.key -> AreaDetail.LESS.maxZoom
        else -> AreaDetail.MEDIUM.maxZoom // full and medium: the pack path's z14 (the area path's z15 is the same detail)
    }

    /** What the phone holds over [box] for a download of [want], from [holdings]. */
    fun held(box: PackBounds, want: Want, holdings: List<Holding>, maxCells: Int = MAX_CELLS): Held {
        val wantRank = rank(want.detail)
        var z = zoomOf(want.detail)
        while (z > 0 && cellCount(box, z) > maxCells) z--
        val n = (1 shl z) - 1
        val x0 = floor(WebMercator.lonToX(box.minLon, z)).toInt().coerceIn(0, n)
        val x1 = floor(WebMercator.lonToX(box.maxLon, z)).toInt().coerceIn(0, n)
        val y0 = floor(WebMercator.latToY(box.maxLat, z)).toInt().coerceIn(0, n)
        val y1 = floor(WebMercator.latToY(box.minLat, z)).toInt().coerceIn(0, n)
        val on = holdings.filter { !it.off && overlaps(it.bounds, box) }
        val off = holdings.filter { it.off && overlaps(it.bounds, box) }
        val lookOn = on.map { lookup(it) }
        val lookOff = off.map { lookup(it) }
        val onCount = IntArray(on.size)
        val offCount = IntArray(off.size)
        var cells = 0
        var any = 0
        var atDetail = 0
        for (y in y0..y1) {
            val north = min(WebMercator.yToLat(y.toDouble(), z), box.maxLat)
            val south = max(WebMercator.yToLat((y + 1).toDouble(), z), box.minLat)
            for (x in x0..x1) {
                val west = max(WebMercator.xToLon(x.toDouble(), z), box.minLon)
                val east = min(WebMercator.xToLon((x + 1).toDouble(), z), box.maxLon)
                if (east <= west || north <= south) continue // a sliver outside the box
                cells++
                var held = false
                var good = false
                for ((i, h) in on.withIndex()) {
                    if (!holds(lookOn[i], z, x, y, west, south, east, north)) continue
                    onCount[i]++
                    held = true
                    if (rank(h.detail) >= wantRank && (!want.routing || h.routing)) good = true
                }
                for (i in off.indices) if (holds(lookOff[i], z, x, y, west, south, east, north)) offCount[i]++
                if (held) any++
                if (good) atDetail++
            }
        }
        fun holders(list: List<Holding>, counts: IntArray) = list.indices.filter { counts[it] > 0 }.map { i ->
            val h = list[i]
            Holder(h.name, h.kind, h.detail, h.routing, counts[i].toDouble() / cells, rank(h.detail) >= wantRank && (!want.routing || h.routing))
        }.sortedWith(compareByDescending<Holder> { rank(it.detail) }.thenByDescending { it.share }.thenBy { it.name })
        val share = if (cells == 0) 0.0 else any.toDouble() / cells
        val shareAt = if (cells == 0) 0.0 else atDetail.toDouble() / cells
        val verdict = when {
            cells == 0 || any == 0 -> Verdict.NOTHING
            atDetail == cells -> Verdict.ALL
            atDetail > 0 -> Verdict.PART
            else -> Verdict.COARSER
        }
        return Held(want, verdict, shareAt, share, holders(on, onCount), holders(off, offCount), cells, z)
    }

    /**
     * T359 (ADR 0066 build task 5): [h] as the lookup's holding ([Holdings.Holding]): a map holds its box for each kind it has,
     * to its deepest zoom; an area holds its own cells. Listed as on whatever [Holding.off] says ([held] keeps the maps that
     * are off apart and asks whether they would hold a cell).
     */
    fun lookup(h: Holding): Holdings.Holding {
        val kind = if (h.kind == Kind.MAP) Holdings.Kind.MAP else Holdings.Kind.AREA
        val cells = h.cells
        return Holdings.Holding(
            h.name, kind, h.vectorMaxZoom?.let { Holdings.Stored(0, it, h.bounds) }, h.demMaxZoom?.let { Holdings.Stored(0, it, h.bounds) },
            cells = cells?.let { c -> { _, z, x, y -> c(z, x, y) } },
        )
    }

    /**
     * True when [h] holds cell z/x/y, whose part inside the box is [west]..[east], [south]..[north], by the lookup
     * ([Holdings.tile], each role [h] stores): it answers the cell (a map's elevation past its depth is made from its deepest
     * tile; a holding asked past its depth answers by the cell's ancestor at its depth). A map whose box covers only part of
     * the tile ([Holdings.How.EDGE]) holds it where the part inside the box lies inside the map's box.
     */
    private fun holds(h: Holdings.Holding, z: Int, x: Int, y: Int, west: Double, south: Double, east: Double, north: Double): Boolean {
        for (role in Holdings.Role.entries) {
            val s = h.stored(role) ?: continue
            val d = z - s.maxZoom
            val m = Holdings.tile(role, z, x, y, listOf(h), null).members.firstOrNull()
                ?: (if (d > 0) Holdings.tile(role, s.maxZoom, x shr d, y shr d, listOf(h), null).members.firstOrNull() else null)
                ?: continue
            if (m.how != Holdings.How.EDGE) return true
            val b = s.bounds ?: return true
            if (west >= b.minLon - EPS && east <= b.maxLon + EPS && south >= b.minLat - EPS && north <= b.maxLat + EPS) return true
        }
        return false
    }

    private fun overlaps(a: PackBounds, b: PackBounds): Boolean = a.minLon < b.maxLon && b.minLon < a.maxLon && a.minLat < b.maxLat && b.minLat < a.maxLat

    private fun cellCount(box: PackBounds, z: Int): Long {
        val w = floor(WebMercator.lonToX(box.maxLon, z)) - floor(WebMercator.lonToX(box.minLon, z)) + 1
        val h = floor(WebMercator.latToY(box.minLat, z)) - floor(WebMercator.latToY(box.maxLat, z)) + 1
        return (w * h).toLong()
    }

    private fun round(v: Double): Double = kotlin.math.round(v * 1000) / 1000

    /** Degrees of slack at a map's edge (about a centimetre): a view's box at a map's box is inside it. */
    private const val EPS = 1e-7
}
