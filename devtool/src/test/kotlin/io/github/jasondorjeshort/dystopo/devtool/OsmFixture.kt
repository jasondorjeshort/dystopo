package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.geo.MercatorWindow
import io.github.jasondorjeshort.dystopo.geo.WebMercator
import io.github.jasondorjeshort.dystopo.mvt.Feature
import io.github.jasondorjeshort.dystopo.mvt.Geometry
import io.github.jasondorjeshort.dystopo.mvt.Layer
import io.github.jasondorjeshort.dystopo.mvt.Polygon
import io.github.jasondorjeshort.dystopo.mvt.TilePoint
import io.github.jasondorjeshort.dystopo.mvt.Value
import io.github.jasondorjeshort.dystopo.mvt.VectorTile
import io.github.jasondorjeshort.dystopo.mvt.signedDoubleArea
import io.github.jasondorjeshort.dystopo.pmtiles.PmtilesWriter
import io.github.jasondorjeshort.dystopo.pmtiles.TileType
import io.github.jasondorjeshort.dystopo.testing.SyntheticTerrain
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * A tiny outdoors-schema vector archive for an OSM golden (see `tilebuild/PROFILE.md`), built in
 * the test with the MVT encoder and the PMTiles writer, so style regressions are caught offline.
 *
 * Features are laid out in the golden's image pixels over the [SyntheticTerrain] it renders, so
 * they agree with the terrain: the lake polygon is traced from the terrain's flooded cells, the cliff
 * line runs along the terrain's cliff band (drawn north to south, so the OSM convention puts the
 * drop on its right, the west, which is also where the DEM is lower), and the peak carries the
 * terrain's elevation. The window sits on a corner of four z14 tiles and renders at z15, so the
 * archive exercises tile seams and overzoom. Every tile holds every feature (the renderer clips each
 * to its own tile), like a generous tile buffer.
 */
class OsmFixture(private val build: (OsmFixture.Canvas) -> Unit) {
    /** Image pixels to terrain meters and back for one golden case. */
    class Canvas(val case: GoldenCase) {
        private val cell = case.cellSizeMeters
        private val half = case.sizePx / 2.0
        fun px(xMeters: Double) = (xMeters - case.centerX) / cell + half
        fun py(yMeters: Double) = (yMeters - case.centerY) / cell + half
        fun mx(px: Double) = case.centerX + (px - half) * cell
        fun my(py: Double) = case.centerY + (py - half) * cell

        /** Elevation at an image pixel position, meters. */
        fun elevation(x: Double, y: Double): Double = case.terrain!!.elevationAt(mx(x), my(y))

        internal val features = LinkedHashMap<String, MutableList<Pair<Map<String, Value>, List<List<Pair<Double, Double>>>>>>()
        internal val kinds = HashMap<String, Char>()

        private fun add(layer: String, kind: Char, props: Map<String, Any>, parts: List<List<Pair<Double, Double>>>) {
            features.getOrPut(layer) { ArrayList() } += props.mapValues { (_, v) -> value(v) } to parts
            kinds["$layer#${features.getValue(layer).size - 1}"] = kind
        }

        fun point(layer: String, x: Double, y: Double, vararg props: Pair<String, Any>) = add(layer, 'p', props.toMap(), listOf(listOf(x to y)))
        fun line(layer: String, points: List<Pair<Double, Double>>, vararg props: Pair<String, Any>) = add(layer, 'l', props.toMap(), listOf(points))
        fun polygon(layer: String, ring: List<Pair<Double, Double>>, vararg props: Pair<String, Any>) = add(layer, 'a', props.toMap(), listOf(ring))

        private fun value(v: Any): Value = when (v) {
            is String -> Value.StringValue(v)
            is Int -> Value.IntValue(v.toLong())
            is Long -> Value.IntValue(v)
            is Double -> Value.DoubleValue(v)
            else -> throw IllegalArgumentException("unsupported property $v")
        }
    }

    /** Where the golden's image sits in Web Mercator: z15, top-left 100 px left of and 156 px above a z14 tile corner. */
    fun window(case: GoldenCase): MercatorWindow {
        require(case.zoom == 15) { "the OSM fixture is laid out for z15" }
        val z14x = floor(WebMercator.lonToX(0.5, 14)).toInt()
        val z14y = floor(WebMercator.latToY(45.0, 14)).toInt()
        return MercatorWindow(15, (z14x + 1) * 512 - 100, (z14y + 1) * 512 - 156, case.sizePx, case.sizePx)
    }

    private val files = HashMap<String, File>()

    /** The archive for [case], written once per test JVM to a temp file. */
    @Synchronized
    fun file(case: GoldenCase): File = files.getOrPut(case.name) {
        val canvas = Canvas(case).also(build)
        val window = window(case)
        val writer = PmtilesWriter(TileType.MVT)
        val tx0 = window.x0 / 512
        val ty0 = window.y0 / 512
        for (ty in ty0..(window.y0 + window.height - 1) / 512) for (tx in tx0..(window.x0 + window.width - 1) / 512) {
            // Image pixel -> tile units: 512 z15 pixels per z14 tile, 4096 units per tile.
            fun unit(x: Double, y: Double) = TilePoint(
                ((x + window.x0 - tx * 512) * 8).roundToInt(),
                ((y + window.y0 - ty * 512) * 8).roundToInt(),
            )
            val layers = canvas.features.map { (name, list) ->
                Layer(name, list.mapIndexed { i, (props, parts) ->
                    val geometry = when (canvas.kinds.getValue("$name#$i")) {
                        'p' -> Geometry.Points(parts.first().map { (x, y) -> unit(x, y) })
                        'l' -> Geometry.Lines(parts.map { part -> part.map { (x, y) -> unit(x, y) } })
                        else -> Geometry.Polygons(parts.map { part ->
                            val ring = part.map { (x, y) -> unit(x, y) }
                            Polygon(if (signedDoubleArea(ring) < 0) ring.reversed() else ring) // exterior: clockwise
                        })
                    }
                    Feature(null, props, geometry)
                })
            }
            writer.addTile(14, tx, ty, VectorTile(layers).encode())
        }
        val file = File.createTempFile("dystopo-golden-${case.name}-", ".pmtiles").apply { deleteOnExit() }
        file.outputStream().use { out -> writer.finish({ out.write(it) }, metadataJson = """{"name":"golden fixture"}""") }
        file
    }

