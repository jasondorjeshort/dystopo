---
date: 2026-11-28
kind: chore
related: [2026-11-14-the-sweep-reruns-only-parts-whose-inputs-changed.md, 2026-11-10-device-parts-of-the-sweep-on-a-cloud-runner.md, 2026-11-28-the-install-from-the-draft-as-a-sweep-run.md, 2026-11-24-the-closing-sweep-finds-its-own-prev.md, 2026-11-24-the-sweep-emulator-drops-mid-part.md, 2026-11-17-a-ledger-of-what-each-sweep-part-leaves-behind.md]
---

# Releases are getting very slow

Source: the owner, answering a brainstorm offer after v0.1.45-alpha was published.

The owner, 2026-11-28: "We probably do need to do some work to improve the efficiency of our release work soon as
these releases are getting very slow."

Where the time went for v0.1.45-alpha (wave 49; the figures are in the log entries of 2026-11-27 and 2026-11-28):

- From the last planned task's merge (2026-11-27 21:21 UTC) to the publication (2026-11-28 16:18 UTC): 19 hours.
- Three whole closing sweeps, 8,698 s, 8,167 s and 7,753 s (two and a quarter hours each, alone on the PC), and two
  counted reruns of the device tour at about 22 minutes each. A first start of the third sweep was lost after 12
  minutes when one part lost its browser.
- Between the sweeps, four fix tasks, each an implementer and a verifier: two for defects the wave's own
  verification could have caught (start-up stages over their ceilings, which no task had judged; a part verified
  alone that broke a part running after it), one for three assertions, one for the tour's tool.
- Inside one sweep the long parts are `a11y` (1,287 s), `device-tour` (1,233 s), `features` (1,078 s),
  `features-sky` (778 s), `two-maps` (763 s) and `update` (475 s): 5,600 of 7,750 s, one after another on one
  emulator.
- The release itself, from the tag to the publication: 32 minutes (the build 4, the draft's checks, the install
  from the draft about 30 with a hand-built setup).

So the cost is less the release's own steps than a closing sweep that is the first place several kinds of defect
show, run whole after every fix. The related files hold measures already written down; the resting one
(`2026-11-14-the-sweep-reruns-only-parts-whose-inputs-changed.md`) waits on a trigger the owner may now want to
lift.

## Open

- What "slow" should become: a target for the hours from the last merge to a release, and for one closing sweep.
- Which defects must be caught before the closing sweep (by a task's verifier) and which the sweep may find.
