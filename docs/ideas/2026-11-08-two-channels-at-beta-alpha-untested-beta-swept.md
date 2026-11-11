---
date: 2026-11-08
kind: feature
---

# Two channels once beta exists: alpha is the latest merge untested, beta is the swept release

The owner, 2026-11-08, on a nightly channel: "Once we move to beta, perhaps the alpha would be the latest release
without testing."

Today there is one channel: GitHub Releases prereleases (ADR 0024, an alpha after every green sweep, the in-app
updater reading the latest release's `version.json`). The beta path decided on 2026-10-11 (the board's standing
facts) is Google Play closed testing under the owner's registration with Play App Signing. The owner's shape: when
beta ships through Play, "alpha" stops meaning "swept" and means the latest build from `main`, published without the
sweep for testers who opt in, while beta carries the swept, released build.

## Open

- Where each channel lives: Play's internal or closed track for beta, GitHub prereleases (or Play's internal track)
  for the untested alpha; the updater's switch ("Alpha builds: the latest changes, untested") with ADR 0023's
  data-use rules; nothing hard-coded beyond GitHub (ADR 0059).
- The release workflow publishing an alpha build from `main` after each merge or once a day, tagged by date and
  commit, with `version.json` per channel so a phone on beta never sees an alpha offer; the sweep still gates beta.
- What the alpha build must still pass before publishing: `check` and the privacy scan at least, so an untested
  build is never an unsafe one; the release notes generated from the changelog's Unreleased section.
- Revise ADR 0024 when this is built (the policy says one channel).

Question: Should this wait for the Play registration and the first beta (a `Waits:` line of yours naming that
trigger), or should the untested alpha channel be built now on GitHub prereleases beside today's swept alphas?
Recommended: wait; until beta exists the swept alpha is the only tested build, and a second GitHub channel now would
need the updater's channel switch and ADR 0024's revision twice, once now and again when Play arrives.
Answer: wait for the Play registration and the first beta, as recommended (owner, 2026-11-11).
