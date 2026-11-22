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
