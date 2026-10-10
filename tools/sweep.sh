#!/usr/bin/env bash
# The JVM and browser parts of the post-wave sweep (T71), for CI and machines without an emulator; the
# full sweep with the device parts is tools/sweep.ps1 (same parts, same output layout, same summary).
#
#   bash tools/sweep.sh [--out DIR] [--zooms 9,12] [--skip a,b] [--only a,b] [--base-port 8980]
#                       [--channel chrome|""] [--pack-zoom N] [--views-file F] [--live-views-file F]
#                       [--demo-timeout S] [--live-timeout S]
#
# Parts: check, server, notices, tour, demo-static, demo-serve, live, sync-sim (see tools/sweep.ps1); the
# device parts are recorded as skipped. Writes out/sweep-<date>/ (steps.tsv, logs, each part's JSON,
# summary.json, sheet.png via tools/sweep-summary.py) and exits 1 when any part failed.
#
# Data: the demo parts need out/rainier-dem.pmtiles and an OSM pack. When the DEM pack is missing (a fresh
# CI checkout) tools/ci/demo-pack.sh builds a small one (DEM to --pack-zoom, default 13, which the live
# Rainier view needs to read |src=pack, and an OpenMapTiles pack from OpenFreeMap as out/rainier-osm.pmtiles).
# Without out/rainier-outdoors.pmtiles the static page is opened with ?osm=openmaptiles, serve is given
# --osm out/rainier-osm.pmtiles, and the demo tour drops the views that need the outdoors profile (labels
# outdoors-* and phone-*, or req= poi-markers, trails or cliffs); the part's note says how many.
#
# Servers: demo-server.py on --base-port, serve on +1, serve --live on +2, each stopped by its recorded PID.
# Ports 8765, 8686 and 8687 are refused (the coordinator's viewer and the app's ports).
set -uo pipefail

cd "$(dirname "$0")/.."
REPO=$(pwd)
DATE=$(date +%Y%m%d-%H%M)
OUT=""
ZOOMS="9,12"
SKIP=""
ONLY=""
BASE_PORT=8980
CHANNEL="chrome"
PACK_ZOOM=13
DEMO_TIMEOUT=120
LIVE_TIMEOUT=120
VIEWS_FILE="tools/demo-tour.txt"
LIVE_VIEWS_FILE=""
PY="${PYTHON:-python3}"
command -v "$PY" > /dev/null 2>&1 || PY=python

while [ $# -gt 0 ]; do
  case "$1" in
    --out) OUT="$2"; shift 2 ;;
    --date) DATE="$2"; shift 2 ;;
    --zooms) ZOOMS="$2"; shift 2 ;;
    --skip) SKIP="$2"; shift 2 ;;
    --only) ONLY="$2"; shift 2 ;;
    --base-port) BASE_PORT="$2"; shift 2 ;;
    --channel) CHANNEL="$2"; shift 2 ;;
    --pack-zoom) PACK_ZOOM="$2"; shift 2 ;;
    --views-file) VIEWS_FILE="$2"; shift 2 ;;
    --demo-timeout) DEMO_TIMEOUT="$2"; shift 2 ;;
    --live-timeout) LIVE_TIMEOUT="$2"; shift 2 ;;
    --live-views-file) LIVE_VIEWS_FILE="$2"; shift 2 ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "unknown option $1" >&2; exit 2 ;;
  esac
done

ALL_PARTS="check server notices tour demo-static demo-serve live sync-sim"
for p in ${SKIP//,/ } ${ONLY//,/ }; do
  case " $ALL_PARTS " in *" $p "*) ;; *) echo "unknown part '$p' (parts: $ALL_PARTS)" >&2; exit 2 ;; esac
done
[ -n "$OUT" ] || OUT="out/sweep-$DATE"
mkdir -p "$OUT"
OUT=$(cd "$OUT" && pwd)
STEPS="$OUT/steps.tsv"
: > "$STEPS"

for port in "$BASE_PORT" $((BASE_PORT + 1)) $((BASE_PORT + 2)); do
  case "$port" in 8765|8686|8687) echo "port $port is reserved for the coordinator; pick another --base-port" >&2; exit 2 ;; esac
  if (exec 3<>"/dev/tcp/127.0.0.1/$port") 2> /dev/null; then echo "port $port is already in use; pick another --base-port" >&2; exit 2; fi
done

