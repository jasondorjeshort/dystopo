---
date: 2026-11-22
kind: chore
---

# A lint for code after a task comment: a mid-line comment removed two map entries

Status: **untriaged** (an idea of the coordinator's own from wave 46's work, offered when the owner asked "Any ideas out of this wave? Moonshots welcome." and recorded on the owner's word, 2026-11-22: "Record them all; we'll triage and think through them later."). The anchors are files wave 46 wrote or read; nothing else here was checked against the code or measured.

In T333 a line of `androidApp/.../app/gnss/Gnss.kt` came to read `"engine" to engine, // T333 "error" to error, "permission" to permitted(),`: the task's marker comment, put in the middle of the line, turned the rest of the line into comment text, and `GET /gnss` lost its `error` and `permission` keys. It compiled, every test passed, and only the verifier's live read of the route caught it.

What it would take: a check (in `./gradlew check`, or the post-edit hook under `.claude/hooks/`) that flags a Kotlin or Python line where a task comment (`// Tnnn`, `# Tnnn`) is followed by something that parses as code (a quoted key and `to`, a call with a comma, a closing bracket); and the convention written in `.claude/rules/kotlin.md`: a task marker stands at the end of a line or on its own line.

## Open

- False positives on comments that quote code on purpose; the check could be limited to lines a diff adds.
- The wider gap: a debug route that silently loses a key breaks no test; a snapshot test of each route's key set would have caught this without any lint.
