"""Headless check of the browser demo: loads the viewer, waits for the map to settle, and prints a
JSON report of console errors, uncaught exceptions, failed requests, map state, and a screenshot path.

    python -I tools/demo-check.py [--url http://localhost:8765/tools/demo/] [--view 13/46.80/-121.74]
                                  [--out out/demo-check.png] [--width 1280] [--height 900] [--timeout 60]

    # a tour: one browser session, one report per view, screenshots out/tour-0.png, out/tour-1.png, ...
    python -I tools/demo-check.py --views "9/46.85/-121.7,13/46.85/-121.76:summit" --out out/tour.png
    python -I tools/demo-check.py --views-file tools/demo-tour.txt --out out/tour.png

A view is `z/lat/lon` with an optional `:label` and optional `|`-separated options: `WxH` sets the viewport for
that view (e.g. `|390x844` for a phone) and `req=a+b` names layers that must have rendered features there
(e.g. `|req=poi-markers+cliffs`). --views-file has one view per line (`#` comments).

Needs the demo server running (tools/demo-server.py) and Playwright with Chrome (pip package
`playwright`; uses the installed Google Chrome via channel="chrome", or `python -m playwright install
chromium`). Exit code 1 if the map never became idle or any page error / console error occurred.
In a tour, a view inside the generated coverage (region box at z8+, core box at z13+) also fails when
the contour layers rendered no features; views outside coverage are expected to be blank and only
fail on errors.
"""
import argparse
import json
import os
import sys
import time

from playwright.sync_api import sync_playwright

# Generated demo coverage (see the generation commands at the top of tools/demo/index.html).
REGION = (-122.05, 46.60, -121.40, 47.10)   # z8-12
CORE = (-121.86, 46.78, -121.66, 46.93)     # z13-14
# Layers that must have rendered features anywhere inside coverage.
REQUIRED_LAYERS = ("contour-minor", "contour-index")


def in_box(box, lat, lon):
    return box[0] <= lon <= box[2] and box[1] <= lat <= box[3]


def covered(z, lat, lon):
    """Is the view centre inside generated DEM coverage? DEM tiles exist to z14 only in CORE, and
    z12 tiles are overzoomed beyond that, so z13+ outside CORE still counts as covered if in REGION."""
    return z >= 8 and in_box(REGION, lat, lon)


def parse_view(text):
    label = ""
    opts = text.split("|")[1:]
    text = text.split("|")[0]
    size, req = None, []
    for o in opts:
        o = o.strip()
        if o.startswith("req="):
            req = [s for s in o[4:].split("+") if s]
        elif "x" in o:
            size = tuple(int(n) for n in o.split("x"))
    if ":" in text:
        text, label = text.split(":", 1)
    z, lat, lon = (float(x) for x in text.strip().split("/"))
    return {"view": f"{z:g}/{lat:g}/{lon:g}", "z": z, "lat": lat, "lon": lon, "label": label.strip(), "size": size, "req": req}


def read_views(args):
    items = []
    if args.view:
        items.append(args.view)
    if args.views:
        items += [s for s in args.views.split(",") if s.strip()]
    if args.views_file:
        with open(args.views_file, encoding="utf-8") as f:
            for line in f:
                line = line.split("#", 1)[0].strip()
                if line:
                    items.append(line)
    return [parse_view(s) for s in items]


