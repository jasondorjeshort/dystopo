package io.github.jasondorjeshort.dystopo.style

/**
 * The built-in topo style for OpenMapTiles-schema archives (the `tilebuild` output), as JSON so it
 * is the same data a user would edit (`render --osm-style file.json`) and can later be translated
 * to a MapLibre style.
 *
 * Format: `{"name": ..., "layers": [layer, ...]}`, bottom to top. Every layer has an `id` and a
 * `type`:
 * - `fill`: `source-layer`, `filter`, `minzoom`/`maxzoom`, `color`, `opacity`.
 * - `line`: as fill plus `width`, `dash` (in line widths), `casing-color`, `casing-width` (pixels
 *   added on each side), `geometry: "polygon"` to stroke polygon outlines.
 * - `symbol`: `text` template (`{name}`, `{elevation}`, `\n` for a second line), `placement`
 *   (`point` or `line`), `size` (text pixels before the user's text scale), `color`,
 *   `halo-color`, `halo-width`, `marker` (`shape`, `size`, `color`), `spacing`, `sort`, `italic`.
 * - `contours`: where the DEM contour lines are drawn.
 * Numbers that depend on zoom are `[[zoom, value], ...]`. Filters are described in [Filter].
 *
 * Design: contours sit above the vegetation and glacier tints but under water (lake surfaces are
 * flat; contours across them are DEM noise), so lakes read as solid blue. Trails are red dashes,
 * long dashes for `path`, short for `footway`, dots for steps; tracks are brown dashes; roads are
 * cased. Labels draw last, sized for a phone at the default text scale, with paper-colored halos
 * like the contour labels; peaks and places claim space first, then water, trails and roads,
 * and contour labels give way to all of them.
 */
