## Debug interface is part of every feature

Agents cannot click a GUI. Every feature ships with an agent-usable way to exercise it: a CLI
entry point, an HTTP endpoint, a REPL command, or a scripted harness. "Done" means an agent can
drive the feature, observe its state, and reproduce a bug from a terminal. The debug interface is
designed up front in `docs/design/05-debug-interface.md` (`debug-interface` skill), not bolted on.
