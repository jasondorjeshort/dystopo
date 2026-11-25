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
T352 adds `t352`, three rows inside both maps with the network off after the eight: each map as the primary in turn,
and a map activated while the Panorama is open (`t352_rows`, whose docstring says what it pins).

It fails only on a row it could not read (a route that did not answer, or answered an error): the rows are the reports'
evidence, not a verdict. Each task that fixes a row (T340 to T343) adds its own assertion in a block at the end of this file.

State it pins, so a row does not depend on what ran before it:
- the maps: the fixture's install moves every other map aside (and the end puts them back), so only the pair is open;
- the online tile cache is emptied first (`POST /online/cache/clear`), and the direct sources' block cache (`cache/sources/`,
  with the app stopped): an earlier part's cached tiles or blocks would answer offline;
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
import urllib.parse

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
                "placeLine": get(x, "placeLine", "text"),
                "terrain": get(x, "horizon", "terrain", "cells"),  # T340: the merged read's cells by source
                "peakCandidates": get(x, "peaks", "candidates"), "peakTiles": get(x, "peaks", "tilesRead"),  # T352: the peak read
                "peakMaps": get(x, "peaks", "maps"), "peaksReason": get(x, "peaks", "reason")}

    def panorama(x):
        work = get(x, "work") or {}
        return {"showing": get(x, "showing"), "reason": work.get("reason"), "demPacks": get(work, "terrain", "demPacks"),
                "tiles": get(work, "terrain", "tiles"), "network": get(work, "terrain", "network"),
                "sweeps": len(work.get("sweeps") or []), "stepDrawn": get(x, "stepDrawn"), "candidates": get(x, "candidates"),
                "names": len(get(x, "names") or []), "firstSkylineMs": get(x, "firstSkylineMs"), "placeLine": get(x, "place", "placeLine"),
                "source": get(work, "terrain", "text"), "terrain": get(work, "terrain", "cells"),  # T340: what answered, as the Horizon's `source`
                "peaksLine": get(x, "peaksLine"), "waiting": get(x, "waiting"),
                "peakTiles": get(work, "peaks", "tiles"), "peakMaps": get(work, "peaks", "maps")}  # T352

    return {
        "map": {"noPack": get(fr, "noPack"), "mapMessage": get(fr, "mapMessage"), "viewInside": get(fr, "viewInside"),
                "textShown": get(fr, "textShown"), "actionShown": get(fr, "actionShown"), "cardVisible": get(fr, "cardVisible"),
                "mapHolds": get(fr, "mapHolds")},
        "chip": {"text": get(on, "chip", "text"), "source": get(on, "chip", "source"), "dot": get(on, "chip", "dot"),
                 "hidden": get(on, "chip", "hidden"), "offerDownload": get(on, "offerDownload"), "state": get(on, "state")},
        "rows": [{"id": r.get("id"), "state": r.get("state"), "detail": [d for d in (r.get("detail") or []) if isinstance(d, str) and len(d) <= 60]}
                 for r in (get(rows, "rows") or []) if isinstance(r, dict)],
        "direct": {"sessionBytes": get(on, "direct", "sessionBytes"), "requests": get(on, "direct", "requests"),
                   "offlineFailures": get(on, "direct", "offlineFailures"), "demRendered": get(on, "direct", "demRendered")},
        "maps": {"packs": get(maps, "packs"), "inUse": get(maps, "inUse"), "active": get(maps, "active"), "startupPacks": get(maps, "startupPacks")},
        "horizonFirst": horizon(h0), "horizon": horizon(h),
        "panoramaFirst": panorama(p0), "panorama": panorama(p),
    }


def read_row(dev, place, net, wait_ms, raw_dir, settle_s=3.0, tag=None):
    """One row: the reads at `place` with the network `net`; `unread` lists each read that did not answer. `tag` (T352's
    rows) names the raw files in place of the place's id."""
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
                with open(os.path.join(raw_dir, f"{net}-{tag or place['id']}-{name}.json"), "w", encoding="utf-8", newline="\n") as fh:
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


def activate(dev, name, timeout_s=60.0):
    """T352: makes `name` the primary map (`POST /packs/activate`) and waits until GET /packs names it with both maps open."""
    t0 = time.time()
    st, body, err = dev.call(f"/packs/activate?name={name}", "POST", timeout=120)
    both = {m["name"] for m in fx.MAPS}

    def done(maps):  # the world overview is in use too
        return maps["active"] == name and both <= set(maps["inUse"])

    maps = fx.read_maps(dev)
    while time.time() - t0 < timeout_s and not done(maps):
        time.sleep(1.0)
        maps = fx.read_maps(dev)
    return {"name": name, "status": st, "error": err, "active": maps["active"], "inUse": maps["inUse"],
            "ok": not err and done(maps), "seconds": round(time.time() - t0, 1)}


