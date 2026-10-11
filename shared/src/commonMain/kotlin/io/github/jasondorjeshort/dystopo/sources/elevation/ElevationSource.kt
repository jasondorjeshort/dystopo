package io.github.jasondorjeshort.dystopo.sources.elevation

import io.github.jasondorjeshort.dystopo.dem.ElevationGrid
import io.github.jasondorjeshort.dystopo.geo.MercatorWindow
import io.github.jasondorjeshort.dystopo.sources.SourceRegistry

/**
 * Something that can fill a Web Mercator window with elevations (meters, EGM2008). T80: the readers live in
 * shared code, so the server, the devtool and the phone open the same Cloud-Optimized GeoTIFFs and compose
 * `best` the same way; the server keeps its old names as type aliases (`serverlib/.../ElevationSource.kt`).
 */
interface ElevationSource {
    val id: String

    /** Data source registry ids (`sources.json`) this source can draw on, including geoids used for datum conversion. */
    val registryIds: List<String>

    /** Credit for everything this source can draw on, composed from the registry. */
    val attribution: String get() = SourceRegistry.builtIn.attribution(registryIds)

    /**
     * [counters] carries source-specific numbers for the JSON report (tiles fetched, nodata cells...).
     * [provenance] lists the registry ids that actually contributed data to [grid], in registry order.
     * It is required, and validated: every id must be in the registry, and a grid with any data must
     * name at least one id (an empty list would silently fall back to crediting everything). Empty is
     * only valid for an all-NaN grid; [ElevationSource.provenanceIfData] builds the usual value.
     */
    data class Result(val grid: ElevationGrid, val counters: Map<String, Long>, val provenance: List<String>) {
        init {
            for (id in provenance) require(id in SourceRegistry.builtIn) { "provenance id '$id' is not in sources.json" }
            require(provenance.isNotEmpty() || grid.values.all { it.isNaN() }) {
                "result has elevation data but empty provenance; set it to the registry ids that contributed"
            }
        }

        /** Credit for what contributed; falls back to [source]'s full credit when nothing did. */
        fun attribution(source: ElevationSource): String =
            if (provenance.isEmpty()) source.attribution else SourceRegistry.builtIn.attribution(provenance)
    }

    fun fetch(window: MercatorWindow): Result

    companion object {
        val ids = listOf("best", "copernicus", "terrarium", "3dep", "3dep-1m", "3dep-best", "swissalti3d", "kartverket", "bev", "hrdem", "linz")

        fun byId(id: String): ElevationSource = when (id) {
            "best" -> BestSource()
            "copernicus" -> CopernicusSource()
            "terrarium" -> TerrariumSource()
            "3dep" -> ThreeDepSource()
            "3dep-1m" -> LidarSource()
            "3dep-best" -> ThreeDepBestSource()
            "swissalti3d" -> SwissAltiSource()
            "kartverket" -> NorwaySource()
            "bev" -> AustriaSource()
            "hrdem" -> CanadaSource()
            "linz" -> NewZealandSource()
            else -> throw IllegalArgumentException("unknown source '$id'; known: ${ids.joinToString()}")
        }

        /** [ids] if [grid] has any data, else nothing: provenance for a single-dataset source. */
        fun provenanceIfData(grid: ElevationGrid, ids: List<String>): List<String> =
            if (grid.values.any { !it.isNaN() }) ids else emptyList()

        /** Union of provenance lists, deduplicated in registry order. */
        fun mergeProvenance(vararg lists: List<String>): List<String> = SourceRegistry.builtIn.ordered(lists.flatMap { it })
    }
}

/**
 * A source that blends layers at coverage edges. The blend band is sized in cells from a ground
 * cell size; [ElevationSource.fetch] uses the window's own. Tile exports pass one reference cell
 * size for a whole zoom level (see `TileWindows.referenceCellSizeMeters`) so adjacent tiles, whose
 * windows sit at slightly different latitudes, weight the same cell identically.
 */
interface FeatheredSource : ElevationSource {
    fun fetch(window: MercatorWindow, featherCellSizeMeters: Double): ElevationSource.Result

    override fun fetch(window: MercatorWindow): ElevationSource.Result = fetch(window, window.cellSizeMeters)
}

/** Math.round for the counters: nearest, ties up. */
internal fun roundL(x: Double): Long = kotlin.math.floor(x + 0.5).toLong()

/** Milliseconds since [startedNanos] ([nanoTime]). */
internal fun msSince(startedNanos: Long): Long = (nanoTime() - startedNanos) / 1_000_000
