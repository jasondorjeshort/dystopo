Replaces `.claude/agreement/git.md` for this project: `CLAUDE.md` › Working in parallel and Rules
(each implementer commits and pushes its own `task/*` branch and reports; no pull requests, ADR 0043:
the coordinator merges a verified branch, and its own doc edits go straight to `main`; `git-guard`
enforces `.claude/hooks/policy.json`; never `--no-verify`; a subagent may pull, merge or rebase `main`
but not commit there, ADR 0036; pushes of `main` are verified from the remote). Decided in ADR 0020,
ADR 0035, ADR 0036 and ADR 0043.
