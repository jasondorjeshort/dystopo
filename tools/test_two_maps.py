"""T339: the two-maps fixture and its sweep part, offline (no device, no build).  Run: python -I tools/test_two_maps.py"""
import importlib.util
import json
import os
import shutil
import tempfile

here = os.path.dirname(os.path.abspath(__file__))


def load(name, file):
    spec = importlib.util.spec_from_file_location(name, os.path.join(here, file))
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


fx = load("two_maps_fixture", "two-maps-fixture.py")
part = load("two_maps_part", "two-maps-part.py")

# The recipe: public Rainier only, the fine map inside the wide one, both from z8, the wide one coarser.
with open(os.path.join(os.path.dirname(here), "areas.json"), encoding="utf-8") as fh:
    areas = json.load(fh)["areas"]
w, s, e, n = areas["rainier"]["bbox"]
wide, fine = fx.MAPS
assert wide["name"] == "rainier-wide" and fine["name"] == "rainier-fine"
assert wide["bbox"][0] <= fine["bbox"][0] and wide["bbox"][1] <= fine["bbox"][1] and fine["bbox"][2] <= wide["bbox"][2] and fine["bbox"][3] <= wide["bbox"][3], "the fine map lies inside the wide one"
assert wide["bbox"][0] <= w and wide["bbox"][2] >= e and wide["bbox"][1] <= s and wide["bbox"][3] >= n, "the wide map holds the rainier area"
assert wide["dem"][1] < fine["dem"][1] and wide["vector"][1] <= fine["vector"][1], "the wide map is the coarse one"
assert wide["dem"][0] == fine["dem"][0] == wide["vector"][0] == fine["vector"][0] == 8, "both start at z8, as the phone's downloads"

# The four places: each one inside exactly the maps it says, and one of each kind.
assert [p["id"] for p in fx.PLACES] == ["inside-both", "wide-only", "outside", "fine-edge"]
for p in fx.PLACES:
    got = [m["name"] for m in fx.MAPS if fx.inside(p, m)]
    assert got == p["inside"], (p["id"], got)
outside = fx.PLACES[2]
for m in fx.MAPS:  # outside both maps' tiles at every zoom, the coarsest (z8) included
    assert not any(fx.tiles_hold(outside, m, z) for z in range(m["dem"][0], m["dem"][1] + 1)), m["name"]
assert fx.tiles_hold(fx.PLACES[1], fine, 8) and not fx.tiles_hold(fx.PLACES[1], fine, 10), "wide-only is in the fine map's z8 tile only"
w8, s8, e8, n8 = fx.tile_bounds(8, 41, 90)
assert round(s8, 2) == 46.07 and round(n8, 2) == 47.04 and w8 < -121.95 and e8 > -121.45, (w8, s8, e8, n8)
edge = fx.PLACES[3]
assert edge["lat"] - fine["bbox"][1] < 0.005, "the edge place is within about 500 m of the fine map's edge"

# The build commands: devtool tiles for the DEM, tilebuild for the vector map, into the folder given.
cmds = fx.commands(fine, "out/two-maps")
assert cmds["dem"][0] == ":devtool:run" and "--pmtiles out/two-maps/rainier-fine-dem.pmtiles" in cmds["dem"][1], cmds
assert "--min-zoom 8 --max-zoom 14" in cmds["dem"][1] and "--bbox=-121.82,46.8,-121.68,46.9" in cmds["dem"][1], cmds
assert cmds["vector"][0] == ":tilebuild:run" and "--profile outdoors" in cmds["vector"][1] and cmds["vector"][1].endswith("--out out/two-maps/rainier-fine-outdoors.pmtiles"), cmds
assert fx.EXTRACT in cmds["vector"][1]

