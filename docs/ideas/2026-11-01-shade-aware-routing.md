---
date: 2026-11-01
kind: feature
related: [2026-10-30-season-aware-routing.md]
---

# Shade-aware routing: prefer shade on a hot day, sun on a cold one

A cost term in the router (`CostModel` in `routing/Router.kt`) from the shade of each edge at the time it would be walked, so the planner prefers shaded trails on a hot day and sunny ones on a cold day. Joins the season-aware routing idea; T194's sampling and arrival times are the building blocks.

## Open

- Edge cost depends on arrival time, which depends on the route so far: a time-dependent shortest path, or a two-pass approximation.
- A user setting (ADR 0044) and its default; research on how route planners treat shade, if any.

Recorded 2026-11-01 at the close of wave 24 from the coordinator's list, moonshots included at the owner's invitation. Untriaged, and not even sanity checked: the premises below were not read against the code. Web delivery (the browser demo at `tools/demo/`, `devtool serve`) is an open question for every idea here; no Kotlin web site exists in the repository today.
