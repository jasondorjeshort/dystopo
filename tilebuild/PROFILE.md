# Dystopo outdoors vector tile profile

`--profile outdoors` builds vector tiles for backcountry use with our own Planetiler profile
(`src/main/kotlin/.../OutdoorsProfile.kt`) instead of the generic OpenMapTiles one. The default stays
`--profile openmaptiles`.

```
./gradlew :tilebuild:run --args="--extract liechtenstein --profile outdoors --out out/li-outdoors.pmtiles"
./gradlew :tilebuild:run --args="--extract north-america/us/washington --area rainier --profile outdoors --out out/rainier-outdoors.pmtiles"
```

This file, the `dystopo:layers` key in the archive metadata (the same list as JSON) and `OutdoorsSchema.kt`
describe the same schema; a test fails if a layer or attribute is missing here.

## Design

- **OpenMapTiles layer names where the data is the same.** Water, waterways, land cover, land use, parks,
  boundaries, roads, places, peaks and water labels use the OpenMapTiles layer names and class values, so a
  style written for OpenMapTiles tiles (such as the browser demo) draws these tiles too. Attributes are a
  smaller set: one `name` plus `name_en`, no `name:latin`/`name_int`/per-language names, no `rank` on parks.
- **Trails are in `transportation`**, as in OpenMapTiles (`class` `path` or `track`), with the outdoor
  attributes added to the same features: `sac_scale`, `trail_visibility`, `informal`, raw `surface_detail`,
  `operator`, `mtb_scale`, `access`, and the hiking route relations they belong to (`route_name`,
  `route_network`). A separate trail layer would have hidden every trail from OpenMapTiles styles.
- **New layers** only for data OpenMapTiles does not have: `outdoor_poi` (huts, shelters, campsites,
  trailheads, springs, viewpoints, ...) and `natural_line` (cliffs, ridges, aretes).
- **Road data for drive mode (version 2, T73).** Roads and tracks carry `oneway` (as OpenMapTiles: 1 or
  -1; roundabouts and motorways are one-way unless tagged otherwise), `motor_access` (`no`, `private` or
  `destination`, from the most specific of `motorcar`, `motor_vehicle`, `vehicle`, `access`; agricultural,
  forestry and emergency-only roads count as no, `permit` as private, `customers` and `delivery` as
  destination) and `maxspeed` (km/h) from z12. The routing graph reads them at z14, where lines are never
  merged, so a line keeps its OSM direction and `oneway` means what it says; below z14 merging may reverse a
  merged line, so do not route on those zooms. Conditional tags (seasonal closures such as a park road shut
  in winter), turn restrictions and lane data are not carried. `dystopo:profile_version` in the metadata is
  `2` from this version on; the phone's router refuses drive mode on graphs of older packs.
- **Trails from z10.** Paths and tracks appear at every zoom from 10; sidewalks and crossings only at z14.
  Below z14 trail segments with equal attributes are merged, and the attributes that vary most
  (`surface_detail`, `operator`, `name`, ...) only appear from z12/z13 so the merging works.
- **Generalization.** Polygons smaller than 2 px are dropped and neighbouring polygons with equal attributes
  merged below z13; peaks and places are thinned per tile below z13; park names live on one label point per
  park instead of on the polygons.
- **Low zooms.** z0-5 water and z0-4 boundaries come from Natural Earth; ocean from z6 comes from OSM water
  polygons (both are part of the auxiliary data tilebuild already downloads). Lake centerlines are not used.
- Not included (yet): buildings, house numbers, aeroways, town POIs (`poi`), contour lines (those come from
  the DEM, not OSM).

## Licensing

The tiles contain OpenStreetMap data and are an ODbL 1.0 derived database: the `attribution` field carries
"Â© OpenStreetMap contributors (ODbL 1.0)" and `dystopo:license` is `ODbL-1.0`. Because the schema reuses the
OpenMapTiles layer names and class values (design CC-BY 4.0), the "Â© OpenMapTiles" credit stays as well.
Sources are listed in `dystopo:sources` and `dystopo:provenance` (OSM, OSM water polygons, Natural Earth).
A build with `--min-zoom` 6 or more skips Natural Earth (it only feeds z0-5) and leaves it out of the provenance;
`--source-id overpass` adds the Overpass API when the extract came from there (an `.osm` XML file, converted to
`.osm.pbf` first). `dystopo:profile` is `outdoors` (OpenMapTiles-profile archives say `openmaptiles`), which is
how pack precedence tells the two apart.

