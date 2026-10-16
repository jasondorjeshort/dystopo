"""Folds one sweep's outputs into summary.json and a contact sheet of contact sheets (T71).

    python -I tools/sweep-summary.py out/sweep-<date> [--channel chrome|""] [--no-sheet]

Called by tools/sweep.ps1 and tools/sweep.sh at the end of a sweep; it can be rerun on a sweep folder by
hand. The folder holds steps.tsv, one line per part the sweep ran or skipped:

    <part>\t<exit code or "skip">\t<seconds>\t<note>

and each part's own outputs under fixed names (the table PARTS below). The summary keeps per part `ok`,
`seconds`, the exit code and the few numbers the dated sweep log entries quote (tour passed/failed, demo
views ok, live DEM sources, sync convergence, device views (T105: with the app's server and the wait for a quiet
device), probe count, release timings, the first-run steps (T105: and the --direct run's timings and screenshots), and
T81's release realism parts: routing and search tours, APK string scan, features, recenter, upgrade, permissions,
background, network, update, the device matrix with any known defects); `ok` overall is
true when every part that ran exited 0. Skipped parts are listed, not counted as failures.

Privacy: only public names reach the summary. Tour failures are named only when the area is in areas.json;
the device tour's pack list and logcat lines are left out; the live views carry the labels from the sweep's
own view list. Nothing here reads devtool.local.json.

The sheet (sheet.png, plus sheet.html beside it) puts every PNG the sweep produced on one page: the world
tour contact sheet, the device tour sheets, the demo, serve and live screenshots, the release smoke views.
It is rendered with Playwright (already needed by demo-check.py); without it the HTML is still written.
T111: Chrome captures at most 16,384 px of a page (a taller full-page capture repeats the top and cuts the end:
the 2026-10-14 sheet was 17,383 px and lost the last demo-tour section), so a page taller than SHEET_MAX_PX gets
more thumbnail columns (6, then 7, 8, 10, 12; the big world and device tour sheets 2, then 3) and, if that is not
enough, is rendered at a device scale factor below 1 (fewer pixels for the same layout); `sheetSize` in the summary has the PNG's width and height (read from the file),
the page height before fitting, the columns and the scale, and `cut` true if the PNG still reached the limit.
Prints the summary JSON; exit 1 when any part failed, 0 otherwise.
"""
import argparse
import glob
import html
import json
import os
import re
import sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def load(path):
    try:
        with open(path, encoding="utf-8-sig") as f:
            return json.load(f)
    except (OSError, ValueError):
        return None


def load_last_json(path):
    """A file whose last JSON value is the report (stdout with log lines before it)."""
    try:
        with open(path, encoding="utf-8-sig", errors="replace") as f:
            text = f.read()
    except OSError:
        return None
    try:
        return json.loads(text)
    except ValueError:
        pass
    dec = json.JSONDecoder()
    for start in [i for i, c in enumerate(text) if c in "{["][::-1]:
        try:
            val, _ = dec.raw_decode(text[start:])
            return val
        except ValueError:
            continue
    return None


def public_areas():
    d = load(os.path.join(REPO, "areas.json")) or {}
    return set((d.get("areas") or {}).keys())


def tour(d, part):
    s = load(os.path.join(d, "tour", "summary.json"))
    if not s:
        return {}
    pub = public_areas()

    def public(names):
        # "<area>-z<zoom>"; anything not named in areas.json is counted but not named.
        return [n for n in names or [] if n.rsplit("-z", 1)[0] in pub]

    out = {k: s.get(k) for k in ("zooms", "renders", "passed", "totalMs") if k in s}
    out["failed"] = len(s.get("failed") or [])
    out["skipped"] = len(s.get("skipped") or [])
    if out["failed"]:
        out["failures"] = public(s.get("failed"))[:20]
    return out


def demo(d, part):
    r = load_last_json(os.path.join(d, part + ".json"))
    if not isinstance(r, list):
        return {}
    out = {"views": len(r), "viewsOk": sum(1 for v in r if v.get("ok"))}
    bad = [v.get("label") or v.get("url", "").split("#")[-1] for v in r if not v.get("ok")]
    if bad:
        out["failedViews"] = bad
    if any(v.get("live") for v in r):
        out["demSources"] = {v.get("label"): v.get("demSource") for v in r}
    return out


