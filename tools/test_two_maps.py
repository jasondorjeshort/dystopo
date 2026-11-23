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

# The part's place in the sweep: listed after a11y, a debug-build device part with its own ceiling.
with open(os.path.join(here, "sweep.ps1"), encoding="utf-8") as fh:
    sweep = fh.read()
assert "'a11y', 'two-maps'" in sweep, "two-maps runs right after a11y in $runOrder"
assert "$ceilings['two-maps']" in sweep
assert "tools/two-maps-part.py" in sweep

print(json.dumps({"ok": True, "test": "test_two_maps"}))