def t352_rows(dev, wait_ms, raw_dir):
    """T352: the two rows the owner's report asks for, inside both maps: each map as the primary in turn (a row read in full
    after `POST /packs/activate`), and a map activated while the Panorama is open (the Panorama read before and after, the
    Horizon after, and logcat's `Dystopo` lines in between for `ClosedChannelException` and the failed reads).

    State it pins: the network off, with the online tile cache and the direct sources' block cache emptied (the app stopped
    for the second): the block cache answers offline and `/online/cache/clear` leaves it, so an "offline" row could name the
    online source; each row sets the primary itself; the stored primary (`files/packs/active`) is put back as found at the
    end. Nothing here reads the clock but the waits."""
    place = fx.PLACES[0]  # inside both maps
    out = {"place": place["id"], "network": fx.network(dev, False), "rows": []}
    _, active0 = dev.runas("cat files/packs/active 2>/dev/null; true")
    out["storedPrimary"] = active0.strip() or None
    dev.adb("shell", "am", "force-stop", fx.PACKAGE, timeout=30)
    dev.runas("rm -rf cache/sources; true")
    dev.restart()
    st, cleared, err = dev.call("/online/cache/clear", "POST", timeout=120)
    out["onlineCacheCleared"] = not err
    try:
        for m in fx.MAPS:
            act = activate(dev, m["name"])
            row = read_row(dev, place, "off", wait_ms, raw_dir, tag=f"primary-{m['name']}")
            row.update(kind="primary", primary=m["name"], activate=act)
            if not act["ok"]:
                row["unread"].append({"read": "activate", "error": act})
                row["ok"] = False
            out["rows"].append(row)
        # A map activated while the Panorama is open: the map that is not the primary now.
        t0 = time.time()
        last = out["rows"][-1].get("primary") if out["rows"] else None
        other = fx.MAPS[0]["name"] if last != fx.MAPS[0]["name"] else fx.MAPS[1]["name"]
        row = {"kind": "activate-open", "place": place["id"], "network": "off", "activated": other, "unread": []}
        reads = {}

        def do(name, path, method="GET", timeout=60):
            st, body, err = dev.call(path, method, timeout=timeout)
            if err or body is None:
                row["unread"].append({"read": name, "path": path.split("?")[0], "status": st, "error": err or "no JSON body"})
            reads[name] = body
            if raw_dir:
                with open(os.path.join(raw_dir, f"off-activate-open-{name}.json"), "w", encoding="utf-8", newline="\n") as fh:
                    json.dump({"path": path, "status": st, "error": err, "body": body}, fh, indent=1)
            return body

        do("camera", f"/camera?lat={place['lat']}&lon={place['lon']}&zoom={ZOOM}&bearing=0", "POST")
        time.sleep(3.0)
        do("horizonOpen", f"/horizon/ui?lat={place['lat']}&lon={place['lon']}", "POST")
        do("panoramaOpen", "/horizon/switch?to=panorama", "POST")
        do("panoramaBefore", f"/panorama?wait={wait_ms}", timeout=wait_ms / 1000 + 60)
        dev.adb("logcat", "-c", timeout=30)
        row["activate"] = activate(dev, other)
        time.sleep(2.0)  # the open Panorama sees the new map set (its place goes stale) and reads again
        do("panorama", f"/panorama?wait={wait_ms}", timeout=wait_ms / 1000 + 60)
        do("horizon", f"/horizon?wait={wait_ms}", timeout=wait_ms / 1000 + 60)
        _, log = dev.adb("logcat", "-d", "-s", "Dystopo", timeout=60)
        do("mapBack", "/horizon/switch?to=map", "POST")
        lines = log.splitlines()
        row["logcat"] = {"lines": len(lines), "closedChannel": sum("ClosedChannelException" in ln for ln in lines),
                         "failed": [ln.strip()[-200:] for ln in lines if " failed" in ln][:10]}
        sm = summarize({"panoramaFirst": reads.get("panoramaBefore"), "panorama": reads.get("panorama"), "horizon": reads.get("horizon")})
        row["summary"] = {k: sm[k] for k in ("horizon", "panoramaFirst", "panorama")}
        row["ok"] = not row["unread"] and row["activate"]["ok"]
        row["seconds"] = round(time.time() - t0, 1)
        out["rows"].append(row)
    finally:
        if out["storedPrimary"]:
            dev.runas(f"echo {out['storedPrimary']} > files/packs/active; true")
        else:
            dev.runas("rm -f files/packs/active; true")
    return out


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
        for i, net in enumerate(PHASES):
            sw = fx.network(dev, net == "on")
            table["network"].append(sw)
            if i == 0:  # the direct sources' block cache too, with the app stopped: it answers offline as the tile cache does
                dev.adb("shell", "am", "force-stop", fx.PACKAGE, timeout=30)
                _, du = dev.runas("du -sk cache/sources 2>/dev/null; rm -rf cache/sources; true")
                table["sourceCacheClearedKb"] = int(du.split()[0]) if du.split() and du.split()[0].isdigit() else 0
            dev.restart()
            for place in fx.PLACES:
                table["rows"].append(read_row(dev, place, net, wait_ms, raw_dir))
        table["t352"] = t352_rows(dev, wait_ms, raw_dir)
        table["t342"] = t342_collect(dev, wait_ms, raw_dir)  # T342: the rows with a map switched off (its block, below)
        table["t343"] = t343_collect(dev, raw_dir)  # T343: the Download card at three places (its block, below)
        table["t341"] = t341_collect(dev, raw_dir)  # T341: GET /why and the chip at the overview's zooms (its block, below)
        table["t354"] = t354_collect(dev, wait_ms, raw_dir)  # T354: GET /holdings against /why and the sky views, each map chosen in turn (its block, below)
        table["t356"] = t356_collect(dev, raw_dir)  # T356: an area's z15 cells inside the coarse map, offline and with no source (its block, below)
        table["t368"] = t368_collect(dev, raw_dir)  # T368: the network's deeper elevation fetched behind the coarse map, the setting on and off (its block, below)
        table["t359"] = t359_collect(dev, raw_dir)  # T359: the chip, the button, search and the first-run rule against GET /holdings, the network off and on (its block, below)
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
    # T340: the Horizon view and the Panorama read terrain one way. In every row both name the same source (the terrain
    # line's words, None for none) and, where there is none, the same sentence; the Panorama never waits on an empty lens
    # (a skyline or a sentence) and its Peaks line is never "Finding the peaks..." once the wait is over; online, outside
    # both maps, it draws a skyline from the online source.
    for r in table.get("rows") or []:
        sm = r.get("summary")
        if not isinstance(sm, dict):
            continue
        row = f"{r.get('network')}/{r.get('place')}"
        h, p = sm.get("horizon") or {}, sm.get("panorama") or {}
        if h.get("source") != p.get("source"):
            out.append({"what": "T340:same-source", "row": row, "horizon": h.get("source"), "panorama": p.get("source")})
        if h.get("source") is None and h.get("reason") != p.get("reason"):
            out.append({"what": "T340:same-sentence", "row": row, "horizon": h.get("reason"), "panorama": p.get("reason")})
        if not p.get("sweeps") and not p.get("reason"):
            out.append({"what": "T340:panorama-empty", "row": row, "waiting": p.get("waiting")})
        if p.get("peaksLine") == "Finding the peaks in view...":
            out.append({"what": "T340:peaks-unfinished", "row": row})
        if r.get("place") == "outside" and r.get("network") == "on" and not (p.get("sweeps") and "online source" in (p.get("source") or "")):
            out.append({"what": "T340:outside-online", "row": row, "panorama": p.get("source"), "reason": p.get("reason")})
    # end T340
    # T352: peaks in every row inside a map and the Panorama a skyline there; the peaks the same whichever map is primary;
    # a map activated with the Panorama open leaves it a skyline and peaks, with no ClosedChannelException in logcat.
    rows = table.get("rows") or []
    for r in rows:
        sm = r.get("summary")
        if not isinstance(sm, dict) or not r.get("inside"):
            continue
        row = f"{r.get('network')}/{r.get('place')}"
        h, p = sm.get("horizon") or {}, sm.get("panorama") or {}
        if not h.get("peakCandidates"):
            out.append({"what": "T352:horizon-peaks", "row": row, "reason": h.get("peaksReason"), "tiles": h.get("peakTiles")})
        if not p.get("candidates") or not p.get("sweeps"):
            out.append({"what": "T352:panorama-peaks", "row": row, "candidates": p.get("candidates"), "sweeps": p.get("sweeps"), "reason": p.get("reason")})
    t = table.get("t352")
    if t is None:
        if (table.get("install") or {}).get("ok") and not table.get("error"):  # a run that installed the fixture and did not stop early
            out.append({"what": "T352:rows-missing"})
    else:
        extra = t.get("rows") or []
        if [x.get("kind") for x in extra] != ["primary"] * len(fx.MAPS) + ["activate-open"]:
            out.append({"what": "T352:rows", "kinds": [x.get("kind") for x in extra]})
        for x in extra:
            row = f"t352/{x.get('kind')}/{x.get('primary') or x.get('activated')}"
            if not x.get("ok"):
                out.append({"what": "T352:unread", "row": row, "unread": x.get("unread")})
            sm = x.get("summary") or {}
            h, p = sm.get("horizon") or {}, sm.get("panorama") or {}
            if not h.get("peakCandidates"):
                out.append({"what": "T352:horizon-peaks", "row": row, "reason": h.get("peaksReason")})
            if not p.get("candidates") or not p.get("sweeps"):
                out.append({"what": "T352:panorama-peaks", "row": row, "candidates": p.get("candidates"), "sweeps": p.get("sweeps")})
            if x.get("kind") == "activate-open" and (x.get("logcat") or {}).get("closedChannel"):
                out.append({"what": "T352:closed-channel", "row": row, "logcat": x.get("logcat")})
        prim = [((x.get("summary") or {}).get("horizon") or {}) for x in extra if x.get("kind") == "primary"]
        if len({(h.get("peakCandidates"), h.get("source")) for h in prim}) > 1:
            out.append({"what": "T352:primary-matters", "rows": [[h.get("peakCandidates"), h.get("source")] for h in prim]})
    # end T352
    # T342
    out += t342_assertions(table)
    # end T342
    # T343
    out += t343_assertions(table)
    # end T343
    # T341
    out += t341_assertions(table)
    # end T341
    out += t354_assertions(table)  # T354
    out += t356_assertions(table)  # T356
    out += t368_assertions(table)  # T368
    out += t359_assertions(table)  # T359
    return out


# T342: a map switched off ("Use on this phone", `POST /areas/rows/off`). After the eight rows, with the network off and a
# cold start (so nothing kept from the phases answers): the place inside both maps read with the fine map off, then on again
# with no restart (the app's pid the same), then with both maps off; both are put back on at the end, whatever failed. With
# the fine map off every reader is on the wide one (the maps in use, the Horizon's and the Panorama's terrain); on again the
# fine one is read again; both off, the map says "No offline map covers this view" with its action, never the first-run
# card, and Storage still counts both maps (with the online sources off as well, put back at the end: the network off alone
# leaves the phone's own reading of the sources counted as drawing). Depends on nothing an earlier part leaves: it switches
# the network off and restarts the app itself; it needs only the fixture this part installed.
T342_CASES = ["fine-off", "fine-on-again", "both-off"]