STATE_JS = """() => {
    const m = window.map; if (!m || !m.getCenter) return null; // before the page script runs, window.map is the #map div
    const c = m.getCenter();
    return { loaded: m.loaded(), tilesLoaded: m.areTilesLoaded(), zoom: +m.getZoom().toFixed(2),
             center: [+c.lng.toFixed(5), +c.lat.toFixed(5)], styleLoaded: m.isStyleLoaded() };
}"""
LAYERS_JS = """() => { const out = {}; for (const l of map.getStyle().layers) {
     const n = map.queryRenderedFeatures({layers: [l.id]}).length; out[l.id] = n; } return out; }"""


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", default="http://localhost:8765/tools/demo/")
    ap.add_argument("--view", default="", help="zoom/lat/lon appended as the URL hash, e.g. 13/46.80/-121.74")
    ap.add_argument("--views", default="", help="comma-separated list of z/lat/lon[:label]; runs a tour in one session")
    ap.add_argument("--views-file", default="", help="file with one z/lat/lon[:label] per line")
    ap.add_argument("--out", default="out/demo-check.png", help="screenshot path; a tour writes <stem>-<i>.png")
    ap.add_argument("--width", type=int, default=1280)
    ap.add_argument("--height", type=int, default=900)
    ap.add_argument("--timeout", type=float, default=60.0)
    ap.add_argument("--channel", default="chrome", help="playwright browser channel; use '' for bundled chromium")
    args = ap.parse_args()

    views = read_views(args)
    tour = bool(args.views or args.views_file)
    if not views:
        views = [{"view": "", "z": None, "lat": None, "lon": None, "label": ""}]
    stem, ext = os.path.splitext(args.out)
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)

    reports = []
    with sync_playwright() as p:
        launch = {"headless": True, "args": ["--use-gl=angle", "--use-angle=swiftshader", "--enable-unsafe-swiftshader", "--ignore-gpu-blocklist"]}
        if args.channel:
            launch["channel"] = args.channel
        browser = p.chromium.launch(**launch)
        page = browser.new_page(viewport={"width": args.width, "height": args.height})
        cur = {}  # the report currently collecting events

        def on_console(m):
            # Tiles outside the generated area 404 by design; count them separately instead of failing.
            if m.type in ("error", "warning") and "status of 404" not in m.text:
                cur["console"].append({"type": m.type, "text": m.text[:500]})

        def on_response(r):
            if r.status == 404 and r.url.endswith(".png") and "/out/" in r.url:
                cur["missingTiles"] += 1
            elif r.status >= 400:
                cur["failedRequests"].append({"url": r.url, "status": r.status})

        page.on("console", on_console)
        page.on("pageerror", lambda e: cur["pageErrors"].append(str(e)[:500]))
        def on_request_failed(r):
            # MapLibre aborts in-flight tile requests when the view changes; that is not a failure.
            if "ERR_ABORTED" not in str(r.failure):
                cur["failedRequests"].append({"url": r.url, "error": r.failure})

        page.on("requestfailed", on_request_failed)
        page.on("response", on_response)

        for i, v in enumerate(views):
            url = args.url + ("#" + v["view"] if v["view"] else "")
            report = {"url": url, "console": [], "pageErrors": [], "failedRequests": [], "idle": False, "map": None, "missingTiles": 0}
            if tour:
                report["index"] = i
                report["label"] = v["label"]
            page.set_viewport_size({"width": v["size"][0], "height": v["size"][1]} if v.get("size") else {"width": args.width, "height": args.height})
            cur.clear()
            cur.update(report)
            report = cur  # share the lists/counters the handlers mutate
            t0 = time.time()
            if i == 0:
                page.goto(url, wait_until="load", timeout=args.timeout * 1000)
            else:
                page.evaluate("h => { location.hash = h; }", v["view"])
                page.wait_for_timeout(500)  # let the hash handler start the jump and new tile requests
            # The page exposes nothing by default; poll MapLibre through the global `map` once it exists.
            deadline = t0 + args.timeout
            state = None
            while time.time() < deadline:
                state = page.evaluate(STATE_JS)
                if state and state["loaded"] and state["tilesLoaded"] and state["styleLoaded"]:
                    if v["z"] is None or abs(state["zoom"] - v["z"]) < 0.05:
                        report["idle"] = True
                        break
                time.sleep(0.5)
            report["map"] = state
            # Let labels and the contour worker finish their last frame.
            page.wait_for_timeout(1500)
            if report["idle"]:
                report["layers"] = page.evaluate(LAYERS_JS)
                if i == 0:
                    report["webgl"] = page.evaluate("() => { const c = document.createElement('canvas'); const gl = c.getContext('webgl2') || c.getContext('webgl'); return gl ? gl.getParameter(gl.RENDERER) : null; }")
            shot = f"{stem}-{i}{ext}" if tour else args.out
            page.screenshot(path=shot)
            report["screenshot"] = shot
            report["seconds"] = round(time.time() - t0, 1)

            ok = report["idle"] and not report["pageErrors"] and not report["failedRequests"] and not any(c["type"] == "error" for c in report["console"])
            if tour and v["z"] is not None:
                inside = covered(v["z"], v["lat"], v["lon"])
                report["inCoverage"] = inside
                report["emptyLayers"] = []
                if inside and report["idle"]:
                    report["emptyLayers"] = [l for l in REQUIRED_LAYERS if report["layers"].get(l, 0) == 0]
                    ok = ok and not report["emptyLayers"]
                if v.get("req") and report["idle"]:
                    report["missingRequired"] = [l for l in v["req"] if report["layers"].get(l, 0) == 0]
                    ok = ok and not report["missingRequired"]
            report["ok"] = ok
            reports.append(dict(report))
        browser.close()

    if tour:
        print(f"{'#':>2} {'view':<26} {'label':<14} {'ok':<4} {'cov':<4} {'miss':>5} {'err':>3} {'cont':>5} {'idx':>4} {'clbl':>4} {'trail':>5} {'poi':>4} {'cliff':>5} {'water':>5} {'peak':>4} {'place':>5} {'s':>5}", file=sys.stderr)
        for r in reports:
            L = r.get("layers", {})
            print(f"{r['index']:>2} {r['url'].split('#')[-1]:<26} {r['label']:<14} {'ok' if r['ok'] else 'FAIL':<4} "
                  f"{'yes' if r.get('inCoverage') else 'no':<4} {r['missingTiles']:>5} "
                  f"{len(r['console']) + len(r['pageErrors']) + len(r['failedRequests']):>3} "
                  f"{L.get('contour-minor', 0):>5} {L.get('contour-index', 0):>4} {L.get('contour-labels', 0):>4} {L.get('trails', 0) + L.get('trails-mountain', 0) + L.get('trails-alpine', 0):>5} {L.get('poi-markers', 0):>4} {L.get('cliffs', 0):>5} "
                  f"{L.get('water', 0) + L.get('waterway', 0):>5} {L.get('peaks', 0):>4} {L.get('places', 0):>5} {r['seconds']:>5}", file=sys.stderr)
        print(json.dumps(reports, indent=2))
        return 0 if all(r["ok"] for r in reports) else 1
    print(json.dumps(reports[0], indent=2))
    return 0 if reports[0]["ok"] else 1


if __name__ == "__main__":
    sys.exit(main())
