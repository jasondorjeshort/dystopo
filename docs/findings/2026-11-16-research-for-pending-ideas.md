---
date: 2026-11-16
status: open
area: ideas / outside research
---

# Outside research for the pending ideas: what is settled, what each idea can now take, and what is still unverified

## Question

The owner (2026-11-16): review every pending idea for priority, blockers and research, and do the research now.
Claims are marked **[src]** (a source fetched that day) or **[mem]** (background knowledge, not re-verified: a
starting value, never a constant to ship without its check).

## What was tried

Four read-only reviewers read the idea files against the code; five Sonnet-class agents searched and fetched.

## Result

### Process and tooling (the build-process wave)

- **Per-agent cost by hook** (`2026-11-10-the-meter-reading-per-agent-recorded-by-a-hook.md`) [src: code.claude.com
  docs, hooks and statusline]: `SubagentStart` carries `agent_id`, `agent_type` only. `SubagentStop` carries
  `agent_id`, `agent_type`, `agent_transcript_path` (the subagent's own JSONL under `subagents/`),
  `last_assistant_message`; no tokens, duration or model. `PreToolUse` on `Agent` carries `description`,
  `subagent_type`, `model`; `PostToolUse` on `Agent` (foreground only) carries `resolvedModel`, `totalDurationMs`,
  `totalTokens` and `usage`, but those cover the final API request only. A background launch returns
  `async_launched` with no usage. The 5-hour and 7-day percentages reach the statusline only
  (`rate_limits.five_hour.used_percentage`, `resets_at`), which is what `.claude/statusline.js` already uses.
  So: tokens are summed from `agent_transcript_path` (its schema undocumented: dump one first), the meter is read
  from the statusline's state file at start and stop, and the model comes from `PreToolUse`. A handback's report is
  `tool_input.message` of a hook on `SubagentHandback`.
- **Device parts on a cloud runner** (`2026-11-10-device-parts-of-the-sweep-on-a-cloud-runner.md`) [src: GitHub
  docs and changelog 2024-04-02]: a public repo's `ubuntu-latest` is 4 vCPU, 16 GB RAM, 14 GB disk, free, 6 h per
  job; KVM works after the udev rule. `reactivecircus/android-emulator-runner` is Apache-2.0 and active (latest
  release unconfirmed: read its releases page before pinning). Its README's practice: cache the AVD and snapshot,
  `-no-snapshot-save`, animations off, and pass every emulator flag (custom options replace the defaults). Disk is
  the tight resource. Boot times [mem]: 1 to 3 min cold on KVM.
- **Retry once** (the sweep-retry idea) [src: Gradle test-retry, Bazel]: tools retry by outcome, off by default,
  report each attempt and mark a pass on retry as flaky. The idea's plan matches; no change.

### Hiking and recording practice

- **Gross elevation gain** (`2026-11-07-gross-elevation-gain-with-noise-handling.md`) [src: OsmAnd docs; Strava
  support; GPS Visualizer; Runalyze]: OsmAnd is the only published algorithm: noise filters, then Douglas-Peucker
  at 7 m, then summed extremes. Strava uses a recognised barometer with light smoothing, else its DEM basemap with
  heavier smoothing, threshold unpublished ("2 m barometric, 10 m otherwise" seen in a snippet, unconfirmed).
  Garmin about 3 m [forum, anecdotal]. Takeaway: barometer 2 to 3 m, GNSS or DEM 5 to 10 m; the plan's per-source
  threshold is the practice, and the stats line naming the rule is more than any app does.
- **A recording left running** (`2026-11-11-a-days-old-recording-still-reads-active.md`) [src: thin, forum only]:
  Strava resumes after a crash with a notice; no app documents an idle auto-stop. No convention to copy: the plan's
  "ask on the next open" is our own design, and the report says so.
- **Backup and restore** (`2026-11-14-backup-and-restore-in-place-of-alpha-migrations.md`) [src: OsmAnd docs,
  Organic Maps news 2024-03-11, Locus docs, Gaia help, Android Auto Backup docs]: offline-first apps write one
  self-contained file (OsmAnd `.osf`, Organic Maps KMZ, Locus backup) with no account; Gaia uses account sync.
  Android Auto Backup is 25 MB per app to the user's Google Drive, nightly on Wi-Fi when idle, latest copy only;
  API 31+ splits `cloud-backup` from `device-transfer` rules so large files can move phone to phone but stay out of
  the cloud. Maps are re-downloaded, not backed up, everywhere. Takeaway for the proposal: one export file is the
  product; Auto Backup of settings and the track database is a free extra, never the path.
- **Map refresh cadence and limits** (`2026-11-04-areas-update-themselves-by-how-you-use-them.md`, ADR 0060)
  [src: openfreemap.org/tos updated 2025-02-26; Protomaps docs; Ericsson mobility report 2025]: OpenFreeMap's
  terms allow offline downloads of at most 3,000 tiles or 50 MB each, 4 parallel requests, a unique User-Agent,
  larger by email; no rebuild schedule in the terms (weekly planet builds per its repo [mem]). Protomaps builds
  daily and keeps a week. Average smartphone use is about 25 GB a month in North America and Western Europe. So a
  weekly or monthly refresh is enough, and a daily limit near 1 GB (the owner's figure) is about 4 % of an average
  month at worst; the direct-mode cap is OpenFreeMap's, already in Later.
- **Times as a range** (the arrival-range idea) [src: thin]: hiking apps show one number with an adjustable pace.

### Android platform

- **BLE between phones** (group positions, mesh breadcrumbs, the barometer network) [src: developer.android.com,
  background BLE]: scanning and advertising work with the app not visible while its process lives; the lasting
  form is a `connectedDevice` foreground service started while the app is visible. `BLUETOOTH_SCAN` with
  `neverForLocation` avoids the location permission. About 5 scan starts per 30 s, then silent delay [src,
  developer blogs]; screen-off scans need a `ScanFilter` [mem]. Legacy advertising 31 bytes, extended up to about
  1,650 with `isLeExtendedAdvertisingSupported()` [mem]. **LoRa: Meshtastic's protobufs are GPL-3.0 [src], its
  app and firmware GPL**; no permissive LoRa mesh stack was found. A LoRa path is closed under our licence rule
  unless a permissive stack turns up.
- **Offline speech** (`2026-10-31-ask-the-map-by-voice.md`) [src: SpeechRecognizer reference; alphacephei.com]:
  `createOnDeviceSpeechRecognizer` (API 31+) binds to whatever recognition service the system ships, in practice
  Google's, and throws where none is available, so it is not dependable without Google apps [mem]. Vosk's small
  English model is 40 MB, Apache-2.0, about 10 % WER, with a grammar mode for a fixed command list. A headset
  button with the screen off needs an active `MediaSession` tied to a media foreground service [mem].
- **Low-battery signals** (`2026-10-13-battery-efficiency.md`) [mem]: `PowerManager.isPowerSaveMode` and its
  broadcast, `getLocationPowerSaveMode` (Battery Saver can turn location off with the screen off, which would stop
  a screen-off recording: warn), `ACTION_BATTERY_LOW`. What other outdoor apps cut was not found.
- **3D terrain** (`2026-10-31-offline-three-d-terrain-view.md`) [src: maplibre-native releases page, MapLibre
  newsletters]: not shipped. The newest Android releases are 13.7.1 (2026-10-15) and 13.5.4 (2026-11-09), neither
  with terrain; globe is in pre-releases only; the March 2026 newsletter put out a call for funding for
  Terrain3D on Native. The idea's recommended `Waits:` stands; its trigger is a MapLibre Native release with
  terrain.

### OpenStreetMap, translation, pairing and QR

- **OSM trace upload** (ADR 0042, `2026-10-23-osm-trace-upload.md`, `2026-10-29-trail-corrections-from-tracks.md`)
  [src: OSM wiki, OAuth, API v0.6, Automated Edits code of conduct]: OAuth 2.0 with PKCE as a public client,
  scopes `write_gpx` and `write_notes`; redirect URIs must be HTTPS or `http://127.0.0.1` loopback (custom schemes
  not documented: test on the dev server). Upload is `POST /api/0.6/gpx` (multipart: `file`, `description`
  required up to 255 chars, `tags`, `visibility`); the wiki says `public` and `private` now error, leaving
  `trackable` and `identifiable` (surprising: confirm on the dev server). "The trail is wrong here" goes as a Note,
  never an automatic edit (the code of conduct covers unreviewed edits).
- **Translation** (`2026-11-08-one-strings-registry-and-localisation.md`) [mem]: Android `strings.xml` with plurals
  and Weblate's free hosting for open source is what OsmAnd, Tusky and Element do; Compose Multiplatform resources
  only if an iOS Compose UI is planned. No stock lint flags `Text("literal")` in Compose: a custom check or a test.
- **Pairing the phone to the site by QR** (`2026-11-01-the-site-as-the-phones-planning-desk.md`) [mem]: the QR
  carries a one-time code that expires in minutes plus the server URL, exchanged over TLS for a per-device token
  (as Nextcloud's login flow v2 does); never a long-lived token. App Links cannot verify each user's own domain, so
  self-hosted links stay in-app scans or a custom scheme.
- **Route in a QR on paper** (`2026-10-30-paper-backup-sheet-with-turn-back-times.md`) [mem]: version 40 at level
  M holds 2,331 bytes, but printed codes read reliably up to about version 25 (about 1,000 bytes); a simplified,
  deflated encoded polyline of 200 to 400 points fits. Nayuki's encoder is MIT and small.

### Drive routing (the cluster after the build-process wave)

- **Speed in bends** (`2026-11-14-drive-speed-falls-on-winding-roads.md`) [mem, from FHWA-RD-99-171's form; the
  coefficients to be read from the report]: operating speed on two-lane rural curves fits V85 = a - b/R (about
  106 - 3,700/R km/h). The implied lateral acceleration is not constant: about 3.5 to 4 m/s² on 100 to 150 m radii,
  falling to 1.5 to 2 at 100 km/h. AASHTO's design friction (0.23 at 60 km/h, 0.15 at 100) is lower again.
  `SpeedProfile`'s constant 2.0 m/s² is therefore slow in tight bends and right in fast ones; an a(v) or the V85
  form is the change, checked against the owner's drives.
- **Controls and turns** (`2026-11-14-drive-stops-and-signals-cost-time.md`) [src: OSRM `car.lua`; mem: HCM]:
  OSRM charges a turn up to 7.5 s by angle, an extra 2 s for a turn across oncoming traffic, 20 s for a U-turn, and
  does not scale by the road crossed. HCM control delay: signal 20 to 35 s at the usual service levels, a stop 8 to
  15 s at light load. Starting values: signal 12 to 15 s mean, stop 8 to 10, give-way 2 to 4. Valhalla is said to
  scale by the class crossed [mem, its docs not read].
- **Traffic counts** (`2026-11-15-a-turn-across-traffic-costs-more-where-the-road-is-busy.md`) [mem except the
  UPS items]: the US federal highway performance data (public domain as federal work) and the UK's road traffic
  counts (Open Government Licence) publish average daily traffic per section; European national counts exist
  country by country; no global open link-level estimate. The parcel carrier's figures (about 10 million gallons, a
  2004 policy, left turns under 10 % of its turns) [src: press, secondary] are a company claim from multi-stop
  delivery rounds, not an effect size for an A-to-B drive.
- **Sharp bends** (`2026-11-16-a-warning-before-a-sharp-bend-while-driving.md`) [src: FHWA, TxDOT pages]: advisory
  speeds come from ball-bank readings of 16° up to 20 mph, 14° at 25 to 30, 12° from 35 mph (about 2.1 to 2.8
  m/s²); an advisory plaque is called for when the 85th-percentile speed exceeds the advisory by more than 10 mph.
  "Sharp" can then be: the bend's cap at least 16 km/h under the approach speed. What other apps do was not found.
- **Several routes** (`2026-11-15-the-card-offers-the-other-route.md`) [mem]: candidates by the penalty or plateau
  method, filtered by limited sharing and stretch (Abraham et al. 2010), scored on time and fuel, keep the
  non-dominated, at most three, each labelled by its difference from the fastest.
- **Energy on a charge** (`2026-11-15-can-i-get-there-on-this-charge.md`) [mem]: Fiori, Ahn and Rakha 2016's
  power-based EV model, regeneration up to about 60 to 70 % in gentle braking; hybrids recover 10 to 20 % in town;
  e-bikes 1 to 12 Wh/km by assist. State the answer as a range.

## Next

- Before any [mem] number is built in: FHWA-RD-99-171's coefficients from the PDF; OSRM's obstacle values for
  signals and stops; Valhalla's costing docs; the 2023 MUTCD text on the speed differential; the parcel carrier's
  own statement. These are a short read for the pass that builds the drive cluster.
- On the OSM dev server, at ADR 0042's build: a custom-scheme redirect, and whether `private` visibility still works.
- Promote: the hook fields to `docs/reference/` when the per-agent cost hook is built; the BLE and LoRa licence
  facts to ADR 0051's record at its next amendment.
