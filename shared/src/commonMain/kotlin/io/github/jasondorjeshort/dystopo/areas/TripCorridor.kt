package io.github.jasondorjeshort.dystopo.areas

/**
 * T130: the corridor a trip keeps offline, the one shape behind both the automatic corridor around every trip and the
 * trip card's Offline button (T75, T95): the route's line simplified within a tenth of the width and the width grown by as
 * much, so the corridor still holds the whole line; zooms 8 to 15, or 8 to 13 for a drive (T91, [maxZoom]).
 *
 * The width is the user's setting (`tripCorridorM` on the phone), [DEFAULT_WIDTH_M] each side unless changed (the owner's
 * answer of 2026-10-23); T75's manual download used [AreaOfInterest.DEFAULT_BUFFER_M], 500 m. 0 turns the automatic
 * corridor off. Pure; the manifest of the result is [AreaManifest.tileItems] as for any area.
 */
object TripCorridor {
    /** The owner's default: 1 km each side of the route. */
    const val DEFAULT_WIDTH_M = 1000

    /** The widths the Offline areas sheet offers (0: off). Any whole number in [WIDTH_RANGE] or 0 through `POST /settings`. */
    val WIDTH_CHOICES_M = listOf(0, 250, 500, 1000, 2000, 5000)

    /** The range of a width other than 0 (off), metres. */
    val WIDTH_RANGE = 100..10_000

    const val MIN_ZOOM = 8

    /** The deepest zoom a trip's corridor fetches: z13 for a drive (seen from a car), z15 for a walk or a ride. */
    fun maxZoom(type: String?): Int = if (type.equals("drive", ignoreCase = true)) 13 else 15

    /** Whether [m] is a width the setting takes: 0 (off) or within [WIDTH_RANGE]. */
    fun validWidth(m: Int): Boolean = m == 0 || m in WIDTH_RANGE

    /** The width a corridor made by [area] was asked for, from its buffer (the width plus a tenth), to the metre. */
    fun widthOf(bufferM: Double): Int = kotlin.math.round(bufferM / 1.1).toInt()

    /**
     * The corridor of a route through [points] in mode [type], [widthM] each side, named [name]; [minZoom] and [maxZoom]
     * override the mode's zooms. The same route and width always give the same [AreaOfInterest.id].
     */
    fun area(points: List<AreaPoint>, type: String?, widthM: Double, name: String?, minZoom: Int? = null, maxZoom: Int? = null): AreaOfInterest {
        require(points.isNotEmpty()) { "a route with no points has no corridor" }
        val tol = widthM / 10
        val line = Polyline.simplify(points, tol)
        return AreaOfInterest(AreaShape.Corridor(line, widthM + tol), minZoom ?: MIN_ZOOM, maxZoom ?: maxZoom(type), name)
    }
}
