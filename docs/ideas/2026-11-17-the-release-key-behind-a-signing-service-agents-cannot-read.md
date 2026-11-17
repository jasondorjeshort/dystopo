---
date: 2026-11-17
kind: chore
---

# The release key behind a signing service that agents cannot read

T293's permission test showed that the deny rule in `.claude/settings.json` stops only the Read tool: a script run
by an agent read the main checkout's `tools\release.properties` (its length and names; no value was read). T293's
`tools/sign-release.ps1` is now the one place that signs, so it is also the one place that needs the key. Move the key
behind a small local signing service (a Windows service or a scheduled task under a separate local account that alone
can read the keystore): it takes an APK path and writes the signed copy, and `sign-release.ps1` calls it instead of
reading the properties file. Then "no agent reads the key" holds by the file's permissions, not by instructions.

## Open

- The owner's part: creating the account and setting the file's ACL (agents must not do it).
- How the service is reached (a named pipe, a watched folder) and what it refuses (anything but an APK).
- Recorded from conversation, not triaged or checked against the code.
