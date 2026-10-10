package io.github.jasondorjeshort.dystopo.nav

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The World Magnetic Model, WMM2025 (T61): the Earth's main field from a degree-12 spherical-harmonic
 * expansion with a linear secular variation, valid 2025.0 to 2030.0. Used to turn the compass's
 * magnetic heading into a true one (`true = magnetic + declination`).
 *
 * The coefficients are the US/UK public-domain WMM2025 release: `WMM.COF` from NOAA NCEI
 * (https://www.ncei.noaa.gov/products/world-magnetic-model, file `WMM2025COF.zip`, header
 * `2025.0  WMM-2025  11/13/2024`, fetched 2026-10-10), copied below unchanged. The evaluation is
 * the standard one from the WMM technical report (NOAA Technical Report NESDIS/NCEI, section 1.2):
 * geodetic to geocentric coordinates on WGS84, Schmidt semi-normalised associated Legendre
 * functions, field components in the geocentric frame, rotated back to geodetic north/east/down.
 * `WmmTest` checks it against NOAA's published test values (`WMM2025_TestValues.txt`).
 *
 * Pure Kotlin, no platform code. Outside 2025 to 2030 the result is extrapolated (and gets worse
 * every year); [Field.inValidity] says so.
 */
object Wmm {
    const val EPOCH = 2025.0
    const val VALID_FROM = 2025.0
    const val VALID_UNTIL = 2030.0
    const val MODEL = "WMM-2025"
    private const val N_MAX = 12

    /** Geomagnetic reference radius, km. */
    private const val A_REF = 6371.2

    /** WGS84 semi-major axis (km) and flattening. */
    private const val WGS84_A = 6378.137
    private const val WGS84_F = 1 / 298.257223563
    private const val E2 = WGS84_F * (2 - WGS84_F)
    private const val DEG = PI / 180

    /** The field at a point: components in nT (north, east, down), angles in degrees. */
    data class Field(
        val declinationDeg: Double,
        val inclinationDeg: Double,
        val xNt: Double,
        val yNt: Double,
        val zNt: Double,
        val year: Double,
    ) {
        val horizontalNt: Double get() = sqrt(xNt * xNt + yNt * yNt)
        val totalNt: Double get() = sqrt(xNt * xNt + yNt * yNt + zNt * zNt)
        val inValidity: Boolean get() = year in VALID_FROM..VALID_UNTIL
    }

    /** Declination (degrees, east positive) at geodetic [latDeg], [lonDeg], [heightKm] above the ellipsoid, decimal [year]. */
    fun declination(latDeg: Double, lonDeg: Double, year: Double, heightKm: Double = 0.0): Double =
        field(latDeg, lonDeg, year, heightKm).declinationDeg

    /** Decimal year of an epoch-millisecond time (365.25-day years from 2000-01-01; good to a fraction of a day). */
    fun decimalYear(epochMs: Long): Double = 2000.0 + (epochMs - MS_2000) / MS_PER_YEAR

    private const val MS_2000 = 946_684_800_000L
    private const val MS_PER_YEAR = 365.25 * 86_400_000.0

