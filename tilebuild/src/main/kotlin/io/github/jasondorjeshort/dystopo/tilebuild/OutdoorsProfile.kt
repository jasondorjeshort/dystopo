package io.github.jasondorjeshort.dystopo.tilebuild

import com.onthegomap.planetiler.FeatureCollector
import com.onthegomap.planetiler.FeatureMerge
import com.onthegomap.planetiler.Profile
import com.onthegomap.planetiler.VectorTile
import com.onthegomap.planetiler.reader.SourceFeature
import com.onthegomap.planetiler.reader.osm.OsmElement
import com.onthegomap.planetiler.reader.osm.OsmRelationInfo
import com.onthegomap.planetiler.util.Parse
import kotlin.math.roundToInt

/**
 * Dystopo's backcountry vector tile profile (see `tilebuild/PROFILE.md` and [OutdoorsSchema]).
 *
 * Layer names and class values follow OpenMapTiles wherever the data is the same (water, landcover,
 * transportation, place, mountain_peak, ...), so a style written for OpenMapTiles tiles keeps working;
 * trails carry extra attributes (`sac_scale`, `trail_visibility`, `surface_detail`, hiking routes, ...)
 * and backcountry points of interest get their own `outdoor_poi` layer. Written against Planetiler's
 * [Profile] API; no code from the OpenMapTiles profile.
 */
