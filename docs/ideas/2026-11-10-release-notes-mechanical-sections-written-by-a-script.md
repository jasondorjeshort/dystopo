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

2026-11-16, v0.1.31-alpha, written by hand again: the checksums and the Files list were copied from the draft, and
the sheet's counts did not come from the sweep's `summary.json`: the sheet was rebuilt without six pairs, so the
counts are the rebuilt `changes.json`'s, and the script needs `--changes <file>` [no-check: proposed] beside
`--sweep`. The one sentence struck before publishing was in the hand-written prose (a claim nobody had checked),
which the script leaves to the coordinator either way.

Plan: one no-Gradle pass. `tools/release-notes.py` [no-check: new file] as the body gives its arguments, with a
test from a fixture draft: the assets and the checksums read through `gh release view --json assets` and the
downloaded `SHA256SUMS.txt`, the sheet's count from the sweep's `summary.json`, the CHANGELOG section's items, the
previous notes' "Known limits" carried forward with their "Still from" prefix; it prints the notes with "What
changed" and "Known limits" as two marked blocks and everything else as today's notes read, runs `PrivacyCheck` on
its output and exits non-zero on a hit. The `release` skill's step 4 becomes: run the script, write the two blocks,
scan again. Done when: the test green; the script's output for v0.1.29-alpha differs from the published notes only
inside the two blocks (the diff in the report). Out of scope: the template step of the release workflow.
