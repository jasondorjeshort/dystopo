#!/usr/bin/env bash
# What came in on main: fetch the remote and list every commit in <base>..<remote>/main, so a commit another
# session pushed (the owner's side session writing ideas or the board) is read before this session pushes.
# Usage: bash tools/main-news.sh [--exit-code] [base] [remote]   (defaults: base the local main, remote origin)
# Per commit one line: short SHA, subject, session=<id of its Claude-Session: trailer> (or "session=none"), and
# BOARD, HANDOFF or IDEAS when it touches docs/TASKS.md, docs/HANDOFF.md or docs/ideas/ (a merge is read against
# its first parent). Last line: "main-news: N new (M touch the board, handoff or ideas)".
# Exit 0 after the list; with --exit-code, 3 when it lists any commit (tools/push-verified.sh uses that);
# 1 when the fetch or a ref fails. Only the session id is printed, never the trailer's link: a report that quotes
# this output may become a commit body (.claude/rules/docs.md, Personal data). Tested by tools/test_main_news.py.
set -u
strict=0
if [ "${1:-}" = "--exit-code" ]; then strict=1; shift; fi
base="${1:-main}"
remote="${2:-origin}"
echo "repo   $(git rev-parse --show-toplevel)"
git fetch -q "$remote" || { echo "main-news: fetch from $remote failed" >&2; exit 1; }
git rev-parse -q --verify "$base^{commit}" >/dev/null || { echo "main-news: no commit $base" >&2; exit 1; }
git rev-parse -q --verify "$remote/main^{commit}" >/dev/null || { echo "main-news: no $remote/main" >&2; exit 1; }
commits=$(git rev-list --reverse "$base..$remote/main") || { echo "main-news: rev-list failed" >&2; exit 1; }
n=0
m=0
for c in $commits; do
  n=$((n + 1))
  line=$(git log -1 --format='%h %s' "$c")
  sess=$(git log -1 --format='%(trailers:key=Claude-Session,valueonly,separator=%x20)' "$c" | tr -d '\r' | head -n 1)
  sess=${sess##*/}
  [ -n "$sess" ] || sess=none
  files=$(git show --format= --name-only --diff-merges=first-parent "$c")
  tags=""
  if printf '%s\n' "$files" | grep -qx 'docs/TASKS.md'; then tags="$tags BOARD"; fi
  if printf '%s\n' "$files" | grep -qx 'docs/HANDOFF.md'; then tags="$tags HANDOFF"; fi
  if printf '%s\n' "$files" | grep -q '^docs/ideas/'; then tags="$tags IDEAS"; fi
  [ -n "$tags" ] && m=$((m + 1))
  echo "$line  session=$sess$tags"
done
echo "main-news: $n new ($m touch the board, handoff or ideas)"
if [ "$strict" = 1 ] && [ "$n" -gt 0 ]; then exit 3; fi
exit 0
