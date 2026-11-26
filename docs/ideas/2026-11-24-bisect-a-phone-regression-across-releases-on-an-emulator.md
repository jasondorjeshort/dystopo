---
date: 2026-11-24
kind: chore
related: [2026-11-07-search-and-routes-find-nothing-outside-a-pack.md]
---

# Bisect a phone regression across releases on an emulator

Source: coordinator (claude-opus-5-5), after the ideas triage of 2026-11-24; offered and accepted by the owner; not sanity-checked.

The owner saw the Horizon view's peaks around v0.1.40 or v0.1.41 and not on v0.1.43. The idea: a script that installs
published releases in turn on an emulator with the two-maps fixture (`tools/two-maps-fixture.py`) and reports the
first release where a view's read goes wrong.

Research (read-only agent, 2026-11-24): published releases have no debug routes, so the script would drive them by
taps (`tools/android-ui.py`) and read screenshots or logcat; the two-maps fixture passed on T340 and T352, so an
emulator reproduces the fault only if the owner's own maps are not its cause. The owner installing v0.1.40 and v0.1.41
on the phone with the same maps is cheaper and tests the real data. Wake it when the phone capture points at
something an emulator could show.

Question: build the release bisect now, or leave it until a phone capture shows a fault an emulator can reproduce?
Options: (1) rest it until the capture script's first real capture points at an emulator-reproducible fault
(recommended: the research found the owner's own phone with old releases cheaper and truer, and the two-maps fixture
already passes on `main`); (2) build it now as one pass (`tools/` script driving each published release by taps on the
two-maps fixture); (3) drop it.

Answer:
