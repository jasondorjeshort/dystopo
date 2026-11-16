"""The route sheet (T289): the same public routes planned every sweep, drawn on their map, and compared with the previous
sweep's by name.

    python -I tools/route-diff.py run --list tools/route-sheet.txt --out <sweep>/route-sheet
        [--devtool devtool/build/install/devtool/bin/devtool.bat] [--packs out] [--timeout 300]
    python -I tools/route-diff.py diff <previous sweep folder> <this sweep folder> [--channel chrome|""] [--no-sheet]

`run` (the sweep part `route-sheet` of tools/sweep.ps1) plans each line of the list with devtool `route`, writing
<name>.png (`--out`, the route on its map with the profile under it), <name>.gpx (`--gpx`) and <name>.json (the
command's JSON) into the folder. A line whose pack (`<packs>/<pack>-outdoors.pmtiles`) is missing is skipped by name;
one whose DEM pack (`<pack>-dem.pmtiles`) is missing is planned without a picture (`noPicture`; devtool draws none
without a DEM). A route that is not found (devtool's exit not 0, or no route in its JSON) is listed in `failed` and the
exit is 1; the other lines still run. Prints one JSON object (the part's report): `routes`, `planned`, `pictures`,
`skipped`, `noPicture`, `failed`, per line its figures and line hash, `seconds`.

`diff` pairs <this>/route-sheet/<name>.json with the previous sweep's namesake. A route is changed when its distance
moved by more than 0.1 m, its time by more than 1 s, its `fuelIndex` by more than one part in a million, its `turns` at
all, or the hash of its line (TOLERANCES): the latitudes and longitudes of the GPX's first route (else its first
track) rounded to six decimals, never an id, a description or a height (the route's JSON carries only a count of
points; a graph rebuilt names other edge ids and can move a sum in its last digit). A field absent on both sides is
equal and any other field is ignored, so fields devtool gains later (`--breakdown`, `--profile`) change nothing.
Writes into <this>:
- route-changes.png (and route-changes-2.png ... when one page would pass Chrome's 16,384 px capture): the changed
  routes first, each as the old and the new picture side by side, the route redrawn on each (purple) with the other
  sweep's line dashed (orange) over it, the figures under each and the differences under the new one; then the new
  routes, one picture each; then the names of the removed, skipped and failed routes;
- route-changes.json: `count` (changed), `changed` (name, `why`, figures before and after, `differences`), `new`,
  `removed`, `unchanged`, `skipped` and `failed` (this sweep's), the tolerances, the pages.
A changed route only shows: the exit is 0 whatever changed, 2 on an error (route-changes.json then holds `error`).
tools/sweep-summary.py folds the counts into summary.json (`routeSheet`). The image work runs in Playwright's
Chromium, as the sweep's other sheets do.
"""
import argparse
import hashlib
import html
import json
import math
import os
import re
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
SUB = "route-sheet"
# Moved by more than: metres, seconds, a share of the fuel index; `turns` and the line hash at all.
TOLERANCES = {"distanceM": 0.1, "timeS": 1.0, "fuelIndexRel": 1e-6}
FIGURES = ("distanceM", "timeS", "turns", "fuelIndex", "ascentM", "descentM", "turnCostS", "points")
PREFERS = ("time", "fuel", "quick")
MODES = ("hike", "bike", "drive")
PROFILE_PX = 150  # devtool route's profile strip under the map (RouteCommand.render)
SHEET_MAX_PX = 15500  # under Chrome's 16,384 px full-page capture, with room for rounding