def t342_collect(dev, wait_ms, raw_dir):
    place = next(p for p in fx.PLACES if p["id"] == "inside-both")
    wide, fine = fx.MAPS[0]["name"], fx.MAPS[1]["name"]
    out = {"switches": [], "rows": []}

    def switch(name, off):
        st, body, err = dev.call(f"/areas/rows/off?name={name}&off={1 if off else 0}", "POST", timeout=120)
        out["switches"].append({"name": name, "off": off, "status": st, "error": err, "inUse": get(body, "result", "inUse"),
                                "offNames": get(body, "result", "offNames")})

    def read(case):
        row = read_row(dev, place, f"t342-{case}", wait_ms, raw_dir)
        raw = {}
        for name in ("horizon", "panorama"):
            try:
                with open(os.path.join(raw_dir, f"t342-{case}-{place['id']}-{name}.json"), encoding="utf-8") as fh:
                    raw[name] = json.load(fh).get("body")
            except (OSError, ValueError):
                raw[name] = None
        _, rows, _ = dev.call("/areas/rows")
        _, storage, _ = dev.call("/storage")
        stored = {p.get("name"): p.get("bytes") for p in (get(storage, "stores", "packs", "packs") or []) if isinstance(p, dict)}
        row.update(case=case, pid=fx.ad.app_pid(dev.exe, dev.serial),
                   horizonMaps=get(raw["horizon"], "horizon", "terrain", "maps"), panoramaMaps=get(raw["panorama"], "work", "terrain", "maps"),
                   off={r.get("pack"): r.get("off") for r in (get(rows, "rows") or []) if isinstance(r, dict) and r.get("pack")},
                   storageBytes={n: stored.get(n) for n in (wide, fine)})
        out["rows"].append(row)

    try:
        out["network"] = fx.network(dev, False)
        dev.restart()
        switch(fine, True)
        read("fine-off")
        switch(fine, False)
        read("fine-on-again")
        switch(fine, True)
        switch(wide, True)
        # No online source either (the server and the phone's own reading of the sources, as a11y's map-outside does): with
        # direct reading on, the map counts an online source as drawing the view even with the network off (T332's rule).
        _, st, _ = dev.call("/settings")
        _, d, _ = dev.call("/firstrun/direct")
        out["onlineBefore"] = {"server": (st or {}).get("server") or "", "direct": bool((d or {}).get("enabled", True))}
        dev.call("/settings?server=", "POST")
        dev.call("/firstrun/direct?on=0", "POST")
        read("both-off")
    except Exception as e:
        out["error"] = f"{type(e).__name__}: {e}"[:300]
    finally:
        switch(fine, False)
        switch(wide, False)
        before = out.get("onlineBefore")
        if before:
            dev.call("/settings?server=" + urllib.parse.quote(before["server"], safe=""), "POST")
            dev.call(f"/firstrun/direct?on={1 if before['direct'] else 0}", "POST")
    return out


def t342_assertions(table):
    out = []
    t = table.get("t342")
    if not isinstance(t, dict):
        return out  # not run: the phases failed before it (the table's error says why), or a table from before T342
    wide, fine = fx.MAPS[0]["name"], fx.MAPS[1]["name"]
    if t.get("error"):
        out.append({"what": "T342:error", "error": t["error"]})
    for sw in t.get("switches") or []:
        if sw.get("error"):
            out.append({"what": "T342:switch", "switch": sw})
    rows = {r.get("case"): r for r in t.get("rows") or []}
    for case in T342_CASES:
        r = rows.get(case)
        if r is None:
            out.append({"what": "T342:missing", "case": case})
            continue
        if not r.get("ok"):
            out.append({"what": "T342:unread", "case": case, "unread": r.get("unread")})
        sm = r.get("summary") or {}
        in_use = [n for n in get(sm, "maps", "inUse") or [] if n != "world"]  # the built-in world overview is no map of the list
        want_off = {"fine-off": {wide: False, fine: True}, "fine-on-again": {wide: False, fine: False}, "both-off": {wide: True, fine: True}}[case]
        if {k: (r.get("off") or {}).get(k) for k in want_off} != want_off:
            out.append({"what": "T342:rows-off", "case": case, "off": r.get("off")})
        if any(not isinstance(b, int) or b <= 0 for b in (r.get("storageBytes") or {}).values()) or not r.get("storageBytes"):
            out.append({"what": "T342:storage", "case": case, "storageBytes": r.get("storageBytes")})
        if case == "fine-off":
            for what, maps in (("inUse", in_use), ("horizon", r.get("horizonMaps")), ("panorama", r.get("panoramaMaps"))):
                if list(maps or []) != [wide]:
                    out.append({"what": f"T342:fine-off-{what}", "maps": maps})
        if case == "fine-on-again":
            for what, maps in (("inUse", in_use), ("horizon", r.get("horizonMaps")), ("panorama", r.get("panoramaMaps"))):
                if fine not in (maps or []):
                    out.append({"what": f"T342:on-again-{what}", "maps": maps})
            before = rows.get("fine-off", {}).get("pid")
            if not before or r.get("pid") != before:
                out.append({"what": "T342:restarted", "pids": [before, r.get("pid")]})
        if case == "both-off":
            m = sm.get("map") or {}
            if in_use or m.get("mapMessage") != "outside" or not m.get("actionShown") or m.get("noPack") or m.get("cardVisible"):
                out.append({"what": "T342:both-off-message", "inUse": in_use, "map": m})
    return out
# end T342


# T343
# The status chip, the map's "Download this area" button and the Download card follow one rule (HeldHere over the view's box at
# the detail the card opens at). In every one of the eight rows, from its `GET /online` answer: a button that reads "Already on
# this phone" goes with the chip's "on this phone", and a chip that says the view is not on the phone goes with a button that
# offers the download. Then, with the network on and a cold start (whatever the phases and T342's rows left), the card is opened
# (`POST /areas/region`) at three views and its lines read (`GET /areas/picker`): the wide map only at z12 (held coarser: the
# card says what is held and offers Download), the summit at z13 inside the fine map (nothing new: no Download, the line names
# the fine map) and outside both (nothing held: no line, Download). Depends on nothing an earlier part leaves: it switches the
# network on and restarts the app itself, and the maps T342 switched off are back on by then.
T343_VIEWS = [("wide-only", 12, "coarser"), ("inside-both", 13, "nothing-new"), ("outside", 12, "nothing")]


def t343_collect(dev, raw_dir):
    out = {"cards": [], "rawDir": raw_dir}  # rawDir: where the assertions read each row's GET /online answer
    try:
        out["network"] = fx.network(dev, True)
        dev.restart()
        for pid, zoom, case in T343_VIEWS:
            place = next(p for p in fx.PLACES if p["id"] == pid)
            dev.call("/areas/picker/close", "POST")
            dev.call(f"/camera?lat={place['lat']}&lon={place['lon']}&zoom={zoom}&bearing=0", "POST")
            time.sleep(4.0)
            _, on, err1 = dev.call("/online")
            _, opened, err2 = dev.call("/areas/region?wait=60000", "POST", timeout=120)
            _, pk, err3 = dev.call("/areas/picker")
            card = {"case": case, "place": pid, "zoom": zoom, "errors": [e for e in (err1, err2, err3) if e],
                    "chip": get(on, "chip", "text"), "source": get(on, "chip", "source"), "button": get(on, "chip", "download"),
                    "verdict": get(on, "chip", "held", "verdict")}
            card.update({k: (pk or {}).get(k) for k in ("detail", "heldLine", "nothingNew", "canDownload", "sizeLine")})
            if raw_dir:
                with open(os.path.join(raw_dir, f"t343-{case}.json"), "w", encoding="utf-8", newline="\n") as fh:
                    json.dump({"online": on, "picker": pk}, fh, indent=1)
            out["cards"].append(card)
    except Exception as e:
        out["error"] = f"{type(e).__name__}: {e}"[:300]
    finally:
        dev.call("/areas/picker/close", "POST")
    return out


