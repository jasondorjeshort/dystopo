---
date: 2026-11-23
status: open
area: android / user-visible states
---

# Audit: every value that can dash, dim, tint, disable or hide, and what explains it today

## Question

The owner's audit step of `../ideas/2026-11-22-every-value-that-can-dash-or-dim-gets-a-question-mark-and-a-reason.md`:
list every place the user sees a dash, a "?", a dimmed or greyed value, a disabled control or a value that hides
itself, with the reason it can and whether anything explains it. It is the checklist for the builds and the
click-through.

## What was tried

One read-only agent (Opus-class) on `main` at 48bf56fe, every file opened, nothing run, no screenshot viewed.
`app/` is `androidApp/.../app/`, `sh/` is `shared/.../dystopo/`. Explained: **Y** at the element, **E** only in a
Settings help text, **N** nowhere.

## Result

**Readout bar.**

| Element | State | Condition | Where | Reason in code | Expl. | On an emulator |
|---|---|---|---|---|---|---|
| Elevation | dash | no height yet (start, reset, jump) | `sh/readout/Readout.kt:574`; `StripText.kt:139-142` | "no height yet", `jumpResets` | E `settings.readoutBar` | `POST /readout/reset` |
| Elevation, speed, course, accuracy | dim 0.45 | no fix for `gapMs`; the height only when the barometer is not fused | `Readout.kt:543`, `:571`, `:673`; `ReadoutStrip.kt:278`, `:300` | `dimmed`, `gapS`, caption "no fix 8 s" | partial | `POST /location`, wait 5 s |
| Climb | "?" | window filling, too uncertain, no fix past the hold, no height | `Readout.kt:583`, `:659-664` | "filling", "uncertain", "no fix" | E `settings.readoutClimb` | reset, then a replay's first 30 s |
| Climb | dash | barometer and GNSS contradict | `Readout.kt:660` | "contradiction" | E | none found |
| Climb | held, dimmed | in a gap before the hold ends | `Readout.kt:584` | "held", `frozen` | partial | `POST /location`, wait 5 s |
| Speed | dash | standing still; no fix; no speed yet | `Readout.kt:553-555` | "still", "no fix", "no speed yet" | E | two fixes at one spot; reset |
| Course | dash; an old value unmarked when standing | no fix or no course; held from the last movement | `Readout.kt:560`, `:562` | "no fix", "no course yet", `frozen` | E | reset; a replay, then stop |
| Pressure | dash; entry absent | no sample for 10 s or never; no barometer | `app/readout/LiveReadout.kt:172-185` | "silent", "no samples" | N; E for absent | a GPX replay with pressure, let it end |
| Accuracy, fix age | dash | not given by the fix; no fix | `Readout.kt:613-620` | "not given", "no fix" | N | reset |
| Trend arrow | blank | too little data, or not recording | `app/readout/PressureCard.kt:149` | `trend.result.status` | E | `POST /readout/trend?fixture=…&clear=1` |
| Known-height mark | vanishes | the pin is no longer trusted | `ReadoutStrip.kt:202-203` | `pinEnd` | N | `POST /readout/calibrate`, wait |
| Entries, the whole bar | left out or hidden for room | font scale, a warning card, the layout ladder | `sh/ui/OverlayLayout.kt:205-212`, `:316`; `ReadoutStrip.kt:60-66` | `droppedItems`, `hiddenBecause` | E; N for the bar | `POST /settings?textScale=2.0` |
| The bar's accessibility text | no state words | always | `StripText.kt:219-222` | none | N | a11y screen `map` |

**Other surfaces.**

