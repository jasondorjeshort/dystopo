---
date: 2026-11-22
kind: feature
related: [2026-11-22-where-might-i-get-a-signal-line-of-sight-to-a-mast-or-a-town.md]
---

# Can those two places see each other? A sight line between any two points, for radios

Status: **untriaged** (an idea of the coordinator's own from wave 46's work, offered when the owner asked "Any ideas out of this wave? Moonshots welcome." and recorded on the owner's word, 2026-11-22: "Record them all; we'll triage and think through them later."). The anchors are files wave 46 wrote or read; nothing else here was checked against the code or measured.

T334's line answers from the fix to a pressed place. The same rule between any two points on the map (`shared/src/commonMain/kotlin/io/github/jasondorjeshort/dystopo/horizon/SightLine.kt` takes a viewer and a place; the kept horizon is only a shortcut for the fix) answers what a party with handheld radios asks: can camp reach the summit, can the car reach the pass. Along a planned route against one fixed point (camp, the trailhead) it becomes stretches of "radio shadow" on the route's profile.

## Open

- The gesture: a second long-press while a first place is selected, or "From here to..." on the card (`androidApp/.../app/tracking/LongPressMenu.kt`).
- Radio is not light: low bands bend over ridges a little, and antenna height matters; whether to say only "line of sight" and leave the radio judgement to the user (likely).
- Devtool has `viewshed` and `horizon`; a `sight --from --to` command would make it agent-testable first.

Plan: after the line-of-sight primitive (`2026-11-22-one-line-of-sight-function-in-shared.md`) lands, one small pass on
its `between` call (that pass brings the `sight --from --to` devtool command). The long-press card
(`app/tracking/LongPressMenu.kt`) gains "Sight line to...": it keeps the pressed point as A, a banner asks for the second
point, the next long-press answers on its card with `Sight.text` from A (eye 1.7 m at both ends), and the map draws the
line between them, green up to the blocking ridge and grey beyond it when hidden; Back or the banner's close clears it.
The wording says "line of sight" only, with one help sentence that radios can reach a little past a ridge and that
antenna height counts. A debug route `GET /sight?from=&to=` mirrors the card. Verify: unit tests through the primitive;
on the emulator at Paradise, to Camp Muir (in view) and to a point behind Mazama Ridge (hidden), screenshots at font
scale 1 and 2, strings in the report (ADR 0031). Out of scope: radio shadow along a route, antenna height as a setting.
