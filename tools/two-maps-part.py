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