# --- the list -------------------------------------------------------------------------------------------------------
def parse_list(text):
    """The list's lines as dicts (name, pack, mode, from, to, prefer); raises ValueError naming a bad line."""
    out, seen = [], set()
    for no, raw in enumerate(text.splitlines(), 1):
        line = raw.split("#", 1)[0].strip()
        if not line:
            continue
        cols = line.split()
        if len(cols) not in (5, 6):
            raise ValueError(f"line {no}: expected <name> <pack> <mode> <from> <to> [<prefer>], got {len(cols)} columns")
        name, pack, mode, a, b = cols[:5]
        prefer = cols[5] if len(cols) == 6 else None
        if not re.fullmatch(r"[a-z0-9][a-z0-9-]*", name) or not re.fullmatch(r"[a-z0-9-]+", pack):
            raise ValueError(f"line {no}: name and pack are lower-case words with dashes")
        if name in seen:
            raise ValueError(f"line {no}: the name {name} is used twice")
        if mode not in MODES:
            raise ValueError(f"line {no}: mode {mode} is not one of {', '.join(MODES)}")
        if prefer is not None and (mode != "drive" or prefer not in PREFERS):
            raise ValueError(f"line {no}: a prefer value ({', '.join(PREFERS)}) is for a drive only")
        for p in (a, b):
            if not re.fullmatch(r"-?\d+(\.\d+)?,-?\d+(\.\d+)?", p):
                raise ValueError(f"line {no}: {p} is not lat,lon")
        seen.add(name)
        out.append({"name": name, "pack": pack, "mode": mode, "from": a, "to": b, "prefer": prefer})
    return out


# --- one route's files ------------------------------------------------------------------------------------------------
def gpx_points(text):
    """(lat, lon) of the GPX's first route, else its first track (attribute order either way)."""
    for block, tag in ((r"<rte\b.*?</rte>", "rtept"), (r"<trk\b.*?</trk>", "trkpt")):
        m = re.search(block, text, re.S)
        if not m:
            continue
        pts = []
        for attrs in re.findall(r"<%s\b([^>]*)>" % tag, m.group(0)):
            la = re.search(r'\blat="([^"]+)"', attrs)
            lo = re.search(r'\blon="([^"]+)"', attrs)
            if la and lo:
                pts.append((float(la.group(1)), float(lo.group(1))))
        if pts:
            return pts
    return []


def line_hash(points):
    """The line's hash: its points rounded to six decimals, nothing else (no id, description or height)."""
    body = "".join(f"{round(la, 6):.6f},{round(lo, 6):.6f}\n" for la, lo in points)
    return hashlib.sha256(body.encode("ascii")).hexdigest()[:16]


def load_json(path):
    try:
        with open(path, encoding="utf-8-sig") as f:
            return json.load(f)
    except (OSError, ValueError):
        return None


def read_route(folder, name):
    """One planned route of a sweep's route-sheet folder: figures, line, picture; None when it has no route JSON."""
    r = load_json(os.path.join(folder, name + ".json"))
    if not isinstance(r, dict) or not isinstance(r.get("route"), dict):
        return None
    rt = r["route"]
    figs = {k: rt[k] for k in FIGURES if k in rt and rt[k] is not None}
    try:
        with open(os.path.join(folder, name + ".gpx"), encoding="utf-8") as f:
            pts = gpx_points(f.read())
    except OSError:
        pts = []
    render = r.get("render") if isinstance(r.get("render"), dict) else {}
    png = os.path.join(folder, name + ".png")
    pic = None
    if render.get("bbox") and render.get("zoom") is not None and os.path.isfile(png):
        pic = {"path": png, "zoom": render["zoom"], "bbox": render["bbox"], "width": render.get("widthPx"), "height": render.get("heightPx")}
    return {"name": name, "figures": figs, "lineHash": line_hash(pts) if pts else None, "linePoints": pts,
            "prefer": (r.get("search") or {}).get("prefer"), "mode": rt.get("mode"), "picture": pic}


def route_names(folder):
    if not os.path.isdir(folder):
        return []
    return sorted(f[:-5] for f in os.listdir(folder) if f.endswith(".json") and f != SUB + ".json"
                  and read_route(folder, f[:-5]) is not None)