def sync_sim(d, part):
    r = load_last_json(os.path.join(d, "sync-sim.json"))
    if not isinstance(r, dict):
        return {}
    out = {k: r[k] for k in ("converged", "seeds", "failures") if k in r}
    if not out.get("failures"):
        out.pop("failures", None)
    return out


def mb(v, unit):
    return round(v / unit) if isinstance(v, (int, float)) else None


def device_tour(d, part):
    runs = sorted(p for p in glob.glob(os.path.join(d, "device", "tour*.json")) if ".stdout." not in p)
    res = []
    for p in runs:
        s = load(p)
        if not isinstance(s, dict):
            continue
        cg = s.get("contourGeneration") or {}
        mem = s.get("memory") or {}
        setup = s.get("setup") or {}
        views = s.get("views") or []
        res.append({"viewsOk": s.get("viewsOk"), "viewsTotal": s.get("viewsTotal"),
                    "glyphsOk": (s.get("glyphs") or {}).get("ok"),
                    "failedViews": [v.get("label") for v in views if not v.get("ok")],
                    # T105: the app's server during the tour, the wait for a quiet device, the first view's seconds
                    "server": (s.get("online") or setup.get("online") or {}).get("server") or "none",
                    "quietWaitS": (setup.get("quiet") or {}).get("waitedS"),
                    "firstViewS": views[0].get("seconds") if views else None,
                    "contourMs": {k: cg.get(k) for k in ("count", "avgMs", "maxMs")},
                    "pssMb": mb(mem.get("totalPssKb"), 1024), "javaHeapMb": mb(mem.get("javaHeapUsedBytes"), 1 << 20),
                    "nativeHeapMb": mb(mem.get("nativeHeapAllocatedBytes"), 1 << 20)})
    for r in res:
        if not r["failedViews"]:
            del r["failedViews"]
    return {"runs": res} if res else {}


def probe(d, part):
    r = load_last_json(os.path.join(d, "device", "probe.json"))
    if not isinstance(r, dict):
        return {}
    out = {"ok": r.get("ok")}
    probes = r.get("probes") or r.get("results")
    if isinstance(probes, list):
        out["probes"] = len(probes)
        out["probesOk"] = sum(1 for p in probes if isinstance(p, dict) and p.get("ok"))
        bad = [p.get("probe") or p.get("name") for p in probes if isinstance(p, dict) and not p.get("ok")]
        if bad:
            out["failed"] = bad
    for k in ("passed", "failed", "total", "serverAlive"):
        if k in r and k not in out:
            out[k] = r[k]
    return out


def release(d, part):
    r = load(os.path.join(d, "release", "release-summary.json"))
    if not isinstance(r, dict):
        return {}
    views = r.get("views") or []
    keys = ("amTotalMs", "firstContourMs", "firstOsmMs", "settledMs")
    out = {"ok": r.get("ok"), "probeOk": r.get("probeOk"),
           "views": [{k: v.get(k) for k in ("ok",) + keys} for v in views if isinstance(v, dict)],
           "crashLines": (r.get("logcat") or {}).get("crashLines"),
           "appErrorLines": (r.get("logcat") or {}).get("appErrorLines")}
    for k in ("apk", "apkBytes", "abi"):
        if k in r:
            out[k] = os.path.basename(r[k]) if k == "apk" and isinstance(r[k], str) else r[k]
    return out


