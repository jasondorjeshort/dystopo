---
date: 2026-11-22
kind: feature
related: [2026-11-22-download-this-area-is-offered-where-a-finer-map-already-covers-the-view.md, 2026-11-07-downloads-as-a-synchronisation-in-cells.md, 2026-11-01-pack-builder-draws-a-polygon-on-the-site.md]
---

# Edit and resize a downloaded area; "Download this area" opens the polygon editor

From the owner (2026-11-22, v0.1.41-alpha): "when you click download this area, you should get the polygon editor imo.
We should also be able to resize earlier pick downloads, the caveat that it might need a full re-download (?) given to
the user."

**Where it stands.**
- "Download this area" opens the size-first card for the **visible rectangle**, fixed to the view
  (`areas/AreaPicker.kt`, `docs/reference/android-app.md`); **Draw an area** on the Offline maps list is the way into the
  polygon editor (`areas/` picker), a separate entrance for what is the same act (choose a shape, then a detail).
- An area's row shows its zooms and bounds and offers Show on map, Allow clearing, Download again, Rename and Remove
  (T221, T234); **no action changes its shape**. A change means Remove and a new download, deleting tiles that a new
  shape would mostly share.

**What is wanted.**
1. **One entrance.** "Download this area" starts with the visible rectangle as the editor's first shape, handles on its
   corners and edges so the owner can drag them, add a vertex, or redraw; the size line and the budget line update as the
   shape moves (the card already does this for a drawn polygon, T153 and T170).
2. **Edit a saved area.** A row action ("Edit shape" or "Resize") opens the same editor on the stored polygon; saving
   fetches what the new shape adds and, per the owner's caveat, **tells the user when a full re-download is needed**.
   Where the shape only grows or shrinks and the detail is unchanged, the tiles already held stay: the new shape's tiles
   minus the held ones are fetched, tiles outside it are released (pins asked again, `TileCache.clearWhere`). A change of
   detail (Medium to Full), of the data kinds or of the pack's routing graph means a re-download of that part, said in
   words and with the size before the owner confirms.

**What touches it.** The tiles are rows keyed by the manifest, "a rectangle polygon through the area download", so a
shape change is a **diff of two manifests**: the cursor (T151) and the chain (T156) already walk a manifest; a second
walk over the removed tiles is new. The owner's cells idea (`2026-11-07-downloads-as-a-synchronisation-in-cells.md`) is
the general form of the diff and would make a resize cheap and exact; without cells the diff is by tile rows. A
**pack** (the owner's US and Colorado maps, "Map only" or with routing) is a different object: its edges are the
build's, and resizing a pack is a rebuild, not an edit, so the first stage is **areas only**, and the editor on a pack
row says it cannot be reshaped (or offers a new area drawn inside it).

**What more we would want to see.**
1. Whether the owner wants resize for a **pack** too (a way to cut a 30 GB pack to a smaller shape on the phone without
   re-downloading): that is a different feature (a pack cannot drop tiles it shares, `pmtiles` is one file) and a
   question of its own.
2. The editor's first frame as the owner would like it for "Download this area": the rectangle already drawn, and a
   "Draw freely" control, or the current card with an "Edit shape" link.
3. A pack row's "Show on map" frame (the owner's screenshot shows the polygons): the stored shape of each is what an
   edit would start from.

2026-11-22, folded in at triage (the owner, 2026-11-22, by multiple choice at triage): a resize or an upgrade (Medium to Full) fetches only the difference (the tiles a new shape adds, the zooms a finer detail adds) and releases what falls outside, using ADR 0060's cell ledger; a resize that re-downloads everything is a defect of this work.

Question: is "Download this area" the polygon editor, started on the visible rectangle, with the present card as its
last step? Recommended: yes, since it is the same act as Draw an area; one editor, two ways in. Resizing a saved area
follows (areas only), with the re-download caveat in words and the size shown before the owner confirms; resizing a
pack is left aside.

Answer:
