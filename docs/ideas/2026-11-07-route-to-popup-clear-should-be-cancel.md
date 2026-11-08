---
date: 2026-11-07
kind: feature
---

# On the route-to popup, "Clear" should read "Cancel"

The owner, on a second flight on 2026-11-07 (v0.1.22-alpha): "on the route-to popup, 'clear' really should be
'cancel'."

T217 gave the planning card one primary action and the actions Go, Save and Clear (`androidApp/.../app/routing/
RouteControls.kt`, the `CLEAR` item). While a route is only being asked for, nothing has been made yet, so the
action that drops the request is a cancel; "Clear" reads as emptying something that exists. Once a route is drawn,
"Clear" may still be the right word for removing it; the two states may want two words.

## Open

- "Cancel" while the request is open or computing, "Clear" once a route is on the map; the strings listed (ADR 0031).
- The same look at the other cards' dismiss words (the trip card, the way-back card).
