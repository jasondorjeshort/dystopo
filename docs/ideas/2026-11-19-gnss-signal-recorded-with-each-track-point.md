---
date: 2026-11-19
kind: feature
---

# The GNSS signal recorded with each track point

A recorded track keeps each fix's accuracy but not why it was poor. With the GPS view's `GnssStatus` listener
(T307) running while a track records, each kept fix could carry the mean C/N0 of the four strongest
satellites and the number used. Afterwards a wobbly stretch shows its cause (signal fell under the canyon wall,
or the phone went into a pocket), and the track can be coloured by signal. It changes the track database
(`.claude/rules/trackdb.md`: a migration) and the GPX export (an extension element).

## Open

- The listener's battery cost over a long recording; one value a fix is enough.
- Whether the track's colouring is a new mode beside the existing ones or a line in the track's details.

Plan: each kept track fix carries two nullable values, `cn0Top4` (mean C/N0 of the four strongest satellites, one
decimal) and `satsUsed`, taken from the `GnssStatus` listener (`app/gnss/Gnss.kt`) held while a recording runs (the
battery cost of the listener measured over a 30-minute emulator recording against one without, in the report). The
track database gets a migration by `.claude/rules/trackdb.md`, with its migration test; the sync op carries the two
fields if track points sync (old phones ignore them, ADR 0033 lists it in the report); the GPX export writes them as
an extension element in the project's namespace and the import reads them back. Trip details gains one line, the
median signal and the share of points under 25 dB-Hz. Verify: migration and GPX round-trip tests, a replay recording
with `POST /gnss?case=` switched mid-way, its details line. Out of scope: colouring the track by signal (a later
pass), the weak-signal note.
