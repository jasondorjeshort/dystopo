package io.github.jasondorjeshort.dystopo.tilebuild

import com.onthegomap.planetiler.FeatureCollector
import com.onthegomap.planetiler.VectorTile
import com.onthegomap.planetiler.config.PlanetilerConfig
import com.onthegomap.planetiler.geo.GeoUtils
import com.onthegomap.planetiler.reader.SimpleFeature
import com.onthegomap.planetiler.reader.SourceFeature
import com.onthegomap.planetiler.reader.osm.OsmElement
import com.onthegomap.planetiler.reader.osm.OsmReader
import com.onthegomap.planetiler.reader.osm.OsmRelationInfo
import com.onthegomap.planetiler.stats.Stats
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Synthetic OSM features (public area: Mount Rainier) through [OutdoorsProfile], no Planetiler run. */
class OutdoorsProfileTest {
    private val profile = OutdoorsProfile("test", mapOf("dystopo:license" to "ODbL-1.0"))
    private val factory = FeatureCollector.Factory(PlanetilerConfig.defaults(), Stats.inMemory())
    private var nextId = 1L

    /** Everything the tests emit, for the "all attributes are documented" check. */
    private val emitted = ArrayList<FeatureCollector.Feature>()

    private fun process(feature: SourceFeature): List<FeatureCollector.Feature> {
        val collector = factory.get(feature)
        profile.processFeature(feature, collector)
        return collector.toList().also { emitted += it }
    }

    private fun osm(geometry: Geometry, tags: Map<String, Any>, relations: List<OsmRelationInfo> = emptyList()): SourceFeature =
        SimpleFeature.createFakeOsmFeature(
            geometry, tags, OutdoorsProfile.OSM_SOURCE, null, nextId++,
            relations.map { OsmReader.RelationMember("", it) },
        )

    private fun point(tags: Map<String, Any>) = osm(GeoUtils.point(-121.76, 46.85), tags)

    private fun line(tags: Map<String, Any>, relations: List<OsmRelationInfo> = emptyList()) = osm(
        GeoUtils.JTS_FACTORY.createLineString(arrayOf(Coordinate(-121.76, 46.85), Coordinate(-121.74, 46.86))),
        tags, relations,
    )

    /** A ~1.5 km square, so it can be both a closed way polygon and big enough for labels at mid zooms. */
    private fun area(tags: Map<String, Any>, size: Double = 0.02) = osm(
        GeoUtils.JTS_FACTORY.createPolygon(
            arrayOf(
                Coordinate(-121.76, 46.85), Coordinate(-121.76 + size, 46.85), Coordinate(-121.76 + size, 46.85 + size),
                Coordinate(-121.76, 46.85 + size), Coordinate(-121.76, 46.85),
            ),
        ),
        tags,
    )

    private fun List<FeatureCollector.Feature>.layer(name: String) = single { it.layer == name }

    @Test
    fun trailCarriesOutdoorAttributesAndHikingRoute() {
        val out = process(
            line(
                mapOf(
                    "highway" to "path", "sac_scale" to "mountain_hiking", "surface" to "ground",
                    "trail_visibility" to "good", "informal" to "yes", "operator" to "National Park Service",
                    "name" to "Wonderland Trail", "bridge" to "yes",
                ),
                listOf(OutdoorsProfile.RouteInfo(7, "Wonderland Trail", null, "rwn")),
            ),
        )
        val trail = out.layer("transportation")
        assertEquals(OutdoorsProfile.TRAIL_MIN_ZOOM, trail.minZoom)
        assertEquals(
            mapOf(
                "class" to "path", "subclass" to "path", "sac_scale" to "mountain_hiking", "route_network" to "rwn",
            ),
            trail.getAttrsAtZoom(10),
        )
        val z14 = trail.getAttrsAtZoom(14)
        assertEquals("unpaved", z14["surface"])
        assertEquals("ground", z14["surface_detail"])
        assertEquals("good", z14["trail_visibility"])
        assertEquals(1, z14["informal"])
        assertEquals("bridge", z14["brunnel"])
        assertEquals("National Park Service", z14["operator"])
        assertEquals("Wonderland Trail", z14["name"])
        assertEquals("Wonderland Trail", z14["route_name"])

        val name = out.layer("transportation_name")
        assertEquals(12, name.minZoom)
        assertEquals("Wonderland Trail", name.getAttrsAtZoom(12)["name"])
    }

