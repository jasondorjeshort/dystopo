# Dependency checklist (ADR 0012)

Answer one line per question, in the brief or the proposal. "Unknown" is an answer except for the licence; a blank is not.

1. What does it do that cannot be copied in under a page?
2. Is the licence GPL-compatible (ADR 0001)?
3. Last release, open issues, response time to them?
4. Who maintains it, and how many of them?
5. How large is the transitive closure it pulls in?
6. Do its tests run here?
7. How does it behave on untrusted input?
8. Who watches its upstream releases and advisories?
9. How would it be replaced, and what does that cost?
10. What does the debug surface lose if it misbehaves?

## Rules

- Default is to evaluate. A small dependency answers in the brief; one the project leans on
  answers in the proposal.
- A thing under a page may be copied in. The copy keeps its own licence text and SPDX identifier,
  carries an origin line (URL, version or commit, date), lives under a path listed in ADR 0001's
  components table, and counts against the dependency budget.
- Reimplementing a parser, a format, cryptography or date handling is not copying: it answers
  this checklist like a dependency.