want() {
  local p="$1"
  if [ -n "$ONLY" ]; then case ",$ONLY," in *",$p,"*) ;; *) return 1 ;; esac; fi
  case ",$SKIP," in *",$p,"*) return 1 ;; esac
  return 0
}

step() {  # name code seconds note
  printf '%s\t%s\t%s\t%s\n' "$1" "$2" "$3" "${4:-}" >> "$STEPS"
  printf '[sweep] %-12s %-6s %6s s %s\n' "$1" "$([ "$2" = 0 ] && echo ok || { [ "$2" = skip ] && echo skip || echo "exit $2"; })" "$3" "${4:-}"
}

# part NAME COMMAND...: runs the command (a shell function) and records its exit code and time, with
# $NOTE (set by the caller just before, then cleared) as the step's note.
NOTE=""
part() {
  local name="$1"; shift
  if ! want "$name"; then step "$name" skip 0 "not selected"; NOTE=""; return 0; fi
  local t0=$(date +%s.%N) code
  "$@"; code=$?
  step "$name" "$code" "$(awk -v a="$t0" -v b="$(date +%s.%N)" 'BEGIN { printf "%.1f", b - a }')" "$NOTE"
  NOTE=""
}

declare -A SERVERS=()
start_server() {  # name port ready-path command...
  local name="$1" port="$2" ready="$3"; shift 3
  "$@" > "$OUT/$name.log" 2> "$OUT/$name.err.log" &
  SERVERS[$name]=$!
  for _ in $(seq 1 180); do
    kill -0 "${SERVERS[$name]}" 2> /dev/null || { echo "$name exited early (see $name.err.log)" >&2; return 1; }
    curl -sf -o /dev/null "http://127.0.0.1:$port$ready" && return 0
    sleep 1
  done
  echo "$name did not answer on port $port" >&2
  return 1
}
stop_server() {
  local pid="${SERVERS[$1]:-}"
  [ -n "$pid" ] || return 0
  kill "$pid" 2> /dev/null; wait "$pid" 2> /dev/null
  unset "SERVERS[$1]"
}
cleanup() { for n in "${!SERVERS[@]}"; do stop_server "$n"; done; }
trap cleanup EXIT

GRADLE="./gradlew"
[ -x "$GRADLE" ] || chmod +x gradlew 2> /dev/null || GRADLE="bash gradlew"
DEVTOOL="$REPO/devtool/build/install/devtool/bin/devtool"
printf '{"host": "%s", "startedAt": "%s", "noDevice": true, "commit": "%s"}\n' \
  "$(uname -s | tr 'A-Z' 'a-z')" "$(date +%Y-%m-%dT%H:%M:%S)" "$(git rev-parse --short HEAD 2> /dev/null)" > "$OUT/meta.json"

run_check()   { $GRADLE check --continue > "$OUT/check.log" 2> "$OUT/check.err.log"; }
run_server()  { $GRADLE :server:installDist > "$OUT/server.log" 2> "$OUT/server.err.log"; }
run_notices() { "$PY" -I tools/notices.py --check > "$OUT/notices.log" 2> "$OUT/notices.err.log"; }

run_tour() {
  # Public areas only (the tour's default also reads devtool.local.json in the main checkout).
  local areas
  areas=$("$PY" -I -c 'import json; print(",".join(k for k, v in json.load(open("areas.json"))["areas"].items() if v.get("expectedElevation")))')
  "$DEVTOOL" tour --zooms "$ZOOMS" --areas "$areas" --out "$OUT/tour" > "$OUT/tour.json" 2> "$OUT/tour.err.log"
}

demo_check() {  # part url views-file [extra args]
  local part="$1" url="$2" views="$3"; shift 3
  "$PY" -I tools/demo-check.py --channel "$CHANNEL" --url "$url" --views-file "$views" --out "$OUT/$part/view.png" "$@" \
    > "$OUT/$part.json" 2> "$OUT/$part.err.log"
}

# The demo tour views to run: all of them, or without an outdoors pack those that do not need its layers.
OUTDOORS=1
demo_views() {
  if [ $OUTDOORS = 1 ]; then echo "$VIEWS_FILE"; return; fi
  local f="$OUT/demo-views-openmaptiles.txt"
  grep -v -E '^\s*#' "$VIEWS_FILE" | grep -v -E ':(outdoors-|phone-)|req=[^ ]*(poi-markers|trails|cliffs)' > "$f"
  echo "$f"
}
demo_note() {
  [ $OUTDOORS = 1 ] && return
  local all kept
  all=$(grep -c -v -E '^\s*(#|$)' "$VIEWS_FILE"); kept=$(grep -c -v -E '^\s*$' "$(demo_views)")
  echo "no outdoors pack: OpenMapTiles archive, $kept of $all views (outdoors-only views dropped)"
}

