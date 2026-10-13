---
date: 2026-10-13
kind: question
status: new
pipeline: none
---

# Doctrine: while in alpha, changing the interface is fine

Owner, 2026-10-13: changing the interface is fine while we are in alpha (the recenter button, the
scale bar and the settings tabs are examples). Do not hold back a better layout to keep the old
one stable.

Tension with the written rule: `CLAUDE.md` (Decisions) says what humans see and touch needs the
owner's sign-off, so agents flag it instead of guessing.

## Open

- Does the doctrine mean agents may change layouts and flows without asking, keeping sign-off for
  wording and visual identity only? Or does it mean sign-off is quick and rarely refused?
- If it is a rule change, `CLAUDE.md` and the `brief` skill change in the same edit, and it
  expires when the app leaves alpha (the publishing ADR 0024 names the milestone).
- Settings and stored data still migrate (the track database rule), so interface freedom does not
  cover data formats.
