---
date: 2026-11-10
status: resolved
area: shared / elevation sources, the range-block cache
promoted_to: ../reference/code-layout.md (the range cache's validator, T269)
---

# The range-block cache keeps blocks across a source's republish, so a cell decodes as garbage

## Question

The mid-wave 31 sweep (`out/sweep-20261110-0254`) failed one of 66 tour renders, `sea-tac-z12`, with
`IllegalArgumentException: LZW stream doesn't start with a clear code`, and failed it again on a rerun.
The sweep agent guessed a corrupt cached TIFF. Was it the cache, a bad download, or a decoder gap on a
tile this PC had never read (the `sea-tac` area was added in wave 29 and this was its first tour here)?

## What was tried

A Sonnet chore reproduced it with `render --area sea-tac --zoom 12` (top frames `tiff.Lzw.decode`
(`Lzw.kt:77`), `GeoTiff.readTile` (`GeoTiff.kt:90`), `RemoteTiff.decode` (`RemoteTiff.kt:125`),
`ThreeDepSource.fetch` (`ThreeDepSource.kt:71`)), found the source and the cache layout, fetched the
same ranges again from the server into a clean directory and compared hashes, then a HEAD request per
cached cell (983 cells, all answered) against the mtime of each cell's oldest cached block.

## Result

Not a decoder gap: a stale cache. `ThreeDepSource` reads USGS 3DEP 1/3" as a Cloud-Optimized GeoTIFF over
HTTP range requests (`.../StagedProducts/Elevation/13/TIFF/current/<cell>/USGS_13_<cell>.tif`), and
`CachedRangeSource` (`shared/.../sources/elevation/RemoteTiff.kt`) keeps each range as
`cache/3dep13/<cell>/<offset>-<length>.bin`, read back by name alone: no ETag, no Last-Modified, no
whole-file check. USGS republished `n48w123` on 2026-10-21 (Content-Length 415,449,258). The cell's
49 cached blocks were fetched on 2026-10-09 and 2026-10-10, the header and offset table among them; two
blocks fetched during the sweep (2026-11-10) matched the new file byte for byte, every older block
differed from a fresh download of the same range. The old offsets pointed into the new bytes, so the
LZW decoder read a tile that did not start with a clear code. A cell whose blocks are all old stays
self-consistent and renders the old data without a word; the error appears only once a new block is
fetched into an old cell, which is why 65 renders passed.

The repair: the cell's directory deleted (the view refetched 3 blocks, 1.75 MB, and rendered), and the
55 other cells whose oldest block predates the server's Last-Modified deleted too (republished
2026-10-20 to 2026-10-30; every cached copy from 2026-10-10 but one from 2026-10-09); the tour part
rerun 66 of 66 in 12.1 s. The phone reads the same COGs through the same class (`RemoteTiff.open`,
`docs/ARCHITECTURE.md`), so a phone's direct-source cache has the same flaw.

## Next

A validator per cell: keep the first response's ETag or Last-Modified beside the blocks (one small file
in the cell's directory), check it on open (a HEAD, or a conditional GET of the header block, once per
process and at most once a day) and drop the whole cell on a mismatch; the same for the other COG
sources (`copernicus30`, `swissalti3d`, `kartverket-dtm`, `bev-als-dtm`, `hrdem`, `linz-dem`). Until
then a deterministic LZW or Deflate error in a cached cell means: delete that cell's directory. A wave 32
task (`docs/TASKS.md` › Later).

Resolved by T269 (2026-11-10): `validator.json` per cell (the first response's ETag, Last-Modified and length),
`If-Match` on every range GET, a whole-cell drop on a 412 or another ETag, a daily background HEAD, a one-time check
of cells cached before it (dropped when Last-Modified is newer than the oldest block), and a recheck on a decode error,
which replaces the manual deletion above. Every host answered a wrong `If-Match` with 412.
