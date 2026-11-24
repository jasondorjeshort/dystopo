package io.github.jasondorjeshort.dystopo.readout

/**
 * T362: when the readout's values are dimmed for the fix stream, on elapsed time (never the fixes' wall-clock times, which
 * a clock change moves). Pure; the estimator feeds it each fix's elapsed time ([onFix]) and asks it at a read ([reason]).
 *
 * The dim enters once no fix came for `gapMs` (`no fix`) and leaves only on the second fix of a run in which each fix came
 * within `gapMs` of the one before: the first fix after a gap keeps it (`resuming`), so fixes in bursts (weak GPS, the
 * network and GPS providers alternating) give one steady dim instead of a blink per gap. The first fix after a start is
 * not after a gap, so a steady stream is never dimmed. No fade and no dwell.
 */
class DimLatch {
    /** The elapsed time of the last fix, null before the first. */
    var lastFixElapsedMs: Long? = null
        private set

    /** A fix came after a gap and no second fix of its run has come yet. */
    var latched: Boolean = false
        private set

    /** One fix at [elapsedMs]; [gapMs] the profile's gap. Returns the interval since the last fix (ms), null for the first. */
    fun onFix(elapsedMs: Long, gapMs: Long): Long? {
        val prev = lastFixElapsedMs
        lastFixElapsedMs = elapsedMs
        if (prev == null) return null
        val dt = elapsedMs - prev
        latched = dt > gapMs // a negative step (a test's or a replay's made-up time going back) counts as steady
        return dt
    }

    /** The current gap at [elapsedNowMs] (ms past the last fix while over [gapMs]), else null. */
    fun gapMs(elapsedNowMs: Long, gapMs: Long): Long? =
        lastFixElapsedMs?.let { elapsedNowMs - it }?.takeIf { it > gapMs }

    /** Why the values are dimmed at [elapsedNowMs]: [NO_FIX] in a gap, [RESUMING] after one until a second fix, else null. */
    fun reason(elapsedNowMs: Long, gapMs: Long): ReadoutReason? = when {
        lastFixElapsedMs == null -> NO_FIX
        gapMs(elapsedNowMs, gapMs) != null -> NO_FIX
        latched -> RESUMING
        else -> null
    }

    companion object {
        /** No fix for the gap (or none yet). T363: the readout's closed set of reasons. */
        val NO_FIX = ReadoutReason.NO_FIX

        /** A fix came after a gap; the dim waits for a second within the gap of it. */
        val RESUMING = ReadoutReason.RESUMING
    }
}

/**
 * T362: one thing that went wrong lately ([RecentFaults]), for the glance view (T363) and `GET /readout` › `readout.recent`.
 * [kind] is a code: [GAP] (no fix for [lengthMs], from [atElapsedMs]), [RESTART] (the position or the height started
 * afresh, [cause] `jump`, `clock` or `activity`), [BAROMETER_SILENT] (no pressure sample for [lengthMs] at the time of a
 * fix), [LATE_FIX] (a fix not newer than the last, skipped). [ongoing]: still going at the read (a gap only). [detail]:
 * the jump's distance (m) or how far the clock went back (s).
 */
data class ReadoutFault(
    val kind: String,
    val atElapsedMs: Long,
    val lengthMs: Long? = null,
    val cause: String? = null,
    val detail: Double? = null,
    val ongoing: Boolean = false,
) {
    fun json(elapsedNowMs: Long): Map<String, Any?> = linkedMapOf(
        "kind" to kind, "cause" to cause, "agoS" to r3((elapsedNowMs - atElapsedMs) / 1000.0),
        "lengthS" to lengthMs?.let { r3(it / 1000.0) }, "detail" to detail?.let { r3(it) }, "ongoing" to ongoing,
    )

    companion object {
        const val GAP = "gap"
        const val RESTART = "restart"
        const val BAROMETER_SILENT = "barometer silent"
        const val LATE_FIX = "late fix"

        const val CAUSE_JUMP = "jump"
        const val CAUSE_CLOCK = "clock"
        const val CAUSE_ACTIVITY = "activity"
    }
}

/**
 * T362: the short record of what went wrong lately, each with its elapsed time: the owner, 2026-11-23, "If there's a gps
 * drop or etc in the last 60 seconds we can trivially fit that on the screen." Kept [ReadoutTuning.FAULTS_KEEP_MS] and at
 * most [ReadoutTuning.FAULTS_MAX] entries; the glance view chooses what it shows. Pure.
 */
class RecentFaults {
    private val list = ArrayDeque<ReadoutFault>()

    fun add(f: ReadoutFault) {
        list.addLast(f)
        while (list.size > ReadoutTuning.FAULTS_MAX) list.removeFirst()
    }

    /** The record at [elapsedNowMs], oldest first, with [ongoing] (a gap still going) added last. */
    fun at(elapsedNowMs: Long, ongoing: ReadoutFault? = null): List<ReadoutFault> =
        list.filter { elapsedNowMs - (it.atElapsedMs + (it.lengthMs ?: 0)) <= ReadoutTuning.FAULTS_KEEP_MS } + listOfNotNull(ongoing)

    fun clear() = list.clear()
}
