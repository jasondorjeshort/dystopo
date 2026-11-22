---
date: 2026-11-22
kind: feature
related: [2026-11-22-edit-and-resize-a-downloaded-area-and-download-this-area-opens-the-polygon-editor.md, 2026-11-07-downloads-as-a-synchronisation-in-cells.md, 2026-11-02-pack-updates-as-deltas.md]
---

# Download only what is missing, for a resize or an upgrade

Status: **untriaged and not sanity-checked** (an idea of the coordinator's own, offered when the owner asked for more and recorded on the owner's word, 2026-11-22). Nothing here has been read against the code or measured; the anchors and the list of what exists are from memory of the session's reading, to be checked at triage.

For a resize or an upgrade (Medium to Full), fetch only the difference between two shapes or two detail levels: the tiles a new shape adds, or the zooms a finer detail adds, and release what falls outside. "Medium to Full" becomes about the size of the added zooms, not a re-download.

**What exists already (to be checked):** **Exists in design:** ADR 0060 (downloads as a synchronisation in cells) and the idea of 2026-11-07, and pack deltas (`2026-11-02-pack-updates-as-deltas.md`). This idea is a use of them, to be checked against what ADR 0060 built.
