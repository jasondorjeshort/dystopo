---
date: 2026-11-22
kind: chore
related: [2026-11-11-trim-the-default-settings-before-beta.md, 2026-11-22-a-zoom-label-by-the-scale-bar-as-an-option.md, 2026-11-08-two-channels-at-beta-alpha-untested-beta-swept.md]
---

# A ledger of settings and aids that are on for now and off (or changed) at beta

Status: **untriaged and not sanity-checked** (the coordinator's own, from a scan of all the ideas, 2026-11-22).

The owner's zoom label is "on for now" and "off by default in a post-beta release" (`2026-11-22-a-zoom-label-by-the-scale-bar-as-an-option.md`);
`2026-11-11-trim-the-default-settings-before-beta.md` is the same kind of decision for the settings as a whole, and the two
channels idea (`2026-11-08`) says when beta starts. Today each "off at beta" decision lives in its own idea or task, so the
flip is easy to forget. Idea: one list (a file in `docs/reference/` or a section of the release skill) of every alpha
aid with its default now, its default at beta and the owner's words, read by the `release` skill's first beta step, and a
test that fails when a listed default differs from the constant it names, so the flip is a one-line change that the test
then asks for.

**What exists already (to be checked):** the trim-the-defaults idea (a one-off audit, not a standing list); the debug
routes that report each setting's value (`GET /settings`); no ledger.

Plan: one table, `docs/reference/beta-defaults.md` (with its line in `docs/reference/README.md`): per row the setting or aid, the
constant it names (`File.kt` and the constant), its default now, its default at beta, and the owner's words with their date.
Seed it only from what the owner has said flips (the zoom label's idea; any row of the trim-the-defaults idea that carries an
answer); nothing guessed. A JVM test in `androidApp/src/test` (`BetaDefaultsTest`) parses the table and fails when a row's
"now" differs from the constant, so a change to either alone fails. The `release` skill's first beta step names the table.
Verify: the test passes, and fails after editing one row's value (say so in the report). Out of scope: flipping any default,
the trim audit itself, the two-channels decision.
