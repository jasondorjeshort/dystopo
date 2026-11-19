---
date: 2026-11-19
kind: feature
related: [2026-11-19-a-gps-view-and-a-gnss-health-check.md]
---

# The GNSS signal recorded with each track point

A recorded track keeps each fix's accuracy but not why it was poor. With the GPS view's `GnssStatus` listener
(the related idea) running while a track records, each kept fix could carry the mean C/N0 of the four strongest
satellites and the number used. Afterwards a wobbly stretch shows its cause (signal fell under the canyon wall,
or the phone went into a pocket), and the track can be coloured by signal. It changes the track database
(`.claude/rules/trackdb.md`: a migration) and the GPX export (an extension element).

## Open

- The listener's battery cost over a long recording; one value a fix is enough.
- Whether the track's colouring is a new mode beside the existing ones or a line in the track's details.
