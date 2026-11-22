---
date: 2026-11-22
kind: chore
---

# The tools' tests run with check: a Python test that nobody runs is not a test

Status: **untriaged** (an idea of the coordinator's own from wave 46's work, offered when the owner asked "Any ideas out of this wave? Moonshots welcome." and recorded on the owner's word, 2026-11-22: "Record them all; we'll triage and think through them later."). The anchors are files wave 46 wrote or read; nothing else here was checked against the code or measured.

T330 changed the `panorama` screen in `tools/android-a11y.py` and left `tools/test_android_a11y.py` failing at T327's assertion; its implementer and its verifier both reported green, because `./gradlew check` does not run the `tools/test_*.py` files and no brief named them. The test stayed red on `main` through three more merges (T333's implementer found it by accident), and because the script stops at the first failed assertion, the blocks T333 and T334 added after it did not run at all until T331 fixed the line.

What it would take: one Gradle task (or a step of `tools/verify-task.ps1`, which every verifier runs) that runs every `tools/test_*.py` and `node .claude/hooks/test-hooks.js` and fails `check` on any failure; and the test files changed so that one failed case does not hide the rest (the `unittest` runner some of them already use).

## Open

- `check` must stay runnable without Python on a fresh machine, or say plainly what is missing; the nightly workflow (`.github/workflows/nightly.yml`) is the other place it must run.
- The coordinator's recommendation at recording: put this on the next wave without waiting for triage.
