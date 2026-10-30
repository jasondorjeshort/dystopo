"""T154: android-check.py's tiles check offline: the viewport's tiles at the zooms MapLibre requests each source at, the
expectation per source from the tiles that overlap the pack's bounds, and the request log's pack-answered tiles that
count when the view's own delta is 0, with the cases that must not count (the online fallback, a tile outside the view,
another zoom or source, an error status, an empty log). T170: a contour delta proven by the view's own pack-answered lines.

    python -I tools/test_android_check.py
"""
import importlib.util
import os

here = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("android_check", os.path.join(here, "android-check.py"))
ac = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ac)

# The z8 view of the tour over the Rainier packs (public; the boxes are the outdoors pack's, docs/reference/machines.md).
VIEW = ac.parse_view("8/46.85/-121.72:region-z8")
BOX = [-122.2, 45.9, -121.2, 47.8]  # a phone-shaped box around that centre at z8: three tiles tall, one wide
PACK = {"bounds": [-121.95, 46.68, -121.45, 46.98], "minZoom": 6, "maxZoom": 14}
DEM = {"bounds": [-121.95, 46.68, -121.45, 46.98], "minZoom": 8, "maxZoom": 14}
STYLE = {"dem": {"type": "raster-dem", "tileSize": 256, "minzoom": 8, "maxzoom": 14},
         "osm": {"type": "vector", "minzoom": 0, "maxzoom": 14}, "contours": {"type": "vector", "minzoom": 8, "maxzoom": 15}}
HEALTH = {"packs": {"dem": DEM, "osm": PACK}}

# Tiles and zooms.
w, s, e, n = ac.tile_box(8, 41, 90)
assert abs(s - 46.07) < 0.01 and abs(n - 47.04) < 0.01, (s, n)
assert ac.viewport_tiles(BOX, 8) == [(8, 41, 89), (8, 41, 90), (8, 41, 91)], ac.viewport_tiles(BOX, 8)
assert ac.viewport_tiles([-121.0, 46.0, -120.9375, 46.5], 8) == [(8, 41, 90), (8, 41, 91)]  # an edge alone is not a tile
assert ac.covering_zooms(8, STYLE["osm"]) == [8] and ac.covering_zooms(8, STYLE["dem"]) == [9]
assert ac.covering_zooms(13.5, STYLE["dem"]) == [14]  # floor(14.5) and round(14.5) = 15, clamped to the maxzoom
assert ac.covering_zooms(16, STYLE["osm"]) == [14] and ac.covering_zooms(7, STYLE["contours"]) == []
assert ac.covering_zooms(8, None) == [8]

# The expectation: one of the three z8 OSM tiles overlaps the pack, so osm is expected, with that tile named.
x = ac.source_expectations(HEALTH, VIEW, BOX, STYLE)
assert x["osm"]["expected"] and x["osm"]["packTiles"] == ["8/41/90"], x["osm"]
assert x["osm"]["reason"] == "1 of 3 viewport tiles at z8 overlap the pack's bounds", x["osm"]["reason"]
assert x["dem"]["expected"] and x["dem"]["packTiles"] == ["9/82/180", "9/83/180"], x["dem"]
assert x["contours"]["expected"]
# A view whose box misses the pack expects nothing from it, though its zoom is in range; a zoom out of range neither.
far = ac.source_expectations(HEALTH, ac.parse_view("8/46.85/-119.5"), [-120.0, 45.9, -119.0, 47.8], STYLE)
assert not far["osm"]["expected"] and far["osm"]["reason"].startswith("0 of "), far["osm"]
low = ac.source_expectations(HEALTH, ac.parse_view("3/46.85/-121.72"), BOX, STYLE)
assert not low["osm"]["expected"] and "outside the pack's 6 to 14" in low["osm"]["reason"], low["osm"]
# No viewBounds (an older app): the centre test, as before T154.
old = ac.source_expectations(HEALTH, VIEW, None, STYLE)
assert old["osm"]["expected"] and "no viewBounds" in old["osm"]["reason"]
# No pack (removed from the phone): nothing expected unless the view asks with |req=, which still names the reason.
gone = ac.source_expectations({"packs": {}}, ac.parse_view("11/46.75/-121.81:longmire-z11|req=dem+osm+contours"), BOX, STYLE)
assert all(gone[s]["expected"] for s in ac.SOURCES) and gone["osm"]["reason"] == "|req= in the view (no pack in /health)", gone


def line(path, pack="rainier", status=200, route=None):
    return {"path": path, "route": route or path.split("/")[1], "pack": pack, "status": status, "ts": "2026-10-27T00:00:00Z"}


def check(served, log, expectation=x):
    tiles = {k: {"served": served, "notFound": 0, "errors": 0} for k in ac.SOURCES}
    ok, missing = ac.tiles_check(tiles, ["osm"], log, expectation)
    return ok, missing, tiles["osm"]


