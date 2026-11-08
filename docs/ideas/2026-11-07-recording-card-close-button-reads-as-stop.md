---
date: 2026-11-07
kind: feature
related: [2026-11-07-tracks-and-routes-manager-and-the-missing-track.md]
---

# The recording card's X looks like it ends the track; it wants a minimise control instead

The owner, on a second flight on 2026-11-07 (v0.1.22-alpha): "the x button when recording a track really looks
like it will end the track. Is there a good minimize button instead?"

The recording panel (`androidApp/.../app/tracking/TrackControls.kt`, the panel the Tracks chip opens, T172 and T219)
closes with an X that only hides the panel; Stop is a separate chip. A close glyph beside a running recording reads
as "stop" to anyone who has not learned otherwise, and the fear of it keeps the panel open.

## Open

- A collapse control (a chevron, "Hide", or a drag handle) where the X is, with Stop kept apart and named; a
  benchmark (ADR 0039) of how recording apps separate "hide this panel" from "stop recording".
- The same read on the other cards with an X that does not end what they show.
