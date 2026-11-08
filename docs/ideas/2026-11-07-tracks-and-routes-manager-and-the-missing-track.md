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

## Open

- Reproduce: record a track, start a second recording, read `GET /tracks` and the overlay's shown set at each
  step; find which action hides the first.
- A tracks and routes manager: one screen (or the Offline maps pattern of a full-screen list) listing every track
  and route with shown or hidden, dates, lengths, and the actions (show, hide, rename, export, delete), with a
  benchmark of how the established apps lay it out; the owner's question whether settings join it as full
  screens rather than overlays is theirs, after the complexity is known.
- A hidden track's trace never silently vanishes: the sheet says it is hidden, and the map's long-press menu
  can show it again.
