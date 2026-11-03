package io.github.jasondorjeshort.dystopo.app.readout

/**
 * T210: one line of the glance view ([GlanceView]): a small [caption] over the big [value] with its [unit], the
 * elevation's [trend] arrow and the [plusMinus] when asked for, and the other unit system's [other] text in both-units
 * mode. [kind] is the readout entry's code (`elevation`, `climb`, `speed`, `course`) or [DEADLINE]. The templates are the
 * widest texts the line can show (the bar's own, [StripText.template]), so the type size is fitted once and does not
 * change as the figures do. [colour] is the turn-around clock's (`normal`, `amber`, `red`).
 */
data class GlanceLine(
    val kind: String,
    val caption: String,
    val value: String,
    val unit: String,
    val trend: String? = null,
    val plusMinus: String? = null,
    val other: String? = null,
    val dimmed: Boolean = false,
    val colour: String = NORMAL,
    val captionTemplate: String = caption,
    val valueTemplate: String = value,
    val unitTemplate: String = unit,
    val trendSlot: Boolean = false,
    val plusMinusTemplate: String? = plusMinus,
    val otherTemplate: String? = other,
) {
    /** "1650 m", "↑320 m/h ± 40", "1 h 20 min": the value as one text. */
    val full: String get() = (if (unit.isEmpty()) value else "$value $unit") + (trend?.let { " $it" } ?: "") + (plusMinus?.let { " $it" } ?: "")

    /** What a screen reader says for the line: "elevation 1650 m". */
    val spoken: String get() = "$caption $full"

    fun json(): Map<String, Any?> = linkedMapOf(
        "kind" to kind, "caption" to caption, "value" to value, "unit" to unit, "trend" to trend, "plusMinus" to plusMinus,
        "other" to other, "full" to full, "dimmed" to dimmed, "colour" to colour,
    )

    companion object {
        const val DEADLINE = "deadline"
        const val NORMAL = "normal"
    }
}

/**
 * T210: the glance view's words (pure, `GlanceTextTest`). The figures are the readout bar's own entries as it is set
 * ([StripText.items]: each entry's on/off setting, in the fixed order elevation, climb, speed, course), so the view and
 * the bar never disagree and there is no second list of figures to keep (the survey of 2026-11-02, ADR 0044); while the
 * turn-around clock runs (navigating, or a recording's way back) the time to it comes first ([deadline]).
 */
object GlanceText {
    /** The widest caption the deadline line shows, and its widest value ("88 h 88" with "min"). */
    const val DEADLINE_CAPTION_TEMPLATE = "turn back now, out by"
    const val DEADLINE_VALUE_TEMPLATE = "88 h 88"

    /** The lines in order: the deadline when the clock runs, then the bar's entries. */
    fun lines(items: List<StripItem>, deadline: GlanceLine? = null): List<GlanceLine> =
        listOfNotNull(deadline) + items.map(::of)

    /** One bar item as a glance line, with its templates. */
    fun of(it: StripItem): GlanceLine = GlanceLine(
        kind = it.entry.code, caption = it.caption, value = it.value, unit = it.unit, trend = it.trend, plusMinus = it.plusMinus,
        other = it.other, dimmed = it.dimmed, captionTemplate = it.captionTemplate, valueTemplate = it.template.text,
        unitTemplate = it.template.unit, trendSlot = it.trendSlot, plusMinusTemplate = it.template.plusMinus, otherTemplate = it.otherTemplate,
    )

    /**
     * The turn-around clock as a line, from its reading ([state] a `TurnState` key, [colour], [turnBackMs], [arrivalMs])
     * at [nowMs]; [clock] formats a time as the navigation card does ("14:10"):
     * - before the time (`ahead`, `margin`): "turn back by 14:10" over the time left, "1 h 20 min" (amber in the margin);
     * - at it (`turn`, red): "turn back now, out by" over "18:52";
     * - on the way out (`returning`, `arrival`, `noDeadline`): "out by" over "18:52".
     */
    fun deadline(state: String, colour: String, turnBackMs: Long?, arrivalMs: Long, nowMs: Long, clock: (Long) -> String): GlanceLine {
        val base = GlanceLine(
            kind = GlanceLine.DEADLINE, caption = "", value = "", unit = "", colour = colour,
            captionTemplate = DEADLINE_CAPTION_TEMPLATE, valueTemplate = DEADLINE_VALUE_TEMPLATE, unitTemplate = "min",
        )
        return when {
            (state == "ahead" || state == "margin") && turnBackMs != null -> {
                val (v, u) = left(turnBackMs - nowMs)
                base.copy(caption = "turn back by ${clock(turnBackMs)}", value = v, unit = u)
            }
            state == "turn" -> base.copy(caption = "turn back now, out by", value = clock(arrivalMs))
            else -> base.copy(caption = "out by", value = clock(arrivalMs))
        }
    }