# Built once: a folder with every archive and the same recipe needs nothing; another recipe or a missing file rebuilds.
names = [nm for m in fx.MAPS for nm in fx.files_of(m).values()]
tmp = tempfile.mkdtemp(prefix="two-maps-test-")
try:
    assert fx.missing(tmp) == names, "an empty folder needs everything"
    for nm in names:
        with open(os.path.join(tmp, nm), "wb") as fh:
            fh.write(b"x")
    assert fx.missing(tmp) == names, "no fixture.json: the archives are of an unknown recipe"
    fx.write_stamp(tmp, names)
    assert fx.missing(tmp) == [], "same recipe, every archive: nothing to build"
    os.remove(os.path.join(tmp, names[1]))
    assert fx.missing(tmp) == [names[1]]
    with open(os.path.join(tmp, names[1]), "wb") as fh:
        fh.write(b"x")
    with open(os.path.join(tmp, "fixture.json"), "w", encoding="utf-8") as fh:
        json.dump({"key": "another"}, fh)
    assert fx.missing(tmp) == names, "another recipe rebuilds everything"
    # A second build over a complete folder runs no Gradle (gradle() would fail the test if called).
    fx.write_stamp(tmp, names)
    called = []
    real = fx.gradle
    fx.gradle = lambda *a: called.append(a) or 1
    try:
        rel = os.path.relpath(tmp, fx.REPO)
        rep = fx.build(None, rel)
        assert rep["ok"] and rep["built"] == [] and sorted(rep["kept"]) == sorted(names) and not called, rep
        # --from: a complete set elsewhere is copied, nothing built.
        tmp2 = tempfile.mkdtemp(prefix="two-maps-test-")
        try:
            rep = fx.build(tmp, os.path.relpath(tmp2, fx.REPO))
            assert rep["ok"] and sorted(rep["copied"]) == sorted(names) and rep["built"] == [] and not called, rep
            assert fx.missing(tmp2) == []
        finally:
            shutil.rmtree(tmp2, ignore_errors=True)
    finally:
        fx.gradle = real
finally:
    shutil.rmtree(tmp, ignore_errors=True)

# GET /packs' names.
assert fx.pack_names({"packs": [{"name": "rainier-wide"}, {"name": "rainier-fine"}], "inUse": [{"name": "rainier-fine"}]}) == (["rainier-wide", "rainier-fine"], ["rainier-fine"])
assert fx.pack_names(None) == ([], [])

# The row's summary from the routes' answers (missing fields read None, never raise).
reads = {
    "firstrun": {"noPack": False, "mapMessage": "none", "viewInside": True, "textShown": None, "cardVisible": False},
    "online": {"state": "online", "offerDownload": True, "chip": {"text": "On this phone", "source": "pack", "dot": "green"}},
    "areasRows": {"rows": [{"id": "pack:rainier-wide", "state": "Saved", "detail": ["Zooms z8-13", "Elevation: " + "x" * 80]}], "count": 1},
    "maps": {"packs": ["rainier-wide", "rainier-fine"], "inUse": ["rainier-fine"], "active": "rainier-fine", "startupPacks": 2},
    "horizon": {"horizon": {"source": "pack", "zoom": 12, "highestDeg": 3.2, "bearingsWithoutData": 0}, "horizonReason": None, "placeLine": {"text": "Here"}},
    "horizonFirst": None,
    "panorama": {"showing": "panorama", "work": {"reason": None, "terrain": {"demPacks": ["rainier-fine"], "tiles": 9, "network": False}, "sweeps": [{}, {}]}, "names": [{}], "stepDrawn": 0.5},
}
sm = part.summarize(reads)
assert sm["map"]["mapMessage"] == "none" and sm["chip"]["offerDownload"] is True and sm["chip"]["text"] == "On this phone"
assert sm["rows"] == [{"id": "pack:rainier-wide", "state": "Saved", "detail": ["Zooms z8-13"]}], "the long credit lines are left out"
assert sm["horizon"]["source"] == "pack" and sm["horizon"]["highestDeg"] == 3.2 and sm["horizonFirst"]["source"] is None
assert sm["panorama"]["demPacks"] == ["rainier-fine"] and sm["panorama"]["sweeps"] == 2 and sm["panorama"]["names"] == 1
assert part.summarize({})["panoramaFirst"]["sweeps"] == 0

# The verdict: only unread rows, a short table, an error or a failed restore fail; a row's content never does.
def row(net, place, ok=True):
    return {"network": net, "place": place, "ok": ok, "unread": [] if ok else [{"read": "horizon", "error": "HTTP 500"}]}