def t343_assertions(table):
    out = []
    # One rule in every row: "Already on this phone" only where the chip says on this phone; a view the chip says is not on
    # the phone always offered for download.
    for r in table.get("rows") or []:
        chip = get(r, "summary", "chip") or {}
        row = f"{r.get('network')}/{r.get('place')}"
        raw = None
        try:
            with open(os.path.join(get(table, "t343", "rawDir") or "", f"{r.get('network')}-{r.get('place')}-online.json"), encoding="utf-8") as fh:
                raw = json.load(fh).get("body")
        except (OSError, ValueError, TypeError):
            pass
        button = get(raw, "chip", "download") if raw is not None else None
        if not isinstance(button, dict):
            continue  # a table read without the raw answers (or from before T343): the cards below still assert
        if str(button.get("text") or "").startswith("Already on this phone") and chip.get("source") != "on this phone":
            out.append({"what": "T343:already-not-on-phone", "row": row, "chip": chip.get("text"), "button": button})
        if chip.get("source") not in ("on this phone", None) and button.get("shown") and not button.get("enabled"):
            out.append({"what": "T343:off-phone-not-offered", "row": row, "chip": chip.get("text"), "button": button})
    t = table.get("t343")
    if not isinstance(t, dict):
        return out
    if t.get("error"):
        out.append({"what": "T343:error", "error": t["error"]})
    cards = {c.get("case"): c for c in t.get("cards") or []}
    for _pid, _z, case in T343_VIEWS:
        c = cards.get(case)
        if c is None:
            out.append({"what": "T343:missing", "case": case})
            continue
        if c.get("errors"):
            out.append({"what": "T343:unread", "case": case, "errors": c["errors"]})
        if case == "coarser" and not (c.get("heldLine") and c.get("canDownload") and c.get("button", {}).get("enabled")):
            out.append({"what": "T343:coarser", "card": c})
        if case == "nothing-new" and not (c.get("nothingNew") and "rainier-fine" in c["nothingNew"] and c.get("canDownload") is False
                                         and c.get("source") == "on this phone" and (c.get("button") or {}).get("enabled") is False):
            out.append({"what": "T343:nothing-new", "card": c})
        if case == "nothing" and (c.get("heldLine") or c.get("nothingNew") or not c.get("canDownload")):
            out.append({"what": "T343:nothing", "card": c})
    return out
# end T343


# T341
# Why a view is sparse (`GET /why`) and More detail from online at the world overview's zooms. Inside both maps, with the
# network off and then on (the online tile cache and the direct sources' block cache emptied first, with the app stopped, so
# nothing kept answers offline), the camera at z5.5 (the overview's own zooms), z6.5 (above the overview, below the maps'
# first zoom) and z12 (inside the maps), `GET /why` and `GET /online` are read after a settle (20 s online: the vector
# source's refresh after the fetches behind the overview settles over 10 s). Asserted: every read has a `cause`; offline the
# overview answers z5.5 at once (`vector.serves` overview, the chip "from the world overview") and z6.5 is cut from it; online
# z5.5 is the online tile (kept, or fetching behind the overview) and the chip is not "no data here" at z6.5; at z12 a map
# answers (`cause` none). Saves the reads under `<raw>/t341-<net>-z<zoom>.json`.
T341_ZOOMS = [5.5, 6.5, 12]


def t341_collect(dev, raw_dir):
    out = {"reads": []}
    place = next(p for p in fx.PLACES if p["id"] == "inside-both")
    try:
        for net in ("off", "on"):
            out.setdefault("network", []).append(fx.network(dev, net == "on"))
            dev.call("/online/cache/clear", "POST", timeout=120)
            dev.adb("shell", "am", "force-stop", fx.PACKAGE, timeout=30)
            dev.runas("rm -rf cache/sources; true")
            dev.restart()
            for z in T341_ZOOMS:
                dev.call(f"/camera?lat={place['lat']}&lon={place['lon']}&zoom={z}&bearing=0", "POST")
                time.sleep(20.0 if net == "on" else 5.0)
                _, why, e1 = dev.call("/why")
                _, on, e2 = dev.call("/online")
                r = {"network": net, "zoom": z, "errors": [e for e in (e1, e2) if e], "cause": get(why, "cause"),
                     "vector": get(why, "online", "vector"), "lastVector": get(why, "online", "lastVector"),
                     "chip": get(on, "chip", "text"), "column": get(on, "chip", "column"),
                     "bytesLanded": get(why, "online", "bytesLanded")}
                if raw_dir:
                    with open(os.path.join(raw_dir, f"t341-{net}-z{z}.json"), "w", encoding="utf-8", newline="\n") as fh:
                        json.dump({"why": why, "online": on}, fh, indent=1)
                out["reads"].append(r)
    except Exception as e:
        out["error"] = f"{type(e).__name__}: {e}"[:300]
    return out


def t341_assertions(table):
    out = []
    t = table.get("t341")
    if not t:
        return out  # not run: the phases failed before it, or a table from before T341
    if t.get("error"):
        out.append({"what": "T341:error", "error": t["error"]})
    for r in t.get("reads") or []:
        row = f"{r['network']}/z{r['zoom']}"
        serves = (r.get("vector") or {}).get("serves")
        if r.get("errors") or not r.get("cause"):
            out.append({"what": "T341:unread", "row": row, "errors": r.get("errors")})
            continue
        if r["network"] == "off" and r["zoom"] < 6 and (serves != "overview" or r.get("column") != "world"):
            out.append({"what": "T341:overview-offline", "row": row, "serves": serves, "chip": r.get("chip")})
        if r["network"] == "off" and 6 <= r["zoom"] < 8 and r.get("cause") != "above-overview-top":
            out.append({"what": "T341:cut-from-overview", "row": row, "cause": r.get("cause")})
        if r["network"] == "on" and r["zoom"] < 6 and not (serves == "cache" or (r.get("vector") or {}).get("fetchBehind")):
            out.append({"what": "T341:overview-online", "row": row, "vector": r.get("vector")})
        if r["network"] == "on" and 6 <= r["zoom"] < 8 and r.get("column") == "no_data":
            out.append({"what": "T341:chip-no-data", "row": row, "chip": r.get("chip"), "lastVector": r.get("lastVector")})
        if r["zoom"] >= 8 and r.get("cause") not in ("none", "relief-off"):
            out.append({"what": "T341:map-cause", "row": row, "cause": r.get("cause")})
    return out
# end T341


# T354
# ADR 0066's lookup (`GET /holdings`) against what `/why`, `/horizon` and `/panorama` name, each map chosen last in turn
# (`activate()`), at all four places. With the network off and a cold start (the online tile cache and the direct sources'
# block cache emptied first, the app stopped for the second), so only the two maps answer: for each map made the primary, at
# each place the camera at ZOOM, then `GET /holdings` and `GET /why` (both for that point and zoom), "Sky from here" and
# the Panorama with `?wait=`. Asserted per row: every read answered; `/why`'s `answersVector` and `answersDem` name the
# lookup's `tilePathServes`, which (no cache, no area here) is its `serves`; the lookup's elevation members agree with the
# order the Horizon view's and the Panorama's terrain read took its maps in (the maps both name, in the same order); where
# a map's box holds the place, the lookup's `readZoom` is the Horizon's zoom; inside both maps the fine map serves both
# tiles; the activated map is the one marked `chosen`. Across the two primaries: at each place the same map serves (the
# fixture's maps differ in depth everywhere, so the map chosen last never decides). The stored primary is put back at the
# end. Nothing here reads the clock but the waits; it depends on no earlier part's state (it switches the network off,
# empties the caches and restarts itself), only on the fixture this part installed.
def t354_collect(dev, wait_ms, raw_dir):
    out = {"rows": []}
    _, active0 = dev.runas("cat files/packs/active 2>/dev/null; true")
    out["storedPrimary"] = active0.strip() or None
    try:
        out["network"] = fx.network(dev, False)
        dev.adb("shell", "am", "force-stop", fx.PACKAGE, timeout=30)
        dev.runas("rm -rf cache/sources; true")
        dev.restart()
        _, _, err = dev.call("/online/cache/clear", "POST", timeout=120)
        out["onlineCacheCleared"] = not err
        for m in fx.MAPS:
            act = activate(dev, m["name"])
            for place in fx.PLACES:
                lat, lon = place["lat"], place["lon"]
                row = {"chosen": m["name"], "activate": act["ok"], "place": place["id"], "inside": place["inside"], "errors": []}
                reads = {}

                def do(name, path, method="GET", timeout=60, keep=True):
                    st, body, err = dev.call(path, method, timeout=timeout)
                    if err or body is None:
                        row["errors"].append({"read": name, "path": path.split("?")[0], "status": st, "error": err or "no JSON body"})
                    if keep:
                        reads[name] = body
                        if raw_dir:
                            with open(os.path.join(raw_dir, f"t354-{m['name']}-{place['id']}-{name}.json"), "w", encoding="utf-8", newline="\n") as fh:
                                json.dump({"path": path, "status": st, "error": err, "body": body}, fh, indent=1)
                    return body

                do("camera", f"/camera?lat={lat}&lon={lon}&zoom={ZOOM}&bearing=0", "POST", keep=False)
                time.sleep(3.0)
                h = do("holdings", f"/holdings?z={ZOOM}&lat={lat}&lon={lon}")
                w = do("why", f"/why?z={ZOOM}&lat={lat}&lon={lon}")
                do("horizonOpen", f"/horizon/ui?lat={lat}&lon={lon}", "POST", keep=False)
                hz = do("horizon", f"/horizon?wait={wait_ms}", timeout=wait_ms / 1000 + 60)
                do("panoramaOpen", "/horizon/switch?to=panorama", "POST", keep=False)
                pn = do("panorama", f"/panorama?wait={wait_ms}", timeout=wait_ms / 1000 + 60)
                do("mapBack", "/horizon/switch?to=map", "POST", keep=False)

                def role(r):
                    return {"serves": get(h, r, "serves"), "tilePathServes": get(h, r, "tilePathServes"), "depth": get(h, r, "depth"),
                            "members": [x.get("name") for x in get(h, r, "members") or [] if isinstance(x, dict)],
                            "holds": [x.get("holds") for x in get(h, r, "members") or [] if isinstance(x, dict)],
                            "chosen": [x.get("name") for x in get(h, r, "members") or [] if isinstance(x, dict) and x.get("chosen")],
                            "off": get(h, r, "off"), "networkAdds": get(h, r, "network", "adds")}
                row.update(
                    vector=role("vector"), dem=role("dem"), readZoom=get(h, "readZoom"),
                    whyVector=[x.get("name") for x in get(w, "maps") or [] if isinstance(x, dict) and x.get("answersVector")],
                    whyDem=[x.get("name") for x in get(w, "maps") or [] if isinstance(x, dict) and x.get("answersDem")],
                    whyPrimary=[x.get("name") for x in get(w, "maps") or [] if isinstance(x, dict) and x.get("primary")],
                    horizonMaps=get(hz, "horizon", "terrain", "maps"), horizonZoom=get(hz, "horizon", "zoom"),
                    panoramaMaps=get(pn, "work", "terrain", "maps"),
                )
                out["rows"].append(row)
    except Exception as e:
        out["error"] = f"{type(e).__name__}: {e}"[:300]
    finally:
        if out["storedPrimary"]:
            dev.runas(f"echo {out['storedPrimary']} > files/packs/active; true")
        else:
            dev.runas("rm -f files/packs/active; true")
    return out