    @Test
    fun tracksSidewalksAndRoads() {
        val track = process(line(mapOf("highway" to "track", "tracktype" to "grade3", "access" to "private"))).layer("transportation")
        assertEquals("track", track.getAttrsAtZoom(14)["class"])
        assertEquals("grade3", track.getAttrsAtZoom(14)["tracktype"])
        assertEquals("private", track.getAttrsAtZoom(14)["access"])
        assertEquals(10, track.minZoom)

        // foot=yes overrides a general access restriction for walkers.
        val open = process(line(mapOf("highway" to "track", "access" to "no", "foot" to "yes"))).layer("transportation")
        assertNull(open.getAttrsAtZoom(14)["access"])

        val sidewalk = process(line(mapOf("highway" to "footway", "footway" to "sidewalk"))).layer("transportation")
        assertEquals(14, sidewalk.minZoom)

        val ramp = process(line(mapOf("highway" to "motorway_link", "ref" to "I-5"))).filter { it.layer == "transportation" }.single()
        assertEquals("motorway", ramp.getAttrsAtZoom(14)["class"])
        assertEquals(1, ramp.getAttrsAtZoom(14)["ramp"])

        val road = process(line(mapOf("highway" to "secondary", "name" to "Paradise Road", "surface" to "asphalt")))
        assertEquals(9, road.layer("transportation").minZoom)
        assertNull(road.layer("transportation").getAttrsAtZoom(14)["name"], "road names live in transportation_name")
        assertEquals("paved", road.layer("transportation").getAttrsAtZoom(12)["surface"])
        assertEquals(11, road.layer("transportation_name").minZoom)

        assertTrue(process(line(mapOf("highway" to "proposed"))).isEmpty())
    }

    @Test
    fun peaksAndPasses() {
        val peak = process(point(mapOf("natural" to "volcano", "name" to "Mount Rainier", "ele" to "4392", "prominence" to "4026")))
            .layer("mountain_peak")
        assertEquals(9, peak.minZoom)
        val attrs = peak.getAttrsAtZoom(9)
        assertEquals("volcano", attrs["class"])
        assertEquals(4392, attrs["ele"])
        assertEquals(14409, attrs["ele_ft"])
        assertEquals(4026, attrs["prominence"])

        assertEquals(13, process(point(mapOf("natural" to "peak"))).layer("mountain_peak").minZoom)
        assertEquals(11, process(point(mapOf("natural" to "saddle", "name" to "Panhandle Gap"))).layer("mountain_peak").minZoom)
        assertEquals("pass", process(point(mapOf("mountain_pass" to "yes"))).layer("mountain_peak").getAttrsAtZoom(14)["class"])
    }

    @Test
    fun cliffsAndNamedRidges() {
        val cliff = process(line(mapOf("natural" to "cliff"))).layer("natural_line")
        assertEquals(12, cliff.minZoom)
        val ridge = process(line(mapOf("natural" to "ridge", "name" to "Rampart Ridge")))
        assertEquals("Rampart Ridge", ridge.layer("natural_line").getAttrsAtZoom(14)["name"])
        assertEquals("ridge", ridge.layer("mountain_peak").getAttrsAtZoom(14)["class"])
    }