full = {"rows": [row(n, p["id"]) for n in part.PHASES for p in fx.PLACES], "restore": {"ok": True}}
assert part.verdict(full) == [], part.verdict(full)
short = dict(full, rows=full["rows"][:7])
assert [f["what"] for f in part.verdict(short)] == ["rows"]
bad = dict(full, rows=full["rows"][:7] + [row("on", "fine-edge", ok=False)])
assert [f["what"] for f in part.verdict(bad)] == ["unread"] and part.verdict(bad)[0]["row"] == "on/fine-edge"
assert [f["what"] for f in part.verdict(dict(full, restore={"ok": False}))] == ["restore"]
assert part.assertions(full) == [], "rows without a summary assert nothing"

# T340's block: the same source and sentence in both views, no empty lens, Peaks finished, a skyline online outside.
def srow(net, place, hs, ps, hr=None, pr=None, sweeps=2, peaks="3 peaks"):
    return {"network": net, "place": place, "ok": True, "summary": {
        "horizon": {"source": hs, "reason": hr}, "panorama": {"source": ps, "reason": pr, "sweeps": sweeps, "peaksLine": peaks}}}
good = [srow("on", "outside", "online source", "online source"), srow("off", "outside", None, None, "No data.", "No data.", 0, "No terrain.")]
assert part.assertions({"rows": good}) == [], part.assertions({"rows": good})
bad = [srow("on", "outside", "online source", None, pr="No elevation data here", sweeps=0),
       srow("on", "inside-both", "2 offline maps", "2 offline maps", peaks="Finding the peaks in view..."),
       srow("off", "outside", None, None, "No data.", None, 0)]
assert sorted(f["what"] for f in part.assertions({"rows": bad})) == sorted([
    "T340:same-source", "T340:outside-online", "T340:peaks-unfinished", "T340:same-sentence", "T340:panorama-empty"]), part.assertions({"rows": bad})

# T352's block: peaks and a skyline in every row inside a map; its three rows (each map primary, an activate with the
# Panorama open) read, with peaks, the same whichever map is primary, and no ClosedChannelException.
sm = part.summarize({"horizon": {"peaks": {"candidates": 5, "tilesRead": 9, "maps": ["rainier-wide"], "reason": None}},
                     "panorama": {"candidates": 4, "work": {"sweeps": [{}], "peaks": {"tiles": 12, "maps": ["rainier-fine"]}}}})
assert sm["horizon"]["peakCandidates"] == 5 and sm["horizon"]["peakMaps"] == ["rainier-wide"] and sm["horizon"]["peakTiles"] == 9
assert sm["panorama"]["peakMaps"] == ["rainier-fine"] and sm["panorama"]["peakTiles"] == 12


def prow(net, place, inside, hc, pc, sweeps=2):
    return {"network": net, "place": place, "inside": inside, "ok": True, "summary": {
        "horizon": {"source": "2 offline maps", "peakCandidates": hc}, "panorama": {"source": "2 offline maps", "candidates": pc, "sweeps": sweeps}}}


def t352(kind, name, hc=5, pc=4, closed=0, ok=True, source="2 offline maps"):
    r = {"kind": kind, "ok": ok, "summary": {"horizon": {"peakCandidates": hc, "source": source}, "panorama": {"candidates": pc, "sweeps": 2}}}
    r["primary" if kind == "primary" else "activated"] = name
    if kind == "activate-open":
        r["logcat"] = {"closedChannel": closed}
    return r


good_rows = [prow("off", "inside-both", ["rainier-wide", "rainier-fine"], 5, 4), prow("off", "outside", [], 0, 0)]
good_extra = {"rows": [t352("primary", "rainier-wide"), t352("primary", "rainier-fine"), t352("activate-open", "rainier-wide")]}
assert part.assertions({"rows": good_rows, "t352": good_extra}) == [], part.assertions({"rows": good_rows, "t352": good_extra})
assert part.assertions({"rows": good_rows}) == [], "a table without T352's rows from a run that did not install asserts nothing more"
assert [f["what"] for f in part.assertions({"rows": good_rows, "install": {"ok": True}})] == ["T352:rows-missing"]
bad_rows = [prow("on", "wide-only", ["rainier-wide"], 0, 3), prow("off", "fine-edge", ["rainier-wide", "rainier-fine"], 2, 0, 0)]
bad_extra = {"rows": [t352("primary", "rainier-wide", hc=5), t352("primary", "rainier-fine", hc=0, source="offline map"),
                      t352("activate-open", "rainier-wide", closed=2, ok=False)]}
