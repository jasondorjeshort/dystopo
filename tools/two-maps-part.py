"""T339: the sweep's part `two-maps`: what the phone says about its maps at four places, the network off and on.

On the two-maps fixture (tools/two-maps-fixture.py: a wide coarse map of Rainier and a fine one inside it) this reads, at
each of the fixture's four places (inside both, inside the wide one only, outside both, near the fine one's edge) and with
the network off and then on, what the phone answers:
- the map at the place (`POST /camera?lat=&lon=&zoom=`, then the settle): `GET /firstrun` (the no-map rule's inputs and its
  message), `GET /online` (the status chip and the Download button's rule, `offerDownload`), `GET /areas/rows` (the Offline
  maps list), `GET /packs` and `GET /debug/startup` (the maps open, the primary, the start trace's count of maps);
- "Sky from here" at the place (`POST /horizon/ui?lat=&lon=`): `GET /horizon` at once and with `?wait=`;
- the Panorama from there (`POST /horizon/switch?to=panorama`): `GET /panorama` at once and with `?wait=`;
and writes one JSON table of eight rows (`--out`, default out/two-maps/table.json) with every answer in full beside it
(`<out folder>/raw/<row>-<read>.json`). Each row's `summary` is the few fields the family's reports turn on.

It fails only on a row it could not read (a route that did not answer, or answered an error): the rows are the reports'
evidence, not a verdict. Each task that fixes a row (T340 to T343) adds its own assertion in a block at the end of this file.

State it pins, so a row does not depend on what ran before it:
- the maps: the fixture's install moves every other map aside (and the end puts them back), so only the pair is open;
- the online tile cache is emptied first (`POST /online/cache/clear`): an earlier part's cached tiles would answer offline;
- each network phase starts with a cold start of the app, so a horizon or a Panorama place kept from the phase before
  (the views keep them, `horizonsKept`, `kept`) is not this phase's; the network-off phase runs first, on the empty cache;
- the clock: the table leaves out the Sun, Satellites and Flights layers, the parts of the answers that move with it; what
  remains (terrain, peaks, the maps, the chip) does not depend on the time of day;
- not pinned: the synced settings (the view range, the layers, the server) are read and written into the table's `settings`.
At the end the fixture is removed, the other maps put back, the network on and the app restarted (`--keep` leaves the
fixture installed and the network as the last phase left it, on).

    python -I tools/two-maps-part.py --serial emulator-5572 --port 8696 [--out out/two-maps/table.json] [--from <dir>]

One JSON object on stdout (the table without the raw answers); exit 0 when every row was read, 1 otherwise.
"""
import argparse
import importlib.util
import json
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location("two_maps_fixture", os.path.join(HERE, "two-maps-fixture.py"))
fx = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(fx)

ZOOM = 12  # the map's zoom at each place: inside both maps' vector zooms (z8 to z13), where the chip and the button speak
PHASES = ["off", "on"]  # the order the phases run in (the module's docstring says why off is first)


def get(d, *path):
    """d[path[0]][path[1]]..., None where any step is missing."""
    for k in path:
        if not isinstance(d, dict):
            return None
        d = d.get(k)
    return d