run_demo_static() {
  start_server demo-server "$BASE_PORT" /tools/demo/ "$PY" -I tools/demo-server.py "$BASE_PORT" || { stop_server demo-server; return 1; }
  local q=""
  [ $OUTDOORS = 1 ] || q="?osm=openmaptiles"
  demo_check demo-static "http://localhost:$BASE_PORT/tools/demo/$q" "$(demo_views)" --timeout "$DEMO_TIMEOUT"; local c=$?
  stop_server demo-server; return $c
}

run_demo_serve() {
  local p=$((BASE_PORT + 1)) osm=()
  [ $OUTDOORS = 1 ] || osm=(--osm out/rainier-osm.pmtiles)
  DYSTOPO_SYNC_DB="$OUT/serve-peer.db" start_server serve "$p" /stats "$DEVTOOL" serve --port "$p" "${osm[@]}" || { stop_server serve; return 1; }
  demo_check demo-serve "http://localhost:$p/" "$(demo_views)" --timeout "$DEMO_TIMEOUT"; local c=$?
  stop_server serve; return $c
}

run_live() {
  local p=$((BASE_PORT + 2)) views="$LIVE_VIEWS_FILE"
  if [ -z "$views" ]; then
    views="$OUT/live-views.txt"
    cat > "$views" << 'EOF'
12/46.853/-121.760:rainier|src=pack
12/36.10/-112.11:grand-canyon|src=live-3dep
12/46.537/7.963:jungfrau|src=live-swissalti
12/61.636/8.313:galdhopiggen|src=live-kartverket
12/47.074/12.695:grossglockner|src=live-bev
12/49.851/-123.005:garibaldi-hrdem
12/-43.595/170.142:aoraki|src=live-linz
11/27.988/86.925:everest|src=live-copernicus
EOF
  fi
  DYSTOPO_SYNC_DB="$OUT/live-peer.db" start_server serve-live "$p" /stats "$DEVTOOL" serve --live --port "$p" || { stop_server serve-live; return 1; }
  demo_check live "http://localhost:$p/?live=1" "$views" --timeout "$LIVE_TIMEOUT"; local c=$?
  stop_server serve-live; return $c
}

run_sync_sim() {
  "$DEVTOOL" sync-sim --peers 3 --ops 2000 --seed 42 --partitions 5 --reorder --seeds 20 --skew-days 2 --crashes 3 --page 50 \
    > "$OUT/sync-sim.json" 2> "$OUT/sync-sim.err.log"
}

part check run_check
part server run_server
part notices run_notices

need_devtool=0
for p in tour demo-serve live sync-sim; do want "$p" && need_devtool=1; done
if [ $need_devtool = 1 ] || { want demo-static && [ ! -f out/rainier-dem.pmtiles ]; }; then
  $GRADLE :devtool:installDist > "$OUT/devtool-install.log" 2>&1 || echo "[sweep] :devtool:installDist failed" >&2
fi
if { want demo-static || want demo-serve || want live; } && [ ! -f out/rainier-dem.pmtiles ]; then
  echo "[sweep] no out/rainier-dem.pmtiles: building a small Rainier pack (z$PACK_ZOOM)"
  DYSTOPO_CI_PORT=$((BASE_PORT + 3)) bash tools/ci/demo-pack.sh "$PACK_ZOOM" > "$OUT/demo-pack.log" 2>&1 || echo "[sweep] demo pack build failed (see demo-pack.log)" >&2
fi

if [ -f out/rainier-outdoors.pmtiles ]; then OUTDOORS=1; else OUTDOORS=0; fi

part tour run_tour
NOTE=$(demo_note); part demo-static run_demo_static
NOTE=$(demo_note); part demo-serve run_demo_serve
part live run_live
part sync-sim run_sync_sim
for p in device-boot device-tour probe release; do step "$p" skip 0 "no device"; done

cleanup
"$PY" -I tools/sweep-summary.py "$OUT" --channel "$CHANNEL"