    fun field(latDeg: Double, lonDeg: Double, year: Double, heightKm: Double = 0.0): Field {
        // Geodetic -> geocentric spherical (WGS84).
        val phi = latDeg * DEG
        val lambda = lonDeg * DEG
        val sinPhi = sin(phi)
        val rc = WGS84_A / sqrt(1 - E2 * sinPhi * sinPhi)
        val p = (rc + heightKm) * cos(phi)
        val z = (rc * (1 - E2) + heightKm) * sinPhi
        val r = sqrt(p * p + z * z)
        val phiC = asin(z / r)

        // Coefficients at the date.
        val dt = year - EPOCH
        val g = Array(N_MAX + 1) { DoubleArray(N_MAX + 1) }
        val h = Array(N_MAX + 1) { DoubleArray(N_MAX + 1) }
        var i = 0
        while (i < COF.size) {
            val n = COF[i].toInt()
            val m = COF[i + 1].toInt()
            g[n][m] = COF[i + 2] + dt * COF[i + 4]
            h[n][m] = COF[i + 3] + dt * COF[i + 5]
            i += 6
        }

        // Schmidt semi-normalised P(n,m)(cos theta) and dP/dtheta, theta the geocentric colatitude.
        val cosT = sin(phiC)
        val sinT = cos(phiC)
        val pnm = Array(N_MAX + 1) { DoubleArray(N_MAX + 1) }
        val dp = Array(N_MAX + 1) { DoubleArray(N_MAX + 1) }
        pnm[0][0] = 1.0
        dp[0][0] = 0.0
        for (m in 1..N_MAX) {
            val k = if (m == 1) 1.0 else sqrt((2.0 * m - 1) / (2.0 * m))
            pnm[m][m] = k * sinT * pnm[m - 1][m - 1]
            dp[m][m] = k * (cosT * pnm[m - 1][m - 1] + sinT * dp[m - 1][m - 1])
        }
        for (m in 0..N_MAX) {
            for (n in m + 1..N_MAX) {
                val a = sqrt((n * n - m * m).toDouble())
                val b = if (n >= 2) sqrt(((n - 1) * (n - 1) - m * m).toDouble()) else 0.0
                val p2 = if (n - 2 >= m) pnm[n - 2][m] else 0.0
                val d2 = if (n - 2 >= m) dp[n - 2][m] else 0.0
                pnm[n][m] = ((2 * n - 1) * cosT * pnm[n - 1][m] - b * p2) / a
                dp[n][m] = ((2 * n - 1) * (cosT * dp[n - 1][m] - sinT * pnm[n - 1][m]) - b * d2) / a
            }
        }

        // Geocentric north (X'), east (Y'), down (Z').
        var xc = 0.0
        var yc = 0.0
        var zc = 0.0
        var ratio = A_REF / r
        ratio *= ratio // (a/r)^2, times (a/r) per degree below
        for (n in 1..N_MAX) {
            ratio *= A_REF / r
            for (m in 0..n) {
                val cm = cos(m * lambda)
                val sm = sin(m * lambda)
                val gh = g[n][m] * cm + h[n][m] * sm
                xc += ratio * gh * dp[n][m]
                yc += ratio * m * (g[n][m] * sm - h[n][m] * cm) * pnm[n][m]
                zc -= ratio * (n + 1) * gh * pnm[n][m]
            }
        }
        // Y' divides by sin(theta): at the geographic pole itself the east component is undefined, so
        // stay a hair away from it (declination is meaningless there anyway).
        yc /= if (sinT < 1e-10) 1e-10 else sinT

        // Rotate into the geodetic frame.
        val psi = phiC - phi
        val x = xc * cos(psi) - zc * sin(psi)
        val zz = xc * sin(psi) + zc * cos(psi)
        val hz = sqrt(x * x + yc * yc)
        return Field(
            declinationDeg = atan2(yc, x) / DEG,
            inclinationDeg = atan2(zz, hz) / DEG,
            xNt = x, yNt = yc, zNt = zz, year = year,
        )
    }

