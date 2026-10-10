package io.github.jasondorjeshort.dystopo.search

/**
 * What a search result is. [code] is stored in the index file and must never change for a kind;
 * [weight] (0..80) is how much a kind counts in ranking before name match and distance; [zoom] is the
 * camera zoom a result of this kind is shown at; [line] kinds come from line features (one name over
 * many tile pieces), which merge over a wider distance when the index is built.
 */
enum class PlaceKind(val code: Int, val label: String, val weight: Int, val zoom: Double, val line: Boolean = false) {
    CITY(1, "city", 80, 11.0),
    TOWN(2, "town", 70, 12.0),
    VILLAGE(3, "village", 60, 13.0),
    HAMLET(4, "hamlet", 45, 14.0),
    LOCALITY(5, "locality", 45, 14.0),
    REGION(6, "region", 50, 7.0),
    ISLAND(7, "island", 40, 12.0),

    VOLCANO(10, "volcano", 72, 12.0),
    PEAK(11, "peak", 62, 13.5),
    PASS(12, "pass", 45, 14.0),
    SADDLE(13, "saddle", 35, 14.0),
    RIDGE(14, "ridge", 32, 14.0, line = true),
    CLIFF(15, "cliff", 15, 15.0, line = true),
    GLACIER(16, "glacier", 45, 13.0),

    LAKE(20, "lake", 50, 13.5),
    RIVER(21, "river", 42, 13.0, line = true),
    STREAM(22, "stream", 25, 14.0, line = true),
    WATERFALL(23, "waterfall", 42, 15.0),
    SPRING(24, "spring", 22, 15.5),

    PARK(30, "park", 55, 10.0),
    HUT(31, "hut", 45, 15.0),
    SHELTER(32, "shelter", 35, 15.0),
    CAMPSITE(33, "campsite", 40, 15.0),
    TRAILHEAD(34, "trailhead", 45, 15.0),
    VIEWPOINT(35, "viewpoint", 35, 15.5),
    CAVE(36, "cave", 25, 15.5),
    PARKING(37, "parking", 15, 16.0),
    POI(38, "poi", 20, 16.0),

    TRAIL(40, "trail", 38, 14.0, line = true),
    /** A named hiking route (outdoors packs: `route_name` on trail pieces). */
    ROUTE(42, "route", 58, 12.0, line = true),
    ROAD(41, "road", 18, 15.0, line = true),
    ;

    companion object {
        private val byCode = entries.associateBy { it.code }
        fun ofCode(code: Int): PlaceKind? = byCode[code]
        fun ofLabel(label: String): PlaceKind? = entries.firstOrNull { it.label == label }
    }
}

/**
 * Which features of a vector tile are searchable, and as what. Covers both the Dystopo outdoors
 * profile (`tilebuild/PROFILE.md`) and the OpenMapTiles schema (OpenFreeMap packs), which share layer
 * names for places, peaks, water and parks; `outdoor_poi` and `natural_line` exist only in outdoors
 * packs, `poi` only in OpenMapTiles ones. Returns null for features that are not indexed.
 */
object PlaceClassifier {
    /** Layers worth decoding for search; everything else in a tile is skipped. */
    val LAYERS = setOf(
        "place", "mountain_peak", "water_name", "waterway", "park", "outdoor_poi", "poi",
        "natural_line", "transportation_name", "transportation", "aerodrome_label",
    )

    fun classify(layer: String, cls: String?, subclass: String?): PlaceKind? = when (layer) {
        "place" -> when (cls) {
            "city" -> PlaceKind.CITY
            "town" -> PlaceKind.TOWN
            "village" -> PlaceKind.VILLAGE
            "hamlet" -> PlaceKind.HAMLET
            "suburb", "quarter", "neighbourhood", "locality", "isolated_dwelling" -> PlaceKind.LOCALITY
            "country", "state", "province", "continent" -> PlaceKind.REGION
            "island" -> PlaceKind.ISLAND
            else -> PlaceKind.LOCALITY
        }
        "mountain_peak" -> when (cls) {
            "volcano" -> PlaceKind.VOLCANO
            "saddle" -> PlaceKind.SADDLE
            "pass" -> PlaceKind.PASS
            "ridge", "arete" -> PlaceKind.RIDGE
            "cliff" -> PlaceKind.CLIFF
            else -> PlaceKind.PEAK
        }
        "natural_line" -> when (cls) {
            "cliff" -> PlaceKind.CLIFF
            else -> PlaceKind.RIDGE
        }
        "water_name" -> when (cls) {
            "river" -> PlaceKind.RIVER
            else -> PlaceKind.LAKE
        }
        "waterway" -> when (cls) {
            "river", "canal" -> PlaceKind.RIVER
            else -> PlaceKind.STREAM
        }
        "park" -> PlaceKind.PARK
        "outdoor_poi" -> when (cls) {
            "hut" -> PlaceKind.HUT
            "shelter" -> PlaceKind.SHELTER
            "campsite" -> PlaceKind.CAMPSITE
            "trailhead" -> PlaceKind.TRAILHEAD
            "waterfall" -> PlaceKind.WATERFALL
            "spring", "drinking_water" -> PlaceKind.SPRING
            "viewpoint" -> PlaceKind.VIEWPOINT
            "cave" -> PlaceKind.CAVE
            "parking" -> PlaceKind.PARKING
            else -> PlaceKind.POI
        }
        "poi" -> when {
            subclass == "alpine_hut" || subclass == "wilderness_hut" -> PlaceKind.HUT
            subclass == "shelter" -> PlaceKind.SHELTER
            cls == "campsite" || subclass == "camp_site" -> PlaceKind.CAMPSITE
            subclass == "viewpoint" -> PlaceKind.VIEWPOINT
            subclass == "waterfall" -> PlaceKind.WATERFALL
            subclass == "spring" -> PlaceKind.SPRING
            subclass == "glacier" -> PlaceKind.GLACIER
            cls == "parking" -> PlaceKind.PARKING
            else -> PlaceKind.POI
        }
        "aerodrome_label" -> PlaceKind.POI
        "transportation_name", "transportation" -> when (cls) {
            "path", "track" -> PlaceKind.TRAIL
            "aerialway" -> PlaceKind.POI
            "rail", "transit", "ferry" -> null
            else -> PlaceKind.ROAD
        }
        else -> null
    }
}
