package io.github.jasondorjeshort.dystopo.app.readout

import io.github.jasondorjeshort.dystopo.app.help.ReasonText
import io.github.jasondorjeshort.dystopo.geo.CoordinateFormat
import io.github.jasondorjeshort.dystopo.readout.ReadoutEntry
import io.github.jasondorjeshort.dystopo.readout.ReadoutReason
import io.github.jasondorjeshort.dystopo.readout.ReadoutReasons
import io.github.jasondorjeshort.dystopo.readout.ReadoutText
import io.github.jasondorjeshort.dystopo.readout.ValueState
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import io.github.jasondorjeshort.dystopo.render.UnitFormat

/**
 * T210: one line of the glance view ([GlanceView]): a small [caption] over the big [value] with its [unit], the
 * elevation's [trend] arrow and the [plusMinus] when asked for, and the other unit system's [other] text in both-units
 * mode. [kind] is the readout entry's code (`elevation`, `climb`, `speed`, `course`; T257 `pressure`, `accuracy`,
 * `fixAge`) or [DEADLINE]. T257 (T238's note): a climb past four digits reads in thousands ("↑12k"), so a replayed
 * jump's figure no longer outgrows the template the type is fitted on. The templates are the
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
    /** T218: a second widest caption and value (with no unit) the line must also hold: the fit takes the wider of each. */
    val captionAlso: String? = null,
    val valueAlso: String? = null,
    /**
     * T363: the line under the value saying why it is not a plain figure and what would end it ([StripItem.note]), and
     * its reason's code; null for a plain figure. The fit reserves its row always, so the layout holds still.
     */
    val reason: String? = null,
    val reasonCode: String? = null,
    /**
     * T384: the value's type size as a share of the figures' ([GlanceFit]): 1 for a figure; the position line's
     * [GlanceText.POSITION_SHARE], drawn under the figures across the whole width, with no caption drawn (its [caption]
     * is spoken), never counted in the columns, its size held to the width its template needs and to the smallest size.
     */
    val share: Float = 1f,
) {
    /** T384: a line under the figures across the whole width (the position line). */
    val wide: Boolean get() = share < 1f

    /** "1650 m", "↑320 m/h ± 40", "1 h 20 min": the value as one text. */
    val full: String get() = (if (unit.isEmpty()) value else "$value $unit") + (trend?.let { " $it" } ?: "") + (plusMinus?.let { " $it" } ?: "")

    /** What a screen reader says for the line: "elevation 1650 m"; T363: with its reason's sentence after it. */
    val spoken: String get() = "$caption $full" + (reason?.let { ". $it" } ?: "")

    fun json(): Map<String, Any?> = linkedMapOf(
        "kind" to kind, "caption" to caption, "value" to value, "unit" to unit, "trend" to trend, "plusMinus" to plusMinus,
        "other" to other, "full" to full, "dimmed" to dimmed, "colour" to colour,
        "reason" to reason, "reasonCode" to reasonCode, // T363
    )

    companion object {
        const val DEADLINE = "deadline"
        const val NORMAL = "normal"
    }
}

/**
 * T210: the glance view's words (pure, `GlanceTextTest`). The figures are the readout bar's own entries as it is set
 * ([StripText.items]: each entry's on/off setting, in the fixed order elevation, climb, speed, course; T257: then the
 * pressure, the GPS accuracy and the fix age, the course as "SSE 163°" whatever the bar's room), so the view and
 * the bar never disagree and there is no second list of figures to keep (the survey of 2026-11-02, ADR 0044); while the
 * turn-around clock runs (navigating, or a recording's way back) the time to it comes first ([deadline]).
 */
object GlanceText {
    /**
     * The widest captions the deadline line shows, and its widest values: "88 h 88" with "min", and T218 the clock's
     * longest form ("12:59 PM"), whichever the phone's setting is, so the line never clips and the type does not change
     * when the state does.
     */
    const val DEADLINE_CAPTION_TEMPLATE = "turn back now, out by"
    val DEADLINE_CAPTION_CLOCK_TEMPLATE = "turn back by ${io.github.jasondorjeshort.dystopo.nav.ClockText.LONGEST}"
    const val DEADLINE_VALUE_TEMPLATE = "88 h 88"
    const val DEADLINE_CLOCK_TEMPLATE = io.github.jasondorjeshort.dystopo.nav.ClockText.LONGEST