def summarize(reads):
    """The fields the reports turn on, from one row's answers (`reads`: read name -> parsed JSON or None)."""
    fr, on, rows, maps = reads.get("firstrun"), reads.get("online"), reads.get("areasRows"), reads.get("maps")
    h0, h, p0, p = reads.get("horizonFirst"), reads.get("horizon"), reads.get("panoramaFirst"), reads.get("panorama")

    def horizon(x):
        return {"source": get(x, "horizon", "source"), "zoom": get(x, "horizon", "zoom"),
                "highestDeg": get(x, "horizon", "highestDeg"), "bearingsWithoutData": get(x, "horizon", "bearingsWithoutData"),
                "reason": get(x, "horizonReason"), "peaksListed": len(get(x, "peaks", "visible") or []) if isinstance(get(x, "peaks", "visible"), list) else get(x, "peaks", "visible"),
                "placeLine": get(x, "placeLine", "text")}

    def panorama(x):
        work = get(x, "work") or {}
        return {"showing": get(x, "showing"), "reason": work.get("reason"), "demPacks": get(work, "terrain", "demPacks"),
                "tiles": get(work, "terrain", "tiles"), "network": get(work, "terrain", "network"),
                "sweeps": len(work.get("sweeps") or []), "stepDrawn": get(x, "stepDrawn"), "candidates": get(x, "candidates"),
                "names": len(get(x, "names") or []), "firstSkylineMs": get(x, "firstSkylineMs"), "placeLine": get(x, "place", "placeLine")}

    return {
        "map": {"noPack": get(fr, "noPack"), "mapMessage": get(fr, "mapMessage"), "viewInside": get(fr, "viewInside"),
                "textShown": get(fr, "textShown"), "actionShown": get(fr, "actionShown"), "cardVisible": get(fr, "cardVisible"),
                "mapHolds": get(fr, "mapHolds")},
        "chip": {"text": get(on, "chip", "text"), "source": get(on, "chip", "source"), "dot": get(on, "chip", "dot"),
                 "hidden": get(on, "chip", "hidden"), "offerDownload": get(on, "offerDownload"), "state": get(on, "state")},
        "rows": [{"id": r.get("id"), "state": r.get("state"), "detail": r.get("detail")} for r in (get(rows, "rows") or []) if isinstance(r, dict)],
        "maps": {"packs": get(maps, "packs"), "inUse": get(maps, "inUse"), "active": get(maps, "active"), "startupPacks": get(maps, "startupPacks")},
        "horizonFirst": horizon(h0), "horizon": horizon(h),
        "panoramaFirst": panorama(p0), "panorama": panorama(p),
    }


def read_row(dev, place, net, wait_ms, raw_dir, settle_s=3.0):
    """One row: the reads at `place` with the network `net`; `unread` lists each read that did not answer."""
    row = {"place": place["id"], "lat": place["lat"], "lon": place["lon"], "inside": place["inside"], "network": net}
    reads, unread, t0 = {}, [], time.time()
    lat, lon = place["lat"], place["lon"]

    def do(name, path, method="GET", timeout=60, keep=True):
        st, body, err = dev.call(path, method, timeout=timeout)
        if err or body is None:
            unread.append({"read": name, "path": path.split("?")[0], "status": st, "error": err or "no JSON body"})
        if keep:
            reads[name] = body
            if raw_dir:
                with open(os.path.join(raw_dir, f"{net}-{place['id']}-{name}.json"), "w", encoding="utf-8", newline="\n") as fh:
                    json.dump({"path": path, "status": st, "error": err, "body": body}, fh, indent=1)
        return body

    do("camera", f"/camera?lat={lat}&lon={lon}&zoom={ZOOM}&bearing=0", "POST", keep=False)
    time.sleep(settle_s)
    do("firstrun", "/firstrun")
    do("online", "/online")
    do("areasRows", "/areas/rows")
    reads["maps"] = fx.read_maps(dev)
    for e in reads["maps"]["errors"]:
        unread.append({"read": "maps", "error": e})
    do("horizonOpen", f"/horizon/ui?lat={lat}&lon={lon}", "POST", keep=False)
    do("horizonFirst", "/horizon")
    do("horizon", f"/horizon?wait={wait_ms}", timeout=wait_ms / 1000 + 60)
    do("panoramaOpen", "/horizon/switch?to=panorama", "POST", keep=False)
    do("panoramaFirst", "/panorama")
    do("panorama", f"/panorama?wait={wait_ms}", timeout=wait_ms / 1000 + 60)
    do("mapBack", "/horizon/switch?to=map", "POST", keep=False)
    row["summary"] = summarize(reads)
    row["unread"] = unread
    row["ok"] = not unread
    row["seconds"] = round(time.time() - t0, 1)
    return row


def settings(dev):
    st, body, err = dev.call("/settings")
    if not isinstance(body, dict):
        return {"error": err}
    words = ("server", "range", "horizon", "units", "mobile", "online", "direct", "limit")
    return {k: v for k, v in body.items() if any(w in k.lower() for w in words) and not isinstance(v, (dict, list))}


