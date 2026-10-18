## Model routing

Pick the subagent by what the task needs, not by what it touches; each one pins its own model in
`.claude/agents/`. A settled edit from a complete brief is `tester` or `coder` work even when it is
to docs, skills or hooks; the `architect` is for the decision itself. Fable costs two and a half
times Opus and five times Sonnet, and in a docs-heavy phase the execution is most of the tokens.

| Task | Agent | Model | Why |
|---|---|---|---|
| Proposals, ADRs, answers to the critic, design docs, plans, design review | `architect` | Fable 5.1 | Strongest reasoning; worth the cost where judgment matters most. Not for executing a settled brief. |
| Adversarial second opinion on a proposal or major technical choice | `critic` | Fable 5.1 | Must be able to find what the architect missed; a weaker critic rubber-stamps. A process proposal may take an Opus critic by model override; a language, toolkit or storage choice gets Fable. |
| Implementation, debugging, non-trivial refactors, building debug interfaces | `coder` | Opus 5.5 | Best coding model at Opus price. Effort pinned to high because its default is medium. |
| Writing tests, fixing failing tests, small well-specified changes to code or docs | `tester` | Sonnet 5.5 | Fast and capable for scoped work at a fifth of Fable's cost. |
| Running test suites, lint, format, commits, mechanical file ops | `runner` | Haiku 5.5 | Cheapest by far; these tasks need reliability, not depth. |
| Independent review of a diff or unpushed commit; reports, does not fix | `reviewer` | Opus 5.5 | Must read code as well as the coder wrote it; a weaker reviewer rubber-stamps. |
| Pre-push sanity checks and the push itself | `pusher` | Sonnet 5.5 | Needs enough judgment to read test output and spot secrets, not enough to justify Opus. |
