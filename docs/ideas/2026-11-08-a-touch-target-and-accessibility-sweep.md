---
date: 2026-11-08
kind: chore
related: [2026-11-07-blue-dots-on-the-map-unexplained.md]
---

# A touch-target and accessibility sweep: every node's bounds match its drawing, and a tap at its centre does what it says

The owner, 2026-11-08, on the idea: "Yes, again."

T243's defect was a control whose accessibility node did not match its drawing: the long-press card's "Route to
here" node sat 46 px above its text after a modifier-order change, and nothing caught it until the sweep's tool
tapped the node's centre and nothing happened. T243 added `semanticsAway` to `GET /layout` for the elements the
layout rule places, and a check inside that one card. The app has never had an accessibility pass: TalkBack
traversal, touch targets under 48 dp (T243's follow-up: the card's actions are 28.5 dp), labels missing on icons,
contrast.

## Open

- A sweep part that walks every reachable screen's semantics tree (`uiautomator dump`, or the debug route reading
  Compose semantics), compares each clickable node's bounds with its drawn pixels (a screencap and the node's text
  or icon position), taps each at its centre and asserts the effect the node promises (a route planned, a sheet
  opened, a toggle flipped) through the debug routes, and lists nodes under 48 dp, without a content description,
  or overlapping another clickable node; the list is the part's report, a new failure a red part.
- The same walk with TalkBack's reading order, so the app can be used by ear; the findings become tasks.
- Which screens: the map with each card up, the Offline maps list, Settings and its pages, the recording panel,
  the share screen, the first-run card.
