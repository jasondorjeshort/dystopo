# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-10-23, at commit f78d1f0d, by main session
Resume: this file, `docs/reference/machines.md`, the `wave` skill, `docs/TASKS.md` › Wave 18 (its wave
paragraph holds ownership, ports, the replay recipe and the benchmark questions); the `handoff` skill.

## Now

- Wave 18 launched 2026-10-23 on the owner's account after the critic pass (2 blocking, 6 serious,
  7 minor, all taken into the plan): nine implementers at once, T126 to T131 (code and measurement)
  and T132 to T134 (proposals 0040 to 0042, each ending at `under review`). No PR yet.
- The coordinator's live viewer (8765) and standalone server (8800) run from the main checkout over
  the old Rainier packs; the profile 2 rebuild failed (`areas.json`'s box ends west of Sunrise).
- Wave 17 leftovers cleared except the locked `dystopo-wt/T122` folder (`gradlew --stop` is the owner's).

## Next

1. As each T126 to T131 PR arrives: a Sonnet verifier (`brief` skill, `verifier.md`; ports 8790 to
   8799, consoles 5660 to 5670), then the merge sequence of the `wave` skill.
2. As each proposal PR arrives: a critic (most capable model, `proposal` skill's `brief-critic.md`),
   its review sent back to the same agent, then the `adr` skill or the open positions below.
3. After T129 merges: a chore sends the three pending carry-backs of ADR 0038 (`carry-back` skill).
4. After the wave: a chore rebuilds the Rainier outdoors pack on profile 2 over the old pack's
   bounds; the post-wave sweep (`sweep` skill); then v0.1.11-alpha (`release` skill, ADR 0024).
5. Hatchery: ADR 0021's follow-ups T-a to T-g from a Hatchery-rooted session; then the second base
   update and a `CLAUDE.md` rules pass.

## Waiting on user

- The S Pen question; a real-phone run of T131's battery script once it merges; the `RELEASE_*`
  secrets; the T122 folder (`gradlew --stop`), `git branch -D triage-saved` and the `refs/original/*`
  cleanup; whether the two older Claude-authored commits get rewritten; T122's strings (nav card
  1.5 cap, three help texts); the wave 18 strings and the proposals' positions as their PRs land.
- Real-phone checks; Google developer registration; MapLibre's compass (T100); T104's palette and
  captions; the wave 14 to 17 strings and help texts (ADR 0031); the 5 s timeout grace.

## Open findings

- `docs/findings/2026-10-21-direct-view-out-of-memory-after-a-tour.md` (T127, in flight)
