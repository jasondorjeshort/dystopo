# Dystopo server

The always-on peer as a program you can deploy: the routes of `devtool serve --live` (tiles from packs,
else rendered live; pack builds; pack downloads; the sync peer; attribution) plus a config file, JSON
logs, `/healthz`, a pack catalog, an optional bearer token, a CORS allowlist and a graceful shutdown.

Code: `server/` (the program: `Main.kt`, `ServerConfig.kt`, `Gate.kt`, `JsonLog.kt`) over `serverlib/`
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
| `cacheDir` | `"cache"` | Downloads and generated tiles (`live/`, `openfreemap/`, `copernicus30/`, `3dep13/`, `3dep1m/`, `overpass/`, `glyphs/`, ...). |
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

## Routes

The same as `devtool serve --live` (`LiveTiles.kt` documents each), plus three:

- `/` browser map, `/phone` phone style page, `/style.json?units=ft|m` (or `?lat=&lon=`), `/live/info`,
  `/live/units`, `/live/sources?bbox=&dz=&oz=` (sources and attribution of a view), `/stats`.
- `/dem/{z}/{x}/{y}.png`, `/contours/{z}/{x}/{y}.pbf?units=`, `/osm/{z}/{x}/{y}.pbf`: packs first, else
  live (`X-Dystopo-Source` says which; live OpenFreeMap tiles below z10 come without glacier polygons, which
  OpenMapTiles cuts at tile edges there, T66). `/glyphs/{fontstack}/{range}.pbf`, `/sprite*.json|png`.
- `/out/{file}`: static pack downloads with byte ranges, `ETag`, `If-Range`, `?kbps=` throttle.
- `POST /build?name=&bbox=w,s,e,n&minZoom=&maxZoom=[&profile=outdoors][&source=auto|extract|overpass][&fallback=1][&overwrite=1]`,
  `GET /build`, `GET|DELETE /build/{id}`, `GET /build/sources?bbox=` (`BuildRoutes.kt`; outdoors sources below). Token-protected.
- `/sync/pull`, `/sync/push`, `/sync/status`, `/sync/tracks[/{id}.gpx]`: the sync peer
  (docs/PLANNING.md, Sync). Token-protected.
- `POST /sync/relay?slot=&side=[&after=][&forget=1]`, `GET /sync/relay/status`: the encrypted relay
  between phones (below). Open, without the token.
- `GET /catalog` (also `/catalog.json`): the pack catalog, below. `POST /catalog/refresh`: rehash every
  file (token-protected).
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

## The sync peer

The server is the always-on peer of the sync protocol (docs/PLANNING.md, Sync): its replica is a SQLite
file (`syncDb`, default `out/sync/peer.db`), created on the first sync request. A phone syncs with
`POST /sync?with=https://maps.example.org` (or the `peer` setting). Every replica that can reach the server
can read and write its log, so on a server reachable from the internet set a token: with one, `/sync/*`
needs `Authorization: Bearer <token>` (the phone sends the token from its sync settings,
`POST /sync/settings?token=`; `devtool sync with --token`).

## Route planning (T68)

The browser map plans routes on the server with the phone's shared code (`RouteRoutes.kt`: T45's router, T60's
route editor and trip summary), so a route planned on the desktop has the numbers the phone shows. Each pack in
`outDir` with a vector archive gets a trail graph on first use, written next to it as
`<vector archive>-graph.idx` (the phone's and `devtool route`'s file name, e.g. `rainier-outdoors-graph.idx`;
reused while both archives are unchanged; the Rainier graph builds in about 1.5 s). Since T76 the file holds z11
graph tiles that load on demand into one LRU (`GET /route/status` `tileCache`), and a route runs over every pack
whose box comes within 5 km of its points, so it crosses from one pack into the next (docs/PLANNING.md ›
Routing). The routes are open (no token): they read packs and write nothing but the graph files.

- `GET /route?from=lat,lon&to=lat,lon[&via=lat,lon;lat,lon][&mode=hike|bike][&pack=<name>][&snap=m]` (or
  `points=lat,lon;lat,lon;...`, 2 to 50): a route through the points, each snapped onto a trail within `snap`
  metres (default 300, as on the phone); legs follow trails, and are straight where no trail connects (DEM
  elevation every 25 m, from the packs or else the live `best` source). The reported pack is `pack=` (a catalog
  id; 400 when unknown), else the first pack holding both ends, else the start; the route uses it and every other
  pack near the points; outside every pack the route is straight (`notes: ["no_graph"]`).
- `POST /route/edit` with JSON `{"path", "op", "index", "lat", "lon", "mode", "pack"}`: `op` is `move`, `insert`,
  `add` (into the nearest leg within 150 m, else the new end; answers `index`), `remove`, `mode`, `reroute` or
  `show`. Only the legs next to a moved, added or removed point are rerouted.
- Both answer `pack`, `mode`, `path` (the encoded route: the `path` field value a trip stores in the sync log),
  `via`, `legs` (`kind` trail/straight/fixed, `coordinates` as `[lon, lat, ele]`), `summary` (distance, ascent,
  descent, hike-model time, `straightM`, a 100-sample profile), `notes` (why a leg is straight), `graph`
  (`built`, `loaded` or `cached`, the reported pack's; every pack used under `graphs`) and `ms`. Bad input is a 400 with `error`.
- `POST /route/gpx` with `{"path", "name", "id"}`: GPX (one `rte` with via marks and our ids), byte for byte
  what the phone exports for the same trip.
- `POST /route/bundle` with `{"device", "ops"}`: the page's own route operations (put, set field, delete; one
  device, HLC-stamped) as a gzipped sync bundle, for `POST /sync/push` or a `.dsync` file.
- `GET /route/packs` (packs and graph state), `GET /route/status` (request counts, builds, timings), and
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

With `token` (or `DYSTOPO_SERVER_TOKEN`) set, `/build`, `/build/*`, `/sync` and `/sync/*` (except the
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
  first), `source` for tile routes (`pack`, `live-3dep`, `live-copernicus`, `openfreemap`, ...). 5xx at
  `error`, 401/403/429 at `warn`.
- `glyphs_fetched`, `request_failed`, `config_invalid`, `bind_failed`, `stopping`, `stopped`.

## Shutdown

On SIGTERM (or Ctrl+C) the JVM's shutdown hook: `/healthz` turns 503, queued and running builds are
cancelled (their partial `*.building` files are deleted), the listener stops accepting and in-flight
requests get up to half of `shutdownGraceSeconds` to finish; then `stopped` is logged with how long it took.

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
