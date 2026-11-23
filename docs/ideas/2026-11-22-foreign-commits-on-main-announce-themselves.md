---
date: 2026-11-22
kind: chore
---

# Commits on main from another session announce themselves to the coordinator

Status: **untriaged** (an idea of the coordinator's own from wave 46's work, offered when the owner asked "Any ideas out of this wave? Moonshots welcome." and recorded on the owner's word, 2026-11-22: "Record them all; we'll triage and think through them later."). The anchors are files wave 46 wrote or read; nothing else here was checked against the code or measured.

On 2026-11-22 the owner's side session pushed three commits to `main` while the coordinator's session ran wave 46: the owner's reports as ideas, fourteen ideas, and a board entry for T337 marked the owner's highest priority. The coordinator learned of it only because an implementer's report named an unfamiliar commit as its rebase base; an hour could have passed with the owner's highest priority sitting unread on the board.

What it would take: `tools/push-verified.sh` (run at every push) and the merge sequence's fetch print, before anything else, every commit on `origin/main` that the local `main` did not have, with its subject and its `Claude-Session:` trailer when that differs from this session's, and say when one of them touches `docs/TASKS.md` or `docs/HANDOFF.md`; a hook could do the same at the start of each coordinator turn.

## Open

- Two sessions writing the board at once will conflict sooner or later; whether the side session should write only ideas and leave the board to the coordinator, with a "for the coordinator" file it is told to read.
- A notice is not consent: a board entry from another session is still read against the owner's words before it is acted on (the T337 entry quoted them).

Plan: [no-check: the new script is this pass's to create] Owns new `tools/main-news.sh`, `tools/push-verified.sh`, `.claude/skills/wave/SKILL.md` (merge sequence, its
fetch step only). `main-news.sh [base]` fetches origin and prints every commit in `<base>..origin/main` (default
base: local `main`): short SHA, subject, its `Claude-Session:` trailer, and `BOARD` or `HANDOFF` when it touches
`docs/TASKS.md` or `docs/HANDOFF.md`; a last line `main-news: N new (M touch the board or handoff)`.
`push-verified.sh` calls it before `git push` when the branch is `main` and stops with exit 3 when it lists any
commit (the push would be non-fast-forward anyway; now it says what came in). The wave skill's merge sequence runs it
after its fetch. Verify: in a scratch clone pair, a commit pushed from one is listed by the other with its trailer and
tag; `push-verified.sh` on a task branch is unchanged. Out of scope: a per-turn hook, and the side session's
board rules (both under Open; the latter is the owner's).
