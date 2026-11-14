---
date: 2026-11-14
kind: feature
---

# Reproducible release builds: anyone can check a published APK against the source

A release is verified against `SHA256SUMS.txt` and `version.json`, both written by the workflow run that built the
APK (`.github/workflows/release.yml`; the `release` skill's draft check), so the check proves the download is the
build, not that the build is the tag's source. If the same tag built the same bytes anywhere (a pinned toolchain,
no timestamps or build paths in the outputs, the signature compared apart), a second build on the owner's PC or a
stranger's would confirm a release, and the catalogues that rebuild from source and take only reproducible apps
(F-Droid's reproducible builds) open to a GPL app. The owner, 2026-11-14: "Unsure how valuable, but eventually
maybe."

Recommendation (the coordinator): measure before deciding. A chore builds one tag twice (the workflow and a local
build) and compares the unsigned APKs; the size of the difference says whether this is a day or a month. Its value
rises at beta, when people other than the owner install the app.

Waits: the owner, 2026-11-14: "Unsure how valuable, but eventually maybe." No trigger named.
