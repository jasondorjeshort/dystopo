---
date: 2026-11-20
status: open
area: ideas / outside research
---

# The aircraft layer: which public sources an open app may use, and how established apps show aircraft and their routes

## Question

The owner (2026-11-20): airplane paths are the next release's focus; research the available sources, the best way to
show the data (origin and destination above all), and what other apps do. Idea:
`docs/ideas/2026-11-20-aircraft-paths-on-the-map-and-dots-in-the-sky.md`.

## What was tried

Two read-only agents (benchmark, ADR 0039; technical variant, ADR 0044), web search and fetch only, every claim cited.
Pages that refused automated reads are marked "(snippet)" or named as unread in each section. Product and source names
appear here as the rules allow (docs may name them; user-visible text may not).

## Result

### Aircraft layer: public data sources (technical survey, 2026-11-20)

Question: which public sources can a free open-source app use, legally and in practice, for (1) live aircraft positions, (2) origin and destination (callsign to route), (3) aircraft metadata (hex to type, registration, operator), and (4) static reference data it can ship offline? Idea: `docs/ideas/2026-11-20-aircraft-paths-on-the-map-and-dots-in-the-sky.md`. "Unknown" means no public source found.

#### 1. Live positions

| Source | Auth, limits | Area query | Licence and terms (quoted) | Notes |
|---|---|---|---|---|
| **adsb.lol** | No key: 500 req/day per IP. Free keys: adhoc 1,000/day, feeder 7,200/day; sponsors up to 360,000/day. Over quota: 429 + Retry-After [1][2] | `/v2/lat/{lat}/lon/{lon}/dist/{nm}`, radius up to 250 nm (no rectangle). Returns `dst`/`dir` per aircraft [2] | "The license for the API as well as all data ADSB.lol makes public is" **ODbL 1.0** [2]. Commercial use is not restricted; ODbL needs attribution, plus share-alike on a public derived database | readsb JSON with `r`, `t`, `alt_geom` (WGS84 ellipsoid) [9]. Quotas are new in 2026. The `adsblol/api` repo was **archived on 2026-11-19**, with no pointer to a successor [3][4] |
| **adsb.fi** | No key; "1 request per second" on public endpoints [5] | `/v3/lat/../lon/../dist/..`, up to 250 NM [5] | "for personal, non-commercial use only"; "You must cite adsb.fi and include a link to our home page" [5] | No SLA; "We reserve the right to suspend or terminate" [5] |
| **airplanes.live** | No key; 1 req/s; "might" need a feeder later [6] | Point and radius, 250 NM [7] | Labelled "Non-Commercial Use"; "No SLA No Uptime Guarantee" [6]. Terms text: unknown. A third party reports "personal or internal business use" [7] | ADSB One merged into it in 2026 [7] |
| **OpenSky Network** | OAuth2 client credentials only. Credits per day: anonymous 400 (by IP), standard 4,000, feeder 8,000 [8] | True **bbox** (`lamin/lomin/lamax/lomax`); ≤25 sq° costs 1 credit [8] | API is for "research and non-commerical purposes" [10]. Data licence: "solely for the purpose of non-profit research, non-profit education…"; may not "distribute, disclose, transfer or otherwise make available the data" to third parties [11] (an archived copy; the live terms page returned 403) | Anonymous data is 10 s resolution, authenticated 5 s [8]. May block cloud IPs [10]. No route endpoint: "Routes … are currently not available through the REST API" [8] |
| **ADS-B Exchange** | Paid only: Personal API on RapidAPI, $10/month for 10,000 requests; Enterprise by contract [12] | location, hex, callsign [12] | Community API is "built for non-commercial use"; commercial use goes to Enterprise [12]. Redistribution clause: unknown | Sold to JETNET in 2023 [13]. No free tier, so ruled out |
| FlightAware AeroAPI | Personal tier: $5/month free credit, then $0.01 per position query [14] | search queries [14] | Personal tier allows derivative works "for personal or academic purposes only" [14] | Pay per query with the key inside a public app: ruled out |
| Flightradar24 API | Explorer plan $9/month, 60,000 credits [15] | yes | Display and redistribution terms: unknown | Paid, closed: ruled out |
| aviationstack / AirLabs | aviationstack free tier: 100 requests/month, personal licence [16]. AirLabs: unknown | — | personal | Too small: ruled out |
| **Own receiver (RTL-SDR)** | USB-OTG dongle; Android apps exist (dump1090 for Android, Avare ADSB) [17] | Antenna range | Not a data licence question. But librtlsdr is **GPL-2+** [18] and readsb/dump1090 are GPL-3 [19], so they cannot be linked. The app could read an external receiver's network feed instead (SBS or GDL90: a protocol, no code) | Hardware plus battery; a later option |

