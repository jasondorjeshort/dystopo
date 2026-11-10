# 0057. One Gradle-using agent at a time

Date: 2026-11-05
Status: accepted

Record: the owner's words during wave 28's launch; the board's record of the wave, `docs/tasks/wave-28.md`.

## Context

`CLAUDE.md` said 5 to 8 implementers at once, and wave 28 launched eight on a 48 GB PC beside the owner's own programs, including another project's Gradle. Within about half an hour seven builds at 3 GB to 4 GB each plus a 2.3 GB Kotlin daemon left 2.1 GB free. The owner, 2026-11-04: "I think you're overwhelming my system with ram use. If in doubt we can take our time and just have one agent at a time." Stopping the builds brought the PC back to 19.5 GB free.

The earlier guards did not hold: `--no-daemon` does not shrink a running build, and a wait for free RAM lets agents pass together and then all grow. The rest of the wave ran one at a time: nine tasks, a sweep and a release in about 13 hours. Free RAM read 17 GB to 22 GB after each merge, except 11.8 GB once with an agent's forgotten emulator up; those are readings between builds, and one verifier's tool step still hit "paging file too small" during its run. No agent reported a rebase conflict in that stretch.

## Decision

- **The default is one Gradle-using agent at a time:** implementer, verifier and merge, then the next task, in an order the coordinator sets. An agent that runs no Gradle and no emulator (a proposal, its critic, a read-only research agent, a cleanup chore) may run alongside.
- **The owner widens it, nobody else.** Going wider on a coordinator's own judgement is not allowed; ask first and say how wide.
- A wave's plan states the order rather than Owns-line parallelism. Owns lines stay: they keep a task's change small and name its crossings.
- A branch that has merged is what the next task rebases on, so a later implementer's brief lists what merged and what it means for its files.
- The emulator cap (`emulators.max`, T190) stays as the machine's backstop, and a coordinator checks free RAM after each merge, since an agent can report a stopped emulator that still runs.

Out of scope: any cap on one process's heap; a build that needs the memory may use it.

## Consequences

A wave takes longer on the clock: about 1.5 hours a task, one after another. It costs no more on the meter: wave 28 read 70 on the 7-day at planning and 81 at the release, under the 86.5 figured. Agents keep context across a stop, so a stop for RAM loses little. An agent ending its turn while a build runs still triggers a harness message to hand back early, so briefs tell agents to wait in the foreground and poll.

Hatchery's wave text carries the same numbers in spirit; this goes back as a carry-back. Revisit if the project moves to a machine with room to spare, on the owner's word.

- Prediction, check at the next wave's close: free RAM stayed above 6 GB throughout, by the readings the coordinator takes after each merge.
  Outcome 2026-11-10 (checked by the triage scan, from wave 30's close): held; the readings after each merge stayed
  between 11.0 and 23 GB, and the guard stopped emulators five times at its limits without losing one.
- Prediction, check at the next wave's close: its cost per task on the 7-day meter is within 0.5 points of the current figure, so running one at a time costs no more.
  Outcome 2026-11-10 (the same scan): failed; wave 30 read 27 to 72 on the 7-day meter for eleven tasks, three
  releases and two critics, about 2.5 a task against the 1.25 figured, and the `wave` skill's figure was revised at
  that close (finding `../../findings/2026-11-10-wave-cost-doubled-and-the-lane-width-does-not-explain-it.md`:
  wave 31's two lanes cost the same per task, so the serial lane is not the cause).
