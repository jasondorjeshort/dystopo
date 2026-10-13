---
date: 2026-10-10
status: resolved
area: process / parallel agents on one machine
promoted_to: CLAUDE.md (Working in parallel), docs/reference/windows-and-shells.md
---

# Stopping processes by a command-line substring killed every agent's emulator and the live server

## Question

An agent cleaning up after itself stopped "its" processes by matching a command-line substring
(`devtool`, `demo-server.py`, `dystopo-avd`). Why did other agents' runs fail at the same moment?

## What was tried

Several agents ran emulators and servers on one machine at once; the coordinator's live server ran
from the main checkout. The substring matched all of them.

## Result

Every other agent's emulator and the coordinator's live server were killed at once. Rule since
2026-10-10 (commit `b32a028`): record the PIDs of the servers and emulators you start and stop exactly
those, by PID or by emulator serial (`adb -s emulator-NNNN emu kill`); never match by name or
substring. `tools/sweep.ps1` stops its servers by PID and refuses the ports and console others use.
