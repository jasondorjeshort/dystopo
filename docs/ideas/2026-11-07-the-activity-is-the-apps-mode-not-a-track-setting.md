---
date: 2026-11-07
kind: feature
related: [2026-11-07-turn-back-card-toggle-and-its-clock-in-the-air.md]
---

# The current activity is the app's mode, not a setting of the recording

The owner, after a third flight on 2026-11-07: "current travel mode shouldn't just be a track setting but an app
mode. If I'm driving or flying and not recording a track we want it to work the same. But when routing we do need
to put it in as it's normal walk to your car."

Today the activity (hike, bike, drive, fly: T158's detection, T172's picker, `androidApp/.../app/tracking/
ActivityPicker.kt`) belongs to the recording: it names the track, sets the recorder's profile and feeds the readout's
rules only while a track is being recorded. Without a recording, the readout, the barometer rule, the turn-back
clock and the map's behaviour have no activity to go on, and a drive or a flight without a track gets the hiker's
rules. The route planner has its own travel mode (hike, bike, drive, crow), and a planned route often starts with a
walk to the car, so the planner's mode is a different thing from the moment's activity.

## Open

- One current activity for the app, set or detected, shown and changeable from the map (the readout strip or the
  top row), driving the readout's sources (the barometer off when flying), the way-back clock's pace, the alert
  rules and the recorder's profile when a recording starts; the recording keeps its own activity per segment as
  today.
- The planner's mode stays separate and defaults from the current activity with the walk-to-the-car exception the
  owner names.
- A benchmark (ADR 0039) of how the established apps hold an activity outside a recording, before the design.

2026-11-10 (triage): the benchmark is done, `out/wave31-research/tracks-manager.md` [no-check: gitignored],
recommendations 5 and 6 (one app-wide mode as the surveyed outlier that has one; the planner's default from it,
overridable per route; the recording's own value editable after). T250 made the readout follow the recording's
activity; outside a recording it still reads the remembered `AppSettings.trackActivity`.

Plan: one pass, after the tracks and routes manager lands. The remembered activity becomes the app's current
activity (the same stored key, synced as today, so no migration): a small chip on the map (in the readout strip's
row, placed by the layout rule) shows and changes it; T158's detection may suggest a change on the chip, never
switch it silently; the readout, the way-back clock, the alert rules and a new recording's profile read it when no
recording runs; the route planner's travel mode defaults from it (hike for any walking activity, drive for drive,
bike for bike) and stays changeable per route. A setting "Ask for the activity when recording starts", default off
(the survey's split, ADR 0044). `GET /readout` and `GET /tracks` report the current activity; strings and
screenshots listed. Done when: `check` green with a test that the planner's default follows the activity; on the
emulator with no recording, setting flying on the chip takes the barometer out (`GET /readout`), and a new route
defaults to the mapped mode. Out of scope: per-segment activities in a recording (unchanged).