# --- comparing --------------------------------------------------------------------------------------------------------
def compare(before, after):
    """The fields that moved past their tolerance, in a fixed order; [] when the route is unchanged."""
    a, b = before["figures"], after["figures"]
    why = []

    def moved(key, test):
        if key not in a and key not in b:
            return
        if (key in a) != (key in b) or test(a[key], b[key]):
            why.append(key)

    moved("distanceM", lambda x, y: abs(y - x) > TOLERANCES["distanceM"])
    moved("timeS", lambda x, y: abs(y - x) > TOLERANCES["timeS"])
    moved("turns", lambda x, y: x != y)
    moved("fuelIndex", lambda x, y: abs(y - x) > TOLERANCES["fuelIndexRel"] * max(abs(x), abs(y)))
    if (before.get("lineHash") or after.get("lineHash")) and before.get("lineHash") != after.get("lineHash"):
        why.append("line")
    return why


def differences(before, after):
    a, b = before["figures"], after["figures"]
    out = {}
    for k in ("distanceM", "timeS", "turns", "fuelIndex", "ascentM", "turnCostS"):
        if k in a and k in b:
            out[k] = round(b[k] - a[k], 3)
    if a.get("fuelIndex") and "fuelIndex" in b:
        out["fuelIndexPercent"] = round((b["fuelIndex"] - a["fuelIndex"]) / a["fuelIndex"] * 100, 3)
    return out


def public(r):
    return {k: r[k] for k in ("figures", "lineHash", "prefer", "mode")} | {"png": r["picture"]["path"] if r.get("picture") else None}


def diff_folders(prev, this):
    """The comparison of two sweep folders (no sheet): the dict route-changes.json holds, with the routes kept
    under `_routes` for the sheet."""
    pa, pb = os.path.join(prev, SUB), os.path.join(this, SUB)
    report = load_json(os.path.join(pb, SUB + ".json"))
    report = report if isinstance(report, dict) else {}
    skipped = [s.get("name") for s in report.get("skipped") or []]
    failed = [s.get("name") for s in report.get("failed") or []]
    order = [l.get("name") for l in report.get("lines") or []]
    names_a, names_b = route_names(pa), route_names(pb)
    rank = {n: i for i, n in enumerate(order)}
    names_b.sort(key=lambda n: (rank.get(n, len(rank)), n))
    routes_a = {n: read_route(pa, n) for n in names_a}
    routes_b = {n: read_route(pb, n) for n in names_b}
    changed, new, unchanged = [], [], []
    for n in names_b:
        if n not in routes_a:
            new.append(n)
            continue
        why = compare(routes_a[n], routes_b[n])
        if why:
            changed.append({"name": n, "why": why, "before": public(routes_a[n]), "after": public(routes_b[n]),
                            "differences": differences(routes_a[n], routes_b[n])})
        else:
            unchanged.append(n)
    removed = [n for n in names_a if n not in routes_b and n not in skipped and n not in failed]
    return {"prev": prev, "this": this, "tolerances": TOLERANCES, "routes": len(names_b), "count": len(changed),
            "changed": changed, "new": new, "removed": removed, "unchanged": unchanged, "skipped": skipped,
            "failed": failed, "_routes": (routes_a, routes_b)}


# --- the sheet --------------------------------------------------------------------------------------------------------
def lon_x(lon, z):
    return (lon + 180.0) / 360.0 * 256 * 2 ** z


def lat_y(lat, z):
    s = math.sin(math.radians(max(-85.05112878, min(85.05112878, lat))))
    return (0.5 - math.log((1 + s) / (1 - s)) / (4 * math.pi)) * 256 * 2 ** z


def project(pic, points):
    """The points in the picture's pixels (devtool's MercatorWindow.forBbox: the bbox's pixel range at its zoom,
    floored at the top left), and the map's height (the profile strip is under it)."""
    z = pic["zoom"]
    w, s, e, n = pic["bbox"]
    x0, y0 = math.floor(lon_x(w, z)), math.floor(lat_y(n, z))
    map_h = math.ceil(lat_y(s, z)) - y0
    return [(lon_x(lo, z) - x0, lat_y(la, z) - y0) for la, lo in points], map_h


