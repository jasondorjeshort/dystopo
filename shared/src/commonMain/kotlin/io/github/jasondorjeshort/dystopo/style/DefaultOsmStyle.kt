package io.github.jasondorjeshort.dystopo.style

/**
 * The built-in topo style for the `tilebuild` archives, as JSON so it is the same data a user would
 * edit (`render --osm-style file.json`) and can later be translated to a MapLibre style. One style
 * draws both schemas: OpenMapTiles (`--profile openmaptiles`) and the Dystopo outdoors schema
 * (`--profile outdoors`, `tilebuild/PROFILE.md`), which keeps the OpenMapTiles layer names and adds
 * attributes and layers. Rules for data only one schema has simply match nothing in the other.
 *
 * Format: `{"name": ..., "layers": [layer, ...]}`, bottom to top. Every layer has an `id` and a
 * `type`:
 * - `fill`: `source-layer`, `filter`, `minzoom`/`maxzoom`, `color`, `opacity`.
 * - `line`: as fill plus `width`, `dash` (in line widths), `casing-color`, `casing-width` (pixels
 *   added on each side), `geometry: "polygon"` to stroke polygon outlines, `modifiers` (a list of
 *   `{"filter": ..., "width": factor, "opacity": factor}`; every matching one multiplies the
 *   feature's width and opacity) and `ticks` (`{"spacing", "length", "width", "side"}`, pixels;
 *   side `right` or `left` of the line direction, or `downhill`: the renderer asks the DEM and
 *   falls back to `right`, the OSM convention for cliffs).
 * - `symbol`: `text` template (`{name}`, `{elevation}`, `\n` for a second line), `placement`
 *   (`point` or `line`), `size` (text pixels before the user's text scale), `color`,
 *   `halo-color`, `halo-width`, `marker` (`shape`: circle, triangle, square, diamond, house,
 *   star; `size`, `color`, optional `text` glyph drawn inside in `text-color`), `text-optional`
 *   (keep the marker when the text is empty or does not fit), `spacing`, `sort`, `italic`.
 * - `contours`: where the DEM contour lines are drawn.
 * Numbers that depend on zoom are `[[zoom, value], ...]`. Filters are described in [Filter].
 *
 * Design: contours sit above the vegetation and glacier tints but under water (lake surfaces are
 * flat; contours across them are DEM noise), so lakes read as solid blue. Roads are cased; tracks
 * are brown dashes. Trails are red dashes that get shorter and darker with difficulty (`sac_scale`):
 * hiking (or untagged) long red dashes, mountain_hiking medium dashes, demanding_mountain_hiking
 * short dark-red dashes, alpine grades dark dots; `footway` short red dashes, steps dots. Trails with
 * poor `trail_visibility` (bad, horrible, no; also the common non-standard `poor`) draw at half
 * opacity, `informal` ones at 65% width. Cliffs are dark brown lines with ticks on the downhill side.
 * Park boundaries are green dashes; wilderness areas (outdoors class `wilderness`, OpenMapTiles
 * `wilderness_area`) teal dots, so the two read apart where they overlap.
 *
 * Point markers (outdoors `outdoor_poi`; the browser demo mirrors these): hut brown house, shelter
 * light-brown house, campsite green triangle, trailhead red square, parking blue square with a
 * white P (marker only), waterfall blue diamond, spring and drinking water small blue circle,
 * viewpoint orange star, cave small black circle. Peaks are dark brown triangles, saddles circles.
 * Toilets, picnic sites, guideposts and individual camp pitches are not drawn.
 *
 * Labels draw last, sized for a phone at the default text scale, with paper-colored halos like the
 * contour labels; places and peaks claim space first, then huts, water, the main POIs, trail and
 * road names, ridge names, and last parking and unnamed features. Contour labels give way to all of
 * them. Ridge names come from `natural_line` (outdoors) or from `mountain_peak` lines (OpenMapTiles);
 * the outdoors `mountain_peak` ridge/arete/cliff label points are not drawn (the line labels say
 * the same along the ridge).
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

    {"id": "cliff", "type": "line", "source-layer": "natural_line", "filter": {"class": "cliff"},
     "minzoom": 12, "color": "#3d2f22", "opacity": 0.9, "width": [[12, 0.9], [15, 1.5]],
     "ticks": {"spacing": [[12, 3.5], [15, 5]], "length": [[12, 3], [15, 4.5]], "width": [[12, 0.9], [15, 1.2]], "side": "downhill"}},
    {"id": "arete", "type": "line", "source-layer": "natural_line", "filter": {"class": "arete"},
     "minzoom": 13, "color": "#4a3b2c", "opacity": 0.6, "width": [[13, 0.7], [15, 1.1]]},

    {"id": "park-boundary", "type": "line", "source-layer": "park", "geometry": "polygon",
     "filter": {"class": ["national_park", "nature_reserve", "protected_area", "state_park"]},
     "color": "#3f8a3a", "opacity": 0.75, "width": [[9, 1.2], [15, 2.2]], "dash": [4, 1.5]},
    {"id": "wilderness-boundary", "type": "line", "source-layer": "park", "geometry": "polygon",
     "filter": {"class": ["wilderness", "wilderness_area"]},
     "color": "#1f7a6e", "opacity": 0.8, "width": [[9, 1.4], [15, 2.6]], "dash": [1, 1.2]},
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
     "casing-color": "#f6f1e499", "casing-width": 0.8,
     "modifiers": [{"filter": {"trail_visibility": ["bad", "horrible", "no", "poor"]}, "opacity": 0.5}]},
    {"id": "trail", "type": "line", "source-layer": "transportation",
     "filter": {"class": "path", "!subclass": ["footway", "pedestrian", "steps"], "!surface": "paved",
                "!sac_scale": ["mountain_hiking", "demanding_mountain_hiking", "alpine_hiking", "demanding_alpine_hiking", "difficult_alpine_hiking"]},
     "minzoom": 11, "geometry": "line", "color": "#c0392b", "width": [[11, 1.1], [15, 2.2]], "dash": [3.5, 1.6],
     "casing-color": "#f6f1e499", "casing-width": 0.9,
     "modifiers": [{"filter": {"trail_visibility": ["bad", "horrible", "no", "poor"]}, "opacity": 0.5},
                   {"filter": {"informal": 1}, "width": 0.65}]},
    {"id": "trail-mountain", "type": "line", "source-layer": "transportation",
     "filter": {"class": "path", "!subclass": "steps", "sac_scale": "mountain_hiking"},
     "minzoom": 11, "geometry": "line", "color": "#b02e1f", "width": [[11, 1.1], [15, 2.2]], "dash": [2, 1.5],
     "casing-color": "#f6f1e499", "casing-width": 0.9,
     "modifiers": [{"filter": {"trail_visibility": ["bad", "horrible", "no", "poor"]}, "opacity": 0.5},
                   {"filter": {"informal": 1}, "width": 0.65}]},
    {"id": "trail-demanding", "type": "line", "source-layer": "transportation",
     "filter": {"class": "path", "!subclass": "steps", "sac_scale": "demanding_mountain_hiking"},
     "minzoom": 11, "geometry": "line", "color": "#86200f", "width": [[11, 1.2], [15, 2.3]], "dash": [1, 1.4],
     "casing-color": "#f6f1e499", "casing-width": 0.9,
     "modifiers": [{"filter": {"trail_visibility": ["bad", "horrible", "no", "poor"]}, "opacity": 0.5},
                   {"filter": {"informal": 1}, "width": 0.65}]},
    {"id": "trail-alpine", "type": "line", "source-layer": "transportation",
     "filter": {"class": "path", "!subclass": "steps", "sac_scale": ["alpine_hiking", "demanding_alpine_hiking", "difficult_alpine_hiking"]},
     "minzoom": 11, "geometry": "line", "color": "#4a1410", "width": [[11, 1.3], [15, 2.4]], "dash": [0.6, 1.4],
     "casing-color": "#f6f1e499", "casing-width": 0.9,
     "modifiers": [{"filter": {"trail_visibility": ["bad", "horrible", "no", "poor"]}, "opacity": 0.5},
                   {"filter": {"informal": 1}, "width": 0.65}]},
    {"id": "footway", "type": "line", "source-layer": "transportation",
     "filter": {"class": "path", "subclass": ["footway", "pedestrian"], "!surface": "paved",
                "!sac_scale": ["mountain_hiking", "demanding_mountain_hiking", "alpine_hiking", "demanding_alpine_hiking", "difficult_alpine_hiking"]},
     "minzoom": 12, "geometry": "line", "color": "#c0392b", "width": [[12, 1.0], [15, 2.0]], "dash": [1.6, 1.4],
     "casing-color": "#f6f1e499", "casing-width": 0.9,
     "modifiers": [{"filter": {"trail_visibility": ["bad", "horrible", "no", "poor"]}, "opacity": 0.5},
                   {"filter": {"informal": 1}, "width": 0.65}]},
    {"id": "path-paved", "type": "line", "source-layer": "transportation",
     "filter": {"class": "path", "!subclass": "steps", "surface": "paved",
                "!sac_scale": ["mountain_hiking", "demanding_mountain_hiking", "alpine_hiking", "demanding_alpine_hiking", "difficult_alpine_hiking"]},
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
    {"id": "huts", "type": "symbol", "source-layer": "outdoor_poi", "geometry": "point", "filter": {"class": "hut"},
     "minzoom": 12, "text": "{name}", "size": 10.5, "color": "#4a2c14", "halo-color": "#f6f1e4dd", "halo-width": 1.3,
     "marker": {"shape": "house", "size": 9, "color": "#6b3a1a"}, "text-optional": true, "spacing": 150},
    {"id": "saddles", "type": "symbol", "source-layer": "mountain_peak", "geometry": "point", "filter": {"class": ["saddle", "pass"]},
     "minzoom": 13, "text": "{name}", "size": 10.5, "color": "#3b2a1a", "halo-color": "#f6f1e4dd", "halo-width": 1.3,
     "marker": {"shape": "circle", "size": 5, "color": "#3b2a1a"}, "sort": "rank"},
    {"id": "water-names", "type": "symbol", "source-layer": "water_name", "geometry": "point",
     "text": "{name}", "size": 11, "italic": true, "color": "#1f5f99", "halo-color": "#f6f1e4cc", "halo-width": 1.3, "spacing": 300},
    {"id": "park-names", "type": "symbol", "source-layer": "park", "geometry": "point", "filter": {"name": true},
     "minzoom": 9, "text": "{name}", "size": 10.5, "italic": true, "color": "#2d6a2a", "halo-color": "#f6f1e4cc", "halo-width": 1.3, "spacing": 800},
    {"id": "trailheads", "type": "symbol", "source-layer": "outdoor_poi", "geometry": "point", "filter": {"class": "trailhead"},
     "minzoom": 13, "text": "{name}", "size": 10, "color": "#7a2418", "halo-color": "#f6f1e4dd", "halo-width": 1.3,
     "marker": {"shape": "square", "size": 7.5, "color": "#b0281c"}, "text-optional": true, "spacing": 150},
    {"id": "campsites", "type": "symbol", "source-layer": "outdoor_poi", "geometry": "point", "filter": {"class": "campsite", "!subclass": "camp_pitch"},
     "minzoom": 13, "text": "{name}", "size": 10, "color": "#1f4f22", "halo-color": "#f6f1e4dd", "halo-width": 1.3,
     "marker": {"shape": "triangle", "size": 8, "color": "#2e7d32"}, "text-optional": true, "spacing": 150},
    {"id": "shelters", "type": "symbol", "source-layer": "outdoor_poi", "geometry": "point", "filter": {"class": "shelter", "!subclass": ["picnic_shelter", "gazebo"]},
     "minzoom": 13, "text": "{name}", "size": 10, "color": "#4a2c14", "halo-color": "#f6f1e4dd", "halo-width": 1.3,
     "marker": {"shape": "house", "size": 7.5, "color": "#a0703c"}, "text-optional": true, "spacing": 150},
    {"id": "waterfalls", "type": "symbol", "source-layer": "outdoor_poi", "geometry": "point", "filter": {"class": "waterfall"},
     "minzoom": 13, "text": "{name}", "size": 10, "italic": true, "color": "#1f5f99", "halo-color": "#f6f1e4cc", "halo-width": 1.3,
     "marker": {"shape": "diamond", "size": 7, "color": "#1f78c8"}, "text-optional": true, "spacing": 150},
    {"id": "viewpoints", "type": "symbol", "source-layer": "outdoor_poi", "geometry": "point", "filter": {"class": "viewpoint"},
     "minzoom": 14, "text": "{name}", "size": 10, "color": "#6b3a00", "halo-color": "#f6f1e4dd", "halo-width": 1.3,
     "marker": {"shape": "star", "size": 8, "color": "#c25e00"}, "text-optional": true, "spacing": 150},
    {"id": "springs", "type": "symbol", "source-layer": "outdoor_poi", "geometry": "point", "filter": {"class": ["spring", "drinking_water"]},
     "minzoom": 14, "text": "{name}", "size": 10, "italic": true, "color": "#1f5f99", "halo-color": "#f6f1e4cc", "halo-width": 1.3,
     "marker": {"shape": "circle", "size": 5.5, "color": "#1f78c8"}, "text-optional": true, "spacing": 150},
    {"id": "caves", "type": "symbol", "source-layer": "outdoor_poi", "geometry": "point", "filter": {"class": "cave"},
     "minzoom": 14, "text": "{name}", "size": 10, "color": "#222222", "halo-color": "#f6f1e4dd", "halo-width": 1.3,
     "marker": {"shape": "circle", "size": 5.5, "color": "#222222"}, "text-optional": true, "spacing": 150},
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
    {"id": "natural-line-names", "type": "symbol", "source-layer": "natural_line", "placement": "line",
     "filter": {"name": true}, "minzoom": 12,
     "text": "{name}", "size": 10, "italic": true, "color": "#5a4632", "halo-color": "#f6f1e4cc", "halo-width": 1.3, "spacing": 500},
    {"id": "parking", "type": "symbol", "source-layer": "outdoor_poi", "geometry": "point", "filter": {"class": "parking", "!subclass": "street_side"},
     "minzoom": 14, "text": "", "size": 10, "color": "#2b5fa8", "halo-color": "#f6f1e4dd", "halo-width": 1.3,
     "marker": {"shape": "square", "size": 8, "color": "#2b5fa8", "text": "P"}, "text-optional": true, "spacing": 60},
    {"id": "peaks-unnamed", "type": "symbol", "source-layer": "mountain_peak", "geometry": "point",
     "filter": {"class": ["peak", "volcano", "hill"], "name": false}, "minzoom": 12,
     "text": "{elevation}", "size": 10.5, "color": "#3b2a1a", "halo-color": "#f6f1e4dd", "halo-width": 1.3,
     "marker": {"shape": "triangle", "size": 6, "color": "#3b2a1a"}, "sort": "-ele", "spacing": 100}
  ]
}
"""

    val style: OsmStyle by lazy { OsmStyle.parse(JSON) }
}