def first_run_direct(d):
    """T105 (T91's follow-up): the --direct run (no server: the public sources, then the phone's own download and
    offline), per step ok, the timings per place and the screenshots."""
    r = load_last_json(os.path.join(d, "first-run-direct.json"))
    if not isinstance(r, dict):
        return None
    steps = [s for s in r.get("steps") or [] if isinstance(s, dict)]
    out = {"ok": r.get("ok"), "steps": {s.get("step"): s.get("ok") for s in steps}, "places": {}}
    for s in steps:
        t = {k: s.get(k) for k in ("firstContoursS", "fullContoursS", "settledS", "contourShare") if s.get(k) is not None}
        if s.get("skipped"):
            t["skipped"] = s["skipped"]
        if t:
            out["places"][s.get("step")] = t
        if s.get("step") == "download" and s.get("waitS") is not None:
            out["downloadWaitS"] = s["waitS"]
        if not s.get("ok"):
            out.setdefault("error", f"{s.get('step')}: {s.get('error')}")
    out["screenshots"] = sorted(os.path.relpath(p, d).replace(os.sep, "/") for p in glob.glob(os.path.join(d, "first-run", "direct", "*.png")))
    return out


def first_run(d, part):
    """T79: the release first run driven by taps (tools/android-firstrun.py): per step ok, and the screenshots;
    T105: the --direct run under `direct`."""
    r = load_last_json(os.path.join(d, "first-run.json"))
    direct = first_run_direct(d)
    if not isinstance(r, dict):
        return {"direct": direct} if direct else {}
    steps = [s for s in r.get("steps") or [] if isinstance(s, dict)]
    out = {"steps": {s.get("step"): s.get("ok") for s in steps},
           "screenshots": [os.path.relpath(s["png"], d).replace(os.sep, "/") for s in steps if s.get("png") and os.path.isabs(s["png"])]}
    if direct:
        out["direct"] = direct
    for s in steps:
        if s.get("step") == "test":
            out["catalogPacks"] = s.get("packs")  # the sweep's server starts with an empty out\: 0 until the download builds one
        if s.get("step") in ("map", "import-map"):
            out[s["step"] + "ContourShare"] = s.get("contourShare")
        if s.get("step") == "download":
            out["downloadWaitS"] = s.get("waitS")
        if not s.get("ok"):
            out.setdefault("error", f"{s.get('step')}: {s.get('error')}")
    return out


def ui_steps(r):
    """T81: an android-ui.py report: per step ok, the known defects (filed follow-ups) and the first error."""
    steps = [s for s in r.get("steps") or [] if isinstance(s, dict)]
    out = {"steps": {s.get("step"): s.get("ok") for s in steps}}
    if r.get("knownDefects"):
        out["knownDefects"] = r["knownDefects"]
    bad = next((s for s in steps if not s.get("ok")), None)
    if bad or r.get("error"):
        out["error"] = f"{bad.get('step')}: {bad.get('error')}" if bad else r.get("error")
    return out


def ui_part(d, part):
    r = load_last_json(os.path.join(d, part + ".json"))
    if not isinstance(r, dict):
        return {}
    out = ui_steps(r)
    steps = {s.get("step"): s for s in r.get("steps") or [] if isinstance(s, dict)}
    if part == "features":
        out["route"] = (steps.get("route") or {}).get("card")
        out["routeLine"] = {k: (steps.get("route") or {}).get(k) for k in ("routeLinePx", "tripLinePx")}  # T105
        out["sync"] = (steps.get("sync") or {}).get("result")
    elif part == "upgrade":
        out["from"] = (steps.get("install-new") or {}).get("from")
        out["to"] = (steps.get("install-new") or {}).get("to")
        out["survived"] = (steps.get("survived") or {}).get("checks")
    elif part == "background":
        st = steps.get("return-stop") or {}
        out.update({k: st.get(k) for k in ("fixesFed", "gained")})
    elif part == "network":
        out["downloadKilledAt"] = (steps.get("download-killed") or {}).get("killedAt")
        out["downloadMessage"] = (steps.get("download-killed") or {}).get("message")
        out["syncMessage"] = (steps.get("sync-killed") or {}).get("message")
    elif part == "permissions":
        out["api"] = r.get("api")
    elif part == "recenter":  # T105
        out["movedPx"] = (steps.get("pan") or {}).get("movedPx")
        out["offsetPx"] = (steps.get("tap") or {}).get("offsetPx")
    elif part == "update":  # T99
        out["versions"] = r.get("versions")
        out["previousCheckAsked"] = (steps.get("create-data") or {}).get("previousCheckAsked")
        out["startupCheckAsked"] = (steps.get("lower-quiet") or {}).get("dailyCheckAsked")
        out["survived"] = (steps.get("survived") or {}).get("checks")
    return out