    /**
     * The lines in order: the deadline when the clock runs, then the bar's entries; T384: while [gps] searches, "no fix"
     * reads "searching" and the GPS area says how it goes ([gpsArea]); with [positionLine], the position line last
     * ([position]: the readout's last fix, in [format], distances in [units]).
     */
    fun lines(
        items: List<StripItem>, deadline: GlanceLine? = null, gps: Gps? = null, position: Position? = null, positionLine: Boolean = false,
        units: ContourUnits = ContourUnits.METERS, format: CoordinateFormat = CoordinateFormat.DEFAULT,
    ): List<GlanceLine> {
        val searching = gps?.searching == true
        val read = items.map { of(it, searching) }
        val figures = if (gps != null && searching) gpsArea(read, gps) else read
        return listOfNotNull(deadline) + figures + listOfNotNull(if (positionLine) position(position, gps, units, format) else null)
    }

    /** One bar item as a glance line, with its templates; T384: "no fix" read as "searching" while the GPS is [searching]. */
    fun of(it: StripItem, searching: Boolean = false): GlanceLine {
        val why = ReadoutReasons.searching(it.reason, searching)
        val figure = it.state == StripItem.STATE_VALUE || it.state == StripItem.STATE_ZERO
        val note = if (why == it.reason) it.note else ReasonText.sentence(it.entry, if (figure) ValueState.SHOWN else ValueState.DASH, why)
        return GlanceLine(
            kind = it.entry.code, caption = it.caption, value = it.value, unit = it.unit, trend = it.trend, plusMinus = it.plusMinus,
            other = it.other, dimmed = it.dimmed, captionTemplate = it.captionTemplate, valueTemplate = it.template.text,
            unitTemplate = it.template.unit, trendSlot = it.trendSlot, plusMinusTemplate = it.template.plusMinus, otherTemplate = it.otherTemplate,
            reason = note, reasonCode = why?.code, // T363
        )
    }

    // ---- T384: the GPS while it searches, and the position ----

    /**
     * T384: the phone's GPS receiver as the glance view reads it: [searching] while no GPS fix has come for the readout's
     * gap ([io.github.jasondorjeshort.dystopo.readout.ReadoutTuning.GAP_S]) since the device's fixes started; [sinceMs] how
     * long this search has run (from the last GPS fix, or from the start of the device's fixes when none came since; null
     * when not known); [seen] and [used] the satellites heard and used in a fix, [bestCn0] the strongest signal heard
     * (dB-Hz), from the status the view holds while open ([io.github.jasondorjeshort.dystopo.app.gnss.Gnss]).
     */
    data class Gps(val searching: Boolean, val sinceMs: Long?, val seen: Int, val used: Int, val bestCn0: Double?) {
        fun json(): Map<String, Any?> = linkedMapOf(
            "searching" to searching, "sinceS" to sinceMs?.let { it / 1000 }, "seen" to seen, "used" to used,
            "bestCn0" to bestCn0?.let { Math.round(it * 10) / 10.0 },
        )
    }

    /**
     * T384: the last fix the readout took ([LiveReadout.lastFix]): where, its [provider] (`gps`, `network`, `passive`, or
     * `debug` for an injected one), its age and accuracy. A network or passive fix is not GPS ([network]).
     */
    data class Position(val lat: Double, val lon: Double, val provider: String, val ageMs: Long, val accuracyM: Double?) {
        val network: Boolean get() = provider == "network" || provider == "passive"
        val source: String get() = if (network) "network" else "gps"
    }

    /** The position line's kind. */
    const val POSITION = "position"

    /** The position's type size as a share of the figures' (held to the width and to the smallest size, [GlanceFit.wideValuePx]). */
    const val POSITION_SHARE = 0.6f

    /** A signal this strong or more reads "good" ([io.github.jasondorjeshort.dystopo.gnss.GnssHealth.HEALTHY_T4], the GPS view's own mark, dB-Hz). */
    val GOOD_CN0: Double get() = io.github.jasondorjeshort.dystopo.gnss.GnssHealth.HEALTHY_T4

