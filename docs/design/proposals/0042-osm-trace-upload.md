# 0042. Uploading a recorded track to OpenStreetMap as a GPS trace

Date: 2026-10-23
Status: promoted to ADR 0042

## Question

The owner answered the external-integrations idea on 2026-10-23: the OpenStreetMap trace upload comes
first, as its own idea (`docs/ideas/2026-10-23-osm-trace-upload.md`) and this proposal; the cloud
drive, route and fitness services wait. To decide: how the app talks to the trace API and signs the
user in, where the client registration lives, what the user confirms and sees before a track leaves
the phone, whether and how the track's ends are trimmed, the visibility default, the terms the user
agrees to, the one exception to the no-product-names rule, and the debug surface that exercises the
whole path against a mock. In ten years the chosen shape should still be: a documented public HTTP
API called directly with no client library, a sign-in that never shows the app a password, nothing
leaving the phone that the user has not just looked at, and a mock that any agent can run.

## Constraints that matter here

- Platforms: JVM and Android now, iOS later (`shared` commonMain stays pure Kotlin); licensing
  (`docs/PLANNING.md` › Licensing: permissive dependencies, LGPL only in `tilebuild`, no GPL);
  offline first; every feature agent-drivable (devtool command, debug route); agents write all code;
  the design goals (ADR 0025).
- Privacy (`CLAUDE.md` › Rules, the owner's words in the idea): a track can reveal where a user
  lives; nothing uploads without a visible per-upload confirmation (ruled out before this proposal:
  uploading without one, and a client library).
- The client registration's secret, if there is one, never enters the repository (as the release key:
  `tools/release.properties`, gitignored, and the `RELEASE_*` Actions secrets).
- User-visible text names no other mapping software (owner, 2026-10-13); this integration is the one
  exception and its strings are flagged for the owner (ADR 0031; interface built in alpha).
- No Wi-Fi gate (ADR 0023): the upload's size is shown, never a network condition.
- The service's own rules: OAuth 2.0 only (Basic auth and OAuth 1.0a were switched off in June 2024),
  a User-Agent naming the application and version, no personal data submitted, the Terms of Use and
  the Contributor Terms (Claims to verify, below).
- What a track carries today (T24, T31, T40): segments of timed points (lat, lon, elevation, time),
  per-track name and stats, waypoints, and a `dystopo:id` extension on export; `TrackExport.gpx`
  writes GPX 1.1 in `shared`. The app already talks to one outside HTTPS service through
  `HttpURLConnection` with a `Dystopo/<version>` User-Agent (`update/Updater.kt`) and names it in
  code only; the `dystopo://map` and `dystopo://share` schemes are registered in the manifest.

## Prior art

