package io.github.jasondorjeshort.dystopo.model

import io.github.jasondorjeshort.dystopo.gpx.Gpx
import io.github.jasondorjeshort.dystopo.gpx.GpxDocument
import io.github.jasondorjeshort.dystopo.gpx.GpxExtensions
import io.github.jasondorjeshort.dystopo.gpx.GpxMetadata

/**
 * A [RoutePlan] as GPX and back (T60). Export: one `rte` whose `rtept`s are the whole path (any GPS
 * or app can follow it), the route's waypoints as `wpt`, our ids (`dystopo:id`, T40) on the route and
 * every waypoint, plus enough to rebuild the plan exactly: `<dystopo:mode>` in the route's extensions
 * and `<dystopo:via>kind</dystopo:via>` in the extensions of each `rtept` that is a via point (the
 * kind of the leg that starts there; empty on the last). Import of our own export gives back the same
 * plan (ids, via points, legs and their kinds); a route from elsewhere has no via marks, so its points
 * become via points joined by [LegKind.FIXED] legs when there are at most [MAX_IMPORT_VIA] of them (a
 * GPS route is usually a few turn points), else one fixed leg from the first to the last.
 */
object RouteGpx {
    const val MAX_IMPORT_VIA = 50

    private const val P = GpxExtensions.DYSTOPO_PREFIX
    private val modeElement = Regex("""<$P:mode>\s*([A-Za-z]+)\s*</$P:mode>""")
    private val viaElement = Regex("""<$P:via>\s*([a-z]*)\s*</$P:via>|<$P:via\s*/>""")

    fun document(plan: RoutePlan): GpxDocument {
        val path = plan.path
        val pts = path.points
        val viaAt = path.viaIndexes.withIndex().associate { (i, k) -> k to i }
        val marked = pts.mapIndexed { k, p ->
            val v = viaAt[k] ?: return@mapIndexed p
            val kind = path.legs.getOrNull(v)?.kind?.label ?: ""
            p.copy(extensions = "<$P:via>$kind</$P:via>")
        }
        val ext = "<$P:mode>${path.mode}</$P:mode>" + (plan.extensions ?: "")
        val route = Route(plan.id, plan.name, marked, plan.description, plan.comment, plan.type, ext)
        return GpxDocument(waypoints = plan.waypoints, routes = listOf(route), metadata = GpxMetadata(name = plan.name))
    }

    fun gpx(plan: RoutePlan): String = Gpx.write(document(plan))

    /** A plan from an imported `rte` (our export or another app's) with [waypoints]. */
    fun plan(route: Route, waypoints: List<Waypoint> = emptyList()): RoutePlan {
        var ext = route.extensions
        val mode = ext?.let { modeElement.find(it) }?.let { m ->
            ext = ext!!.removeRange(m.range).trim().ifEmpty { null }
            m.groupValues[1].lowercase()
        } ?: modeOfType(route.type) ?: "hike"
        val pts = route.points
        val marks = pts.map { p -> p.extensions?.let { viaElement.find(it) } }
        val path = if (pts.size >= 2 && marks.first() != null && marks.last() != null) {
            val via = ArrayList<ViaPoint>()
            val legs = ArrayList<RouteLeg>()
            var start = -1
            var kind: LegKind? = null
            for ((k, m) in marks.withIndex()) {
                if (m == null) continue
                val p = pts[k]
                via += ViaPoint(p.lat, p.lon)
                if (start >= 0) legs += RouteLeg(kind ?: LegKind.FIXED, pts.subList(start, k + 1).map { RoutePoint(it.lat, it.lon, it.ele) })
                start = k
                kind = LegKind.ofLabel(m.groupValues.getOrNull(1)?.takeIf { it.isNotEmpty() })
            }
            RoutePath(mode, via, legs).quantized()
        } else if (pts.size in 2..MAX_IMPORT_VIA) {
            RoutePath(
                mode, pts.map { ViaPoint(it.lat, it.lon) },
                pts.zipWithNext { a, b -> RouteLeg(LegKind.FIXED, listOf(RoutePoint(a.lat, a.lon, a.ele), RoutePoint(b.lat, b.lon, b.ele))) },
            ).quantized()
        } else {
            RoutePath.fixed(pts, mode)
        }
        return RoutePlan(route.id, route.name, path, waypoints, route.description, route.comment, route.type, ext)
    }

    private fun modeOfType(type: String?): String? = type?.lowercase()?.takeIf { it == "hike" || it == "bike" }
}
