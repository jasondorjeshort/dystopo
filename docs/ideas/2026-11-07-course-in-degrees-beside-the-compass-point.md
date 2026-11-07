---
date: 2026-11-07
kind: feature
---

# Course in degrees beside, or instead of, the compass point

The owner, on a flight on 2026-11-07: "course should add or replace NNE from 163deg. Maybe a setting but I'd lean
toward both for now."

The readout strip's course entry shows a compass point (`androidApp/.../app/readout/ReadoutStrip.kt`, the text from
`shared`'s `readout/ReadoutText.kt`). A pilot, a sailor or anyone with a bearing to hold reads degrees.

## Open

- Both by default ("NNE 163°"), with the strip's width rule deciding when only one fits (T177's templates).
- Whether it is a setting (ADR 0044: survey how navigation apps show course; a split means a setting with the
  researched default).
- True or magnetic, by the existing heading rule (`shared`'s `nav/Wmm.kt`, `nav/Compass.kt`); say which on screen
  if both are ever shown.
