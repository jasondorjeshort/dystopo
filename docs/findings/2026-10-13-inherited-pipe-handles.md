---
date: 2026-10-13
status: resolved
area: tooling / Windows processes
---

# Device scripts "hung" after finishing: a daemon they started held the caller's pipe

## Question

`tools/android-push-pack.ps1` hung after pushing the packs for T85's implementer (and once for T75's).
Where does it wait?

## What was tried

Ran `android-push-pack.ps1 -Serial <serial>` from Git Bash with its output piped, after
`./gradlew --stop`. Every line was printed by 35 s (the last is the `ls` of `files/packs`), but the pipe
did not close until the Gradle daemon the sprite step (`gradlew :devtool:run --args=sprite`) had started
was killed. The same held for `android-emu.ps1 -Build` run in the background: its task finished only
when the emulator was stopped, 35 minutes after its last line.

## Result

On Windows a child process inherits every inheritable handle of its parent, and a script's stdout is
the caller's pipe. A Gradle daemon (started by a Gradle client run with `&`) and the emulator (started
with `Start-Process -RedirectStandardOutput`) kept that pipe open after the script ended, so the caller
waited for them. Starting them through ShellExecute (`Start-Process` without `-NoNewWindow` or
redirection, with `cmd /c` writing the output to a log) gives them none of the script's handles:
`Start-Detached` in both scripts. After the change the push returned at 20 s with a fresh daemon still
running, and `android-emu.ps1 -Build` at 153 s with the emulator up.

## Next

Nothing for these two scripts. Any other script that starts a daemon from a caller's pipe needs the same
treatment (`../reference/windows-and-shells.md`, Processes).