got = sorted(f["what"] for f in part.assertions({"rows": bad_rows, "t352": bad_extra}) if f["what"].startswith("T352"))
assert got == sorted(["T352:horizon-peaks", "T352:panorama-peaks", "T352:horizon-peaks", "T352:unread", "T352:closed-channel",
                      "T352:primary-matters"]), got
assert [f["what"] for f in part.assertions({"rows": [], "t352": {"rows": good_extra["rows"][:2]}})] == ["T352:rows"]
# end T352

# T342's block: with the fine map off every reader is on the wide one, on again (no restart) it is read again, both off the
# outside message with its action; the rows' switches and Storage's bytes in every case.
W, F = fx.MAPS[0]["name"], fx.MAPS[1]["name"]
def t342_row(case, in_use, hm, pm, off, pid=4242, message="none", action=False):
    return {"case": case, "ok": True, "pid": pid, "horizonMaps": hm, "panoramaMaps": pm, "off": off, "storageBytes": {W: 10, F: 5},
            "summary": {"maps": {"inUse": in_use}, "map": {"mapMessage": message, "actionShown": action, "noPack": False, "cardVisible": False}}}
t342_good = {"switches": [{"name": F, "off": True}], "rows": [
    t342_row("fine-off", [W], [W], [W], {W: False, F: True}),
    t342_row("fine-on-again", [W, F], [F, W], [F, W], {W: False, F: False}),
    t342_row("both-off", [], None, None, {W: True, F: True}, pid=4343, message="outside", action=True)]}
assert part.t342_assertions({"t342": t342_good}) == [], part.t342_assertions({"t342": t342_good})
assert part.t342_assertions({}) == [], "a table without the block's rows asserts nothing"
t342_bad = {"switches": [{"name": F, "off": True, "error": "HTTP 500"}], "rows": [
    t342_row("fine-off", [W, F], [F, W], [W], {W: False, F: False}),
    t342_row("fine-on-again", [W], [W], [W], {W: False, F: False}, pid=1),
    t342_row("both-off", [], None, None, {W: True, F: True}, message="nothingHeld")]}
t342_bad["rows"][0]["storageBytes"] = {W: 10, F: None}
assert sorted(f["what"] for f in part.t342_assertions({"t342": t342_bad})) == sorted([
    "T342:switch", "T342:rows-off", "T342:storage", "T342:fine-off-inUse", "T342:fine-off-horizon", "T342:on-again-inUse",
    "T342:on-again-horizon", "T342:on-again-panorama", "T342:restarted", "T342:both-off-message"]), part.t342_assertions({"t342": t342_bad})
# end T342

# T343's block: the three cards (held coarser, nothing new, nothing held) and the chip and the button agreeing in every row.
def t343_card(case, held=None, nothing_new=None, can=True, source="on this phone", enabled=True):
    return {"case": case, "errors": [], "heldLine": held, "nothingNew": nothing_new, "canDownload": can, "source": source,
            "button": {"shown": True, "enabled": enabled}}
t343_good = {"cards": [t343_card("coarser", held="On this phone at low detail, with routing and place search"),
                       t343_card("nothing-new", held="On this phone at full detail", nothing_new=f"Already on this phone in {F}: nothing new at this detail",
                                 can=False, enabled=False),
                       t343_card("nothing", source="no data here")]}
