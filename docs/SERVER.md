# Dystopo server

The always-on peer as a program you can deploy: the routes of `devtool serve --live` (tiles from packs,
else rendered live; pack builds; pack downloads; the sync peer; attribution) plus a config file, JSON
logs, `/healthz`, a pack catalog, an optional bearer token, a CORS allowlist and a graceful shutdown.

Code: `server/` (the program: `Main.kt`, `ServerConfig.kt`, `Gate.kt`, `JsonLog.kt`, `Metrics.kt`) over `serverlib/`
(the routes and sources, shared with `devtool serve`: `LiveTiles`, `BuildRoutes`, `ServeStatic`,
`SyncRoutes`, `PackCatalog`, the elevation sources). Both modules use the Kotlin package
`io.github.jasondorjeshort.dystopo.server` (T58 renamed `serverlib` from `...devtool`).

## Run it locally

JDK 21.

```
./gradlew :server:installDist
server/build/install/server/bin/server                          # defaults: 0.0.0.0:8080, ./cache, ./out
server/build/install/server/bin/server --config my-server.json  # or DYSTOPO_SERVER_CONFIG=my-server.json
server/build/install/server/bin/server --print-config           # the effective config (token masked), then exit
```

On Windows the launcher is `bin\server.bat`. Open `http://localhost:8080/` for the browser map (the same
page as `devtool serve --live`, shipped in the distribution under `web/`), `/phone` for the phone style.
To share the download cache with a checkout, point `cacheDir` at it (or run from the repo root, where
`cache/` and `out/` are the defaults).

The distribution is `bin/` (start scripts), `lib/` (jars: our modules, Kotlin stdlib,
kotlinx-serialization, SQLDelight, sqlite-jdbc; all Apache-2.0), `web/index.html` and `config/server.example.json`
(every key with its default). `profile=outdoors` builds also need a tilebuild install
(`./gradlew :tilebuild:installDist`; `tilebuildHome` below).

## Docker

The image builds from the repository root in two stages (T58): a JDK stage runs
`./gradlew :server:installDist :tilebuild:installDist`, and the runtime stage copies the server to
`/opt/dystopo` and tilebuild to `/opt/tilebuild` (`DYSTOPO_TILEBUILD_HOME`), so `profile=outdoors` builds
work in the container. `server/Dockerfile.dockerignore` keeps `cache/`, `out/` and build outputs out of the
context (BuildKit, the default builder, reads it).

```
docker build -f server/Dockerfile -t dystopo-server .
docker run -d --name dystopo -p 8080:8080 \
  -v dystopo-cache:/data/cache -v dystopo-out:/data/out \
  -e DYSTOPO_SERVER_TOKEN=change-me \
  dystopo-server
```

- Base `eclipse-temurin:21-jre` (the JRE is GPL-2.0 with the classpath exception, which does not reach our
  code) plus `fonts-dejavu-core` so headless AWT can draw the sprite sheet's marker letters. Runs as an
  unprivileged user (uid 10001).
- Working directory `/data`: the default `cache` and `out` are the two volumes. `/data/cache` grows with
  use (live DEM tiles, OpenFreeMap tiles, Copernicus and 3DEP downloads, Overpass extracts; gigabytes over
  time); `/data/out` holds the packs, their `.sha256` sidecars and the sync replica (`out/sync/peer.db`).
- A config file mounted at `/opt/dystopo/config/server.json` is read without arguments
  (`-v ./server.json:/opt/dystopo/config/server.json:ro`).
- `docker stop` sends SIGTERM; the start script `exec`s java, so the JVM is PID 1 and runs the graceful
  shutdown (below). Give it at least `shutdownGraceSeconds` (`docker stop -t 15`).
- `JAVA_OPTS` (default `-XX:MaxRAMPercentage=75`) and `SERVER_OPTS` pass JVM options.
- Healthcheck for an orchestrator: `GET /healthz` (200 while serving, 503 while shutting down).
- tilebuild runs as a subprocess with its own heap (`DYSTOPO_TILEBUILD_HEAP`, default 3g), so give the
  container room for both (`JAVA_OPTS` defaults to `-XX:MaxRAMPercentage=60` for the server). Its first
  outdoors build downloads Planetiler's aux data (about 1.45 GB: water polygons, Natural Earth, Wikidata
  names) into the cache volume. tilebuild carries LGPL build tooling (GeoTools); the image ships those jars
  unmodified and separate, and they are never on the server's classpath.
- CI builds and starts the image on every push and (T66) builds an outdoors pack inside it: the cache
  volume is a host directory whose `planetiler/` aux files are kept in an Actions cache between runs
  (key `planetiler-aux-v1`), the config lists Geofabrik's Liechtenstein under `extractDownloads`, a box
  no extract covers must be a 422, and a small box inside it at z8 to z10 must come back `done` with
  `osmSource: extract`, `osmProfile: outdoors` and appear in `/catalog`. The `image` job also checks
  `/healthz`, the token, `/catalog` and that tilebuild is available.

Packs built elsewhere (`tilebuild`, `devtool tiles --pmtiles`) go into the out volume as
`<name>-dem.pmtiles` plus `<name>-outdoors.pmtiles`; the server picks up new files at startup, and packs
made by `POST /build` immediately.

## Behind a reverse proxy with TLS

The server speaks plain HTTP; terminate TLS in front of it. Keep the server on a private interface
(`"bind": "127.0.0.1"`, or a container network) and let the proxy forward `Host` and `X-Forwarded-Proto`:
the phone style (`/style.json`) has absolute tile URLs, which the server builds from `X-Forwarded-Proto` +
`X-Forwarded-Host` (else `Host`). Set `publicUrl` to pin them instead (for example when the proxy
rewrites the host).

Caddy (automatic certificates):

```
maps.example.org {
    reverse_proxy 127.0.0.1:8080
}
```

nginx:

```
server {
    listen 443 ssl http2;
    server_name maps.example.org;
    ssl_certificate     /etc/letsencrypt/live/maps.example.org/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/maps.example.org/privkey.pem;

    client_max_body_size 256m;        # sync pushes (bundles up to 256 MB)
    proxy_read_timeout 120s;          # the first view of a new place renders for 10 to 30 s

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header X-Forwarded-Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_buffering off;          # pack downloads stream; ranges pass through
    }
}
```

Range requests, `ETag` and `If-Range` on `/out/` pass through both, so interrupted pack downloads resume.
A CDN in front of `/out/` and the tile routes works too (tiles do not change for a given URL, except that
a `/build` of an existing pack name replaces its files).

## Config file

JSON; every key is optional; an unknown key is an error (so a typo does not silently fall back to a
default). Relative paths resolve against the working directory. `server/server.example.json` lists every
key with its default.