def tour_part(d, part):
    """T81: routes and search tours (debug build)."""
    r = load_last_json(os.path.join(d, part + ".json"))
    if not isinstance(r, dict):
        return {}
    if part == "routes":
        out = {"ok": r.get("routesOk"), "of": r.get("routesTotal")}
        bad = [x.get("label") for x in r.get("routes") or [] if not x.get("ok")]
    else:
        out = {"ok": r.get("queriesOk"), "of": r.get("queriesTotal")}
        bad = [x.get("label") for x in r.get("queries") or [] if not x.get("ok")]
    if bad:
        out["failed"] = bad
    if r.get("error"):
        out["error"] = r["error"]
    return {"passed": out}


def apk_strings(d, part):
    r = load_last_json(os.path.join(d, "apk-strings.json"))
    if not isinstance(r, dict):
        return {}
    scan = r.get("scan") or {}
    return {"apk": r.get("apk"), "strings": r.get("strings"), "privateFile": r.get("privateFile"),
            **{k: scan.get(k) for k in ("termHits", "coordinatePairHits", "coordinateValueHits")}, **({"error": r["error"]} if r.get("error") else {})}


def matrix(d, part):
    out = {}
    for p in sorted(glob.glob(os.path.join(d, "ui", "matrix", "*.json"))):
        r = load_last_json(p)
        if isinstance(r, dict):
            label = os.path.splitext(os.path.basename(p))[0]
            out[label] = {"ok": r.get("ok"), "config": r.get("config"), **ui_steps(r)}
    return {"configs": out} if out else {}


PARTS = {
    "routes": tour_part,
    "search": tour_part,
    "apk-strings": apk_strings,
    "features": ui_part,
    "recenter": ui_part,
    "upgrade": ui_part,
    "permissions": ui_part,
    "background": ui_part,
    "network": ui_part,
    "update": ui_part,
    "matrix": matrix,
    "tour": tour,
    "demo-static": demo,
    "demo-serve": demo,
    "live": demo,
    "sync-sim": sync_sim,
    "device-tour": device_tour,
    "probe": probe,
    "release": release,
    "first-run": first_run,
}


def read_steps(d):
    steps = []
    try:
        with open(os.path.join(d, "steps.tsv"), encoding="utf-8-sig") as f:
            for line in f:
                cols = line.rstrip("\r\n").split("\t")
                if len(cols) >= 3 and cols[0]:
                    steps.append(cols + [""] * (4 - len(cols)))
    except OSError:
        pass
    return steps


def shot_labels(d, part):
    """screenshot file name -> view label, from a demo-check.py report."""
    r = load_last_json(os.path.join(d, part + ".json"))
    if not isinstance(r, list):
        return {}
    return {os.path.basename(v.get("screenshot", "")): (v.get("label") or str(v.get("index", ""))) + ("" if v.get("ok") else " FAIL")
            for v in r if isinstance(v, dict)}


def sheet_images(d):
    """(section, label, path relative to d) for every PNG worth looking at, in a fixed order."""
    out = []

    def add(section, pattern, labels=None, label=None):
        # Natural order (view-2 before view-10, 02-x before 08-y; T99: was by length first, which put 08-alive first).
        for p in sorted(glob.glob(os.path.join(d, pattern)), key=lambda s: [int(t) if t.isdigit() else t for t in re.split(r"(\d+)", s)]):
            name = os.path.basename(p)
            out.append((section, label or (labels or {}).get(name) or os.path.splitext(name)[0], os.path.relpath(p, d)))

    add("World tour", os.path.join("tour", "contact-sheet.png"), label="contact sheet")
    add("Device tour", os.path.join("device", "tour*-sheet.png"))
    add("Release smoke tour", os.path.join("release", "*.png"))
    add("First run (release, by taps)", os.path.join("first-run", "*.png"))
    add("First run, no server (release, by taps)", os.path.join("first-run", "direct", "*.png"))  # T105
    for part, title in (("features", "Release features (by taps)"), ("recenter", "Recenter (by taps)"), ("upgrade", "Upgrade in place"),
                        ("permissions", "Permissions"),
                        ("background", "Background recording under doze"), ("network", "Network: throttled, server killed"),
                        ("update", "In-app update (mock releases API)")):
        add(title, os.path.join("ui", part, "*.png"))
    add("Device matrix", os.path.join("ui", "matrix", "*", "*.png"))
    for part, title in (("live", "Live views (serve --live)"), ("demo-static", "Demo tour, static page"),
                        ("demo-serve", "Demo tour, devtool serve")):
        add(title, os.path.join(part, "*.png"), shot_labels(d, part))
    return out