## Layers

### `water`

Polygon, z0-14. Oceans, lakes, ponds, reservoirs and river areas. z0-5 from Natural Earth, z6+ ocean from OSM water polygons and inland water from OSM.

| Attribute | Meaning |
|---|---|
| `class` | ocean, lake, river, pond |
| `intermittent` | 1 when the water is seasonal/intermittent (absent otherwise) |

### `waterway`

Line, z8-14. Rivers (z8+), canals (z11+), streams (z12+), ditches and drains (z13+).

| Attribute | Meaning |
|---|---|
| `class` | river, canal, stream, ditch, drain |
| `name` | OSM `name` |
| `intermittent` | 1 when seasonal/intermittent |
| `brunnel` | bridge, tunnel (z12+) |

### `landcover`

Polygon, z7-14. Natural land cover. Glaciers and rock from z7, the rest from z8; small polygons are dropped and neighbours merged below z13.

| Attribute | Meaning |
|---|---|
| `class` | ice, rock, wood, grass, wetland, sand, farmland (OpenMapTiles classes) |
| `subclass` | the OSM value: glacier, bare_rock, scree, shingle, rock, wood, forest, grassland, meadow, heath, scrub, fell, tundra, wetland or the wetland=* type (bog, marsh, swamp, ...), sand, beach, dune, farmland, orchard, vineyard |

### `landuse`

Polygon, z10-14. Built-up and other land use.

| Attribute | Meaning |
|---|---|
| `class` | residential, commercial, industrial, retail, quarry, cemetery, military, park |

### `park`

Polygon, point, z6-14. Protected areas: polygons (no name, so they merge) plus one named label point per area, placed when the area is about 64 px across.

| Attribute | Meaning |
|---|---|
| `class` | national_park, wilderness (protect_class 1b, or protected_area/protection_title naming wilderness), nature_reserve, protected_area |
| `protect_class` | OSM `protect_class` (IUCN-like: 1a, 1b, 2, ..., 6, 97-99) when tagged |
| `name` | OSM `name` (label points only) |
| `name_en` | OSM `name:en`, when it differs from `name` (label points only) |
| `operator` | OSM `operator`, e.g. National Park Service (label points only) |

### `boundary`

Line, z0-14. Country (admin_level 2) and state/province (admin_level 4) boundaries. z0-4 from Natural Earth, z5+ from OSM boundary relations.

| Attribute | Meaning |
|---|---|
| `admin_level` | 2 or 4 |
| `maritime` | 1 for boundaries at sea |

### `transportation`

Line, z4-14. Roads, tracks, trails, railways, ferries and aerialways. Trails and tracks are kept from z10. Line segments with equal attributes are merged below z14.

| Attribute | Meaning |
|---|---|
| `class` | motorway (z4+), trunk (z5+), primary (z7+), secondary (z9+), tertiary (z11+), minor (z12+), service (z13+; parking aisles and driveways z14), track (z10+), path (z10+; sidewalks and crossings z14), rail (z9+), ferry (z8+), aerialway (z12+) |
| `subclass` | path class: path, footway, cycleway, bridleway, steps, via_ferrata; rail class: rail, narrow_gauge, light_rail; aerialway class: the aerialway=* value |
| `ramp` | 1 for motorway/trunk/primary/... links |
| `brunnel` | bridge, tunnel or ford (z12+) |
| `surface` | paved or unpaved, as in OpenMapTiles (z12+) |
| `surface_detail` | the raw OSM `surface` value on tracks and trails: gravel, dirt, ground, rock, ... (z13+) |
| `sac_scale` | OSM `sac_scale` on trails: hiking, mountain_hiking, demanding_mountain_hiking, alpine_hiking, demanding_alpine_hiking, difficult_alpine_hiking |
| `trail_visibility` | OSM `trail_visibility`: excellent, good, intermediate, bad, horrible, no (z12+) |
| `informal` | 1 for informal (social) trails (z12+) |
| `mtb_scale` | OSM `mtb:scale` (z13+) |
| `tracktype` | OSM `tracktype`: grade1-grade5 (tracks, z12+) |
| `access` | no or private when access/foot forbids walking (tracks and trails, z12+) |
| `oneway` | 1 when vehicles may travel only in the line's direction (oneway=yes, roundabouts, motorways), -1 only against it (oneway=-1); roads and tracks, z12+ |
| `motor_access` | no, private or destination when cars may not use the road, or only to reach a place on it (motorcar, motor_vehicle, vehicle, access, most specific first; roads and tracks, z12+) |
| `maxspeed` | OSM `maxspeed` in whole km/h (mph converted; roads and tracks, z12+) |
| `operator` | OSM `operator` on tracks and trails (z13+) |
| `name` | OSM `name` on tracks and trails (z13+) and roads (z14 only, for route descriptions; labels use transportation_name) |
| `ref` | OSM `ref` on tracks and trails (z13+) and roads (z14 only) |
| `route_network` | best hiking route network the trail belongs to: iwn, nwn, rwn, lwn (z10+) |
| `route_name` | names of the hiking/foot route relations the trail belongs to, ';'-separated (z12+) |