# The z8 case: delta 0, and the pack answered the overlapping tile at the app's start: counted, and reported.
ok, missing, t = check(0, [line("/osm/7/20/45.pbf"), line("/osm/8/41/90.pbf"), line("/osm/8/41/90.pbf")])
assert ok and missing == [] and t["servedBeforeBaseline"] == 1 and t["beforeBaseline"][0]["tile"] == "8/41/90", t
# A delta of its own needs no log.
ok, _, t = check(2, None)
assert ok and "servedBeforeBaseline" not in t
# The negative cases: nothing a pack answered for this view, so the check fails and says which source.
for log in ([], None,
            [line("/osm/8/41/90.pbf", pack="online:direct://sources")],  # the online fallback
            [line("/osm/8/41/90.pbf", pack=None)],  # nobody answered
            [line("/osm/8/40/90.pbf")],  # a tile outside the view's box
            [line("/osm/8/41/89.pbf")],  # a viewport tile the pack's bounds do not overlap
            [line("/osm/7/20/45.pbf")],  # the view's area at another zoom (MapLibre's prefetch of parents)
            [line("/osm/8/41/90.pbf", status=404)],
            [line("/dem/8/41/90.png")]):  # another source
    ok, missing, t = check(0, log)
    assert not ok and missing == ["osm"] and t["servedBeforeBaseline"] == 0, (log, t)
# Without the view's box nothing before the baseline counts (the centre test cannot place a tile).
assert check(0, [line("/osm/8/41/90.pbf")], old)[:2] == (False, ["osm"])
# The pack removed and the app reloaded in the same process: the log still holds the tile the pack answered, but no
# pack in /health means no overlapping tile, so a view that asks for osm with |req= fails (the negative case).
assert check(0, [line("/osm/8/41/90.pbf")], gone)[:2] == (False, ["osm"])

# T170 (T154's follow-up): a contour delta made over online DEM alone does not prove the pack. The view's own lines of
# the request log decide: a pack-answered contour tile passes, online answers alone fail, no lines read keeps the delta.
def check_contours(served, view_lines, log=None, expectation=x):
    tiles = {k: {"served": served, "notFound": 0, "errors": 0} for k in ac.SOURCES}
    ok, missing = ac.tiles_check(tiles, ["contours"], log, expectation, view_lines)
    return ok, missing, tiles["contours"]


ONLINE = "online:direct://sources"
x["contours"]["packTiles"] = ["9/82/180"]  # the viewport's contour tile over the pack (the rest lie outside its bounds)
ok, _, t = check_contours(3, [line("/contours/9/82/180.pbf"), line("/contours/9/81/180.pbf", pack=ONLINE)])
assert ok and t["servedFromPack"] == 1 and t["servedOnline"] == 1, t
ok, missing, t = check_contours(3, [line("/contours/9/81/180.pbf", pack=ONLINE), line("/contours/9/81/181.pbf", pack=ONLINE)], log=[])
assert not ok and missing == ["contours"] and t["servedFromPack"] == 0 and t["servedOverPack"] == 0 and t["servedOnline"] == 2, t
# The tour's region-z10 case: the tile over the pack's edge is made by the online fallback over both elevations: counted.
ok, _, t = check_contours(3, [line("/contours/9/82/180.pbf", pack=ONLINE), line("/contours/9/81/180.pbf", pack=ONLINE)], log=[])
assert ok and t["servedFromPack"] == 0 and t["servedOverPack"] == 1, t
# The pack removed (no pack in /health, the view asks with |req=): no packTiles, so online answers never count.
assert check_contours(3, [line("/contours/9/82/180.pbf", pack=ONLINE)], log=[], expectation=gone)[:2] == (False, ["contours"])
assert check_contours(3, None)[:2] == (True, [])  # the view's lines not read (an older app, or more than the log keeps)
assert check_contours(3, [line("/osm/8/41/90.pbf")], log=[])[:2] == (False, ["contours"])  # another source's line
assert check_contours(3, [line("/contours/9/82/180.pbf", status=404)], log=[])[:2] == (False, ["contours"])
# Online answers in the view, but the pack answered one of its viewport tiles before the baseline: counted as T154 does.
ok, _, t = check_contours(3, [line("/contours/9/81/180.pbf", pack=ONLINE)], log=[line("/contours/9/82/180.pbf")])
assert ok and t["servedBeforeBaseline"] == 1, t
# dem and osm keep the plain delta (their online answers come only where no pack covers the tile).
tiles = {k: {"served": 1, "notFound": 0, "errors": 0} for k in ac.SOURCES}
assert ac.tiles_check(tiles, ["dem", "osm"], [], x, [line("/dem/9/81/180.png", pack=ONLINE)]) == (True, [])
assert ac.pack_answered([line("/contours/9/82/180.pbf", pack=None), line("/contours/9/82/180.pbf")], "contours") == [line("/contours/9/82/180.pbf")]

print("test_android_check: ok")
