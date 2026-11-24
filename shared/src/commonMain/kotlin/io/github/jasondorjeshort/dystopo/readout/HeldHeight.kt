package io.github.jasondorjeshort.dystopo.readout

/**
 * T362: the last height before the estimator's height started afresh (a clock going back: the filter cannot step back in
 * time), shown dimmed with reason [REASON] until the new filter has a height or [ReadoutTuning.HELD_HEIGHT_MS] of elapsed
 * time pass; then the elevation reads "no height yet". Pure.
 */
data class HeldHeight(val heightM: Double, val sigmaM: Double, val source: String?, val atElapsedMs: Long) {
    /** Still held at [elapsedNowMs] (a made-up elapsed time that went back counts as within the hold). */
    fun holds(elapsedNowMs: Long): Boolean = elapsedNowMs - atElapsedMs <= ReadoutTuning.HELD_HEIGHT_MS

    /** The elevation it shows: the height, dimmed, [REASON]. */
    fun value(): ReadoutValue = ReadoutValue(ReadoutEntry.ELEVATION, ValueState.SHOWN, heightM, sigmaM, source, dimmed = true, reason = REASON)

    companion object {
        val REASON = ReadoutReason.HELD // T363: the closed set
    }
}
