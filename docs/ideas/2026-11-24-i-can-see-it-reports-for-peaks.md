---
date: 2026-11-24
kind: feature
related: [2026-11-24-refraction-from-the-weather-for-long-sight-lines.md, 2026-11-22-star-cutoff-as-a-shared-sky-quality-reading.md]
---

# "I can see it" reports for peaks (moonshot)

Source: coordinator (claude-opus-5-5), after the ideas triage of 2026-11-24; offered and accepted by the owner; not sanity-checked. Moonshot.

The app predicts which peaks are visible (`Viewshed`, the Panorama's placed names) but never learns whether they
were. The idea: on a peak in the Panorama or the List, "I can see this" or "It's hidden", stored on the phone with the
place, time, the drawn prediction and the weather if known; optionally shared (a new data flow: the owner's to decide,
flagged per CLAUDE.md). Uses: check the refraction coefficient and the view range against what people actually see,
find elevation-model faults (a peak marked hidden that people see), and a personal log of the far peaks seen. Lever:
the only ground truth for long sight lines; it outranks the shared star-cutoff reading as a crowd check with a
measurable answer.

## Open

- Kept on the phone only at first, or shared from the start (a new data flow, privacy rules apply: a report carries a
  position).
- Where the tap lives without cluttering the Panorama.
