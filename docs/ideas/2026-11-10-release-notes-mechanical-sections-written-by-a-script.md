---
date: 2026-11-10
kind: chore
---

# Release notes: the mechanical sections written by a script, the prose by hand

Each alpha's notes (`release` skill, step 4) are written by hand from the CHANGELOG, and most of the text is the
same every time: the install steps, the Files list, the checksums copied from the draft's `SHA256SUMS.txt`, the
certificate line, the sheet's link with its count from the sweep's `summary.json`. `release.yml`'s "Release notes"
step already writes a template with the Files list. Two alphas on 2026-11-10 each spent most of an hour of the
coordinator's time on the mechanical parts and on scanning them. The owner, 2026-11-10: "Sure."

- `tools/release-notes.py <tag> --sweep <folder> --changelog CHANGELOG.md` [no-check: proposed]: reads the draft's
  assets and `SHA256SUMS.txt` through `gh release view --json assets` and the downloaded checksum file, the sweep's
  `changes.count` and `paired`, the CHANGELOG section's items, and prints the notes with "What changed" and "Known
  limits" as marked blocks for the coordinator to write; the rest verbatim as today's notes read.
- The script runs `PrivacyCheck` on its output; the skill's step 4 becomes "run the script, write the two blocks,
  scan again".

## Open

- The "Known limits" block carries the previous notes' lines forward with their "Still from" prefix, for the
  coordinator to strike what landed.