def run(dev, out_path, wait_ms, from_dir=None, keep=False):
    t0 = time.time()
    out_dir = os.path.dirname(out_path) or "."
    raw_dir = os.path.join(out_dir, "raw")
    os.makedirs(raw_dir, exist_ok=True)
    table = {"schema": 1, "part": "two-maps", "serial": dev.serial, "fixtureKey": fx.recipe_key(), "maps": fx.MAPS,
             "places": fx.PLACES, "phases": PHASES, "zoom": ZOOM, "waitMs": wait_ms, "rows": []}
    try:
        built = fx.build(from_dir)
        table["build"] = built
        if not built.get("ok"):
            table.update(ok=False, error=built.get("error"))
            return table
        inst = fx.install(dev)
        table["install"] = inst
        if not inst.get("ok"):
            table.update(ok=False, error=f"the fixture did not install: {inst.get('error') or inst.get('packs')}")
            return table
        st, cleared, err = dev.call("/online/cache/clear", "POST", timeout=120)
        table["onlineCacheCleared"] = cleared if not err else {"error": err}
        table["settings"] = settings(dev)
        table["network"] = []
        for net in PHASES:
            sw = fx.network(dev, net == "on")
            table["network"].append(sw)
            dev.restart()
            for place in fx.PLACES:
                table["rows"].append(read_row(dev, place, net, wait_ms, raw_dir))
    except Exception as e:
        table["error"] = f"{type(e).__name__}: {e}"[:500]
    finally:
        if not keep:
            try:
                table["restore"] = fx.restore(dev)
            except Exception as e:
                table["restore"] = {"ok": False, "error": f"{type(e).__name__}: {e}"[:300]}
        table["seconds"] = round(time.time() - t0, 1)
    return table


def verdict(table):
    """The part's failures: a row not read, fewer than eight rows, the fixture or the restore not done; then the tasks'
    assertions (below)."""
    failed = []
    if table.get("error"):
        failed.append({"what": "error", "error": table["error"]})
    want = len(fx.PLACES) * len(PHASES)
    if len(table.get("rows") or []) != want:
        failed.append({"what": "rows", "error": f"{len(table.get('rows') or [])} of {want} rows"})
    for r in table.get("rows") or []:
        if not r.get("ok"):
            failed.append({"what": "unread", "row": f"{r['network']}/{r['place']}", "unread": r.get("unread")})
    if table.get("restore") is not None and not table["restore"].get("ok"):
        failed.append({"what": "restore", "error": table["restore"]})
    failed += assertions(table)
    return failed


def assertions(table):
    """The fixing tasks' assertions over the eight rows, each a list of {"what": "<task>:<name>", "row", ...}."""
    out = []
    # --- T340 to T343: one assertion block per fixing task below, each opened by a comment naming the task and closed by
    # `# end Tnn` (docs/TASKS.md, wave 47's ownership paragraph). Nothing above this line asserts a row. ---
    return out


def main(argv=None):
    ap = argparse.ArgumentParser(description="T339: the two-maps table (the sweep's part two-maps)")
    ap.add_argument("--serial", required=True)
    ap.add_argument("--port", type=int, default=8696)
    ap.add_argument("--out", default=os.path.join("out", "two-maps", "table.json"))
    ap.add_argument("--from", dest="from_dir", default="", help="a folder with the fixture built from the same recipe, copied instead of a build")
    ap.add_argument("--wait-ms", type=int, default=15000, help="each view's ?wait= (default 15000)")
    ap.add_argument("--keep", action="store_true", help="leave the fixture installed at the end")
    a = ap.parse_args(argv)
    try:
        dev = fx.Device(a.serial, a.port)
        dev.connect()
        table = run(dev, a.out, a.wait_ms, a.from_dir or None, a.keep)
    except Exception as e:
        table = {"schema": 1, "part": "two-maps", "rows": [], "error": f"{type(e).__name__}: {e}"[:500]}
    table["failed"] = verdict(table)
    table["ok"] = not table["failed"]
    os.makedirs(os.path.dirname(a.out) or ".", exist_ok=True)
    with open(a.out, "w", encoding="utf-8", newline="\n") as fh:
        json.dump(table, fh, indent=1)
    print(json.dumps(table))
    return 0 if table["ok"] else 1


if __name__ == "__main__":
    sys.exit(main())
