# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-10-21, at commit 91501745, by main session
Resume: this file, `docs/reference/machines.md`, the `wave` skill, `docs/TASKS.md` › Wave 17 (T120);
the owner's identity rule in `.claude/hooks/policy.json` (`$comment`, privacy); the `handoff` skill.

## Now

- Triage is done (eight `Question:` blocks awaiting the owner's `Answer:`, one `Plan:`), but its ten
  commits are not on `main`: the push scan refused them (placeholder identity, `Claude-Session:`
  trailers). They are on the local branch `triage-saved` (tip 533f6dd8), to cherry-pick after T120.
- T120 (the scan accepts the trailer and GitHub's merge committer) is in flight on `task/T120`, no PR
  yet. `main` carries the owner's real identity in `policy.json` since 91501745; pushes of `main`
  under it pass, but nothing with a trailer can be pushed until T120 merges.
- 19 commits on `origin/main` (2026-10-17 and 10-18) and the `v0.1.9-alpha` tag carry the placeholder
  `owner@dystopo.invalid`; the owner chose to rewrite them (2026-10-21). The script is
  `<scratchpad>/rewrite-identity.sh` of this session; the owner runs it (`git-guard` refuses rewrites
  from agents); it rewrites the identity from 3a7e843b on, re-tags, force-pushes with a lease.

## Next

1. Verify T120's PR (Sonnet-class), merge by the `wave` skill's sequence.
2. Owner runs the rewrite script after `git pull`; report the remote's view, not the command's.
3. `git cherry-pick dd482159..triage-saved` onto `main`, push with `push-verified.sh`; then delete
   `triage-saved` and `refs/original/*`.
4. Carry T120 back to Hatchery (ADR 0018 item 10, the scan, its `commit` skill) through Hatchery's
   own commit skill (only its `runner` and `pusher` agents commit and push there). Hatchery's PR #1
   (T117's carry-back) shows CLOSED, not merged, on 2026-10-18: check whether it landed another way.
5. Plan the rest of wave 17 from the wave 16 Status lines (hooks: `git rebase --exec` uninspected;
   the nav bar help icons; route-to follow-ups; the ownership-map `Plan:` idea of 2026-10-19).

## Waiting on user

- The eight `Answer:` lines in `docs/ideas/`.
- The repository is private: no phone's updater sees v0.1.8/v0.1.9-alpha and Actions does not run.
- Real-phone checks of v0.1.9-alpha; Google developer registration; MapLibre's compass (T100); T104's
  palette and captions; the wave 14 to 16 strings and help texts (ADR 0031); the 5 s timeout grace.

## Open findings

- none
