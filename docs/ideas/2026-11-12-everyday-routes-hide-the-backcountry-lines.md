---
date: 2026-11-12
kind: feature
related: [2026-11-07-the-activity-is-the-apps-mode-not-a-track-setting.md, 2026-11-07-turn-back-card-toggle-and-its-clock-in-the-air.md, 2026-11-11-trim-the-default-settings-before-beta.md]
---

# An everyday route hides the backcountry lines

The owner, 2026-11-12: "If I drive across town to a restaurant I don't need to know about my water crossings or
out-by time. Yet this takes up half the screen space." They asked for a system that fits these lines in naturally,
"maybe just a toggle at the start of the route selector".

Today the route card (`androidApp/.../app/routing/RouteControls.kt`, `RouteControls()`) shows the water line, the
terrain line, the best start and past effort under the directions for every route; drive hides only terrain and best
start (`RouteExposure.applies`, `RouteBestStart.applies`), so water shows on a drive. The navigation card's turn-back
clock ("Turn back by", "Out by"; `nav/NavOverlay.kt`, T163) shows on every navigated route, while a recording without a
route already has it off by default (T252). The travel mode is the only signal, and a walk to a restaurant and a walk
up a peak share it.

The survey (ADR 0039; `out/wave33-research/backcountry-detail.md` [no-check: gitignored]; from search summaries, and
several apps not established): no surveyed app has a separate everyday-or-backcountry toggle; the travel mode or
activity chosen first is the switch everywhere, and the apps that serve both town and trail (OsmAnd's profiles,
Locus's presets) tie the screen to it; the summary stays distance, time and climb, and the rest is pulled (profile
views, layers, a trail page's sections, user-pinned stats); none found shows a daylight or turn-back clock unprompted;
none infers the kind of trip from the route's geography.

Plan: one pass. The owner's toggle becomes the mode chips themselves, which the survey says is where every app puts
this switch: the planner's Hike chip splits into **Walk** (everyday) and **Hike** (backcountry), the same pedestrian
routing for now. Kind by mode: Walk and Drive are everyday; Hike and Line are backcountry; Bike is backcountry when
most of the route runs off roads and streets, else everyday (the share and its threshold picked by testing public
areas, reported). The planner's default for a new route on foot: Hike when most of the planned route is off roads and
streets, else Walk, so the chip shows the guess and one tap overrides it; the choice is kept with a saved trip. An
everyday route card shows the headline, the time basis, From and To, via, directions and the mode chips; no water,
terrain, best start or past effort, and no "Trip details" row. A backcountry card adds one collapsed **Trip details**
row whose one line summarises (the water count and the steepest slope, as they answer), opening to today's water,
terrain, best start and past effort lines; it is closed by default and remembers open or closed. The navigation card's
turn-back clock is off on an everyday route and on for a backcountry one (our departure from the survey: the product's
reason to exist; the card's existing control turns it off). No new setting: the survey found no split (ADR 0044).
`GET /route` reports the kind, its reason (mode or share off roads) and whether Trip details is open; strings and
screenshots of a town walk, a drive and a Rainier hike listed. Done when: `check` green with tests of the kind per mode
and of the Walk-or-Hike default from the share off roads; on the emulator a street route in a public town area from
`areas.json` defaults to Walk with no water or terrain lines and no turn-back clock under navigation, a Rainier trail
route defaults to Hike with Trip details collapsed and the clock on, and tapping Walk on it removes both. Out of scope:
different routing costs for Walk and Hike (a later idea if the owner wants sidewalks preferred); the app-wide activity
of the 2026-11-07 idea, whose planner default maps walking activities to this guess when it lands.