    /** The time left to the minute, as value and unit: ("45", "min"), ("1 h 05", "min"), ("0", "min") once it has passed. */
    fun left(ms: Long): Pair<String, String> {
        val m = (ms.coerceAtLeast(0L) / 60_000L)
        return (if (m < 60) "$m" else "${m / 60} h ${(m % 60).toString().padStart(2, '0')}") to "min"
    }

    /** The view's description for a screen reader: every line, in order. */
    fun description(lines: List<GlanceLine>): String = lines.joinToString(" · ") { it.spoken }
}

/**
 * T210: the largest type that fits (pure, `GlanceTextTest`). Sizes are in pixels, so the user's font scale changes
 * nothing (the type is already as large as the room allows; Android's non-linear font scaling would break a fit in sp).
 * Every line has the same size: its caption at [CAPTION] of the value's size, its unit and ± at [UNIT], its other-units
 * text at [OTHER], a gap of [GAP] between lines; one column, or two when the area is wider than tall and has three lines
 * or more. The size is the largest (a binary search between [minPx] and [maxPx]) at which every line's templates fit the
 * column's width and the lines fit the height; it is fitted on the templates, so it holds while the figures change.
 * [Measure] gives a text's width and height in pixels at a type size in pixels (bold for the value): `GlanceView` passes
 * Compose's text measurer, the test a fake.
 */
object GlanceFit {
    const val CAPTION = 0.3f
    const val UNIT = 0.5f
    const val OTHER = 0.3f
    const val GAP = 0.12f

    /** One fit: the value's type size in px, the columns, and whether even the smallest size fits ([fits] false: the lines clip). */
    data class Fit(val valuePx: Float, val columns: Int, val fits: Boolean) {
        val captionPx get() = valuePx * CAPTION
        val unitPx get() = valuePx * UNIT
        val otherPx get() = valuePx * OTHER
        val gapPx get() = valuePx * GAP
    }

    fun interface Measure {
        /** [text]'s width and height in px at type size [px]; [bold] for the value. */
        fun size(text: String, px: Float, bold: Boolean): Pair<Float, Float>
    }

    fun columns(n: Int, widthPx: Float, heightPx: Float): Int = if (n >= 3 && widthPx > heightPx) 2 else 1

    /** A line's width and height at value size [v]. */
    fun lineSize(l: GlanceLine, v: Float, m: Measure): Pair<Float, Float> {
        val cap = m.size(l.captionTemplate, v * CAPTION, false)
        val value = m.size(l.valueTemplate, v, true)
        var w = value.first
        if (l.unitTemplate.isNotEmpty()) w += m.size(" " + l.unitTemplate, v * UNIT, false).first
        if (l.trendSlot) w += TREND_ARROWS.maxOf { m.size(" $it", v, true).first }
        l.plusMinusTemplate?.let { w += m.size(" $it", v * UNIT, false).first }
        val other = l.otherTemplate?.let { m.size(it, v * OTHER, false) }
        return maxOf(w, cap.first, other?.first ?: 0f) to (value.second + cap.second + (other?.second ?: 0f))
    }

    /** Whether [lines] fit [widthPx] by [heightPx] at value size [v] in [cols] columns. */
    fun fitsAt(lines: List<GlanceLine>, widthPx: Float, heightPx: Float, v: Float, cols: Int, m: Measure): Boolean {
        val colW = (widthPx - (cols - 1) * v * GAP) / cols
        val sizes = lines.map { lineSize(it, v, m) }
        if (sizes.any { it.first > colW }) return false
        val rows = sizes.chunked(cols).map { r -> r.maxOf { it.second } }
        return rows.sum() + (rows.size - 1).coerceAtLeast(0) * v * GAP <= heightPx
    }

    /** The largest value size between [minPx] and [maxPx] at which [lines] fit [widthPx] by [heightPx]. */
    fun fit(lines: List<GlanceLine>, widthPx: Float, heightPx: Float, minPx: Float, maxPx: Float, m: Measure): Fit {
        val cols = columns(lines.size, widthPx, heightPx)
        if (lines.isEmpty() || widthPx <= 0f || heightPx <= 0f) return Fit(minPx, cols, lines.isEmpty())
        if (!fitsAt(lines, widthPx, heightPx, minPx, cols, m)) return Fit(minPx, cols, false)
        if (fitsAt(lines, widthPx, heightPx, maxPx, cols, m)) return Fit(maxPx, cols, true)
        var lo = minPx
        var hi = maxPx
        repeat(20) {
            val mid = (lo + hi) / 2
            if (fitsAt(lines, widthPx, heightPx, mid, cols, m)) lo = mid else hi = mid
        }
        return Fit(lo, cols, true)
    }

    /** The trend arrows the elevation can carry (the bar's [io.github.jasondorjeshort.dystopo.readout.TrendBand.ARROWS]). */
    val TREND_ARROWS: List<String> get() = io.github.jasondorjeshort.dystopo.readout.TrendBand.ARROWS
}
