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
| `cacheDir` | `"cache"` | Downloads and generated tiles (`live/`, `openfreemap/`, `derived/`, `copernicus30/`, `3dep13/`, `3dep1m/`, `overpass/`, `glyphs/`, ...). The tile stores are capped (`cache.*`); the source downloads are not. |
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
| `extractDownloads.urls` | `[]` | Geofabrik regions the server may download into `extractsDir` when an outdoors build needs one (T66, below), e.g. `["https://download.geofabrik.de/europe/alps"]` (a `-latest.osm.pbf` or `.poly` URL works too). `devtool serve` reads `DYSTOPO_EXTRACT_DOWNLOADS` (comma-separated) instead. |
| `extractDownloads.maxMb` | `4096` | Largest extract downloaded (by `Content-Length`, checked again while streaming). |
| `log.file` | `null` | T82: write the JSON log to this file instead of stdout, rotating it (below, Operations). Null: stdout (cap it in journald or Docker). |
| `log.maxMb` | `64` | Rotate the log file at this size. |
| `log.keep` | `5` | Rotated files kept (`server.log.1` .. `.5`); the log never takes more than about `(keep + 1) * maxMb`. |
| `metrics.enabled` | `true` | `GET /metrics` (below). |
| `metrics.diskScanMinutes` | `10` | How often `/metrics` re-measures `cacheDir` and `outDir` (a walk of every file, in the background, at low priority); `0` turns it off. |
| `http.maxRequestSeconds` | `60` | A client must send its whole request (headers and body) within this time, else the connection is closed. Bounds slow-header clients; a sync push slower than this fails (behind a proxy that buffers requests it only bounds the proxy's hop). |
| `http.idleSeconds` | `30` | Idle keep-alive connections are closed after this. |
| `http.maxConnections` | `1000` | Open connections at once; beyond, new ones are closed at once. |
| `cache.tileDir` | `null` | T74: where the tile stores live (`live/`, `openfreemap/`, `derived/`); null: `cacheDir`. `devtool serve` reads `DYSTOPO_TILE_CACHE`. |
| `cache.liveMb` | `4096` | Byte cap of the live DEM tiles (`live/best-v2/`), least recently used evicted first. |
| `cache.openfreemapMb` | `2048` | Byte cap of the OpenFreeMap tiles (`openfreemap/<build>/`, every planet build; old builds go first). |
| `cache.derivedMb` | `1024` | Byte cap of the contour and shade tiles the server keeps (`derived/`). `devtool serve` reads all three from `DYSTOPO_CACHE_CAPS=live=4096,openfreemap=2048,derived=1024`. |

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
- `POST /build?name=&bbox=w,s,e,n&minZoom=&maxZoom=[&profile=outdoors][&source=auto|extract|overpass][&fallback=1][&overwrite=1]`,
  `GET /build`, `GET|DELETE /build/{id}`, `GET /build/sources?bbox=` (`BuildRoutes.kt`; outdoors sources below). Token-protected.
- `POST /area` (T75): the manifest of an area of interest, a box (a pack built through `/build`) or a route
  corridor (tile lists); `GET /area` for the parameters. Below, Areas of interest. Token-protected.
- `/sync/pull`, `/sync/push`, `/sync/status`, `/sync/tracks[/{id}.gpx]`: the sync peer
  (docs/PLANNING.md, Sync). Token-protected.
- `POST /sync/relay?slot=&side=[&after=][&forget=1]`, `GET /sync/relay/status`: the encrypted relay
  between phones (below). Open, without the token.
- `GET /catalog` (also `/catalog.json`): the pack catalog, below. `POST /catalog/refresh`: rehash every
  file (token-protected).
- `GET /metrics` (T82; `?format=prometheus` for the Prometheus text format): what to watch on a long-running
  server, open like `/healthz` (no secrets, no user ids). Below, Operations.
- `GET /healthz`: `status` (`ok`, or `stopping` with a 503), `uptimeS`, `packs`, `outDirWritable`,
  `cacheDirWritable`, `glyphs` (fonts present), `errors` (tile route exceptions so far), `catalog` (the
  startup hash pass: `state` `cold`, `warming` or `ready`, `files`, `hashed`, `hashesComputed`, `ms`).

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

`devtool serve` (both modes) answers `/catalog` and `/catalog/refresh` too (no token there).

## Areas of interest (T75)

"Tell us where you are going": a box or a route corridor with a zoom range, expanded to a **manifest** of
everything the map needs there offline, with sizes. The phone downloads it item by item, verifies what it
gets, and pins the result so its caches do not evict it (docs/PLANNING.md, Design goals, principle 4). The
format is the one a phone will publish to other phones on a trip (T78), so it is versioned and documented
field by field here. Code: `shared/.../areas/` (`AreaOfInterest`, `AreaManifest`, `TileCover`, `AreaPins`),
the server side in `BuildRoutes.kt`.

```
curl -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  --data '{"kind":"corridor","line":[[-121.7356,46.7859],[-121.7355,46.8016],[-121.7268,46.8037]],"bufferM":500,"minZoom":8,"maxZoom":15}' \
  https://maps.example.org/area
curl -X POST -H "Authorization: Bearer $TOKEN" "https://maps.example.org/area?bbox=-121.76,46.77,-121.70,46.80&minZoom=8&maxZoom=14&name=paradise"
```

`POST /area` takes the area as a JSON body (the area object below, or `{"area": {...}, "pack": 0}`) or as
query parameters (`bbox=w,s,e,n` or `line=lat,lon;lat,lon;...` with latitude first, `bufferM`, `minZoom`,
`maxZoom`, `name`). Options (query or body): `pack=1|0` (default: 1 for a box, 0 for a corridor), `profile`,
`fallback`, `source` (as for `/build`), `retry=1` (build again after a failed build). It is token-protected
like `/build`, because a box starts a build. `GET /area` lists the parameters and limits.

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

### The manifest, field by field (version 1)

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

- `version`: the format version (1). A reader refuses a newer major version rather than misreading it.
- `id`: the area's content id, `a` + 16 hex digits of the SHA-256 of the geometry (coordinates rounded to
  1e-6 degrees, the buffer) and the zoom range. The name is not part of it, so the same area asked twice (or by
  two phones) has the same id; the phone keys its area records by it.
