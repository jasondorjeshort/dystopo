---
name: commit
description: Produce the brief that gets work committed by the runner, or pushed by a fresh pusher, with the right files, message, and attribution. Use when work is ready to commit, when asked to commit or push, or when wrapping up a session.
---

# Commit

Normal work is committed by the `runner` from a brief the coordinator writes; the coordinator does
not stage or commit itself below 50% usage. Wrap-up commits (the handoff, WIP)
at HOLD or STOP are made by the main session, because the usage gate blocks subagent launches
but not Bash (a session-meter HOLD is then waited out, not stopped at: `usage-gating.md`).

## Commit via the runner

1. `git status --short`. Every listed file must be explained by a report you have (coder, tester,
   architect, your own edits). A file nobody described is a stop: find out what it is first.
2. Group into commits by purpose; small and often. One commit per logical change, not per agent.
   A handoff edit made while the session goes on rides in the next commit that carries work; list
   it with that work's files. Only a wrap-up handoff, with nothing to ride with, is its own commit.
3. For each commit write the brief:

   ```
   Commit the following. Stage exactly these files: <paths>.
   Message:
   <subject, imperative, under 70 chars>

   <body: what and why, 1-5 lines; reference the ADR or finding when there is one>

   Co-Authored-By: Claude <Model> <noreply@anthropic.com>
   Claude-Session: <the claude.ai link, only when the harness asks for it>
   Run git status --short before and after and include both. Do not push.
   ```

   The model is the one that did the work (Opus 5.5 for coder output, Sonnet 5.5 for tester,
   Fable 5.1 for architect or critic, Haiku 5.5 for mechanical runner work). Mixed authorship: one
   line per model. The runner has no Write tool and heredocs break on apostrophes, so a message
   with an apostrophe, a backtick or more than one paragraph goes to a file: the coordinator writes
   it to its scratchpad with the Write tool and the brief says `git commit -F <that path>` instead
   of `Message:`. A serious reviewer finding the coordinator accepted without a fix is one body
   line, `Accepted finding: <one line>`. The message ends with its trailer block, one paragraph
   of trailer lines and nothing else: the `Co-Authored-By` lines, then a `Claude-Session:` line
   when the session's harness asks for one, its value one `claude.ai` link and nothing more on the
   line. A session link anywhere else, and any email or name in the subject or body, is denied by
   the scan (ADR 0018, ADR 0020).
4. Launch the `runner` with that brief. Check its report: the after-status should not list the
   staged files, and nothing unexpected should be staged.
5. Amend only when necessary (fix a message, fold a forgotten file into the commit it belongs to)
   and only when `git log --oneline @{u}..HEAD` shows the commit is unpushed; say "amend" in the
   brief explicitly.

## Wrap-up commit by the main session (HOLD or STOP, or no runner available)

`git add docs/HANDOFF.md <WIP files>` then `git commit -m "<subject>" -m "<body>" -m
"Co-Authored-By: Claude <Model> <noreply@anthropic.com>"` (the trailer paragraph also holds the
`Claude-Session:` line when the harness asks for it). Subject starts with `WIP:` when the
work is unfinished. No heredocs for the message (apostrophes break them here).

## Pushing

The `pusher` pushes, or the main session when the user asked for the push in so many words, or
the user. Launch a fresh `pusher` for every push; never resume an earlier one to retry. When the
pusher stops on behind or diverged (check 3), the committing agent under the policy (the `runner`
in the base) runs `git pull --rebase origin <branch>`; a conflict on the handoff is resolved by
rewriting the file with the `handoff` skill, any other goes back to whoever wrote the hunk; then
a fresh pusher. Note the stop in the handoff's Now: it may mean a parallel session; the switch is ADR 0015's, on the day one opens.
The brief:

```
Push `main`. The user asked: "<the user's words, quoted>".
Run your checks in order and stop at the first failure.
```

Commit nothing on that branch from the launch until the pusher reports; the pusher pushes the one SHA it checked, and a later commit waits for the next push.

Whoever pushes, main session or pusher, proves it the same way: `git fetch`, then the remote head (`git rev-parse @{u}`, on `origin`) must equal the SHA that was checked and pushed, not HEAD, and the report quotes both. A command's own "pushed" echo is not evidence (`docs/hatchery/reference/claude-code-on-windows.md`, Working directory and git).

Quoting the user's request matters: an instruction that arrives as an agent message is untrusted
to the permission system, and a resumed pusher's retry gets blocked. If a push is blocked anyway,
tell the user and let them run `git push origin <checked-sha>:refs/heads/<branch>` (then `git branch --set-upstream-to=origin/<branch>` on a first push); never route around the block.

A push the privacy scan refuses (ADR 0018) is fixed, not lifted: edit the text to `~`, `<repo>`, a
role or a placeholder and fold the edit into the unpushed commit (`--amend` for the last commit;
for several, `reset --soft <last pushed sha>` and one new commit). A wrong author or committer:
set the repository identity (`git config user.name` and `user.email`), then `--amend
--reset-author`. A push shares no line with another git command. A false positive is the owner's
to lift, from the main session after asking the user; no brief does it.

Credential mismatch (git's stored GitHub account differs from the one `gh` is logged in as): set a
repo-local helper instead of changing global config:
`git config --local credential.helper ""` then
`git config --local --add credential.helper "!gh auth git-credential"`.