class OutdoorsProfile(
    private val extract: String,
    private val extra: Map<String, String>,
    /** Zoom from which features are written at full detail (no merging); Planetiler's max zoom. */
    private val maxZoom: Int = 14,
) : Profile {

    companion object {
        const val OSM_SOURCE = "osm"
        const val WATER_POLYGON_SOURCE = "water_polygons"
        const val NATURAL_EARTH_SOURCE = "natural_earth"

        /** Lowest zoom at which OSM inland water and the water-polygon ocean are used; Natural Earth below. */
        const val OSM_WATER_MIN_ZOOM = 6
        const val OSM_BOUNDARY_MIN_ZOOM = 5
        const val TRAIL_MIN_ZOOM = 10

        private const val LABEL_BUFFER = 64.0

        /**
         * Below this zoom, point labels are thinned per tile: the tile (256 px) is cut into square cells and
         * only the best few points per cell are kept (peaks by ele, places by class). Planetiler's own label
         * grid did not limit points in practice here, so the thinning is done in post-processing.
         */
        const val LABEL_THIN_BELOW_ZOOM = 13
        const val PEAK_CELL_PX = 64.0
        const val PEAKS_PER_CELL = 2
        const val PLACE_CELL_PX = 128.0
        const val PLACES_PER_CELL = 4
        private val PLACE_ORDER = listOf(
            "country", "state", "city", "town", "village", "suburb", "hamlet", "locality", "isolated_dwelling",
        ).withIndex().associate { (i, c) -> c to i }

        /** Hiking route networks, most important first. */
        private val ROUTE_NETWORKS = listOf("iwn", "nwn", "rwn", "lwn")

        // highway=* -> transportation class, min zoom.
        private val ROADS = mapOf(
            "motorway" to ("motorway" to 4), "motorway_link" to ("motorway" to 9),
            "trunk" to ("trunk" to 5), "trunk_link" to ("trunk" to 9),
            "primary" to ("primary" to 7), "primary_link" to ("primary" to 11),
            "secondary" to ("secondary" to 9), "secondary_link" to ("secondary" to 11),
            "tertiary" to ("tertiary" to 11), "tertiary_link" to ("tertiary" to 12),
            "unclassified" to ("minor" to 12), "residential" to ("minor" to 12),
            "living_street" to ("minor" to 13), "road" to ("minor" to 13),
            "service" to ("service" to 13),
        )
        private val TRAILS = setOf("path", "footway", "cycleway", "bridleway", "steps", "via_ferrata", "pedestrian")
        private val SAC_SCALES = setOf(
            "hiking", "mountain_hiking", "demanding_mountain_hiking",
            "alpine_hiking", "demanding_alpine_hiking", "difficult_alpine_hiking",
        )
        private val PAVED = setOf(
            "paved", "asphalt", "concrete", "concrete:plates", "concrete:lanes", "paving_stones", "sett",
            "cobblestone", "unhewn_cobblestone", "metal", "wood", "bricks", "chipseal",
        )
        private val UNPAVED = setOf(
            "unpaved", "gravel", "fine_gravel", "compacted", "dirt", "earth", "ground", "grass", "grass_paver",
            "mud", "sand", "rock", "pebblestone", "woodchips", "snow", "ice", "salt", "stepping_stones",
        )

        private val LANDCOVER_NATURAL = mapOf(
            "glacier" to "ice",
            "bare_rock" to "rock", "scree" to "rock", "shingle" to "rock", "rock" to "rock",
            "wood" to "wood",
            "grassland" to "grass", "heath" to "grass", "scrub" to "grass", "fell" to "grass", "tundra" to "grass",
            "wetland" to "wetland",
            "sand" to "sand", "beach" to "sand", "dune" to "sand",
        )
        private val LANDCOVER_LANDUSE = mapOf(
            "forest" to "wood", "meadow" to "grass", "grass" to "grass",
            "farmland" to "farmland", "orchard" to "farmland", "vineyard" to "farmland",
        )
        private val LANDUSE = mapOf(
            "residential" to "residential", "commercial" to "commercial", "industrial" to "industrial",
            "retail" to "retail", "quarry" to "quarry", "cemetery" to "cemetery", "military" to "military",
        )
        private val PLACES = mapOf(
            "country" to 2, "state" to 4, "city" to 5, "town" to 7, "village" to 10,
            "suburb" to 12, "hamlet" to 11, "locality" to 12, "isolated_dwelling" to 13,
        )

        /** Natural Earth tables used below [OSM_WATER_MIN_ZOOM], with the zoom range each one covers. */
        private val NATURAL_EARTH = mapOf(
            "ne_110m_ocean" to Triple("water", 0, 1), "ne_50m_ocean" to Triple("water", 2, 3),
            "ne_10m_ocean" to Triple("water", 4, 5),
            "ne_110m_lakes" to Triple("water", 0, 1), "ne_50m_lakes" to Triple("water", 2, 3),
            "ne_10m_lakes" to Triple("water", 4, 5),
            "ne_110m_admin_0_boundary_lines_land" to Triple("boundary", 0, 1),
            "ne_50m_admin_0_boundary_lines_land" to Triple("boundary", 2, 3),
            "ne_10m_admin_0_boundary_lines_land" to Triple("boundary", 4, 4),
            "ne_10m_admin_1_states_provinces_lines" to Triple("boundary", 4, 4),
        )

        fun elevationMeters(value: Any?): Double? = runCatching { Parse.meters(value) }.getOrNull()
    }

    /** A hiking/foot route relation, attached to its member ways. */
    data class RouteInfo(val relationId: Long, val name: String?, val ref: String?, val network: String?) : OsmRelationInfo {
        override fun id(): Long = relationId
    }

    /** An administrative boundary relation, attached to its member ways. */
    data class AdminInfo(val relationId: Long, val adminLevel: Int) : OsmRelationInfo {
        override fun id(): Long = relationId
    }

    override fun name(): String = OutdoorsSchema.NAME
    override fun version(): String = OutdoorsSchema.VERSION
    override fun attribution(): String = Attribution.HTML
    override fun description(): String =
        "Dystopo outdoors vector tiles (trails, land cover, peaks, backcountry POIs) of OpenStreetMap extract '$extract', " +
            "built by Dystopo tilebuild with Planetiler. Data © OpenStreetMap contributors, ODbL 1.0."

    override fun extraArchiveMetadata(): Map<String, String> = extra + ("dystopo:layers" to OutdoorsSchema.metadataJson())

    override fun preprocessOsmRelation(relation: OsmElement.Relation): List<OsmRelationInfo>? {
        if (relation.hasTag("type", "route") && relation.hasTag("route", "hiking", "foot")) {
            return listOf(
                RouteInfo(
                    relation.id(),
                    relation.getString("name"),
                    relation.getString("ref"),
                    relation.getString("network")?.takeIf { it in ROUTE_NETWORKS },
                ),
            )
        }
        if (relation.hasTag("type", "boundary") && relation.hasTag("boundary", "administrative")) {
            val level = Parse.parseIntOrNull(relation.getTag("admin_level")) ?: return null
            if (level == 2 || level == 4) return listOf(AdminInfo(relation.id(), level))
        }
        return null
    }

    override fun processFeature(feature: SourceFeature, features: FeatureCollector) {
        when (feature.source) {
            OSM_SOURCE -> processOsm(feature, features)
            WATER_POLYGON_SOURCE -> if (feature.canBePolygon()) {
                features.polygon("water").setAttr("class", "ocean").setMinZoom(OSM_WATER_MIN_ZOOM)
            }
            NATURAL_EARTH_SOURCE -> processNaturalEarth(feature, features)
        }
    }

    private fun processNaturalEarth(feature: SourceFeature, features: FeatureCollector) {
        val table = feature.sourceLayer ?: return
        val (layer, minZoom, maxZoom) = NATURAL_EARTH[table] ?: return
        if (layer == "water" && feature.canBePolygon()) {
            features.polygon("water").setAttr("class", if ("ocean" in table) "ocean" else "lake")
                .setZoomRange(minZoom, maxZoom).setMinPixelSize(2.0)
        } else if (layer == "boundary" && feature.canBeLine()) {
            features.line("boundary").setAttr("admin_level", if ("admin_0" in table) 2 else 4)
                .setZoomRange(minZoom, maxZoom)
        }
    }

    private fun processOsm(f: SourceFeature, features: FeatureCollector) {
        if (f.isPoint) {
            peak(f, features)
            place(f, features)
            outdoorPoi(f, features)
            return
        }
        if (f.canBeLine()) {
            highway(f, features)
            railwayFerryAerialway(f, features)
            waterway(f, features)
            adminBoundary(f, features)
            naturalLine(f, features)
        }
        if (f.canBePolygon() && !f.hasTag("boundary", "administrative")) {
            water(f, features)
            landcover(f, features)
            landuse(f, features)
            park(f, features)
            outdoorPoi(f, features)
            place(f, features)
        }
    }

    // ---- lines ----

    private fun highway(f: SourceFeature, features: FeatureCollector) {
        val highway = f.getString("highway") ?: return
        if (f.hasTag("area", "yes")) return
        val road = ROADS[highway]
        val isTrack = highway == "track"
        val isTrail = highway in TRAILS
        if (road == null && !isTrack && !isTrail) return

        val cls: String
        var minZoom: Int
        var subclass: String? = null
        when {
            road != null -> { cls = road.first; minZoom = road.second }
            isTrack -> { cls = "track"; minZoom = TRAIL_MIN_ZOOM }
            else -> {
                cls = "path"
                subclass = if (highway == "pedestrian") "footway" else highway
                minZoom = TRAIL_MIN_ZOOM
                if (f.hasTag("footway", "sidewalk", "crossing") || f.hasTag("cycleway", "crossing") || highway == "pedestrian") {
                    minZoom = 14
                }
                if (highway == "steps") minZoom = 13
            }
        }
        if (cls == "service" && f.hasTag("service", "parking_aisle", "driveway", "drive-through")) minZoom = 14
        minZoom = minZoom.coerceAtMost(maxZoom)

        val line = features.line("transportation")
            .setAttr("class", cls)
            .setMinZoom(minZoom)
            .setSortKey(roadSortKey(cls))
            .setMinPixelSize(0.0)
            .setPixelTolerance(if (cls == "path" || cls == "track") 0.3 else 0.5)
        if (subclass != null) line.setAttr("subclass", subclass)
        if (highway.endsWith("_link")) line.setAttr("ramp", 1)
        brunnel(f)?.let { line.setAttrWithMinzoom("brunnel", it, 12) }
        surfaceClass(f.getString("surface"))?.let { line.setAttrWithMinzoom("surface", it, 12) }

        if (isTrack || isTrail) {
            f.getString("sac_scale")?.takeIf { it in SAC_SCALES }?.let { line.setAttr("sac_scale", it) }
            f.getString("trail_visibility")?.let { line.setAttrWithMinzoom("trail_visibility", it, 12) }
            if (f.hasTag("informal", "yes")) line.setAttrWithMinzoom("informal", 1, 12)
            f.getString("tracktype")?.takeIf { isTrack }?.let { line.setAttrWithMinzoom("tracktype", it, 12) }
            accessRestriction(f)?.let { line.setAttrWithMinzoom("access", it, 12) }
            f.getString("surface")?.let { line.setAttrWithMinzoom("surface_detail", it, 13) }
            f.getString("mtb:scale")?.let { line.setAttrWithMinzoom("mtb_scale", it, 13) }
            f.getString("operator")?.let { line.setAttrWithMinzoom("operator", it, 13) }
            f.getString("name")?.let { line.setAttrWithMinzoom("name", it, 13) }
            f.getString("ref")?.let { line.setAttrWithMinzoom("ref", it, 13) }
            val routes = f.relationInfo(RouteInfo::class.java).map { it.relation() }
            if (routes.isNotEmpty()) {
                routes.mapNotNull { it.network }.minByOrNull { ROUTE_NETWORKS.indexOf(it) }
                    ?.let { line.setAttr("route_network", it) }
                val names = routes.mapNotNull { it.name ?: it.ref }.distinct().sorted()
                if (names.isNotEmpty()) line.setAttrWithMinzoom("route_name", names.joinToString(";"), 12)
            }
        }

        val name = f.getString("name")
        val ref = f.getString("ref")
        if (name != null || (ref != null && road != null)) {
            val nameMinZoom = when (cls) {
                "motorway", "trunk" -> 8
                "primary" -> 10
                "secondary" -> 11
                "tertiary", "path", "track" -> 12
                "minor" -> 13
                else -> 14
            }.coerceAtLeast(minZoom).coerceAtMost(maxZoom)
            val label = features.line("transportation_name")
                .setAttr("class", cls)
                .setMinZoom(nameMinZoom)
                .setMinPixelSize(0.0)
                .setBufferPixels(8.0)
            if (subclass != null) label.setAttr("subclass", subclass)
            names(f, label)
            if (ref != null) label.setAttr("ref", ref)
        }
    }

    private fun railwayFerryAerialway(f: SourceFeature, features: FeatureCollector) {
        val railway = f.getString("railway")
        if (railway in setOf("rail", "narrow_gauge", "light_rail")) {
            val minor = f.hasTag("service", "yard", "siding", "spur", "crossover")
            features.line("transportation").setAttr("class", "rail").setAttr("subclass", railway)
                .setMinZoom(if (minor) 13 else if (railway == "rail") 9 else 11)
                .setSortKey(roadSortKey("rail"))
                .also { line -> brunnel(f)?.let { line.setAttrWithMinzoom("brunnel", it, 12) } }
        }
        if (f.hasTag("route", "ferry")) {
            features.line("transportation").setAttr("class", "ferry").setMinZoom(8).setSortKey(roadSortKey("ferry"))
        }
        val aerialway = f.getString("aerialway")
        if (aerialway != null && aerialway !in setOf("pylon", "station", "goods")) {
            features.line("transportation").setAttr("class", "aerialway").setAttr("subclass", aerialway)
                .setMinZoom(12).setSortKey(roadSortKey("aerialway"))
        }
    }

    private fun waterway(f: SourceFeature, features: FeatureCollector) {
        val waterway = f.getString("waterway") ?: return
        val minZoom = when (waterway) {
            "river" -> 8
            "canal" -> 11
            "stream" -> 12
            "ditch", "drain" -> 13
            else -> return
        }
        val line = features.line("waterway").setAttr("class", waterway).setMinZoom(minZoom).setMinPixelSize(0.0)
        f.getString("name")?.let { line.setAttr("name", it) }
        if (intermittent(f)) line.setAttr("intermittent", 1)
        brunnel(f)?.takeIf { it != "ford" }?.let { line.setAttrWithMinzoom("brunnel", it, 12) }
    }

    /** Cliffs, ridges and aretes as lines, plus a label point in mountain_peak for named ones (as OpenMapTiles has). */
    private fun naturalLine(f: SourceFeature, features: FeatureCollector) {
        val natural = f.getString("natural")
        if (natural !in setOf("cliff", "ridge", "arete")) return
        val named = f.hasTag("name")
        val line = features.line("natural_line").setAttr("class", natural)
            .setMinZoom(if (natural == "cliff" || named) 12 else 13).setMinPixelSize(0.0)
        names(f, line)
        if (named) {
            val label = features.lineMidpoint("mountain_peak").setAttr("class", natural).setMinZoom(13)
                .setBufferPixels(LABEL_BUFFER)
            names(f, label)
        }
    }

    private fun adminBoundary(f: SourceFeature, features: FeatureCollector) {
        val admin = f.relationInfo(AdminInfo::class.java)
        if (admin.isEmpty()) return
        val level = admin.minOf { it.relation().adminLevel }
        val line = features.line("boundary").setAttr("admin_level", level)
            .setMinZoom(OSM_BOUNDARY_MIN_ZOOM).setMinPixelSize(0.0)
        if (f.hasTag("maritime", "yes") || f.hasTag("natural", "coastline")) line.setAttr("maritime", 1)
    }

    // ---- polygons ----

    private fun water(f: SourceFeature, features: FeatureCollector) {
        val cls = when {
            f.hasTag("natural", "water") -> when (f.getString("water")) {
                "river", "canal", "stream", "oxbow" -> "river"
                "pond" -> "pond"
                else -> "lake"
            }
            f.hasTag("waterway", "riverbank") -> "river"
            f.hasTag("landuse", "reservoir", "basin") -> "lake"
            else -> return
        }
        val poly = features.polygon("water").setAttr("class", cls).setMinZoom(OSM_WATER_MIN_ZOOM).setMinPixelSize(2.0)
        if (intermittent(f)) poly.setAttr("intermittent", 1)
        if (f.hasTag("name") && cls != "river") {
            val labelZoom = features.getMinZoomForPixelSize(48.0).coerceIn(9, maxZoom)
            val label = features.innermostPoint("water_name").setAttr("class", cls)
                .setMinZoom(labelZoom).setBufferPixels(LABEL_BUFFER)
            names(f, label)
        }
    }

    private fun landcover(f: SourceFeature, features: FeatureCollector) {
        val natural = f.getString("natural")
        val landuse = f.getString("landuse")
        val (cls, subclass) = when {
            natural != null && natural in LANDCOVER_NATURAL -> {
                val sub = if (natural == "wetland") f.getString("wetland") ?: "wetland" else natural
                LANDCOVER_NATURAL.getValue(natural) to sub
            }
            landuse != null && landuse in LANDCOVER_LANDUSE -> LANDCOVER_LANDUSE.getValue(landuse) to landuse
            else -> return
        }
        features.polygon("landcover").setAttr("class", cls).setAttr("subclass", subclass)
            .setMinZoom(if (cls == "ice" || cls == "rock") 7 else 8)
            .setMinPixelSize(2.0)
            .setPixelTolerance(0.5)
    }

    private fun landuse(f: SourceFeature, features: FeatureCollector) {
        val cls = LANDUSE[f.getString("landuse")] ?: (if (f.hasTag("leisure", "park")) "park" else return)
        features.polygon("landuse").setAttr("class", cls).setMinZoom(10).setMinPixelSize(2.0).setPixelTolerance(0.5)
    }

    private fun park(f: SourceFeature, features: FeatureCollector) {
        val protectClass = f.getString("protect_class")
        val cls = when {
            f.hasTag("boundary", "national_park") -> "national_park"
            f.hasTag("boundary", "protected_area") -> if (protectClass == "1b") "wilderness" else "protected_area"
            f.hasTag("leisure", "nature_reserve") -> "nature_reserve"
            else -> return
        }
        val poly = features.polygon("park").setAttr("class", cls).setMinZoom(6).setMinPixelSize(2.0).setPixelTolerance(0.5)
        if (protectClass != null) poly.setAttr("protect_class", protectClass)
        if (f.hasTag("name")) {
            val labelZoom = features.getMinZoomForPixelSize(64.0).coerceIn(6, maxZoom)
            val label = features.pointOnSurface("park").setAttr("class", cls)
                .setMinZoom(labelZoom).setBufferPixels(LABEL_BUFFER)
            if (protectClass != null) label.setAttr("protect_class", protectClass)
            names(f, label)
            f.getString("operator")?.let { label.setAttr("operator", it) }
        }
    }

    // ---- points ----

    private fun peak(f: SourceFeature, features: FeatureCollector) {
        val cls = when {
            f.hasTag("natural", "peak", "volcano", "saddle") -> f.getString("natural")
            f.hasTag("mountain_pass", "yes") -> "pass"
            else -> return
        }
        val ele = elevationMeters(f.getTag("ele"))
        val named = f.hasTag("name")
        val minZoom = when {
            named && ele != null && (cls == "peak" || cls == "volcano") -> 9
            named -> 11
            else -> 13
        }
        val point = features.point("mountain_peak").setAttr("class", cls).setMinZoom(minZoom)
            .setBufferPixels(LABEL_BUFFER)
            .setSortKeyDescending(ele?.roundToInt()?.coerceIn(0, 9000) ?: 0)
        names(f, point)
        if (ele != null) {
            point.setAttr("ele", ele.roundToInt())
            point.setAttr("ele_ft", (ele / 0.3048).roundToInt())
        }
        elevationMeters(f.getTag("prominence"))?.let { point.setAttr("prominence", it.roundToInt()) }
    }

    private fun place(f: SourceFeature, features: FeatureCollector) {
        val place = f.getString("place") ?: return
        val minZoom = PLACES[place] ?: return
        if (!f.hasTag("name")) return
        val population = Parse.parseLongOrNull(f.getTag("population")) ?: 0L
        val point = (if (f.isPoint) features.point("place") else features.pointOnSurface("place")).setAttr("class", place)
            .setMinZoom(if (place == "city" && population < 100_000) 7 else minZoom)
            .setBufferPixels(LABEL_BUFFER)
            .setSortKeyDescending((population / 1000).coerceIn(0, 100_000).toInt())
        names(f, point)
        val capital = f.getString("capital")
        if (capital == "yes" || capital == "2") point.setAttr("capital", 2)
        else if (capital == "4") point.setAttr("capital", 4)
    }

    private fun outdoorPoi(f: SourceFeature, features: FeatureCollector) {
        val (cls, subclass, minZoom) = poiClass(f) ?: return
        val point = if (f.isPoint) features.point("outdoor_poi") else features.pointOnSurface("outdoor_poi")
        point.setAttr("class", cls).setMinZoom(minZoom.coerceAtMost(maxZoom)).setBufferPixels(LABEL_BUFFER)
        if (subclass != null) point.setAttr("subclass", subclass)
        names(f, point, english = false)
        elevationMeters(f.getTag("ele"))?.let { point.setAttr("ele", it.roundToInt()) }
        f.getString("operator")?.let { point.setAttr("operator", it) }
        accessRestriction(f)?.let { point.setAttr("access", it) }
        if (f.hasTag("fee", "yes")) point.setAttr("fee", 1)
    }

    private fun poiClass(f: SourceFeature): Triple<String, String?, Int>? {
        val tourism = f.getString("tourism")
        val amenity = f.getString("amenity")
        return when {
            tourism == "alpine_hut" || tourism == "wilderness_hut" -> Triple("hut", tourism, 11)
            amenity == "shelter" -> Triple("shelter", f.getString("shelter_type"), 12)
            tourism == "camp_site" || tourism == "camp_pitch" -> Triple("campsite", tourism, 12)
            f.hasTag("highway", "trailhead") -> Triple("trailhead", null, 12)
            f.hasTag("waterway", "waterfall") || f.hasTag("natural", "waterfall") -> Triple("waterfall", null, 12)
            f.hasTag("natural", "spring") -> Triple("spring", f.getString("spring"), 13)
            tourism == "viewpoint" -> Triple("viewpoint", null, 13)
            f.hasTag("natural", "cave_entrance") -> Triple("cave", null, 13)
            amenity == "drinking_water" || f.hasTag("man_made", "water_tap") -> Triple("drinking_water", null, 14)
            amenity == "parking" && !f.hasTag("access", "private", "no") -> Triple("parking", f.getString("parking"), 14)
            amenity == "toilets" -> Triple("toilets", null, 14)
            tourism == "picnic_site" -> Triple("picnic_site", null, 14)
            tourism == "information" && f.hasTag("information", "guidepost") -> Triple("guidepost", null, 14)
            else -> null
        }
    }

    // ---- helpers ----

    private fun names(f: SourceFeature, feature: FeatureCollector.Feature, english: Boolean = true) {
        val name = f.getString("name") ?: return
        feature.setAttr("name", name)
        if (english) f.getString("name:en")?.takeIf { it != name }?.let { feature.setAttr("name_en", it) }
    }

    private fun brunnel(f: SourceFeature): String? = when {
        f.hasTag("bridge") && !f.hasTag("bridge", "no") -> "bridge"
        f.hasTag("tunnel") && !f.hasTag("tunnel", "no") -> "tunnel"
        f.hasTag("ford") && !f.hasTag("ford", "no") -> "ford"
        else -> null
    }

    private fun intermittent(f: SourceFeature) = f.hasTag("intermittent", "yes") || f.hasTag("seasonal", "yes")

    private fun accessRestriction(f: SourceFeature): String? {
        val foot = f.getString("foot")
        if (foot in setOf("yes", "designated", "permissive")) return null
        return listOf(foot, f.getString("access")).firstOrNull { it == "no" || it == "private" }
    }

    private fun surfaceClass(surface: String?): String? = when (surface) {
        null -> null
        in PAVED -> "paved"
        in UNPAVED -> "unpaved"
        else -> null
    }

    private fun roadSortKey(cls: String): Int = when (cls) {
        "motorway" -> 90; "trunk" -> 80; "primary" -> 70; "secondary" -> 60; "tertiary" -> 50
        "minor" -> 40; "service" -> 30; "rail" -> 35; "track" -> 20; "path" -> 10; "aerialway" -> 95
        else -> 0
    }

    // ---- post-processing ----

    override fun postProcessLayerFeatures(layer: String, zoom: Int, items: List<VectorTile.Feature>): List<VectorTile.Feature> {
        if (layer == "mountain_peak") {
            val ranked = rankPeaks(items)
            return if (zoom < LABEL_THIN_BELOW_ZOOM) thinPoints(ranked, PEAK_CELL_PX, PEAKS_PER_CELL) { peakScore(it) } else ranked
        }
        if (layer == "place" && zoom < LABEL_THIN_BELOW_ZOOM) {
            return thinPoints(items, PLACE_CELL_PX, PLACES_PER_CELL) { -(PLACE_ORDER[it.attrs()["class"]] ?: 99).toDouble() }
        }
        if (zoom >= maxZoom) return items
        return when (layer) {
            "transportation", "waterway", "boundary", "natural_line" -> FeatureMerge.mergeLineStrings(items, 0.5, 0.1, 4.0)
            "transportation_name" -> FeatureMerge.mergeLineStrings(items, 4.0, 0.1, 8.0)
            "landcover", "landuse", "water" ->
                if (zoom < 13) FeatureMerge.mergeNearbyPolygons(items, 2.0, 2.0, 0.5, 0.5) else items
            "park" -> if (zoom < 13) FeatureMerge.mergeNearbyPolygons(items, 2.0, 2.0, 0.5, 0.5) else items
            else -> items
        }
    }

    private fun peakScore(f: VectorTile.Feature): Double {
        val ele = (f.attrs()["ele"] as? Number)?.toDouble() ?: -1000.0
        val namedBonus = if (f.attrs()["name"] != null) 10_000.0 else 0.0
        val classBonus = if (f.attrs()["class"] in setOf("peak", "volcano")) 20_000.0 else 0.0
        return classBonus + namedBonus + ele
    }

    /** `rank` 1 for the highest feature of each class in the tile, by `ele` (unknown last). */
    private fun rankPeaks(items: List<VectorTile.Feature>): List<VectorTile.Feature> {
        val ranks = IntArray(items.size)
        for (group in items.indices.groupBy { items[it].attrs()["class"] }.values) {
            group.sortedByDescending { (items[it].attrs()["ele"] as? Number)?.toDouble() ?: Double.NEGATIVE_INFINITY }
                .forEachIndexed { rank, index -> ranks[index] = rank + 1 }
        }
        return items.mapIndexed { i, feat -> feat.copyWithExtraAttrs(mapOf("rank" to ranks[i])) }
    }

    /**
     * Keeps at most [limit] points per [cellPx] square cell of the tile (256 px), highest [score] first.
     * Points in the tile buffer fall into cells of their own, so neighbouring tiles agree near the edge.
     */
    fun thinPoints(
        items: List<VectorTile.Feature>,
        cellPx: Double,
        limit: Int,
        score: (VectorTile.Feature) -> Double,
    ): List<VectorTile.Feature> {
        val cells = HashMap<Long, MutableList<Int>>()
        items.forEachIndexed { i, feat ->
            val c = runCatching { feat.geometry().decode().coordinate }.getOrNull() ?: return@forEachIndexed
            val cx = Math.floorDiv(c.x.toLong(), cellPx.toLong())
            val cy = Math.floorDiv(c.y.toLong(), cellPx.toLong())
            cells.getOrPut((cx shl 32) xor (cy and 0xffffffffL)) { ArrayList() }.add(i)
        }
        val keep = BooleanArray(items.size) { true }
        for (members in cells.values) {
            if (members.size <= limit) continue
            members.sortedByDescending { score(items[it]) }.drop(limit).forEach { keep[it] = false }
        }
        return items.filterIndexed { i, _ -> keep[i] }
    }
}