def t354_assertions(table):
    out = []
    t = table.get("t354")
    if not isinstance(t, dict):
        return out  # not run: the phases failed before it, or a table from before T354
    if t.get("error"):
        out.append({"what": "T354:error", "error": t["error"]})
    rows = t.get("rows") or []
    if len(rows) != len(fx.MAPS) * len(fx.PLACES):
        out.append({"what": "T354:rows", "rows": len(rows)})
    fine = fx.MAPS[1]["name"]
    for r in rows:
        row = f"t354/{r.get('chosen')}/{r.get('place')}"
        if r.get("errors") or not r.get("activate"):
            out.append({"what": "T354:unread", "row": row, "errors": r.get("errors"), "activate": r.get("activate")})
            continue
        v, d = r.get("vector") or {}, r.get("dem") or {}
        for name, x in (("vector", v), ("dem", d)):
            if x.get("serves") != x.get("tilePathServes"):
                out.append({"what": f"T354:{name}-serves-tile-path", "row": row, "serves": x.get("serves"), "tilePath": x.get("tilePathServes")})
        if r.get("whyVector") != [m for m in [v.get("tilePathServes")] if m and m != "world"]:
            out.append({"what": "T354:why-vector", "row": row, "why": r.get("whyVector"), "holdings": v.get("tilePathServes")})
        if r.get("whyDem") != [m for m in [d.get("tilePathServes")] if m]:
            out.append({"what": "T354:why-dem", "row": row, "why": r.get("whyDem"), "holdings": d.get("tilePathServes")})
        members = d.get("members") or []
        for view in ("horizon", "panorama"):
            maps = r.get(f"{view}Maps") or []
            if [m for m in maps if m in members] != [m for m in members if m in maps]:
                out.append({"what": f"T354:{view}-order", "row": row, view: maps, "holdings": members})
        if r.get("inside") and r.get("readZoom") != r.get("horizonZoom"):
            out.append({"what": "T354:read-zoom", "row": row, "holdings": r.get("readZoom"), "horizon": r.get("horizonZoom")})
        if r.get("place") == "inside-both" and (v.get("serves") != fine or d.get("serves") != fine):
            out.append({"what": "T354:fine-inside", "row": row, "vector": v.get("serves"), "dem": d.get("serves")})
        for name, x in (("vector", v), ("dem", d)):
            if any(c != r.get("chosen") for c in x.get("chosen") or []):
                out.append({"what": f"T354:{name}-chosen", "row": row, "chosen": x.get("chosen")})
        if r.get("whyPrimary") != [r.get("chosen")]:
            out.append({"what": "T354:why-primary", "row": row, "primary": r.get("whyPrimary")})
    by_place = {}
    for r in rows:
        by_place.setdefault(r.get("place"), set()).add(((r.get("vector") or {}).get("serves"), (r.get("dem") or {}).get("serves")))
    for place, seen in by_place.items():
        if len(seen) > 1:
            out.append({"what": "T354:chosen-matters", "place": place, "serves": sorted(seen, key=str)})
    return out
# end T354


# T356
# What the phone holds, ranked by depth (ADR 0066 decision 1), in the tile paths and the downloads' skip. A local stub
# server (this script, on the host, reached as 10.0.2.2) serves synthetic elevation tiles (a smooth made-up surface, no
# place's data) and no vector tiles; the phone's server is set to it, and a corridor at `wide-only` (inside the coarse map,
# whose elevation stops at z11) is downloaded at z10 to z15. Asserted: the download fetched its z12 to z15 elevation from
# the stub (before T356 a map's overzoom skipped it) and skipped what the coarse map stores; then, in airplane mode with the
# server still set and again with no source set (server cleared, direct reading off), after a cold start: `GET /holdings` at
# the place names the area as what serves the z15 elevation tile and as the tile path's (`tilePathServes`); with the camera
# at z15 the request log names the area (`area:<name>`) for the elevation and contour tiles there, and no z15 tile of the
# place is a 404. Pinned: the online tile cache is emptied first; the settings it changes (server, direct reading, the
# network) are read first and put back, and the area is deleted, at the end; nothing reads the clock but the waits. It
# depends on no earlier part, only on the fixture this part installed.
T356_AREA = "t356-held"
T356_LINE = "46.726,-121.566;46.734,-121.554"  # at the fixture's wide-only place, inside the coarse map only