- `name`: the area's name, else the pack name (box) or the id.
- `status`: `ready` (every item can be fetched now), `building` (HTTP 202: a pack is being built; `build`
  is the `/build` job, ask again when it is done) or `failed` (`notes` says why).
- `source`: who made the manifest when it is not the server answering (`phone`, `phone (public sources)`;
  a phone publishing to another phone puts its address here). Null from the server.
- `generatedAt`: ISO-8601 UTC.
- `area`: the request, normalised. Box: `{"kind": "box", "bbox": [west, south, east, north], "minZoom", "maxZoom",
  "name"}` (west < east: no antimeridian crossing; split such boxes). Corridor: `{"kind": "corridor", "line":
  [[lon, lat], ...]` (GeoJSON order, 1e-7 degrees), `"bufferM"` (10 to 20000, metres on each side), zooms, name}.
  Zooms 0 to 16.
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
- `build`: the `/build` job (as `GET /build/{id}` answers it) while `status` is `building` or `failed`.
- `notes`: plain sentences about choices made (fallbacks, the DEM start zoom).
- `pack` (server, box only): the pack name, so a client can find it in `/catalog` later.

Measured (T75, 2026-10-12, emulator against `devtool serve --live` on the same host): the Paradise to Panorama
Point trip (the route of `skyline-loop.gpx`, 2.5 km, 550 m buffer, z8 to z15) is 236 tiles estimated at 29.6 MB;
the phone fetched 180 of them (18.1 MB, 56 were already cached) in 19 s.

How the phone uses it: CLAUDE.md, Android (areas of interest); in short, `pack` items go through the
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
are not capped yet.

## Route planning (T68)

