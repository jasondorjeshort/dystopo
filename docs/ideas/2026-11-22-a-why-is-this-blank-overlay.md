---
date: 2026-11-22
kind: feature
related: [2026-11-22-the-map-is-nearly-empty-at-the-zooms-between-the-world-overview-and-the-packs.md, 2026-11-22-a-zoom-label-by-the-scale-bar-as-an-option.md]
---

# A "why is this blank?" overlay for a sparse view

Status: **untriaged and not sanity-checked** (an idea of the coordinator's own, offered when the owner asked for more and recorded on the owner's word, 2026-11-22). Nothing here has been read against the code or measured; the anchors and the list of what exists are from memory of the session's reading, to be checked at triage.

A debug or advanced toggle that names the cause of a thin view: below the packs' floor, above the overview's top zoom, a layer's minimum zoom, an empty or failed tile, a setting that is off. It would have saved most of the guessing of 2026-11-22.

**What exists already (to be checked):** The debug routes `/stats` and `/features` and the status chip's "no data here" and "from a wider zoom" words already say parts of it; the zoom label of the owner's idea is its first piece.

Plan: a debug route first, no user-visible toggle. `GET /why` (optional `z`, `lat`, `lon`; default the camera), in the file
that serves `/stats` and `/features`, reports for the point: the zoom; each open pack's vector and elevation zoom range and
whether it covers the point; the world overview's top zoom and whether it answers the tile (`Packs.worldAnswers`); whether
the online source is allowed and what it last returned for that tile; the style layers visible at that zoom with their
minimum zooms and feature counts; the Hillshade and Contours settings; and one `cause` word (below the packs' floor, above
the overview's top, a layer's minimum zoom, an empty tile, a failed fetch, a setting off, none). Docs: the route file's KDoc
and `docs/reference/android-debug-routes.md`. Verify on an emulator with a public pack (Rainier): z6, z9 and z13 inside it
and one point outside, online and offline; each `cause` matches the screenshot. Out of scope: fixing any cause; a user toggle
(a later question once the route has proved itself).
