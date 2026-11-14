---
date: 2026-11-07
kind: bug
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

2026-11-14 (triage): built by T262 (the cause: one transient shown id; the full-screen list with its filter chips,
"Shown" and "Hidden" on every row, rename, export, statistics, delete) and T272 (the sorts, select mode, the
system share sheet, the notification's Pause and Finish). Left: the folder kept in sync on a file-sharing service
of the user's choice, and whether Settings becomes a full screen too.

Question: do you want the kept folder (the storage access framework's document tree, no service built in, ADR
0059), into which the app writes every track and route as GPX so that the desktop and the web page read the same
files? Recommended: yes, export-only first (the app writes and never reads back), as the survey's point 4 says; a
sync in both directions is a new data flow and wants a proposal. Settings as a full screen: recommended yes, with
Layout C's Menu.
Answer:
