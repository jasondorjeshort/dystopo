"""T147: sweep-summary.py offline: load_last_json with log text around a report, and the both-units part's four
screenshots (both phones after each carry).

    python -I tools/test_sweep_summary.py
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
finally:
    shutil.rmtree(d, ignore_errors=True)

print("test_sweep_summary: ok")
