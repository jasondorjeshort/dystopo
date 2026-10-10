package io.github.jasondorjeshort.dystopo.tilebuild

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The layers and attributes [OutdoorsProfile] writes, as data. The same list is written into the
 * archive metadata (`dystopo:layers`) and checked against `tilebuild/PROFILE.md` by the tests, so the
 * three cannot drift apart.
 */
object OutdoorsSchema {
    const val NAME = "dystopo-outdoors"
    const val VERSION = "1"

    @Serializable
    data class Layer(
        val name: String,
        val geometry: String,
        val minZoom: Int,
        val maxZoom: Int,
        val description: String,
        /** Attribute name to a short description, including its values and from which zoom it appears. */
        val attributes: Map<String, String>,
    )

    private const val NAME_ATTR = "OSM `name`"
    private const val NAME_EN_ATTR = "OSM `name:en`, when it differs from `name`"

    val LAYERS: List<Layer> = listOf(
        Layer(
            "water", "polygon", 0, 14,
            "Oceans, lakes, ponds, reservoirs and river areas. z0-5 from Natural Earth, z6+ ocean from OSM water polygons and inland water from OSM.",
            mapOf(
                "class" to "ocean, lake, river, pond",
                "intermittent" to "1 when the water is seasonal/intermittent (absent otherwise)",
            ),
        ),
        Layer(
            "waterway", "line", 8, 14,
            "Rivers (z8+), canals (z11+), streams (z12+), ditches and drains (z13+).",
            mapOf(
                "class" to "river, canal, stream, ditch, drain",
                "name" to NAME_ATTR,
                "intermittent" to "1 when seasonal/intermittent",
                "brunnel" to "bridge, tunnel (z12+)",
            ),
        ),
        Layer(
            "landcover", "polygon", 7, 14,
            "Natural land cover. Glaciers and rock from z7, the rest from z8; small polygons are dropped and neighbours merged below z13.",
            mapOf(
                "class" to "ice, rock, wood, grass, wetland, sand, farmland (OpenMapTiles classes)",
                "subclass" to "the OSM value: glacier, bare_rock, scree, shingle, rock, wood, forest, grassland, meadow, heath, scrub, fell, tundra, wetland or the wetland=* type (bog, marsh, swamp, ...), sand, beach, dune, farmland, orchard, vineyard",
            ),
        ),
        Layer(
            "landuse", "polygon", 10, 14,
            "Built-up and other land use.",
            mapOf(
                "class" to "residential, commercial, industrial, retail, quarry, cemetery, military, park",
            ),
        ),
        Layer(
            "park", "polygon, point", 6, 14,
            "Protected areas: polygons (no name, so they merge) plus one named label point per area, placed when the area is about 64 px across.",
            mapOf(
                "class" to "national_park, wilderness (protect_class 1b, or protected_area/protection_title naming wilderness), nature_reserve, protected_area",
                "protect_class" to "OSM `protect_class` (IUCN-like: 1a, 1b, 2, ..., 6, 97-99) when tagged",
                "name" to "$NAME_ATTR (label points only)",
                "name_en" to "$NAME_EN_ATTR (label points only)",
                "operator" to "OSM `operator`, e.g. National Park Service (label points only)",
            ),
        ),
        Layer(
            "boundary", "line", 0, 14,
            "Country (admin_level 2) and state/province (admin_level 4) boundaries. z0-4 from Natural Earth, z5+ from OSM boundary relations.",
            mapOf(
                "admin_level" to "2 or 4",
                "maritime" to "1 for boundaries at sea",
            ),
        ),
        Layer(
            "transportation", "line", 4, 14,
            "Roads, tracks, trails, railways, ferries and aerialways. Trails and tracks are kept from z10. Line segments with equal attributes are merged below z14.",
            mapOf(
                "class" to "motorway (z4+), trunk (z5+), primary (z7+), secondary (z9+), tertiary (z11+), minor (z12+), service (z13+; parking aisles and driveways z14), track (z10+), path (z10+; sidewalks and crossings z14), rail (z9+), ferry (z8+), aerialway (z12+)",
                "subclass" to "path class: path, footway, cycleway, bridleway, steps, via_ferrata; rail class: rail, narrow_gauge, light_rail; aerialway class: the aerialway=* value",
                "ramp" to "1 for motorway/trunk/primary/... links",
                "brunnel" to "bridge, tunnel or ford (z12+)",
                "surface" to "paved or unpaved, as in OpenMapTiles (z12+)",
                "surface_detail" to "the raw OSM `surface` value on tracks and trails: gravel, dirt, ground, rock, ... (z13+)",
                "sac_scale" to "OSM `sac_scale` on trails: hiking, mountain_hiking, demanding_mountain_hiking, alpine_hiking, demanding_alpine_hiking, difficult_alpine_hiking",
                "trail_visibility" to "OSM `trail_visibility`: excellent, good, intermediate, bad, horrible, no (z12+)",
                "informal" to "1 for informal (social) trails (z12+)",
                "mtb_scale" to "OSM `mtb:scale` (z13+)",
                "tracktype" to "OSM `tracktype`: grade1-grade5 (tracks, z12+)",
                "access" to "no or private when access/foot forbids walking (tracks and trails, z12+)",
                "operator" to "OSM `operator` on tracks and trails (z13+)",
                "name" to "$NAME_ATTR on tracks and trails (z13+; roads carry names in transportation_name)",
                "ref" to "OSM `ref` on tracks and trails (z13+)",
                "route_network" to "best hiking route network the trail belongs to: iwn, nwn, rwn, lwn (z10+)",
                "route_name" to "names of the hiking/foot route relations the trail belongs to, ';'-separated (z12+)",
            ),
        ),
        Layer(
            "transportation_name", "line", 8, 14,
            "Road and trail names and refs for line labels; merged by name below z14.",
            mapOf(
                "class" to "as in transportation",
                "subclass" to "as in transportation (path class only)",
                "name" to NAME_ATTR,
                "name_en" to NAME_EN_ATTR,
                "ref" to "OSM `ref`",
            ),
        ),
        Layer(
            "place", "point", 2, 14,
            "Settlements and named places: country z2, state z4, city z5, town z7, village z10, hamlet z11, suburb/locality z12, isolated_dwelling z13. Thinned below z13 to 4 per 128 px cell (most important class first).",
            mapOf(
                "class" to "country, state, city, town, village, suburb, hamlet, locality, isolated_dwelling",
                "name" to NAME_ATTR,
                "name_en" to NAME_EN_ATTR,
                "capital" to "2 for national capitals, 4 for state capitals",
            ),
        ),
        Layer(
            "mountain_peak", "point", 9, 14,
            "Peaks, volcanoes, saddles and passes. Named peaks with an elevation from z9, other named ones z11, unnamed z13. Thinned below z13 to 2 per 64 px cell (named peaks and volcanoes first, then by ele).",
            mapOf(
                "class" to "peak, volcano, saddle, pass (mountain_pass=yes); ridge, arete, cliff (z13+, label points for named natural_line features)",
                "name" to NAME_ATTR,
                "name_en" to NAME_EN_ATTR,
                "ele" to "elevation in metres (integer), parsed from OSM `ele`",
                "ele_ft" to "elevation in feet (integer)",
                "prominence" to "OSM `prominence` in metres, when tagged",
                "rank" to "1 for the highest feature of this class in the tile, 2 for the next, ... (by ele)",
            ),
        ),
        Layer(
            "natural_line", "line", 12, 14,
            "Cliffs (z12+), named ridges and aretes (z12+), unnamed ridges and aretes (z13+).",
            mapOf(
                "class" to "cliff, ridge, arete",
                "name" to NAME_ATTR,
                "name_en" to NAME_EN_ATTR,
            ),
        ),
        Layer(
            "water_name", "point", 9, 14,
            "Labels for named lakes and reservoirs, placed when the water body is about 48 px across.",
            mapOf(
                "class" to "lake, river, pond",
                "name" to NAME_ATTR,
                "name_en" to NAME_EN_ATTR,
            ),
        ),
        Layer(
            "outdoor_poi", "point", 11, 14,
            "Backcountry points of interest. Areas (campsites, parking lots) become a point on the surface.",
            mapOf(
                "class" to "hut (z11+), shelter (z12+), campsite (z12+), trailhead (z12+), waterfall (z12+), spring (z13+), viewpoint (z13+), cave (z13+), drinking_water (z14), parking (z14), toilets (z14), picnic_site (z14), guidepost (z14)",
                "subclass" to "the OSM value it came from: alpine_hut, wilderness_hut, shelter_type for shelters, camp_site, camp_pitch, ...",
                "name" to NAME_ATTR,
                "ele" to "elevation in metres (integer), when tagged",
                "operator" to "OSM `operator`",
                "access" to "no or private when tagged so",
                "fee" to "1 when fee=yes",
            ),
        ),
    )

    val LAYER_NAMES: Set<String> = LAYERS.map { it.name }.toSet()

    private val json = Json { encodeDefaults = true }

    /** Compact JSON summary for the archive metadata key `dystopo:layers`. */
    fun metadataJson(): String = json.encodeToString(LAYERS)
}