#### 2. Origin and destination (callsign to route)

| Source | What | Licence | Accuracy, notes |
|---|---|---|---|
| **VRS standing data** (`vradarserver/standing-data`) | CSV `routes/schema-01/<code>-all.csv`: Callsign, Code, Number, AirlineCode, **AirportCodes** (hyphen-separated, in the order flown). Also airlines, airports, countries, model types [20][21] | **CC0-1.0** [20] (adsb.lol mirrors it as CC0 [4]) | Committed daily by the "SDM Site" (latest 2026-11-20) [22]. Built from ACARS-log extraction plus routes "manually entered by users" [23]. Heuristic: callsigns are reused, legs can be wrong, multi-leg routes list every stop |
| **adsb.lol `POST /api/0/routeset`** | Takes up to 100 `{callsign, lat, lng}` and returns `airport_codes`, `_airports` and **`plausible`** [2][24] | ODbL [2]. Unmetered ("Free: … /api/0/{my,routeset}") [2] | Data comes from VRS standing data [24][25]. `plausible` tests whether the position fits a leg between consecutive airports. It is cached on first lookup, so it is not rechecked later [24] |
| adsbdb.com | `/v0/callsign/{cs}` returns origin, (midpoint), destination [26] | Code MIT. Route data "may not be copied, published, or incorporated into other databases without the explicit permission" of its author [26] | Rate limits unknown. **Ruled out by its clause** |
| hexdb.io | `/api/v2/routes/{callsign}`, plus aircraft and airports [27] | Licence, terms and rate limits: unknown. Credits PlaneBase and Jim Mason [27] | Used in hobby projects. Terms unknown, so not recommended |
| OpenSky `/flights/*` | Estimated departure and arrival airports, from a nightly batch: "only flights from the previous day or earlier" [8] | non-profit only [11] | Not live, so useless for a live label |
| OpenFlights routes | Airline, from, to | ODbL/DbCL [28] | "ceased providing updates in June 2014" [28]. Stale: ruled out |

#### 3. Aircraft metadata (hex to registration, type, operator)

| Source | Licence | Notes |
|---|---|---|
| Fields in the live feed (`r`, `t`, `desc`, `dbFlags`) | Same as the feed (ODbL for adsb.lol) | readsb fills them from tar1090-db [9]. **Covers the need with no second call** |
| tar1090-db (`aircraft.csv.gz`) | Unknown: no licence in the repo [29] | Built from the Mictronics database [29]. Mictronics licence: unknown (pages 404) |
| ADS-B Exchange `basic-ac-db.json.gz` | Unknown. Attribution "Source: ADS-B Exchange" asked when showing data [13] | Rebuilt daily [13] |
| FAA Releasable Aircraft (`ReleasableAircraft.zip`, about 60 MB) | US government work, not subject to copyright (17 U.S.C. §105) [30][31] | US (N-number) only. Includes hex (Mode S code) and registrant |
| VRS standing data `aircraft/`, `model-type/` | CC0 [20] | Per-hex coverage: unknown. VRS excludes the PlaneBase lookup from GitHub [32] |

