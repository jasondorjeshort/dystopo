---
date: 2026-11-14
kind: chore
related: [2026-11-14-a-new-sweep-part-runs-in-sweep-order-before-its-merge.md, 2026-11-10-the-sweep-retries-a-part-once-on-a-machine-failure.md]
---

# The sweep reruns only the parts whose inputs changed

Wave 32's first closing sweep was red on two parts (`startup`, `a11y`); after T280 the whole sweep ran again, every
green part with it. `tools/sweep.ps1` takes `-Only` and `-Skip`, but a release wants one sweep that is green as a
whole. If each part's entry in `summary.json` recorded what it ran against (the APK's hash, the hashes of its
scripts and inputs, the server's build), a second run could carry a green part over when nothing it read has
changed, and say so on the sheet, as a build system does. The owner, 2026-11-14: "Okay, doesn't seem essential
though".

Recommendation (the coordinator): not now. T280 changed the app, so nearly every device part's input had changed
and little would have been carried over that day; the saving is real only when a fix touches a script or one
part's data. The cheaper answer to the same loss is the related idea of this date (a new part proves itself before
its merge). Worth building if a closing sweep is run again twice in one wave for a part's own script.

Waits: the coordinator, 2026-11-14, by the owner's leave ("The other decisions I leave to you, or get an
independent review to confirm"; a critic confirmed). Trigger: a closing sweep run again twice in one wave for a
part's own script. On the day (T280) the APK had changed, so only the parts that use no device (the tests, the
live server, the sync simulator) could have been carried over.