assert part.t343_assertions({"t343": t343_good}) == [], part.t343_assertions({"t343": t343_good})
assert part.t343_assertions({}) == [], "a table without the block's cards asserts nothing"
t343_bad = {"cards": [t343_card("coarser"), t343_card("nothing-new", can=True), t343_card("nothing", held="On this phone at low detail")]}
assert sorted(f["what"] for f in part.t343_assertions({"t343": t343_bad})) == ["T343:coarser", "T343:nothing", "T343:nothing-new"], part.t343_assertions({"t343": t343_bad})
import tempfile
with tempfile.TemporaryDirectory() as raw:
    with open(os.path.join(raw, "on-inside-both-online.json"), "w", encoding="utf-8") as fh:
        json.dump({"body": {"chip": {"download": {"shown": True, "text": "Already on this phone", "enabled": False}}}}, fh)
    rows = [{"network": "on", "place": "inside-both", "summary": {"chip": {"source": "from public sources", "text": "Online · from public sources"}}}]
    whats = sorted(f["what"] for f in part.t343_assertions({"rows": rows, "t343": {"cards": t343_good["cards"], "rawDir": raw}}))
    assert whats == ["T343:already-not-on-phone", "T343:off-phone-not-offered"], whats
# end T343

# T341's block: GET /why and the chip at the overview's zooms, offline and online.
def t341_read(net, z, cause, serves, column, fetch_behind=False, errors=None):
    return {"network": net, "zoom": z, "errors": errors or [], "cause": cause, "vector": {"serves": serves, "fetchBehind": fetch_behind},
            "column": column, "chip": column}
t341_good = {"reads": [
    t341_read("off", 5.5, "overview-offline", "overview", "world"), t341_read("off", 6.5, "above-overview-top", "network", "wider"),
    t341_read("off", 12, "none", "map", "pack"), t341_read("on", 5.5, "none", "cache", "network_public"),
    t341_read("on", 6.5, "none", "network", "network_public"), t341_read("on", 12, "none", "map", "pack")]}
assert part.t341_assertions({"t341": t341_good}) == [], part.t341_assertions({"t341": t341_good})
assert part.t341_assertions({}) == []
t341_bad = {"reads": [
    t341_read("off", 5.5, "overview", "cache", "cache"), t341_read("off", 6.5, "empty-tile", "network", "no_data"),
    t341_read("on", 5.5, "overview", "overview", "world"), t341_read("on", 6.5, "none", "network", "no_data"),
    t341_read("on", 12, "below-maps-floor", "network", "no_data"), t341_read("on", 13, None, None, None, errors=["HTTP 500"])]}
whats = sorted(f["what"] for f in part.t341_assertions({"t341": t341_bad}))
assert whats == ["T341:chip-no-data", "T341:cut-from-overview", "T341:map-cause", "T341:overview-offline", "T341:overview-online", "T341:unread"], whats
# end T341

# T354's block: GET /holdings against /why and the sky views, each map chosen in turn at the four places.
def t354_row(chosen, place, vec, dem, members, hm, inside=True, read_zoom=14, horizon_zoom=14, why_vec=None, why_dem=None):
    role = lambda s, ms: {"serves": s, "tilePathServes": s, "members": ms, "chosen": [m for m in ms if m == chosen]}
    return {"chosen": chosen, "activate": True, "place": place, "inside": [W] if inside else [], "errors": [],
            "vector": role(vec, [vec] if vec else []), "dem": role(dem, members), "readZoom": read_zoom, "horizonZoom": horizon_zoom,
            "whyVector": why_vec if why_vec is not None else [m for m in [vec] if m], "whyDem": why_dem if why_dem is not None else [m for m in [dem] if m],
            "whyPrimary": [chosen], "horizonMaps": hm, "panoramaMaps": hm}
t354_good = {"rows": [t354_row(c, p, v, d, ms, hm, inside=ins, read_zoom=rz, horizon_zoom=rz) for c in (W, F) for (p, v, d, ms, hm, ins, rz) in (
    ("inside-both", F, F, [F, W], [F, W], True, 14), ("wide-only", W, W, [W], [F, W], True, 11),
    ("outside", None, None, [], [], False, None), ("fine-edge", W, W, [F, W], [F, W], True, 14))]}
