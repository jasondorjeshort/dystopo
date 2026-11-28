---
date: 2026-11-28
kind: feature
related: [2026-11-21-peak-names-for-your-old-photos.md, 2026-11-28-moonshot-a-live-track-through-the-kept-folder.md]
---

# Moonshot: photos from the hike placed on the track by their time

Source: coordinator (claude-fable-5-2), a wave close; offered and accepted by the owner; not sanity-checked. Moonshot.

The owner, 2026-11-28, accepting it with the live-track idea: "#7 and #8 sound pretty cool. These are moonshots
though and there's a lot to do first."

The idea: the user points the app at a folder of photos (the same folder pick "Keep a folder" uses,
`androidApp/.../app/tracks/KeptFolder.kt`); each photo taken during a recording is placed on that track at the
point recorded at the photo's time, shown as a small mark on the track and a row in the trip report. Nothing is
uploaded and no photo is copied: the app keeps a reference and the matched point.

Lever: "Peak names for your old photos" (resting) needs a photo's place first, and most cameras other than the
phone's write none; the trip report gains its pictures.

## Open

- A new data flow: reading the user's photos. The owner's to allow, with what the user is told; a folder picked
  by the user, never the whole library.
- Clock differences between a camera and the phone (the GPS clock work of wave 49 shows how wrong a clock can
  be); photos taken while no recording ran.
