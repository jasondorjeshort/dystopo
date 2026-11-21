package io.github.jasondorjeshort.dystopo.horizon

/**
 * T322: what [HorizonLabels.place] needs of a view to place names on it: where a mark falls, which way a name steps away
 * from it, the px per dp, and how far apart two marks are in the density gap's units. The round plot ([RimGeometry])
 * is one; the Panorama's lens ([LensView]) is the other.
 *
 * The density rule ([HorizonLabels.gapDeg], [HorizonLabels.LOCAL_DEG]) is tested against [separation], not against a
 * scale: a perspective lens has no single degrees-per-dp (at a 100 degree view its edge has about 2.4 times the centre's
 * px per degree), so the view says how far apart two marks stand.
 */
interface LabelProjection {
    /** px per dp. */
    val dp: Double

    /** Where a mark at [elDeg] above the horizontal (negative below) and true bearing [azDeg] falls (px, y down), or null when it is off the view. */
    fun mark(elDeg: Double, azDeg: Double): RimPt?

    /** The unit vector (px) a name steps along from the mark [at] of ([elDeg], [azDeg]): away from the mark toward the zenith. */
    fun away(at: RimPt, elDeg: Double, azDeg: Double): RimPt

    /** How far apart the marks of [a] and [b] stand, in the density gap's degrees; infinite when either is off the view. */
    fun separation(a: HorizonLabel, b: HorizonLabel): Double
}
