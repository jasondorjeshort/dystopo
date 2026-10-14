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

One tour file serves both pages: the static demo (tools/demo/index.html) and the phone style that `devtool serve`
draws (shared MapLibreStyle; also live mode's /phone). Their layer ids differ, so a `req=` name is a role (ROLES
below: trails, poi-markers, cliffs, water), met when any of the role's layers present in the page's style has
features; a name that is not a role is a layer id. A role none of whose layers exists in the style fails the view
(a renamed layer must not pass silently). In pack mode a 404 for a tile route (`/dem/`, `/osm/`, `/contours/` as
`devtool serve` answers outside its pack, or `/out/...png` on the static server) is a tile outside the pack, counted
under missingTiles; in live mode every tile must exist, so there it is a failed request.

Live world mode (`devtool serve --live`, then --url http://localhost:8687/?live=1): every view counts as covered,
and besides the contour layers it must show hillshade (DEM tiles from some source) and OSM base layers (any layer
of the osm source with features). `|src=live-copernicus` requires the view's DEM tiles to come mostly from that
source (pack, live-3dep, live-copernicus); the report's `sources` has the counts the page's status box shows.
--views also takes several space-separated views (one argument per view).

Needs the demo server running (tools/demo-server.py) and Playwright with Chrome (pip package
`playwright`; uses the installed Google Chrome via channel="chrome", or `python -m playwright install
chromium`). Exit code 1 if the map never became idle or any page error / console error occurred. Idle is
two polls in a row with every tile loaded; a view that is not idle after --timeout is jumped to again and
given another --timeout (--retries, default 1: the host under load, T66), and the report lists the misses
under `notIdle` with the flags that were false.
T92: --trip-sync (live mode, with --route) checks waypoint editing and the pull side of the browser's trip replica
(tools/demo/trip-sync.js: check_trip_sync); --token types a server's bearer token into the page's Token field.
In a tour, a view inside the generated coverage (region box at z8+, core box at z13+) also fails when
the contour layers rendered no features; views outside coverage are expected to be blank and only
fail on errors.
"""
import argparse
import json
import math
import os
import re
import sys
import time
import urllib.parse
import urllib.request
import uuid

from playwright.sync_api import sync_playwright

# Generated demo coverage (see the generation commands at the top of tools/demo/index.html).
REGION = (-122.05, 46.60, -121.40, 47.10)   # z8-12
CORE = (-121.86, 46.78, -121.66, 46.93)     # z13-14
# Layers that must have rendered features anywhere inside coverage (same ids in both styles).
REQUIRED_LAYERS = ("contour-minor", "contour-index")
# req= roles: the static demo's layer ids first, then the phone style's (DefaultOsmStyle ids, via MapLibreStyle).
ROLES = {
    "trails": ["trails", "trails-mountain", "trails-alpine", "trail", "trail-mountain", "trail-demanding", "trail-alpine"],
    "poi-markers": ["poi-markers", "huts", "trailheads", "campsites", "shelters", "waterfalls", "viewpoints", "springs",
                    "caves", "parking", "outdoor-pois"],
    "cliffs": ["cliffs", "cliff"],
    "water": ["water"],
}
# A tile request: what a 404 means depends on the mode (see the module docstring).
TILE_URL = re.compile(r"/(dem|osm|contours)/\d+/\d+/\d+\.(png|pbf)(\?|$)")


def missing_roles(req, layers):
    """The req= names not met by `layers` (layer id -> rendered feature count), each with the reason."""
    out = []
    for name in req:
        ids = [l for l in ROLES.get(name, [name]) if l in layers]
        if not ids:
            out.append(name + " (no such layer in this style)")
        elif sum(layers[l] for l in ids) == 0:
            out.append(name)
    return out


def role_count(role, layers):
    """Rendered features of a role's layers (for the summary table)."""
    return sum(layers.get(l, 0) for l in ROLES.get(role, [role]))


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
    size, req, src = None, [], None
    for o in opts:
        o = o.strip()
        if o.startswith("src="):
            src = o[4:]
        elif o.startswith("req="):
            req = [s for s in o[4:].split("+") if s]
        elif "x" in o:
            size = tuple(int(n) for n in o.split("x"))
    if ":" in text:
        text, label = text.split(":", 1)
    z, lat, lon = (float(x) for x in text.strip().split("/"))
    return {"view": f"{z:g}/{lat:g}/{lon:g}", "z": z, "lat": lat, "lon": lon, "label": label.strip(), "size": size, "req": req, "src": src}


def read_views(args):
    items = []
    if args.view:
        items.append(args.view)
    if args.views:
        items += [s for v in args.views for s in v.split(",") if s.strip()]
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


def check_route(page, spec, shot, timeout, mode=""):
    """T68: drive the trip editor (tools/demo/trips.js, live mode) with the real mouse. `spec` is
    `lat,lon;lat,lon[;lat,lon]`: click the start and the end with "Plan route" on, wait for the route, click the
    middle of the route to add a via point, drag that point's marker to the third place (default: 300 m east of
    where it was), check the route was rerouted, export GPX, "Save as trip" (into the browser replica and on to the
    server's sync peer), and screenshot `shot`. Returns a report with `ok`."""
    pts = [tuple(float(v) for v in p.split(",")) for p in spec.split(";") if p.strip()]
    out = {"ok": False, "spec": spec, "screenshot": shot, "steps": []}
    step = lambda name, **kw: out["steps"].append({"step": name, **kw})
    try:
        page.wait_for_function("() => window.dystopoTrips", timeout=timeout * 1000)
        a, b = pts[0], pts[1]
        page.evaluate("""([a, b]) => map.fitBounds([[Math.min(a[1], b[1]), Math.min(a[0], b[0])], [Math.max(a[1], b[1]), Math.max(a[0], b[0])]],
                         { padding: { top: 120, bottom: 300, left: 340, right: 380 }, duration: 0 })""", [a, b])
        page.wait_for_timeout(800)
        if mode:  # T73: plan in this mode (hike, bike, drive) through the page's Mode select
            page.select_option("#trip-mode", mode)
        page.click("#trip-plan")
        box = page.evaluate("() => { const r = map.getCanvas().getBoundingClientRect(); return [r.left, r.top]; }")
        px = lambda p: [v + o for v, o in zip(page.evaluate("p => { const q = map.project([p[1], p[0]]); return [q.x, q.y]; }", list(p)), box)]
        t0 = time.time()
        for p in (a, b):
            page.mouse.click(*px(p))
            page.wait_for_timeout(150)
        s = page.evaluate("() => window.dystopoTrips.idle()")
        r = s["route"]
        if not r:
            raise RuntimeError("no route after two clicks: " + str(s.get("error")))
        step("plan", seconds=round(time.time() - t0, 2), pack=r["pack"], mode=r.get("mode"), legs=r["legs"], notes=r["notes"], notice=r.get("notice"),
             cardNotes=page.inner_text("#trip-notes"), summary=r["summary"])
        if mode and r.get("mode") != mode:
            raise RuntimeError("planned in %s, not %s" % (r.get("mode"), mode))
        out["planned"] = r["summary"]
        # Add a via point on the route (a click on the line inserts it into that leg), then drag its marker.
        page.mouse.click(*px(r["middle"]))
        s = page.evaluate("() => window.dystopoTrips.idle()")
        via = s["route"]["via"]
        k = 1 if len(via) == 3 else len(via) - 1
        step("add via", via=len(via), index=k, distanceM=s["route"]["summary"]["distanceM"])
        target = pts[2] if len(pts) > 2 else (via[k][0], via[k][1] + 300 / (111195 * math.cos(math.radians(via[k][0]))))
        before = s["route"]
        el = page.locator(f".trip-via[data-index='{k}']")
        bb = el.bounding_box()
        page.mouse.move(bb["x"] + bb["width"] / 2, bb["y"] + bb["height"] / 2)
        page.mouse.down()
        end = px(target)
        page.mouse.move(end[0], end[1], steps=12)
        page.mouse.up()
        page.wait_for_timeout(200)
        s = page.evaluate("() => window.dystopoTrips.idle()")
        after = s["route"]
        moved = math.dist(before["via"][k], after["via"][k]) * 111195
        rerouted = after["path"] != before["path"] and moved > 50
        step("drag via", movedM=round(moved), legs=after["legs"], notes=after["notes"], distanceM=after["summary"]["distanceM"], rerouted=rerouted)
        out["dragged"] = after["summary"]
        gpx = page.evaluate("() => window.dystopoTrips.gpx(false)")
        gpx_ok = gpx.startswith("<?xml") and "<rte>" in gpx and gpx.count("<rtept") >= after["summary"]["points"]
        step("gpx", bytes=len(gpx), rtept=gpx.count("<rtept"), ok=gpx_ok)
        page.fill("#trip-name", "demo-check trip")
        page.click("#trip-save")
        page.wait_for_function("() => { const s = window.dystopoTrips.state(); return s.saved && s.lastPush; }", timeout=timeout * 1000)
        s = page.evaluate("() => window.dystopoTrips.state()")
        rep = page.evaluate("() => window.dystopoTrips.replica()")
        push = s["lastPush"]
        step("save", id=s["id"], replica=rep, push=push)
        out.update(tripId=s["id"], push=push, listed=page.locator("#trip-list li").count())
        page.wait_for_timeout(500)
        page.screenshot(path=shot)
        layers = page.evaluate("() => ({ line: map.queryRenderedFeatures({ layers: ['trip-line', 'trip-straight'] }).length, markers: document.querySelectorAll('.trip-via').length })")
        step("drawn", **layers)
        out["ok"] = bool(r["legs"]) and len(via) >= 3 and rerouted and gpx_ok and push.get("ok") and layers["line"] > 0 and layers["markers"] == len(after["via"])
    except Exception as e:  # report, do not crash the tour
        out["error"] = str(e)[:500]
        try:
            page.screenshot(path=shot)
        except Exception:
            pass
    return out


def check_long_route(page, spec, shot, timeout, mode="drive", max_ms=10000, min_packs=2):
    """T77: a long route in the trip editor (live mode). `spec` is `lat,lon;lat,lon`: fit both on screen, click the
    start and the end with "Plan route" on (mode `mode`, default drive), wait for the route, and check that every leg
    follows the network (no straight leg, no notes), that the server planned it across at least `min_packs` packs
    in under `max_ms` (its `ms`, which includes opening or building the packs' graphs), and that the line is drawn;
    screenshot `shot`. Returns a report with `ok`."""
    pts = [tuple(float(v) for v in p.split(",")) for p in spec.split(";") if p.strip()]
    out = {"ok": False, "spec": spec, "mode": mode, "maxMs": max_ms, "minPacks": min_packs, "screenshot": shot}
    try:
        page.wait_for_function("() => window.dystopoTrips", timeout=timeout * 1000)
        page.evaluate("() => window.dystopoTrips.clear && window.dystopoTrips.clear()")
        a, b = pts[0], pts[1]
        page.evaluate("""([a, b]) => map.fitBounds([[Math.min(a[1], b[1]), Math.min(a[0], b[0])], [Math.max(a[1], b[1]), Math.max(a[0], b[0])]],
                         { padding: { top: 120, bottom: 300, left: 340, right: 380 }, duration: 0 })""", [a, b])
        page.wait_for_timeout(800)
        page.select_option("#trip-mode", mode)
        if not page.evaluate("() => window.dystopoTrips.state().planning"):
            page.click("#trip-plan")
        box = page.evaluate("() => { const r = map.getCanvas().getBoundingClientRect(); return [r.left, r.top]; }")
        px = lambda p: [v + o for v, o in zip(page.evaluate("p => { const q = map.project([p[1], p[0]]); return [q.x, q.y]; }", list(p)), box)]
        t0 = time.time()
        for p in (a, b):
            page.mouse.click(*px(p))
            page.wait_for_timeout(150)
        s = page.evaluate("() => window.dystopoTrips.idle()")
        r = s["route"]
        if not r:
            raise RuntimeError("no route after two clicks: " + str(s.get("error")))
        long = r.get("longRoute") or {}
        out.update(seconds=round(time.time() - t0, 2), pack=r["pack"], legs=r["legs"], notes=r["notes"], messages=r.get("messages"),
                   summary=r["summary"], longRoute=long, cardNotes=page.inner_text("#trip-notes"))
        page.wait_for_timeout(1500)
        page.screenshot(path=shot)
        drawn = page.evaluate("() => map.queryRenderedFeatures({ layers: ['trip-line'] }).length")
        out["drawn"] = drawn
        problems = []
        if r.get("mode") != mode:
            problems.append("planned in %s" % r.get("mode"))
        if any(k != "trail" for k in r["legs"]) or r["notes"]:
            problems.append("a straight leg: %s" % (r.get("messages") or r["notes"]))
        if len(long.get("packs") or []) < min_packs:
            problems.append("planned over %s, fewer than %d packs" % (long.get("packs"), min_packs))
        if (long.get("ms") or 0) > max_ms:
            problems.append("the server took %s ms" % long.get("ms"))
        if drawn == 0:
            problems.append("no route line drawn")
        out["problems"] = problems
        out["ok"] = not problems
    except Exception as e:  # report, do not crash the tour
        out["error"] = str(e)[:500]
        try:
            page.screenshot(path=shot)
        except Exception:
            pass
    return out


def server_json(base, path, token="", method="GET", body=None, raw=False):
    """T92: one request to the server under test (urllib), with the bearer token when given."""
    req = urllib.request.Request(base + path, data=body, method=method)
    if token:
        req.add_header("Authorization", "Bearer " + token)
    if body is not None and not raw:
        req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req, timeout=60) as r:
        data = r.read()
    return data if raw else json.loads(data)