def write_sheet(d, summary, channel, render):
    imgs = sheet_images(d)
    if not imgs:
        return None
    parts = []
    section = None
    for sec, label, rel in imgs:
        if sec != section:
            if section is not None:
                parts.append("</div>")
            big = sec in ("World tour", "Device tour")
            parts.append(f"<h2>{html.escape(sec)}</h2><div class='grid{' big' if big else ''}'>")
            section = sec
        parts.append(f"<figure><img src='{html.escape(rel.replace(os.sep, '/'))}'><figcaption>{html.escape(label)}</figcaption></figure>")
    parts.append("</div>")
    ok = summary["ok"]
    head = (f"<h1>Dystopo sweep {html.escape(summary['sweep'])}: "
            f"<span style='color:{'#1a7f37' if ok else '#c62828'}'>{'all green' if ok else 'FAILED: ' + html.escape(', '.join(summary['failed']))}</span></h1>")
    rows = "".join(f"<tr><td>{html.escape(k)}</td><td>{'ok' if v['ok'] else 'FAIL'}</td><td>{v['seconds']}</td></tr>"
                   for k, v in summary["parts"].items())
    doc = ("<!doctype html><meta charset='utf-8'><title>Sweep sheet</title><style>"
           "body{font:13px system-ui,sans-serif;margin:16px;background:#fff;color:#222;width:1568px}"
           "h1{font-size:20px}h2{font-size:15px;margin:18px 0 6px}table{border-collapse:collapse}td{padding:1px 10px 1px 0}"
           ".grid{display:grid;grid-template-columns:repeat(var(--cols,6),1fr);gap:6px}.grid.big{grid-template-columns:repeat(var(--bigcols,2),1fr)}"
           "figure{margin:0}img{width:100%;display:block;border:1px solid #ccc}figcaption{font-size:11px;color:#555}"
           f"</style>{head}<table>{rows}</table>{''.join(parts)}")
    html_path = os.path.join(d, "sheet.html")
    with open(html_path, "w", encoding="utf-8") as f:
        f.write(doc)
    if not render:
        return None
    try:
        from playwright.sync_api import sync_playwright
    except ImportError:
        return None
    png = os.path.join(d, "sheet.png")
    try:
        with sync_playwright() as p:
            launch = {"headless": True}
            if channel:
                launch["channel"] = channel
            b = p.chromium.launch(**launch)
            url = "file:///" + os.path.abspath(html_path).replace(os.sep, "/")
            page = b.new_page(viewport={"width": 1600, "height": 900})
            page.goto(url, wait_until="load")
            fit = fit_sheet(page)
            if fit["scale"] < 1:
                # Still too tall at the most columns: render the same layout with fewer device pixels per CSS pixel
                # (the capture limit is in device pixels; CSS zoom does not shrink what the full-page capture takes).
                page.close()
                page = b.new_page(viewport={"width": 1600, "height": 900}, device_scale_factor=fit["scale"])
                page.goto(url, wait_until="load")
                set_columns(page, fit["columns"], fit["bigColumns"])
            page.screenshot(path=png, full_page=True)
            b.close()
    except Exception as e:  # the sheet is a convenience; never fail the sweep over it
        summary["sheetError"] = str(e)[:300]
        return None
    w, h = png_size(png)
    summary["sheetSize"] = {"width": w, "height": h, **fit, "cut": h is not None and h >= SHEET_CAPTURE_PX}
    return png