#### 4. Static reference data (offline)

| Source | Licence | Notes |
|---|---|---|
| **OurAirports** `airports.csv` | Public domain [33][34] | Nightly; ICAO, IATA, name, municipality, lat/lon |
| VRS standing data airports, airlines | CC0 [20] | Keyed to the route file codes, so a join needs no mapping |
| OpenFlights airports, airlines | ODbL/DbCL; airline data from Wikipedia "may be subject to the GNU FDL" [28] | Airports as of 2017, airlines as of 2012 [28]. Stale |

#### Common practice among open-source trackers

- **tar1090** (the standard readsb web map): its route display is **off by default** (`useRouteAPI = false`). It calls `routeApiUrl = "https://adsb.im/api/0/routeset"`, with adsb.lol's routeset as the noted alternative, and shows IATA codes by default [35]. Aircraft metadata comes from tar1090-db, which is built from Mictronics [29].
- **adsb.lol**: positions from its own feeder network, routes from VRS standing data plus a plausibility flag [24][25].
- **Virtual Radar Server**: its own SDM standing data (ACARS plus user entries), downloaded every 24 h [23][32].
- **FlightAirMap**: no default feed ("no default sources are provided"); publishes its own routes TSV and a Mode S file from "ACARS, FAA data" [36].
- **Stratux**: a local 1090/978 receiver for pilots; no internet source. How it gets routes or metadata: unknown.
- **Handling route inaccuracy**: label the route as unverified or hide it when the aircraft is off the leg (adsb.lol's `plausible`), and let users submit corrections upstream (VRS SDM site) [20][24].

#### Recommendation for Dystopo

1. **Positions**: **adsb.lol** `/v2/lat/lon/dist`. It is the only free feed under an open licence (ODbL) with no non-commercial clause. Fallbacks: adsb.fi or airplanes.live, but both are "non-commercial/personal" with an unknown stance on public apps. Ask each operator by email before adding one as a fallback, and add none to `sources.json` until one answers. OpenSky is ruled out: its non-profit-research clause and its no-redistribution clause rule out serving a public app.
2. **Origin and destination**: ship the **VRS standing-data routes (CC0)** with the airports, and compute plausibility on the phone. The check: is the aircraft within N km of the great circle between consecutive airports, and is its track heading toward the next one? Show "LHR to JFK" only when plausible; otherwise show the callsign alone, or the route with "?". Rechecking each poll avoids adsb.lol's cached-flag flaw. `routeset` is an online alternative (unmetered, ODbL) if a shipped table is too large. The table's size is unknown: measure it first. The owner's server can refresh it daily and serve it as a small pack.
3. **Metadata**: use the `r`/`t`/`desc` fields from the adsb.lol response (no extra call). Optionally add FAA (public domain) for the operator on US aircraft. Skip tar1090-db and Mictronics until their licences are known.
4. **Static data**: OurAirports `airports.csv` (public domain), cut down to airports with an IATA or ICAO code, plus VRS airlines (CC0) for airline names. Both can be packed and work offline. Routes are static too.
5. **`sources.json` entries** (attribution wording is a proposal; ODbL requires notice, and adsb.lol's exact wording is unknown):
   - `adsblol-live`: ODbL-1.0, "Aircraft positions: ADSB.lol contributors (ODbL)", link https://www.adsb.lol
   - `vrs-standing-data`: CC0-1.0, no attribution required; "Routes and airlines: VRS standing data (CC0)"
   - `ourairports`: Public Domain, "Airports: OurAirports"
   - `faa-registry` (optional): Public Domain (US Gov), "Aircraft registry: FAA"
   - **Flag**: ADR "no other product names in user-visible text" covers mapping software. Data-source credits like the OSM credit are presumably allowed, but "Virtual Radar Server" names a tracking product. Since CC0 needs no credit, it can stay in docs and an About screen only. The owner decides.
6. **Phone direct or via the owner's server**: **phone direct by default.** adsb.lol's no-key quota is per IP, so each phone gets its own 500/day; a proxy would put every user on one IP and one quota, and would need a feeder or sponsor key. ODbL allows a proxy or cache (with notice), but adsb.fi's and airplanes.live's terms would not clearly cover one, and OpenSky's would not allow it. Keep the server optional, as a cache for the route and airport packs, and later perhaps for positions under a feeder key, if the owner feeds.
7. **Poll interval**: 500/day is about 83 minutes at a 10 s poll, or about 4 h at 30 s. Default **20 s** while the layer is visible and the app is in the foreground; never poll in the background. Make it a user setting (10, 20, 30, 60 s) with the remaining daily quota shown as a visible limit (ADR 0023, no Wi-Fi gate). Extrapolate positions between polls from ground speed and track. Round the query centre to about 0.1° and the radius up, so the exact position is not sent (the idea's new-data-flow flag). Off by default, with the help text naming where the query goes.
8. **Geometry note**: `alt_geom` is above the WGS84 ellipsoid [9], while Dystopo elevations are EGM2008. Subtract the geoid undulation before computing elevation angles. Use `alt_baro` only when `alt_geom` is absent.

#### Risks

- **adsb.lol longevity**: the API repo was archived on 2026-11-19, the day before this survey, and quotas arrived in 2026 [3][2]. Keep the feed behind an interface (a readsb v2 JSON parser fits adsb.lol, adsb.fi and airplanes.live alike), and add a replay file for tests.
- **Route accuracy**: data is crowd-sourced, so callsigns get reused and legs go wrong [23]. Without the plausibility gate the app will show wrong destinations.
- **Unknown terms**: airplanes.live (terms text), hexdb (everything), Mictronics and tar1090-db (licences), ADS-B Exchange's basic-ac-db (licence).
- **ODbL share-alike**: if Dystopo publishes a derived database (for example, a cached track pack), it must also be ODbL. App display alone is a "Produced Work" and needs notice only.
- **Privacy**: the query reveals the user's area to a third party. Mitigate with a coarse centre, off by default, and the help text.

#### Sources

[1] https://www.adsb.lol/docs/open-data/api/ [2] https://api.adsb.lol/api/openapi.json [3] https://github.com/adsblol/api [4] https://github.com/adsblol [5] https://github.com/adsbfi/opendata/blob/main/README.md [6] https://airplanes.live/api-guide (via search snippet; page 403) [7] https://flyitalyadsb.com/en/dati/confronto/ [8] https://openskynetwork.github.io/opensky-api/rest.html [9] https://raw.githubusercontent.com/wiedehopf/readsb/dev/README-json.md [10] https://openskynetwork.github.io/opensky-api/ [11] https://zenodo.org/records/6325961/files/LICENSE.txt?download=1 [12] https://www.adsbexchange.com/community/developer-hub/ [13] https://www.adsbexchange.com/about/media-kit/ , https://hackaday.com/2023/01/26/ads-b-exchange-sells-up-contributors-unhappy (search summaries) [14] https://www.flightaware.com/commercial/aeroapi/ [15] https://www.flightradar24.com/blog/b2b/how-to-get-started-with-the-flightradar24-api/ [16] https://aviationstack.com/pricing [17] https://www.rtl-sdr.com/dump1090-for-android-updated-to-v2/ , https://hiz.ch/fwd/user-guide [18] https://sources.debian.org/src/rtl-sdr/2.0.2-2/debian/copyright [19] https://sources.debian.org/src/readsb/3.16-2/LICENSE [20] https://github.com/vradarserver/standing-data [21] https://github.com/vradarserver/standing-data/tree/main/routes/schema-01 [22] https://github.com/vradarserver/standing-data/commits/main [23] https://virtualradarserver.co.uk/FlightRoutes.aspx (search snippet) [24] https://github.com/adsblol/api/blob/main/src/adsb_api/utils/api_routes.py [25] https://www.adsb.lol/docs/acknowledgements/open-source/ [26] https://github.com/mrjackwills/adsbdb [27] https://hexdb.io/ [28] https://openflights.org/data.php [29] https://github.com/wiedehopf/tar1090-db [30] https://www.faa.gov/licenses_certificates/aircraft_certification/aircraft_registry/releasable_aircraft_download/ [31] https://www.law.cornell.edu/uscode/text/17/105 [32] https://virtualradarserver.co.uk/ [33] https://ourairports.com/data/ (certificate expired at fetch; via search snippet) [34] https://github.com/davidmegginson/ourairports-data [35] https://raw.githubusercontent.com/wiedehopf/tar1090/master/html/defaults.js [36] https://github.com/Ysurac/FlightAirMap/wiki , https://data.flightairmap.com (search snippet; host unreachable)

### Survey: live aircraft on a map and in a sky view, with origin and destination (2026-11-20)

Read-only. Sources are public pages, cited per row. Several vendor pages refused automated reads (HTTP 403:
the label-options and info-panel posts on the flightradar24.com blog, the ADS-B Exchange and airplanes.live map help, the
AirNav Radar blog). Where only a search snippet of such a page was seen, the row says "(snippet)". Nothing was installed.

#### Flightradar24 (map + AR)
| Behaviour | Detail | Source |
|---|---|---|
| Trail colour | By altitude: white below 100 m, yellow above it, then green to light blue, with dark blue, purple and red at the highest altitudes (above 6,000 m) | https://support.fr24.com/support/solutions/articles/3000115027-why-does-the-aircraft-s-trail-change-colour- |
| Estimated position | Black dotted trail = "outside our coverage area and its position is being estimated" | same |
| Labels (web) | Off, logos, flags or text; up to 4 rows; one row type is Route; background opacity can be set; icon size slider (snippet) | https://www.flightradar24.com/blog/inside-flightradar24/new-flightradar24-com-label-options/ |
| Tap on map | The route shows as "a subtle line"; tapping again opens full details | https://www.frommers.com/tips/airfare/how-to-identify-flights-overhead-and-ships-at-sea-for-free-using-your-smartphone/ |
| Great circle | Explainer draws the great-circle route as a red dashed line | https://www.flightradar24.com/blog/aviation-explainer-series/flight-paths-and-great-circles-why-are-great-circles-the-shortest-flight-path/ (snippet) |
| Info panel | Grouped into flight, aircraft, movement and data source; scheduled, actual and estimated times; progress bar of time and distance flown vs remaining; app card: route, ETA, departure time, type, speed, altitude (snippet) | https://www.flightradar24.com/blog/inside-flightradar24/an-overview-of-the-updated-flight-information-panel-on-flightradar24-com/ |
| AR view | AR button at top left; camera and location permission; labels give "flight number, aircraft type, altitude, and destination"; figure-eight to calibrate when labels are off; a missing aircraft usually means no coverage | https://support.fr24.com/support/solutions/articles/3000137971-how-to-use-ar-view-in-the-flightradar24-mobile-app |
| AR, later version | Shows a photo of the aircraft; you can start tracking a flight from AR (snippet) | https://www.flightradar24.com/blog/inside-flightradar24/introducing-the-all-new-flightradar24-mobile-app/ |
| Not established | AR distance or altitude filter, how AR labels avoid overlapping, refresh rate | |

#### FlightAware (web map; app not separately documented)
| Behaviour | Detail | Source |
|---|---|---|
| Flown vs planned | Solid line = flown track (every position report); **dashed line = filed route ahead of the aircraft**, updated when the plan changes, gone after landing | https://discussions.flightaware.com/t/planned-route-now-displayed-on-maps-for-airborne-flights/8376 |
| Colours | Track and icon green; a coverage gap is drawn **white along the great circle**; while out of coverage the icon becomes a **hollow white icon**, with a range ring and the word "ESTIMATED" | https://flightaware.com/news/article/Improvements-to-FlightAware-Maps--Understanding-Map-Behavior/196 |
| Tap | Shows the aircraft, where it took off and where it is heading; paths on by default, switchable in layers | Frommer's (above) |

#### tar1090 (the front end of ADS-B Exchange, adsb.lol and airplanes.live; open source, GPL)
| Behaviour | Detail | Source |
|---|---|---|
| Route | Off by default (`useRouteAPI=false`, a visitor's checkbox); looked up by call sign from a route service; `routeDisplay` = iata (default), icao or city | https://raw.githubusercontent.com/wiedehopf/tar1090/master/html/config.js |
| Labels | Toggles L (labels), O (extended label), K (track labels); **shown only from zoom 8** (ground traffic from 14.8); label scale | same; https://raw.githubusercontent.com/wiedehopf/tar1090/master/README-query.md |
| Colour | Colour by altitude, hue interpolated between steps (example: 2,000 ft orange, 10,000 ft light green, 40,000 ft magenta); offsets for selected, **stale** and MLAT positions; a monochrome option | config.js |
| Trails | Tracks of selected aircraft; "all tracks" (T) off by default; temporary trails of 90 s; a 5-minute heading line ahead | config.js |
| Staleness | An aircraft is dropped 58 s after it was last seen; positions implying faster than Mach 3.5 are filtered out | config.js |
| Filters | Altitude min/max; filter by source and by flags; separate categories for ground vehicles and non-ICAO targets | README-query.md; https://adsb.lol/ |
| Info panel | Registration, type, route, altitude, speed, source, squawk; then sections for spatial, signal, speed, altitude, direction, accuracy | https://adsb.lol/ |
| Route accuracy | ADS-B carries no origin or destination; a call-sign lookup can be wrong (users report wrong origins on tar1090) | https://discussions.flightaware.com/t/tar1090-improved-webinterface-for-dump1090-fa-and-readsb/52888?page=81 (snippet) |

#### Plane Finder, RadarBox / AirNav Radar
| App | Detail | Source |
|---|---|---|
| Plane Finder | Airline logo plus up to 4 lines of label; label position, colour and content are configurable; AR mode: "point your camera at a plane"; information is told apart by shape or text, not colour alone; 3D models | https://apps.apple.com/us/app/-/id361273585 |
| RadarBox | Labels drawn only when fewer than 250 aircraft are in view (snippet) | https://www.airnavradar.com/blog/airnav-radar-april-update-new-features-to-enhance-your-tracking-experience (403; snippet) |
| AirNav Radar AR | Altitude filter up to 50,000 ft (snippet) | same |

#### Apps for "what plane is overhead" (iOS listings)
| App | Detail | Source |
|---|---|---|
| Overflight | A radar scope of the visible sky (north-up or heading-up). One aircraft is in focus, chosen from what you can see; swipe to the next one. Filled dot = there now; **dashed ring = just left**; hollow amber ring = notable and approaching. Range presets Overhead, Near, Normal and Far ("Overhead": above 45 degrees, or under 10,000 ft above you, or within 1.6 nm); widens once if the scope is empty. **"SIGNAL LOST" when the feed stalls**, never frozen as live; "Watching the sky" when idle. Route shown with airport names (Pro) | https://apps.apple.com/app/id6778497813 |
| HeadUp | Locks onto the nearest aircraft within a scan radius set in Settings; route as codes with an arrow ("LAX > JFK"); a widget refreshed every 5 to 15 min | https://apps.apple.com/us/app/id6752604170 |
| SkySpottr | AR label: call sign, type, altitude, speed, destination; tap for details; **an arrow to the nearest plane when it is off-screen**; route and colour-by-altitude trails in Pro | https://apps.apple.com/us/app/skyspottr/id6756084687 |
| HA flight card (open source) | Route as IATA codes "SEA -> DEN"; scheduled time added on the ground; works out approaching or not, closest passing distance and time to it | https://github.com/Springvar/home-assistant-flightradar24-card |

#### Sky and star apps
| App | Detail | Source |
|---|---|---|
| Stellarium Planes plugin (2026) | Off by default; adsb.fi or airplanes.live within each source's radius limit; **refresh no faster than 15 s** (to respect rate limits), with **dead reckoning between polls** (track, speed, vertical rate); labels give flight number or model; no trails yet (planned); no route; real-time mode only | https://github.com/Stellarium/stellarium/pull/4926 |
| Heavens-Above, Sky Guide, SkySafari | No aircraft layer found (satellites only) | https://www.positioniseverything.net/best-apps-to-track-satellites-and-identify-lights-overhead/ |
| PeakFinder, PeakVisor | No aircraft layer found in any source | (none) |

#### Open-source Android
- airplanes.live companion (GPLv3, IzzyOnDroid): live map; search by hex, call sign, registration or squawk; routes with origin and destination airports; alerts when an aircraft enters an area. Icons and card not documented. https://darken.eu/airplanes-live-companion-2/
- SkyPulse (F-Droid, GPL-3.0): decodes on the phone from an RTL-SDR dongle; map with offline airspace boundaries. https://f-droid.org/packages/io.github.mrjohn6774.skypulse/
- Neither can be reused: GPL, and no copying in any case.

#### Not established from a public source
- Silhouettes by aircraft type, rotated to the heading. They are visible in every tracker's screenshots, but no fetched page documents the scheme.
- The refresh rate of any commercial app.
- How the AR apps keep labels apart.
- Flighty's live-map dashed line: its help page names the question but the answer could not be read.

#### Common practice
1. **On the map:** an aircraft icon turned to its heading; the trail or track **coloured by altitude** (FR24, tar1090, SkySpottr Pro); no lines until an aircraft is tapped (FR24 "subtle line", tar1090 selected-only, all tracks off by default).
2. **Origin and destination:** codes with an arrow ("SEA -> DEN", "LAX > JFK"). IATA is the default (tar1090 default `iata`, HA card); city names are an option (tar1090 `city`, Overflight with airport names).
3. **Route line:** flown track solid; the rest of the route **dashed**, drawn as the filed plan (FlightAware) or as the great circle (FR24 explainer). Gaps are drawn along the great circle and marked as estimated.
4. **AR and sky labels:** short, giving call sign or flight, type, altitude and **destination** (FR24 AR, SkySpottr); the full card comes on a tap.
5. **Labels thinned by count or zoom** (tar1090 zoom 8 and up, RadarBox under 250 aircraft); the full set of labels is an option.
6. **Stale and estimated positions are marked, not hidden** (FR24 dotted line, FlightAware hollow icon with "ESTIMATED", tar1090 stale colour, then dropped after 58 s; Overflight "SIGNAL LOST").
7. **Route lookups are optional or paid** (tar1090 off by default, Pro tiers), because ADS-B carries no route.

#### Outliers and why
- Overflight: a scope of the sky, not a camera; one aircraft in focus; range presets in angle and height above you. It is the closest model for Dystopo's Horizon plot.
- Stellarium: the only sky app with aircraft; off by default, a 15-second floor and dead reckoning. These are the network-cost and source-terms choices Dystopo also faces.
- FlightAware draws the remaining route as the filed plan, where the explainer elsewhere draws the great circle. Filed plans come from air traffic control data that open feeds do not have.

#### Recommendation for Dystopo
- **Map:** a small silhouette turned to the heading (shapes distinguish it, not colour alone, as Plane Finder notes). Each aircraft gets a short trail coloured by altitude. When one is tapped, its full track is drawn solid, and the remaining part to its destination is drawn as a **dashed great circle**, since there is no filed plan. Draw the dashed part only when the route is known and plausible, i.e. when the aircraft sits near the origin-to-destination great circle; that guards against wrong call-sign lookups.
- **Horizon and Panorama:** a dot at the aircraft's bearing and elevation, in the same altitude colour, drawn hollow when behind terrain or stale (the convention of T309's hollow peak). It gets a tail of the last ~60 to 90 s (tar1090's 90 s). Label it **call sign and destination code** ("UAL123 > DEN") and nothing more. Off-plot aircraft need no arrow, since the plot covers the whole sky. Dead-reckon between polls.
- **Tap card, in this order:**
  1. Call sign or flight.
  2. **Origin > destination**, as codes plus city ("SEA Seattle > DEN Denver").
  3. Type.
  4. Altitude in the user's units, climbing or descending.
  5. Ground speed.
  6. Distance and bearing from you (and elevation angle in the sky views).
  7. Position age ("12 s ago").
  8. The data source's attribution line.
- **Origin to destination:** codes and an arrow on labels; codes and city names on the card. An unknown route reads "Route unknown", with no line drawn. Mark a looked-up route as a lookup ("Route from schedule data, may be wrong").
- **Decluttering:** reuse the ranking of `HorizonLabels.kt`, closest and highest elevation first. On the map, labels only from a zoom threshold or under a count cap, as tar1090 and RadarBox do; dots are always drawn.
- **Offline, stale and unknown:**
  - Offline, say "No aircraft data: offline" on the layer, never an empty sky.
  - A position older than about 30 s is drawn hollow and labelled with its age; past about 60 s it is dropped (tar1090's 58 s).
  - A stalled feed reads "Aircraft data stopped" (Overflight's practice).
- **One tap for "what's overhead":** the Horizon view's Aircraft list, closest first (as the Peaks list is), with the nearest highlighted.
- **User settings (ADR 0044):**
  - The layer itself is off by default. It is a new data flow, and Stellarium also ships its plugin off.
  - Refresh interval: default 15 s, choices 5, 10, 15, 30 and 60 s. Stellarium's floor is 15 s; apps differ. No Wi-Fi gate (ADR 0023).
  - Route lookup: on when the layer is on, since it is the owner's explicit interest and tar1090 makes it optional. It is a separate setting because it is a second data flow.
  - Airport naming: codes or city (tar1090 has iata/icao/city). Default codes on labels, both on the card.
  - Altitude filter or sky range: default all. Choices: overhead (above 45 degrees), near, all. Overflight and AirNav split here.
  - Not settings: the trail length on the sky views, the colour scale and the stale rules. One practice is clear for each.
- **Product names:** none needed in UI text. The data source's own name (for example airplanes.live or adsb.fi) would appear only as an attribution line under `sources.json`. Whether a data provider's name counts as "another product name" is the coordinator's call; ADR-wise it reads as attribution, not mapping software. Never write "Flightradar-style" or similar in help text.
- **Conflicts:** none with ADR 0023 (a visible refresh interval, no Wi-Fi gate). ADR 0031: the presentation can be built in alpha and shown with screenshots. The new data flow and the route lookup are flagged to the owner, not guessed.

## What it means for the build

- Positions: adsb.lol (ODbL, no non-commercial clause; point and radius, 500 requests a day per IP without a key),
  called from the phone, off by default. Its API repository was archived on 2026-11-19: the source is the main risk,
  and the layer reads positions through one interface so a second source can be added.
- Origin and destination: the CC0 route table (VRS standing data), shipped as data rather than looked up per call
  sign, so no second data flow; shown only when the aircraft lies near the great circle between the two airports and
  heads for the next one, rechecked each poll; otherwise the call sign alone.
- Display: the common practice (heading-turned silhouette, trail coloured by altitude, flown track solid and the
  remaining route dashed on tap, "SEA > DEN" labels, a card on tap, stale positions hollow then dropped).
- Altitude: `alt_geom` is above the WGS84 ellipsoid; convert to EGM2008 before the elevation angle.
