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
    "areasRows": {"rows": [{"id": "pack:rainier-wide", "state": "Saved", "detail": "x"}], "count": 1},
    "maps": {"packs": ["rainier-wide", "rainier-fine"], "inUse": ["rainier-fine"], "active": "rainier-fine", "startupPacks": 2},
    "horizon": {"horizon": {"source": "pack", "zoom": 12, "highestDeg": 3.2, "bearingsWithoutData": 0}, "horizonReason": None, "placeLine": {"text": "Here"}},
    "horizonFirst": None,
    "panorama": {"showing": "panorama", "work": {"reason": None, "terrain": {"demPacks": ["rainier-fine"], "tiles": 9, "network": False}, "sweeps": [{}, {}]}, "names": [{}], "stepDrawn": 0.5},
}
sm = part.summarize(reads)
assert sm["map"]["mapMessage"] == "none" and sm["chip"]["offerDownload"] is True and sm["chip"]["text"] == "On this phone"
assert sm["rows"] == [{"id": "pack:rainier-wide", "state": "Saved", "detail": "x"}]
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
assert part.assertions(full) == [], "no task's assertion yet"

# The part's place in the sweep: listed after a11y, a debug-build device part with its own ceiling.
with open(os.path.join(here, "sweep.ps1"), encoding="utf-8") as fh:
    sweep = fh.read()
assert "'a11y', 'two-maps'" in sweep, "two-maps runs right after a11y in $runOrder"
assert "$ceilings['two-maps']" in sweep
assert "tools/two-maps-part.py" in sweep

print(json.dumps({"ok": True, "test": "test_two_maps"}))