| Key | Default | Meaning |
| --- | --- | --- |
| `bind` | `"0.0.0.0"` | Address to listen on. `127.0.0.1` behind a local proxy. |
| `port` | `8080` | Port (0 picks a free one; the `started` log line says which). |
| `publicUrl` | `null` | Base URL for the absolute URLs in `/style.json`, e.g. `"https://maps.example.org"`. Null: from each request's `X-Forwarded-*` / `Host` headers. |
| `cacheDir` | `"cache"` | Downloads and generated tiles (`live/`, `openfreemap/`, `derived/`, `copernicus30/`, `3dep13/`, `3dep1m/`, `overpass/`, `glyphs/`, ...). The tile stores are capped (`cache.*`); the source downloads have a byte budget (`sources.*`). |
| `outDir` | `"out"` | Region packs: served as packs, under `/out/`, listed by `/catalog`, written by `/build`. |
| `glyphsDir` | `null` | Glyph PBFs (`<font>/<range>.pbf`); null: `<cacheDir>/glyphs`. |
| `fetchGlyphs` | `true` | At startup, download the glyph ranges the style uses (Noto Sans, SIL OFL) when missing. |
| `glyphsUrl` | protomaps basemaps-assets | Where those glyphs come from. |
| `webPage` | `null` | The page at `/`; null: `web/index.html` in the distribution, else `tools/demo/index.html`. |
| `syncDb` | `null` | The sync peer's replica (SQLite); null: `<outDir>/sync/peer.db`. |
| `corsOrigins` | `["*"]` | Origins allowed from a browser. `"*"` allows any; otherwise exact origins (`"https://maps.example.org"`, no path), echoed with `Vary: Origin`; `[]` allows none. Preflights (`OPTIONS`) are answered for every route, allowing the `Authorization`, `Range` and `If-Range` headers. |
| `token` | `null` | Bearer token for `/build` and `/sync` (see below). The environment variable `DYSTOPO_SERVER_TOKEN` overrides it. |
| `threads` | `32` | HTTP worker threads. |
| `liveThreads` | `null` | Threads rendering live DEM tiles; null: CPU count clamped to 4..16. |
| `shutdownGraceSeconds` | `10` | How long a shutdown waits for requests in flight and cancelled builds. |
| `logRequests` | `true` | One log line per request (errors, 401s, startup and shutdown are logged regardless). |
| `build.maxTiles` | `20000` | DEM and OSM tiles per `/build` job. |
| `build.maxSpanDegrees` | `3.0` | Largest box side, degrees. |
| `build.concurrent` | `1` | Builds running at once. |
| `build.queue` | `4` | Builds waiting beyond those (then 429). |
| `overpass.url` | `https://overpass-api.de/api/interpreter` | Overpass endpoint for `profile=outdoors` builds. |
| `overpass.maxAreaSqDeg` | `0.05` | Largest box sent to Overpass (square degrees). A larger box needs an extract; without one it is a 422 (413 with `source=overpass`), or OpenFreeMap with `fallback=1`. |
| `overpass.timeoutSeconds` | `90` | Overpass query timeout (10..900). |
| `tilebuildHome` | `null` | A `./gradlew :tilebuild:installDist` install for `profile=outdoors` builds; null: `DYSTOPO_TILEBUILD_HOME` (`/opt/tilebuild` in the Docker image), else `tilebuild/build/install/tilebuild`. Without one, outdoors builds answer 503 and `profile=openmaptiles` builds work. |
| `extracts` | `[]` | OSM extracts for outdoors packs bigger than Overpass serves (below): `[{"path": "data/washington-261008.osm.pbf", "bbox": [w, s, e, n], "name": "washington"}]`; `bbox` and `name` are optional. |
| `extractsDir` | `null` | Directory scanned for more `*.osm.pbf` extracts at every build; null: `<cacheDir>/extracts`; `""` turns scanning off. |
| `relay.enabled` | `true` | The encrypted sync relay at `/sync/relay` (below, The relay); `false` answers 404. |
| `relay.ttlSeconds` | `900` | A relayed blob is dropped this long after its last write. |
| `relay.maxBlobBytes` | `16777216` | Largest blob (one phone's whole log, encrypted and gzipped); larger is a 413. |
| `relay.maxTotalBytes` | `268435456` | Bytes held in memory for all slots; beyond, new blobs get a 507. |
| `relay.maxSlots` | `10000` | Slots (share codes in use) at once; beyond, 507. |
| `relay.maxSides` | `4` | Phones per slot. |
| `relay.requestsPerMinute` | `120` | Relay requests per client address per minute (token bucket; 429 with `Retry-After`). A phone polls every 5 s. |
| `extractDownloads.urls` | `[]` | Geofabrik regions the server may download into `extractsDir` when an outdoors build needs one (T66, below), e.g. `["https://download.geofabrik.de/europe/alps"]` (a `-latest.osm.pbf`, `-YYMMDD.osm.pbf` or `.poly` URL, or one with a trailing slash, works too; T107: the config check reads them exactly as the downloader does, so it takes all of these and refuses what the downloader would). `devtool serve` reads `DYSTOPO_EXTRACT_DOWNLOADS` (comma-separated) instead. |
| `extractDownloads.maxMb` | `4096` | Largest extract downloaded (by `Content-Length`, checked again while streaming). |
| `extractDownloads.sha256` | `{}` | T92: region name to the SHA-256 (hex) its download must have, e.g. `{"liechtenstein": "38c2..."}`; a mismatch fails the build and nothing is kept. Pair it with a dated Geofabrik snapshot URL (`https://download.geofabrik.de/europe/liechtenstein-260101.osm.pbf`, kept as `liechtenstein-260101.osm.pbf`) so the bytes never change; CI does. T97: a key must be the name of a region in `extractDownloads.urls` (the last segment of its URL without `-latest.osm.pbf`, `-YYMMDD.osm.pbf` or `.poly`, compared exactly: `liechtenstein`, not `Liechtenstein` or `europe/liechtenstein`); a key that names none, which would let a misspelt region download unchecked, fails the config check at start (`config_invalid` in the log, exit code 2). |
| `log.file` | `null` | T82: write the JSON log to this file instead of stdout, rotating it (below, Operations). Null: stdout (cap it in journald or Docker). |
| `log.maxMb` | `64` | Rotate the log file at this size. |
| `log.keep` | `5` | Rotated files kept (`server.log.1` .. `.5`); the log never takes more than about `(keep + 1) * maxMb`. |
| `metrics.enabled` | `true` | `GET /metrics` (below). |
| `metrics.diskScanMinutes` | `10` | How often `/metrics` re-measures `cacheDir` and `outDir` (a walk of every file, in the background, at low priority); `0` turns it off. |
| `http.maxRequestSeconds` | `60` | A client must send its whole request (headers and body) within this time, else the connection is closed. Bounds slow-header clients; a sync push slower than this fails (behind a proxy that buffers requests it only bounds the proxy's hop). T111: a body still arriving at this limit is answered 408 with `Connection: close` (counted as a 4xx, logged `outcome: requestTimeout`, `/metrics` `requestTimeouts`); the listener's own timer runs 5 s later and closes a connection whose headers are still arriving, without an answer. |
| `http.idleSeconds` | `30` | Idle keep-alive connections are closed after this. |
| `http.maxConnections` | `1000` | Open connections at once; beyond, new ones are closed at once. |
| `cache.tileDir` | `null` | T74: where the tile stores live (`live/`, `openfreemap/`, `derived/`); null: `cacheDir`. `devtool serve` reads `DYSTOPO_TILE_CACHE`. |
| `cache.liveMb` | `4096` | Byte cap of the live DEM tiles (`live/best-v2/`), least recently used evicted first. |
| `cache.openfreemapMb` | `2048` | Byte cap of the OpenFreeMap tiles (`openfreemap/<build>/`, every planet build; old builds go first). |
| `cache.derivedMb` | `1024` | Byte cap of the contour and shade tiles the server keeps (`derived/`). `devtool serve` reads all three from `DYSTOPO_CACHE_CAPS=live=4096,openfreemap=2048,derived=1024`. |
| `sources.maxRequestMb` | `256` | T92: the most one download of the elevation readers may bring in (3DEP, Copernicus, the national sources, geoid grids under `cacheDir`): a larger range request is refused before it is sent, a larger whole file is cut off while it streams. `0`: no cap. |
| `sources.maxHourMb` | `8192` | T92: the most those downloads may bring in over any 60 minutes; past it new ones are refused until older minutes leave the window (the tile that needed one fails; the server stays up). `0`: no cap. `/stats` `sourceDownloads` has the counters; `devtool serve --live` reads `DYSTOPO_SOURCE_CAPS=request=256,hour=8192`. |

## Routes

The same as `devtool serve --live` (`LiveTiles.kt` documents each), plus three:

- `/` browser map, `/phone` phone style page, `/style.json?units=ft|m` (or `?lat=&lon=`), `/live/info`,
  `/live/units`, `/live/sources?bbox=&dz=&oz=` (sources and attribution of a view), `/stats`.
- `/dem/{z}/{x}/{y}.png`, `/contours/{z}/{x}/{y}.pbf?units=`, `/osm/{z}/{x}/{y}.pbf`: packs first, else
  live (`X-Dystopo-Source` says which; live OpenFreeMap tiles below z10 come without glacier polygons, which
  OpenMapTiles cuts at tile edges there, T66). `/glyphs/{fontstack}/{range}.pbf`, `/sprite*.json|png`.
  T74: an unknown `units=` is a 400 (it fell back to the default before). A glyph range is the file, a valid
  range without glyphs for one of the style's fonts when the file is missing (MapLibre counts zero bytes as a
  failed load and never reports the map loaded), or a 404 (another font, a range that is not a 256-codepoint
  block); never an empty 200. Contour and shade tiles are kept in `derived/` (below, Tile caches).
- `GET /version` (T74): the content version per route a phone keys its cached tiles on: `dem` (the live
  rendering version and the DEM packs, `best-v2.<hash>`; a `/build` pack changes it), `osm` (the OpenFreeMap
  planet build and the vector packs) and `assets` (the asset manifest's version). `/dem` and `/osm` tiles
  carry theirs in an `X-Dystopo-Version` header, and `/live/info` lists `versions` too.
- `GET /assets/manifest.json` (T74): the style assets with their SHA-256: every glyph range of the style's
  fonts present in `glyphsDir` and the four sprite files, as `{"version", "files": [{"path", "url",
  "sha256", "bytes"}]}`. A phone fetches every file whose hash differs from its copy, so a changed sprite
  sheet or glyph range reaches installs that already had the old one.
- `/out/{file}`: static pack downloads with byte ranges, `ETag`, `If-Range`, `?kbps=` throttle.
- `POST /build?name=&bbox=w,s,e,n&minZoom=&maxZoom=[&profile=outdoors][&source=auto|extract|overpass][&fallback=1][&overwrite=1][&graph=0]`,
  `GET /build`, `GET|DELETE /build/{id}`, `GET /build/sources?bbox=` (`BuildRoutes.kt`; outdoors sources below). Token-protected.
  T189 (ADR 0049's B): after `publish`, a pack with a vector archive goes through a `graph` phase (below, The pack's
  routing graph); `graph=0` skips it.
- `POST /area` (T75): the manifest of an area of interest, a box (a pack built through `/build`), a route
  corridor or (T140) a polygon (tile lists); T157: `demMaxZoom` or `detail=full|medium|less` caps the elevation
  below the vector tiles; `GET /area` for the parameters, the shapes read, the zoom caps and the manifest
  version. Below, Areas of interest. Token-protected.
- `/sync/pull`, `/sync/push`, `/sync/status`, `/sync/tracks[/{id}.gpx]`, `/sync/routes[?full=1]`: the sync peer
  (docs/PLANNING.md, Sync). T92: `?full=1` adds each route's `path` value and `waypointList` (the browser
  page pulls its trips with it), T116 and `directions` (how many saved turn directions count for that path, null
  when none were saved or they were saved for another path); `POST /sync/waypoints/encode` turns waypoints into the `wpt:<id>` field values
  the page writes (below, Route planning). Token-protected.
- `POST /sync/relay?slot=&side=[&after=][&forget=1]`, `GET /sync/relay/status`: the encrypted relay
  between phones (below). Open, without the token.
- `GET /catalog` (also `/catalog.json`): the pack catalog, below. `POST /catalog/refresh`: rehash every
  file (token-protected).
- `GET /metrics` (T82; `?format=prometheus` for the Prometheus text format): what to watch on a long-running
  server, open like `/healthz` (no secrets, no user ids). Below, Operations.
- `GET /healthz`: `status` (`ok`, or `stopping` with a 503), `uptimeS`, `packs`, `outDirWritable`,
  `cacheDirWritable`, `glyphs` (fonts present), `errors` (tile route exceptions so far), `catalog` (the
  startup hash pass: `state` `cold`, `warming` or `ready`, `files`, `hashed`, `hashesComputed`, `ms`),
  `users`, `routeGate` (T92: `/route*` needs a token, true while users are configured), `replicas`, `shareLinks`.

## Outdoors packs: extracts, Overpass, fallback

`profile=outdoors` builds the vector pack with tilebuild's outdoors profile (wilderness, cliffs with
ticks, alpine routes, huts). The OSM data for it comes from, in order (`source=auto`, the default):

1. **A regional extract** on the server's disk that holds the whole box: the smallest of the configured
   `extracts` and the `*.osm.pbf` files in `extractsDir` (`<cacheDir>/extracts` by default, rescanned per
   build, so dropping a file in is enough). tilebuild runs Planetiler on the whole file with the box as
   bounds. No network and no area cap beyond `build.maxSpanDegrees` and `build.maxTiles`: this is how
   whole-park packs get the outdoors layers. A Mount Rainier National Park box (-122.05,46.6,-121.4,47.1,
   z8 to z14) from the Washington file (364 MB) builds its vector pack in about 20 s (Planetiler passes 1
   and 2 read the whole state); the DEM half takes longer than that when its tiles are not cached.
2. **Overpass** for a box under `overpass.maxAreaSqDeg` (one request per box, cached).
3. **A configured Geofabrik region, downloaded on demand** (T66): the smallest of `extractDownloads.urls`
   whose region polygon holds the whole box. Each region's `.poly` is fetched once into `extractsDir`
   (a few kB; `GET /build/sources` and `GET /build` fetch the missing ones); the `-latest.osm.pbf` only when
   a build needs it. The job's phase is then `osm-download` and its `download` has `name`, `url`, `bytes`
   and `total`; one region downloads once at a time, and afterwards the file is an ordinary extract (step
   1) with its `.poly` beside it. Measured: Liechtenstein (3.5 MB) downloaded in 2.1 s and built its
   z8 to z12 outdoors pack in 6.9 s.
4. **OpenFreeMap** (OpenMapTiles schema, no outdoors layers) with `fallback=1` and a warning in the job.
   Without it the request is refused with a message that names the ways out: **422** when no extract
   covers the box (the request is valid, the server has no data for it), **413** only for
   `source=overpass` with a box over the Overpass cap (T66; both were 413 before).

`source=extract` (on disk or downloadable) or `source=overpass` forces one. The job says what it used: `osmSource` (`extract`,
`overpass`, `openfreemap`), `osmProfile`, and for an extract `provenance.osmExtract` (`name`, `file`,
`date` of the data). The archive itself carries `dystopo:extract` (the file's base name) and Planetiler's
`planetiler:osm:osmosisreplicationtime`. `GET /build/sources?bbox=w,s,e,n` answers what an outdoors
build of the box would use (`outdoors.source`, `profile`, `extract`, `why`), every extract with
`covers` and `coverage` (`poly` or `bbox`), the download regions under `downloads` (with `covers`,
`downloaded`, `downloading`, `problem`), the Overpass cap and whether tilebuild is installed; `GET /build`
lists the extracts under `limits.extracts` with any file that could not be used (`problems`) and the
download regions under `limits.extractDownloads`.

Getting an extract (Geofabrik, ODbL, rebuilt daily; the redirect names the file after its date):

```
mkdir -p cache/extracts
curl -L -o cache/extracts/washington-261008.osm.pbf https://download.geofabrik.de/north-america/us/washington-latest.osm.pbf
```

The extract's bbox and date come from a sidecar JSON next to it (`washington-261008.json`:
`{"name": "washington", "bbox": [w, s, e, n], "date": "2026-10-08"}`, every key optional), else from the
PBF header (Geofabrik files carry the region's bounding box and the replication timestamp), else the
date is the file's modification time. The name is the file name without `-latest` or a date suffix.
Coverage is the region polygon when a `.poly` lies beside the file (`<name>.poly` or `<base>.poly`,
Geofabrik's Osmosis format; `curl -o cache/extracts/washington.poly
https://download.geofabrik.de/north-america/us/washington.poly`, or listing the region in
`extractDownloads.urls` fetches it): the whole box must be inside the polygon and no edge of it may
enter the box. Without one it is the bbox, the region polygon's bounding box, so a box near the region's
edge can count as covered while the data beyond the border is missing (a box just across Washington's southern border
was "covered" by the Washington file's bbox and is not by its polygon). Planetiler needs its 1.45 GB of
auxiliary data in the cache the first time (any tilebuild run downloads it); the default heap
(`DYSTOPO_TILEBUILD_HEAP`, 3g) was enough for the Washington file.

## The catalog

`GET /catalog` lists every pack in `outDir` a client can download: a `<name>-dem.pmtiles` plus its
optional `<name>-outdoors.pmtiles` (or `-osm`). Files being written (`*.building`) are not listed. Every
other `.pmtiles` file (a vector archive without its DEM, an `-osm` archive next to an `-outdoors` one, a
tilebuild output such as `liechtenstein.pmtiles`) is a single-file pack: `id` and `name` are the file name
without `.pmtiles`, `"single": true`, and its one file's role comes from the tile type (`mvt` is
`outdoors`, anything else `dem`). The phone lists only packs with a DEM, so it skips vector-only entries.

```
{"version": 1, "generatedAt": "2026-10-10T09:41:43Z", "packs": [
  {"id": "rainier", "name": "rainier",
   "bounds": [-122.05, 46.6, -121.4, 47.1], "center": [-121.725, 46.85], "minZoom": 8, "maxZoom": 14,
   "profile": "outdoors", "bytes": 226811914, "builtAt": "2026-10-10T03:15:47.583Z",
   "provenance": ["3dep", "3dep-1m", "geoid18", "egm2008", "osm", ...], "attribution": "...", "license": "...",
   "contentHash": "sha256:185a50cf...",
   "files": [
     {"role": "dem", "file": "rainier-dem.pmtiles", "url": "/out/rainier-dem.pmtiles", "bytes": 222344963,
      "tileType": "png", "minZoom": 8, "maxZoom": 14, "bounds": [...], "modified": "...", "sha256": "86d849d4..."},
     {"role": "outdoors", "file": "rainier-outdoors.pmtiles", ...}]}]}
```

- `id`: the pack name. Stable across rebuilds and unique on one server; the content hash is what changes.
- `bounds` / `center`: the union of the pack's archives, `[west, south, east, north]`. `minZoom`/`maxZoom`:
  the DEM's; each file has its own.
- `profile`: the vector archive's `dystopo:profile` (`outdoors`: tilebuild's outdoors profile;
  `openmaptiles`: built from OpenFreeMap tiles); archives from before that key count as `outdoors` when
  they have the `outdoor_poi` layer. Null for a DEM-only pack.