The browser map plans routes on the server with the phone's shared code (`RouteRoutes.kt`: T45's router, T60's
route editor and trip summary), so a route planned on the desktop has the numbers the phone shows. Each pack in
`outDir` with a vector archive gets a trail graph on first use, written next to it as
`<vector archive>-graph.idx` (the phone's and `devtool route`'s file name, e.g. `rainier-outdoors-graph.idx`;
reused while both archives are unchanged; the Rainier graph builds in about 1.5 s). Since T76 the file holds z11
graph tiles that load on demand into one LRU (`GET /route/status` `tileCache`), and a route runs over every pack
whose box comes within 5 km of its points, so it crosses from one pack into the next (docs/PLANNING.md ›
Routing). The routes are open (no token): they read packs and write nothing but the graph files.

**Long drives (T77).** Drives whose ends are 40 km or more apart search the tiles away from their ends through a
drive overlay (per graph tile of one pack: the border crossings of tertiary and larger roads and the cheapest
in-tile drive between them), then expand it back into the pack's edges, so a drive across several packs plans in
well under a second once the graphs are open (Seattle to Yakima over three Washington packs, 232 km: 0.66 s on a
fresh server with the graph files on disk, 0.2 to 0.4 s warm; numbers in docs/PLANNING.md › Routing). Cells are
built when a drive first reaches them, and every tile of a pack is built in the background once its graph opens
(`GET /route/status` `driveOverlay`, `warm`); they are kept in memory, up to 65,536 cells, and built again after a
restart. A phone with this server set asks it for routes whose ends lie beyond its own packs (the same
`GET /route`), keeps the answer as a trip and downloads its corridor (`POST /area`, below) to navigate offline.

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
  `via`, `legs` (`kind` trail/straight/fixed, `coordinates` as `[lon, lat, ele]`), `summary` (distance, ascent,
  descent, hike-model time, `straightM`, a 100-sample profile), `notes` (why a leg is straight), `graph`
  (`built`, `loaded` or `cached`, the reported pack's; every pack used under `graphs`; T77: `searches`, one per
  leg searched, with `ms`, `tiles`, `overlayTiles`, `expanded` and the search's memory) and `ms`. Bad input is a 400 with `error`.
- `POST /route/gpx` with `{"path", "name", "id"}`: GPX (one `rte` with via marks and our ids), byte for byte
  what the phone exports for the same trip.
- `POST /route/bundle` with `{"device", "ops"}`: the page's own route operations (put, set field, delete; one
  device, HLC-stamped) as a gzipped sync bundle, for `POST /sync/push` or a `.dsync` file.
- `GET /route/packs` (packs and graph state), `GET /route/status` (request counts, builds, timings, `tileCache`,
  `lastRouteMemory`, T77 `driveOverlay`: cells, builds, hits, border nodes, shortcuts and the per-pack `warm` state), and
  `GET /trips.js`, the page's editor script (`tools/demo/trips.js` next to the served page).

**Save as trip.** The page keeps its trips in its own sync replica in the browser (localStorage
`dystopo.trips`: a device id, a hybrid logical clock, and the same put + `path` + `waypoints` operations the
phone's store writes). Saving appends operations there, then sends the whole log through `/route/bundle` to
`POST /sync/push`, with the page's Token field as the bearer token when it is set; so on a server with
per-user tokens the trip lands in that user's replica. Where the push is refused (a server that wants a token
and has none, or no server) the trip stays in the browser and the page says so; "Send to server" retries and
".dsync" saves the same bundle as a file a phone can sync with (`POST /sync?with=<file>`). A phone that syncs with
the server then lists the trip under Routes. The page only pushes: edits made on the phone do not come back to
the browser.

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
  when the gate answered itself (`unauthorized`, `refused`, `preflight`, `tooLarge`, `connectionLost`,
  `exception`). 5xx at `error`, 401/403/413/429 at `warn`.
- `connection_lost` (warn, T82): the client went away mid-answer, or `http.maxRequestSeconds` closed a slow
  upload. Not a server error; the request line keeps the status that was actually sent.