# T111: Chrome's full-page capture is at most this tall; a taller page repeats its top and loses its end.
SHEET_CAPTURE_PX = 16384
SHEET_MAX_PX = 16000  # what the sheet is fitted to, with room for rounding


def set_columns(page, cols, big):
    page.evaluate("([c, b]) => { document.body.style.setProperty('--cols', c); document.body.style.setProperty('--bigcols', b); }", [cols, big])


def fit_sheet(page):
    """Fits the open sheet page into SHEET_MAX_PX: more thumbnail columns first; if the most columns are still too
    tall, the device scale factor the caller renders with (`scale`, below 1). Returns the page height before, the
    columns, the big columns, the scale and the fitted height in CSS pixels."""
    height = lambda: page.evaluate("() => document.documentElement.scrollHeight")
    before = height()
    out = {"pageHeight": before, "columns": 6, "bigColumns": 2, "scale": 1.0}
    if before <= SHEET_MAX_PX:
        return out
    for cols, big in ((7, 2), (8, 2), (10, 3), (12, 3)):
        set_columns(page, cols, big)
        out.update(columns=cols, bigColumns=big, fittedHeight=height())
        if out["fittedHeight"] <= SHEET_MAX_PX:
            return out
    out["scale"] = int(SHEET_MAX_PX / out["fittedHeight"] * 1000) / 1000  # rounded down
    return out


def png_size(path):
    """(width, height) from a PNG's header, or (None, None)."""
    try:
        with open(path, "rb") as f:
            head = f.read(24)
        if head[:8] == b"\x89PNG\r\n\x1a\n":
            return int.from_bytes(head[16:20], "big"), int.from_bytes(head[20:24], "big")
    except OSError:
        pass
    return None, None


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("dir")
    ap.add_argument("--channel", default="chrome", help="playwright browser channel for the sheet; '' for bundled chromium")
    ap.add_argument("--no-sheet", action="store_true", help="write sheet.html only, no PNG")
    a = ap.parse_args()
    d = os.path.abspath(a.dir)
    steps = read_steps(d)
    summary = {"sweep": os.path.basename(os.path.normpath(d)).removeprefix("sweep-"), "ok": True, "failed": [],
               "skipped": [], "parts": {}}
    meta = load(os.path.join(d, "meta.json"))
    if isinstance(meta, dict):
        summary.update({k: meta[k] for k in ("host", "commit", "noDevice", "startedAt") if k in meta})
    total = 0.0
    for name, code, secs, note in steps:
        if code == "skip":
            summary["skipped"].append(name + (f" ({note})" if note else ""))
            continue
        try:
            seconds = round(float(secs), 1)
        except ValueError:
            seconds = None
        total += seconds or 0
        ok = code == "0"
        entry = {"ok": ok, "seconds": seconds}
        if not ok:
            entry["exit"] = code
            summary["failed"].append(name)
        if note:
            entry["note"] = note
        fn = PARTS.get(name)
        if fn:
            try:
                entry.update(fn(d, name))
            except Exception as e:  # a report in an unexpected shape is reported, not fatal
                entry["summaryError"] = str(e)[:200]
        entry["ok"] = ok  # the exit code decides; a part's own "ok" field never overrides it
        summary["parts"][name] = entry
    if not steps:
        summary["failed"].append("no steps.tsv")
    summary["ok"] = not summary["failed"]
    summary["totalSeconds"] = round(total, 1)
    sheet = write_sheet(d, summary, a.channel, not a.no_sheet)
    if sheet:
        rel = os.path.relpath(sheet, REPO) if os.path.splitdrive(sheet)[0].lower() == os.path.splitdrive(REPO)[0].lower() else sheet
        summary["sheet"] = rel.replace(os.sep, "/")
    with open(os.path.join(d, "summary.json"), "w", encoding="utf-8") as f:
        json.dump(summary, f, indent=2)
    print(json.dumps(summary, indent=2))
    return 0 if summary["ok"] else 1


if __name__ == "__main__":
    sys.exit(main())
