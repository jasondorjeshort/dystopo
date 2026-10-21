---
verified: 2026-10-17
source: https://code.claude.com/docs/en/memory#import-additional-files, https://code.claude.com/docs/en/memory#instructions-seem-lost-after-compact, https://code.claude.com/docs/en/hooks#instructionsloaded, observed
---

# How the agreement loads

The project `CLAUDE.md` imports the agreement files with `@.claude/agreement/<section>.md` lines
(ADR 0016). This entry records what the loader does with them.

## Facts from the docs

- The project-root `CLAUDE.md` and its imports are re-read and re-injected after compaction.
- Imports nest to four hops, resolve relative to the importing file, and are skipped inside code
  spans and fenced blocks. Imports load at launch and cost full context.
- An `InstructionsLoaded` hook fires once per loaded instruction file, with a `load_reason` of
  `session_start`, `include`, `compact` and others. It is observability only: it cannot block or
  alter the load.

## Sentinel procedure (observed 2026-10-17)

In a scratch directory outside any repository: a `CLAUDE.md` holding `@sub/s.md`, and `sub/s.md`
holding one unique sentence.

1. `claude -p "Print the sentinel sentence from your instructions and nothing else"`. The sentence
   was printed: the import loaded.
2. Add `.claude/settings.json` with an `InstructionsLoaded` hook whose command is
   `cat >> instr.log; echo >> instr.log`, and run the same prompt. The sentence was printed again,
   and the log held two events (stdin JSON, `file_path` shown with Windows backslashes):
   - `file_path` `...\sentinel\CLAUDE.md`, `load_reason` `session_start`.
   - `file_path` `...\sentinel\sub\s.md`, `load_reason` `include`.

The import resolved from the importing file's directory, and the imported file is its own event.

## Not observed

- A `compact` event: the compaction check needs a long interactive session and was not run. The
  docs state the re-injection; the `load_reason` value is from the docs only.
- Whether an import written with a trailing `.`, `,` or `)` strips it (the token rule
  `.claude/hooks/imports.js` follows). The agreement files avoid the case.
