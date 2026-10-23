# 0042. Uploading a recorded track to OpenStreetMap as a GPS trace

Date: 2026-10-23
Status: proposed
Before a build task is drafted the owner confirms: an account in the app with the sign-in in the
system browser (rather than the no-account hand-off to the website); the per-upload sheet and the
trimming default (500 m around each end, on by default); the default visibility (trackable); and the
exact exception strings listed under Decision 9.

Record: proposal `../proposals/0042-osm-trace-upload.md` and `../proposals/0042-critic-review.md`
(0 blocking, 5 serious, 9 minor, all accepted; Option A with five amendments). The owner's answer
of 2026-10-23 to `docs/ideas/2026-10-13-external-integrations.md`; the idea
`docs/ideas/2026-10-23-osm-trace-upload.md`.

## Context

The owner chose the OpenStreetMap trace upload as the app's first outside-service integration. It
is a new data flow off the phone and needs the one exception to the rule that user-visible text
names no other mapping software, both of which `CLAUDE.md` › Decisions flags to the owner rather
than builds (ADR 0031, decision 3). The technical design is settled after the critic; this record
carries it and waits for the owner's word on the four points above.

Verified against primary sources (the proposal's Claims to verify, each checked by the critic): the
trace API (`POST /api/0.6/gpx`, multipart `file`, `description` required, `tags`, `visibility`;
OAuth 2.0 only since June 2024; scopes `read_prefs`, `write_gpx`); only `trackable` and
`identifiable` are accepted for new traces since 2026-08-11, and the website's own default is
trackable; a non-confidential OAuth 2 client needs no secret, tokens do not expire and there are no
refresh tokens; the server's redirect validator accepts a scheme of the app's own (three shipping
clients register one) while the documented rule is https or `http://127.0.0.1`; the Terms of Use,
the Contributor Terms, the Privacy Policy (its advice: trim both ends, strip extra content) and the
API usage policy (a User-Agent naming application and version). Prior art (ADR 0039 survey): the
uploading apps sign in through the browser and show a per-upload dialog without a preview of the
data; none trims; the social platforms' privacy zones are the only home-protection precedent.

Constraints: `shared` commonMain stays pure Kotlin; no client library (a documented HTTP API; any
library would have to be permissive anyway); nothing uploads without a visible per-upload
confirmation; the registration's secret never enters the repository; no Wi-Fi gate (ADR 0023);
every feature agent-testable; data formats migrate (ADR 0033).

## Decision

1. **Sign-in: a public OAuth 2 client with PKCE in the system browser.** "Sign in" opens
   `https://www.openstreetmap.org/oauth2/authorize` by an `ACTION_VIEW` intent with the client id,
   `redirect_uri=dystopo://osm`, `scope=read_prefs write_gpx`, `state` and an S256 challenge. The
   verifier, the `state` and an expiry (ten minutes) are written to a private preferences file before
   the browser opens, since the process may die meanwhile. The code is exchanged at `/oauth2/token`
   with the verifier; the token goes into the same private file (`allowBackup="false"`), never into
   the sync log or a debug answer; the display name comes from `/api/0.6/user/details`. Sign out
   revokes the token (the endpoint from the service's RFC 8414 metadata) and forgets it. A 401 later
   means revoked on the website: the sheet says "Sign in again".
2. **Registration.** The owner registers a non-confidential application on the live service and on
   the dev instance (`master.apis.dev.openstreetmap.org`) with that redirect URI and those scopes and
   gives the coordinator the two client ids. A public client has no secret; the client id is not one
   and lives in code like the releases API URL. A confidential client is rejected: a secret in an APK
   is extractable.
3. **The return path: an exported no-UI `OsmAuthActivity`** for scheme `dystopo`, host `osm`
   (`exported="true"`, BROWSABLE). It hands `code` and `state` to the process singleton and finishes;
   `MainActivity` (no `launchMode`) is untouched. A return whose `state` does not match the pending
   one is dropped. `state` plus PKCE is what makes a scheme any app may claim safe (RFC 8252 §8.1).
4. **Fallback for the redirect:** an https App Link on the project's namespace
   (`https://jasondorjeshort.github.io/dystopo/osm`, `.well-known/assetlinks.json` on GitHub Pages),
   if the service refuses the custom scheme at registration or disallows it later; the loopback
   redirect (an ephemeral listener, a manual switch back) is third. Everything after the redirect is
   the same.
5. **The upload: one multipart `POST /api/0.6/gpx`** with `file` (named `track.gpx`), `description`,
   `tags`, `visibility` and `User-Agent: Dystopo/<version>` with nothing after it. The answer, the
   trace id, is kept in a **local-only column** on the track: not in the sync op log, no `SetField`,
   no wire-format change (ADR 0033); a later migration may drop it (`.claude/rules/trackdb.md`).
6. **What is sent.** A GPX built fresh in the shared `osm/` package: trackpoints (lat, lon, elevation,
   time) only, root `creator="Dystopo"`, no metadata, no waypoints, no `dystopo:id`, each point's
   extensions dropped explicitly. The description is prefilled with the track name (by default the
   local date and time of the recording) and leaves unless edited; the tags are prefilled `dystopo`.
7. **Trimming.** Every point within a radius of the first point and within a radius of the last point
   is removed from the sent copy; the stored track is untouched. Default 500 m, on; choices 0, 200 m,
   500 m, 1 km, 2 km; the choice is remembered only when it is at least the default, so a 0 or 200 m
   applies to the one track in front of the user. A track that trims to nothing cannot be sent; the
   sheet names the remedy (a smaller cut). Known limit: a track of several segments is cut at its
   first and last point only. The sheet states that a cut is not a guarantee.
8. **The sheet is the only path to an upload.** It shows a small map with the cut parts grey; points,
   distance, duration, local start and end times, the size in kB; the visibility choice; the
   description and tags; a "Show the file" expander with the exact GPX; one fixed line that anyone
   can download the points and times; Cancel and a Send button that carries the cut ("Send, ends cut
   500 m" / "Send, nothing cut"). No "always send" switch exists. A failed send keeps the sheet. The
   action is absent on the recording track, whose sheet says "Stop recording to send". The sign-in
   step links the Terms of Use, the Privacy Policy and the Contributor Terms.
9. **Visibility and strings.** Only `trackable` (default) and `identifiable` are offered, explained
   as "Not shown under your name; the points and their times are public" and "Listed under your
   name with the points and their times"; never "anonymous". The exception strings, the only
   user-visible text that may name the service (never "OSM" alone), verbatim:
   "Send to OpenStreetMap"; "Send this track to OpenStreetMap?"; "OpenStreetMap account"; "Sign in to
   OpenStreetMap"; "Signed in to OpenStreetMap as <name>"; "Sign out"; "Sent to OpenStreetMap; it
   appears there once processed"; "Not sent: <reason>" (the reason may carry the service's own error
   text); "Sending uses your OpenStreetMap account under its Terms of Use and Privacy Policy".
   Every other new string names no product.
10. **Registry.** `sources.json` gains `osm-traces` under a new kind `destination` (one `SourceKind`
    value, the file comment updated; never composed into a credit line): provider the OpenStreetMap
    Foundation, url `https://api.openstreetmap.org/api/0.6/gpx`, the Contributor Terms, Terms of
    Use, Privacy Policy and API usage policy under its licence fields.
11. **Debug surface.** Android, debug builds: `GET /osm` (state, API base and its origin, last
    upload, client id, the pending request's `state`, challenge and expiry); `POST /osm/config?api=`
    (home-network and loopback hosts only, `CleartextPolicy.problem`); `POST /osm/signin[?nobrowser=1]`;
    the real return by `adb shell am start -d "dystopo://osm?code=mock&state=<state>"`;
    `POST /osm/signin?token=` for later steps; `GET /osm/preview?track=[&trimM=][&gpx=1]`;
    `POST /osm/send?track=` opens the sheet, `POST /osm/send/confirm` taps Send (409 "open the sheet
    first" without it); `POST /osm/signout`. The mock `tools/osm-mock.py` on `MockReleases`' pattern:
    `/oauth2/token` (any code with a verifier of 43 to 128 base64url characters), `/oauth2/revoke`,
    `/api/0.6/user/details`, `POST /api/0.6/gpx` (field checks; 400 on an empty description or a
    third visibility; keeps the GPX bytes), `GET /api/0.6/gpx/<id>`. JVM: `devtool osm-trace --gpx
    --trim-m --api --token` prints the JSON. Tour step: sign in through the `am start` return, replay
    the Skyline fixture, send, then the mock's GPX has no point within 500 m of the fixture's ends and
    no waypoint, metadata or extension; sign out hits the revoke endpoint.
12. **Scope.** `osm/` is specific to this service; a second outside service extracts the PKCE flow
    and the token store then. Out of scope: listing or deleting traces from the app, uploads of
    routes, any automatic send. Deferred: a trim by distance along the track (trigger: the owner
    prefers it); an upload without timestamps (trigger: the service offers one).

## Consequences

- The first standing credential, account row and exported browser-facing component in an app whose
  story is that nothing leaves the phone; all three are bounded (one scope, a private file, a
  `state`-checked no-UI activity) and the sheet is the one path out.
- Rejected: an OAuth client library (under a page of code, and the task ruled it out); a confidential
  client with a secret in a gitignored file and an Actions secret (extractable from the APK); the
  loopback redirect as the first fallback (a token-exempt listener and a manual switch back); the
  no-account hand-off to the website (no share sheet or `FileProvider` exists, the export is private,
  no agent path), kept as the owner's alternative; the retired `public` and `private` values.
- Costs: a hosted `assetlinks.json` only if the fallback is needed; the owner's two registrations;
  a Python mock and a devtool command to keep green; the owner's attention on the strings.
- Supersede when the service changes its redirect policy or terms, when a second service wants the
  shared flow, or when the owner replaces the sheet.

- Prediction, check when the build task merges: the device tour's upload step passes against the
  mock with the real `am start` return and no route can send without the sheet open.
- Prediction, check by 2027-04-23: no trace sent from the app has been reported to the owner as
  showing a home; the remembered-radius rule has not been loosened.