From the benchmark survey of 2026-10-23 (ADR 0039; read-only, Sonnet-class, web sources; the
coordinator's brief asked one question: which apps upload traces, how they ask, what they show).

| App | What it does | Source |
|---|---|---|
| The website's own form | File, Description (optional on the form, required by the API), Tags, Visibility; the trace then sits in a queue ("PENDING"). Advice: "If you do not want your GPS trace to be seen by anybody else, stop here and do not upload." No trimming advice on that page (the privacy policy gives it). The form's default is trackable, or the user's last choice (`Trace.default_visibility`; the column default since 2026-08-11, Claims to verify). | https://wiki.openstreetmap.org/wiki/Upload_GPS_tracks |
| OsmAnd | A per-upload dialog from the tracks list: Description (empty means the track name), Tags (default "osmand" plus the activity), Visibility with Identifiable selected by default and both values explained in a sentence each, Account. No map, point count or time preview in the documented dialog. Shows "Upload to OpenStreetMap" and "OSM account" to the user. | https://osmand.net/docs/user/plugins/osm-editing/ |
| Locus Map | One-time sign-in, then export of a track to the account's traces; dialog fields not established. | https://docs.locusmap.app/doku.php/manual:advanced:map_tools:osm |
| GPS Logger for Android | An automatic send destination set up once, no per-upload confirmation; v137 dropped public and private and defaults to trackable. | https://f-droid.org/en/packages/com.mendhak.gpslogger/index.html |
| Vespucci, OSMTracker | Upload exists (OSMTracker moved to OAuth 2 in 2024); dialogs not established from public sources. | https://github.com/labexp/osmtracker-android |
| StreetComplete | Records a track for a note, not a trace upload. | https://github.com/streetcomplete/StreetComplete/blob/master/CHANGELOG.md |
| Organic Maps, CoMaps, Gaia GPS, Komoot, AllTrails, Mapy.cz | No in-app trace upload found; users export GPX and use the website. | https://napoveda.seznam.cz/en/import-recorded-route |
| Strava, Garmin Connect (precedent) | A privacy zone hides the start and end within a radius (Strava: up to 1 mile) from other viewers; the data is kept; Strava says hiding does not make inference impossible, and research re-found homes anyway. | https://support.strava.com/hc/en-us/articles/115000173384-Privacy-Zones ; https://www.usenix.org/system/files/conference/usenixsecurity18/sec18-hassan_0.pdf |

The service is retiring two of its four visibility values: the announcement of 2026-07-23 ("sunset
the Public and Private options, leaving only Trackable and Identifiable"; a third-party client that
still offers them gets a failed upload), the wiki (deprecated August 2026; "there is no way to make
a track completely private") and the website's code (merged and deployed 2026-08-11; a create with
another value fails validation). Trackable: not linked to the user through the API, not in the public
list, points with timestamps in order; still under the owner's account on the website. Identifiable:
linked, listed under the user's name, the same points.

Common practice: a sign-in to the service, then a per-upload dialog with description, tags and a
visibility choice, and no preview of the data. Outliers: the automatic send (a logger's convenience)
and the social platforms' privacy zones, the only home-protection precedent, and a display filter
rather than a cut. No trace-uploading app trims. Most outdoor apps do not upload at all. Where this
proposal departs: a preview of exactly what leaves (the survey's recommendation, for the owner's
stated risk), a cut of the ends on by default, and only the two surviving visibility values. The
default, trackable, is the website's own and GPS Logger's; only OsmAnd differs.

## Options

What is common to every option: the shared core gains a pure-Kotlin `osm/` package: the upload
document, built fresh rather than through `TrackExport.document` (no metadata, no waypoints, no
`dystopo:id`, each point's `extensions` dropped explicitly, since `Gpx.write` copies them verbatim; the
privacy policy's own advice), the multipart body, the PKCE challenge (SHA-256 is `packs/Sha256.kt`,
pure Kotlin; the verifier's 32 random bytes come from the platform, so the shared API takes them as
input and does the base64url), and the JSON views; the platform supplies `post(url, headers, body)` as
`HttpSyncTransport` does. The sign-in, the dialog and the stored token are Android. The options
differ in how the user signs in and how the app gets the authorization code back. The package is
specific to this service; a second service (the cloud drive or fitness ideas) extracts the PKCE flow
and the token store then, not before.

### A. Direct API client; sign-in in the system browser; the code returns by the app's own scheme

The dialog's "Sign in" opens `https://www.openstreetmap.org/oauth2/authorize` in the browser (an
`ACTION_VIEW` intent, as the About screen opens links) with `client_id`, `redirect_uri=dystopo://osm`,
`scope=read_prefs write_gpx`, `state` and a PKCE challenge; the verifier, the `state` and their expiry
are written to the private preferences file first, because Android may kill the process while the
browser is in front (the code lives ten minutes). The user signs in on the website; the site redirects
to `dystopo://osm?code=&state=`, which the manifest routes to a small exported no-UI
`OsmAuthActivity` (`exported="true"`, BROWSABLE, host `osm`) that hands `code` and `state` to the
process singleton and finishes; `MainActivity` is untouched (it has no `launchMode`, so a browser's
`VIEW` intent on it would create a second map). The app checks `state`, exchanges the code at
`/oauth2/token` with the verifier, stores the token in the private preferences file (as
`ShareLinkClient` and `AppSync` keep theirs; `allowBackup="false"`), and reads the display name from
`/api/0.6/user/details`. `state` plus PKCE is what makes a scheme any app may claim safe (RFC 8252
§8.1): an intercepted code is useless without the verifier, and a forged return fails `state`.
Upload: `POST /api/0.6/gpx` multipart with `file`, `description`, `tags`, `visibility`; the answer is
the trace id, kept with the track in a local-only column (not in the sync op log, so no wire-format
change under ADR 0033; its one use is opening the trace on the website). Sign out revokes at
`/oauth2/revoke` (RFC 8414 metadata at `/.well-known/oauth-authorization-server`) and forgets the token.
Registration: a non-confidential application registered by the owner at
`openstreetmap.org/oauth2/applications/new` with that redirect URI and those scopes. A public client
has no secret; the client id is not one (it is in every authorize URL the browser shows) and lives in
code like the releases API URL. Nothing to keep out of the repository, which is the strongest form of
"never in the repository". A dev-instance registration (`master.apis.dev.openstreetmap.org`) for
testing is the same, and the debug route can point the client at it or at the mock.
Cost: the shared package and its tests; the dialog; the callback activity; the routes and the mock;
about the size of T83's updater. Risks: the custom scheme is a property of the server's validator
(only an `http` scheme fails its SSL rule), not of the wiki's documented rule (https except
`http://127.0.0.1`); three shipping clients register one today (StreetComplete, Vespucci, Every Door;
Claims to verify), and the dev registration is the first step, but the service may tighten the rule
later; the token does not expire and there is no refresh token, so a leaked preferences file is a
standing `write_gpx` credential until the user signs out or revokes on the website; the browser's
cookie keeps the user signed in to the website; the first exported component that receives data from
the browser.
Fallback, ahead of B: an **https App Link** on the namespace the project already owns
(`https://jasondorjeshort.github.io/dystopo/osm` beside `GpxExtensions.NS_DYSTOPO`'s
`.../dystopo/gpx/1`, with `.well-known/assetlinks.json` on GitHub Pages), the wiki's documented https
form, exclusive to this app on Android 12 and later, and what OsmAnd does. Its extra moving part is a
hosted file; the activity, the state handling and everything after the redirect are the same.
Who else: StreetComplete (`streetcomplete://oauth`), Vespucci (`vespucci:/oauth2/`) and Every Door
(`everydoor:/oauth`) use custom schemes; OsmAnd an https App Link, and it ships a client secret in its
resources, which this proposal does not; OSMTracker moved to OAuth 2 in 2024.
Exit cost: delete `osm/`, the dialog, the activity and the routes; the local-only trace id column is
dropped by a later migration (`.claude/rules/trackdb.md`), nothing synced changes; the user revokes
the app on the website.
Moving parts: one outside host (`www.openstreetmap.org` and `api.openstreetmap.org`), a registered
client id, a private preferences file, an exported activity with its intent filter (plus a hosted
`assetlinks.json` in the fallback).