    companion object {
        /**
         * Outdoors features over [SyntheticTerrain.LAKE_CLIFF]: trails of every difficulty class
         * (one faint and informal), a named cliff with the drop to the west, the lake, a park and a
         * wilderness boundary, a peak on the cliff top, and one POI of each common kind.
         */
        val LAKE_CLIFF = OsmFixture { c ->
            val terrain = c.case.terrain!!
            require(terrain === SyntheticTerrain.LAKE_CLIFF) { "laid out for the lake-cliff preset" }
            // The preset's landforms (SyntheticTerrain.LAKE_CLIFF).
            val lake = SyntheticTerrain.Lake(x = -700.0, y = 300.0, radius = 1300.0, depth = 80.0, waterDepth = 37.0)
            val cliff = SyntheticTerrain.Cliff(x = 450.0, y = 0.0, dx = 0.2, dy = 1.0, height = 220.0, run = 20.0, halfLength = 1800.0)
            // The lake, traced from the terrain: along rays from its center to the last flooded sample.
            val level = terrain.lakeLevels.first()
            val ring = (0 until 72).map { k ->
                val a = 2 * PI * k / 72
                var r = 0.0
                while (r < lake.radius && terrain.elevationAt(lake.x + (r + 5) * cos(a), lake.y + (r + 5) * sin(a)) <= level + 1e-6) r += 5
                c.px(lake.x + r * cos(a)) to c.py(lake.y + r * sin(a))
            }
            c.polygon("water", ring, "class" to "lake")

            // The cliff band's middle line, north to south (the drop is on the right, west).
            fun cliffAt(py: Double): Pair<Double, Double> {
                val ym = c.my(py)
                return c.px(cliff.x + cliff.dx / cliff.dy * (ym - cliff.y)) to py
            }
            c.line("natural_line", listOf(cliffAt(-8.0), cliffAt(80.0), cliffAt(170.0), cliffAt(264.0)), "class" to "cliff", "name" to "Long Wall")

            c.polygon("park", listOf(-50.0 to -50.0, 300.0 to -50.0, 300.0 to 236.0, -50.0 to 236.0), "class" to "national_park", "protect_class" to "2")
            c.polygon("park", listOf(214.0 to -50.0, 400.0 to -50.0, 400.0 to 400.0, 214.0 to 400.0), "class" to "wilderness", "protect_class" to "1b")

            fun tr(vararg props: Pair<String, Any>) = arrayOf("class" to "path", "subclass" to "path", *props)
            val lakeTrail = listOf(0.0 to 118.0, 60.0 to 126.0, 120.0 to 120.0, 165.0 to 132.0, 178.0 to 190.0, 185.0 to 264.0)
            c.line("transportation", lakeTrail, *tr("sac_scale" to "hiking", "name" to "Lake Trail"))
            c.line("transportation_name", lakeTrail, "class" to "path", "name" to "Lake Trail")
            c.line("transportation", listOf(165.0 to 132.0, 205.0 to 105.0, 264.0 to 92.0), *tr("sac_scale" to "mountain_hiking"))
            c.line("transportation", listOf(212.0 to 264.0, 228.0 to 200.0, 242.0 to 150.0, 264.0 to 140.0), *tr("sac_scale" to "demanding_mountain_hiking"))
            c.line("transportation", listOf(205.0 to 105.0, 214.0 to 60.0, 240.0 to 20.0, 264.0 to -8.0), *tr("sac_scale" to "alpine_hiking", "trail_visibility" to "bad"))
            c.line("transportation", listOf(60.0 to 126.0, 72.0 to 60.0, 92.0 to -8.0), *tr("informal" to 1))

            c.point("mountain_peak", 236.0, 46.0, "class" to "peak", "name" to "Rim Peak", "ele" to c.elevation(236.0, 46.0).roundToLong(), "rank" to 1)
            c.point("outdoor_poi", 26.0, 122.0, "class" to "trailhead")
            c.point("outdoor_poi", 16.0, 96.0, "class" to "parking", "subclass" to "surface")
            c.point("outdoor_poi", 96.0, 196.0, "class" to "campsite", "subclass" to "camp_site", "name" to "Lake Camp")
            c.point("outdoor_poi", 132.0, 168.0, "class" to "spring")
            c.point("outdoor_poi", 238.0, 118.0, "class" to "viewpoint", "name" to "Rim View")
            c.point("outdoor_poi", 40.0, 30.0, "class" to "hut", "subclass" to "wilderness_hut", "name" to "Old Hut")
            c.point("outdoor_poi", 181.0, 222.0, "class" to "waterfall")
        }
    }
}
