"""T147: sweep-summary.py offline: load_last_json with log text around a report, and the both-units part's four
screenshots (both phones after each carry); T154: the diagnostics part; T268: the startup part; T270: the a11y part;
T289: `changes` keeps the diff's three --first lists on a rerun, the route-sheet part and `routeSheet`'s counts;
T292: the retry's classification on fixture logs of each device class, each outcome code and failures never retried,
`--retry-check`, `retried`, `reruns` (with -RerunOf), the sheet's mark, other changes.json lists kept, and sweep.ps1's
-ListParts order against its Part calls.

    python -I tools/test_sweep_summary.py
    python -I -m unittest discover -s tools -p test_sweep_summary.py
"""
import importlib.util
import json
import os
import shutil
import tempfile

here = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("sweep_summary", os.path.join(here, "sweep-summary.py"))
summary = importlib.util.module_from_spec(spec)
spec.loader.exec_module(summary)

d = tempfile.mkdtemp(prefix="t147-summary-")
try:
    def write(name, text):
        p = os.path.join(d, name)
        with open(p, "w", encoding="utf-8") as f:
            f.write(text)
        return p

    report = {"ok": True, "steps": [{"step": "peer", "ok": True, "a": {"code": 200}}, {"step": "last", "ok": False, "error": "x"}],
              "screenshots": []}
    body = json.dumps(report, indent=2)

    # The report alone, and with a BOM (PowerShell's redirection writes one).
    assert summary.load_last_json(write("plain.json", body)) == report
    assert summary.load_last_json(write("bom.json", "﻿" + body)) == report
    # Log text before the report: the report, not its last inner object (the step), which the old reverse scan returned.
    log = "[ui] peer: ok\n[ui] last: FAILED x\nwarning: {not json} and [brackets] and {\"k\": 1}\n"
    assert summary.load_last_json(write("before.json", log + body)) == report
    # Log text after it too, and an earlier JSON value: the last top-level value wins.
    assert summary.load_last_json(write("around.json", '{"early": true}\n' + log + body + "\n[ui] done\n")) == report
    # A list report, and a file with no JSON at all.
    assert summary.load_last_json(write("list.json", "log line\n[1, {\"a\": [2]}]\n")) == [1, {"a": [2]}]
    assert summary.load_last_json(write("none.json", "only {log} text [here]\n")) is None
    assert summary.load_last_json(os.path.join(d, "missing.json")) is None

    # The both-units part: each carry's `pngs` holds both phones; the summary lists all four, in the order taken.
    shots = os.path.join(d, "ui", "bothunits")
    os.makedirs(shots)
    names = ["01-a-on-to-b-b.png", "02-a-on-to-b-a.png", "03-b-off-to-a-a.png", "04-b-off-to-a-b.png"]
    for n in names:
        open(os.path.join(shots, n), "wb").close()
    p = {n: os.path.join(shots, n) for n in names}
    both = {"part": "bothunits", "ok": True, "peer": "http://10.0.2.2:8783", "steps": [
        {"step": "peer", "ok": True}, {"step": "baseline", "ok": True},
        {"step": "a-on-to-b", "ok": True, "value": "on", "dstAfter": {"bothUnits": True}, "png": p[names[0]],
         "pngs": {"b": p[names[0]], "a": p[names[1]]}},
        {"step": "b-off-to-a", "ok": True, "value": "off", "dstAfter": {"bothUnits": False}, "png": p[names[2]],
         "pngs": {"a": p[names[2]], "b": p[names[3]]}}],
        "screenshots": [p[names[0]], p[names[2]]]}
    write("bothunits.json", "[ui] peer: ok\n" + json.dumps(both, indent=1))  # with a log line before it
    got = summary.bothunits(d, "bothunits")
    assert got["screenshots"] == ["ui/bothunits/" + n for n in names], got
    assert got["carries"]["a-on-to-b"]["dstBothUnits"] is True and got["carries"]["b-off-to-a"]["dstBothUnits"] is False, got
    assert got["peer"] == "http://10.0.2.2:8783" and "error" not in got, got

    # T154: the diagnostics part (android-ui.py diagnostics on the release build): the row's numbers, how the
    # force-stopped session ended, its screenshots on the sheet, and the first failed step as the error.
    dshots = os.path.join(d, "ui", "diagnostics")
    os.makedirs(dshots)
    open(os.path.join(dshots, "02-diagnostics.png"), "wb").close()
    diag = {"part": "diagnostics", "ok": True, "sdk": 35, "steps": [
        {"step": "install", "ok": True}, {"step": "row", "ok": True, "heapsize": "576m", "now": {"Max heap": "576 MB"}},
        {"step": "copy", "ok": True}, {"step": "refresh", "ok": True},
        {"step": "after-force-stop", "ok": True, "last": {"Ended": "force-stopped", "Peak heap": "80 MB"}}, {"step": "alive", "ok": True}]}
    write("diagnostics.json", "[ui] install: ok\n" + json.dumps(diag))
    got = summary.PARTS["diagnostics"](d, "diagnostics")
    assert got["heapsize"] == "576m" and got["lastAfterForceStop"]["Ended"] == "force-stopped" and got["sdk"] == 35, got
    assert got["steps"]["after-force-stop"] is True and "error" not in got, got
    assert ("Diagnostics row (by taps)", "02-diagnostics", os.path.join("ui", "diagnostics", "02-diagnostics.png")) in summary.sheet_images(d)
    diag["steps"][4] = {"step": "after-force-stop", "ok": False, "error": "after the force-stop: ended"}
    write("diagnostics.json", json.dumps(diag))
    assert summary.PARTS["diagnostics"](d, "diagnostics")["error"] == "after-force-stop: after the force-stop: ended"

    # T154: the device tour's runs name the views whose tiles check counted a tile served before the baseline.
    os.makedirs(os.path.join(d, "device"))
    tour = {"viewsOk": 2, "viewsTotal": 2, "views": [
        {"label": "region-z8", "ok": True, "seconds": 12.0, "tiles": {"dem": {"served": 1}, "osm": {"served": 0, "servedBeforeBaseline": 1}}},
        {"label": "region-z10", "ok": True, "tiles": {"dem": {"served": 4}, "osm": {"served": 3}}}]}
    write(os.path.join("device", "tour1.json"), json.dumps(tour))
    runs = summary.device_tour(d, "device-tour")["runs"]
    assert runs[0]["servedBeforeBaseline"] == {"region-z8": {"osm": 1}} and "failedViews" not in runs[0], runs

    # T268: the startup part's table (android-startup.py --budget's report), the reasons when red, the seeded store.
    assert summary.PARTS["startup"](d, "startup") == {}, "no report, no entry"
    rep = {"mode": "cold", "launches": [{}, {}, {}, {}, {}],
           "medians": {"amTotalMs": 3505, "firstFrameMs": 3369, "fullyLoadedMs": 4642, "longestStallMs": 0, "launchOverheadMs": 6.7},
           "seed": {"tracks": 52, "tracksImported": 0, "seedPoints": 26000, "packs": ["dem.pmtiles", "osm.pmtiles"], "seconds": 0.4},
           "budget": {"set": {"date": "2026-11-13", "from": ["a", "b"]}, "factor": 1.25, "red": True,
                      "reasons": ["stage composition: median 1049.5 ms over its ceiling of 500 ms"], "undeclared": [],
                      "rows": [{"name": "firstFrameMs", "kind": "frame", "medianMs": 3369, "ceilingMs": 4212, "status": "ok"},
                               {"name": "composition", "kind": "stage", "medianMs": 1049.5, "ceilingMs": 500, "status": "over"}]}}
    write("startup.json", json.dumps(rep, indent=1))
    got = summary.PARTS["startup"](d, "startup")
    assert got["table"][1] == {"name": "composition", "kind": "stage", "medianMs": 1049.5, "ceilingMs": 500, "status": "over"}, got
    assert got["red"] is True and got["reasons"] == rep["budget"]["reasons"] and got["launches"] == 5, got
    assert got["seed"] == {"tracks": 52, "seedPoints": 26000, "packs": ["dem.pmtiles", "osm.pmtiles"], "seconds": 0.4}, got
    assert got["medians"]["firstFrameMs"] == 3369 and got["budgetSet"]["date"] == "2026-11-13" and "error" not in got, got
    del rep["budget"]
    write("startup.json", json.dumps(rep))
    assert summary.PARTS["startup"](d, "startup")["error"] == "no budget in the report"

    # T270: the a11y part (android-a11y.py --baseline): counts per screen, a skipped screen's reason, the new findings, the sheet.
    assert summary.PARTS["a11y"](d, "a11y") == {}, "no report, no entry"
    new = {"screen": "route:2.0", "kind": "under48", "label": "Fit", "boundsDp": [100, 100, 130, 120]}
    rep = {"part": "a11y", "ok": False, "fontScales": [1.0, 2.0], "screensChecked": 1, "seconds": 260.4,
           "totals": {"under48": 1, "contrast": 2},
           "screens": [{"screen": "route:2.0", "counts": {"under48": 1, "contrast": 2}, "findings": [{}]},
                       {"screen": "share:2.0", "skipped": "RuntimeError: POST /share/start: 500"}],
           "skipped": ["share:2.0"], "baseline": {"file": "tools/a11y-baseline.json", "entries": 40, "matched": 2, "new": [new], "gone": []}}
    write("a11y.json", "[a11y] map:1.0: ...\n" + json.dumps(rep))
    got = summary.PARTS["a11y"](d, "a11y")
    assert got["screens"]["route:2.0"] == {"under48": 1, "contrast": 2} and got["screens"]["share:2.0"] == {"skipped": "RuntimeError: POST /share/start: 500"}, got
    assert got["baseline"] == {"entries": 40, "matched": 2, "new": 1, "gone": 0} and got["new"] == [new] and got["skipped"] == ["share:2.0"], got
    write("a11y.json", json.dumps({"part": "a11y", "ok": False, "error": "RuntimeError: no debug server"}))
    assert summary.PARTS["a11y"](d, "a11y") == {"error": "RuntimeError: no debug server"}
    os.makedirs(os.path.join(d, "a11y"))
    open(os.path.join(d, "a11y", "a11y-sheet.png"), "wb").close()
    assert ("Accessibility, offending nodes outlined", "a11y sheet", os.path.join("a11y", "a11y-sheet.png")) in summary.sheet_images(d)

    # T289 (T288's report): `changes` keeps the diff's three --first lists when the summary is written again.
    write("changes.json", json.dumps({"count": 1, "paired": 3, "changed": [{"path": "ui/features/drive-fuel.png", "first": ["drive"]}],
                                      "added": [], "removed": [], "prev": "x/sweep-1", "firstMissing": ["nosuchscreen"],
                                      "firstUnchanged": ["settings"], "firstAdded": ["ui/features/drive-row.png"]}))
    ch = summary.changes(d)
    assert ch["firstMissing"] == ["nosuchscreen"] and ch["firstUnchanged"] == ["settings"], ch
    assert ch["firstAdded"] == ["ui/features/drive-row.png"] and ch["first"] == ["ui/features/drive-fuel.png"], ch
    write("changes.json", json.dumps({"count": 0, "changed": []}))
    assert summary.changes(d)["firstMissing"] == [] and summary.changes(d)["firstAdded"] == []
    # ... through main(): a rerun over a folder whose changes.json carries the lists writes them into summary.json.
    write("changes.json", json.dumps({"count": 0, "changed": [], "firstMissing": ["nosuchscreen"], "firstUnchanged": [], "firstAdded": []}))
    write("steps.tsv", "sync-sim\t0\t1.0\t\nroute-sheet\t0\t104.0\t\n")

    # T289: the route-sheet part's entry and summary.json's routeSheet counts.
    write("route-sheet.json", "[route-sheet] a: 1 m\n" + json.dumps(
        {"routes": 4, "planned": 3, "pictures": 2, "skipped": [{"name": "grand-canyon-hike", "why": "no out/grand-canyon-outdoors.pmtiles"}],
         "noPicture": [{"name": "b", "why": "no DEM pack"}], "failed": [], "seconds": 12.5}))
    got = summary.PARTS["route-sheet"](d, "route-sheet")
    assert got == {"routes": 4, "planned": 3, "pictures": 2, "skipped": ["grand-canyon-hike"], "noPicture": ["b"],
                   "failed": [], "routeSeconds": 12.5}, got
    assert summary.route_changes(d) is None, "no route-changes.json, no entry"
    write("route-changes.json", json.dumps({"prev": "C:/x/sweep-1", "count": 1, "changed": [{"name": "a", "why": ["timeS", "line"]}],
                                            "new": ["c"], "removed": [], "unchanged": ["b"], "skipped": ["grand-canyon-hike"],
                                            "failed": [], "pages": [{"png": os.path.join(d, "route-changes.png")}]}))
    rc = summary.route_changes(d)
    assert (rc["changed"], rc["new"], rc["skipped"], rc["removed"], rc["unchanged"], rc["failed"]) == (1, 1, 1, 0, 1, 0), rc
    assert rc["routes"] == {"a": ["timeS", "line"]} and rc["prev"] == "sweep-1" and rc["png"][0].endswith("route-changes.png"), rc
    import subprocess, sys
    subprocess.run([sys.executable, "-I", os.path.join(here, "sweep-summary.py"), d, "--no-sheet"], check=False, capture_output=True)
    with open(os.path.join(d, "summary.json"), encoding="utf-8") as f:
        s = json.load(f)
    assert s["changes"]["firstMissing"] == ["nosuchscreen"], s.get("changes")
    assert s["routeSheet"]["changed"] == 1 and s["parts"]["route-sheet"]["planned"] == 3, s
    write("route-changes.json", json.dumps({"error": "boom"}))
    assert summary.route_changes(d)["error"] == "boom"

    # T292 (T295's lists, T289's rerun bug): any other list in changes.json is kept on a rerun of the summary.
    write("changes.json", json.dumps({"count": 0, "changed": [], "leftOut": ["ui/features/a.png"], "unknown": ["device/b.png"],
                                      "renumbered": [{"x": 1}]}))
    ch = summary.changes(d)
    assert ch["leftOut"] == ["ui/features/a.png"] and ch["unknown"] == ["device/b.png"] and "renumbered" not in ch, ch

    # T292: the retry. Fixture logs of each device class, each outcome code, and failures that are never retried.
    import time
    r = os.path.join(d, "retry-fixtures")
    os.makedirs(os.path.join(r, "ui", "features"))
    os.makedirs(os.path.join(r, "device"))

    def put(rel, text, mtime=None):
        p = os.path.join(r, *rel.split("/"))
        with open(p, "w", encoding="utf-8") as f:
            f.write(text)
        if mtime is not None:
            os.utime(p, (mtime, mtime))

    device_class = {
        "device-offline": ("release.err.log", "Open-View 1\nadb.exe: error: device offline\n"),
        "device-not-found": ("routes.err.log", "error: device 'emulator-5560' not found\n"),
        "adb-permission-denied": ("features.json", "[ui] install-map: FAILED\n" + json.dumps(
            {"ok": False, "steps": [{"step": "install-map", "ok": False, "error": "mkdir /sdcard/Android: Permission denied"}]})),
        "storage-not-ready": ("device/emu.err.log", "the guest's storage was not ready 180 s after sys.boot_completed (pm path android: '')\n"),
    }
    for reason, (rel, text) in device_class.items():
        got = summary.retry_reason("1", "emulator private 4.4 GB at the start", [(rel, text)])
        assert got and got["reason"] == reason and got["file"] == rel, (reason, got)
    # The outcome codes of sweep.ps1's Part: retried on the code alone, the line is the note's first clause.
    for code in ("emulator-gone", "guard-stop"):
        # The order sweep.ps1 writes (T292's sweep): the memory clause and retry counts first, then how the emulator went.
        got = summary.retry_reason(code, "emulator private 4.7 GB at the start, peak 5.1 GB, none at the end; captureRetries 52; "
                                         "emulator-5560 stopped while the part ran, not by its guard; the part's own result: 1", [])
        assert got == {"reason": code, "line": "emulator-5560 stopped while the part ran, not by its guard", "file": "steps.tsv"}, got
    # A device-class line in the note (a body that threw) is enough too.
    assert summary.retry_reason("error", "adb -s emulator-5560 shell getprop failed: error: device offline", [])["reason"] == "device-offline"
    # Never retried: an assertion, a golden, a test, a budget; a timeout, a skip or a refused boot even with a device line;
    # the success line of the storage wait, and a "Permission denied" that is not the device's.
    never = [("1", "", [("release.json", json.dumps({"ok": False, "probeOk": False, "error": "probe /dem: 200, expected 402"}))]),
             ("1", "", [("check.log", "StyleParityTest > golden rainier-z13 FAILED\nAssertionError: expected 0 differing pixels\n")]),
             ("1", "", [("startup.json", json.dumps({"budget": {"red": True, "reasons": ["stage composition: over its ceiling"]}}))]),
             ("1", "rebooted emulator-5560 before release; guest storage ready 4.3 s after sys.boot_completed", []),
             ("1", "", [("x.log", "PermissionError: [Errno 13] Permission denied: 'C:/tmp/x'\n")]),
             ("timeout", "timed out at its ceiling of 900 s; error: device offline", []),
             ("skip", "error: device offline", []), ("cap-refused", "", [("e.log", "device offline")]), ("0", "", [])]
    for code, note, texts in never:
        assert summary.retry_reason(code, note, texts) is None, (code, note, texts)
    # A recovered device error early in a long log is not the failure: only the last LOG_TAIL lines are read.
    t0 = time.time()
    put("tour.log", "error: device offline\n" + "".join(f"[tour] view {i}: ok\n" for i in range(summary.LOG_TAIL + 5)) + "AssertionError: 3 views failed\n", t0 - 3600)
    assert summary.retry_reason("1", "", summary.part_texts(r, ["tour.log"])) is None
    put("tour.log", "error: device offline\nAssertionError: 3 views failed\n", t0 - 3600)
    assert summary.retry_reason("1", "", summary.part_texts(r, ["tour.log"]))["reason"] == "device-offline"

    # retry_check over a folder: only files written since the run started count, the first run's text files are copied to
    # retry/<part>/ (no PNG), and retries.json records it; an assertion is not retried and leaves no record.
    put("release.err.log", "adb.exe: error: device offline\n", t0 - 3600)  # an older sweep's line: not this run's
    put("features.json", "[ui] install-map: FAILED\n" + json.dumps({"ok": False, "steps": [{"step": "x", "ok": False, "error": "wrong card: 12 km, expected 13 km"}]}))
    assert summary.retry_check(r, "features", "1", t0 - 1, 40.0, "") == {"retry": False}
    assert not os.path.exists(os.path.join(r, "retries.json"))
    put("features.err.log", "Traceback ...\nsubprocess error: adb: error: failed to copy: remote couldn't create file: Permission denied\n")
    with open(os.path.join(r, "ui", "features", "01-map.png"), "wb") as f:
        f.write(b"png")
    got = summary.retry_check(r, "features", "1", t0 - 1, 40.0, "emulator private 4.4 GB")
    assert got["retry"] and got["reason"] == "adb-permission-denied" and got["file"] == "features.err.log", got
    assert os.path.exists(os.path.join(r, "retry", "features", "features.err.log"))
    assert os.path.exists(os.path.join(r, "retry", "features", "features.json"))
    assert not os.path.exists(os.path.join(r, "retry", "features", "ui", "features", "01-map.png")), "no PNG copied"
    assert not os.path.exists(os.path.join(r, "retry", "features", "release.err.log")), "an older file is not this run's"
    # The copies under retry/ are never read again as the part's own files.
    assert summary.part_files(r, t0 - 1) == ["features.err.log", "features.json"], summary.part_files(r, t0 - 1)
    # The CLI: one JSON line, retries.json appended (a second retry of another part).
    import subprocess, sys
    put("device/note.txt", "emulator-5560 stopped while the part ran, not by its guard (no stop line); the part's own result: 1")
    out = subprocess.run([sys.executable, "-I", os.path.join(here, "sweep-summary.py"), r, "--retry-check", "release", "--code",
                          "emulator-gone", "--since", str(t0 - 1), "--seconds", "61.5", "--note-file", os.path.join(r, "device", "note.txt")],
                         capture_output=True, text=True, check=True).stdout
    assert json.loads(out)["reason"] == "emulator-gone", out
    with open(os.path.join(r, "retries.json"), encoding="utf-8") as f:
        rec = json.load(f)
    assert [(x["part"], x["reason"], x["code"]) for x in rec] == [("features", "adb-permission-denied", "1"), ("release", "emulator-gone", "emulator-gone")], rec

    # The summary of that folder: each retried part's two lines (the last decides), `retried` and `reruns`, the sheet's mark.
    put("steps.tsv", "release\temulator-gone\t61.5\tretried once (emulator-gone: ...)\nrelease\t0\t120.0\tthe retry\n"
                     "features\t1\t40.0\tretried once (adb-permission-denied: ...)\nfeatures\t1\t300.0\tthe retry\n"
                     "recenter\t0\t50.0\t\nmatrix\tskip\t0\tneeds -Matrix\n")
    subprocess.run([sys.executable, "-I", os.path.join(here, "sweep-summary.py"), r, "--no-sheet"], check=False, capture_output=True)
    with open(os.path.join(r, "summary.json"), encoding="utf-8") as f:
        s = json.load(f)
    rel_, feat = s["parts"]["release"], s["parts"]["features"]
    assert rel_["ok"] and rel_["passedOnRetry"] and rel_["runs"] == 2 and rel_["earlier"][0]["exit"] == "emulator-gone", rel_
    assert not feat["ok"] and "passedOnRetry" not in feat and feat["runs"] == 2, feat
    assert s["failed"] == ["features"] and not s["ok"] and s["totalSeconds"] == 571.5, s
    assert [(x["part"], x["reason"], x["passedOnRetry"], x["firstRun"]["exit"]) for x in s["retried"]] == [
        ("features", "adb-permission-denied", False, "1"), ("release", "emulator-gone", True, "emulator-gone")], s["retried"]
    assert s["reruns"] == {"release": 1, "features": 1}, s["reruns"]
    with open(os.path.join(r, "sheet.html"), encoding="utf-8") as f:
        page = f.read()
    assert "ok on retry: emulator-gone" in page and "passed on retry: release" in page and "FAIL (2 runs)" in page, page[:600]
    # A sweep with no retry has an empty `retried` and `reruns`.
    clean = os.path.join(d, "retry-clean")
    os.makedirs(clean)
    with open(os.path.join(clean, "steps.tsv"), "w", encoding="utf-8") as f:
        f.write("probe\t0\t4.0\t\n")
    subprocess.run([sys.executable, "-I", os.path.join(here, "sweep-summary.py"), clean, "--no-sheet"], check=False, capture_output=True)
    with open(os.path.join(clean, "summary.json"), encoding="utf-8") as f:
        s = json.load(f)
    assert s["retried"] == [] and s["reruns"] == {} and "rerunOf" not in s, s

    # -RerunOf: a -Only rerun into a second folder counts against the first, and follows the first's own rerunOf.
    second = os.path.join(d, "sweep-rerun")
    os.makedirs(second)
    with open(os.path.join(second, "steps.tsv"), "w", encoding="utf-8") as f:
        f.write("features\t0\t290.0\t\nrecenter\t0\t40.0\t\nprobe\t0\t5.0\t\n")
    with open(os.path.join(second, "meta.json"), "w", encoding="utf-8") as f:
        json.dump({"rerunOf": r}, f)
    third = os.path.join(d, "sweep-rerun2")
    os.makedirs(third)
    with open(os.path.join(third, "steps.tsv"), "w", encoding="utf-8") as f:
        f.write("features\t0\t280.0\t\n")
    with open(os.path.join(third, "meta.json"), "w", encoding="utf-8") as f:
        json.dump({"rerunOf": second}, f)
    # features: retried in the first (1), rerun in the second (+1); recenter ran in the first; probe did not (not a rerun).
    assert summary.reruns(second) == {"features": 2, "recenter": 1}, summary.reruns(second)
    assert summary.reruns(third) == {"features": 3}, summary.reruns(third)
    subprocess.run([sys.executable, "-I", os.path.join(here, "sweep-summary.py"), third, "--no-sheet"], check=False, capture_output=True)
    with open(os.path.join(third, "summary.json"), encoding="utf-8") as f:
        s = json.load(f)
    assert s["rerunOf"] == "sweep-rerun" and s["reruns"] == {"features": 3}, s

    # sweep.ps1 -ListParts: its $runOrder is the order of the Part calls in the script (the order they run in).
    import re
    with open(os.path.join(here, "sweep.ps1"), encoding="utf-8") as f:
        ps = f.read()
    m = re.search(r"\$runOrder = @\(([^)]*)\)", ps)
    order = re.findall(r"'([\w-]+)'", m.group(1))
    calls = re.findall(r"^\s*\$\w+ = (?:Part|Phone-Step) '([\w-]+)'", ps, re.M)  # T303: the phone parts go through Phone-Step
    assert order == calls, (order, calls)
    assert order.index("startup") > order.index("bothunits") and order.index("a11y") > order.index("startup") and order[-1] == "apk-strings", order
    # T303: the phone parts run after every emulator part, in sweep-summary.py's order.
    assert [p for p in order if p.startswith("phone-")] == list(summary.PHONE_PARTS) and order.index("phone-attach") > order.index("matrix"), order

    # T303: the phone section, from the phone parts' outputs under phone/ (and none without them).
    ph = os.path.join(d, "sweep-phone")
    os.makedirs(os.path.join(ph, "phone"))
    assert summary.phone(ph) == {}
    def pw(name, obj, prefix=""):
        with open(os.path.join(ph, "phone", name), "w", encoding="utf-8") as f:
            f.write(prefix + json.dumps(obj))
    rel = {"installed": True, "versionCode": "550", "versionName": "0.1.32-alpha", "firstInstallTime": "a", "lastUpdateTime": "b", "files": 12, "listingSha256": "ab"}
    pw("attach.json", {"model": "Pixel X", "android": "15", "sdk": "35", "emulator": False, "release": rel})
    pw("tour.json", {"viewsOk": 9, "viewsTotal": 10, "fixBy": "POST /location", "views": [{"label": "a", "ok": True, "seconds": 12.5}, {"label": "b", "ok": False}],
                     "memory": {"totalPssKb": 409600}, "contourGeneration": {"count": 5, "avgMs": 80, "maxMs": 200}})
    pw("startup.json", {"launches": [{}] * 5, "medians": {"firstFrameMs": 900, "fullyLoadedMs": 2100, "amTotalMs": None},
                        "stageMedians": [{"name": "packs", "medianMs": 40}, {"name": "mapview", "medianMs": 120}, {"name": "x", "medianMs": None}]}, "[startup] log line\n")
    pw("diagnostics.json", {"path": "/diagnostics", "body": {"heapMaxBytes": 512 << 20, "heapUsedBytes": 64 << 20, "pssKb": 300 * 1024,
                                                            "lowMemory": False, "system": {"buildType": "debug"}}})
    pw("release.json", {"before": rel, "after": rel, "changed": [], "listingSame": True})
    p = summary.phone(ph)
    assert p["device"] == {"model": "Pixel X", "android": "15", "sdk": "35", "emulator": False}, p
    assert p["tour"]["viewsOk"] == 9 and p["tour"]["failedViews"] == ["b"] and p["tour"]["pssMb"] == 400 and p["tour"]["fixBy"] == "POST /location", p
    assert p["startup"]["launches"] == 5 and p["startup"]["medians"] == {"firstFrameMs": 900, "fullyLoadedMs": 2100}, p
    assert [x["stage"] for x in p["startup"]["slowestStages"]] == ["mapview", "packs"], p
    assert p["diagnostics"]["heapMaxMb"] == 512 and p["diagnostics"]["pssMb"] == 300 and p["diagnostics"]["buildType"] == "debug", p
    assert p["release"]["unchanged"] is True and p["release"]["listingSame"] is True, p
    assert "serial" not in json.dumps(p)
    t = summary.phone_table(p)
    assert "Pixel X" in t and "9/10 views ok" in t and "unchanged" in t and "the same" in t, t
    pw("release.json", {"before": rel, "after": {**rel, "lastUpdateTime": "c"}, "changed": ["lastUpdateTime"], "listingSame": False})
    assert summary.phone(ph)["release"]["unchanged"] is False and "CHANGED: lastUpdateTime" in summary.phone_table(summary.phone(ph))
    # T304: a failed re-read is not a change: no "CHANGED", no "unchanged", the reason shown.
    pw("release.json", {"before": rel, "after": None, "unreadable": "adb shell pm list timed out after 30 s", "changed": [], "listingSame": None})
    u = summary.phone(ph)["release"]
    assert u["unchanged"] is None and "timed out" in u["unreadable"], u
    t = summary.phone_table(summary.phone(ph))
    assert "could not re-read: adb shell pm list timed out" in t and "CHANGED" not in t and "unchanged" not in t, t
    assert summary.phone_table({}) == ""
finally:
    shutil.rmtree(d, ignore_errors=True)

print("test_sweep_summary: ok")