def poly(pts):
    return " ".join(f"{x:.1f},{y:.1f}" for x, y in pts)


def figure_html(route, other, label, uid):
    """One picture with its own line redrawn (purple) and the other sweep's line dashed (orange) over the map."""
    pic = route.get("picture")
    if not pic:
        return f"<div class='nopic'>{html.escape(label)}: no picture (no DEM pack)</div>"
    own, map_h = project(pic, route["linePoints"])
    width = pic["width"] or 1
    total = pic["height"] or map_h + PROFILE_PX
    svg = [f"<svg viewBox='0 0 {width} {total}' preserveAspectRatio='none'><defs><clipPath id='c{uid}'>"
           f"<rect width='{width}' height='{map_h}'/></clipPath></defs><g clip-path='url(#c{uid})'>"]
    if own:
        svg.append(f"<polyline class='own' points='{poly(own)}'/>")
    if other and other.get("linePoints"):
        theirs, _ = project(pic, other["linePoints"])
        svg.append(f"<polyline class='other' points='{poly(theirs)}'/>")
    svg.append("</g></svg>")
    url = "file:///" + os.path.abspath(pic["path"]).replace(os.sep, "/")
    return f"<div class='pic'><img src='{html.escape(url)}'>{''.join(svg)}</div>"


def fmt_time(s):
    s = int(round(s))
    return f"{s} s ({s // 3600}:{s % 3600 // 60:02d}:{s % 60:02d})"


def figures_html(figs, diff=None, why=()):
    rows = [("distanceM", "distance", lambda v: f"{v:,.1f} m", lambda d: f"{d:+,.1f} m"),
            ("timeS", "time", fmt_time, lambda d: f"{d:+,.0f} s"),
            ("turns", "turns paid", lambda v: f"{v}", lambda d: f"{d:+.0f}"),
            ("turnCostS", "turn seconds", lambda v: f"{v:,.1f} s", lambda d: f"{d:+,.1f} s"),
            ("fuelIndex", "fuel index", lambda v: f"{v / 1e6:,.3f} MJ", None),
            ("ascentM", "ascent", lambda v: f"{v:,.1f} m", lambda d: f"{d:+,.1f} m"),
            ("points", "points", lambda v: f"{v}", None)]
    out = []
    for key, label, show, delta in rows:
        if key not in figs:
            continue
        d = ""
        if diff is not None:
            if key == "fuelIndex" and "fuelIndexPercent" in diff:
                d = f"{diff['fuelIndexPercent']:+.2f} %"
            elif delta and key in diff:
                d = delta(diff[key])
        hot = " class='hot'" if key in why or (key == "points" and "line" in why) else ""
        out.append(f"<tr{hot}><td>{label}</td><td>{show(figs[key])}</td>" + (f"<td>{d}</td>" if diff is not None else "") + "</tr>")
    return f"<table>{''.join(out)}</table>"


WHY_WORDS = {"distanceM": "distance", "timeS": "time", "turns": "turns", "fuelIndex": "fuel", "line": "line"}