def check_trip_sync(browser, page, page_url, shot, timeout, token="", size=(1280, 900)):
    """T92: waypoint editing and the pull side of the browser's trip replica (tools/demo/trip-sync.js), after --route
    saved a trip. With the real mouse: "Add waypoint" and a click on the map, a drag of that waypoint; through the page:
    a second waypoint and a rename. The server's replica must then list both (GET /sync/routes?full=1). A phone-like
    device then renames the trip and removes one waypoint on the server (its own operations through /route/bundle and
    /sync/push); "Get trips from the server" must bring both changes into this page, and a fresh browser (empty
    localStorage) must get the trip with its waypoint by pulling. Returns a report with `ok`."""
    out = {"ok": False, "screenshot": shot, "steps": []}
    step = lambda name, **kw: out["steps"].append({"step": name, **kw})
    u = urllib.parse.urlsplit(page_url)
    base = f"{u.scheme}://{u.netloc}"
    try:
        page.wait_for_function("() => window.dystopoTripSync && window.dystopoTrips", timeout=timeout * 1000)
        trip = page.evaluate("() => window.dystopoTrips.state()")
        if not trip["id"] or not trip["saved"]:
            raise RuntimeError("no saved trip open: run --trip-sync with --route")
        tid = trip["id"]
        page.evaluate("() => window.dystopoTripSync.refresh()")
        box = page.evaluate("() => { const r = map.getCanvas().getBoundingClientRect(); return [r.left, r.top]; }")
        px = lambda p: [v + o for v, o in zip(page.evaluate("p => { const q = map.project([p[1], p[0]]); return [q.x, q.y]; }", list(p)), box)]
        mid = trip["route"]["middle"]
        at = (mid[0] + 0.0012, mid[1] - 0.0016)  # beside the line, so the click is not on a via marker
        page.fill("#wpt-name", "Lunch spot")
        page.click("#wpt-add")
        page.mouse.click(*px(at))
        s = page.evaluate("() => window.dystopoTripSync.idle()")
        if len(s["waypoints"]) != 1:
            raise RuntimeError("no waypoint after the click: " + str(s.get("error")))
        w1 = s["waypoints"][0]
        step("add by click", waypoint=w1["name"], at=[w1["lat"], w1["lon"]], offM=round(math.dist(at, (w1["lat"], w1["lon"])) * 111195, 1))
        page.click("#wpt-add")  # off again
        page.evaluate("p => window.dystopoTripSync.add(p[0], p[1], 'Viewpoint')", [mid[0] - 0.001, mid[1] + 0.0015])
        s = page.evaluate("() => window.dystopoTripSync.idle()")
        step("add by api", waypoints=len(s["waypoints"]), markers=len(s["markers"]))
        # Drag the first one about 150 m north with the real mouse.
        el = page.locator(f".trip-wpt[data-id='{w1['id']}']")
        bb = el.bounding_box()
        page.mouse.move(bb["x"] + 4, bb["y"] + bb["height"] - 4)
        page.mouse.down()
        start = px((w1["lat"], w1["lon"]))
        end = px((w1["lat"] + 150 / 111195, w1["lon"]))
        page.mouse.move(bb["x"] + 4 + (end[0] - start[0]), bb["y"] + bb["height"] - 4 + (end[1] - start[1]), steps=10)
        page.mouse.up()
        s = page.evaluate("() => window.dystopoTripSync.idle()")
        moved = next(w for w in s["waypoints"] if w["id"] == w1["id"])
        moved_m = math.dist((w1["lat"], w1["lon"]), (moved["lat"], moved["lon"])) * 111195
        step("drag", movedM=round(moved_m))
        # Rename the second in the list.
        w2 = next(w for w in s["waypoints"] if w["id"] != w1["id"])
        page.fill(f"#wpt-list input[data-id='{w2['id']}']", "Viewpoint east")
        page.press(f"#wpt-list input[data-id='{w2['id']}']", "Enter")
        page.locator("#wpt-name").click()  # blur fires change
        s = page.evaluate("() => window.dystopoTripSync.idle()")
        last = page.evaluate("() => window.dystopoTrips.state().lastPush || window.dystopoTrips.replica().lastPush")
        step("rename", names=[w["name"] for w in s["waypoints"]], push=last)
        server = {r["id"]: r for r in server_json(base, "/sync/routes?full=1", token)["routes"]}
        on_server = [w["name"] for w in server.get(tid, {}).get("waypointList", [])]
        step("server", waypoints=on_server)
        server_ok = sorted(on_server) == ["Lunch spot", "Viewpoint east"]

        # A phone-like device renames the trip and removes "Lunch spot" on the server.
        dev = str(uuid.uuid4())
        wall = int(time.time() * 1000) + 2000
        hlc = lambda i: "%012x.%06x.%s" % (wall, i, dev)
        ops = [{"hlc": hlc(0), "type": "set", "id": tid, "field": "name", "value": "Renamed on a phone"},
               {"hlc": hlc(1), "type": "set", "id": tid, "field": "wpt:" + w1["id"], "value": None}]
        bundle = server_json(base, "/route/bundle", token, "POST", json.dumps({"device": dev, "ops": ops}).encode(), raw=True)
        pushed = server_json(base, "/sync/push", token, "POST", bundle, raw=True)
        step("phone edit", push=json.loads(pushed))
        r = page.evaluate("() => window.dystopoTripSync.pull()")
        page.evaluate("() => window.dystopoTripSync.idle()")
        here = page.evaluate("id => ({ trip: (window.dystopoTrips.trips().find(t => t.id === id) || {}).name, waypoints: window.dystopoTripSync.waypoints().map(w => w.name), listed: [...document.querySelectorAll('#trip-list li span')].map(e => e.textContent) })", tid)
        step("pull here", result=page.evaluate("() => window.dystopoTripSync.state().lastPull"), **here)
        card = page.input_value("#trip-name")
        step("card after pull", card=card)
        pulled_ok = card == "Renamed on a phone" and here["trip"] == "Renamed on a phone" and here["waypoints"] == ["Viewpoint east"] and any("Renamed on a phone" in t for t in here["listed"])
        page.wait_for_timeout(400)
        page.screenshot(path=shot)

        # A fresh browser: nothing in localStorage; the pull brings the trip, opening it draws its waypoint.
        ctx = browser.new_context(viewport={"width": size[0], "height": size[1]})
        fresh = ctx.new_page()
        fresh.goto(page_url, wait_until="load", timeout=timeout * 1000)
        fresh.wait_for_function("() => window.dystopoTripSync && window.dystopoTrips", timeout=timeout * 1000)
        if token:
            fresh.fill("#packtoken", token)
            fresh.dispatch_event("#packtoken", "change")
        fresh.wait_for_function("() => map.isStyleLoaded() && map.getSource('trip')", timeout=timeout * 1000)
        before = fresh.evaluate("() => window.dystopoTrips.trips().length")
        fresh.click("#sync-pull")
        fresh.wait_for_function("() => window.dystopoTripSync.state().lastPull", timeout=timeout * 1000)
        pull2 = fresh.evaluate("() => window.dystopoTripSync.state().lastPull")
        fresh.evaluate("id => window.dystopoTrips.load(id)", tid)
        fresh.evaluate("() => window.dystopoTrips.idle()")
        fresh.wait_for_function("() => window.dystopoTripSync.state().open", timeout=timeout * 1000)
        fs = fresh.evaluate("() => window.dystopoTripSync.state()")
        drawn = fresh.evaluate("() => document.querySelectorAll('.trip-wpt').length")
        froute = fresh.evaluate("() => window.dystopoTrips.state().route")
        stem, ext = os.path.splitext(shot)
        fresh.wait_for_timeout(1200)
        line = fresh.evaluate("() => map.queryRenderedFeatures({ layers: ['trip-line', 'trip-straight'] }).length")
        fresh.screenshot(path=f"{stem}-fresh{ext}")
        step("fresh browser", tripsBefore=before, pull=pull2, waypoints=[w["name"] for w in fs["waypoints"]], drawn=drawn, lineFeatures=line,
             route=froute and {"pack": froute["pack"], "distanceM": froute["summary"]["distanceM"]})
        ctx.close()
        # The phone deletes the trip: the next pull here drops it from the list.
        bundle = server_json(base, "/route/bundle", token, "POST", json.dumps({"device": dev, "ops": [{"hlc": hlc(2), "type": "delete", "id": tid}]}).encode(), raw=True)
        server_json(base, "/sync/push", token, "POST", bundle, raw=True)
        gone = page.evaluate("() => window.dystopoTripSync.pull()")
        still = page.evaluate("id => window.dystopoTrips.trips().some(t => t.id === id)", tid)
        step("deleted elsewhere", pull=gone, stillListed=still)
        deleted_ok = gone.get("ok") and gone.get("removed", 0) >= 1 and not still
        fresh_ok = before == 0 and pull2.get("ok") and pull2.get("added", 0) >= 1 and [w["name"] for w in fs["waypoints"]] == ["Viewpoint east"] and drawn == 1 and froute and line > 0
        out["ok"] = bool(len(s["waypoints"]) == 2 and moved_m > 80 and server_ok and pulled_ok and fresh_ok and deleted_ok and (last or {}).get("ok"))
    except Exception as e:  # report, do not crash the tour
        out["error"] = str(e)[:500]
        try:
            page.screenshot(path=shot)
        except Exception:
            pass
    return out


