# .claude/rules/ — path-scoped conventions

A rule file here is loaded only when Claude reads, writes or edits a file matching its `paths:`
frontmatter (a YAML list of globs; see https://code.claude.com/docs/en/memory). That makes rules the
right place for conventions that matter in one part of the tree and would be noise everywhere else.
A rule file without `paths:` loads on every session like `CLAUDE.md`; do not do that here. From the
Hatchery base (ADR 0020).

| File | Paths | What |
|---|---|---|
| `docs.md` | `docs/**`, `CLAUDE.md` | Where a piece of text goes, size caps and exemptions, indexes, frontmatter |
| `kotlin.md` | `**/*.kt`, `**/*.kts` | Platform boundaries, geodata conventions, tests and goldens, devtool and route conventions |
| `trackdb.md` | the SQLDelight schema | Track database migrations, step by step |

Keep each rule short and concrete. Procedures belong in skills (`.claude/skills/`); decisions belong
in ADRs (`docs/design/decisions/`); a rule is what to do and not do while editing matching files.