def blocks_html(res, prev_name, this_name):
    """The sheet as a list of blocks (a heading, one card per changed route, rows of new routes, the lists)."""
    routes_a, routes_b = res["_routes"]
    blocks = [f"<h1>Routes: {res['count']} changed, {len(res['new'])} new, {len(res['removed'])} removed, "
              f"{len(res['unchanged'])} unchanged, {len(res['skipped'])} skipped"
              f"{', ' + str(len(res['failed'])) + ' failed' if res['failed'] else ''}"
              f" <span class='sub'>{html.escape(prev_name)} to {html.escape(this_name)}</span></h1>"
              "<p class='key'>Each map: <b class='p'>purple</b> its own route, <b class='o'>orange dashes</b> the other sweep's line"
              " where it differs. Changed when the distance moves by more than 0.1 m, the time by more than 1 s, the fuel"
              " index by more than one part in a million, the turns at all, or the line.</p>"]
    for i, c in enumerate(res["changed"]):
        a, b = routes_a[c["name"]], routes_b[c["name"]]
        why = ", ".join(WHY_WORDS.get(w, w) for w in c["why"])
        blocks.append(
            f"<div class='card'><div class='cap'><b>{html.escape(c['name'])}</b>: {html.escape(why)} changed</div><div class='row2'>"
            f"<div><div class='lab'>before ({html.escape(prev_name)})</div>{figure_html(a, b, 'before', f'a{i}')}{figures_html(a['figures'])}</div>"
            f"<div><div class='lab'>after ({html.escape(this_name)}), differences on the right</div>{figure_html(b, a, 'after', f'b{i}')}"
            f"{figures_html(b['figures'], c['differences'], c['why'])}</div></div></div>")
    if res["new"]:
        blocks.append("<h2>New (no namesake in the previous sweep)</h2>")
        for k in range(0, len(res["new"]), 3):
            cells = []
            for j, n in enumerate(res["new"][k:k + 3]):
                r = routes_b[n]
                cells.append(f"<div class='card'><div class='cap'><b>{html.escape(n)}</b>: new</div>{figure_html(r, None, n, f'n{k + j}')}"
                             f"{figures_html(r['figures'])}</div>")
            blocks.append(f"<div class='row3'>{''.join(cells)}</div>")
    for key, title in (("removed", "Removed (in the previous sweep only)"), ("skipped", "Skipped this sweep (pack not in out/)"),
                       ("failed", "Failed this sweep (no route)")):
        if res[key]:
            blocks.append(f"<h2>{title}</h2><p>{html.escape(', '.join(res[key]))}</p>")
    return blocks


STYLE = ("body{font:14px system-ui,sans-serif;margin:16px;background:#fff;color:#222;width:1568px}"
         "h1{font-size:20px}h2{font-size:16px;margin:18px 0 6px}.sub{font-weight:normal;color:#666;font-size:14px}"
         ".key{color:#444}.p{color:#7a2fd0}.o{color:#d55e00}"
         ".card{border:1px solid #ccc;padding:8px;margin:0 0 14px;break-inside:avoid}.cap{font-size:15px;margin-bottom:6px}"
         ".row2{display:grid;grid-template-columns:1fr 1fr;gap:12px}.row3{display:grid;grid-template-columns:1fr 1fr 1fr;gap:12px}"
         ".row3 .card{margin:0 0 14px}.lab{font-size:12px;color:#555;margin-bottom:2px}"
         ".pic{position:relative;line-height:0}.pic img{width:100%;display:block;border:1px solid #bbb}"
         ".pic svg{position:absolute;left:0;top:0;width:100%;height:100%}"
         "polyline{fill:none;stroke-linejoin:round;stroke-linecap:round;vector-effect:non-scaling-stroke}"
         ".own{stroke:#7a2fd0;stroke-width:3.5;opacity:.9}.other{stroke:#d55e00;stroke-width:3.5;stroke-dasharray:9 7}"
         "table{border-collapse:collapse;margin-top:6px;font-variant-numeric:tabular-nums}td{padding:1px 14px 1px 0}"
         "tr.hot td{color:#b3261e;font-weight:600}.nopic{padding:30px;background:#eee;color:#555}")


def page_html(blocks):
    return f"<!doctype html><meta charset='utf-8'><title>Route changes</title><style>{STYLE}</style><body>{''.join(blocks)}</body>"