def not_idle_because(state, v):
    """Why a view did not count as idle: the MapLibre flags that were false, or the zoom still moving."""
    if not state:
        return ["noMap"]
    why = [k for k in ("styleLoaded", "loaded", "tilesLoaded") if not state.get(k)]
    if v["z"] is not None and abs(state["zoom"] - v["z"]) >= 0.05:
        why.append("zoom %s" % state["zoom"])
    return why or ["flapping"]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", default="http://localhost:8765/tools/demo/")
    ap.add_argument("--view", default="", help="zoom/lat/lon appended as the URL hash, e.g. 13/46.80/-121.74")
    ap.add_argument("--views", nargs="*", default=[], help="z/lat/lon[:label] views, comma- or space-separated; runs a tour in one session")
    ap.add_argument("--views-file", default="", help="file with one z/lat/lon[:label] per line")
    ap.add_argument("--out", default="out/demo-check.png", help="screenshot path; a tour writes <stem>-<i>.png")
    ap.add_argument("--width", type=int, default=1280)
    ap.add_argument("--height", type=int, default=900)
    ap.add_argument("--timeout", type=float, default=60.0, help="seconds for a view to go idle, per attempt")
    ap.add_argument("--retries", type=int, default=1,
                    help="when a view is not idle after --timeout, jump to it again and wait again, this many times (default 1); "
                         "each miss is listed under notIdle, and a view that never goes idle still fails")
    ap.add_argument("--channel", default="chrome", help="playwright browser channel; use '' for bundled chromium")
    ap.add_argument("--route-mode", default="", choices=["", "hike", "bike", "drive"], help="T73: the trip editor's mode for --route (default: the page's, hike)")
    ap.add_argument("--long-route", default="", help="T77, live mode: after the views (and --route), plan lat,lon;lat,lon in the trip editor "
                    "(--route-mode, default drive) and check it follows the network across at least --long-route-packs packs in under --long-route-ms (check_long_route)")
    ap.add_argument("--long-route-ms", type=int, default=10000, help="T77: the server's time limit for --long-route, ms (default 10000)")
    ap.add_argument("--long-route-packs", type=int, default=2, help="T77: how many packs --long-route must cross (default 2)")
    ap.add_argument("--trip-sync", action="store_true", help="T92, live mode, with --route: waypoint editing and the pull side of the browser's trip replica (check_trip_sync)")
    ap.add_argument("--token", default="", help="T92: the server's bearer token, typed into the page's Token field (a server with users keeps /route* and /sync* behind it)")
    ap.add_argument("--route", default="", help="T68, live mode: after the views, plan lat,lon;lat,lon[;drag-to lat,lon] in the trip editor, drag a via point, export GPX, save as trip (check_route)")
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
            elif r.status == 404 and TILE_URL.search(r.url):
                cur["tile404"].append({"url": r.url, "status": r.status})
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
            report = {"url": url, "console": [], "pageErrors": [], "failedRequests": [], "idle": False, "map": None, "missingTiles": 0, "tile404": []}
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
            # Idle means two polls in a row (0.5 s apart) with the style and every tile loaded at the view's
            # zoom, so the gap between one batch of tile requests and the next does not count. Under load a
            # view can miss the deadline (a stalled request, a slow live tile): then jump to it again, which
            # asks again for whatever is missing, and wait once more (--retries; T66).
            state = None
            for attempt in range(args.retries + 1):
                deadline = time.time() + args.timeout
                streak = 0
                while time.time() < deadline:
                    state = page.evaluate(STATE_JS)
                    if state and state["loaded"] and state["tilesLoaded"] and state["styleLoaded"] and \
                            (v["z"] is None or abs(state["zoom"] - v["z"]) < 0.05):
                        streak += 1
                        if streak >= 2:
                            report["idle"] = True
                            report["idleSeconds"] = round(time.time() - t0, 1)  # to idle, before the fixed settle wait below
                            break
                    else:
                        streak = 0
                    time.sleep(0.5)
                if report["idle"]:
                    break
                report.setdefault("notIdle", []).append(not_idle_because(state, v))
                if attempt < args.retries and v["z"] is not None:
                    report["retries"] = attempt + 1
                    page.evaluate("v => { map.jumpTo({center: [v.lon, v.lat], zoom: v.z}); map.triggerRepaint(); }",
                                  {"lon": v["lon"], "lat": v["lat"], "z": v["z"]})
                    page.wait_for_timeout(500)
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

            live = report["idle"] and page.evaluate("() => typeof window.dystopoLiveSources === 'function'")
            if live:
                report["live"] = True
                report["sources"] = page.evaluate("() => window.dystopoLiveSources()")
                osm_ids = page.evaluate("() => map.getStyle().layers.filter(l => l.source === 'osm').map(l => l.id)")
                report["osmFeatures"] = sum(report["layers"].get(l, 0) for l in osm_ids)
            # Tile routes answering 404: outside the pack in pack mode; in live mode every tile must exist.
            if live:
                report["failedRequests"] += report["tile404"]
            else:
                report["missingTiles"] += len(report["tile404"])
            del report["tile404"]
            if report["idle"]:
                report["units"] = page.evaluate("() => window.dystopoUnits || null")
            ok = report["idle"] and not report["pageErrors"] and not report["failedRequests"] and not any(c["type"] == "error" for c in report["console"])
            if tour and v["z"] is not None:
                inside = live or covered(v["z"], v["lat"], v["lon"])
                report["inCoverage"] = inside
                report["emptyLayers"] = []
                if inside and report["idle"]:
                    report["emptyLayers"] = [l for l in REQUIRED_LAYERS if report["layers"].get(l, 0) == 0]
                    ok = ok and not report["emptyLayers"]
                if live:
                    dem = {k: n for k, n in report["sources"]["dem"].items() if k not in ("pending", "none")}
                    report["demSource"] = max(dem, key=dem.get) if dem else None
                    if not dem:
                        report["emptyLayers"].append("hillshade")
                    if report["osmFeatures"] == 0:
                        report["emptyLayers"].append("osm")
                    if v.get("src") and report["demSource"] != v["src"]:
                        report["wrongSource"] = {"expected": v["src"], "got": report["demSource"]}
                    ok = ok and not report["emptyLayers"] and "wrongSource" not in report
                if v.get("req") and report["idle"]:
                    report["missingRequired"] = missing_roles(v["req"], report["layers"])
                    ok = ok and not report["missingRequired"]
            report["ok"] = ok
            reports.append(dict(report))
        if args.token:
            page.fill("#packtoken", args.token)
            page.dispatch_event("#packtoken", "change")
        if args.route:
            route = check_route(page, args.route, f"{stem}-route{ext}", args.timeout, args.route_mode)
            errors = cur["pageErrors"] + cur["failedRequests"] + [c for c in cur["console"] if c["type"] == "error"]
            reports[-1].update(route=route, ok=reports[-1]["ok"] and route["ok"] and not errors)
        if args.trip_sync:
            ts = check_trip_sync(browser, page, args.url, f"{stem}-trip-sync{ext}", args.timeout, args.token, (args.width, args.height))
            errors = cur["pageErrors"] + cur["failedRequests"] + [c for c in cur["console"] if c["type"] == "error"]
            reports[-1].update(tripSync=ts, ok=reports[-1]["ok"] and ts["ok"] and not errors)
        if args.long_route:
            lr = check_long_route(page, args.long_route, f"{stem}-long-route{ext}", args.timeout, args.route_mode or "drive",
                                  args.long_route_ms, args.long_route_packs)
            errors = cur["pageErrors"] + cur["failedRequests"] + [c for c in cur["console"] if c["type"] == "error"]
            reports[-1].update(longRoute=lr, ok=reports[-1]["ok"] and lr["ok"] and not errors)
        browser.close()

    if tour:
        print(f"{'#':>2} {'view':<26} {'label':<14} {'ok':<4} {'cov':<4} {'miss':>5} {'err':>3} {'cont':>5} {'idx':>4} {'clbl':>4} {'trail':>5} {'poi':>4} {'cliff':>5} {'water':>5} {'peak':>4} {'place':>5} {'s':>5}", file=sys.stderr)
        for r in reports:
            L = r.get("layers", {})
            print(f"{r['index']:>2} {r['url'].split('#')[-1]:<26} {r['label']:<14} {'ok' if r['ok'] else 'FAIL':<4} "
                  f"{'yes' if r.get('inCoverage') else 'no':<4} {r['missingTiles']:>5} "
                  f"{len(r['console']) + len(r['pageErrors']) + len(r['failedRequests']):>3} "
                  f"{L.get('contour-minor', 0):>5} {L.get('contour-index', 0):>4} {L.get('contour-labels', 0):>4} {role_count('trails', L):>5} {role_count('poi-markers', L):>4} {role_count('cliffs', L):>5} "
                  f"{L.get('water', 0) + L.get('waterway', 0):>5} {L.get('peaks', 0):>4} {L.get('places', 0):>5} {r['seconds']:>5}"
                  + (f"  dem={r.get('demSource')} osm={r.get('osmFeatures')}" if r.get("live") else ""), file=sys.stderr)
        print(json.dumps(reports, indent=2))
        return 0 if all(r["ok"] for r in reports) else 1
    print(json.dumps(reports[0], indent=2))
    return 0 if reports[0]["ok"] else 1


if __name__ == "__main__":
    sys.exit(main())
