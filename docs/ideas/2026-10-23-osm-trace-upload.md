---
date: 2026-10-23
kind: feature
related: [2026-10-13-external-integrations.md]
---

# Upload a recorded track to OpenStreetMap as a GPS trace

Split from `2026-10-13-external-integrations.md` on the owner's answer (2026-10-23): the first outside
service the app talks to, on its own because it is the only one whose data is a public contribution
rather than a backup. One press on a recorded track sends it to the project's public GPS traces, so
mappers can fix the trails the app draws. The track model, GPX 1.1 export and the tracks bar are the
anchor (`shared/src/commonMain/kotlin/io/github/jasondorjeshort/dystopo/gpx/`, `TrackExport`,
`androidApp/.../tracking/TrackControls.kt`, `TrackRoutes.kt`); the app already talks to one outside
HTTPS service (`update/Updater.kt`) and names it in code only.

## Open

- Track data can reveal where a user lives: nothing uploads without a visible per-upload
  confirmation that shows what leaves the phone; the ends of a track near a home are the risk.
- The service's visibility values and which one is the default; what the user is told they mean.
- An account and a client registration; the registration's secret never enters the repository.
- Terms of use and the contributor terms the user agrees to by uploading.
- The one exception to the no-other-product-names rule in user-visible text (the owner decides the
  strings, ADR 0031).
- Agent-testable: a debug route that runs the whole upload against a mock service.

Plan: proposal 0042 (`docs/design/proposals/0042-osm-trace-upload.md`, T134) decides the API use, the
confirmation, the trimming, the visibility default, where the client registration lives and the mock;
once promoted to ADR 0042, one task builds it from that record and deletes this file.
