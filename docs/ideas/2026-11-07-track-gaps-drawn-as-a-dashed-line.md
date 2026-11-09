---
date: 2026-11-07
kind: feature
related: [2026-11-07-blue-dots-on-the-map-unexplained.md]
---

# A gap in a recorded track is bridged with a dashed line so its direction can be read

The owner, on a second flight on 2026-11-07 (v0.1.22-alpha): "we should probably connect missing track pieces with a
dotted/dashed line or something. Otherwise there's no way to connect the direction sometimes. Colors could be a
thing here but they could also represent older tracks." The screenshot shows two solid pieces of one track with
nothing between them.

The overlay (`androidApp/.../app/tracking/TrackOverlay.kt`, T171's smoothed line) draws a track as its recorded
segments; a gap where no fix was accepted (T203's accuracy cut, a lost fix, the flight's 18 minutes) is simply
empty, so two pieces read as two tracks.

## Open

- A dashed straight bridge between a segment's end and the next's start when the gap is longer than the recorder's
  own interval, in the track's colour, with the gap's length and time on the track card's statistics.
- Colour stays for the track's identity (older tracks); the dash carries "unknown path", and the help text says so.
- The GPX export marks the gap (a new segment, as GPX already allows) rather than a straight line.
