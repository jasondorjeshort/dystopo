#!/usr/bin/env python3
"""Pulls USGS NHD flowlines (high resolution, public domain) for an area and writes them as a CSV of points (T179).

The truth table of `devtool water --truth <csv>` compares the class Dystopo derives from the terrain against the flow
code USGS gave each flowline (FCode 46006 perennial, 46003 intermittent, 46007 ephemeral, 46000 unclassified; set
from aerial photos and maps at compile time, not measured). Read-only: one ArcGIS REST query per page against The
National Map's NHD service (layer 6, "Flowline - Large Scale"), standard library only.

Usage (repo root):
  python -I tools/nhd-flowlines.py --areas areas.json --area rainier --raw-dir <empty dir> --out <file>.csv
  python -I tools/nhd-flowlines.py --bbox=-121.86,46.78,-121.66,46.93 --out <file>.csv [--step 25]

Output CSV columns: line (NHD permanent identifier), fcode, ftype, name (GNIS, may be empty), seq (point index along
the line), lon, lat. Each line is densified to a point every --step metres (25 by default) so a point stands for
--step metres of channel and a table over points is weighted by length. The raw GeoJSON pages go to --raw-dir when
given (keep it an empty directory of its own). Prints one JSON summary line to stdout. The bbox is the area's;
flowlines crossing its edge come whole, so points outside the box are dropped.
"""
import argparse
import csv
import json
import math
import os
import sys
import time
import urllib.parse
import urllib.request

SERVICE = "https://hydro.nationalmap.gov/arcgis/rest/services/nhd/MapServer/6/query"
PAGE = 1000
FCODE_WORDS = {46006: "perennial", 46003: "intermittent", 46007: "ephemeral", 46000: "unclassified"}


def fetch(params, retries=3):
    url = SERVICE + "?" + urllib.parse.urlencode(params)
    last = None
    for attempt in range(retries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "dystopo-nhd-flowlines/1 (truth table, read-only)"})
            with urllib.request.urlopen(req, timeout=120) as r:
                return r.read()
        except Exception as e:  # network trouble: retry with backoff
            last = e
            time.sleep(2 * (attempt + 1))
    raise SystemExit(json.dumps({"error": "NHD service unreachable", "detail": str(last)}))


def haversine(lon1, lat1, lon2, lat2):
    r = 6371008.8
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2 - p1, math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))


def densify(coords, step):
    """Points every `step` metres along the line, the first vertex included."""
    out = [tuple(coords[0])]
    carry = 0.0
    for (x1, y1), (x2, y2) in zip(coords, coords[1:]):
        d = haversine(x1, y1, x2, y2)
        if d <= 0:
            continue
        pos = step - carry
        while pos <= d:
            t = pos / d
            out.append((x1 + (x2 - x1) * t, y1 + (y2 - y1) * t))
            pos += step
        carry = (carry + d) % step
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--areas", help="areas.json (with --area)")
    ap.add_argument("--area", help="named area in --areas")
    ap.add_argument("--bbox", help="west,south,east,north in degrees (instead of --area)")
    ap.add_argument("--out", required=True, help="CSV to write")
    ap.add_argument("--raw-dir", help="directory for the raw GeoJSON pages (an empty one of its own)")
    ap.add_argument("--step", type=float, default=25.0, help="metres between points along a line")
    a = ap.parse_args()
    if a.bbox:
        box = [float(v) for v in a.bbox.split(",")]
    elif a.areas and a.area:
        with open(a.areas, encoding="utf-8") as f:
            box = json.load(f)["areas"][a.area]["bbox"]
    else:
        ap.error("give --bbox, or --areas and --area")
    w, s, e, n = box
    base = {
        "where": "1=1", "geometry": f"{w},{s},{e},{n}", "geometryType": "esriGeometryEnvelope", "inSR": "4326",
        "spatialRel": "esriSpatialRelIntersects", "outFields": "permanent_identifier,fcode,ftype,gnis_name",
        "outSR": "4326", "orderByFields": "OBJECTID", "f": "geojson",
    }
    features = []
    offset = 0
    pages = 0
    while True:
        body = fetch(dict(base, resultOffset=offset, resultRecordCount=PAGE))
        pages += 1
        if a.raw_dir:
            os.makedirs(a.raw_dir, exist_ok=True)
            with open(os.path.join(a.raw_dir, f"page-{pages:03d}.geojson"), "wb") as f:
                f.write(body)
        page = json.loads(body)
        got = page.get("features", [])
        features += got
        if len(got) < PAGE and not page.get("exceededTransferLimit") and not page.get("properties", {}).get("exceededTransferLimit"):
            break
        offset += len(got)
        if not got:
            break

    rows = 0
    by_code = {}
    lines = set()
    with open(a.out, "w", newline="", encoding="utf-8") as f:
        wr = csv.writer(f)
        wr.writerow(["line", "fcode", "ftype", "name", "seq", "lon", "lat"])
        for ft in features:
            p = ft.get("properties") or {}
            g = ft.get("geometry") or {}
            parts = g.get("coordinates") or []
            if g.get("type") == "LineString":
                parts = [parts]
            elif g.get("type") != "MultiLineString":
                continue
            ident = p.get("permanent_identifier") or str(len(lines))
            code = p.get("fcode")
            seq = 0
            for part in parts:
                if len(part) < 2:
                    continue
                for lon, lat in densify([c[:2] for c in part], a.step):
                    if not (w <= lon <= e and s <= lat <= n):
                        continue
                    wr.writerow([ident, code, p.get("ftype"), p.get("gnis_name") or "", seq, f"{lon:.6f}", f"{lat:.6f}"])
                    seq += 1
                    rows += 1
                    by_code[code] = by_code.get(code, 0) + 1
            if seq:
                lines.add(ident)
    print(json.dumps({
        "bbox": box, "pages": pages, "features": len(features), "linesInBox": len(lines), "points": rows,
        "stepM": a.step, "pointsByFcode": {f"{k} {FCODE_WORDS.get(k, 'other')}": v for k, v in sorted(by_code.items(), key=lambda kv: str(kv[0]))},
        "out": a.out, "source": "USGS National Hydrography Dataset, high resolution (public domain)",
    }))


if __name__ == "__main__":
    sys.exit(main())