    /**
     * T384: the GPS area while it searches: the accuracy's and the fix age's reason rows, else (an entry switched off) the
     * first other lines that read "searching", two rows at most: the first says for how long and the satellites
     * ([searchingSentence]), the second the best signal ([signalSentence]). Rows T389 reserved, so nothing moves.
     */
    fun gpsArea(lines: List<GlanceLine>, gps: Gps): List<GlanceLine> {
        val gpsKinds = listOf(ReadoutEntry.ACCURACY.code, ReadoutEntry.FIX_AGE.code)
        val area = (lines.filter { it.kind in gpsKinds } + lines.filter { it.kind !in gpsKinds && it.reasonCode == ReadoutReason.SEARCHING.code })
            .map { it.kind }.distinct().take(2)
        val texts = listOf(searchingSentence(gps), signalSentence(gps))
        return lines.map { l -> area.indexOf(l.kind).takeIf { it >= 0 }?.let { i -> l.copy(reason = texts[i], reasonCode = ReadoutReason.SEARCHING.code) } ?: l }
    }

    /** "GPS searching for 4 min 20 s: 11 seen, none used." (ADR 0031: ours; no surveyed source gave words for it). */
    fun searchingSentence(g: Gps): String {
        val time = g.sinceMs?.let { " for ${duration(it)}" } ?: ""
        val sats = when {
            g.seen == 0 -> "no satellites heard yet"
            g.used == 0 -> "${g.seen} seen, none used"
            else -> "${g.seen} seen, ${g.used} used"
        }
        return "GPS searching$time: $sats."
    }

    /** "Best signal 23 dB-Hz: weak (good is 35 or more)." */
    fun signalSentence(g: Gps): String {
        val c = g.bestCn0?.takeIf { g.seen > 0 && it > 0 } ?: return "Best signal: none heard yet."
        val n = Math.round(c)
        val good = Math.round(GOOD_CN0)
        return if (c >= GOOD_CN0) "Best signal $n dB-Hz: good ($good or more)." else "Best signal $n dB-Hz: weak (good is $good or more)."
    }

    /** "20 s", "4 min 20 s", "1 h 05 min": how long a search has run. */
    fun duration(ms: Long): String {
        val s = ms.coerceAtLeast(0L) / 1000
        return when {
            s < 60 -> "$s s"
            s < 3600 -> "${s / 60} min ${s % 60} s"
            else -> "${s / 3600} h ${(s % 3600 / 60).toString().padStart(2, '0')} min"
        }
    }

    /**
     * T384: the position line: [p]'s latitude and longitude in [format] (a dash before any fix), and under it where it
     * came from: "From GPS, 3 s ago.", or a network position marked "Not GPS" with its accuracy and age, or why there is
     * none yet. Its template is the format's widest text, so the line's size holds as the figures change.
     */
    fun position(p: Position?, gps: Gps?, units: ContourUnits, format: CoordinateFormat = CoordinateFormat.DEFAULT): GlanceLine {
        val reason = when {
            p == null -> if (gps?.searching == true) "No position yet: GPS is searching." else "No position yet: shown with the first fix."
            p.network -> "Not GPS: network position" + (p.accuracyM?.let { " ± " + UnitFormat.distance(it, units) } ?: "") + ", ${ReasonText.span(p.ageMs)} ago."
            else -> "From GPS, ${ReasonText.span(p.ageMs)} ago."
        }
        return GlanceLine(
            kind = POSITION, caption = POSITION, value = p?.let { format.format(it.lat, it.lon) } ?: ReadoutText.DASH, unit = "",
            captionTemplate = "", valueTemplate = positionTemplate(format), share = POSITION_SHARE, reason = reason,
        )
    }

    /** The widest text [format] gives: every digit an 8, the longest degrees. */
    fun positionTemplate(format: CoordinateFormat): String = format.format(-88.8888888, -178.8888888).map { if (it.isDigit()) '8' else it }.joinToString("")

