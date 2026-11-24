---
date: 2026-11-24
kind: feature
---

# Sort the List view

The List view (T344, ADR 0067) shows peaks closest first. A user looking for the highest summit in
sight, or for what lies in one direction, has to scan the whole list.

The idea: a sort choice on the List with distance (today's order, the default), height and bearing.
A benchmark of how established topo and navigation apps order such lists comes first (ADR 0039).
If apps are split, the choice becomes a remembered setting (ADR 0044).

Recorded from the post-wave brainstorm of wave 47 (the owner, 2026-11-24).

Plan: two agents, after wave 48 (which does not own the List; T344's step two is a later wave's, so stay clear of the
Horizon pane). (1) A read-only benchmark (`brief` skill, `benchmark.md`, every cited source fetched): how established
topo, peak-finder and navigation apps order a list of visible peaks or places, and whether the order is remembered.
(2) One implementer: a sort choice on the List (`app/skylist/SkyListScreen.kt`): distance (default, today's order),
height, bearing (clockwise from the view's heading, or from north if the benchmark says so). Remembered as a synced
setting only if the benchmark finds apps split (ADR 0044), else a per-session choice; spoken label and help text;
the order as a pure function in `shared` with tests; `GET` of the List's route reports the sort, and a `POST` sets it.
Verify on the emulator at Mount Rainier (Paradise): each order's screenshot viewed, the route's rows checked against
the function. Out of scope: filters, the List's range from the Horizon view.