### `transportation_name`

Line, z8-14. Road and trail names and refs for line labels; merged by name below z14.

| Attribute | Meaning |
|---|---|
| `class` | as in transportation |
| `subclass` | as in transportation (path class only) |
| `name` | OSM `name` |
| `name_en` | OSM `name:en`, when it differs from `name` |
| `ref` | OSM `ref` |

### `place`

Point, z2-14. Settlements and named places: country z2, state z4, city z5, town z7, village z10, hamlet z11, suburb/locality z12, isolated_dwelling z13. Thinned below z13 to 4 per 128 px cell (most important class first).

| Attribute | Meaning |
|---|---|
| `class` | country, state, city, town, village, suburb, hamlet, locality, isolated_dwelling |
| `name` | OSM `name` |
| `name_en` | OSM `name:en`, when it differs from `name` |
| `capital` | 2 for national capitals, 4 for state capitals |

### `mountain_peak`

Point, z9-14. Peaks, volcanoes, saddles and passes. Named peaks with an elevation from z9, other named ones z11, unnamed z13. Thinned below z13 to 2 per 64 px cell (named peaks and volcanoes first, then by ele).

| Attribute | Meaning |
|---|---|
| `class` | peak, volcano, saddle, pass (mountain_pass=yes); ridge, arete, cliff (z13+, label points for named natural_line features) |
| `name` | OSM `name` |
| `name_en` | OSM `name:en`, when it differs from `name` |
| `ele` | elevation in metres (integer), parsed from OSM `ele` |
| `ele_ft` | elevation in feet (integer) |
| `prominence` | OSM `prominence` in metres, when tagged |
| `rank` | 1 for the highest feature of this class in the tile, 2 for the next, ... (by ele) |

### `natural_line`

Line, z12-14. Cliffs (z12+), named ridges and aretes (z12+), unnamed ridges and aretes (z13+).

| Attribute | Meaning |
|---|---|
| `class` | cliff, ridge, arete |
| `name` | OSM `name` |
| `name_en` | OSM `name:en`, when it differs from `name` |

### `water_name`

Point, z9-14. Labels for named lakes and reservoirs, placed when the water body is about 48 px across.

| Attribute | Meaning |
|---|---|
| `class` | lake, river, pond |
| `name` | OSM `name` |
| `name_en` | OSM `name:en`, when it differs from `name` |

### `outdoor_poi`

Point, z11-14. Backcountry points of interest. Areas (campsites, parking lots) become a point on the surface.

| Attribute | Meaning |
|---|---|
| `class` | hut (z11+), shelter (z12+), campsite (z12+), trailhead (z12+), waterfall (z12+), spring (z13+), viewpoint (z13+), cave (z13+), drinking_water (z14), parking (z14), toilets (z14), picnic_site (z14), guidepost (z14) |
| `subclass` | the OSM value it came from: alpine_hut, wilderness_hut, shelter_type for shelters, camp_site, camp_pitch, ... |
| `name` | OSM `name` |
| `ele` | elevation in metres (integer), when tagged |
| `operator` | OSM `operator` |
| `access` | no or private when tagged so |
| `fee` | 1 when fee=yes |