    @Test
    fun landcoverWaterAndLanduse() {
        val glacier = process(area(mapOf("natural" to "glacier", "name" to "Nisqually Glacier"))).layer("landcover")
        assertEquals(mapOf("class" to "ice", "subclass" to "glacier"), glacier.getAttrsAtZoom(10))
        assertEquals(7, glacier.minZoom)
        val scree = process(area(mapOf("natural" to "scree"))).layer("landcover")
        assertEquals("rock", scree.getAttrsAtZoom(14)["class"])
        val marsh = process(area(mapOf("natural" to "wetland", "wetland" to "marsh"))).layer("landcover")
        assertEquals(mapOf("class" to "wetland", "subclass" to "marsh"), marsh.getAttrsAtZoom(14))
        val forest = process(area(mapOf("landuse" to "forest"))).layer("landcover")
        assertEquals(mapOf("class" to "wood", "subclass" to "forest"), forest.getAttrsAtZoom(14))

        val lake = process(area(mapOf("natural" to "water", "name" to "Reflection Lakes", "intermittent" to "yes")))
        assertEquals(mapOf("class" to "lake", "intermittent" to 1), lake.layer("water").getAttrsAtZoom(14))
        assertEquals("Reflection Lakes", lake.layer("water_name").getAttrsAtZoom(14)["name"])

        assertEquals("residential", process(area(mapOf("landuse" to "residential"))).layer("landuse").getAttrsAtZoom(14)["class"])
    }

    @Test
    fun parksAndWilderness() {
        val np = process(area(mapOf("boundary" to "national_park", "name" to "Mount Rainier National Park", "protect_class" to "2")))
        val polygons = np.filter { it.layer == "park" && it.isPolygon }
        val labels = np.filter { it.layer == "park" && !it.isPolygon }
        assertEquals(mapOf("class" to "national_park", "protect_class" to "2"), polygons.single().getAttrsAtZoom(10))
        assertEquals("Mount Rainier National Park", labels.single().getAttrsAtZoom(14)["name"])

        val wilderness = process(area(mapOf("boundary" to "protected_area", "protect_class" to "1b", "name" to "Glacier View Wilderness")))
        assertEquals("wilderness", wilderness.first { it.layer == "park" }.getAttrsAtZoom(14)["class"])
        val reserve = process(area(mapOf("leisure" to "nature_reserve")))
        assertEquals("nature_reserve", reserve.layer("park").getAttrsAtZoom(14)["class"])

        // Mount Rainier Wilderness as tagged in OSM: IUCN 1a, but a designated Wilderness Area.
        val rainierWilderness = process(area(mapOf(
            "boundary" to "protected_area", "leisure" to "nature_reserve", "protect_class" to "1a",
            "protected_area" to "wilderness_preserve", "protection_title" to "Wilderness Area", "name" to "Mount Rainier Wilderness",
        )))
        assertEquals(setOf("wilderness"), rainierWilderness.filter { it.layer == "park" }.map { it.getAttrsAtZoom(14)["class"] }.toSet())
        assertEquals("1a", rainierWilderness.first { it.layer == "park" && it.isPolygon }.getAttrsAtZoom(14)["protect_class"])
        // Either hint alone is enough.
        val titled = process(area(mapOf("boundary" to "protected_area", "protect_class" to "1a", "protection_title" to "wilderness area")))
        assertEquals("wilderness", titled.layer("park").getAttrsAtZoom(14)["class"])
        val preserve = process(area(mapOf("boundary" to "protected_area", "protected_area" to "wilderness_preserve")))
        assertEquals("wilderness", preserve.layer("park").getAttrsAtZoom(14)["class"])
        // A strict nature reserve (1a) without a wilderness designation stays a protected area.
        val strict = process(area(mapOf("boundary" to "protected_area", "protect_class" to "1a", "protection_title" to "Research Natural Area")))
        assertEquals("protected_area", strict.layer("park").getAttrsAtZoom(14)["class"])

        // Administrative areas never become polygons; their ways are drawn from the relation instead.
        assertTrue(process(area(mapOf("boundary" to "administrative", "admin_level" to "4", "name" to "Washington"))).isEmpty())
    }

