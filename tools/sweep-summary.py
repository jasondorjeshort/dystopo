"""Folds one sweep's outputs into summary.json and a contact sheet of contact sheets (T71).

    python -I tools/sweep-summary.py out/sweep-<date> [--channel chrome|""] [--no-sheet]

Called by tools/sweep.ps1 and tools/sweep.sh at the end of a sweep; it can be rerun on a sweep folder by
hand. The folder holds steps.tsv, one line per part the sweep ran or skipped:

    <part>\t<exit code or "skip">\t<seconds>\t<note>

and each part's own outputs under fixed names (the table PARTS below). The summary keeps per part `ok`,
`seconds`, the exit code and the few numbers the dated sweep log entries quote (tour passed/failed, demo
views ok, live DEM sources, sync convergence, device views, probe count, release timings); `ok` overall is
true when every part that ran exited 0. Skipped parts are listed, not counted as failures.

Privacy: only public names reach the summary. Tour failures are named only when the area is in areas.json;
the device tour's pack list and logcat lines are left out; the live views carry the labels from the sweep's
own view list. Nothing here reads devtool.local.json.

The sheet (sheet.png, plus sheet.html beside it) puts every PNG the sweep produced on one page: the world
tour contact sheet, the device tour sheets, the demo, serve and live screenshots, the release smoke views.
It is rendered with Playwright (already needed by demo-check.py); without it the HTML is still written.
Prints the summary JSON; exit 1 when any part failed, 0 otherwise.
"""
import argparse
import glob
import html
import json
import os
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
        res.append({"viewsOk": s.get("viewsOk"), "viewsTotal": s.get("viewsTotal"),
                    "glyphsOk": (s.get("glyphs") or {}).get("ok"),
                    "failedViews": [v.get("label") for v in s.get("views") or [] if not v.get("ok")],
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


PARTS = {
    "tour": tour,
    "demo-static": demo,
    "demo-serve": demo,
    "live": demo,
    "sync-sim": sync_sim,
    "device-tour": device_tour,
    "probe": probe,
    "release": release,
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
        for p in sorted(glob.glob(os.path.join(d, pattern)), key=lambda s: (len(s), s)):
            name = os.path.basename(p)
            out.append((section, label or (labels or {}).get(name) or os.path.splitext(name)[0], os.path.relpath(p, d)))

    add("World tour", os.path.join("tour", "contact-sheet.png"), label="contact sheet")
    add("Device tour", os.path.join("device", "tour*-sheet.png"))
    add("Release smoke tour", os.path.join("release", "*.png"))
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
           ".grid{display:grid;grid-template-columns:repeat(6,1fr);gap:6px}.grid.big{grid-template-columns:repeat(2,1fr)}"
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
            page = b.new_page(viewport={"width": 1600, "height": 900})
            page.goto("file:///" + os.path.abspath(html_path).replace(os.sep, "/"), wait_until="load")
            page.screenshot(path=png, full_page=True)
            b.close()
    except Exception as e:  # the sheet is a convenience; never fail the sweep over it
        summary["sheetError"] = str(e)[:300]
        return None
    return png


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
