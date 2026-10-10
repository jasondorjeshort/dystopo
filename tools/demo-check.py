"""Headless check of the browser demo: loads the viewer, waits for the map to settle, and prints a
JSON report of console errors, uncaught exceptions, failed requests, map state, and a screenshot path.

    python -I tools/demo-check.py [--url http://localhost:8765/tools/demo/] [--view 13/46.80/-121.74]
                                  [--out out/demo-check.png] [--width 1280] [--height 900] [--timeout 60]

Needs the demo server running (tools/demo-server.py) and Playwright with Chrome (pip package
`playwright`; uses the installed Google Chrome via channel="chrome", or `python -m playwright install
chromium`). Exit code 1 if the map never became idle or any page error / console error occurred.
"""
import argparse
import json
import sys
import time

from playwright.sync_api import sync_playwright


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", default="http://localhost:8765/tools/demo/")
    ap.add_argument("--view", default="", help="zoom/lat/lon appended as the URL hash, e.g. 13/46.80/-121.74")
    ap.add_argument("--out", default="out/demo-check.png")
    ap.add_argument("--width", type=int, default=1280)
    ap.add_argument("--height", type=int, default=900)
    ap.add_argument("--timeout", type=float, default=60.0)
    ap.add_argument("--channel", default="chrome", help="playwright browser channel; use '' for bundled chromium")
    args = ap.parse_args()

    url = args.url + ("#" + args.view if args.view else "")
    report = {"url": url, "console": [], "pageErrors": [], "failedRequests": [], "idle": False, "map": None}

    with sync_playwright() as p:
        launch = {"headless": True, "args": ["--use-gl=angle", "--use-angle=swiftshader", "--enable-unsafe-swiftshader", "--ignore-gpu-blocklist"]}
        if args.channel:
            launch["channel"] = args.channel
        browser = p.chromium.launch(**launch)
        page = browser.new_page(viewport={"width": args.width, "height": args.height})
        # Tiles outside the generated area 404 by design; count them separately instead of failing.
        report["missingTiles"] = 0

        def on_console(m):
            if m.type in ("error", "warning") and "status of 404" not in m.text:
                report["console"].append({"type": m.type, "text": m.text[:500]})

        def on_response(r):
            if r.status == 404 and r.url.endswith(".png") and "/out/" in r.url:
                report["missingTiles"] += 1
            elif r.status >= 400:
                report["failedRequests"].append({"url": r.url, "status": r.status})

        page.on("console", on_console)
        page.on("pageerror", lambda e: report["pageErrors"].append(str(e)[:500]))
        page.on("requestfailed", lambda r: report["failedRequests"].append({"url": r.url, "error": r.failure}))
        page.on("response", on_response)

        t0 = time.time()
        page.goto(url, wait_until="load", timeout=args.timeout * 1000)
        # The page exposes nothing by default; poll MapLibre through the global `map` once it exists.
        deadline = t0 + args.timeout
        while time.time() < deadline:
            state = page.evaluate(
                """() => {
                    const m = window.map; if (!m) return null;
                    const c = m.getCenter();
                    return { loaded: m.loaded(), tilesLoaded: m.areTilesLoaded(), zoom: +m.getZoom().toFixed(2),
                             center: [+c.lng.toFixed(5), +c.lat.toFixed(5)], styleLoaded: m.isStyleLoaded() };
                }"""
            )
            if state and state["loaded"] and state["tilesLoaded"] and state["styleLoaded"]:
                report["idle"] = True
                report["map"] = state
                break
            time.sleep(0.5)
        if report["map"] is None and state:
            report["map"] = state
        # Let labels and the contour worker finish their last frame.
        page.wait_for_timeout(1500)
        if report["idle"]:
            report["layers"] = page.evaluate(
                """() => { const out = {}; for (const l of map.getStyle().layers) {
                     const n = map.queryRenderedFeatures({layers: [l.id]}).length; out[l.id] = n; } return out; }"""
            )
            report["webgl"] = page.evaluate("() => { const c = document.createElement('canvas'); const gl = c.getContext('webgl2') || c.getContext('webgl'); return gl ? gl.getParameter(gl.RENDERER) : null; }")
        page.screenshot(path=args.out)
        report["screenshot"] = args.out
        report["seconds"] = round(time.time() - t0, 1)
        browser.close()

    print(json.dumps(report, indent=2))
    ok = report["idle"] and not report["pageErrors"] and not report["failedRequests"] and not any(c["type"] == "error" for c in report["console"])
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
