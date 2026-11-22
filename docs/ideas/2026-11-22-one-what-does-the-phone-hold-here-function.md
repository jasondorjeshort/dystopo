---
date: 2026-11-22
kind: feature
related: [2026-11-22-a-downloaded-map-must-never-make-the-view-worse-than-online.md, 2026-11-22-sky-from-here-in-colorado-says-no-elevation-data-with-a-pack-and-the-internet.md, 2026-11-20-no-map-message-shown-with-a-bulk-downloaded-region.md]
---

# One "what does the phone hold here?" function for every reader

Status: **untriaged and not sanity-checked** (an idea of the coordinator's own, offered when the owner asked for more and recorded on the owner's word, 2026-11-22). Nothing here has been read against the code or measured; the anchors and the list of what exists are from memory of the session's reading, to be checked at triage.

The map, the Horizon view, the Panorama, the shade, place search, the Download card and the first-run card each ask separately what the phone holds at a point, which is why they disagree (the owner's reports of 2026-11-22). Idea: one function answering, for a point and a zoom, the detail held per open pack or area, which one serves it, and what the online source would add; every reader calls it, and it is the lookup of the finest-first rule.

**What exists already (to be checked):** `MapCoverage` (`app/search/PlaceSearch.kt`), `Areas.isRecorded` (the status chip) and the first-run rule of T332 (wave 46) are each a part of it; T332 writes one rule for "the phone holds a map".
