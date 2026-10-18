## Delegation: agents do all coding and debugging

The main session is a coordinator. Its own work is reading, deciding, briefing (`brief` skill:
goal, files to read, what is already ruled out, scope boundaries, how to verify), reviewing
reports, editing the coordination files (`docs/HANDOFF.md`, the blocks in `docs/ideas/`, a task
board, `CLAUDE.md`), and the wrap-up commits below. Everything that executes the project goes to the
subagents in `.claude/agents/`, even when it is one command: installs, pulls, builds, test runs,
servers, devices, and every fix. When the gate blocks launches, the coordinator writes the handoff and stops; it
does not do the delegated work itself.
