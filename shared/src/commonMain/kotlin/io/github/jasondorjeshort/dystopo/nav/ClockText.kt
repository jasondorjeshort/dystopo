package io.github.jasondorjeshort.dystopo.nav

/** T218: the clock's two forms; the phone's 12/24-hour setting picks one (`ClockFormat` on Android, `--clock` in devtool). */
enum class ClockStyle(val key: String) {
    H12("12"),
    H24("24");

    companion object {
        fun parse(raw: String?): ClockStyle? = entries.firstOrNull { it.key == raw?.trim() }
    }
}

/**
 * T218: a wall-clock time as every card, line and spoken sentence shows it (pure; the platform gives the hour and the
 * minute and picks the style, the survey `clock-times-and-deadlines.md`: Android's documentation says to follow the
 * system's preference, and no app found offers its own, so there is no setting, ADR 0044). No seconds on a clock time.
 *
 * - 12-hour: "7:05 PM", "12:00 AM": no zero on the hour, the marker in capitals after a no-break space ([SPACE]), so a
 *   line that wraps never leaves "PM" alone on the next row.
 * - 24-hour: "19:05", "09:05", "00:00": the hour padded, the short form Android's own pattern gives (CLDR's `Hm`, `HH:mm`,
 *   as `DateFormat.getTimeFormat` prints for the readout's and the recorder's clocks).
 *
 * The stored form of a typed deadline ([DeadlineSetting.hhmm]) and a track's default name are not clock text: they keep
 * their 24-hour form whatever the phone's setting.
 */
object ClockText {
    /** Between the time and its marker: a no-break space. */
    const val SPACE = " "

    /** The widest time, for a line that reserves room for any ("12:59 PM"). */
    const val LONGEST = "12:59${SPACE}PM"

    /** [hour] 0 to 23 and [minute] 0 to 59 in [style]. */
    fun of(hour: Int, minute: Int, style: ClockStyle): String {
        require(hour in 0..23 && minute in 0..59) { "a time of day is 00:00 to 23:59" }
        val mm = minute.toString().padStart(2, '0')
        return when (style) {
            ClockStyle.H24 -> "${hour.toString().padStart(2, '0')}:$mm"
            ClockStyle.H12 -> "${if (hour % 12 == 0) 12 else hour % 12}:$mm$SPACE${if (hour < 12) "AM" else "PM"}"
        }
    }
}
