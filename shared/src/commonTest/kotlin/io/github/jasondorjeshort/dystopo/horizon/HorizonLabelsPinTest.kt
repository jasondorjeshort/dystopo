package io.github.jasondorjeshort.dystopo.horizon

import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * T322: the round plot's placements pinned before [HorizonLabels.place] took a projection: a fixed candidate set (a dominant
 * summit, a crowd, a sparse stretch, labels low and high, one picked, a ring label and the sun's arc in the way, and an
 * aircraft pass) on a turned [RimGeometry] at a phone's density, each name with its x and y. Written against the code
 * before the refactor; it must pass unchanged after it.
 */
class HorizonLabelsPinTest {
    private val g = RimGeometry.of(1080.0, 1080.0, 2.625, 17.0)
    private val width = { s: String -> 6.5 * 2.625 * s.length }
    private val ascent = -11.5 * 2.625 * 0.92
    private val descent = 3.1 * 2.625

    private fun peak(name: String, bearing: Double, angle: Double, d: Double, clear: Double = 1.0, ele: Double = 2000.0, prom: Double? = null, dominant: Boolean = false) =
        HorizonLabel(name, LabelKind.PEAK, bearing, angle, d, clear, ele, prom, dominant)

    private val peaks = listOf(
        peak("Mount Summit", 352.0, 14.2, 12_400.0, ele = 4392.0, prom = 4000.0, dominant = true),
        peak("Near Knob", 101.0, 6.0, 640.0, clear = -0.3, ele = 1900.0),
        peak("Low Ridge", 175.0, -0.8, 8_000.0, ele = 1500.0, prom = 40.0),
        peak("Steep Spire", 31.0, 38.0, 1_500.0, ele = 2700.0, prom = 300.0),
        peak("Overhead Rock", 210.0, 71.0, 400.0, ele = 2300.0),
        peak("Far Dome", 260.0, 1.2, 28_000.0, ele = 3100.0, prom = 900.0),
        peak("West Horn", 275.0, 2.4, 9_000.0, ele = 2500.0),
    ) + (0 until 14).map { i -> peak("Crowd ${'A' + i}", 40.0 + i * 1.7, 3.0 + 0.4 * i, 2_000.0 + 350.0 * i, ele = 1800.0 + 20 * i, prom = 30.0 * i) } +
        (0 until 4).map { i -> peak("Sparse $i", 140.0 + i * 4.5, 1.5 + i, 5_000.0 + 900.0 * i, ele = 2000.0 + 50 * i) }

    private val aircraft = listOf(
        HorizonLabel("FLT1 > SEA", LabelKind.AIRCRAFT, 45.0, 9.0, 30_000.0, 90.0, 9000.0, 0.0),
        HorizonLabel("FLT2", LabelKind.AIRCRAFT, 120.0, 25.0, 15_000.0, 90.0, 6000.0, 0.0),
        HorizonLabel("FLT3 > PDX", LabelKind.AIRCRAFT, 300.0, 4.0, 60_000.0, 90.0, 11000.0, 0.0),
    )

    private fun sig(l: List<HorizonLabels.Placed>) = l.joinToString("\n") { p ->
        val lead = p.leader?.let { ld -> " leader ${r(ld[1].x)},${r(ld[1].y)}" } ?: ""
        "${p.label.name} ${r(p.x)},${r(p.y)} gap ${r(p.gapDeg)}$lead"
    }

    private fun r(v: Double) = kotlin.math.round(v * 1000) / 1000

    @Test
    fun theRoundPlotsPlacementsArePinned() {
        val ranked = HorizonLabels.rank(peaks)
        val rings = listOf(0.0, 30.0, 60.0).map { el -> g.ringLabel(el, width("${el.toInt()}°"), ascent, descent) }
        val arc = (100..260 step 5).map { az -> g.at(18 * sin((az - 100) * kotlin.math.PI / 160), az.toDouble()) }
        val picked = peaks.first { it.name == "Crowd F" }
        val alone = HorizonLabels.place(g, ranked, width, ascent, descent)
        val withBlocks = HorizonLabels.place(g, ranked, width, ascent, descent, rings, listOf(arc), picked)
        val layers = HorizonLabels.placeLayers(
            g, ranked, HorizonLabels.rank(aircraft), width, ascent, descent, rings, listOf(arc), picked,
            aircraftBlocked = g.compassBoxes({ 9.0 * 2.625 * it.length }, -14.0 * 2.625, 4.0 * 2.625),
        )
        val actual = "alone\n${sig(alone)}\nblocked\n${sig(withBlocks)}\npeaks\n${sig(layers.peaks)}\naircraft\n${sig(layers.aircraft)}"
        assertEquals(EXPECTED.trim(), actual.trim())
    }

    private companion object {
        const val EXPECTED = """
alone
Mount Summit 387.967,224.465 gap 2.0
Overhead Rock 526.795,607.696 gap 2.0
Steep Spire 596.513,323.839 gap 4.0
Near Knob 966.541,449.404 gap 2.0 leader 982.761,462.792
Crowd I 726.6,148.251 gap 6.693
Crowd M 783.425,293.085 gap 5.657 leader 839.337,260.063
Sparse 3 777.303,934.862 gap 3.578 leader 850.803,901.839
Sparse 2 820.221,802.812 gap 3.578 leader 880.695,816.2
Far Dome 165.273,827.92 gap 2.53 leader 120.657,794.898
Sparse 0 832.109,740.198 gap 3.578 leader 905.609,753.585
West Horn 203.977,621.924 gap 2.53 leader 121.946,635.311
Low Ridge 614.27,1022.947 gap 2.0
blocked
Crowd F 753.441,202.196 gap 6.693
Mount Summit 387.967,224.465 gap 2.0
Overhead Rock 526.795,607.696 gap 2.0
Steep Spire 596.513,323.839 gap 4.0
Near Knob 966.541,449.404 gap 2.0 leader 982.761,462.792
Crowd M 895.17,300.528 gap 5.657 leader 839.337,267.506
Crowd B 643.508,103.781 gap 5.933
Sparse 3 777.303,934.862 gap 3.578 leader 850.803,901.839
Sparse 2 761.699,750.118 gap 3.578 leader 835.199,763.506
Far Dome 228.246,709.347 gap 2.53 leader 154.746,722.734
West Horn 203.977,621.924 gap 2.53 leader 121.946,635.311
Low Ridge 614.27,1022.947 gap 2.0
peaks
Crowd F 753.441,202.196 gap 6.693
Mount Summit 387.967,224.465 gap 2.0
Overhead Rock 526.795,607.696 gap 2.0
Steep Spire 596.513,323.839 gap 4.0
Near Knob 966.541,449.404 gap 2.0 leader 982.761,462.792
Crowd M 895.17,300.528 gap 5.657 leader 839.337,267.506
Crowd B 643.508,103.781 gap 5.933
Sparse 3 777.303,934.862 gap 3.578 leader 850.803,901.839
Sparse 2 761.699,750.118 gap 3.578 leader 835.199,763.506
Far Dome 228.246,709.347 gap 2.53 leader 154.746,722.734
West Horn 203.977,621.924 gap 2.53 leader 121.946,635.311
Low Ridge 614.27,1022.947 gap 2.0
aircraft
FLT2 853.046,678.632 gap 2.0 leader 875.67,645.609
FLT3 > PDX 213.537,475.13 gap 2.0 leader 122.975,442.108
"""
    }
}
