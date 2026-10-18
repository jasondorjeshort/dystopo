## Git

- Branch: the `Branch:` line of `CLAUDE.md`. Commit small and often, as work lands. Below HOLD the `runner` commits from a
  brief (`commit` skill); `coder` and `tester` leave a clean, described working tree for it.
  Wrap-up commits (`docs/HANDOFF.md`, WIP) are made by the main session at any usage level.
- Amend only to fold a review fix, a forgotten file or a message fix into the unpushed commit it
  belongs to. Never amend a pushed commit.
- The `pusher` pushes, launched fresh for each push with the user's request quoted in the brief
  (a resumed pusher's retry gets blocked; the `commit` skill says why); the main session may push
  when the user asked for the push in so many words. Never force-push. A push that is blocked
  anyway goes back to the user to run `git push` themselves; never route around the block.
- The `git-guard` hook denies destructive git for everyone, agents included, in Bash and
  PowerShell alike. Which agents may commit and push is policy in `.claude/hooks/policy.json`
  (the base: `git commit` only from the `runner`, `git push` only from the `pusher`, the main
  session exempt from both); a project with its own branch or push model edits the policy file,
  never the hook. The denied list and each hook's fail mode: ADR 0006 and the hook specs.
- Every push is scanned commit by commit for personal data and runs on a line of its own (ADR 0018).
- `privacy.allow`, the never list and the user allow list are the owner's: edited from the main
  session, after asking the user; no brief, agent or hook grant edits them.
- Commit messages end with `Co-Authored-By: <model that did the work> <noreply@anthropic.com>`,
  for example `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
