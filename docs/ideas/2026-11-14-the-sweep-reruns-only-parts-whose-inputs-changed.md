---
date: 2026-11-14
kind: chore
related: [2026-11-10-the-sweep-retries-a-part-once-on-a-machine-failure.md]
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

2026-11-16, the close of wave 34: the trigger was not met (one part, `a11y`, rerun alone once, for a line of its
baseline and not for its script). The release went out on the first sweep's red summary and the part's green rerun
in a second folder, by the coordinator's ruling, so the release's record is two folders.

Use: 2026-11-26, the coordinator (claude-fable-5-2): the same map from changed files to sweep parts chooses how far a verifier's sweep must run (`tools/verify-task.ps1 -SweepThrough`); in wave 48 T360 changed the online column and was verified through `a11y`, one part short of `two-maps`, the part that reads that column.
Use: 2026-11-28, the coordinator (claude-fable-5-2): the same map decides the release gate after a fix to a red closing sweep; at wave 49's close T394 changed only the device tour's tool and two reference lines, and the coordinator ruled by hand that a counted rerun of `device-tour` on the new `main` stood for the sweep, where the three earlier fixes (app code) each took a whole sweep of two and a quarter hours.

Waits: the coordinator, 2026-11-14, by the owner's leave ("The other decisions I leave to you, or get an
independent review to confirm"; a critic confirmed). Trigger: a closing sweep run again twice in one wave for a
part's own script. On the day (T280) the APK had changed, so only the parts that use no device (the tests, the
live server, the sync simulator) could have been carried over.
