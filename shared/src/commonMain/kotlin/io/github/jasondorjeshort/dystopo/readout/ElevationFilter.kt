package io.github.jasondorjeshort.dystopo.readout

import io.github.jasondorjeshort.dystopo.model.Activity
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The readout's elevation as a small Kalman filter (T160): height above mean sea level (m, EGM2008), vertical speed
 * (m/s) and the barometer offset (m: pressure altitude minus true height, which takes in the weather and the
 * sensor's own bias). Built on the readout idea's research (2026-10-25; a Garmin patent, US 6735542, gives the shape):
 *
 * - **Barometer** (when open): every sample, as height plus offset, 0.5 m noise (pocket and wind jitter; the sensor
 *   itself is about 0.25 m).
 * - **GNSS**: with the barometer, one height per [ReadoutTuning.gnssCorrelationS] (30 s on foot; its error is
 *   correlated), one per 10 s for the first 5 minutes after the offset is set (the research's fast gain after a cold start) at a variance of (2 × vertical accuracy)²; on GNSS alone, every fix with that variance scaled by the
 *   correlation time over the fix interval. Gated at 5 sigma (of the fix's own variance); four in a row on one side averaging over
 *   1 sigma are a coarse recalibration (the research's "residual beyond k times the vertical
 *   uncertainty": the spread of height and offset opens to the residual, so the next updates pull it in fast).
 * - **DEM** ([dem]): a height with the caller's sigma (the estimator gates it: on foot, slope, structures, 150 m).
 * - **Weather station pressure** ([prior]): a prior on the offset with a sigma that grows with distance and age.
 * - The offset drifts as a random walk of 5 m/√h; the vertical speed as white acceleration ([ReadoutTuning.verticalNoise]).
 * - **A known height** ([knownHeight], T197: a summit, a saddle or a sign the user says they stand at) replaces the
 *   filter's height with its own sigma: the spread is opened along (h, b) = (1, -1) first, so the update takes the
 *   known height whole and the offset follows through the barometer. With the barometer fused it is kept as a [pin]:
 *   while it holds, GNSS and DEM heights are not applied (the GNSS's 30 s decimation counts its correlated error as
 *   independent, which holds the offset's sigma near 2 m and would pull the set height back toward the GNSS's own
 *   bias within minutes). The offset's random walk alone ages it: the pin ends when the offset's sigma reaches
 *   [PIN_HOLD_FACTOR] times the pin's (no timer), when the barometer closes or goes silent, or when [RECAL_AFTER]
 *   watched GNSS heights in a row lie over [GATE] sigmas off on one side (a wrong tag, a mistyped height). A weather
 *   station's prior is still applied while pinned.
 *
 * Not thread-safe; times are the inputs' own (no clock).
 */
internal class ElevationFilter(var activity: Activity) {
    private val x = DoubleArray(3)
    private val p = DoubleArray(9)
    private var t: Long? = null

    var heightKnown = false
        private set
    var offsetKnown = false
        private set

    /** Barometer samples are applied (the estimator closes it while suspect, or for road, rail and air). */
    var baroOpen = false
        private set
    var lastBaroMs: Long? = null
        private set
    var lastBaroAltM: Double? = null
        private set
    private var lastGnssMs: Long? = null
    private var offsetSinceMs: Long? = null
    private val residuals = ArrayDeque<Double>()

    var gnssUpdates = 0
        private set
    var gnssRejected = 0
        private set
    var demUpdates = 0
        private set
    var demRejected = 0
        private set
    var baroUpdates = 0
        private set
    var recalibrations = 0
        private set
    var priors = 0
        private set
    var lastDemMs: Long? = null
        private set

    // T197: the known height kept while it holds ([knownHeight]), and how pins ended.
    /** A known height kept by the filter: [name] null for a typed one; [tMs] when it was set. */
    class Pin(val name: String?, val eleM: Double, val sigmaM: Double, val tMs: Long)

    var pin: Pin? = null
        private set

    /** Why the last pin ended: `sigma` (the offset's sigma grew past the rule), `barometer` (closed or silent), `disagreement`, `replaced`. */
    var pinEnd: String? = null
        private set
    private val pinResiduals = ArrayDeque<Double>()
    var pins = 0
        private set
    var pinsCleared = 0
        private set
    var pinReleases = 0
        private set
    var gnssHeldByPin = 0
        private set
    var demHeldByPin = 0
        private set

    val heightM: Double get() = x[0]
    val heightSigmaM: Double get() = sqrt(max(0.0, p[0]))
    val verticalMps: Double get() = x[1]
    val verticalSigmaMps: Double get() = sqrt(max(0.0, p[4]))
    val offsetM: Double get() = x[2]
    val offsetSigmaM: Double get() = sqrt(max(0.0, p[8]))

    /** The barometer is open, calibrated and has sent a sample within [BARO_FRESH_MS] of [nowMs]. */
    fun fused(nowMs: Long): Boolean = baroOpen && offsetKnown && heightKnown && lastBaroMs?.let { nowMs - it <= BARO_FRESH_MS } == true

    /** The filter's time (the latest input's), null before the first (the estimator's clock check, T161). */
    val timeMs: Long? get() = t

    /** Moves the state to [tMs]; a time before the filter's is taken as the filter's (a late sample). */
    fun predictTo(tMs: Long) {
        val t0 = t
        if (t0 == null || tMs <= t0) {
            if (t0 == null) t = tMs
            return
        }
        val dt = (tMs - t0) / 1000.0
        t = tMs
        val q = ReadoutTuning.verticalNoise(activity, fused(t0))
        // P = F P F' + Q with F = [[1, dt, 0], [0, 1, 0], [0, 0, 1]].
        val p00 = p[0] + dt * (p[1] + p[3]) + dt * dt * p[4] + q * dt * dt * dt / 3
        val p01 = p[1] + dt * p[4] + q * dt * dt / 2
        val p02 = p[2] + dt * p[5]
        val p11 = p[4] + q * dt
        val p12 = p[5]
        val p22 = p[8] + OFFSET_WALK_M2_PER_S * dt
        x[0] += dt * x[1]
        set(p00, p01, p02, p11, p12, p22)
    }

    /** Opens the barometer: the next sample sets the offset from the current height (a reopened offset, the research's rule). */
    fun openBarometer() {
        if (baroOpen) return
        baroOpen = true
        offsetKnown = false
    }

    fun closeBarometer() {
        baroOpen = false
        endPin("barometer") // T197: without the barometer the set height cannot be held
    }

    /** T197: the offset's sigma at [nowMs], grown by its random walk from the filter's time (pure). */
    fun offsetSigmaAt(nowMs: Long): Double {
        val dt = t?.let { (nowMs - it).coerceAtLeast(0) / 1000.0 } ?: 0.0
        return sqrt(max(0.0, p[8] + OFFSET_WALK_M2_PER_S * dt))
    }

    /** T197: the pin holds at [nowMs]: the barometer fused and the offset's sigma under [PIN_HOLD_FACTOR] times the pin's (pure). */
    fun pinHolds(nowMs: Long): Boolean {
        val pn = pin ?: return false
        return fused(nowMs) && offsetSigmaAt(nowMs) < PIN_HOLD_FACTOR * pn.sigmaM
    }

    /** Ends the pin at [tMs] when it no longer holds (after [predictTo]). */
    private fun checkPin(tMs: Long) {
        if (pin == null || pinHolds(tMs)) return
        endPin(if (fused(tMs)) "sigma" else "barometer")
    }

    private fun endPin(why: String) {
        if (pin == null) return
        pin = null
        pinEnd = why
        pinResiduals.clear()
        if (why == "disagreement") pinReleases++ else pinsCleared++
    }

    /**
     * T197: a known height [eleM] with 1-sigma [sigmaM] at [tMs], named [name] (null for a typed one). Replaces the
     * height (and through the barometer the offset) with it; returns true when it is kept as a [pin] (the barometer
     * fused), false when it was applied once as a height (no barometer: the next fixes take over).
     */
    fun knownHeight(tMs: Long, eleM: Double, sigmaM: Double, name: String?): Boolean {
        predictTo(tMs)
        val r = sigmaM * sigmaM
        endPin("replaced")
        residuals.clear()
        if (!heightKnown) {
            x[0] = eleM
            x[1] = 0.0
            val vv = ReadoutTuning.verticalSpeedPriorMps(activity).let { it * it }
            set(r, 0.0, 0.0, vv, 0.0, if (offsetKnown) p[8] else 0.0)
            heightKnown = true
            return false
        }
        // Open the spread so the update takes the known height whole: along (h, b) = (1, -1) while the barometer ties
        // the two (its offset then moves the other way, keeping the pressure altitude), along h alone otherwise.
        val l = KNOWN_SPREAD_M2
        if (baroOpen && offsetKnown) set(p[0] + l, p[1], p[2] - l, p[4], p[5], p[8] + l)
        else set(p[0] + l, p[1], p[2], p[4], p[5], p[8])
        update(1.0, 0.0, 0.0, eleM, r)
        if (!fused(tMs)) return false
        pin = Pin(name, eleM, sigmaM, tMs)
        pins++
        return true
    }

    /** A barometer sample as pressure altitude [altM] at [tMs]. */
    fun barometer(tMs: Long, altM: Double) {
        lastBaroAltM = altM
        if (!baroOpen) return
        predictTo(tMs)
        lastBaroMs = tMs
        checkPin(tMs)
        when {
            !heightKnown && offsetKnown -> {
                // A height from the barometer and the prior offset: h = z - b, so cov(h, b) = -var(b).
                x[0] = altM - x[2]
                x[1] = 0.0
                val vb = p[8]
                val vv = ReadoutTuning.verticalSpeedPriorMps(activity).let { it * it }
                set(vb + BARO_VAR, 0.0, -vb, vv, 0.0, vb)
                heightKnown = true
            }
            !heightKnown -> Unit
            !offsetKnown -> {
                // b = z - h: cov(b, h) = -var(h), cov(b, v) = -cov(h, v).
                x[2] = altM - x[0]
                set(p[0], p[1], -p[0], p[4], -p[1], p[0] + BARO_VAR)
                offsetKnown = true
                offsetSinceMs = tMs
            }
            else -> {
                update(1.0, 0.0, 1.0, altM, BARO_VAR)
                baroUpdates++
            }
        }
    }

    /**
     * A GNSS height [eleM] with vertical accuracy [vAccM] at [tMs]. With [fusedNow] only one per correlation time
     * is used; otherwise every one, its variance scaled. Returns whether it was applied.
     */
    fun gnss(tMs: Long, eleM: Double, vAccM: Double): Boolean {
        predictTo(tMs)
        checkPin(tMs)
        val fused = fused(tMs)
        val corrMs = (ReadoutTuning.gnssCorrelationS(activity) * 1000).toLong()
        val last = lastGnssMs
        val r0 = (2 * vAccM) * (2 * vAccM)
        if (!heightKnown) {
            x[0] = eleM
            x[1] = 0.0
            val vv = ReadoutTuning.verticalSpeedPriorMps(activity).let { it * it }
            set(r0, 0.0, 0.0, vv, 0.0, if (offsetKnown) p[8] else 0.0)
            heightKnown = true
            lastGnssMs = tMs
            gnssUpdates++
            return true
        }
        if (pin != null) return heldByPin(tMs, eleM, r0, corrMs)
        val r = if (fused) {
            // One per correlation time; one per FAST_EVERY_MS for the first FAST_MS after the offset was (re)set.
            val fast = offsetSinceMs?.let { tMs - it < FAST_MS } == true
            if (last != null && tMs - last < (if (fast) FAST_EVERY_MS else corrMs)) return false
            r0
        } else {
            val dtMs = if (last == null) corrMs else (tMs - last).coerceAtLeast(1)
            r0 * max(1.0, corrMs.toDouble() / dtMs)
        }
        lastGnssMs = tMs
        val y = eleM - x[0]
        // Gates on the fix's own variance (not the scaled one): an outlier is one fix, a shift is several in a row.
        val s = p[0] + r0
        // A shift of the barometer offset: the last RECAL_AFTER residuals all on one side, their mean over RECAL_SIGMAS
        // of the fix's sigma. On GNSS alone there is no offset to recalibrate (a DEM disagreeing with the GNSS is not a shift).
        val sigma = sqrt(s)
        if (fused) {
            residuals.addLast(y / sigma)
            while (residuals.size > RECAL_AFTER) residuals.removeFirst()
        }
        val shifted = fused && residuals.size == RECAL_AFTER && (residuals.all { it > 0 } || residuals.all { it < 0 }) &&
            kotlin.math.abs(residuals.average()) > RECAL_SIGMAS
        if (shifted) {
            // Coarse recalibration: the height (and the offset, which the barometer ties to it) may be this far off.
            recalibrations++
            // The shift is in the offset, the height following it through the barometer: open the spread along
            // (h, b) = (1, -1) so the next GNSS heights move both, in opposite directions.
            val y2 = y * y
            set(p[0] + y2, p[1], p[2] - y2, p[4], p[5], p[8] + y2)
            offsetSinceMs = tMs
            residuals.clear()
        } else if (y * y > GATE * GATE * s) {
            gnssRejected++
            return false
        }
        update(1.0, 0.0, 0.0, eleM, r)
        gnssUpdates++
        return true
    }

    /**
     * T197: a GNSS height while pinned: not applied, one per correlation time watched. [RECAL_AFTER] in a row beyond
     * [GATE] sigmas of the fix's own variance, all on one side, release the pin (a wrong tag or a mistyped height) and
     * recalibrate coarsely as a shift does.
     */
    private fun heldByPin(tMs: Long, eleM: Double, r0: Double, corrMs: Long): Boolean {
        val last = lastGnssMs
        if (last != null && tMs - last < corrMs) return false
        lastGnssMs = tMs
        val y = eleM - x[0]
        if (y * y > GATE * GATE * (p[0] + r0)) pinResiduals.addLast(y) else pinResiduals.clear()
        while (pinResiduals.size > RECAL_AFTER) pinResiduals.removeFirst()
        if (pinResiduals.size == RECAL_AFTER && (pinResiduals.all { it > 0 } || pinResiduals.all { it < 0 })) {
            endPin("disagreement")
            recalibrations++
            val y2 = y * y
            set(p[0] + y2, p[1], p[2] - y2, p[4], p[5], p[8] + y2)
            offsetSinceMs = tMs
            update(1.0, 0.0, 0.0, eleM, r0)
            gnssUpdates++
            return true
        }
        gnssHeldByPin++
        return false
    }

    /** A DEM height [eleM] with 1-sigma [sigmaM] at [tMs] (the caller gates it). Returns whether it was applied. */
    fun dem(tMs: Long, eleM: Double, sigmaM: Double): Boolean {
        predictTo(tMs)
        checkPin(tMs)
        if (pin != null) { demHeldByPin++; return false } // T197: the set height outranks the DEM while it holds
        val r = sigmaM * sigmaM
        if (!heightKnown) {
            x[0] = eleM
            x[1] = 0.0
            val vv = ReadoutTuning.verticalSpeedPriorMps(activity).let { it * it }
            set(r, 0.0, 0.0, vv, 0.0, if (offsetKnown) p[8] else 0.0)
            heightKnown = true
            lastDemMs = tMs
            demUpdates++
            return true
        }
        val y = eleM - x[0]
        if (y * y > GATE * GATE * (p[0] + r)) {
            demRejected++
            return false
        }
        update(1.0, 0.0, 0.0, eleM, r)
        lastDemMs = tMs
        demUpdates++
        return true
    }

    /** A prior on the barometer offset: mean [offsetM], 1-sigma [sigmaM] (from a weather station's pressure). */
    fun prior(offsetM: Double, sigmaM: Double) {
        val r = sigmaM * sigmaM
        priors++
        if (!offsetKnown) {
            x[2] = offsetM
            p[2] = 0.0; p[6] = 0.0; p[5] = 0.0; p[7] = 0.0
            p[8] = r
            offsetKnown = true
            return
        }
        update(0.0, 0.0, 1.0, offsetM, r)
    }

    /** Scalar update with H = [h0, h1, h2]. */
    private fun update(h0: Double, h1: Double, h2: Double, z: Double, r: Double) {
        // PH' and S = H P H' + R.
        val ph0 = p[0] * h0 + p[1] * h1 + p[2] * h2
        val ph1 = p[3] * h0 + p[4] * h1 + p[5] * h2
        val ph2 = p[6] * h0 + p[7] * h1 + p[8] * h2
        val s = h0 * ph0 + h1 * ph1 + h2 * ph2 + r
        if (!(s > 0)) return
        val y = z - (h0 * x[0] + h1 * x[1] + h2 * x[2])
        val k0 = ph0 / s
        val k1 = ph1 / s
        val k2 = ph2 / s
        x[0] += k0 * y
        x[1] += k1 * y
        x[2] += k2 * y
        // P -= K (H P) = K PH' (P symmetric).
        set(
            p[0] - k0 * ph0, p[1] - k0 * ph1, p[2] - k0 * ph2,
            p[4] - k1 * ph1, p[5] - k1 * ph2,
            p[8] - k2 * ph2,
        )
    }

    private fun set(p00: Double, p01: Double, p02: Double, p11: Double, p12: Double, p22: Double) {
        p[0] = max(p00, 0.0); p[1] = p01; p[2] = p02
        p[3] = p01; p[4] = max(p11, 0.0); p[5] = p12
        p[6] = p02; p[7] = p12; p[8] = max(p22, 0.0)
    }

    companion object {
        /** Barometer sample variance (m²): 0.5 m. */
        const val BARO_VAR = 0.25

        /** The offset's random walk: 5 m/√h (research, derived for a phone). */
        const val OFFSET_WALK_M2_PER_S = 25.0 / 3600

        /** After the offset is set (a cold start, a reopened barometer), GNSS heights are taken this often for this long (the research's fast gain, about 5 min). */
        const val FAST_MS = 300_000L
        const val FAST_EVERY_MS = 10_000L

        /** A barometer silent this long no longer counts as fused (the GNSS goes back to every fix). */
        const val BARO_FRESH_MS = 10_000L

        /** A GNSS or DEM height further off than this many sigmas is rejected as an outlier. */
        const val GATE = 5.0

        /** [RECAL_AFTER] GNSS residuals in a row on one side, averaging over this many sigmas, trigger a coarse recalibration. */
        const val RECAL_SIGMAS = 1.0
        const val RECAL_AFTER = 4

        /**
         * T197: a pin holds while the offset's sigma is under this many times the pin's own (the idea's rule): until
         * then the set height carries at least a quarter of its first weight against the walk. On the walk alone a
         * summit's 3 m holds 1 h 5 min and a sign's 5 m 3 h.
         */
        const val PIN_HOLD_FACTOR = 2.0

        /** T197: the spread (m², a 1 km sigma) opened before a known height so its update replaces the filter's height. */
        const val KNOWN_SPREAD_M2 = 1e6
    }
}