assert part.t354_assertions({"t354": t354_good}) == [], part.t354_assertions({"t354": t354_good})
assert part.t354_assertions({}) == [], "a table without the block's rows asserts nothing"
t354_bad = {"rows": [t354_row(W, "inside-both", W, W, [W, F], [F, W], why_dem=[F]),  # the chosen map ahead of the deeper one
                     t354_row(F, "inside-both", F, F, [F, W], [F, W], read_zoom=11),
                     t354_row(W, "wide-only", W, W, [W], [W]), t354_row(F, "wide-only", W, W, [W], [W])]}
t354_bad["rows"][2]["errors"] = [{"read": "holdings", "status": 404}]
t354_bad["rows"][3]["dem"]["tilePathServes"] = F
whats = sorted(f["what"] for f in part.t354_assertions({"t354": t354_bad}))
assert whats == sorted(["T354:rows", "T354:why-dem", "T354:horizon-order", "T354:panorama-order", "T354:fine-inside", "T354:read-zoom",
                        "T354:unread", "T354:dem-serves-tile-path", "T354:why-dem", "T354:chosen-matters"]), whats
# end T354

# T356's block: an area's z15 cells inside the coarse map, offline and with no source set.
png = part.t356_png(15, 5319, 11561, size=8)
assert png[:4] == bytes([0x89]) + b"PNG" and b"IHDR" in png and b"IDAT" in png, "a PNG"
_, tx, ty = part.t356_tile(15, 46.73, -121.56)
lines = [{"path": f"/dem/15/{tx}/{ty}.png", "pack": "area:t356-held", "status": 200}, {"path": f"/contours/15/{tx + 1}/{ty}.pbf", "pack": "area:t356-held", "status": 200},
         {"path": f"/dem/15/{tx + 5}/{ty}.png", "pack": "rainier-wide", "status": 200}, {"path": "/osm/14/1/1.pbf", "pack": None, "status": 200}]
t356_seen = part.t356_here([{"path": "/dem/15/0/0.png"}] + lines, 1, 5, 46.73, -121.56)
assert [h["kind"] for h in t356_seen] == ["dem", "contours"], t356_seen
assert part.t356_here(lines, 5, 5, 46.73, -121.56) == [], "nothing new"
ph = lambda name, serves="t356-held", z15=None: {"phase": name, "errors": [], "dem": {"serves": serves, "tilePathServes": serves}, "z15Here": z15 if z15 is not None else t356_seen}
t356_good = {"download": {"state": "done", "inPacks": 30, "demFetchedByZoom": {"12": 4, "13": 6, "14": 9, "15": 20}}, "phases": [ph("airplane"), ph("no-source")]}
assert part.t356_assertions({"t356": t356_good}) == [], part.t356_assertions({"t356": t356_good})
assert part.t356_assertions({}) == []
t356_bad = {"download": {"state": "done", "inPacks": 30, "demFetchedByZoom": {"12": 4}},  # the old skip: nothing past the map's z11 inside it
            "phases": [ph("airplane", serves="rainier-wide"), ph("no-source", z15=[{"kind": "dem", "pack": None, "status": 404}])]}
whats = sorted(f["what"] for f in part.t356_assertions({"t356": t356_bad}))
assert whats == sorted(["T356:download-skip", "T356:holdings", "T356:request-log", "T356:z15-404"]), whats
# end T356

# T368's block: the network's deeper elevation fetched behind the coarse map, the setting on and off.
ph = lambda detail, **k: dict({"onlineDetail": detail, "errors": [], "started": 0, "landed": 0, "failed": 0, "inFlight": 0, "waiting": 0, "refreshes": 0,
                               "recent": [], "serves": "rainier-wide", "z15": {}, "upgraded": 0}, **k)
t368_good = {"phases": [ph(1, started=24, landed=24, refreshes=1, serves="cache", upgraded=6), ph(0)]}
assert part.t368_assertions({"t368": t368_good}) == [], part.t368_assertions({"t368": t368_good})
assert part.t368_assertions({}) == []
t368_bad = {"phases": [ph(1, started=24, landed=20, inFlight=4, recent=[{"ended": "style changed"}], refreshes=1, serves="rainier-wide"), ph(0, started=3)]}
whats = sorted(f["what"] for f in part.t368_assertions({"t368": t368_bad}))
assert whats == sorted(["T368:fetch-behind", "T368:refresh", "T368:holdings-on", "T368:upgraded", "T368:setting-off-fetched"]), whats
# end T368

