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

2026-11-14 (the owner left this to the coordinator, "or get an independent review to confirm"; a critic confirmed):
yes to the kept folder, export-only and opt-in; a sync in both directions stays a proposal of its own. Settings as
a full screen: yes, with Layout C's Menu (the board's Later line), not in this pass.

Plan: one pass by one implementer. A "Keep a folder" row on the Tracks screen
(`androidApp/.../app/tracks/TracksScreen.kt`) opens the system's folder picker (the one T272's Export GPX uses,
`ShareGpx.toFolder` in `SaveFile.kt`), takes the persistable permission, and from then on the app writes one GPX
file per track and route into that folder, rewritten when the item changes; it never reads the folder back. Off
until the user picks a folder; a plain sentence at setup says that every recording will be written there and that
another app may copy the folder elsewhere. A rename renames the file, a delete removes the file the app wrote,
files the app did not write are never touched, and "Stop keeping" releases the permission and leaves the files.
The folder is per device, not a synced key. The layout (file names, what a rename and a delete do) is written down
in `docs/reference/android-tracks-and-sync.md` and listed in the report as a data format (ADR 0033), since the
desktop tooling reads it. Verify: unit tests for the file naming; `GET /tracks/ui` reports the kept folder's state,
its writes and its last error; the device tour records, renames and deletes a track and lists the folder each
time. Out of scope: reading the folder back, any service address (ADR 0059), Settings as a full screen.
