# Brief for the critic (fill in and send as the Agent prompt; most capable model)

Review proposal `docs/design/proposals/NNNN-<title>.md` of the Dystopo project. Read `CLAUDE.md`, the
proposal, and the files it cites (PLANNING sections, ADRs, code), in that order.

Context the proposal does not state: <one or two sentences, or "none">.

Already ruled out and why (do not re-argue these): <list, or "nothing">.

Verify every item under "Claims to verify" against primary sources (docs, release notes, licences,
source, a measurement you can run without a device) and cite what you found. Then argue the strongest
case against the recommendation and for the best alternative. Weigh this project's constraints:
offline first, `shared` commonMain pure Kotlin for every target, permissive dependencies only (LGPL
only in `tilebuild`), every feature agent-drivable, the design goals (ADR 0025). Look for hidden
coupling: does this decision quietly make a later one for us? Produce a numbered list of objections
with severity (blocking, serious, minor), evidence, and what would resolve each; then one paragraph
with your own recommendation and confidence. If the proposal is right, say so plainly and list the
residual risks; manufactured objections waste the budget.

Write the review to `docs/design/proposals/NNNN-critic-review.md` with the Write tool (Bash heredocs
with apostrophes fail here). Do not edit the proposal. Do not commit; leave the file for the
coordinator. Report the file path and the count of objections by severity.