def render_sheet(res, this, channel):
    """route-changes.png (and -2, -3 ... past the capture limit), by measured block heights; returns the pages."""
    from playwright.sync_api import sync_playwright

    blocks = blocks_html(res, os.path.basename(os.path.normpath(res["prev"])), os.path.basename(os.path.normpath(this)))
    for f in os.listdir(this):  # pages of an earlier run would mislead
        if re.fullmatch(r"route-changes(-\d+)?\.(png|html)", f):
            os.remove(os.path.join(this, f))
    pages = []
    with sync_playwright() as p:
        launch = {"headless": True}
        if channel:
            launch["channel"] = channel
        b = p.chromium.launch(**launch)
        try:
            page = b.new_page(viewport={"width": 1600, "height": 900})

            def show(blks, name):
                path = os.path.join(this, name + ".html")
                with open(path, "w", encoding="utf-8") as f:
                    f.write(page_html(blks))
                page.goto("file:///" + os.path.abspath(path).replace(os.sep, "/"), wait_until="load")
                return path

            show(blocks, "route-changes")
            heights = page.evaluate("() => Array.from(document.body.children).filter(e => e.tagName !== 'STYLE')"
                                    ".map(e => { const r = e.getBoundingClientRect(), s = getComputedStyle(e);"
                                    " return r.height + parseFloat(s.marginTop) + parseFloat(s.marginBottom); })")
            groups, cur, h = [], [], 32.0
            for blk, bh in zip(blocks, heights):
                if cur and h + bh > SHEET_MAX_PX:
                    groups.append(cur)
                    cur, h = [], 32.0
                cur.append(blk)
                h += bh
            if cur:
                groups.append(cur)
            for i, g in enumerate(groups):
                name = "route-changes" if i == 0 else f"route-changes-{i + 1}"
                show(g if i == 0 else [blocks[0]] + g, name)
                png = os.path.join(this, name + ".png")
                page.screenshot(path=png, full_page=True)
                pages.append({"png": png, "height": page.evaluate("() => document.documentElement.scrollHeight")})
        finally:
            b.close()
    return pages


def diff_main(prev, this, channel, sheet=True):
    res = diff_folders(prev, this)
    res["pages"] = render_sheet(res, this, channel) if sheet and (res["changed"] or res["new"]) else []
    if not res["pages"]:
        for f in os.listdir(this):
            if re.fullmatch(r"route-changes(-\d+)?\.(png|html)", f):
                os.remove(os.path.join(this, f))
    out = {k: v for k, v in res.items() if not k.startswith("_")}
    with open(os.path.join(this, "route-changes.json"), "w", encoding="utf-8") as f:
        json.dump(out, f, indent=2)
    return out


# --- run --------------------------------------------------------------------------------------------------------------
def default_devtool():
    name = "devtool.bat" if os.name == "nt" else "devtool"
    return os.path.join(REPO, "devtool", "build", "install", "devtool", "bin", name)


