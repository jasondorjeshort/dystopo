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
