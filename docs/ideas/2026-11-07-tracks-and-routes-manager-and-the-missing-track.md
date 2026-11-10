---
date: 2026-11-07
kind: bug
related: [2026-11-07-recording-card-close-button-reads-as-stop.md]
---

# The last recorded track vanished from the map, and tracks and routes want a manager of their own

The owner, on a second flight on 2026-11-07 (v0.1.22-alpha): "the last track I recorded is gone now? What did I
do to hide it? Is it deleted? We're going to need a tracks and routes manager. Unsure but maybe these and settings
should be entire different screens rather than overlays. Depends on complexity I guess." And later: "I really don't
understand why the track disappeared. Other times tracks never go away."

Today the Tracks sheet (`androidApp/.../app/tracking/TrackControls.kt`, `TracksSheet`, T219) lists recordings with
Show and Hide per track, the overlay draws the shown ones (`tracking/TrackOverlay.kt`), and a new recording replaces
the previous track's "shown" state in some paths (T171's smoothed line, T180's anchor). Whether the earlier track was
hidden, replaced on screen by the new recording, or dropped by the recorder's own rules is not readable from the
map; nothing says "hidden" where it was.

After a third flight the same day: "I really do need a list of tracks made in another window that I can select what
to show or where to share. Linking to a file sharing tool could automatically sync these, even across tools, letting
us look at them on the desktop app."

## Open

- Reproduce: record a track, start a second recording, read `GET /tracks` and the overlay's shown set at each
  step; find which action hides the first.
- A tracks and routes manager: one screen (or the Offline maps pattern of a full-screen list) listing every track
  and route with shown or hidden, dates, lengths, and the actions (show, hide, rename, export, delete), with a
  benchmark of how the established apps lay it out; the owner's question whether settings join it as full
  screens rather than overlays is theirs, after the complexity is known.
- Sharing from the list: a track or a set of tracks to the system share sheet, and a folder on a file-sharing
  service the user chooses (the storage access framework's document tree, no service built in, ADR 0059) that the
  app keeps in sync, so the desktop tooling and the web page read the same files.
- A hidden track's trace never silently vanishes: the sheet says it is hidden, and the map's long-press menu
  can show it again.

Plan: one pass (Opus-class, a full screen), from the survey `out/wave31-research/tracks-manager.md` [no-check:
gitignored], recommendations 1 to 3. First the reproduction: record, start a second recording, read `GET /tracks`
and the overlay's shown set at each step, name the action that hid the first track and fix it in the same branch.
Then a full-screen "Tracks and routes" list built like Offline maps (`packs/PacksScreen.kt`'s pattern), opened from
where the Tracks sheet (`tracking/TrackControls.kt`, `TracksSheet`) is opened today and replacing it: one list with
a Tracks / Routes / All filter chip, sorted newest first (name and length as other sorts), each row with the
activity, date, length and a visible "Hidden" or "Shown" with its eye toggle; a row menu with rename, export GPX,
share (the system share sheet), statistics, delete; a select mode for show, hide, export and delete. A debug route
for the screen's state and the tour's step in `tools/android-check.py`; help text; strings listed and screenshots
(ADR 0031). Done when: `check` green; on the emulator the reproduction before and after, the list in each filter at
font scales 1.0 and 2.0, hide and show from the list reflected on the map. Out of scope, flagged for the owner in
the report: the kept folder sync (a new data flow; the survey's recommendation 4, export-only first), whether
Settings also becomes a full screen, and the activity as the app's mode (its own idea, after this).
