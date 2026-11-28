---
date: 2026-11-28
kind: feature
related: [2026-10-31-trip-plan-left-with-a-contact.md, 2026-11-02-last-known-position-shared-with-the-group.md, 2026-10-30-paper-backup-sheet-with-turn-back-times.md]
---

# "Where am I", for a rescue call, and the position sent as a text message

Source: coordinator (claude-fable-5-2), a wave close; offered and accepted by the owner; not sanity-checked.

The owner, 2026-11-28, accepting it: "I at one idea had the idea of text messaging your exact coordinates to
another phone...possibly even continuously to keep track of location."

Two parts, one primitive (the position said so that another person can use it):

1. One screen in large type for the moment someone must be told where you are: the position in every coordinate
   format at once, the elevation, the accuracy and the fix's age, and a sentence placing you from named features
   nearby (a distance and direction from a named peak, lake or trail junction, and the trail you are on when the
   map knows it), with Share and Copy. Anchors: the glance view's position line
   (`androidApp/.../app/readout/GlanceView.kt`), the formats in `shared/.../geo/CoordinateFormat.kt`, the place
   index built from an area's tiles (T377) and the peak names (T378).
2. The owner's: the position sent as a text message to another phone, once by a tap, and possibly repeated on a
   timer while recording so the other phone can follow. A text message needs no data connection and no server.

Lever: the trip-plan and group ideas prepare for trouble; this serves the moment itself, with what the phone
already knows offline.

## Open

- Sending messages by itself needs the system's permission to send SMS, a new permission and a new data flow
  (a position leaving the phone): the owner's to allow, with what the user is told. A tap that opens the
  messaging app with the text filled in needs neither.
- The repeat: how often, what it costs in battery and messages, how it stops; whether the other phone's copy of
  the app reads the messages into a dot on its map.
- The sentence's wording and which features it may name.