- `builtAt`: the newest file's modification time (when the pack was published in `out/`).
- `provenance`, `attribution`, `license`: from the archives' metadata through the source registry.
- `sha256` per file: SHA-256 of the whole file, computed once and cached next to it as
  `<file>.sha256` in `sha256sum` format (so `sha256sum -c rainier-dem.pmtiles.sha256` checks a download).
  The sidecar carries its file's modification time; one whose time differs from the file's is stale and
  recomputed, and `/build` deletes the sidecars of the files it replaces, so a rebuilt pack gets a new
  hash. Bytes changed in place under the same size and time (disk corruption, a hand edit that restores
  the time) are found only by `POST /catalog/refresh`, which rehashes every file ignoring the sidecars and
  answers the catalog plus `refresh` (`files`, `changed`: the files whose hash differed, `ms`). Hashes are
  computed in the background at startup (about 1 s per 200 MB; progress in `/healthz` `catalog`), so the
  first `/catalog` is fast.
- `contentHash`: `sha256:` + SHA-256 of the lines `<role>:<file sha256>\n` (dem first). A client compares
  it with the one it installed to offer an update; it verifies each file against `sha256` after download.

- `derived` (T189, ADR 0049's B; only when the pack has one): files made from the pack's archives and shipped beside
  them, never one of them. Today one kind, the routing graph (below, The pack's routing graph):
  `{"kind": "graph", "file": "rainier-outdoors-graph.idx", "url": "/out/rainier-outdoors-graph.idx", "bytes": 512345,
  "sha256": "<64 hex>", "sidecar": "rainier-outdoors-graph.idx.sha256", "format": "DYSG", "version": 6,
  "key": {"tileZoom": 11, "fingerprint": "v1:...", "demFingerprint": "v1:..."}}`. Field by field: `kind` (what the file
  is; a reader ignores a kind it does not know), `file` (a plain file name in `outDir`), `url` (as for `files`),
  `bytes`, `sha256` (of the whole file, from its `.sha256` sidecar, which follows the archives' rule: stamped with the
  file's time, so any rewrite of the graph makes it stale and it is recomputed; `/healthz` `catalog` counts graphs
  too), `sidecar` (the sidecar's file name), `format` (`DYSG`, the graph file's magic) and `version` (6), and under
  `key` the kind's match keys: `tileZoom` (11, the zoom of the graph tiles every reader requires), `fingerprint` and
  `demFingerprint` (the vector and DEM archive headers' fingerprints the graph was built from, `v1:` tuples, a wire
  value since T189; `demFingerprint` is empty for a pack without a DEM). The catalog lists a graph only when the file
  opens as a version 6 graph at tile zoom 11 whose fingerprints match the archives beside it; a stale graph (the pack
  rebuilt since) or an unreadable one is left out. `derived` is outside `files`, `bytes`, `builtAt` and `contentHash`,
  so a rebuilt graph is not a new pack and a pack's hash is the same with or without one. Readers before T189
  (v0.1.14 and older) ignore the key and build the graph on the phone; a reader that knows it fetches the graph only
  when `format`, `version`, `tileZoom` and both fingerprints match what it holds, and builds otherwise (ADR 0033 §2:
  derived data is rebuilt, never refused). The phone's peer catalog (T78) lists its own graph the same way when it
  matches its archives. The catalog's `version` stays 1.

`devtool serve` (both modes) answers `/catalog` and `/catalog/refresh` too (no token there).

### The pack's routing graph (T189, ADR 0049's B)

A pack built through `POST /build` or `POST /area` ends with a `graph` phase after `publish`: the server's route
planner (the one `/route` uses, so a route and the phase never build one graph twice) builds the pack's routing
graph from the published vector archive with elevations from its DEM, in one pass, and writes
`out/<name>-outdoors-graph.idx` (the version 6 tiled graph file every reader opens beside a vector archive) and
hashes it into `<name>-outdoors-graph.idx.sha256`; the catalog then lists it under `derived` and `POST /area`'s
manifest on its `graph` item, and phones download it with the pack instead of building it. When a matching graph is
already there (an identical rebuild, a route that ran first) it is reused (`action` `loaded` or `cached`).

- The job's `graph` (in `GET /build/{id}`): `state` `building`, `done`, `failed` or `skipped`; when done, `action`
  (`built`, `loaded`, `cached`), `file`, `url`, `bytes`, `sha256`, `sidecar`, `version`, `tileZoom`, `tiles`, `nodes`,
  `edges`, `buildMs`, `listed` (the catalog lists it) and `problem` (why not, when it does not); when failed, `error`
  and `note`; when skipped, `reason` (`graph=0`, `cancelled`, or a terrain-only pack). `timings.graphMs` is the phase.
- The phase never fails the pack: the archives are published before it starts, and a failure (an
  `OutOfMemoryError` on a server whose heap is too small for the box included) leaves `graph.state` `failed` with
  the error; the catalog lists no graph and phones build their own, as before T189. The job stays `running`, with
  phase `graph`, until the phase ends either way, so a phone polling `POST /area` gets the manifest with the graph.
- Cost: the one-pass builder holds the whole network (T149's fit: 48 MiB + 300 B per routable vertex, about x1.5
  for garbage), and the encoder the whole file once more. `devtool serve` runs with the JVM's default heap (a quarter
  of the machine's memory); the server image takes 60 percent of its container's (`server/Dockerfile`). A 3 degree
  box needs about 2.25 GB at the least and builds in 8 to 19 minutes depending on the heap (T149's series).
- A graph rewritten later (by the first route after the pack changed)
  gets a new modification time, so its sidecar is stale and recomputed on the next catalog read.

## Areas of interest (T75)

"Tell us where you are going": a box, a route corridor or (T140, ADR 0041) a polygon with a zoom range, expanded to a **manifest** of
everything the map needs there offline, with sizes. The phone downloads it item by item, verifies what it
gets, and pins the result so its caches do not evict it (docs/PLANNING.md, Design goals, principle 4). The
format is the one a phone will publish to other phones on a trip (T78), so it is versioned and documented
field by field here. Code: `shared/.../areas/` (`AreaOfInterest`, `AreaManifest`, `TileCover`, `AreaPins`,
`PolygonRings`, `AreaLimit`), the server side in `BuildRoutes.kt`; `devtool area-plan` plans one offline.

```
curl -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  --data '{"kind":"corridor","line":[[-121.7356,46.7859],[-121.7355,46.8016],[-121.7268,46.8037]],"bufferM":500,"minZoom":8,"maxZoom":15}' \
  https://maps.example.org/area
curl -X POST -H "Authorization: Bearer $TOKEN" "https://maps.example.org/area?bbox=-121.76,46.77,-121.70,46.80&minZoom=8&maxZoom=14&name=paradise"
```

`POST /area` takes the area as a JSON body (the area object below, or `{"area": {...}, "pack": 0}`) or as
query parameters (`bbox=w,s,e,n`, `line=lat,lon;lat,lon;...` or `ring=lat,lon;lat,lon;lat,lon` with latitude
first, further rings after `|`, or `around=lat,lon` with `radiusM`; `bufferM`, `minZoom`, `maxZoom`, `demMaxZoom`,
`name`; T157: `detail=full|medium|less` sets the zooms of a detail, over the zoom parameters).
Options (query or body): `pack=1|0` (default: 1 for a box without `demMaxZoom`, 0 for a corridor, a polygon or a
capped area), `profile`,
`fallback`, `source` (as for `/build`), `retry=1` (build again after a failed build). It is token-protected
like `/build`, because a box starts a build. `GET /area` lists the parameters and limits, `manifestVersion`
(the newest it reads, 3 since T157) and `shapes` (`box`, `corridor`, `polygon`; a phone treats a server whose answer has
no `shapes` as reading box and corridor only), `maxAreaTiles`, and (T157) `zoomCaps` (`["dem"]`: it reads `demMaxZoom`;
a phone asks a server without it for no capped manifest, and takes a server's manifest only when its `id` is the
area's) and `details` (`full`, `medium`, `less`, each `minZoom`, `maxZoom`, `demMaxZoom`).

- **A box** (or `pack=1`) is a region pack built through the same jobs as `POST /build` (name: `name`, else
  the area's name when it is a valid pack name, else `area-<12 hex digits of the id>`; the outdoors profile
  with `fallback=1` when tilebuild is installed, else OpenMapTiles). While the job runs the answer is
  **202** with `status: building` and the job under `build`; poll `GET /build/{id}` and ask again when it is
  done; then **200** with each archive as a `pack` item (URL, bytes, SHA-256 from the catalog's sidecar). A
  finished pack of that name is answered at once, without a build. A failed build answers 200 with
  `status: failed` and the reason in `notes` until asked again with `retry=1`.
- **A corridor** (or `pack=0`) is the DEM and vector tiles within the buffer, listed per zoom, with
  estimated sizes, answered at once (200). DEM tiles start at z6 whatever `minZoom` is (contour tiles sample
  zooms coarser than they show) and stop at z15; vector tiles run from `minZoom` to z14; one tile of margin
  around the corridor at every zoom (contour tiles sample past their edges). At most `DYSTOPO_AREA_MAX_TILES`
  (200000) tiles, else 413 with what to change. A corridor does not build a pack: the tiles come from the
  same `/dem` and `/osm` routes as the map's (packs on the server first, else live), so a corridor needs no
  build time and costs only the tiles it touches; it has no trail graph or search index (those are built from
  vector packs; download a box for them).
- **A polygon** (T140, ADR 0041) is tiles like a corridor: the tiles whose square lies within `bufferM` of a ring
  edge (with no buffer, the tiles an edge crosses) or whose centre is inside the rings by the even-odd rule, with
  the same zooms and margin, as a **version 2** manifest. `pack=1` on one is a 400, "a pack is a box; download a
  box for the trail graph". Its tile sets are held as rows (T152, ADR 0041 §3's T152 note), so no heap check
  comes first; over `DYSTOPO_AREA_MAX_TILES` its 413 ends "lower maxZoom or draw a smaller area". The area within a
  distance of a point is the one-point corridor up to 20 km (every build since T75 reads it), a 48-vertex
  polygon circle beyond.
- **A capped area** (T157, ADR 0047): any shape with `demMaxZoom` lists DEM tiles from z6 to `demMaxZoom` and
  vector tiles to `min(maxZoom, 14)`, as a **version 3** manifest. The medium detail is `maxZoom` 14 with
  `demMaxZoom` 13 (`detail=medium`): every vector tile of full detail, the elevation to z13 (the 5 km disc around
  Paradise: 274 tiles, about 24 MiB, against 538 tiles and 64 MiB at full detail). A capped box is tiles; `pack=1`
  with a cap is a 400, "a pack holds one zoom range; leave out demMaxZoom for a pack, or ask for tiles (pack=0)".

### The manifest, field by field (versions 1 to 3)

```
{"version": 1, "id": "affd2044dcca0ad24", "name": "Planned: Paradise to Panorama Point", "status": "ready",
 "source": null, "generatedAt": "2026-10-12T18:00:00Z",
 "area": {"kind": "corridor", "line": [[-121.7365, 46.7865], ...], "bufferM": 550, "minZoom": 8, "maxZoom": 15, "name": "..."},
 "bounds": [-121.7437, 46.7816, -121.7218, 46.8098], "lengthM": 2482.3,
 "bytes": 29570000, "bytesExact": false, "tiles": 236,
 "items": [
   {"kind": "tiles", "route": "dem", "ext": "png", "url": "/dem/{z}/{x}/{y}.png", "zooms": [6, 15], "count": 143,
    "perZoom": {"6": 9, ..., "14": 19, "15": 32}, "bytes": 22880000, "estimated": true, "fetchedBytes": null,
    "tiles": {"6": [[22, 9, 11], [23, 9, 11], [24, 9, 11]], ..., "15": [[...]]}},
   {"kind": "tiles", "route": "osm", "ext": "pbf", "url": "/osm/{z}/{x}/{y}.pbf", ...},
   {"kind": "pack", "role": "dem", "pack": "paradise", "file": "paradise-dem.pmtiles", "url": "/out/paradise-dem.pmtiles",
    "bytes": 5234567, "sha256": "<64 hex>", "tileType": "png", "minZoom": 8, "maxZoom": 14, "bounds": [w, s, e, n]},
   {"kind": "glyphs", "url": "/glyphs/{fontstack}/{range}.pbf", "files": 768, "bytes": 0, "fileBytes": 11079256, "bundled": true, "names": ["Noto Sans Italic", ...]},
   {"kind": "sprite", "url": "/{file}", "files": 4, "bytes": 0, "fileBytes": 61000, "bundled": true, "names": ["sprite.json", ...]},
   {"kind": "contours", "onDevice": true, "from": "dem", "available": true, "note": "..."},
   {"kind": "graph", "onDevice": true, "from": "paradise-outdoors.pmtiles", "available": true, "note": "..."},
   {"kind": "search", "onDevice": true, "from": "paradise-outdoors.pmtiles", "available": true, "note": "..."}],
 "build": null, "notes": ["..."], "pack": "paradise"}
```

- `version`: the format version, the lowest that holds the content (T140, ADR 0041 §2): 1 for a box or a
  corridor, 2 for a polygon, 3 for any area with `demMaxZoom` (T157, ADR 0047: a version 2 reader would ignore the
  field and fetch the elevation to `maxZoom`, so it refuses the manifest instead). A reader checks `version`, then the
  area's `kind`, then the fields; a version newer than it reads ("manifest version 4 is newer than this app reads (3)")
  or a kind it does not know ("kind must be
  box, corridor or polygon (got '...')") is refused rather than misread, and a phone shows "This phone runs an
  older Dystopo; update it to use this area." with that sentence under it. Builds before T140 parse the area
  first, so they answer a polygon with "kind must be box or corridor"; a phone falls back to its own manifest on
  a 400 that starts "kind must be", as on 404 and 405.
- `id`: the area's content id, `a` + 16 hex digits of the SHA-256 of the geometry (coordinates rounded to
  1e-6 degrees, the buffer) and the zoom range (T157: `:dem<z>` after the zooms when `demMaxZoom` is set, so the
  medium, less and full areas of one shape have three ids and no earlier id changed). The name is not part of it, so the same area asked twice (or by
  two phones) has the same id; the phone keys its area records by it.
- `name`: the area's name, else the pack name (box) or the id.
- `status`: `ready` (every item can be fetched now), `building` (HTTP 202: a pack is being built; `build`
  is the `/build` job, ask again when it is done) or `failed` (`notes` says why).
- `source`: who made the manifest when it is not the server answering (`phone`, `phone (public sources)`;
  a phone publishing to another phone puts its address here). Null from the server.
- `generatedAt`: ISO-8601 UTC.
- `versions` (T95, optional): the source's content versions (`dem`, `osm`, as `GET /version` names them) the
  tiles were fetched at; a phone writes it when a download completes, and keeps a pinned area's tiles at these
  versions until the area is refreshed. Absent when unknown, so the server's own answers are unchanged.
- `area`: the request, normalised. Box: `{"kind": "box", "bbox": [west, south, east, north], "minZoom", "maxZoom",
  "name"}` (west < east: no antimeridian crossing; split such boxes). Corridor: `{"kind": "corridor", "line":
  [[lon, lat], ...]` (GeoJSON order, 1e-7 degrees), `"bufferM"` (10 to 20000, metres on each side), zooms, name}.
  Polygon (T140): `{"kind": "polygon", "rings": [[[lon, lat], ...], ...]` (GeoJSON order, each ring closed by
  repeating its first point and read closed or open; holes and further parts are more rings, inside by even-odd;
  rings never overlap and none crosses the antimeridian), `"bufferM"` (0 to 20000, default 0), zooms, name}; its
  id hashes `polygon:<buffer>:<rings>:<zooms>`. Writers simplify to about 2,000 vertices over all rings and add
  the tolerance to `bufferM`; readers take any count (the 2 MiB body is the only hard limit). Zooms 0 to 16.
  T157: any kind may carry `"demMaxZoom"` after `maxZoom` (written only when set, below `maxZoom`; a reader drops a
  cap at or above it): the DEM tiles stop there, the vector tiles at `min(maxZoom, 14)`.
- `bounds`: the rectangle holding the area (a corridor's line grown by its buffer), `[w, s, e, n]`.
  `lengthM`: a corridor's line length in metres (great circle).
- `bytes`: the sum of the items' `bytes`; `bytesExact`: false while any item's size is an estimate.
  `tiles`: the number of tiles in `tiles` items.
- `items`: what to fetch or make, each with a `kind`:
  - `tiles`: tiles of one route of the source (`route`: `dem` Terrarium PNG, `osm` Mapbox Vector Tile),
    `url` the template relative to the source, `ext` the file extension, `zooms` the first and last zoom,
    `count` and `perZoom` the tile counts, `tiles` the tiles themselves: per zoom a list of rows
    `[y, x0, x1]` meaning tiles x0 to x1 (inclusive) of row y, sorted. `bytes` is an estimate
    (`estimated: true`) from typical tile sizes (DEM 160 kB, the Rainier pack's mountain average; vector
    800 kB at z7 and below falling to 10 kB at z13 and z14) until a client fills `fetchedBytes`.
  - `pack`: one archive of a region pack: `role` (`dem` or `outdoors`, the vector half), `pack` (the pack
    name), `file`, `url` (relative to the source, or absolute), `bytes`, `sha256` (64 lowercase hex digits;
    the client refuses a file whose hash differs), `tileType`, `minZoom`, `maxZoom`, `bounds`.
  - `glyphs`, `sprite`: style assets: `url` template, `files`, `fileBytes` on the source, `bundled` (the app
    ships them, so a client fetches them only when it lacks them, and `bytes`, what to transfer, is 0),
    `names` (font stacks, or sprite files).
  - `contours`, `graph`, `search`: products the client makes itself (`onDevice: true`) from `from` (the DEM
    route, or the vector archive); `available: false` when this area cannot have one (a corridor has no
    trail graph: graphs come from vector packs), with the reason in `note`.
    T189 (ADR 0049's B): a box's `graph` item, once the build's `graph` phase wrote a graph matching the archives,
    also carries it, with the fields of a catalog `derived` entry (`file`, `url`, `bytes`, `sha256`, `sidecar`,
    `format`, `version`, `key`) and `onDevice: false`: `{"kind": "graph", "onDevice": false, "from":
    "paradise-outdoors.pmtiles", "available": true, "note": "...", "file": "paradise-outdoors-graph.idx", "url":
    "/out/paradise-outdoors-graph.idx", "bytes": 512345, "sha256": "<64 hex>", "sidecar": "...", "format": "DYSG",
    "version": 6, "key": {"tileZoom": 11, "fingerprint": "v1:...", "demFingerprint": "v1:..."}}`. Its `bytes` count in
    the manifest's `bytes`. A client fetches it with the archives when the match keys fit (as for `derived`) and
    builds its own otherwise; a reader before T189 parses the item as before, ignores the extra keys and builds, so
    the manifest's `version` does not move (not a new item kind: an older parser refuses an unknown kind and fails
    the whole manifest).
- `build`: the `/build` job (as `GET /build/{id}` answers it) while `status` is `building` or `failed`.
- `notes`: plain sentences about choices made (fallbacks, the DEM start zoom).
- `pack` (server, box only): the pack name, so a client can find it in `/catalog` later.

Measured (T75, 2026-10-12, emulator against `devtool serve --live` on the same host): the Paradise to Panorama
Point trip (the route of `skyline-loop.gpx`, 2.5 km, 550 m buffer, z8 to z15) is 236 tiles estimated at 29.6 MB;
the phone fetched 180 of them (18.1 MB, 56 were already cached) in 19 s.

How the phone uses it: docs/reference/android-debug-routes.md (Areas of interest); in short, `pack` items go through the
catalog downloader (resumable, SHA-256 checked, installed only when every archive verifies), `tiles` items
through the online tile fetcher into the tile cache (tiles already cached or in a pack are skipped, so a
second run resumes; a DEM tile that is not a PNG is dropped and counted), and the area's tiles are pinned
there until the user unpins it.

## The sync peer

The server is the always-on peer of the sync protocol (docs/PLANNING.md, Sync): its replica is a SQLite
file (`syncDb`, default `out/sync/peer.db`), created on the first sync request. A phone syncs with
`POST /sync?with=https://maps.example.org` (or the `peer` setting). Every replica that can reach the server
can read and write its log, so on a server reachable from the internet set a token: with one, `/sync/*`
needs `Authorization: Bearer <token>` (the phone sends the token from its sync settings,
`POST /sync/settings?token=`; `devtool sync with --token`).

A push (T74) is inflated with a cap before it is merged: at most 64 MiB inflated and 100,000 operations
(the largest page a pull hands out); past either it is a 413 ("push in pages"), and something that is not
a bundle is a 400. Phones push pages of 2,000 operations, a few MB at most. Before, a 1 MB gzip of zeros
inflated to 1 GiB in memory behind a valid token (the T82 probe's gzip bomb).

### Tile caches (T74)

Three stores under `cache.tileDir` (default `cacheDir`), each with a byte cap and least-recently-used
eviction: `live/` (live DEM tiles and their `.src` provenance sidecars, which go together), `openfreemap/`
(OpenFreeMap tiles of every planet build; the TileJSON is kept) and `derived/` (contour tiles keyed
`contours-v2-<unit>-<options hash>-<dem version>/z/x/y.pbf`, shade tiles `shade1-<options>-<dem version>/`,
the same inputs the phone keys its own on). At start each store indexes what is on disk in the background
(oldest modification time first) and evicts down to its cap once that is done; a hit sets the file's mtime
at most once a minute, so the order survives a restart. A reader that loses a race with eviction treats it
as a miss and renders or fetches again. `/stats` `caches` has per store `bytes`, `maxBytes`, `entries`,
`hits`, `adds`, `evictions`, `evictedBytes`, `peakBytes` (the most it held after a trim), `indexed` and
`indexMs`; `versions` has the content versions. The source downloads (`3dep13/`, `copernicus30/`, ...)
are not a tile store: T92 puts a byte budget on them instead (`sources.maxRequestMb` per download,
`sources.maxHourMb` per hour; `/stats` `sourceDownloads`: `requests`, `bytes`, `lastHourBytes`,
`largestRequestBytes`, `refused.requestCap`, `refused.hourCap`).

**Norway tiles from before T66** (T92). T66 changed how a Kartverket block's zero cells are read without
bumping the live cache version (a bump wipes every server's whole `live/` store). Once per cache, at start, the
server deletes the live DEM tiles whose `.src` sidecar names `kartverket-dtm` and the `derived/` tiles over
Norway's coverage box, in the background after the stores are indexed, and writes `live/norway-zero-rule-pruned.txt`;
`/stats` `staleCachePrune.norway-zero-rule` has the counts. On the shared development cache it deleted 55 tiles
(9.0 MB) on 2026-10-13.

## Route planning (T68)

The browser map plans routes on the server with the phone's shared code (`RouteRoutes.kt`: T45's router, T60's
route editor and trip summary), so a route planned on the desktop has the numbers the phone shows. Each pack in
`outDir` with a vector archive gets a trail graph on first use (T189: or at the end of its build, the `graph` phase
above), written next to it as
`<vector archive>-graph.idx` (the phone's and `devtool route`'s file name, e.g. `rainier-outdoors-graph.idx`;
reused while both archives are unchanged; the Rainier graph builds in about 2 s). Since T76 the file holds z11
graph tiles that load on demand into one LRU (`GET /route/status` `tileCache`), and a route runs over every pack
whose box comes within 5 km of its points, so it crosses from one pack into the next (docs/PLANNING.md ›
Routing). T89: the file is `DYSG` version 5 (bridge and tunnel levels, component labels); a file of an older
version is rebuilt on the next route, and the reply's `graph.rebuildReason` says why (`missing`, `stale (pack
changed)`, or the older version's message). The routes read packs and write nothing but the graph files and the
overlay cell files (below). T92: they are open while the server has
no users; once `users` are configured they need a known token like `/build` (any user's, the admin's or the T52
`token`; `routeGate` in `/healthz`), since a route costs CPU and opens every pack's graph. The browser page
sends its Token field with them.

**Long drives (T77).** Drives whose ends are 40 km or more apart search the tiles away from their ends through a
drive overlay (per graph tile of one pack: the border crossings of tertiary and larger roads and the cheapest
in-tile drive between them), then expand it back into the pack's edges, so a drive across several packs plans in
well under a second once the graphs are open (Seattle to Yakima over three Washington packs, 232 km: 0.66 s on a
fresh server with the graph files on disk, 0.2 to 0.4 s warm; numbers in docs/PLANNING.md › Routing). Cells are
built when a drive first reaches them, and every tile of a pack is built in the background once its graph opens
(`GET /route/status` `driveOverlay`, `warm`); they are kept in memory, up to 65,536 cells. A phone with this
server set asks it for routes whose ends lie beyond its own packs (the same `GET /route`), keeps the answer as a
trip and downloads its corridor (`POST /area`, below) to navigate offline.

**Overlay cell files (T89, ADR 0029).** A second level (z8 cells, each over the 64 graph tiles below it: the crossings of a
z8 tile and the cheapest drive between them through its level-1 cells) lets a long drive search mostly z8 cells;
only the server builds it (the phone stays on level 1), in the warm-up and never inside a search. When the
warm-up has built both levels for every tile of a pack it writes them to `<vector archive>-overlay.idx` beside
`<vector archive>-graph.idx` (`DYSO` version 1, each cell with its own checksum; `rainier-outdoors-overlay.idx` is
1,549 bytes: 15 level-1 and 2 level-2 cells, a warm-up of 0.1 to 0.2 s). A restarted server reads that
file instead of building (cells on demand through ranged reads): the pack's `warm` entry says `loaded` with
`cells`, `bytes` and `ms` (1 to 2 ms for Rainier) and `built` and `level2Built` stay 0. The file is valid for
the one graph build it names (the graph file's source name, fingerprints and build time) and the overlay's zooms; a
rebuilt graph (the pack changed, an older `DYSG` version, the graph file deleted) makes it stale, and the
warm-up then builds again and overwrites it, as it does for a file that cannot be read (a truncated one, say).
Delete it to force a rebuild; nothing else reads it. It is not written when the cells no longer all fit in
memory (a pack with more than 65,536 of them; `file` and `bytes` are null in its `warm` entry), and a pack
without drive data (outdoors profile 1) has neither a cell file nor a `warm` entry. T107: a pack file replaced
while the server runs (a new modification time or size) reopens its graph on the next route that uses it, and the
warm-up runs again for the new open: when the content is unchanged the graph file is reused and its cell file
registered again (`warm` says `loaded`, the vector archive is back in `cellFiles`); when it changed, the graph is
rebuilt and the cells are built and written again (`done`). Each pack keeps one `warm` entry, the current open's.
Before T107 the old entry stayed, so a reused graph lost its cell file (`cellFiles` empty, level-2 cells unused)
until a restart.

- `GET /route?from=lat,lon&to=lat,lon[&via=lat,lon;lat,lon][&mode=hike|bike|drive][&pack=<name>][&snap=m][&overlay=0]` (or
  `points=lat,lon;lat,lon;...`, 2 to 50): a route through the points, each snapped onto a trail within `snap`
  metres (default 300, as on the phone); legs follow trails, and are straight where no trail connects (DEM
  elevation every 25 m, from the packs or else the live `best` source). The reported pack is `pack=` (a catalog
  id; 400 when unknown), else the first pack holding both ends, else the start; the route uses it and every other
  pack near the points; outside every pack the route is straight (`notes: ["no_graph"]`). `overlay=0` plans a
  long drive without the T77 overlay (for comparing).
- `POST /route/edit` with JSON `{"path", "op", "index", "lat", "lon", "mode", "pack"}`: `op` is `move`, `insert`,
  `add` (into the nearest leg within 150 m, else the new end; answers `index`), `remove`, `mode`, `reroute` or
  `show`. Only the legs next to a moved, added or removed point are rerouted.
- Both answer `pack`, `mode`, `path` (the encoded route: the `path` field value a trip stores in the sync log),
  `via`, `legs` (`kind` trail/straight/fixed, `points`, `coordinates` as `[lon, lat, ele]`), `summary` (`mode`,
  `distanceM`, `ascentM`, `descentM`, `minEleM`, `maxEleM`, `timeS` and `time` (the hike or bike model; for a drive
  the router's sum of each road's time), `points`, `viaPoints`, `legs`, `straightM`, `waypoints`, and `profile`, 100
  samples), `notes` (why a leg is straight, one code each: `no_graph` (no pack's graph near the points),
  `empty_graph`, `start_off_network` and `end_off_network` (no trail, or for a drive no road open to cars, within
  `snap` of that end), `not_connected` (both ends on the network but no way between them), `drive_unsupported` (a
  pack built before the road data; rebuild it), `search_limit` (the search gave up at its node budget, which is no
  proof there is no way)), `messages` (T73: the router's sentence for each note, e.g. "no road open to cars within
  300 m of the end point (the nearest is 3192 m away)"), `notice` (for `mode=drive`: it follows the packs' roads and
  is not a road navigator, the one sentence `Router.DRIVE_NOTICE`, ADR 0032; T111: for `mode=crow` the phone's line
  under its mode picker, `TravelMode.CROW_NOTICE`; null otherwise), `waypointsValue` (the empty `waypoints` value a page saves with a new
  trip), `directionsValue` (T116: the turn directions below as the route's `directions` field value, keyed to this
  `path` as the phone's Keep writes them, ADR 0033, for the page to save with the trip; null when no pack's graph
  was open and the path has a trail or fixed leg, since its directions would be only the frame and a phone with the
  pack makes better ones when it opens the trip, and when longer than the field's 64 KiB), `graph` (`action` `built`, `loaded` or `cached` and `file`, the reported pack's; every pack used under
  `graphs` with its `pack` and `action`; T77: `searches`, one per leg searched, with `ms`, `found`, `tiles`,
  `graphBytes`, `searchBytes`, `searchNodes`, `expanded`, and `overlayTiles` and T89 `level2Tiles` (the z8 cells it
  went through) when it used the overlay) and `ms`. Bad input is a 400 with `error`. T102: `instructions`, the turn directions
  along the whole path (every leg, kept or rerouted; via points and straight legs too): each with `type` (`depart`,
  `left`, `keep-right`, `continue`, `via`, `off-network`, `arrive`, ...), `priority` (1 major, 2 normal, 3 minor; the
  apps show 1 and 2), `text` (English, no distance), `alongM`, `lat`, `lon`, `name`, `class`, `sameWay`, `turnDeg`,
  `bearingDeg`, `leadM` (how far ahead to announce it) and `stretchM` (to the next one); the phone's remote router
  reads them. T116: `text` carries the default level's "then" cue where the next instruction shown follows within its
  lead ("Head south on the service road, then turn right"; `then` is that next one's `type`, null otherwise; the cue
  is worked out from the list, so it is not part of the saved field). PLANNING › Routing, Turn directions.
  (The phone's own router has one
  more code, `remote_unavailable`, for a server it asked that did not answer.)
- `POST /route/gpx` with `{"path", "name", "id"}`: GPX (one `rte` with via marks and our ids), byte for byte
  what the phone exports for the same trip.
- `POST /route/bundle` with `{"device", "ops"}`: the page's own route operations (put, set field, delete; one
  device, HLC-stamped) as a gzipped sync bundle, for `POST /sync/push` or a `.dsync` file. A `path`, `waypoints`
  or (T116) `directions` value must decode, else a 400.
- `GET /route/packs` (packs and graph state), `GET /route/status` (below), and
  `GET /trips.js`, the page's editor script (`tools/demo/trips.js` next to the served page).
- `GET /route/status` answers, since the server started (a pack's graph opens on its first route, so a fresh
  server has empty `graphs` and `warm`):
  - `outDir`, `requests` (counts by kind), `errors`, `lastMs`, `building` (packs whose graph is being built),
    `graphs` (per open pack, how its graph was opened: `action` `built` or `loaded`, `file`, `bytes`, `buildMs` or
    `loadMs`, `rebuildReason` after a build, `nodes` after a build, `tiles`, and after a build `demTilesDecoded`,
    the DEM pack's tiles decoded to sample the graph's elevations, null when the pack has no DEM);
  - `tileCache`: graph tiles in memory (`tiles`, `bytes` of `maxBytes`, `loads`, `hits`, `evictions`) and, T89,
    `loadErrors` (tiles that failed their checksum or were cut short), `corruptTiles` (how many are remembered)
    and `corruptHits` (lookups answered from that memory without reading the file again);
  - `lastRouteMemory`: the last route's `tiles`, `graphBytes`, `searchBytes` and `searchNodes`, and
    `overlayTiles` and `level2Tiles` when its search used the overlay;
  - `corruptGraphTiles` (T89): `[]`, or one `{file, x, y, error}` per remembered corrupt graph tile (`file` is the
    pack's vector archive name, `x` and `y` the z11 tile). Such a tile is skipped for the life of the process, so
    a route that needs it behaves as if the roads there were missing (`start_off_network`, `end_off_network` or
    `not_connected` notes, not an error). The list lives in memory only: a restart starts it empty and the next route that
    reaches the tile finds it again. To repair, stop the server, delete `<vector archive>-graph.idx` (and the
    `-overlay.idx` beside it), start it and route: the graph is rebuilt (`rebuildReason` `missing`), the list is
    empty and the warm-up writes the cell file again;
  - `driveOverlay`: `tiles` (cells held in memory, both levels, with the tiles known to have none), `built`
    (level-1 tiles built by this process: 25 for Rainier, 15 of them with a cell), `hits`, `buildMs`,
    `borderNodes` and `shortcuts` (totals of those built), `minClass` (`tertiary`: the smallest road class that
    crosses a cell border), T89 `level2Zoom` (8; null when level 2 is off), `level2Built`, `level2Used` (level-2
    cells handed to searches), `loadedFromFiles` (cells read from cell files), `cellFiles` (the vector archive
    name of each graph with a registered cell file) and `warm`: one entry per pack and graph build, `state`
    `queued` (while the warm-up runs), `done` (`tiles`, `cells`, `level2Cells`, `ms`, `level1Ms`, `level2Ms`,
    `file`, `bytes`), `loaded` (`file`, `cells`, `bytes`, `ms`) or `failed` (`error`).

**Save as trip.** The page keeps its trips in its own sync replica in the browser (localStorage
`dystopo.trips`: a device id, a hybrid logical clock, and the same put + `path` + `waypoints` operations the
phone's store writes; T116: and `directions`, the reply's `directionsValue`, after each `path` it writes, on the
first save and whenever the path changed, so the phone opens a browser-saved trip with its directions and no
graph; a trip saved by a page from before T116 has no `directions` op, and the phone makes them when the trip is
opened with packs covering it (T108's migration, `POST /routes/{id}/open?wait=1`), then syncs them back: T125 saw
`directions.saved` go from false to true with the same 8 directions as a T116 save of the same hike, and
`/sync/routes?full=1` count 8 for it after the phone's next sync). Saving appends operations there, then sends the whole log through `/route/bundle` to
`POST /sync/push`, with the page's Token field as the bearer token when it is set; so on a server with
per-user tokens the trip lands in that user's replica. Where the push is refused (a server that wants a token
and has none, or no server) the trip stays in the browser and the page says so; "Send to server" retries and
".dsync" saves the same bundle as a file a phone can sync with (`POST /sync?with=<file>`). A phone that syncs with
the server then lists the trip under Routes.

**Getting trips back, and waypoints** (T92, `tools/demo/trip-sync.js`, served as `/trip-sync.js`). "Get trips
from the server" reads the user's routes with `GET /sync/routes?full=1` and merges them into the browser's trip
list: a route the browser lacks, or whose server copy changed after the browser last saved it, comes from the
server (so a trip planned or edited on a phone shows up, and the open trip is shown again); a trip the browser
had pushed or pulled that the server no longer lists was deleted elsewhere and goes. For the open (saved) trip,
"Add waypoint" and a click drop a named waypoint; drag one to move it, rename or remove it in the list. Each edit
is the phone's per-waypoint write (T65: one `wpt:<id>` field per waypoint, null when removed), encoded by
`POST /sync/waypoints/encode` (`{"waypoints": [{"id", "name", "lat", "lon", "ele"?}]}` to `{"values": {"<id>":
"<value>"}}`; ids are lowercase UUIDs; nothing is stored), appended to the browser's log and pushed, so a phone and
the browser editing different waypoints of one trip both keep theirs. `tools/demo-check.py --route ... --trip-sync`
drives it with the real mouse, including a fresh browser that gets the trip by pulling.

## The relay

Two phones that are not on the same network sync through the server without the server reading or
merging anything (T72; `serverlib/.../RelayRoutes.kt`, design and threat model in docs/PLANNING.md,
Sync, Encryption and Threat model). On the phone: Share, then "Sync with a phone anywhere (via
server)": one phone taps New code, the other types that 16-character code and taps Join. Both use the
online server (or the sync peer when it is a URL).

Each phone posts its whole log, encrypted with ChaCha20-Poly1305 under a key derived from the code, to
`POST /sync/relay?slot=<32 hex>&side=<16 hex>[&after=<seq>]`:
- `slot` is derived from the code by HKDF, so the server never sees the code; `side` is a random id per
  phone and session.
- A non-empty body (a `DSEAL` relay frame; anything else is a 400) replaces this side's blob.
- The answer is every other side's blob newer than `after` (200, a `DRLY` container) or 204 when there
  is none; `X-Dystopo-Relay-Ttl` says how long blobs live. `&forget=1` deletes this side's blob.
- `GET /sync/relay/status`: counts (slots, blobs, bytes, posts, stored, delivered, expired, refusals by
  reason) and the limits; never a slot or side id.

Blobs live in memory only (a restart forgets them), for `relay.ttlSeconds` after their last write; the
`relay.*` keys above cap their size, number and the request rate per address (`X-Forwarded-For` counts
only when the connection comes from loopback or a private address, i.e. a proxy in front). The relay
is open on purpose: it holds only ciphertext under slots nobody can guess (80-bit codes), and the
bearer token stays on `/sync/pull|push|status`. The server learns sizes, timing and client addresses,
nothing else; serve it over HTTPS (above) to hide even the slot ids from the network path.

```
curl http://localhost:8080/sync/relay/status
```

## The token

With `token` (or `DYSTOPO_SERVER_TOKEN`) set, `/build`, `/build/*`, `/area` (T75), `/sync` and `/sync/*` (except the
relay, `/sync/relay`) need `Authorization: Bearer <token>`; anything else (tiles, `/out/`, `/catalog`, `/healthz`) stays open. A
missing or wrong token is a 401 with `WWW-Authenticate: Bearer`, logged at `warn`. The comparison is
constant-time. CORS preflights are answered without the token. `POST /catalog/refresh` needs it too.

The browser page sends the token with "Build pack here" (and its progress polls and cancel) when it has
one: open it once as `https://maps.example.org/?token=<token>` (the page stores it in the browser's
localStorage as `dystopo.token` and removes it from the address bar) or type it in the Token field next to
the button; an empty field sends none. Without it the build answers 401 and the page says so. The phone's
sync client sends the token from its sync settings (`POST /sync/settings?token=`). With curl:

```
curl -X POST -H "Authorization: Bearer $TOKEN" "https://maps.example.org/build?name=paradise&bbox=-121.76,46.77,-121.70,46.80&minZoom=8&maxZoom=14"
```

## Users and trip share links (T67)

One server can sync for several people. Each user has a bearer token and **their own sync replica**;
the catalog, builds, tiles and `/out/` stay shared. Code: `serverlib/.../Users.kt` (tokens to users),
`SyncRoutes.kt` (one replica per user), `ShareLinks.kt` (links); `server/.../Gate.kt` (who a request is),
`UserAdmin.kt` (the user flags and `/admin/users`).

**The model in one paragraph** (T92). Three kinds of bearer token, all in the config file: user tokens
(`users`), one per person, each opening that person's own replica and share links; the admin token
(`adminToken`), which manages users and can look into any replica as that user but is not a sync user itself;
and the single T52 `token`, which reaches the default replica `peer.db` (a one-person server, or one configured
before T67). Everything else is shared by everyone the server lets in: packs, the catalog, builds, tiles, `/out/`
and, once users exist, route planning (`/route*` needs any known token then). With no token of any kind the
server is open and everyone is one anonymous user on `peer.db` (how `devtool serve` runs). Tokens are compared in
constant time against every configured token, never logged (paths only, share links redacted; a test checks the
log), and live in plain text in the config file, so the file must be readable by the server's account only (below).

Config keys (both optional; with neither, nor `token`, the server stays open as before):

| Key | Default | Meaning |
| --- | --- | --- |
| `users` | `[]` | `[{"id": "alice", "token": "<at least 16 characters>"}]`. Ids are `[a-z0-9][a-z0-9_-]{0,31}` (they name a file; `peer`, `admin` and `shares` are reserved). Every token must differ. |
| `adminToken` | `null` | The admin token: `/admin/users`, and any user's `/sync*` with `?user=<id>`. `DYSTOPO_ADMIN_TOKEN` overrides it. |

Who a token is:

- **A user token**: `/sync*` runs on `<syncDb's directory>/<id>.db` (by default `out/sync/<id>.db`),
  created on the user's first request with its own device id. Two users never see each other's
  tracks, routes or settings: a pull returns only that replica's operations and a push merges only into
  it. User tokens also open `/build` and `/catalog/refresh`.
- **The admin token**: `/admin/users`; on `/sync*` only as a named user (`?user=alice`: 400 without it,
  404 for an unknown id), to inspect a replica (`/sync/status?user=alice`, `/sync/tracks?user=alice`).
- **The T52 `token`**: still works and reaches the default replica `peer.db`, so a server configured
  before T67 behaves as it did. A user token on `/admin*` is a 403; no token or an unknown one is a 401.

The user a request runs as is decided once, in the gate, and kept on that request only (not in the JDK's
exchange attributes, which are shared by every request on a context).

Making users. On the command line (edits the config file, keeping its other keys, validates it, prints
one JSON object; the token is printed once and never again):

```
server/build/install/server/bin/server --config server.json --add-user alice      # {"added":"alice","token":"..."}; creates server.json if missing
server/build/install/server/bin/server --config server.json --new-admin-token     # {"adminToken":"..."}
server/build/install/server/bin/server --config server.json --list-users          # ids, replica files and sizes, no tokens
server/build/install/server/bin/server --config server.json --remove-user alice   # the replica file stays on disk
```

A running server reads the file only at startup; to add a user without a restart use the admin routes,
which also write the config file when the server was started with one (`persisted`):

```
curl -H "Authorization: Bearer $ADMIN" https://maps.example.org/admin/users                    # users with replica file, bytes, ops, tracks, routes, share links, last request
curl -X POST -H "Authorization: Bearer $ADMIN" "https://maps.example.org/admin/users?id=carol"  # {"added":"carol","token":"...","persisted":true}
curl -X DELETE -H "Authorization: Bearer $ADMIN" https://maps.example.org/admin/users/carol     # token refused at once, her share links revoked, replica kept
```

**The config file holds the tokens** (T92). At start the server checks who can read it and logs
`config_permissions` at `warn` when others can: group or world read bits on POSIX (`chmod 600 server.json`), an
allow entry for Everyone, Users or Authenticated Users on Windows (`icacls server.json /inheritance:r /grant:r
"%USERNAME%:F"`). A warning, not a refusal, because a container mounts the file read-only from the host. The user
flags and `/admin/users` write it owner-only (mode 600) on POSIX.

Tokens from the tools are 32 random bytes in base64url (43 characters). Give each person theirs: the
phone's sync settings (`POST /sync/settings?peer=https://maps.example.org&token=<token>`, or the sync
sheet), `devtool sync with --peer ... --token <token>` (or `DYSTOPO_SYNC_TOKEN`). `/healthz` reports
`users` (configured), `replicas` (replica files on disk) and `shareLinks`.

### Trip share links

A user can share one trip (a planned route) read-only with anyone, by link:

- `POST /sync/share?route=<id>` (the user's token): makes a link to that route in the user's replica, or
  returns the existing one for the same route (`existing: true`). Answer: `token`, `url`
  (`<base>/s/<token>`), `gpx`, `name`, `createdAt`. A 404 means the server's replica does not hold the
  route yet: sync the device first (the phone's "Share link" does).
- `GET /sync/share`: the user's links (with the route's current name and whether it still exists).
- `DELETE /sync/share/<token>`: revokes a link (404 when it is not this user's). From then on every form
  of the link is a 404.
- `GET /s/<token>` (no token needed; the link is the secret): a page with the route on the live map
  (MapLibre GL JS over this server's `/style.json`; trail legs solid, straight legs dashed, start, end
  and waypoints), its name, distance, climb and hike time, and "Download GPX". `GET /s/<token>.gpx`: the
  GPX, byte for byte what the phone writes for that route (`rte` with via marks, `wpt`, our ids), as an
  attachment named after the route. `GET /s/<token>.json`: name, summary and the route as GeoJSON.

A link serves the route as it is now in the owner's replica: an edit synced later shows up, and a
deleted route (once the delete is synced) is a 404 like a revoked link. Removing a user revokes their
links. Links are stored in `shares.json` next to the replicas (not in the sync log). Link tokens are
128 random bits. The `/s/` answers carry `Cache-Control: no-store` (a revocation takes effect at once,
also through a proxy), `Referrer-Policy: no-referrer` (the token is in the URL) and
`X-Robots-Tag: noindex`. The link's base is `publicUrl` when set, else the request's
`X-Forwarded-Proto`/`X-Forwarded-Host` or `Host`, so behind a proxy the link points at the public name;
a phone that syncs with `http://10.0.2.2:8080` (an emulator) gets links with that address.

On the phone: the trip card's "Share link" syncs with the sync peer, asks it for the link and copies it
to the clipboard (debug: `POST /routes/{id}/share`, `GET|DELETE /routes/{id}/share`). On the JVM:
`devtool sync share --peer URL --token T --route ID`, `sync links`, `sync unshare --link <token>`.
`devtool serve` answers the same routes without tokens, on its one default replica.

## Logs

One JSON object per line on stdout: `ts` (ISO-8601 UTC), `level` (`info`, `warn`, `error`), `event`, then
fields.

- `started`: bind, port, config file, directories, packs found, whether a token is set (never the token),
  CORS origins, whether tilebuild is available.
- `request`: `method`, `path` (no query string), `status`, `ms`, `bytes`, `remote` (`X-Forwarded-For`
  first), `source` for tile routes (`pack`, `live-3dep`, `live-copernicus`, `openfreemap`, ...), `outcome`
  when the gate answered itself (`unauthorized`, `refused`, `preflight`, `tooLarge`, `requestTimeout` (T111: a body
  still arriving at `http.maxRequestSeconds`, answered 408), `connectionLost`, `exception`). 5xx at `error`,
  401/403/408/413/429 at `warn`.
- `connection_lost` (warn, T82): the client went away mid-answer (or, before T111, `http.maxRequestSeconds`
  closed a slow upload without an answer). Not a server error; the request line keeps the status that was actually
  sent, 0 when nothing was (`/metrics` counts those under status `none`).
- `glyphs_fetched`, `request_failed`, `config_invalid`, `bind_failed`, `stopping`, `stopped`.
- T82: every string field is cut at 512 characters (`...(N chars)` says how long it was) and lists at 100
  items, so a client cannot make one request write kilobytes of log.
- `config_permissions` (warn, T92): the config file (which holds the tokens) is readable by others.
- No token ever reaches the log (T92, `TokenHygieneTest`): the `Authorization` header is never logged, the path
  is logged without its query string (so a page opened with `?token=` leaves none), share links are logged as
  `/s/…` and `/sync/share/…`, and the tokens `/admin/users` hands out are only in its answer.

## Shutdown

On SIGTERM (or Ctrl+C) the JVM's shutdown hook: `/healthz` turns 503, queued and running builds are
cancelled (their partial `*.building` files are deleted), the listener stops accepting and in-flight
requests get up to half of `shutdownGraceSeconds` to finish; then `stopped` is logged with how long it took.

## Operations (T82)

Running the server for weeks rather than for a sweep. Everything here was checked against a 12-hour soak of
the installed server (`tools/server-soak.ps1`, numbers at the end of this section).

### A service unit

systemd, with the distribution unpacked in `/opt/dystopo` and the data in `/var/lib/dystopo`
(`useradd --system --home-dir /var/lib/dystopo dystopo` first):

```
# /etc/systemd/system/dystopo.service
[Unit]
Description=Dystopo map server
After=network-online.target
Wants=network-online.target

[Service]
User=dystopo
WorkingDirectory=/var/lib/dystopo
Environment=JAVA_OPTS=-Xmx4g
EnvironmentFile=-/etc/dystopo/env              # DYSTOPO_SERVER_TOKEN=..., DYSTOPO_ADMIN_TOKEN=...
ExecStart=/opt/dystopo/bin/server --config /etc/dystopo/server.json
# SIGTERM runs the graceful shutdown (builds cancelled, requests drained); give it shutdownGraceSeconds.
TimeoutStopSec=20
Restart=on-failure
RestartSec=5
LimitNOFILE=65536
NoNewPrivileges=true
ProtectSystem=strict
ReadWritePaths=/var/lib/dystopo /etc/dystopo   # /etc/dystopo only if /admin/users should persist users
PrivateTmp=true

[Install]
WantedBy=multi-user.target
```

`systemctl enable --now dystopo`, logs with `journalctl -u dystopo` (journald caps its own size:
`SystemMaxUse=` in `journald.conf`). In `/etc/dystopo/server.json` set `"bind": "127.0.0.1"` and put a
reverse proxy in front (above).

Docker Compose, with Caddy terminating TLS (the server is not published on the host at all):

```
# compose.yaml
services:
  dystopo:
    image: dystopo-server                    # docker build -f server/Dockerfile -t dystopo-server .
    restart: unless-stopped
    stop_grace_period: 20s
    environment:
      JAVA_OPTS: -Xmx4g
      DYSTOPO_SERVER_TOKEN: ${DYSTOPO_SERVER_TOKEN}
    volumes:
      - ./server.json:/opt/dystopo/config/server.json:ro
      - cache:/data/cache
      - out:/data/out
    logging:
      driver: json-file
      options: { max-size: "50m", max-file: "5" }   # the log cap when the log goes to stdout
    healthcheck:
      test: ["CMD", "bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8080 && printf 'GET /healthz HTTP/1.0\\r\\n\\r\\n' >&3 && head -1 <&3 | grep -q ' 200 '"]
      interval: 30s
      timeout: 5s
      retries: 3
  caddy:
    image: caddy:2
    restart: unless-stopped
    ports: ["80:80", "443:443"]
    command: caddy reverse-proxy --from maps.example.org --to dystopo:8080
    volumes:
      - caddy-data:/data
volumes: { cache: {}, out: {}, caddy-data: {} }
```

### The reverse proxy is part of the defence

The JDK HTTP listener reads a request's headers on a worker thread (`threads`, 32). The probe's slowloris
test (64 connections that send one header line a second) stalled `/healthz` for exactly
`http.maxRequestSeconds` (60 s), when the listener closed them (T111: the listener's timer now runs 5 s after that
limit, so slow headers are closed at 65 s; a slow body gets the gate's 408 at 60 s); with the JDK default (no limit)
it never answered and the connections were never closed. nginx and Caddy read the whole request before they forward
it (nginx: `proxy_request_buffering on`, the default), so behind them a slow client costs the proxy a
connection, not the server a thread. Expose the server directly only on a trusted network. Request bodies
are capped by the server whatever the proxy does: 256 MiB on `/sync*` (a whole-log push), the relay's
`relay.maxBlobBytes`, 16 MiB elsewhere (the route planner's own cap is 8 MiB); a declared `Content-Length`
over the cap is a 413 before anything is read, a chunked body is cut at the cap while it is read.

### Backups

| What | Where | How |
| --- | --- | --- |
| Sync replicas (users' tracks, routes, settings) | `out/sync/*.db` (`syncDb`'s directory) | SQLite files: `sqlite3 out/sync/alice.db ".backup '/backup/alice.db'"` is safe while the server runs (the online backup API); a plain `cp` is safe only with the server stopped. |
| Share links | `out/sync/shares.json` | A small JSON file, rewritten whole on each change: copy it. |
| Config and tokens | `server.json` (and `DYSTOPO_*_TOKEN` in the environment file) | With the replicas: a replica without its user's token cannot be reached. `/admin/users` writes the file when it adds or removes a user. |
| Packs | `out/*.pmtiles` | Optional: they can be rebuilt (`/build`) or re-copied; their `.sha256` sidecars are recomputed when missing. |
| Cache | `cacheDir` | Never: everything in it is downloaded or rendered again on demand. |

Restore with the server stopped: put the files back, start it. A replica restored from an older backup
is not a problem for sync: devices push what the server lacks on their next sync (the log is an operation
log; see docs/PLANNING.md, Sync), so restoring loses nothing a device still holds. Without the replicas,
users keep their data on their devices and push it again.

### What to monitor

`GET /healthz` for liveness (an orchestrator restarts on a non-200), `GET /metrics` for everything else
(scrape `?format=prometheus` every minute, or poll the JSON):

- `jvm.heapUsedBytes` against `heapMaxBytes`, and the trend of `heapAfterGcBytes` (what survived the last
  collection): a steady climb over hours is a leak; the soak's numbers below say what is expected.
- `requests.<route>.status.5xx` (`dystopo_requests_total{status="5xx"}`): any rise; `live.errors` counts tile
  route exceptions. `status.none` counts requests that were never answered (the client went away first); T111:
  a body still arriving at `http.maxRequestSeconds` is answered 408 and counted under `4xx` and `requestTimeouts`
  (`dystopo_request_timeouts_total`), so a rising `none` means clients giving up on slow answers.
- `requests.<route>.p95Ms` for `dem`, `osm`, `contours`, `shade`: pack tiles answer in tens of
  milliseconds; live tiles of a place nobody viewed take seconds (10 to 30 s at worst).
- `disk.freeBytes`, and the growth of `disk.cacheDir.bytes` (per directory under `children`): the tile
  stores stay under `cache.*` (T74; `/stats` `caches`), the source downloads still grow with every new
  place viewed; `out` grows with builds and replicas.
- `jvm.openFiles` (Linux) or the process handle count (Windows), `jvm.threads`: flat under steady load.
- `log.writeErrors` (the log file could not be written: disk full) and `log.rotations`.
- `relay.bytes` and `relay.refused` (`full`, `rateLimited`) for the relay.

Prometheus, scraping through the private address (the proxy need not expose `/metrics`):

```
scrape_configs:
  - job_name: dystopo
    metrics_path: /metrics
    params: { format: [prometheus] }
    static_configs: [{ targets: ["127.0.0.1:8080"] }]
```

`/metrics` is unauthenticated, like `/healthz`: anyone who reaches the server can read it. It carries no
tokens, user ids or slot ids, but it does expose the log file's path, the cache and out directories'
sizes per top-level directory, the free disk space, request counts per route and the JVM's memory, which
an operator may not want public. Restrict it at the reverse proxy (`location = /metrics { allow 127.0.0.1;
deny all; }` in nginx, `respond /metrics 403` in Caddy) and scrape it on the private address, or turn it
off with `metrics.enabled: false`. Share-link tokens are never logged: `/s/<token>` and
`/sync/share/<token>` appear in the request log as `/s/…` and `/sync/share/…`.

### The log cap

On stdout (the default) the collector caps the log: journald's `SystemMaxUse`, Docker's
`--log-opt max-size=50m --log-opt max-file=5`. With `log.file` the server rotates it itself at
`log.maxMb` (64), keeping `log.keep` (5) old files, so the log takes at most about 384 MB. Every string
field is cut at 512 characters. At `logRequests: true` each request is one line of about 190 bytes: a
server answering 10 requests a second writes about 160 MB a day, so either cap matters; `logRequests: false`
keeps only errors, refusals, startup and shutdown.

### Soak and probe

```
python -I tools/server-probe.py --port 8080 --token <token> [--admin-token <admin>] [--quick]   # bad input on every route family: JSON, exit 0 when all answered as expected and the server stayed up
python -I tools/server-probe.py --port 9090 --quick   # T87: an open server (no token): sync, catalog refresh and /admin get their open answers, builds are still refused for their bbox; `serverOpen` in the JSON
powershell -ExecutionPolicy Bypass -File tools\server-soak.ps1 -Hours 12 -Port 9090 -Root E:\soak -Packs out\rainier-dem.pmtiles,out\rainier-outdoors.pmtiles -HeapMb 6144
powershell -ExecutionPolicy Bypass -File tools\server-soak.ps1 -ChartOnly -Root E:\soak         # redraw soak.svg and summary.json, also while it runs
powershell -ExecutionPolicy Bypass -File tools\server-soak.ps1 -Minutes 60 -Port 9091 -Root out\soak-hourly -SampleMinutes 5 -TripMinutes 5 -ShareMinutes 5 -BuildMinutes 5 -ProbeMinutes 5 -Packs out\rainier-dem.pmtiles,out\rainier-outdoors.pmtiles   # T111: the hourly items every 5 minutes, to catch what happens once an hour
```

The probe sends malformed tile coordinates, map options, traversal attempts on `/out/`, `/glyphs/` and
`/s/`, broken ranges, bad JSON and deep nesting, malformed relay frames, wrong and missing tokens, an
over-long request line, 10 000 headers, a 1 MB header, lying, negative and duplicate `Content-Length`,
declared and chunked bodies over the cap, a gzip bomb to `/sync/push` (413 since T74), unknown `units=`
and glyph ranges (400 and 404, strict since T74), `/version` and the asset manifest, non-HTTP bytes, a slow header drip,
a 64-connection slowloris and 300 idle connections, and checks `/healthz` after each. The soak starts its
own server (own cache and out directories under `-Root`, ports 9090 to 9099, stopped by its PID), walks the
pack's tiles at z8 to z14 with styles, glyphs, `/out/` ranges, routes, sync, relay posts, a trip saved
every 10 minutes, a build every hour, the quick probe every hour and a new live tile column every two
minutes, samples `/metrics`, the OS process and a class histogram (a full GC, so the live heap is exact)
every 10 minutes into `soak.jsonl`, and ends with the full probe, `soak.svg` and `summary.json`. The
nightly workflow runs a 20-minute version (`server-soak` job) on a pack it builds itself. T107: each sample
also carries `/stats` `sourceDownloads` (the elevation readers' downloads under the T92 budget), which the chart
draws in its own panel (downloaded since start, in the last hour, the largest one) and `summary.json` summarises
(`sourceMb`, `sourceRequests`, `sourceLastHourMb`, `sourceRefusedRequestCap`, `sourceRefusedHourCap`); each URL of
a round is its own curl operation, so a header (the sync lines' token, the `/out/` line's `Range`) goes only
with its own request; and the trip's route and bundle carry the soak user's token, which the T92 route gate
had refused since, so no trip had been saved or shared (a failed trip route is now a `tripRoute` event). T111:
every trip (`routeMs`, `bundleMs`, `pushMs` and their statuses) and share link (`ms`) is an event in `soak.jsonl`,
so a slow request lines up with what the soak did; `-ShareMinutes` sets the share link's interval (60); the
server logs every request (`logRequests`, the default) to `<Root>/logs/server.log`; `summary.json` `unanswered`
has the requests the server never answered (its own `/metrics` status `none`) per route, the slowest of each
such route and the samples at which a count grew (counted after the final probe, which the soak follows with one
more `/metrics` read, else at the last sample: `countedAt`), and the chart's request panel draws their total.

T92: the soak ends with a verdict, printed one check per line (`PASS name: why` / `FAIL name: why`) and kept
under `verdict` in `summary.json`, and exits 0 only when every check passes: the server alive when the load
stopped and after the final probe, the final probe without unexpected answers, no `OutOfMemoryError` in the
kept logs, failed requests (5xx or no answer) at most `-MaxFailureRate` of all (default 0.001: a cold live
render may fail once in a hundred thousand; the post-T80 soak exited 1 on exactly one 500 in 102,792 requests),
and the live heap after full GC growing at most `-MaxHeapGrowthMbPerHour` (default 5) over the second half.
`-ChartOnly` redraws the chart, rewrites `summary.json` with the verdict, prints the same lines and exits 1 on a
FAIL. A run that did not reach its end (no `summary-extra.json`: the account signed out, the machine slept, the
script was killed; or one still going) gets a partial verdict from the samples: the server alive at the last
sample with no `serverExited` event, the last hourly probe in place of the final one, no `OutOfMemoryError`, the
failure rate, the unanswered requests and the live heap growth; its last line reads `verdict: PASS [partial run:
6.34 of 12 h planned, no final probe]`, and a server that exited on its own or an out-of-memory line is still a
FAIL. T111: the `unanswered` check fails when the server left more than `-MaxUnanswered` (0) requests without an
answer, and its line names each route with its count and slowest request (`FAIL unanswered: 12 requests never
answered (server /metrics status none): route 12 (slowest 60914.6 ms); limit 0` for T107's soak). The probe (T92) carries the token on its route
probes when `/healthz` says `routeGate` and checks that `/route*` answers 401 without one (and stays open on a
server without users); run it once against each kind of server.

### Measured (T82 and T92, October 2026)

Two soaks of the standalone server on one Windows machine (JDK 21, 32 logical CPUs, `-Xmx2g`), each with the
Rainier pack and the load above: about 2.4 requests a second (12 pack tiles of each kind per 20-second round, the
rest of the mix, a new live tile column every two minutes), sampled every 10 minutes. The first (T82c, 11 October,
after the T80 fixes) ran the full 12 hours: 102,792 requests and 1 failed (a cold live render), live heap after a
full GC 6 to 174 MB (+0.22 MB/h over the second half), working set 121 MB at the start, 1.69 GB at its peak and
0.99 GB at the end, threads 22 to 114, handles 621 to 1,371, cache 2.8 GB. The second (T92, 13 October, with the
tile caps and the source budget) is below. The first soaks of T82 found two defects that are fixed: an unbounded
geoid-offset cache (2.2 GB of live heap on a US pack) and a gzip bomb to `/sync/push` that was an
`OutOfMemoryError` behind a valid token (a 413 since T74); away from the US (the Mont Blanc pack, no live column)
that soak held a live heap of about 145 MB and 81 threads.

**T92: 6.34 of 12 hours, the end was not the server.** The Windows session that ran the soak was signed out at
23:50 local (03:50 UTC); that killed the script and the server together, mid-round. The server log stops there with
no error and no shutdown line, `stderr` is empty, the last sample (03:44 UTC) was healthy (live heap 187 MB, 0
failures), and the System log shows the session ending (a shutdown veto by `steam.exe` eight seconds later, the
logoff notifications; no reboot). A detached soak does not outlive its account's session: run a long one as a
scheduled task that runs whether or not the user is signed in, or on a machine nobody signs out of. `-ChartOnly` then
judged the 39 samples (21:24 to 03:44 UTC): PASS on all five checks of a partial verdict (see Soak and probe).

- **Requests.** 53,356 from the load, 0 failed (no 5xx, no timeout): 51,232 answered 200, 1,062 206, 1,062 204.
  The server log has 0 `5xx` and 0 `OutOfMemoryError`. The hourly quick probe ran 7 times, 72 probes each, 0
  unexpected, and left 14 `connection_lost` warnings, `live.errors` 14 and 7 `/route/edit` requests of 60.9 s
  with no status (the probe's short-body case, cut at `http.maxRequestSeconds`; they are the `route` maximum;
  answered 408 since T111).
  The hourly rebuild of a small pack finished 7 of 7.
- **Heap: flat after warm-up, a slow drift.** Live heap after a full GC went from 6 MB to 181 MB in the first 10
  minutes (97 MB of float arrays that never change after that, 66 MB of byte arrays) and then stayed between 172
  and 189 MB: median 174.5 MB at 1 to 2.5 hours, 179.4 MB at 4.8 to 6.3 hours. The summary's least-squares fit
  over the second half is +1.69 MB/h (limit 5); a median-slope fit from the first hour on is +1.3 MB/h. The four
  samples over 185 MB are the ones whose byte arrays were 73 to 76 MB instead of 65 to 69, most likely the buffers
  of a request round in flight, and they sit at the end of the window, which is why the least-squares figure is
  the higher one. Strings (1.2 to 2.4 MB) and map entries grow with the number of cached files, consistent with the
  tile stores' in-memory index (one entry per file). If +1.3 MB/h held, a 2 GB heap has more than a thousand hours
  of room. GC took 24 s in 6.3 hours.
- **Working set and threads.** Working set 105 MB, then 0.9 GB after the first hour, 1,009 MB at the end (private
  bytes 203 MB, 1,096 MB); the second half grows 12.8 MB/h, mostly the heap the JVM keeps committed (650 to 737 MB)
  and 4 MB of non-heap, not live data. The run was too short to see it level off; T82c's working set came back
  down from 1.69 GB. Handles 633, then 1,330 to 1,414 from the first half hour on (the first T82 soak had 3,300 on
  the same kind of load), JVM threads 18, then 101 to 114 (it had up to 407), classes loaded constant at about
  6,290.
- **Disk.** `cache/` went from 0 to 1.4 GB in the first hour (the first pass over the pack's edge), 2.60 GB and 37,013
  files at 6.3 hours, +233 MB/h over the second half: `3dep1m/` +101 MB/h (980 MB), `derived/` +66 MB/h (413
  MB), `3dep13/` +59 MB/h (680 MB), `live/` +7 MB/h (510 MB); Copernicus 15 MB, OpenFreeMap 2.4 MB. No cap was
  reached (`live/` 510 of 4,096 MB, `derived/` 413 of 1,024, OpenFreeMap 2.4 of 2,048), so eviction was not
  exercised; at this rate `derived/` would reach its cap after about 9 more hours. The two source directories
  (1.7 GB) have the download budget but no size cap, and every new place the soak adds grows them: a server that
  keeps viewing the same places should stop growing. `/stats` `sourceDownloads` was not sampled (the soak reads
  `/metrics`), so the budget's counters are not in this run; no request failed, so nothing shows it refusing a
  tile. `out/` 144 to 146 MB (replicas, the hourly rebuild).
- **Latency.** Client p95 per interval, pack tiles: 7.2 s in the first interval (the first pass renders contours
  and shades at the pack's edge), then 83 to 138 ms (median 115). Live tiles: 1.2 to 7.8 s (median 2.6 s) from
  about 20 requests an interval, no trend. Other routes 13 to 30 ms. Over the run the server's own histogram has
  contours p50 at most 50 ms and p95 at most 250 ms (bucket bounds), the slowest 18.0 s; slowest `dem` 8.8 s, `shade`
  9.4 s; `/metrics` p50 at most 250 ms, slowest 2.7 s.
- **Log.** 55,312 lines and 10.0 MB at 6.4 hours (about 190 bytes a line, 1.6 MB/h), no rotation, 0 write
  errors.

### Measured (T107 and T111, October 2026)

**11.4 hours flat; the hourly request with no status was the probe's.** T107's soak (15 October UTC,
the T92 load plus trips with the user's token, cut at 11.4 h by a restart): 97,366 requests, 0 failures, live heap
187 to 200 MB after the first half hour (+1.1 MB/h over the second half), working set 0.96 to 1.06 GB, handles
1,303 to 1,430, every hourly probe green; but `/metrics` counted one more `route` request with status `none` after
each hourly probe, 12 in all, the slowest 60.9 s. T111 named it with two one-hour soaks that ran every hourly
item every 5 minutes (the recipe under Soak and probe): on the old server 12 requests were left unanswered, every
one the quick probe's `short-body` case (`POST /route/edit`, 50 bytes declared and 6 sent), closed by the JDK
listener at 60.0 to 61.0 s (`outcome: connectionLost`); the 12 trips (route 22 to 82 ms), 11 share links, 11
builds and the full GC every 5 minutes never left one. With the gate's 408 the same hour had the 12 short bodies
answered 408 at 60.0 s and 0 unanswered (`verdict: PASS`, 5,593 requests, live heap 185.8 MB, -1.2 MB/h).
Finding `docs/findings/2026-10-16-soak-route-requests-without-status.md`.

## Limits

- One process, one disk: no clustering. Live tiles are cached on disk, so a restart is warm; the first view
  of a new place costs 10 to 30 s of downloads and rendering.
- Upstreams are public services with fair-use expectations: OpenFreeMap (at most 4 requests in flight),
  Overpass (one request per build box, area-capped), USGS and Copernicus cloud files. A busy public server
  should run its own Overpass and host planet tiles itself.
- `/build` limits are per job (`build.*`); there is no per-client quota beyond the token.
- The token is a shared secret, not per-user accounts; sync has no signatures yet (peer-to-peer plan in
  docs/PLANNING.md).
- Packs added to `outDir` by hand are picked up at the next start (`/build` packs immediately); removing a
  pack's files while serving needs a restart.
- On Windows, Ctrl+C in the console runs the graceful shutdown; killing the process (Task Manager,
  `Stop-Process`) skips it.