def run_list(lines, devtool, packs, out, timeout):
    """Plans every line with devtool `route`; the part's report."""
    os.makedirs(out, exist_ok=True)
    t0 = time.time()
    rep = {"list": None, "routes": len(lines), "planned": 0, "pictures": 0, "skipped": [], "noPicture": [], "failed": [], "lines": []}
    for ln in lines:
        name = ln["name"]
        pack = os.path.join(packs, f"{ln['pack']}-outdoors.pmtiles")
        dem = os.path.join(packs, f"{ln['pack']}-dem.pmtiles")
        if not os.path.isfile(pack):
            rep["skipped"].append({"name": name, "why": f"no out/{ln['pack']}-outdoors.pmtiles"})
            continue
        for ext in (".png", ".gpx", ".json"):  # a file left by an earlier run would pass for this one's
            if os.path.exists(os.path.join(out, name + ext)):
                os.remove(os.path.join(out, name + ext))
        argv = [devtool, "route", "--pack", pack, "--from", ln["from"], "--to", ln["to"], "--mode", ln["mode"],
                "--gpx", os.path.join(out, name + ".gpx")]
        if ln["prefer"]:
            argv += ["--prefer", ln["prefer"]]
        has_dem = os.path.isfile(dem)
        if has_dem:
            argv += ["--dem", dem, "--out", os.path.join(out, name + ".png")]
        s0 = time.time()
        entry = {"name": name, "pack": ln["pack"], "mode": ln["mode"], "prefer": ln["prefer"], "ok": False}
        try:
            with open(os.path.join(out, name + ".json"), "w", encoding="utf-8") as so, \
                    open(os.path.join(out, name + ".err.log"), "w", encoding="utf-8") as se:
                code = subprocess.run(argv, stdout=so, stderr=se, cwd=REPO, timeout=timeout).returncode
        except subprocess.TimeoutExpired:
            code = "timeout"
        except OSError as e:
            code = f"could not start: {e}"
        entry["seconds"] = round(time.time() - s0, 1)
        r = read_route(out, name)
        if code != 0 or r is None:
            j = load_json(os.path.join(out, name + ".json")) or {}
            why = j.get("failure") or j.get("error") if isinstance(j, dict) else None
            rep["failed"].append({"name": name, "why": f"exit {code}" + (f": {json.dumps(why)[:200]}" if why else "")})
            entry["exit"] = code
            rep["lines"].append(entry)
            continue
        rep["planned"] += 1
        entry.update(ok=True, figures=r["figures"], lineHash=r["lineHash"], picture=bool(r["picture"]))
        if r["picture"]:
            rep["pictures"] += 1
        else:
            rep["noPicture"].append({"name": name, "why": "no DEM pack" if not has_dem else "devtool drew none"})
        rep["lines"].append(entry)
        print(f"[route-sheet] {name}: {r['figures'].get('distanceM')} m, {r['figures'].get('timeS')} s, {entry['seconds']} s",
              file=sys.stderr, flush=True)
    rep["seconds"] = round(time.time() - t0, 1)
    with open(os.path.join(out, SUB + ".json"), "w", encoding="utf-8") as f:
        json.dump(rep, f, indent=2)
    return rep


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    sub = ap.add_subparsers(dest="cmd", required=True)
    r = sub.add_parser("run", help="plan every line of the list with devtool route")
    r.add_argument("--list", default=os.path.join(HERE, "route-sheet.txt"))
    r.add_argument("--out", required=True, help="the sweep's route-sheet folder")
    r.add_argument("--devtool", default=default_devtool())
    r.add_argument("--packs", default=os.path.join(REPO, "out"), help="the folder holding <pack>-outdoors.pmtiles and <pack>-dem.pmtiles")
    r.add_argument("--timeout", type=int, default=300, help="seconds per route")
    d = sub.add_parser("diff", help="compare this sweep's routes with the previous sweep's")
    d.add_argument("prev")
    d.add_argument("this")
    d.add_argument("--channel", default="chrome", help="playwright browser channel; '' for bundled chromium")
    d.add_argument("--no-sheet", action="store_true", help="route-changes.json only")
    a = ap.parse_args()
    if a.cmd == "run":
        with open(a.list, encoding="utf-8") as f:
            lines = parse_list(f.read())
        out = os.path.abspath(a.out)
        rep = run_list(lines, os.path.abspath(a.devtool), os.path.abspath(a.packs), out, a.timeout)
        rep["list"] = os.path.relpath(os.path.abspath(a.list), REPO).replace(os.sep, "/")
        with open(os.path.join(out, SUB + ".json"), "w", encoding="utf-8") as f:
            json.dump(rep, f, indent=2)
        print(json.dumps(rep, indent=2))
        return 1 if rep["failed"] else 0
    prev, this = os.path.abspath(a.prev), os.path.abspath(a.this)
    try:
        for x in (prev, this):
            if not os.path.isdir(x):
                raise ValueError(f"not a folder: {x}")
        out = diff_main(prev, this, a.channel, not a.no_sheet)
    except Exception as e:
        if os.path.isdir(this):
            with open(os.path.join(this, "route-changes.json"), "w", encoding="utf-8") as f:
                json.dump({"prev": prev, "this": this, "error": str(e)[:500]}, f, indent=2)
        print(json.dumps({"error": str(e)[:500]}))
        return 2
    print(json.dumps({k: out[k] for k in ("routes", "count", "new", "removed", "skipped", "failed")} |
                     {"changed": [c["name"] for c in out["changed"]], "pages": [p["png"] for p in out["pages"]]}, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