    /** `GET /readout/glance` › `position`: the last fix as the line shows it ([line]). */
    fun positionJson(p: Position?, line: GlanceLine?): Map<String, Any?> = if (p == null) linkedMapOf("source" to null, "text" to line?.value, "line" to line?.reason) else linkedMapOf(
        "lat" to Math.round(p.lat * 100_000) / 100_000.0, "lon" to Math.round(p.lon * 100_000) / 100_000.0,
        "source" to p.source, "provider" to p.provider, "ageS" to p.ageMs / 1000, "accuracyM" to p.accuracyM?.let { a -> Math.round(a * 10) / 10.0 },
        "text" to line?.value, "line" to line?.reason,
    )

    /**
     * T363: the glance view's notes under the figures: [leftOut] what the bar leaves out for room (or the bar hidden for
     * room), and [recent] what went wrong in the last minute, newest first (the owner, 2026-11-23: "If there's a gps drop
     * or etc in the last 60 seconds we can trivially fit that on the screen"), even when every value is normal again;
     * empty when nothing did.
     */
    data class Notes(val leftOut: String?, val recent: List<String>) {
        val empty: Boolean get() = leftOut == null && recent.isEmpty()

        fun json(): Map<String, Any?> = linkedMapOf("leftOut" to leftOut, "recentHeading" to if (recent.isEmpty()) null else ReasonText.RECENT_HEADING, "recent" to recent)
    }

    /**
     * The notes from [r] (its record of faults, read at its elapsed time), [pinEnded] (when the known height's mark went
     * and why, [LiveReadout.pinEnded]), the bar's [dropped] entries and whether it is [hiddenForRoom]; distances in [units].
     */
    fun notes(r: io.github.jasondorjeshort.dystopo.readout.Readout?, pinEnded: Pair<Long, String?>?, dropped: List<String>, hiddenForRoom: Boolean,
              units: io.github.jasondorjeshort.dystopo.render.ContourUnits, max: Int = MAX_RECENT): Notes {
        val recent = ArrayList<String>()
        if (r != null) {
            val pin = pinEnded?.takeIf { r.elapsedMs - it.first in 0..ReasonText.RECENT_MS }?.let { ReasonText.pinEnd(it.second, r.elapsedMs - it.first) }
            recent += listOfNotNull(pin) + ReasonText.recent(r.recent, r.elapsedMs, units)
        }
        return Notes(ReasonText.leftOut(dropped, hiddenForRoom), recent.take(max))
    }

    /** At most this many last-minute lines. */
    const val MAX_RECENT = 4

    /**
     * T384: on its side, at most this many: the notes' reserved rows give way first, so the position line costs the
     * figures nothing where the height is shortest (T389's verifier read the figures at 55 px there at font 2.0).
     */
    const val MAX_RECENT_SIDE = 2

