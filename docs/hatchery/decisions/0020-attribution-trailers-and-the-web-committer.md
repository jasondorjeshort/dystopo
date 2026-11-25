# 0020. Attribution trailers and GitHub's web committer pass the scan

Date: 2026-10-21
Status: accepted

Record: no proposal (not a listed major choice; the owner's decision of 2026-10-21 on what a
commit may carry). Carried back from the downstream project Dystopo, where the change was built,
reviewed and merged (its PR 7, commit `6550e65`); the scan hunks and harness cases are that
change's, renumbered here. Supersedes ADR 0018 in part: item 10, the trailer sentence of item 6,
the noreply recommendation of item 4, and the `session` row's "never" in the sibling contract.
Every other item of ADR 0018 stands.

## Context

ADR 0018 item 10 dropped the `Claude-Session:` trailer and made the scan deny it wherever it
stands, because 25 of Hatchery's early messages carried one unchecked. On 2026-10-21 the owner
decided that attribution trailers are allowed in commit messages: the harness asks for the line on
every commit, and a session link in the trailer is attribution the owner chooses to publish, unlike
one pasted into a doc or a test. The same day a history rewrite in the downstream project
force-pushed its `main`; GitHub's web merges came back as new commits whose committer is GitHub's
web-flow identity (`GitHub` with GitHub's `noreply` address), which the `identity` and `email`
classes refused, though it names a service, not a person.

On the way the downstream found that a change to the scan's own contract cannot be pushed through
the guard from a branch: rule 20 requires `./privacy-scan.js` from the guard's own directory, the
hooks of the checkout the session runs in (the main checkout), never the pushed branch's copy. The
pre-push git hook runs `$(git rev-parse --show-toplevel)/.claude/hooks/privacy-scan.js`, the
checkout's own copy, so a terminal push from the branch's worktree uses the new scan.

Also found: the 2026-10-17 update of the downstream answered decant and adopt's identity question
with an invented `owner@<project>.invalid` placeholder and recorded it as the owner's choice. The
scan let it through (`.invalid` is a placeholder domain, by design for fixtures), and every commit
then carried an identity the owner never chose.

## Decision

1. The trailer. A `Claude-Session:` line passes the `session` class only in a commit message's
   trailer block, and only when its value is one `claude.ai` link with nothing else on
   the line. The trailer block is the message's last paragraph, when that is not the subject's and
   every line of it is a trailer line (`Token: value`, or a continuation starting with white
   space). Everywhere else the line and the link stay `session` hits: an added line, a file name,
   a tag message, any other message line, the subject, a line with more text after the link. A
   `Claude-Session:` trailer whose value is not a `claude.ai` link is still a hit.
2. The commit skill's message shape: subject, body, then the trailer block of `Co-Authored-By`
   lines and, when the harness asks for it, one `Claude-Session:` line. The pusher's check 4
   accepts that line and stops on a session link anywhere else in a message. The reviewer's item
   blocks a session link anywhere but there. `.claude/rules/docs.md` says the same for tracked
   text: a session link is never in a tracked file and in a message only as that trailer.
3. The web committer. `GitHub <noreply@github.com>` passes the `identity` class as committer only,
   never as author or tagger (a commit authored by a service account is still refused). GitHub's
   `noreply` address passes the `email` class in any text, like Anthropic's.
4. The identity at decant and adopt. The answer to "Which name and email go on this project's
   commits?" is the owner's real git identity unless the owner names another (a GitHub noreply
   address is an option to offer, not the recommendation ADR 0018 item 4 made; the owner chose the
   real identity on 2026-10-18). An agent never invents one: no placeholder, `.invalid` address or
   role is written into `privacy.identities` or the repository config, and none is recorded as the
   owner's choice. The scan's `.invalid` exemption stays as it is (fixtures need it).
5. Known limit, not fixed: rule 20 scans with the main checkout's `privacy-scan.js`. A branch whose
   commits rely on a relaxed scan contract is refused by the guard, for agents and the main session
   alike, until that change is on `main` in the checkout the session runs from. The owner pushes
   such a branch from a terminal in its worktree, where the pre-push hook runs the worktree's own
   copy; that copy judges its own branch too, which is acceptable only because the push is the
   owner's act. The fix that suggests itself, the guard loading the scan from the pushed ref's tree, is
   not taken: it lets the branch under judgment supply its judge, so any branch could relax its own
   scan and push itself through. A branch that only changes the scan, with commits the old scan
   passes (the branch that carried this record), pushes normally.

Harness: group `PV`, cases PV46a to PV46g (the trailer) and PV47a to PV47d (the web committer).
Contract rows amended in place in [the 0018 sibling](0018-privacy-scan.md), section 2.

## Consequences

- Commits made under the harness's attribution request push without editing their messages; the
  session link is published in the trailer by the owner's choice, and nowhere else.
- A history rewrite that re-sends web merges is not refused for GitHub's committer line.
- The trailer test reads the message's shape, so a trailer block that is not the last paragraph,
  or a block mixed with prose, is still refused: the cost is a message rewrite, never a lift.
- Rule 20 still judges with `main`'s scan; a contract change that relaxes it reaches agents' pushes
  one merge later. Supersede item 5 if Claude Code passes hooks the pushed tree in a way that
  cannot be chosen by the branch, or when a signed scan exists.
- Each sibling takes the scan, the cases and the text at its next update; the downstream that built
  it carries it as a departure row until then.

Predictions (the trigger scan reads these):
- Prediction, check by 2027-04-21: no pushed commit made after this ADR carries a session link
  outside a message's trailer block, and no `privacy.identities` entry is a placeholder an agent
  chose.