# T359's block: the chip, the button, search and the first-run rule against GET /holdings, the network off and on.
def t359_row(net, place, z, source, vec, dem, msg="none", already=False, shown=None, network=None):
    on = net == "on"
    return {"network": net, "place": place, "zoom": z, "errors": [], "source": source, "chip": f"x · {source}", "chipNetwork": on if network is None else network,
            "allowed": on if network is None else network, "button": {"shown": on if shown is None else shown, "text": "Already on this phone" if already else "Download this area"},
            "holdings": {"vector": {"tilePathServes": vec}, "dem": {"tilePathServes": dem}}, "mapMessage": msg, "cardVisible": False, "noPack": False}
t359_rows = []
for net in ("off", "on"):
    for place in ("inside-both", "wide-only", "outside", "fine-edge"):
        for z in part.T359_ZOOMS:
            m = "rainier-fine" if place != "outside" else None
            vec = m if z >= 8 else None
            if place == "outside":
                src = "from your server" if net == "on" else "no data here"
            elif vec is None and net == "on":
                src = "from your server"
            else:
                src = "on this phone"
            t359_rows.append(t359_row(net, place, z, src, vec, m, msg="outside" if (net == "off" and place == "outside") else "none"))
t359_search = [{"network": n, "place": "wide-only", "reason": "found", "coverage": "vector", "hits": [part.T359_SEARCH]} for n in ("off", "on")] + \
              [{"network": n, "place": "outside", "reason": "no-map", "coverage": "no-map", "sentence": "Search needs an offline map here"} for n in ("off", "on")]
t359_off = [dict(t359_row("all-off", "inside-both", 12, "a map here is off", None, None, network=False)), dict(t359_row("all-off", "outside", 12, "no data here", None, None, msg="outside", network=False))]
t359_good = {"rows": t359_rows, "search": t359_search, "allOff": t359_off}
assert part.t359_assertions({"t359": t359_good}) == [], part.t359_assertions({"t359": t359_good})
assert part.t359_assertions({}) == []
t359_bad_rows = [dict(r) for r in t359_rows]
t359_bad_rows[0] = dict(t359_rows[0], chipNetwork=True)  # off/inside-both/z12: the chip's network test true in airplane mode
t359_bad_rows[1] = dict(t359_rows[1], source="on this phone", holdings={"vector": {"tilePathServes": None}, "dem": {"tilePathServes": "cache"}})  # cache served, chip on this phone
t359_bad_rows[9] = dict(t359_rows[9], source="on this phone")  # on/inside-both/z7.5: T341's follow-up, the roads online
t359_bad_rows[12] = dict(t359_rows[12], source="no data here")  # on/outside/z12: T339's follow-up
t359_bad_rows[13] = dict(t359_rows[13], button={"shown": True, "text": "Already on this phone"})  # on/outside/z7.5: already where the chip is not on the phone
t359_bad_rows[4] = dict(t359_rows[4], mapMessage="none")  # off/outside/z12: no message offline outside both maps
t359_bad = {"rows": t359_bad_rows, "search": [dict(t359_search[0], reason="tiles-only")] + t359_search[1:],
            "allOff": [dict(t359_off[0], source="on this phone"), t359_off[1]]}
whats = sorted(f["what"] for f in part.t359_assertions({"t359": t359_bad}))
assert whats == sorted(["T359:network-test", "T359:on-phone-not-served", "T359:on-phone-not-served", "T359:network-not-named", "T359:network-not-named",
                        "T359:already-not-on-phone", "T359:map-message", "T359:search-inside", "T359:all-off-chip"]), whats
# end T359

# The part's place in the sweep: listed after a11y, a debug-build device part with its own ceiling.
with open(os.path.join(here, "sweep.ps1"), encoding="utf-8") as fh:
    sweep = fh.read()
assert "'a11y', 'two-maps'" in sweep, "two-maps runs right after a11y in $runOrder"
assert "$ceilings['two-maps']" in sweep
assert "tools/two-maps-part.py" in sweep

print(json.dumps({"ok": True, "test": "test_two_maps"}))