    @Test
    fun outdoorPoisAndPlaces() {
        val spring = process(point(mapOf("natural" to "spring"))).layer("outdoor_poi")
        assertEquals("spring", spring.getAttrsAtZoom(14)["class"])
        assertEquals(13, spring.minZoom)
        val hut = process(point(mapOf("amenity" to "shelter", "shelter_type" to "basic_hut", "name" to "Camp Muir Shelter", "ele" to "3100")))
            .layer("outdoor_poi")
        assertEquals(mapOf("class" to "shelter", "subclass" to "basic_hut", "name" to "Camp Muir Shelter", "ele" to 3100), hut.getAttrsAtZoom(14))
        val camp = process(area(mapOf("tourism" to "camp_site", "name" to "Cougar Rock", "fee" to "yes"), size = 0.002))
            .layer("outdoor_poi")
        assertFalse(camp.isPolygon)
        assertEquals(1, camp.getAttrsAtZoom(14)["fee"])
        assertEquals("trailhead", process(point(mapOf("highway" to "trailhead"))).layer("outdoor_poi").getAttrsAtZoom(14)["class"])
        assertTrue(process(area(mapOf("amenity" to "parking", "access" to "private"), size = 0.001)).none { it.layer == "outdoor_poi" })

        val hamlet = process(point(mapOf("place" to "hamlet", "name" to "Paradise", "name:en" to "Paradise"))).layer("place")
        assertEquals(mapOf("class" to "hamlet", "name" to "Paradise"), hamlet.getAttrsAtZoom(14))
        assertEquals(11, hamlet.minZoom)
        val town = process(area(mapOf("place" to "town", "name" to "Ashford"))).layer("place")
        assertFalse(town.isPolygon)
    }

    @Test
    fun relationsAttachRoutesAndAdminLevels() {
        val route = OsmElement.Relation(5, mapOf("type" to "route", "route" to "hiking", "name" to "Wonderland Trail", "network" to "rwn"), emptyList())
        assertEquals(listOf<OsmRelationInfo>(OutdoorsProfile.RouteInfo(5, "Wonderland Trail", null, "rwn")), profile.preprocessOsmRelation(route))
        val state = OsmElement.Relation(6, mapOf("type" to "boundary", "boundary" to "administrative", "admin_level" to "4"), emptyList())
        assertEquals(listOf<OsmRelationInfo>(OutdoorsProfile.AdminInfo(6, 4)), profile.preprocessOsmRelation(state))
        val county = OsmElement.Relation(7, mapOf("type" to "boundary", "boundary" to "administrative", "admin_level" to "6"), emptyList())
        assertNull(profile.preprocessOsmRelation(county))

        val border = process(line(emptyMap(), listOf(OutdoorsProfile.AdminInfo(6, 4), OutdoorsProfile.AdminInfo(8, 2))))
            .layer("boundary")
        assertEquals(mapOf("admin_level" to 2), border.getAttrsAtZoom(10))
        assertEquals(OutdoorsProfile.OSM_BOUNDARY_MIN_ZOOM, border.minZoom)
    }

    @Test
    fun auxiliarySourcesCoverLowZooms() {
        val ocean = SimpleFeature.create(
            GeoUtils.JTS_FACTORY.createPolygon(
                arrayOf(Coordinate(-130.0, 40.0), Coordinate(-125.0, 40.0), Coordinate(-125.0, 45.0), Coordinate(-130.0, 40.0)),
            ),
            emptyMap(), OutdoorsProfile.NATURAL_EARTH_SOURCE, "ne_50m_ocean", 1,
        )
        val water = process(ocean).layer("water")
        assertEquals(2, water.minZoom)
        assertEquals(3, water.maxZoom)
        val polygons = SimpleFeature.create(
            GeoUtils.JTS_FACTORY.createPolygon(
                arrayOf(Coordinate(-130.0, 40.0), Coordinate(-125.0, 40.0), Coordinate(-125.0, 45.0), Coordinate(-130.0, 40.0)),
            ),
            emptyMap(), OutdoorsProfile.WATER_POLYGON_SOURCE, "water_polygons", 2,
        )
        assertEquals(OutdoorsProfile.OSM_WATER_MIN_ZOOM, process(polygons).layer("water").minZoom)
    }