    /**
     * The turn-around clock as a line, from its reading ([state] a `TurnState` key, [colour], [turnBackMs], [arrivalMs])
     * at [nowMs]; [clock] formats a time as the navigation card does ("14:10", "2:10 PM"):
     * - before the time (`ahead`, `margin`): "turn back by 14:10" over the time left, "1 h 20 min" (amber in the margin);
     * - at it (`turn`, red): "turn back now, out by" over "18:52";
     * - on the way out (`returning`, `arrival`, `noDeadline`): "out by" over "18:52".
     */
    fun deadline(state: String, colour: String, turnBackMs: Long?, arrivalMs: Long, nowMs: Long, clock: (Long) -> String): GlanceLine {
        val base = GlanceLine(
            kind = GlanceLine.DEADLINE, caption = "", value = "", unit = "", colour = colour,
            captionTemplate = DEADLINE_CAPTION_TEMPLATE, valueTemplate = DEADLINE_VALUE_TEMPLATE, unitTemplate = "min",
            captionAlso = DEADLINE_CAPTION_CLOCK_TEMPLATE, valueAlso = DEADLINE_CLOCK_TEMPLATE,
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

    /** T363: a reason's line under the value, at most this share of the value's size (smaller when the sentence needs it, never under the fit's smallest size, which its row reserves). */
    const val REASON = 0.22f

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

    /**
     * T384: a wide line's value size at the figures' size [v]: its share of [v], held to what its template needs to fit
     * [widthPx] and never under [minPx] (the smallest legible size).
     */
    fun wideValuePx(l: GlanceLine, v: Float, widthPx: Float, m: Measure, minPx: Float): Float {
        val atV = m.size(l.valueTemplate, v, true).first
        val toWidth = if (atV > 0f) widthPx * v / atV else Float.MAX_VALUE
        return maxOf(minPx, minOf(v * l.share, toWidth))
    }

    /** A line's width and height at value size [v]; T384: a wide line's value at [valuePx], with no caption. */
    fun lineSize(l: GlanceLine, v: Float, m: Measure, reasonMinPx: Float = 0f, valuePx: Float = v): Pair<Float, Float> {
        val cap = if (l.wide) 0f to 0f else m.size(l.captionTemplate, v * CAPTION, false)
        val capAlso = l.captionAlso?.let { m.size(it, v * CAPTION, false) }
        val value = m.size(l.valueTemplate, valuePx, true)
        val valueAlso = l.valueAlso?.let { m.size(it, valuePx, true) }
        var w = value.first
        if (l.unitTemplate.isNotEmpty()) w += m.size(" " + l.unitTemplate, valuePx * UNIT, false).first
        if (l.trendSlot) w += TREND_ARROWS.maxOf { m.size(" $it", valuePx, true).first }
        l.plusMinusTemplate?.let { w += m.size(" $it", valuePx * UNIT, false).first }
        val other = l.otherTemplate?.let { m.size(it, v * OTHER, false) }
        // T363: a reason's row, its height only: its type shrinks to the column's width when the sentence needs it.
        val reason = m.size("Ag", maxOf(v * REASON, reasonMinPx), false).second // always reserved: the layout never moves as a reason comes and goes
        return maxOf(maxOf(w, valueAlso?.first ?: 0f), maxOf(cap.first, capAlso?.first ?: 0f), other?.first ?: 0f) to
            (maxOf(value.second, valueAlso?.second ?: 0f) + cap.second + (other?.second ?: 0f) + reason)
    }

    /**
     * Whether [lines] fit [widthPx] by [heightPx] at value size [v] in [cols] columns. T384: the wide lines (the position)
     * go under the figures across the whole width, a gap before each.
     */
    fun fitsAt(lines: List<GlanceLine>, widthPx: Float, heightPx: Float, v: Float, cols: Int, m: Measure, reasonMinPx: Float = 0f): Boolean {
        val colW = (widthPx - (cols - 1) * v * GAP) / cols
        val sizes = lines.filter { !it.wide }.map { lineSize(it, v, m, reasonMinPx) }
        if (sizes.any { it.first > colW }) return false
        val wide = lines.filter { it.wide }.map { lineSize(it, v, m, reasonMinPx, wideValuePx(it, v, widthPx, m, reasonMinPx)) }
        if (wide.any { it.first > widthPx }) return false
        val rows = sizes.chunked(cols).map { r -> r.maxOf { it.second } } + wide.map { it.second }
        return rows.sum() + (rows.size - 1).coerceAtLeast(0) * v * GAP <= heightPx
    }

    /** The largest value size between [minPx] and [maxPx] at which [lines] fit [widthPx] by [heightPx]. */
    fun fit(lines: List<GlanceLine>, widthPx: Float, heightPx: Float, minPx: Float, maxPx: Float, m: Measure): Fit {
        val cols = columns(lines.count { !it.wide }, widthPx, heightPx)
        if (lines.isEmpty() || widthPx <= 0f || heightPx <= 0f) return Fit(minPx, cols, lines.isEmpty())
        if (!fitsAt(lines, widthPx, heightPx, minPx, cols, m, minPx)) return Fit(minPx, cols, false)
        if (fitsAt(lines, widthPx, heightPx, maxPx, cols, m, minPx)) return Fit(maxPx, cols, true)
        var lo = minPx
        var hi = maxPx
        repeat(20) {
            val mid = (lo + hi) / 2
            if (fitsAt(lines, widthPx, heightPx, mid, cols, m, minPx)) lo = mid else hi = mid
        }
        return Fit(lo, cols, true)
    }

    /** The trend arrows the elevation can carry (the bar's [io.github.jasondorjeshort.dystopo.readout.TrendBand.ARROWS]). */
    val TREND_ARROWS: List<String> get() = io.github.jasondorjeshort.dystopo.readout.TrendBand.ARROWS
}