- `glyphs_fetched`, `request_failed`, `config_invalid`, `bind_failed`, `stopping`, `stopped`.
- T82: every string field is cut at 512 characters (`...(N chars)` says how long it was) and lists at 100
  items, so a client cannot make one request write kilobytes of log.

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
`http.maxRequestSeconds` (60 s), when the listener closed them; with the JDK default (no limit) it never
answered and the connections were never closed. nginx and Caddy read the whole request before they forward
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
  route exceptions.
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
powershell -ExecutionPolicy Bypass -File tools\server-soak.ps1 -Hours 12 -Port 9090 -Root E:\soak -Packs out\rainier-dem.pmtiles,out\rainier-outdoors.pmtiles -HeapMb 6144
powershell -ExecutionPolicy Bypass -File tools\server-soak.ps1 -ChartOnly -Root E:\soak         # redraw soak.svg and summary.json, also while it runs
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
nightly workflow runs a 20-minute version (`server-soak` job) on a pack it builds itself.

### Measured (T82, October 2026)

Two soaks side by side on one Windows machine (JDK 21, `-Xmx6g`, about 2.5 requests a second each: 12 pack
tiles of each kind per 20-second round plus the rest of the mix), sampled every 10 minutes; the numbers
below are from the first hours (the PR carries the final 12-hour charts):

- **Heap: one unbounded cache, in serverlib.** With the Rainier pack and the live column, the live heap
  (after a full GC) was 2.45 GB after 30 minutes and then grew about 0.15 GB an hour. A heap dump put
  2.19 GB of it in `Geoids.offsetCache`: the NAVD88-to-EGM2008 offsets (one `FloatArray`, about 0.73 MB)
  of every US live window ever rendered with 3DEP or lidar, 3023 of them, never evicted (the map's bins
  had also turned into trees: `MercatorWindow` keys collide). The edge of a US pack fills it fastest: the
  first walk over the Rainier pack's 921 tiles at z8 to z14 with `-Xmx1g` ran out of memory within two
  minutes. Until that cache is bounded, give a server that renders US live tiles 4 GB of heap or more, or
  restart it daily. Away from the US (the Mont Blanc pack, no live column) the live heap stayed at about
  145 MB.
- **Threads.** Up to 407 on the US soak, 287 of them `ForkJoinPool.commonPool` workers: `RemoteTiff.prefetch`
  runs blocking range reads in a parallel stream, and the common pool adds compensation threads up to its
  cap (its parallelism plus 256). They idle out after a minute; the Mont Blanc soak held 81 threads.
  Handles (Windows): about 3 300 on the US soak, 1 090 on the other; flat after the first half hour.
- **Disk.** The cache has no cap yet (T74): with one new live column every two minutes it grew 1.45 GB in
  the first 40 minutes (3DEP 1 m lidar range reads 0.52 GB, rendered live tiles 0.48 GB, 3DEP 1/3" 0.34 GB,
  Copernicus 0.17 GB). `out/` grew only by the replicas and the rebuilt pack (a few MB).
- **Latency.** Warm pack tiles p95 34 to 40 ms; while the first cycle rendered live tiles at the pack's
  edges, p95 was 6 s and the slowest contour tile 21 s. `/metrics` answers in under a second.
- **Errors.** 4 contour tiles answered 500 in the first 40 minutes (cold live renders whose upstream
  reads failed; the two retried by hand answered 200). The quick probe passed every hour. The full probe
  passes on Linux (CI) and Windows except one known answer: `/sync/push` inflates a gzipped bundle with no
  cap on the inflated size, so 1 GiB of zeros in a 1 MB body is an `OutOfMemoryError` (a 500; the server
  survives) with a 1 GB heap, and a 400 with 3 GB. The token keeps strangers out of `/sync/push`, so only a
  user can do this.
- **Log.** About 190 bytes a request at `logRequests: true`: 1.1 MB in 40 minutes at this rate.

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