    /**
     * WMM2025 Gauss coefficients, `WMM.COF` rows unchanged: n, m, g (nT), h (nT), dg/dt (nT/yr),
     * dh/dt (nT/yr), epoch 2025.0. Public domain (NOAA NCEI / British Geological Survey).
     */
    private val COF = doubleArrayOf(
        1.0, 0.0,  -29351.8,       0.0,   12.0,    0.0,
        1.0, 1.0,   -1410.8,    4545.4,    9.7,  -21.5,
        2.0, 0.0,   -2556.6,       0.0,  -11.6,    0.0,
        2.0, 1.0,    2951.1,   -3133.6,   -5.2,  -27.7,
        2.0, 2.0,    1649.3,    -815.1,   -8.0,  -12.1,
        3.0, 0.0,    1361.0,       0.0,   -1.3,    0.0,
        3.0, 1.0,   -2404.1,     -56.6,   -4.2,    4.0,
        3.0, 2.0,    1243.8,     237.5,    0.4,   -0.3,
        3.0, 3.0,     453.6,    -549.5,  -15.6,   -4.1,
        4.0, 0.0,     895.0,       0.0,   -1.6,    0.0,
        4.0, 1.0,     799.5,     278.6,   -2.4,   -1.1,
        4.0, 2.0,      55.7,    -133.9,   -6.0,    4.1,
        4.0, 3.0,    -281.1,     212.0,    5.6,    1.6,
        4.0, 4.0,      12.1,    -375.6,   -7.0,   -4.4,
        5.0, 0.0,    -233.2,       0.0,    0.6,    0.0,
        5.0, 1.0,     368.9,      45.4,    1.4,   -0.5,
        5.0, 2.0,     187.2,     220.2,    0.0,    2.2,
        5.0, 3.0,    -138.7,    -122.9,    0.6,    0.4,
        5.0, 4.0,    -142.0,      43.0,    2.2,    1.7,
        5.0, 5.0,      20.9,     106.1,    0.9,    1.9,
        6.0, 0.0,      64.4,       0.0,   -0.2,    0.0,
        6.0, 1.0,      63.8,     -18.4,   -0.4,    0.3,
        6.0, 2.0,      76.9,      16.8,    0.9,   -1.6,
        6.0, 3.0,    -115.7,      48.8,    1.2,   -0.4,
        6.0, 4.0,     -40.9,     -59.8,   -0.9,    0.9,
        6.0, 5.0,      14.9,      10.9,    0.3,    0.7,
        6.0, 6.0,     -60.7,      72.7,    0.9,    0.9,
        7.0, 0.0,      79.5,       0.0,   -0.0,    0.0,
        7.0, 1.0,     -77.0,     -48.9,   -0.1,    0.6,
        7.0, 2.0,      -8.8,     -14.4,   -0.1,    0.5,
        7.0, 3.0,      59.3,      -1.0,    0.5,   -0.8,
        7.0, 4.0,      15.8,      23.4,   -0.1,    0.0,
        7.0, 5.0,       2.5,      -7.4,   -0.8,   -1.0,
        7.0, 6.0,     -11.1,     -25.1,   -0.8,    0.6,
        7.0, 7.0,      14.2,      -2.3,    0.8,   -0.2,
        8.0, 0.0,      23.2,       0.0,   -0.1,    0.0,
        8.0, 1.0,      10.8,       7.1,    0.2,   -0.2,
        8.0, 2.0,     -17.5,     -12.6,    0.0,    0.5,
        8.0, 3.0,       2.0,      11.4,    0.5,   -0.4,
        8.0, 4.0,     -21.7,      -9.7,   -0.1,    0.4,
        8.0, 5.0,      16.9,      12.7,    0.3,   -0.5,
        8.0, 6.0,      15.0,       0.7,    0.2,   -0.6,
        8.0, 7.0,     -16.8,      -5.2,   -0.0,    0.3,
        8.0, 8.0,       0.9,       3.9,    0.2,    0.2,
        9.0, 0.0,       4.6,       0.0,   -0.0,    0.0,
        9.0, 1.0,       7.8,     -24.8,   -0.1,   -0.3,
        9.0, 2.0,       3.0,      12.2,    0.1,    0.3,
        9.0, 3.0,      -0.2,       8.3,    0.3,   -0.3,
        9.0, 4.0,      -2.5,      -3.3,   -0.3,    0.3,
        9.0, 5.0,     -13.1,      -5.2,    0.0,    0.2,
        9.0, 6.0,       2.4,       7.2,    0.3,   -0.1,
        9.0, 7.0,       8.6,      -0.6,   -0.1,   -0.2,
        9.0, 8.0,      -8.7,       0.8,    0.1,    0.4,
        9.0, 9.0,     -12.9,      10.0,   -0.1,    0.1,
        10.0, 0.0,      -1.3,       0.0,    0.1,    0.0,
        10.0, 1.0,      -6.4,       3.3,    0.0,    0.0,
        10.0, 2.0,       0.2,       0.0,    0.1,   -0.0,
        10.0, 3.0,       2.0,       2.4,    0.1,   -0.2,
        10.0, 4.0,      -1.0,       5.3,   -0.0,    0.1,
        10.0, 5.0,      -0.6,      -9.1,   -0.3,   -0.1,
        10.0, 6.0,      -0.9,       0.4,    0.0,    0.1,
        10.0, 7.0,       1.5,      -4.2,   -0.1,    0.0,
        10.0, 8.0,       0.9,      -3.8,   -0.1,   -0.1,
        10.0, 9.0,      -2.7,       0.9,   -0.0,    0.2,
        10.0, 10.0,      -3.9,      -9.1,   -0.0,   -0.0,
        11.0, 0.0,       2.9,       0.0,    0.0,    0.0,
        11.0, 1.0,      -1.5,       0.0,   -0.0,   -0.0,
        11.0, 2.0,      -2.5,       2.9,    0.0,    0.1,
        11.0, 3.0,       2.4,      -0.6,    0.0,   -0.0,
        11.0, 4.0,      -0.6,       0.2,    0.0,    0.1,
        11.0, 5.0,      -0.1,       0.5,   -0.1,   -0.0,
        11.0, 6.0,      -0.6,      -0.3,    0.0,   -0.0,
        11.0, 7.0,      -0.1,      -1.2,   -0.0,    0.1,
        11.0, 8.0,       1.1,      -1.7,   -0.1,   -0.0,
        11.0, 9.0,      -1.0,      -2.9,   -0.1,    0.0,
        11.0, 10.0,      -0.2,      -1.8,   -0.1,    0.0,
        11.0, 11.0,       2.6,      -2.3,   -0.1,    0.0,
        12.0, 0.0,      -2.0,       0.0,    0.0,    0.0,
        12.0, 1.0,      -0.2,      -1.3,    0.0,   -0.0,
        12.0, 2.0,       0.3,       0.7,   -0.0,    0.0,
        12.0, 3.0,       1.2,       1.0,   -0.0,   -0.1,
        12.0, 4.0,      -1.3,      -1.4,   -0.0,    0.1,
        12.0, 5.0,       0.6,      -0.0,   -0.0,   -0.0,
        12.0, 6.0,       0.6,       0.6,    0.1,   -0.0,
        12.0, 7.0,       0.5,      -0.1,   -0.0,   -0.0,
        12.0, 8.0,      -0.1,       0.8,    0.0,    0.0,
        12.0, 9.0,      -0.4,       0.1,    0.0,   -0.0,
        12.0, 10.0,      -0.2,      -1.0,   -0.1,   -0.0,
        12.0, 11.0,      -1.3,       0.1,   -0.0,    0.0,
        12.0, 12.0,      -0.7,       0.2,   -0.1,   -0.1,
    )
}
