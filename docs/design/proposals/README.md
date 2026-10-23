# Proposals and critic reviews

Proposals for major choices before they become ADRs, and the critic's review of each, numbered to
match the ADR (`proposal` skill; ADRs in `../decisions/`). A review of an ADR that was recorded
without a critic pass also lives here.

| File | What |
|---|---|
| `0027-critic-review.md` | Critic review of ADR 0027 (area manifest, pinned corridors), after the build: one blocking defect (version changes delete pinned tiles), one serious (corridor size and mode), two minor. |
| `0028-critic-review.md` | Critic review of ADR 0028 (long-distance drive overlay), after the build: two serious (unbounded fallback, disjoint local packs never go remote), two minor. |
| `0040-stops-and-reroute.md` | Proposal (T132, promoted to ADR 0040): a plan of stops as a keyed `stops` field beside the frozen route, a mode per leg, automatic replanning to the next stop for plans only, passed and skipped stops, the Go and stale-fix rules, route to from search; bushwhacking needs no path version. Prior art from the 2026-10-23 survey; revised on the critic's review. |
| `0040-critic-review.md` | Critic review of proposal 0040, before the build: 0 blocking, 4 serious (where a replan's geometry lives, the path-writer invariant made structural, asynchronous replanning from the start, lifts for the rate limit and the cap), 8 minor; Option A upheld. |
| `0042-osm-trace-upload.md` | Proposal 0042 (T134): uploading a recorded track to OpenStreetMap as a GPS trace: a direct API client with a browser sign-in (OAuth 2 PKCE, a public client with no secret, the app's own scheme for the redirect), a per-upload sheet that shows exactly what leaves, the ends cut within 500 m by default, trackable as the default visibility, the exception strings for the owner, a mock and routes. Revised on the critic's 14 objections; promoted to ADR 0042 (Status proposed: the owner confirms the account, the sheet, the default and the exception strings). |
| `0042-critic-review.md` | Critic review of proposal 0042: 0 blocking, 5 serious (the code's return path, "anonymous", the trace id's format, the fallback, the agent path), 9 minor; every claim checked against primary sources; A upheld with revisions. |
