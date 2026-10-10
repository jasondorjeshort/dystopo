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
kotlinx-serialization, SQLDelight, sqlite-jdbc; all Apache-2.0), `web/index.html`, `config/server.example.json`
(every key with its default) and the `Dockerfile`.

## Docker

The image is built from the installed distribution (no build tools in the image):

```
./gradlew :server:installDist
docker build -t dystopo-server server/build/install/server
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
| `overpass.maxAreaSqDeg` | `0.05` | Largest box sent to Overpass (square degrees); larger is a 413 (or OpenFreeMap with `fallback=1`). |
| `overpass.timeoutSeconds` | `90` | Overpass query timeout (10..900). |
| `tilebuildHome` | `null` | A `./gradlew :tilebuild:installDist` install for `profile=outdoors` builds; null: `DYSTOPO_TILEBUILD_HOME`, else `tilebuild/build/install/tilebuild`. Not in the Docker image (tilebuild carries LGPL build tooling and stays a separate program); without it outdoors builds answer 503 and `profile=openmaptiles` builds work. |

## Routes

The same as `devtool serve --live` (`LiveTiles.kt` documents each), plus three:

- `/` browser map, `/phone` phone style page, `/style.json?units=ft|m` (or `?lat=&lon=`), `/live/info`,
  `/live/units`, `/live/sources?bbox=&dz=&oz=` (sources and attribution of a view), `/stats`.
- `/dem/{z}/{x}/{y}.png`, `/contours/{z}/{x}/{y}.pbf?units=`, `/osm/{z}/{x}/{y}.pbf`: packs first, else
  live (`X-Dystopo-Source` says which). `/glyphs/{fontstack}/{range}.pbf`, `/sprite*.json|png`.
- `/out/{file}`: static pack downloads with byte ranges, `ETag`, `If-Range`, `?kbps=` throttle.
- `POST /build?name=&bbox=w,s,e,n&minZoom=&maxZoom=[&profile=outdoors][&overwrite=1]`, `GET /build`,
  `GET|DELETE /build/{id}` (`BuildRoutes.kt`). Token-protected.
- `/sync/pull`, `/sync/push`, `/sync/status`, `/sync/tracks[/{id}.gpx]`: the sync peer
  (docs/PLANNING.md, Sync). Token-protected.
- `GET /catalog` (also `/catalog.json`): the pack catalog, below.
- `GET /healthz`: `status` (`ok`, or `stopping` with a 503), `uptimeS`, `packs`, `outDirWritable`,
  `cacheDirWritable`, `glyphs` (fonts present), `errors` (tile route exceptions so far).

## The catalog

`GET /catalog` lists every pack in `outDir` a client can download: a `<name>-dem.pmtiles` plus its
optional `<name>-outdoors.pmtiles` (or `-osm`). Files being written (`*.building`) and vector archives
without a DEM are not listed.

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
  A sidecar older than its file is recomputed, so a rebuilt pack gets a new hash. Hashes are computed in
  the background at startup (about 1 s per 200 MB), so the first `/catalog` is fast.
- `contentHash`: `sha256:` + SHA-256 of the lines `<role>:<file sha256>\n` (dem first). A client compares
  it with the one it installed to offer an update; it verifies each file against `sha256` after download.

`devtool serve` (both modes) answers `/catalog` too.

## The sync peer

The server is the always-on peer of the sync protocol (docs/PLANNING.md, Sync): its replica is a SQLite
file (`syncDb`, default `out/sync/peer.db`), created on the first sync request. A phone syncs with
`POST /sync?with=https://maps.example.org` (or the `peer` setting). Every replica that can reach the server
can read and write its log, so on a server reachable from the internet set a token: with one, `/sync/*`
needs `Authorization: Bearer <token>` (the phone's sync client has to send it; until it does, keep an open
sync peer on a private network or a VPN).

## The token

With `token` (or `DYSTOPO_SERVER_TOKEN`) set, `/build`, `/build/*`, `/sync` and `/sync/*` need
`Authorization: Bearer <token>`; anything else (tiles, `/out/`, `/catalog`, `/healthz`) stays open. A
missing or wrong token is a 401 with `WWW-Authenticate: Bearer`, logged at `warn`. The comparison is
constant-time. CORS preflights are answered without the token. The browser page's "Build pack here"
button sends no token, so on a server with a token it gets the 401; build with curl or the phone instead:

```
curl -X POST -H "Authorization: Bearer $TOKEN" "https://maps.example.org/build?name=paradise&bbox=-121.76,46.77,-121.70,46.80&minZoom=8&maxZoom=14"
```

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
