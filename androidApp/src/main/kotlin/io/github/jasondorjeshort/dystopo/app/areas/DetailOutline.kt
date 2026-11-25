package io.github.jasondorjeshort.dystopo.app.areas

import io.github.jasondorjeshort.dystopo.packs.HeldHere

/**
 * T366 (finding `docs/findings/2026-11-23-outline-colours-by-map-detail.md`): one function from an offline map's detail to how
 * its outline is drawn, by day and by night ([look]). One hue, indigo, ordered by lightness, so more detail reads stronger
 * against the paper (darker by day, lighter by night); width and dash rise with detail too ("thicker and more solid is
 * finer"), so the order survives without colour. A map that is off is grey and dotted; the area being drawn keeps the teal
 * and is the only filled shape. Saved outlines are drawn on a paper-coloured casing ([casingWidth]), coarse first and the
 * finest on top ([Level.order]). The key on the picker's card and the swatch on each Offline maps row use the same looks.
 *
 * A map's level comes from its deepest zooms through [HeldHere.detailOf] (the key T343's rule and T360 share): [levelOf].
 * Pure, tested (`DetailOutlineTest`).
 */
object DetailOutline {
    /**
     * The levels, coarse to fine, then the two that are not a detail. [key] is [HeldHere.detailOf]'s word (`GET /areas/rows`
     * `level`); [label] the name the key, the row and the outline's label show (ADR 0031: the owner's to adjust).
     */
    enum class Level(val key: String, val label: String, val order: Int) {
        /** The fourth, lowest level ([HeldHere.LOW]): a map shallower than less detail; its values wait for the smaller detail. */
        LOW(HeldHere.LOW, "Low detail", 1),
        LESS("less", "Less detail", 2),
        MEDIUM("medium", "Medium detail", 3),
        FULL("full", "Full detail", 4),
        /** A map that is off (T342): kept, read by nothing. Drawn first, under every other outline. */
        OFF("off", "Not used", 0),
        /** The area being drawn ([PickerLayer]): never a saved outline. */
        DRAFT("draft", "Area being drawn", 5),
    }

    /** The four details in order, coarse to fine (adjacent ones differ in colour, width and dash). */
    val DETAILS = listOf(Level.LOW, Level.LESS, Level.MEDIUM, Level.FULL)

    /**
     * How an outline is drawn: [colour] `#rrggbb`, [width] in dp, [dash] in line widths (MapLibre's `line-dasharray`; null for
     * solid), [dots] round caps (a dash of 0.4 then reads as a dot), [fill] the opacity of a fill (0: none).
     */
    data class Look(val colour: String, val width: Float, val dash: List<Float>?, val dots: Boolean = false, val fill: Float = 0f) {
        fun json(): Map<String, Any?> = linkedMapOf("colour" to colour, "width" to width.toDouble(), "dash" to dash?.map { it.toDouble() }, "dots" to dots, "fill" to fill.toDouble())
    }

    /** The map's paper by day and by night (`DefaultOsmStyle`'s background, `NightColors.of` of it). */
    const val DAY_PAPER = "#f6f1e4"
    const val NIGHT_PAPER = "#2f2816"

    /** The casing's colour: the paper, so an outline stays readable over forest, water and relief. */
    fun paper(night: Boolean): String = if (night) NIGHT_PAPER else DAY_PAPER

    /** The finding's table. */
    fun look(level: Level, night: Boolean): Look = when (level) {
        Level.LOW -> Look(if (night) "#7d8aae" else "#5f6f99", 1.5f, listOf(0.4f, 2.2f), dots = true)
        Level.LESS -> Look(if (night) "#a29dd3" else "#4c4d95", 2f, listOf(2f, 2f))
        Level.MEDIUM -> Look(if (night) "#c8b4f2" else "#38287d", 2.5f, listOf(5f, 2f))
        Level.FULL -> Look(if (night) "#f0e6ff" else "#1f0a58", 3f, null)
        Level.OFF -> Look(if (night) "#a09a8f" else "#67625a", 1.5f, listOf(0.4f, 3f), dots = true)
        Level.DRAFT -> Look(if (night) "#4fd1be" else "#00796b", 3.5f, null, fill = 0.12f)
    }

    /** The casing under a saved outline: 1 dp of paper each side. */
    const val CASING_DP = 1f
    fun casingWidth(l: Look): Float = l.width + 2 * CASING_DP

    /** The casing's dash, scaled so its dashes lie under the line's (a dash is counted in line widths). */
    fun casingDash(l: Look): List<Float>? = l.dash?.map { it * l.width / casingWidth(l) }

    /**
     * A map's level from its deepest zooms ([HeldHere.detailOf]); a map with one kind only (a terrain-only or a map-only pack)
     * by the one it has; [off] wins (T342). Null zooms on both sides: [Level.LOW].
     */
    fun levelOf(vectorMaxZoom: Int?, demMaxZoom: Int?, off: Boolean = false): Level =
        if (off) Level.OFF else byKey(HeldHere.detailOf(vectorMaxZoom ?: demMaxZoom, demMaxZoom ?: vectorMaxZoom))

    /** The level of [key] (a [Level.key]); an unknown word is [Level.LOW]. */
    fun byKey(key: String?): Level = Level.entries.firstOrNull { it.key == key } ?: Level.LOW

    /** The levels of [shown] in the key's order: the area being drawn first, then finest to coarsest, then a map not used. */
    fun keyOrder(shown: Collection<Level>): List<Level> =
        shown.distinct().sortedBy { if (it == Level.DRAFT) -1 else if (it == Level.OFF) 99 else 10 - it.order }

    /** The label on an outline: the level's name, and the map's ([name]) after it where it fits (the long label). */
    fun longLabel(level: Level, name: String?): String = if (name.isNullOrBlank()) level.label else "${level.label} · $name"

    /** WCAG's contrast of two `#rrggbb` colours, (L1 + 0.05) / (L2 + 0.05). */
    fun contrast(a: String, b: String): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun luminance(hex: String): Double {
        val v = hex.removePrefix("#").toInt(16)
        fun ch(c: Int): Double = (c / 255.0).let { if (it <= 0.03928) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
        return 0.2126 * ch(v shr 16 and 0xff) + 0.7152 * ch(v shr 8 and 0xff) + 0.0722 * ch(v and 0xff)
    }
}