### B. As A, but the code returns through the app's loopback server

`redirect_uri=http://127.0.0.1/osm/callback`: Doorkeeper allows plain http for loopback hosts and
ignores the port for them (RFC 8252 §7.3), so the app can listen on an ephemeral port for the one
return rather than on the fixed server port, which removes the "another app on 8686" risk a fixed
port would have. The callback is reachable without the per-launch token (the browser does not have
it); `state` guards it. The user ends on a page served by the app and switches back by hand (no
automatic return to the app). It is the wiki's documented loopback form and works on the JVM too (a
desktop sign-in for `devtool`), and not at all on iOS later.
Cost as A plus a token-exempt listener and a landing page. Risks: the exempt listener is a hole in
the token rule to argue for; the switch back is a worse flow than A's and the App Link's. Who else:
desktop editors (JOSM) use a loopback redirect; no phone app found.
Exit cost as A. Moving parts as A plus the listener.

### C. No account in the app: export the GPX and open the website's upload form

"Send to OpenStreetMap" writes the trimmed GPX (the same shared code) and opens
`https://www.openstreetmap.org/traces/new` in the browser; the user picks the file there and fills the
form. No OAuth, no registration, no token, no API call; the website shows its own confirmation and
visibility choice, so the exception string is one button.
Cost: the shared trimming and the file write, plus a way to hand the file to the browser: the app has
no share sheet and no `FileProvider` today (the critic's grep over `androidApp/`), and the GPX export
writes to the private `files/tracks/`, which no browser or picker can see, so C needs a `FileProvider`
grant or a write to shared Downloads. Risks: not "one press" (the owner's words); the preview of what
leaves is split between the app and the site; a file in shared storage is itself a leak surface; the
site's form may change; no agent path through the website. Who else: most outdoor apps (the survey):
export and upload by hand.
Exit cost: remove the provider and the button. Moving parts: a `FileProvider` or a shared-storage write.

### Not an option

- An OAuth client library (AppAuth-Android, Apache-2.0): ruled out in the task; the flow is three HTTP
  requests and a redirect, under a page of code, so the dependency checklist is not opened.
- A confidential client with a secret in `tools/osm.properties` and an Actions secret: a secret
  compiled into an APK is extractable, so the registration would be no more trusted than a public one
  while adding a thing to keep out of the repository.
- Visibility `public` or `private`: refused by the service for new uploads.
- Uploading without timestamps: the API requires valid timestamps on the trackpoints.

## Recommendation

Option A. It is the common practice of the apps that upload (a browser sign-in, an API upload),
has no secret at all, reuses the app's scheme, and keeps the whole flow in the app so the
confirmation and the preview are ours. The https App Link is the fallback if the custom scheme is
refused at registration or disallowed later; B is third. C is kept as the owner's alternative if a
signed-in account in the app is unwanted.

The user-facing design, all of it the owner's to adjust (ADR 0031; built and shown in the PR):

1. **Entry.** One action per stored track in the tracks list beside "GPX", and in the track's sheet.
   Not on the recording track; its sheet says "Stop recording to send" instead of hiding the reason.
2. **The confirmation**, a sheet that is the only path to an upload: a small map of the track with
   the parts to be cut drawn grey; the counts (points, distance, duration, start and end as local
   times, the upload's size in kB); the visibility choice; the description (the API requires one;
   prefilled with the track name, which therefore leaves unless edited, 255 characters) and tags
   (prefilled `dystopo`); a "Show the file" expander with the exact GPX text that will be sent; one
   fixed line that anyone can download the points and times; Cancel and a Send button that carries
   the cut ("Send, ends cut 500 m" or "Send, nothing cut"), so the state is read at the moment of
   sending. Nothing is sent before Send. No "always send" switch anywhere. A failed send keeps the
   sheet and its fields; a 401 means the token was revoked on the website (it never expires) and says
   "Sign in again".
3. **What leaves the phone**, exactly: the trimmed trackpoints (lat, lon, elevation, time) in a GPX
   whose root carries `creator="Dystopo"` and nothing else of ours; the description (by default the
   track name, which by default is the local date and time of the recording); the tags; the
   visibility; the header `User-Agent: Dystopo/<version>` with nothing after it (not the updater's,
   which adds the Android SDK level); the OAuth token. Not waypoints, not the `dystopo:id`, not point
   extensions (dropped explicitly), not the device, not the file name (the multipart file name is
   `track.gpx`).
4. **Trimming.** On by default: every point within a radius of the first point and within a radius of
   the last point is removed (a loop from home loses both ends at once; a trim by distance along the
   track would leave the street), default 500 m, choices 0, 200 m, 500 m, 1 km, 2 km. The choice is
   remembered only when it is at least the default; 0 and 200 m apply to the one track in front of the
   user, so a trailhead track cannot silently disarm the next one from home. Known limit: a track of
   several segments (the recorder splits on `gapS`) is cut at its first and last point only, so a
   later segment may start at another private place; the grey map shows it. The stored track is
   untouched; the cut copy is what the preview shows and the upload sends. A track that trims to
   nothing cannot be sent; the sheet says so and names the remedy (a smaller cut). The sheet states
   that a cut is not a guarantee (the social platforms' own caveat and the research above).
5. **Visibility default: trackable**, with identifiable as the other choice, each in one sentence in
   the wiki's own terms, never "anonymous": "Not shown under your name; the points and their times are
   public" and "Listed under your name with the points and their times". The two retired values are
   never offered.
6. **Terms.** The sign-in step links the service's Terms of Use and Privacy Policy (the document that
   governs traces) and the Contributor Terms as the account's own terms; the API usage policy's
   User-Agent rule is met.
7. **Account.** A Settings row (Sync group) shows the signed-in display name with Sign out; the token
   lives in a private preferences file, never synced, never in a debug answer (`GET /osm` says
   `signedIn: true` and the name only). The trace id is a local-only column on the track (ADR 0033
   untouched: not a `SetField` in `sync/Ops.kt`).
8. **The exception strings** (the only user-visible text that may name the service; never "OSM"
   alone): action "Send to OpenStreetMap"; sheet title "Send this track to OpenStreetMap?"; account
   row "OpenStreetMap account", "Sign in to OpenStreetMap", "Signed in to OpenStreetMap as <name>",
   "Sign out"; result "Sent to OpenStreetMap; it appears there once processed" and "Not sent:
   <reason>" (the reason may carry the server's own error text); the terms line "Sending uses your
   OpenStreetMap account under its Terms of Use and Privacy Policy", both linked. Strings that name no
   product: "Stop recording to send", "Sign in again", the two visibility sentences, the Send button.
9. **Registry.** `sources.json` gains an entry `osm-traces` under a new kind `destination` (one enum
   value in `SourceKind`, the file comment updated; `osm` is documented as inputs to the vector tile
   build and the parser rejects unknown kinds): provider the OpenStreetMap Foundation, url
   `https://api.openstreetmap.org/api/0.6/gpx`, licence the Contributor Terms, the Terms of Use, the
   Privacy Policy and the API usage policy under `allowedUses`, the required `attribution` text a
   sentence that is never composed into a credit (`SourceRegistry.attribution` composes by id only),
   update cadence n/a. So the one destination the app sends user data to is recorded beside the
   sources it reads.
10. **Debug surface** (every feature agent-testable). Android, debug builds: `GET /osm` (state, API
    base and where it came from, last upload, the registered client id, and in debug builds the
    pending authorization request: its `state`, challenge and expiry); `POST /osm/config?api=<base>`
    points the client at a mock or the dev instance (home-network and loopback hosts only, as
    `update-api.txt`: `CleartextPolicy.problem`); `POST /osm/signin[?nobrowser=1]` prepares the
    request and opens the browser unless `nobrowser=1`; the real return is driven with `adb shell am
    start -d "dystopo://osm?code=mock&state=<state>"`, which exercises the exported activity, the
    `state` check and the code exchange; `POST /osm/signin?token=<mock token>` installs a token for
    later steps; `GET /osm/preview?track=<id>[&trimM=][&gpx=1]` answers the sheet's JSON and the exact
    GPX; `POST /osm/send?track=<id>` opens the sheet and `POST /osm/send/confirm` taps Send (409 "open
    the sheet first" without it, so a script cannot skip the confirmation the user cannot skip); `POST
    /osm/signout`. The mock is `tools/osm-mock.py` on the pattern of `tools/android-ui.py`'s
    `MockReleases`: `/oauth2/token` (accepts any code when a verifier of 43 to 128 base64url characters
    is present; a missing one is 400), `/oauth2/revoke`, `/api/0.6/user/details`, `POST /api/0.6/gpx`
    (checks the multipart fields, refuses an empty description and any visibility but the two with 400
    as the service does, keeps the GPX bytes and answers an id), `GET /api/0.6/gpx/<id>`. JVM: `devtool
    osm-trace --gpx <file> --trim-m 500 --api <mock> --token <t>` runs the shared code and prints the
    JSON, so `check` and a desktop agent cover the format without an emulator. The device tour's
    step: `signin?nobrowser=1`, the `am start` return, `GET /osm` `signedIn: true`; a replay of the
    Skyline fixture, `POST /osm/send`, `confirm`, then the mock's received GPX has no point within
    500 m of the fixture's ends and no waypoint, metadata or extension; `signout` and the mock's
    revoke hit.

First step after promotion: the owner registers the application (a public client, redirect
`dystopo://osm`, scopes `read_prefs write_gpx`) on the live and the dev instance and gives the
coordinator the client ids; the build task then starts with the shared package and the mock.

## What would change this recommendation

- The service refuses a custom-scheme redirect URI at registration, or disallows it later: the
  https App Link (A's fallback), then B.
- The owner does not want a signed-in account in the app: build C, with its `FileProvider`.
- A second outside service is decided: extract the PKCE flow and the token store from `osm/` then.
- The service starts requiring confidential clients or expiring tokens: add the refresh flow; the
  secret question would reopen, and the answer would still not be the repository.
- The terms or the usage policy come to forbid uploads from third-party clients: withdraw.
- The owner prefers a trim by distance along the track, or a different default radius: a constant.
- The service adds an upload option without timestamps: offer it as the more private choice.

## Claims to verify

- Create a trace: `POST /api/0.6/gpx`, multipart fields `file`, `description` (required, 255
  characters at most, 400 when empty), `tags`, `visibility`; `/gpx/create` deprecated; the answer is
  the new id; processing is asynchronous; trackpoints with valid timestamps are required; metadata at
  `GET /api/0.6/gpx/<id>`, data at `/gpx/<id>/data`, update `PUT`, delete `DELETE`, own list at
  `GET /api/0.6/user/gpx_files[.json]`; OAuth 2.0 only, Basic auth and OAuth 1.0a shut down in June
  2024; scopes `read_gpx` and `write_gpx`: https://wiki.openstreetmap.org/wiki/API_v0.6
- Visibility: only trackable and identifiable for new uploads; public and private deprecated (August
  2026); what each exposes; "there is no way to make a track completely private":
  https://wiki.openstreetmap.org/wiki/Visibility_of_GPS_traces and the announcement
  https://community.openstreetmap.org/t/changes-coming-to-gps-trace-uploads/145728 (a client still
  offering the old values "will fail if you select them"; a JOSM ticket reports the API's 400:
  https://josm.openstreetmap.de/ticket/24819).
- Verified by the critic (2026-10-23): the change was merged and deployed 2026-08-11 (PR 7146
  "Simplify GPS trace visibility levels"; issue 7089's comment of 2026-08-12), and `Trace` validates
  `visibility` against the two values on create:
  https://github.com/openstreetmap/openstreetmap-website/pull/7146 ;
  https://github.com/openstreetmap/openstreetmap-website/issues/7089 ;
  https://github.com/openstreetmap/openstreetmap-website/blob/master/app/models/trace.rb
- Verified by the critic: the website's form defaults to trackable (`Trace.default_visibility`; the
  user's last upload's visibility preference when valid; the column default changed from public to
  trackable in PR 7146): trace.rb as above;
  https://github.com/openstreetmap/openstreetmap-website/blob/master/app/models/user.rb ;
  https://github.com/openstreetmap/openstreetmap-website/blob/master/app/controllers/traces_controller.rb
- OAuth 2: authorize `https://www.openstreetmap.org/oauth2/authorize`, token
  `https://www.openstreetmap.org/oauth2/token`, authorization code with PKCE, the client secret
  optional for a non-confidential application, registration at
  `https://www.openstreetmap.org/oauth2/applications/new`, the scope list, redirect URIs HTTPS except
  `http://127.0.0.1` and `urn:ietf:wg:oauth:2.0:oob`, the dev instance with its own accounts and
  registrations: https://wiki.openstreetmap.org/wiki/OAuth
- The server's Doorkeeper config: `force_ssl_in_redirect_uri` exempts `127.0.0.1` and `::1` and
  applies to http(s) URIs; `grant_flows authorization_code`; `access_token_expires_in nil`; refresh
  tokens off:
  https://github.com/openstreetmap/openstreetmap-website/blob/master/config/initializers/doorkeeper.rb
  Doorkeeper's redirect URI validator rejects a script scheme, a fragment, a missing scheme, a
  relative URI and, under the SSL rule, only a URI whose scheme is `http` (`non_https = uri.scheme ==
  "http"`), so a scheme of the app's own passes:
  https://github.com/doorkeeper-gem/doorkeeper/blob/main/lib/doorkeeper/orm/active_record/redirect_uri_validator.rb
  The request for custom schemes (issue 4008) was closed by its reporter the same day with no
  maintainer answer: https://github.com/openstreetmap/openstreetmap-website/issues/4008
- Verified by the critic: custom-scheme redirect URIs are registered by shipping clients today:
  StreetComplete `streetcomplete://oauth`
  (https://github.com/streetcomplete/StreetComplete/blob/master/app/src/commonMain/kotlin/de/westnordost/streetcomplete/data/user/OAuthConstants.kt),
  Vespucci `vespucci:/oauth2/`
  (https://github.com/MarcusWolschon/osmeditor4android/blob/master/src/main/java/de/blau/android/contract/OpenStreetMap.java),
  Every Door `everydoor:/oauth` (https://github.com/Zverik/every_door/blob/main/lib/helpers/auth/oauth2.dart);
  OsmAnd uses an https App Link and ships a client secret in its resources
  (https://github.com/osmandapp/OsmAnd/blob/master/OsmAnd/res/values/no_translate.xml). The dev
  registration before building stays the first step.
- Doorkeeper ignores the port of a loopback redirect URI (RFC 8252 §7.3), so option B needs no fixed
  port: https://github.com/doorkeeper-gem/doorkeeper/blob/main/lib/doorkeeper/oauth/helpers/uri_checker.rb
- The revoke endpoint: the wiki lists none; the RFC 8414 metadata at
  `https://www.openstreetmap.org/.well-known/oauth-authorization-server` names it (to read before
  building).
- API usage policy: a valid User-Agent identifying application and version, no personal or
  confidential data submitted, clients may be blocked: https://operations.osmfoundation.org/policies/api/
- Terms of Use (OSMF, 2018-09-20): 13 years or older; the Usage Policies incorporated; "Contributions
  to OSM are governed by the OSM Contributor Terms"; API compatibility is the caller's responsibility:
  https://osmfoundation.org/wiki/Terms_of_Use
- Contributor Terms 1.2.4: the contributor must have the right to contribute; ODbL/DbCL now, another
  free licence by a 2/3 vote of active contributors; the text does not name GPS traces (the wiki and
  the privacy policy treat uploaded traces as contributions):
  https://osmfoundation.org/wiki/Licence/Contributor_Terms
- Privacy policy (2026-01-25): public traces downloadable with the uploader's display name;
  area downloads return points of non-public traces without user or time; raw files are kept; its
  advice to trim the start and end and strip extra content: https://osmfoundation.org/wiki/Privacy_Policy
- The surveyed apps' behaviour as the Prior art table cites it, in particular OsmAnd's default
  (identifiable) and GPS Logger's (trackable).

## Critic's objections and responses

Review: `0042-critic-review.md` (2026-10-23; 0 blocking, 5 serious, 9 minor; A upheld).

1. Serious, the code's return under-specified and broken (no `launchMode`; in-memory verifier).
   Accepted with change: an exported no-UI `OsmAuthActivity` for host `osm`; verifier, `state` and
   expiry persisted in the private preferences file; the tour drives the return (A's text, item 10).
2. Serious, "anonymous" over-promises. Accepted: the word is gone; the two sentences are in the
   wiki's terms (item 5); Prior art says trackable traces stay under the owner's account.
3. Serious, the trace id a silent data-format decision. Accepted with change: a local-only column,
   not in the op log; the exit cost corrected (A, item 7).
4. Serious, the custom scheme undocumented and B the wrong fallback. Accepted with change: the three
   shipping clients cited as the confirmation, "Who else" corrected (OsmAnd: App Link plus a shipped
   secret), the https App Link as A's fallback ahead of B, `state` plus PKCE named as what makes a
   claimable scheme safe; the dev registration stays the first step.
5. Serious, no agent path through the return. Accepted: `GET /osm` exposes the pending `state`,
   `signin?nobrowser=1`, `am start -d "dystopo://osm?code=mock&state=..."`, the mock's token endpoint
   accepts any code with a verifier and gains `/oauth2/revoke` (item 10).
6. Minor, B's premise wrong (the port is ignored). Accepted: B rewritten with an ephemeral listener,
   still third.
7. Minor, items 2 and 3 contradict on the name; `creator`, the exact User-Agent and point extensions.
   Accepted: item 3 lists the description prefill, `creator="Dystopo"`, `User-Agent: Dystopo/<version>`
   alone, and the explicit drop of extensions; the common text builds the upload document fresh.
8. Minor, SHA-256 is `packs/Sha256.kt`; random bytes are the platform's. Accepted: corrected.
9. Minor, a remembered 0 disarms the default; multi-segment tracks. Accepted with change: remembered
   only when at least the default, the Send button carries the cut, the segment limit stated (item 4).
10. Minor, refusals without their remedy. Accepted: "Stop recording to send", the smaller-cut remedy,
    the sheet kept on failure, 401 "Sign in again", 409 "open the sheet first" (items 1, 2, 4, 10).
11. Minor, the terms string asserts what the Contributor Terms do not say. Accepted: "Sending uses
    your OpenStreetMap account under its Terms of Use and Privacy Policy", the Contributor Terms linked
    from the sign-in step as the account's terms (items 6, 8).
12. Minor, the registry kind. Accepted with change: a `destination` kind rather than a PLANNING line,
    so the one place agents look for licence and terms facts holds the destination too (item 9).
13. Minor, Prior art row 1 answerable. Accepted: the website defaults to trackable; the departure from
    OsmAnd is an alignment with the service.
14. Minor, does the account pattern generalise. Accepted: `osm/` is specific; a second service
    extracts the flow and the token store then (the common text and "What would change").
Side finding (no share sheet or `FileProvider` in the app): taken into C and recorded in the parent
idea file's dated line.