object DefaultOsmStyle {
    val JSON: String = """
{
  "name": "dystopo-topo",
  "layers": [
    {"id": "wood", "type": "fill", "source-layer": "landcover", "filter": {"class": ["wood", "forest"]},
     "color": "#7fb069", "opacity": 0.30},
    {"id": "wetland", "type": "fill", "source-layer": "landcover", "filter": {"class": "wetland"},
     "color": "#8fc1b5", "opacity": 0.35},
    {"id": "rock", "type": "fill", "source-layer": "landcover", "filter": {"class": ["rock", "sand"]},
     "color": "#d9d4cc", "opacity": 0.25},
    {"id": "glacier", "type": "fill", "source-layer": "landcover", "filter": {"class": "ice"},
     "color": "#eef6ff", "opacity": 0.65},

    {"id": "contours", "type": "contours"},

    {"id": "water", "type": "fill", "source-layer": "water",
     "color": "#a5cbe9", "opacity": 1.0},
    {"id": "water-edge", "type": "line", "source-layer": "water", "geometry": "polygon",
     "color": "#5b9bd5", "width": 0.8},
    {"id": "river", "type": "line", "source-layer": "waterway", "filter": {"class": ["river", "canal"], "!brunnel": "tunnel"},
     "color": "#4a8fd0", "width": [[10, 1.0], [15, 2.6]]},
    {"id": "stream", "type": "line", "source-layer": "waterway", "filter": {"class": ["stream", "ditch", "drain"], "!brunnel": "tunnel", "!intermittent": 1},
     "minzoom": 11, "color": "#4a8fd0", "width": [[11, 0.6], [15, 1.4]]},
    {"id": "stream-intermittent", "type": "line", "source-layer": "waterway", "filter": {"class": ["stream", "ditch", "drain"], "!brunnel": "tunnel", "intermittent": 1},
     "minzoom": 12, "color": "#4a8fd0", "width": [[12, 0.6], [15, 1.2]], "dash": [4, 2]},

    {"id": "park-boundary", "type": "line", "source-layer": "park", "geometry": "polygon",
     "filter": {"class": ["national_park", "wilderness_area", "nature_reserve", "protected_area", "state_park"]},
     "color": "#3f8a3a", "opacity": 0.75, "width": [[9, 1.2], [15, 2.2]], "dash": [4, 1.5]},
    {"id": "admin-boundary", "type": "line", "source-layer": "boundary", "filter": {"admin_level": {"<=": 4}, "!maritime": 1},
     "color": "#7b5a8c", "opacity": 0.8, "width": 1.6, "dash": [6, 2, 1, 2]},

    {"id": "road-major", "type": "line", "source-layer": "transportation", "filter": {"class": ["motorway", "trunk", "primary", "secondary"]},
     "color": "#f2b552", "width": [[9, 1.4], [15, 4.5]], "casing-color": "#6b5232", "casing-width": 0.9},
    {"id": "road-minor", "type": "line", "source-layer": "transportation", "filter": {"class": ["tertiary", "minor"], "!surface": "unpaved"},
     "minzoom": 10, "color": "#fffdf7", "width": [[10, 1.0], [15, 3.4]], "casing-color": "#5a5048", "casing-width": 0.8},
    {"id": "road-unpaved", "type": "line", "source-layer": "transportation", "filter": {"class": ["tertiary", "minor", "service"], "surface": "unpaved"},
     "minzoom": 11, "color": "#fffdf7", "width": [[11, 1.0], [15, 2.6]], "dash": [2.5, 1], "casing-color": "#5a5048", "casing-width": 0.8},
    {"id": "road-service", "type": "line", "source-layer": "transportation", "filter": {"class": "service", "!surface": "unpaved"},
     "minzoom": 13, "color": "#fffdf7", "width": [[13, 0.8], [16, 2.0]], "casing-color": "#5a5048", "casing-width": 0.6},
    {"id": "track", "type": "line", "source-layer": "transportation", "filter": {"class": "track"},
     "minzoom": 11, "color": "#7a5230", "width": [[11, 1.0], [15, 1.8]], "dash": [3, 1.5],
     "casing-color": "#f6f1e499", "casing-width": 0.8},
    {"id": "trail", "type": "line", "source-layer": "transportation",
     "filter": {"class": "path", "!subclass": ["footway", "pedestrian", "steps"], "!surface": "paved",
                "!sac_scale": ["demanding_mountain_hiking", "alpine_hiking", "demanding_alpine_hiking", "difficult_alpine_hiking"]},
     "minzoom": 11, "geometry": "line", "color": "#c0392b", "width": [[11, 1.1], [15, 2.2]], "dash": [3.5, 1.6],
     "casing-color": "#f6f1e499", "casing-width": 0.9},
    {"id": "trail-difficult", "type": "line", "source-layer": "transportation",
     "filter": {"class": "path", "sac_scale": ["demanding_mountain_hiking", "alpine_hiking", "demanding_alpine_hiking", "difficult_alpine_hiking"]},
     "minzoom": 11, "geometry": "line", "color": "#5c1a14", "width": [[11, 1.1], [15, 2.2]], "dash": [1, 1.6],
     "casing-color": "#f6f1e499", "casing-width": 0.9},
    {"id": "footway", "type": "line", "source-layer": "transportation",
     "filter": {"class": "path", "subclass": ["footway", "pedestrian"], "!surface": "paved",
                "!sac_scale": ["demanding_mountain_hiking", "alpine_hiking", "demanding_alpine_hiking", "difficult_alpine_hiking"]},
     "minzoom": 12, "geometry": "line", "color": "#c0392b", "width": [[12, 1.0], [15, 2.0]], "dash": [1.6, 1.4],
     "casing-color": "#f6f1e499", "casing-width": 0.9},
    {"id": "path-paved", "type": "line", "source-layer": "transportation",
     "filter": {"class": "path", "!subclass": "steps", "surface": "paved"},
     "minzoom": 13, "geometry": "line", "color": "#b5483c", "width": [[13, 0.8], [16, 1.6]],
     "casing-color": "#f6f1e499", "casing-width": 0.7},
    {"id": "steps", "type": "line", "source-layer": "transportation", "filter": {"class": "path", "subclass": "steps"},
     "minzoom": 14, "geometry": "line", "color": "#c0392b", "width": [[14, 1.6], [16, 2.6]], "dash": [0.4, 0.5]},

    {"id": "places", "type": "symbol", "source-layer": "place",
     "filter": {"class": ["city", "town", "village", "hamlet", "suburb", "locality", "isolated_dwelling"]},
     "text": "{name}", "size": [[10, 13], [15, 14]], "color": "#1f1a17", "halo-color": "#f6f1e4dd", "halo-width": 1.6, "sort": "rank", "spacing": 600},
    {"id": "peaks", "type": "symbol", "source-layer": "mountain_peak", "geometry": "point",
     "filter": {"class": ["peak", "volcano", "hill"], "name": true},
     "text": "{name}\n{elevation}", "size": 12.5, "color": "#3b2a1a", "halo-color": "#f6f1e4dd", "halo-width": 1.5,
     "marker": {"shape": "triangle", "size": 8, "color": "#3b2a1a"}, "sort": "-ele", "spacing": 200},
    {"id": "saddles", "type": "symbol", "source-layer": "mountain_peak", "geometry": "point", "filter": {"class": "saddle"},
     "minzoom": 13, "text": "{name}", "size": 10.5, "color": "#3b2a1a", "halo-color": "#f6f1e4dd", "halo-width": 1.3,
     "marker": {"shape": "circle", "size": 5, "color": "#3b2a1a"}, "sort": "rank"},
    {"id": "water-names", "type": "symbol", "source-layer": "water_name", "geometry": "point",
     "text": "{name}", "size": 11, "italic": true, "color": "#1f5f99", "halo-color": "#f6f1e4cc", "halo-width": 1.3, "spacing": 300},
    {"id": "outdoor-pois", "type": "symbol", "source-layer": "poi",
     "filter": {"subclass": ["viewpoint", "camp_site", "shelter", "alpine_hut", "wilderness_hut", "spring", "waterfall", "drinking_water"]},
     "minzoom": 14, "text": "{name}", "size": 10, "color": "#3d3a36", "halo-color": "#f6f1e4dd", "halo-width": 1.3,
     "marker": {"shape": "circle", "size": 5, "color": "#3d3a36"}, "sort": "rank"},
    {"id": "trail-names", "type": "symbol", "source-layer": "transportation_name", "placement": "line",
     "filter": {"class": ["path", "track"]}, "minzoom": 12,
     "text": "{name}", "size": 10.5, "color": "#7a2418", "halo-color": "#f6f1e4dd", "halo-width": 1.3, "spacing": 450},
    {"id": "road-names", "type": "symbol", "source-layer": "transportation_name", "placement": "line",
     "filter": {"!class": ["path", "track"]}, "minzoom": 12,
     "text": "{name}", "size": 10, "color": "#3a332d", "halo-color": "#f6f1e4dd", "halo-width": 1.3, "spacing": 500},
    {"id": "waterway-names", "type": "symbol", "source-layer": "waterway", "placement": "line",
     "filter": {"name": true, "!brunnel": "tunnel"}, "minzoom": 13,
     "text": "{name}", "size": 10, "italic": true, "color": "#1f5f99", "halo-color": "#f6f1e4cc", "halo-width": 1.3, "spacing": 500},
    {"id": "ridge-names", "type": "symbol", "source-layer": "mountain_peak", "placement": "line", "geometry": "line",
     "filter": {"name": true}, "minzoom": 12,
     "text": "{name}", "size": 10, "italic": true, "color": "#5a4632", "halo-color": "#f6f1e4cc", "halo-width": 1.3, "spacing": 500},
    {"id": "peaks-unnamed", "type": "symbol", "source-layer": "mountain_peak", "geometry": "point",
     "filter": {"class": ["peak", "volcano", "hill"], "name": false}, "minzoom": 12,
     "text": "{elevation}", "size": 10.5, "color": "#3b2a1a", "halo-color": "#f6f1e4dd", "halo-width": 1.3,
     "marker": {"shape": "triangle", "size": 6, "color": "#3b2a1a"}, "sort": "-ele", "spacing": 100}
  ]
}
"""

    val style: OsmStyle by lazy { OsmStyle.parse(JSON) }
}