| Surface, element | State | Condition | Where | Expl. |
|---|---|---|---|---|
| Glance view lines | dim 0.6; the deadline amber or red | as the bar; the turn-back state | `GlanceView.kt:276`, `GlanceText.kt:26-40` | N |
| Pressure card, way-down lines | absent | no fix, no elevation, below the ridge, no exits | `PressureCard.kt:723-724`, `:831-858` | N |
| Location puck | grey; hollow with a dashed ring | lost or stale; estimated or flagged | `MapController.kt:650-658`, `LocationPuck.kt:167-172` | Y (long-press line, `map.marks`) |
| Position button | grey, and a tap does nothing | no fix | `controls/Recenter.kt:34-38`, `:70-75` | N |
| Orientation button | "facing" skipped | no compass | `controls/MapOrientation.kt:228-260` | N |
| Lock, orientation, scale bar, position column | hidden for room | the layout ladder | `OverlayLayout.kt:205-212` | N |
| GPS view rows | dash | no satellites, no fix, speed under 0.5 m/s, band unknown | `gnss/GnssScreen.kt:163-175`, `:331` | N (the narrow layout says "band unknown") |
| Online chip, navigation card, turn-back line, way-back card | dots, banners, amber and red | their states | `online/OnlineOverlay.kt`, `nav/NavOverlay.kt`, `nav/WayBackClock.kt` | Y |
| Horizon view and Panorama | disabled layer buttons, dashed sun, sentences for an empty list, unnamed peaks | at a chosen point, behind terrain, no map | `horizon/SkyViewParts.kt:150-165`, `HorizonText.kt`, `panorama/PanoramaPlot.kt:449` | Y; partial for unnamed peaks |
| Follow phone | absent | no attitude sensor | `PanoramaPlot.kt:178-184` | E |
| Flights | hollow with "N s ago"; "unknown" fields; the speed line absent | stale or behind terrain; fields missing | `aircraft/AircraftLayer.kt:453`, `sh/aircraft/AircraftText.kt` | Y; N for speed |
| Download rows | "X of ?"; "Waiting"; Incomplete, Not on this phone, Needs a newer app, Part saved | no total; queued; see the rule | `areas/OfflineMapList.kt:221-269`, `packs/DownloadStatus.kt:58`, `packs/PacksScreen.kt:468` | N but "Download stopped" |
| Storage sizes | "?" | a value missing | `storage/StorageScreen.kt:447` | N |
| Tracks select actions | greyed, a tap is silent | nothing ticked, or all ticked are recording | `tracks/TracksScreen.kt:683-684` | N |
| Sync status | "?" | a field missing | `sync/SyncNowAction.kt:174` | N |

**Counts** (Y / Settings help only / N): the bar 0 / 9 / 7 with two partial; the glance view and pressure card
0 / 0 / 3; the puck 2 / 0 / 0; map controls 1 / 0 / 4; the GPS view 2 / 0 / 2; online, navigation and way-back
5 / 0 / 2; the sky views and flights 5 / 2 / 1; downloads, storage and lists 4 / 0 / 6.

**Corrections to the idea's list.** The bar's pressure comes from `LiveReadout.kt:176-185`; the puck is already
explained; Settings help already explains the speed dash and the climb's marks; a tap on the bar opens the glance
view (`ReadoutStrip.kt:267`), so "tap the value for a reason" collides with it; `ScaleBarRoutes.kt:56`'s KDoc
says the climb becomes a dash where the code gives "?".

**Build tasks proposed, disjoint files, the bar first.** (0) the reason table and its test beside the help
registry (`app/help/`); (1) the readout's rules in `shared` (reasons as a closed set); (2) the bar and the glance
view (`StripText.kt`, `ReadoutStrip.kt`, `LiveReadout.kt`, `GlanceView.kt`, `GlanceText.kt`); (3) map controls
(`controls/Recenter.kt`, `RecenterButton.kt`, `MapOrientation.kt`, `ScaleBar.kt`); (4) the pressure card's way
down; (5) the GPS view; (6) downloads and storage; (7) lists and minor. The online chip, the navigation card,
the way-back clock, the Horizon view and the puck need only the click-through.

**Fix the behaviour, not explain it.** The dim's blink and the height lost on a jump (the readout finding of the
same date); elevation's "no height yet" a dash where the climb's is "?"; the position button's dead tap with no
fix; the tracks' silent greyed actions; "Waiting" with no sentence and "of ?"; no state in the bar's
accessibility text; the pin mark leaving without a word.

**Mechanical checks.** Reason codes are free strings today (`ReadoutValue.reason`, `BarometerStatus.out`, the way
down's `state`, `Recenter.state`): make them closed sets and test that every (entry, state, reason) has text.
`HelpRegistryTest` finds only literal keys and wants 60 to 330 characters ending in a full stop, so one-line
reasons need their own table and test. A source lint beside `tools/user-text-check.py` could flag a placeholder
or a dim with no reason key on its line.

## Next

The owner's answer of 2026-11-23: the reason shows in the glance view, under the value, with a small "?" on the
bar's value while it is not normal; the bar's tap is unchanged. Not determined: which state the owner saw; whether
the emulator reports a barometer; a fixture for the climb's "contradiction"; a route that opens Storage; there
is no a11y screen for the glance view, the GPS view, Storage or the pressure card, so the click-through needs
them added.