    @Test
    fun peaksAreRankedAndThinnedBelowZ13() {
        fun peak(x: Double, y: Double, ele: Int, name: String?) = VectorTile.Feature(
            "mountain_peak", nextId++, VectorTile.encodeGeometry(GeoUtils.point(x, y)),
            buildMap { put("class", "peak"); put("ele", ele); if (name != null) put("name", name) },
        )
        // Five peaks in one 64 px cell, one in another cell.
        val items = listOf(
            peak(10.0, 10.0, 2000, "A"), peak(20.0, 20.0, 4000, "B"), peak(30.0, 30.0, 3000, "C"),
            peak(40.0, 40.0, 4300, null), peak(50.0, 50.0, 1000, "E"), peak(200.0, 200.0, 500, "F"),
        )
        val z14 = profile.postProcessLayerFeatures("mountain_peak", 14, items)
        assertEquals(listOf(4, 2, 3, 1, 5, 6), z14.map { it.tags()["rank"] })

        val z11 = profile.postProcessLayerFeatures("mountain_peak", 11, items)
        // Named peaks win over the higher unnamed one; then by ele. The lone peak in its own cell stays.
        assertEquals(setOf("B", "C", "F"), z11.map { it.tags()["name"] }.toSet())
    }

    @Test
    fun everyEmittedAttributeIsDocumented() {
        // Run the other tests' features through the profile, then check layer and attribute names.
        trailCarriesOutdoorAttributesAndHikingRoute()
        tracksSidewalksAndRoads()
        peaksAndPasses()
        cliffsAndNamedRidges()
        landcoverWaterAndLanduse()
        parksAndWilderness()
        outdoorPoisAndPlaces()
        relationsAttachRoutesAndAdminLevels()
        val docs = OutdoorsSchema.LAYERS.associateBy { it.name }
        assertTrue(emitted.size > 30)
        for (f in emitted) {
            val layer = docs[f.layer] ?: error("layer ${f.layer} is not documented")
            for (z in 0..14) {
                for (key in f.getAttrsAtZoom(z).keys) {
                    assertTrue(key in layer.attributes, "${f.layer}.$key is not documented in OutdoorsSchema")
                }
            }
        }
    }

    @Test
    fun profileMarkdownDocumentsEveryLayerAndAttribute() {
        val md = File("PROFILE.md").takeIf { it.isFile } ?: File("tilebuild/PROFILE.md")
        val text = md.readText()
        for (layer in OutdoorsSchema.LAYERS) {
            val heading = "### `${layer.name}`"
            val start = text.indexOf(heading)
            assertTrue(start >= 0, "PROFILE.md has no section '$heading'")
            val end = text.indexOf("\n### ", start + heading.length).let { if (it < 0) text.length else it }
            val section = text.substring(start, end)
            for (attr in layer.attributes.keys) {
                assertTrue("`$attr`" in section, "PROFILE.md section ${layer.name} does not mention `$attr`")
            }
        }
    }

    @Test
    fun metadataCarriesLayerDocsAndOdblAttribution() {
        val meta = profile.extraArchiveMetadata()
        assertEquals("ODbL-1.0", meta["dystopo:license"])
        val layers = Json.parseToJsonElement(meta.getValue("dystopo:layers")).jsonArray
        assertEquals(OutdoorsSchema.LAYER_NAMES, layers.map { it.jsonObject.getValue("name").jsonPrimitive.content }.toSet())
        assertTrue("OpenStreetMap" in profile.attribution())
        assertTrue("ODbL" in profile.attribution())
        assertEquals("dystopo-outdoors", profile.name())

        val extra = Attribution.extraMetadata("washington", null, Attribution.OUTDOORS_SOURCE_IDS, Attribution.OUTDOORS_SCHEMA)
        assertEquals("osm,osm-water-polygons,natural-earth,openmaptiles", extra["dystopo:provenance"])
        assertFalse("lake centerlines" in extra.getValue("dystopo:sources"))
        assertTrue(extra.getValue("dystopo:schema").startsWith("Dystopo outdoors"))
    }

    @Test
    fun profileOptionParses() {
        assertEquals(ProfileKind.OUTDOORS, ProfileKind.parse("outdoors"))
        assertEquals(ProfileKind.OPENMAPTILES, ProfileKind.parse("openmaptiles"))
        assertFailsWith<IllegalArgumentException> { ProfileKind.parse("osm-bright") }
    }
}
