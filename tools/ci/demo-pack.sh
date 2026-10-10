#!/usr/bin/env bash
# CI helper (T48): builds the tiny Rainier region pack the browser demo needs, from scratch, in out/.
#
#   tools/ci/demo-pack.sh [maxZoom]          # default 10; run from the repo root after :devtool:installDist
#
# 1. DEM: `devtool tiles --area rainier --min-zoom 8 --max-zoom N --pmtiles out/rainier-dem.pmtiles`
#    (3DEP and Copernicus over the network; this also warms the cache the world tour reads).
# 2. OSM: the demo page needs a vector pack too, and tilebuild (Planetiler) would download a state extract
#    plus ~1.45 GB of aux data. Instead `devtool serve --live` builds one from OpenFreeMap tiles through
#    POST /build (OpenMapTiles schema), which is renamed to out/rainier-osm.pmtiles; the check then opens
#    the page with ?osm=openmaptiles, the style that matches that schema.
# Prints one JSON line per step with timings. Exits non-zero when a step fails or runs out of time.
set -euo pipefail

MAX_ZOOM="${1:-10}"
PORT="${DYSTOPO_CI_PORT:-8687}"
BUILD_TIMEOUT_S="${DYSTOPO_CI_BUILD_TIMEOUT_S:-300}"
DEVTOOL=devtool/build/install/devtool/bin/devtool
PY="${PYTHON:-python3}"
BBOX="-121.86,46.78,-121.66,46.93"   # areas.json rainier

mkdir -p out
[ -x "$DEVTOOL" ] || { echo "run ./gradlew :devtool:installDist first" >&2; exit 2; }

t0=$(date +%s)
"$DEVTOOL" tiles --area rainier --min-zoom 8 --max-zoom "$MAX_ZOOM" --pmtiles out/rainier-dem.pmtiles > out/ci-tiles.json
echo "{\"step\":\"dem\",\"seconds\":$(( $(date +%s) - t0 )),\"bytes\":$(stat -c %s out/rainier-dem.pmtiles)}"

t0=$(date +%s)
"$DEVTOOL" serve --live --port "$PORT" > out/ci-serve.log 2>&1 &
SERVER=$!
trap 'kill "$SERVER" 2>/dev/null || true' EXIT
for _ in $(seq 1 60); do
  curl -sf "http://127.0.0.1:$PORT/live/info" > /dev/null && break
  sleep 1
done
JOB=$(curl -sf -X POST "http://127.0.0.1:$PORT/build?name=ci&bbox=$BBOX&minZoom=8&maxZoom=$MAX_ZOOM&overwrite=1")
ID=$("$PY" -c 'import json,sys; print(json.loads(sys.argv[1])["id"])' "$JOB")
STATUS=queued
deadline=$(( $(date +%s) + BUILD_TIMEOUT_S ))
while [ "$(date +%s)" -lt "$deadline" ]; do
  STATE=$(curl -sf "http://127.0.0.1:$PORT/build/$ID")
  STATUS=$("$PY" -c 'import json,sys; print(json.loads(sys.argv[1])["status"])' "$STATE")
  case "$STATUS" in done|failed|cancelled) break ;; esac
  sleep 2
done
echo "$STATE" > out/ci-build.json
if [ "$STATUS" != done ]; then
  echo "OSM pack build ended as '$STATUS' (see out/ci-build.json, out/ci-serve.log)" >&2
  exit 1
fi
kill "$SERVER" 2>/dev/null || true
wait "$SERVER" 2>/dev/null || true
mv -f out/ci-outdoors.pmtiles out/rainier-osm.pmtiles
rm -f out/ci-dem.pmtiles
echo "{\"step\":\"osm\",\"seconds\":$(( $(date +%s) - t0 )),\"bytes\":$(stat -c %s out/rainier-osm.pmtiles)}"
