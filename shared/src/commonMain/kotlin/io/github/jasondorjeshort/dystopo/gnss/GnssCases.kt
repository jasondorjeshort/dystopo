package io.github.jasondorjeshort.dystopo.gnss

/**
 * T307: synthetic GNSS statuses for the GPS view's screenshots and the check's runs on an emulator, whose own GNSS reports
 * one fixed set (`POST /gnss?case=` on the phone). Deterministic: the same case gives the same satellites. [RIDGE] puts
 * its weak satellites behind the terrain the [horizon] describes (where it stands highest), so the verdict reads a weak
 * signal from a blocked sky, not a fault.
 */
object GnssCases {
    const val OPEN = "open"
    const val WEAK = "weak"
    const val FAULT = "fault"
    const val INDOORS = "indoors"
    const val RIDGE = "ridge"
    val NAMES = listOf(OPEN, WEAK, FAULT, INDOORS, RIDGE)

    private const val L1 = 1_575_420_000.0
    private const val L5 = 1_176_450_000.0
    private const val G1 = 1_602_000_000.0

    /** (system, svid, elevation, azimuth): a spread over the whole sky, as an open sky shows it. */
    private val SKY = listOf(
        Triple(Constellation.GPS, 2, 72.0 to 40.0), Triple(Constellation.GPS, 5, 48.0 to 130.0), Triple(Constellation.GPS, 12, 33.0 to 215.0),
        Triple(Constellation.GPS, 15, 61.0 to 290.0), Triple(Constellation.GPS, 18, 22.0 to 340.0), Triple(Constellation.GPS, 24, 14.0 to 95.0),
        Triple(Constellation.GPS, 25, 40.0 to 170.0), Triple(Constellation.GPS, 29, 9.0 to 255.0),
        Triple(Constellation.GALILEO, 3, 55.0 to 75.0), Triple(Constellation.GALILEO, 8, 27.0 to 150.0), Triple(Constellation.GALILEO, 13, 66.0 to 230.0),
        Triple(Constellation.GALILEO, 26, 18.0 to 310.0), Triple(Constellation.GALILEO, 31, 38.0 to 15.0),
        Triple(Constellation.GLONASS, 7, 44.0 to 260.0), Triple(Constellation.GLONASS, 8, 25.0 to 195.0), Triple(Constellation.GLONASS, 22, 58.0 to 110.0),
        Triple(Constellation.GLONASS, 23, 12.0 to 30.0),
        Triple(Constellation.BEIDOU, 19, 50.0 to 320.0), Triple(Constellation.BEIDOU, 20, 30.0 to 60.0), Triple(Constellation.BEIDOU, 29, 70.0 to 180.0),
        Triple(Constellation.BEIDOU, 30, 15.0 to 240.0),
        Triple(Constellation.QZSS, 194, 20.0 to 205.0), Triple(Constellation.SBAS, 131, 31.0 to 165.0),
    )

    /** The case's satellites, or null for a name not in [NAMES]. [horizon]: the terrain's angle by azimuth, for [RIDGE]. */
    fun of(name: String, horizon: ((Double) -> Double)? = null): List<Satellite>? = when (name.trim().lowercase()) {
        OPEN -> sky { i, el -> 38.0 + (i * 7 % 9) + el / 20 }
        WEAK -> sky(usedEvery = 2) { i, el -> 24.0 + (i * 5 % 9) + el / 30 }
        FAULT -> sky(usedEvery = 4) { i, _ -> 13.0 + (i * 5 % 10) }
        INDOORS -> SKY.take(4).mapIndexed { i, (c, n, p) -> Satellite(c, n, 17.0 + i * 2, p.first, p.second, used = false, ephemeris = i < 2, almanac = true, carrierHz = carrier(c)) }
        RIDGE -> ridge(horizon)
        else -> null
    }

    private fun carrier(c: Constellation) = if (c == Constellation.GLONASS) G1 else L1

    private fun sky(usedEvery: Int = 1, cn0: (Int, Double) -> Double): List<Satellite> {
        val out = ArrayList<Satellite>()
        SKY.forEachIndexed { i, (c, n, p) ->
            val s = Satellite(c, n, cn0(i, p.first), p.first, p.second, used = i % usedEvery == 0 && c != Constellation.SBAS, ephemeris = true, almanac = true, carrierHz = carrier(c))
            out += s
            // A dual-band phone hears the newer GPS and Galileo satellites on L5 too, a little weaker.
            if ((c == Constellation.GPS || c == Constellation.GALILEO) && i % 2 == 0) out += s.copy(cn0 = s.cn0 - 2.5, carrierHz = L5)
        }
        return out
    }

    /**
     * A blocked sky: ten satellites behind the terrain where it stands highest (weak, 14 to 23 dB-Hz), and four above it
     * at 27 to 32 dB-Hz, two of them used. Without a [horizon] a synthetic ridge of 25 degrees from north-west to
     * north-east stands in for one (the unit tests use it too).
     */
    fun ridge(horizon: ((Double) -> Double)?): List<Satellite> {
        val h = horizon ?: SYNTHETIC_RIDGE
        // The highest bearings, at least 12 degrees apart.
        val bearings = (0 until 360).sortedByDescending { h(it.toDouble()) }
        val picked = ArrayList<Int>()
        for (b in bearings) {
            if (picked.size == 10) break
            if (h(b.toDouble()) < 3.0) break
            if (picked.all { GnssHealth.angleApart(it.toDouble(), b.toDouble()) >= 12 }) picked += b
        }
        val out = ArrayList<Satellite>()
        val systems = listOf(Constellation.GPS, Constellation.GALILEO, Constellation.GLONASS, Constellation.BEIDOU)
        picked.forEachIndexed { i, b ->
            val top = h(b.toDouble())
            val el = (top * (0.35 + 0.1 * (i % 4))).coerceAtLeast(0.5)
            val c = systems[i % systems.size]
            out += Satellite(c, 40 + i, 14.0 + (i * 3 % 10), el, b.toDouble(), used = false, ephemeris = true, almanac = true, carrierHz = carrier(c))
        }
        // Four above the terrain, away from it: where it is lowest, high in the sky.
        val low = (0 until 360).sortedBy { h(it.toDouble()) }.filter { b -> picked.all { GnssHealth.angleApart(it.toDouble(), b.toDouble()) >= 30 } }
        val opens = listOf(0, low.size / 3, 2 * low.size / 3, low.size - 1).mapNotNull { low.getOrNull(it) }.distinct()
        opens.forEachIndexed { i, b ->
            val c = systems[i % systems.size]
            out += Satellite(c, 10 + i, 27.0 + i * 1.5, maxOf(h(b.toDouble()) + 20, 35.0 + i * 8), b.toDouble(), used = i < 2, ephemeris = true, almanac = true, carrierHz = carrier(c))
        }
        return out
    }

    /** A ridge of 25 degrees between 300 and 60 degrees (north), flat elsewhere. */
    val SYNTHETIC_RIDGE: (Double) -> Double = { az -> if (GnssHealth.angleApart(az, 0.0) <= 60) 25.0 else 2.0 }
}