def t356_png(z, x, y, size=256):
    """A synthetic Terrarium tile: a smooth surface of global pixel position (no place's data), the same across zooms."""
    import math
    import struct
    import zlib
    rows = []
    scale = 2.0 ** (15 - z)
    for j in range(size):
        gy = (y * size + j) * scale
        row = bytearray([0])
        for i in range(size):
            gx = (x * size + i) * scale
            e = 1200.0 + 250.0 * math.sin(gx / 700.0) * math.cos(gy / 900.0)
            v = e + 32768.0
            row += bytes((int(v // 256) & 255, int(v) & 255, int((v - int(v)) * 256) & 255))
        rows.append(bytes(row))

    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(b"".join(rows), 6)) + chunk(b"IEND", b""))


def t356_stub():
    """The stub server on a free host port: `/live/info`, `/dem/z/x/y.png` (synthetic), 404 for the rest; (server, port, log)."""
    import re
    import threading
    from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
    log = []

    class H(BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def reply(self, code, body=b"", ctype="application/octet-stream"):
            self.send_response(code)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            if body:
                self.wfile.write(body)
            log.append({"path": self.path.split("?")[0], "status": code})

        def do_POST(self):  # no /area manifest: the phone makes it
            self.reply(404)

        def do_GET(self):
            p = self.path.split("?")[0]
            if p == "/live/info":
                return self.reply(200, json.dumps({"demMinZoom": 6, "demMaxZoom": 15, "osmMaxZoom": 14}).encode(), "application/json")
            m = re.match(r"^/dem/(\d+)/(\d+)/(\d+)\.png$", p)
            if m and 6 <= int(m.group(1)) <= 15:
                return self.reply(200, t356_png(*(int(g) for g in m.groups())), "image/png")
            return self.reply(404)

    srv = ThreadingHTTPServer(("127.0.0.1", 0), H)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv, srv.server_address[1], log


def t356_tile(z, lat, lon):
    import math
    n = 2 ** z
    return z, int((lon + 180.0) / 360.0 * n), int((1.0 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2.0 * n)


def t356_here(lines, total_before, total_after, lat, lon):
    """The request log's lines since `total_before` for the elevation, contour and shade tiles at z15 within one tile of the place."""
    new = lines[-(total_after - total_before):] if total_after > total_before else []
    _, cx, cy = t356_tile(15, lat, lon)
    here = []
    for line in new:
        parts = line.get("path", "").split("/")
        if len(parts) > 4 and parts[1] in ("dem", "contours", "shade") and parts[2] == "15":
            tx, ty = int(parts[3]), int(parts[4].split(".")[0])
            if abs(tx - cx) <= 1 and abs(ty - cy) <= 1:
                here.append({"kind": parts[1], "tile": f"15/{tx}/{ty}", "pack": line.get("pack"), "status": line.get("status")})
    return here


def t356_collect(dev, raw_dir):
    out = {"phases": []}
    place = next(p for p in fx.PLACES if p["id"] == "wide-only")
    lat, lon = place["lat"], place["lon"]
    _, s0, _ = dev.call("/settings")
    _, d0, _ = dev.call("/firstrun/direct")
    server0 = (s0 or {}).get("server") or ""
    direct0 = bool((d0 or {}).get("enabled"))
    out["before"] = {"serverSet": bool(server0), "direct": direct0}
    srv, port, log = t356_stub()
    area_id = None

    def save(name, body):
        if raw_dir:
            with open(os.path.join(raw_dir, f"t356-{name}.json"), "w", encoding="utf-8", newline="\n") as fh:
                json.dump(body, fh, indent=1)

    try:
        fx.network(dev, True)
        dev.call("/online/cache/clear", "POST", timeout=120)
        dev.call("/settings?server=" + urllib.parse.quote(f"http://10.0.2.2:{port}", safe=""), "POST")
        time.sleep(3.0)
        _, started, err = dev.call(f"/areas/download?line={T356_LINE}&bufferM=300&minZoom=10&maxZoom=15&name={T356_AREA}", "POST", timeout=60)
        area_id = get(started, "download", "id")
        t0, dl = time.time(), None
        while time.time() - t0 < 300:
            _, dl, _ = dev.call("/areas/download")
            if get(dl, "state") in ("done", "failed", "partial", "cancelled"):
                break
            time.sleep(2.0)
        save("download", dl)
        dem = [z for z, x, y in (map(int, e["path"][5:-4].split("/")) for e in list(log) if e["path"].startswith("/dem/") and e["status"] == 200)
               if abs(x - t356_tile(z, lat, lon)[1]) <= 2 and abs(y - t356_tile(z, lat, lon)[2]) <= 2]  # the corridor's, not a view's elsewhere
        out["download"] = {"error": err, "id": area_id, "state": get(dl, "state"), "planned": get(dl, "planned"), "fetched": get(dl, "fetched"),
                           "inPacks": get(dl, "inPacks"), "failed": get(dl, "failed"),
                           "demFetchedByZoom": {str(z): dem.count(z) for z in sorted(set(dem))}}
        for name, source in (("airplane", True), ("no-source", False)):
            fx.network(dev, False)
            if not source:
                dev.call("/settings?server=", "POST")
                dev.call("/firstrun/direct?on=0", "POST")
            dev.restart()
            ph = {"phase": name}
            _, h, e1 = dev.call(f"/holdings?z=14.5&lat={lat}&lon={lon}")
            save(f"{name}-holdings", h)
            ph["dem"] = {"serves": get(h, "dem", "serves"), "tilePathServes": get(h, "dem", "tilePathServes"), "tile": get(h, "dem", "tile")}
            dev.call(f"/camera?lat={lat + 0.3}&lon={lon}&zoom=10&bearing=0", "POST")
            time.sleep(4.0)
            _, pre, _ = dev.call("/stats/requests?limit=1")
            dev.call(f"/camera?lat={lat}&lon={lon}&zoom=15&bearing=0", "POST")
            time.sleep(15.0)
            _, req, e2 = dev.call("/stats/requests?limit=1500")
            ph["errors"] = [e for e in (e1, e2) if e]
            req = req or {}
            ph["z15Here"] = t356_here(req.get("lines") or [], (pre or {}).get("total", 0), req.get("total", 0), lat, lon)
            save(f"{name}-z15", ph["z15Here"])
            out["phases"].append(ph)
    except Exception as e:
        out["error"] = f"{type(e).__name__}: {e}"[:300]
    finally:
        try:
            if area_id:
                dev.call(f"/areas/{area_id}", "DELETE")
            fx.network(dev, True)
            dev.call("/settings?server=" + urllib.parse.quote(server0, safe=""), "POST")
            dev.call(f"/firstrun/direct?on={1 if direct0 else 0}", "POST")
        except Exception as e:
            out["restoreError"] = f"{type(e).__name__}: {e}"[:300]
        srv.shutdown()
    return out


def t356_assertions(table):
    out = []
    t = table.get("t356")
    if not isinstance(t, dict):
        return out  # not run: the phases failed before it, or a table from before T356
    for k in ("error", "restoreError"):
        if t.get(k):
            out.append({"what": f"T356:{k}", "error": t[k]})
    d = t.get("download") or {}
    by = {int(z): n for z, n in (d.get("demFetchedByZoom") or {}).items()}
    if d.get("state") != "done" or any(by.get(z, 0) == 0 for z in (12, 13, 14, 15)) or not d.get("inPacks"):
        out.append({"what": "T356:download-skip", "download": d})
    for ph in t.get("phases") or []:
        row = f"t356/{ph.get('phase')}"
        if ph.get("errors"):
            out.append({"what": "T356:unread", "row": row, "errors": ph["errors"]})
            continue
        dem = ph.get("dem") or {}
        if dem.get("serves") != T356_AREA or dem.get("tilePathServes") != T356_AREA:
            out.append({"what": "T356:holdings", "row": row, "dem": dem})
        here = ph.get("z15Here") or []
        kinds = {h["kind"] for h in here if h.get("pack") == f"area:{T356_AREA}" and h.get("status") == 200}
        if "dem" not in kinds or "contours" not in kinds:
            out.append({"what": "T356:request-log", "row": row, "z15": here[:12]})
        if any(h.get("status") == 404 for h in here):
            out.append({"what": "T356:z15-404", "row": row, "z15": [h for h in here if h.get("status") == 404][:6]})
    if len(t.get("phases") or []) != 2 and not t.get("error"):
        out.append({"what": "T356:phases", "phases": len(t.get("phases") or [])})
    return out
# end T356


# T368
# The network where it is finer, behind what the phone holds (ADR 0066 decision 2). T356's stub (synthetic elevation to z15,
# declaring demMaxZoom 15; no vector tiles) is set as the server, the online tile cache emptied and the app restarted, then
# the camera put at z14 at `wide-only` (inside the coarse map, whose elevation stops at z11), with More detail from online on
# and then off (each after a cold start). Asserted with it on: elevation tiles were fetched behind the map (`GET /online`
# `behind.dem`: started, all landed or failed, none in flight at the end), at least one refresh was made and none ended
# early or was skipped, `GET /holdings` names the cache as what serves the z14 point's elevation tile, and the request log
# has z15 elevation tiles of the place answered from the map first and then from `cache` (the refresh). With it off: nothing
# was fetched behind and the coarse map serves. Pinned: the cache emptied and a cold start per phase, the camera moved away
# before each restart (the view kept at rest is not the one read); the settings it changes (server, direct reading,
# onlineDetail) read first and put back. It reads the clock only in its waits (the refresh is held until the camera rests and
# the batch settles: up to 60 s for `behind.dem` to show nothing in flight or waiting and no refresh running); it depends on
# no earlier part, only on the fixture this part installed.
def t368_behind(dev, max_s=60.0):
    t0 = time.time()
    b = None
    while time.time() - t0 < max_s:
        _, on, _ = dev.call("/online")
        b = get(on, "behind") or {}
        d = b.get("dem") or {}
        if not d.get("inFlight") and not d.get("waiting") and not b.get("refreshing") and time.time() - t0 > 8.0:
            break
        time.sleep(1.0)
    return b


def t368_collect(dev, raw_dir):
    out = {"phases": []}
    place = next(p for p in fx.PLACES if p["id"] == "wide-only")
    lat, lon = place["lat"], place["lon"]
    _, s0, _ = dev.call("/settings")
    _, d0, _ = dev.call("/firstrun/direct")
    server0 = (s0 or {}).get("server") or ""
    direct0 = bool((d0 or {}).get("enabled"))
    detail0 = (s0 or {}).get("onlineDetail")
    srv, port, log = t356_stub()
    try:
        fx.network(dev, True)
        dev.call("/firstrun/direct?on=0", "POST")
        dev.call("/settings?server=" + urllib.parse.quote(f"http://10.0.2.2:{port}", safe=""), "POST")
        for detail in (1, 0):
            dev.call(f"/settings?onlineDetail={detail}", "POST")
            dev.call("/camera?lat=46.85&lon=-121.76&zoom=9&bearing=0", "POST")
            time.sleep(2.0)
            dev.call("/online/cache/clear", "POST", timeout=120)
            dev.restart()
            time.sleep(4.0)
            _, b0, _ = dev.call("/online")
            _, pre, _ = dev.call("/stats/requests?limit=1")
            dev.call(f"/camera?lat={lat}&lon={lon}&zoom=14&bearing=0", "POST")
            b = t368_behind(dev)
            _, req, e1 = dev.call("/stats/requests?limit=1500")
            _, h, e2 = dev.call(f"/holdings?z=14&lat={lat}&lon={lon}")
            before = get(b0, "behind", "dem") or {}
            d = (b or {}).get("dem") or {}
            ph = {"onlineDetail": detail, "errors": [e for e in (e1, e2) if e],
                  "started": (d.get("started") or 0) - (before.get("started") or 0), "landed": (d.get("landed") or 0) - (before.get("landed") or 0),
                  "failed": (d.get("failed") or 0) - (before.get("failed") or 0), "inFlight": d.get("inFlight"), "waiting": d.get("waiting"),
                  "refreshes": (d.get("refreshes") or 0) - (before.get("refreshes") or 0),
                  "recent": [r for r in ((b or {}).get("recent") or []) if r.get("ended") or r.get("skipped")][-4:],
                  "serves": get(h, "dem", "serves")}
            req = req or {}
            new = (req.get("lines") or [])[-max(0, req.get("total", 0) - (pre or {}).get("total", 0)):]
            _, cx, cy = t356_tile(15, lat, lon)
            seen = {}
            for line in new:
                parts = line.get("path", "").split("?")[0].split("/")
                if len(parts) > 4 and parts[1] == "dem" and parts[2] == "15" and line.get("status") == 200:
                    tx, ty = int(parts[3]), int(parts[4].split(".")[0])
                    if abs(tx - cx) <= 2 and abs(ty - cy) <= 3:
                        seen.setdefault(f"{tx}/{ty}", []).append(line.get("pack"))
            ph["z15"] = seen
            ph["upgraded"] = sum(1 for v in seen.values() if v and v[0] != "cache" and "cache" in v[1:])
            if raw_dir:
                with open(os.path.join(raw_dir, f"t368-detail{detail}.json"), "w", encoding="utf-8", newline="\n") as fh:
                    json.dump({"behind": b, "holdings": h, "z15": seen}, fh, indent=1)
            out["phases"].append(ph)
    except Exception as e:
        out["error"] = f"{type(e).__name__}: {e}"[:300]
    finally:
        try:
            dev.call("/settings?server=" + urllib.parse.quote(server0, safe=""), "POST")
            dev.call(f"/firstrun/direct?on={1 if direct0 else 0}", "POST")
            dev.call(f"/settings?onlineDetail={0 if detail0 in (False, 0, '0') else 1}", "POST")
        except Exception as e:
            out["restoreError"] = f"{type(e).__name__}: {e}"[:300]
        srv.shutdown()
    return out


def t368_assertions(table):
    out = []
    t = table.get("t368")
    if not isinstance(t, dict):
        return out  # not run: the phases failed before it, or a table from before T368
    for k in ("error", "restoreError"):
        if t.get(k):
            out.append({"what": f"T368:{k}", "error": t[k]})
    phases = {p.get("onlineDetail"): p for p in t.get("phases") or []}
    if set(phases) != {0, 1} and not t.get("error"):
        out.append({"what": "T368:phases", "phases": sorted(phases)})
    on, off = phases.get(1), phases.get(0)
    for p in (on, off):
        if p and p.get("errors"):
            out.append({"what": "T368:unread", "onlineDetail": p["onlineDetail"], "errors": p["errors"]})
    if on:
        if not on.get("started") or on.get("landed", 0) + on.get("failed", 0) != on.get("started") or on.get("inFlight") or on.get("waiting"):
            out.append({"what": "T368:fetch-behind", "on": {k: on.get(k) for k in ("started", "landed", "failed", "inFlight", "waiting")}})
        if not on.get("refreshes") or on.get("recent"):
            out.append({"what": "T368:refresh", "refreshes": on.get("refreshes"), "endedOrSkipped": on.get("recent")})
        if on.get("serves") != "cache":
            out.append({"what": "T368:holdings-on", "serves": on.get("serves")})
        if not on.get("upgraded"):
            out.append({"what": "T368:upgraded", "z15": dict(list((on.get("z15") or {}).items())[:6])})
    if off:
        if off.get("started"):
            out.append({"what": "T368:setting-off-fetched", "started": off.get("started")})
        if off.get("serves") != "rainier-wide":
            out.append({"what": "T368:holdings-off", "serves": off.get("serves")})
    return out
# end T368


# T359
# The coverage readers take the lookup (ADR 0066 build task 5): the status chip, the map's "Download this area" button, search's
# coverage and the first-run rule, against `GET /holdings` (`tilePathServes`, what the tile paths serve). With the network off
# (airplane mode) and then on, each after the online tile cache and the direct sources' block cache are emptied with the app
# stopped (so nothing kept answers), at the four places at z12 (inside the maps' zooms) and z7.5 (the elevation from the maps,
# the map's roads below their first zoom), read `GET /online`, `GET /holdings` and `GET /firstrun`. Asserted per row: the
# chip's network test (`chip.network`) is the phase's (false in airplane mode); the chip says "on this phone" only where no
# tile is served by the cache and, online, a map serves the vector tile; where the cache serves a tile the chip does not say
# "on this phone"; online, where nothing on the phone serves the vector tile, the chip names the network (T341's z7.5 and
# T339's outside), and offline outside both maps it says "no data here"; the button is shown only online, and reads "Already
# on this phone" only where the chip says "on this phone"; the first-run card shows nowhere, and the map's message is
# "outside" only offline outside both maps at z12 (`none` elsewhere; at z7.5 the view reaches the maps). The chip's word while a deeper tile is fetched behind a map:
# the map serves now, so the map's (`OnlineView.sourceOf`); a cached elevation tile at a zoom a map stores is the map's too
# (T368's rule; `GET /holdings` names the cache there, ranking it by the source's depth: T368's follow-up). Then search (`GET /search`, the index built first) for a name in
# the coarse map from a point inside it only (found, `vector`) and from outside both (a sentence, no `vector`), each phase.
# Last, offline with no server and the phone's own reading of the sources on (T342's and T332's follow-up): both maps off,
# the map says "No offline map covers this view" outside them and the chip "a map here is off" over them. Pinned: it switches
# the network and restarts the app itself and empties the caches; the server, direct reading and the maps' off switches are
# read first and put back; the clock only in its waits. It depends on no earlier part, only on the fixture this part installed.
T359_ZOOMS = [12, 7.5]
T359_SEARCH = "Longmire"  # in the coarse map's index (a public place in the Rainier fixture)


def t359_row(dev, place, net, zoom, raw_dir):
    lat, lon = place["lat"], place["lon"]
    dev.call(f"/camera?lat={lat}&lon={lon}&zoom={zoom}&bearing=0", "POST")
    time.sleep(6.0 if net == "on" else 4.0)
    _, on, e1 = dev.call("/online")
    _, h, e2 = dev.call(f"/holdings?z={zoom}&lat={lat}&lon={lon}")
    _, fr, e3 = dev.call("/firstrun")
    if raw_dir:
        with open(os.path.join(raw_dir, f"t359-{net}-{place['id']}-z{zoom}.json"), "w", encoding="utf-8", newline="\n") as fh:
            json.dump({"online": on, "holdings": h, "firstrun": fr}, fh, indent=1)
    c = get(on, "chip") or {}
    return {"network": net, "place": place["id"], "inside": place["inside"], "zoom": zoom, "errors": [e for e in (e1, e2, e3) if e],
            "chip": c.get("text"), "source": c.get("source"), "column": c.get("column"), "chipNetwork": c.get("network"),
            "served": c.get("served"), "offAhead": c.get("offAhead"),
            "button": {k: get(c, "download", k) for k in ("shown", "text", "enabled")}, "verdict": get(c, "held", "verdict"),
            "holdings": {r: {"serves": get(h, r, "serves"), "tilePathServes": get(h, r, "tilePathServes")} for r in ("vector", "dem")},
            "allowed": get(h, "online", "allowed"), "mapMessage": get(fr, "mapMessage"), "cardVisible": get(fr, "cardVisible"),
            "noPack": get(fr, "noPack")}


def t359_search(dev, net):
    dev.call("/search/build", "POST", timeout=120)
    t0, st = time.time(), None
    while time.time() - t0 < 120:
        _, st, _ = dev.call("/search/status")
        if get(st, "status") == "ready":
            break
        time.sleep(2.0)
    out = []
    for pid in ("wide-only", "outside"):
        p = next(x for x in fx.PLACES if x["id"] == pid)
        _, b, err = dev.call(f"/search?q={T359_SEARCH}&lat={p['lat']}&lon={p['lon']}&limit=3", timeout=120)
        out.append({"network": net, "place": pid, "error": err, "reason": get(b, "reason"), "coverage": get(b, "coverage"),
                    "sentence": get(b, "sentence"), "hits": [x.get("name") for x in (get(b, "hits") or []) if isinstance(x, dict)][:3]})
    return out


def t359_collect(dev, raw_dir):
    out = {"rows": [], "search": [], "allOff": []}
    wide, fine = fx.MAPS[0]["name"], fx.MAPS[1]["name"]
    _, s0, _ = dev.call("/settings")
    _, d0, _ = dev.call("/firstrun/direct")
    server0 = (s0 or {}).get("server") or ""
    direct0 = bool((d0 or {}).get("enabled"))
    try:
        for net in ("off", "on"):
            fx.network(dev, net == "on")
            dev.call("/online/cache/clear", "POST", timeout=120)
            dev.adb("shell", "am", "force-stop", fx.PACKAGE, timeout=30)
            dev.runas("rm -rf cache/sources; true")
            dev.restart()
            for place in fx.PLACES:
                for z in T359_ZOOMS:
                    out["rows"].append(t359_row(dev, place, net, z, raw_dir))
            out["search"] += t359_search(dev, net)
        # Offline, no server, the phone's own reading on, both maps off.
        fx.network(dev, False)
        dev.call("/settings?server=", "POST")
        dev.call("/firstrun/direct?on=1", "POST")
        for name in (fine, wide):
            dev.call(f"/areas/rows/off?name={name}&off=1", "POST", timeout=120)
        dev.restart()
        for pid in ("inside-both", "outside"):
            out["allOff"].append(t359_row(dev, next(p for p in fx.PLACES if p["id"] == pid), "all-off", ZOOM, raw_dir))
    except Exception as e:
        out["error"] = f"{type(e).__name__}: {e}"[:300]
    finally:
        try:
            for name in (fine, wide):
                dev.call(f"/areas/rows/off?name={name}&off=0", "POST", timeout=120)
            dev.call("/settings?server=" + urllib.parse.quote(server0, safe=""), "POST")
            dev.call(f"/firstrun/direct?on={1 if direct0 else 0}", "POST")
            fx.network(dev, True)
        except Exception as e:
            out["restoreError"] = f"{type(e).__name__}: {e}"[:300]
    return out


T359_PHONE = ("on this phone",)
T359_NETWORK = ("from your server", "from public sources", "from a nearby phone", "viewed earlier")


def t359_assertions(table):
    out = []
    t = table.get("t359")
    if not isinstance(t, dict):
        return out  # not run: the phases failed before it, or a table from before T359
    for k in ("error", "restoreError"):
        if t.get(k):
            out.append({"what": f"T359:{k}", "error": t[k]})
    rows = t.get("rows") or []
    if len(rows) != 2 * len(fx.PLACES) * len(T359_ZOOMS) and not t.get("error"):
        out.append({"what": "T359:rows", "rows": len(rows)})
    for r in rows:
        row = f"t359/{r['network']}/{r['place']}/z{r['zoom']}"
        if r.get("errors"):
            out.append({"what": "T359:unread", "row": row, "errors": r["errors"]})
            continue
        on = r["network"] == "on"
        hv, hd = (r.get("holdings") or {}).get("vector") or {}, (r.get("holdings") or {}).get("dem") or {}
        # A cached elevation tile at a zoom a map stores is the map's to the chip (T368's rule, `served.demSameDepth`).
        cache = hv.get("tilePathServes") == "cache" or (hd.get("tilePathServes") == "cache" and not (r.get("served") or {}).get("demSameDepth"))
        src, btn = r.get("source"), r.get("button") or {}
        if r.get("chipNetwork") is not on or r.get("allowed") is not on:
            out.append({"what": "T359:network-test", "row": row, "chip": r.get("chipNetwork"), "holdingsAllowed": r.get("allowed")})
        if src in T359_PHONE and (cache or (on and hv.get("tilePathServes") is None)):
            out.append({"what": "T359:on-phone-not-served", "row": row, "chip": r.get("chip"), "holdings": r.get("holdings")})
        if on and hv.get("tilePathServes") is None and src not in T359_NETWORK:
            out.append({"what": "T359:network-not-named", "row": row, "chip": r.get("chip"), "holdings": r.get("holdings")})
        outside = r["place"] == "outside" and r["zoom"] >= 8  # at z7.5 the view reaches the maps (the message says nothing then)
        if not on and outside and src != "no data here":
            out.append({"what": "T359:outside-offline", "row": row, "chip": r.get("chip")})
        if btn.get("shown") and not on:
            out.append({"what": "T359:button-offline", "row": row, "button": btn})
        if str(btn.get("text") or "").startswith("Already") and btn.get("shown") and src not in T359_PHONE:
            out.append({"what": "T359:already-not-on-phone", "row": row, "chip": r.get("chip"), "button": btn, "holdings": r.get("holdings")})
        want = "outside" if (not on and outside) else "none"
        if r.get("cardVisible") or r.get("noPack") or r.get("mapMessage") != want:
            out.append({"what": "T359:map-message", "row": row, "mapMessage": r.get("mapMessage"), "want": want, "card": r.get("cardVisible")})
    for s in t.get("search") or []:
        row = f"t359/search/{s['network']}/{s['place']}"
        if s.get("error"):
            out.append({"what": "T359:search-unread", "row": row, "error": s["error"]})
        elif s["place"] == "wide-only" and (s.get("reason") != "found" or T359_SEARCH not in (s.get("hits") or [])):
            out.append({"what": "T359:search-inside", "row": row, "search": s})
        elif s["place"] == "outside" and (s.get("coverage") == "vector" or not s.get("sentence")):
            out.append({"what": "T359:search-outside", "row": row, "search": s})
    alloff = {r["place"]: r for r in t.get("allOff") or []}
    if not t.get("error"):
        i, o = alloff.get("inside-both") or {}, alloff.get("outside") or {}
        if i.get("source") != "a map here is off" or i.get("chipNetwork") is not False:
            out.append({"what": "T359:all-off-chip", "chip": i.get("chip"), "network": i.get("chipNetwork")})
        if o.get("mapMessage") != "outside" or o.get("cardVisible"):
            out.append({"what": "T359:all-off-message", "mapMessage": o.get("mapMessage"), "card": o.get("cardVisible")})
    return out
# end T359


def main(argv=None):
    ap = argparse.ArgumentParser(description="T339: the two-maps table (the sweep's part two-maps)")
    ap.add_argument("--serial", required=True)
    ap.add_argument("--port", type=int, default=8696)
    ap.add_argument("--out", default=os.path.join("out", "two-maps", "table.json"))
    ap.add_argument("--from", dest="from_dir", default="", help="a folder with the fixture built from the same recipe, copied instead of a build")
    ap.add_argument("--wait-ms", type=int, default=30000,
                    help="each view's ?wait= (default 30000: the online horizon outside both maps took 15 to 30 s on a task emulator)")
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
