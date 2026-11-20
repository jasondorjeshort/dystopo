---
date: 2026-11-20
kind: chore
related: [docs/findings/2026-11-05-emulator-private-bytes-grow-per-cold-launch.md, docs/ideas/2026-11-20-a-failing-hook-says-why.md]
---

# The sweep stops below a memory floor

Wave 40's full sweep (`out/sweep-20261120-0131`) ran its emulator to a peak of 5.3 GB of private bytes; soon after, on 2026-11-20 at 03:26, every tool call on the machine began failing until a reboot, leaving a rerun sweep and its emulator running unattended with no agent able to stop them. The sweep's guard already reboots the emulator over 6 GB (`tools/sweep.ps1`), but it watches the emulator, not the machine.

Wanted: before each device part, `tools/sweep.ps1` reads the machine's free physical memory and commit headroom; below a floor (a named constant, set from a measurement of this machine) it stops the part cleanly, stops its own emulator by serial, writes the reading and a named reason to `steps.tsv` and `summary.json`, and exits red, so a sweep never leaves the machine too short to run the tools that would clean it up. The reading also goes into each part's line, beside the emulator's own figures.

The owner, 2026-11-20: "Record them all. We'll triage and sanity check later."

Plan: in `tools/sweep.ps1`, before each device part and at each 2 s slice where the guard already reads the
emulator, read the machine's free physical memory and commit headroom (`Win32_OperatingSystem` FreePhysicalMemory,
and TotalVirtualMemorySize minus the commit charge); below a floor held in two named parameters (set in the pass from
a measurement: the readings across one full sweep plus a margin that leaves room for node, git and an agent's shell),
stop the part, stop the sweep's own emulator by its serial, write `memoryFloor` with the readings to `steps.tsv` and
`summary.json` `failed`, and exit red. Each part's line carries the two readings beside the emulator's figures.
Verify: a run with the floor set above the current free memory stops cleanly at the first part, its emulator gone by
serial; one normal `-Only startup` run shows the readings. Out of scope: the emulator's own guard limits.
