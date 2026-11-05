package io.github.jasondorjeshort.dystopo.app

import android.text.format.DateFormat
import io.github.jasondorjeshort.dystopo.nav.ClockStyle
import io.github.jasondorjeshort.dystopo.nav.ClockText
import java.util.Calendar
import java.util.TimeZone

/**
 * T218: every wall-clock time the phone shows or says, through `shared`'s [ClockText] in the phone's own 12/24-hour form
 * (`DateFormat.is24HourFormat`, read at each use, so a changed setting shows at the next reading). No setting of the
 * app's own: Android's documentation says to follow the system and no surveyed app does otherwise (ADR 0044). Not for a
 * typed deadline's stored form or a track's default name, which keep their 24-hour form.
 */
object ClockFormat {
    /** The phone's setting now. */
    fun style(): ClockStyle = if (DateFormat.is24HourFormat(DystopoApp.instance)) ClockStyle.H24 else ClockStyle.H12

    /** The time [ms] in [zone] (the phone's by default) in [style] (the phone's by default). */
    fun text(ms: Long, zone: TimeZone = TimeZone.getDefault(), style: ClockStyle = style()): String {
        val c = Calendar.getInstance(zone).apply { timeInMillis = ms }
        return ClockText.of(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), style)
    }
}
