---
verified: 2026-10-14
source: observed on this machine; the wave 12 and 13 paragraphs in docs/TASKS.md
---

# Machines, accounts, paths and ports

The development machine is one Windows PC with two user accounts that have both run the coordinator
(`Dorje`, the owner's, for waves 1 to 12 first half and from wave 13; `second` for the second half of
wave 12). Briefs name the account's paths explicitly; when this file and a brief disagree, the brief
wins and this file gets fixed.

## The owner's account (`Dorje`, coordinator from wave 13, 2026-10-13)

| What | Where |
|---|---|
| Main checkout | `C:\Users\Dorje\Documents\src\dystopo` (named `android-app` until 2026-10-13; never edited by a task agent) |
| Task worktrees | `C:\Users\Dorje\Documents\src\dystopo-wt\<task-id>`: `git -C C:\Users\Dorje\Documents\src\dystopo worktree add C:\Users\Dorje\Documents\src\dystopo-wt\<task-id> -b task/<task-id> origin/main`, then copy `local.properties` from the main checkout |
| Shared download cache | `DYSTOPO_CACHE=C:\Users\Dorje\Documents\src\dystopo\cache` in every shell |
| JDK 21 | `JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot` (Git Bash: `/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot`); the git hooks need it too |
| Android SDK | `C:\Users\Dorje\Android\sdk` (named in `local.properties`) |
| AVDs | `E:\dystopo-avd` (`-AvdHome E:\dystopo-avd`, shared with the other account; each AVD about 6 GB; remove with `-Stop -Delete`) |
| Node | 22 (`node .claude/hooks/test-hooks.js`) |
| Usage files | `~/.claude/usage-state.json`, written by the project status line (`.claude/statusline.js`) and read by the usage gate; `~/.claude/usage-gate-override.json` when the owner lifts the gate. The account-wide status line (`~/.claude/usage-statusline.ps1`, writing `~/.claude/usage.json`) still runs in other projects; nothing here reads that file |
| Hatchery base clone | `C:\Users\Dorje\Documents\src\hatchery` (read it; only its runner commits there) |
| Signing key | `tools\release.keystore` and `tools\release.properties` in this main checkout, the other account's (below), and the `RELEASE_*` Actions secrets (`.claude/skills/release/SKILL.md`) |
| Private terms | `devtool.local.json` in this main checkout; the privacy hooks read it from the main checkout even in worktrees |
| Coordinator's servers | live viewer on 8765 (`serve --live`, Rainier packs in `out/`); PIDs in `out/coordinator-pids.txt` (`live-8765 <pid>`) |

## The other account (`second`, wave 12 second half and wave 13 second half)

| What | Where |
|---|---|
| Main checkout | `~\Documents\src\Dystopo` (never edited by a task agent) |
| Task worktrees | `~\Documents\src\dystopo-wt\<task-id>`: `git -C ~\Documents\src\Dystopo worktree add ~\Documents\src\dystopo-wt\<task-id> -b task/<task-id> origin/main`, then copy `local.properties` from the main checkout |
| Shared download cache | `DYSTOPO_CACHE=~\Documents\src\Dystopo\cache` in every shell |
| JDK 21 | `JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot` (Git Bash: `/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot`); the git hooks need it too |
| Android SDK | `~\Android\sdk` (named in `local.properties`) |
| AVDs | `E:\dystopo-avd` (`-AvdHome E:\dystopo-avd`; each AVD about 6 GB; remove with `-Stop -Delete`) |
| Node | 22 (`node .claude/hooks/test-hooks.js`) |
| Hatchery base clone | `~\Documents\src\hatchery` (read it; only its runner commits there) |
| Signing key | `tools\release.keystore` and `tools\release.properties` in this main checkout (copied by the owner 2026-10-14; gitignored); worktrees do not see them, so the sweep's `upgrade` and `update` parts run from the main checkout |
| Private terms | none: there is no `devtool.local.json` on this account, so the privacy hooks have no private terms to check; use only `areas.json` names and public places in anything written down |
| Coordinator's servers | live viewer on 8765 (`serve --live`, Rainier packs in `out/`), standalone server on 8800 (no token, binds the LAN address); PIDs in `out/coordinator-pids.txt` (`live-8765 <pid>`, `server-8800 <pid>`) |

Neither account can read the other's folders. Shared data is read by absolute path within one account,
or copied, or generated into the worktree's own `out/` (never a junction; `CLAUDE.md`).

## Ports

| Port | Owner | Never |
|---|---|---|
| 8686 | the phone's loopback tile server (device side; host side when forwarded) | used by a host server |
| 8687 | `devtool serve` default | used by a sweep |
| 8765 | the coordinator's live viewer | asked to build by an agent (`--until test` only reads its catalog); stopped by anyone but its starter |
| 8800 | the coordinator's standalone server on this account | stopped by anyone but its starter |
| 8841 | the share listener on a phone (`adb forward` for emulator pairs) | |
| 8980 to 8987 | `tools/sweep.ps1` (`-BasePort`, +7 is the mock releases API) | |
| 8990, console 5640 | the sweep's emulator (`-Port`, `-EmuPort`; matrix from 5642) | |
| 5554 | the default emulator console | used by the sweep or a task |
| 9171 | mock releases API for `android-ui.py update` (`--mock-port`) | |

Per-task ports and emulator console ports are assigned in each brief (for example host 8694 and
console 5570); two agents never share either.

## Hardware notes

- AVDs go on E:: C: ran out of space with eight task AVDs.
- CPU virtualization is enabled (Windows Hypervisor Platform); setup in `android-emulator.md`.
