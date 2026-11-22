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
