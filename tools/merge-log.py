"""Integrator helper: records a merged task in the three places that must stay in step.

Usage (from anywhere inside the repo, after `gh pr merge`):
    python -I tools/merge-log.py <task-id> <entry-file> <status-file> [<pr-number>]

- <entry-file>: the dated log paragraph for docs/PLANNING.md. It starts with
  **YYYY-MM-DD: Tnn merged (PR #n): title.** and is inserted at the end of the development log.
- <status-file>: the **Status: merged (PR #n).** line with follow-ups, appended to the task in docs/TASKS.md.
- CHANGELOG.md gets one line under the entry's date in the Unreleased section (newest first).
Then `git add docs CHANGELOG.md && git commit -m "Planning log: Tnn merged"`. See CLAUDE.md > Coordinating a wave.
"""
import os
import re
import subprocess
import sys

os.chdir(subprocess.check_output(['git', 'rev-parse', '--show-toplevel'], text=True).strip())
task, entry_file, status_file = sys.argv[1], sys.argv[2], sys.argv[3]
pr = sys.argv[4] if len(sys.argv) > 4 else None
entry = open(entry_file, encoding='utf-8').read().strip() + '\n\n'
status = open(status_file, encoding='utf-8').read().strip()

# docs/PLANNING.md: insert before the paragraph that closes the development log.
p = 'docs/PLANNING.md'
s = open(p, encoding='utf-8').read()
anchor = 'Phase-0 algorithms live in `shared` from the start'
assert anchor in s and (task + ' merged') not in s, 'log anchor missing or task already logged'
s = s.replace(anchor, entry + anchor, 1)
open(p, 'w', encoding='utf-8', newline='\n').write(s)

# docs/TASKS.md: append the Status line after the task's Done-when paragraph.
p = 'docs/TASKS.md'
s = open(p, encoding='utf-8').read()
h = s.index('### ' + task + '.')
title = s[h + 4:s.index('\n', h)].strip()  # e.g. "T59. Norway and Austria bare-earth elevation"
j = s.index('\n\n', s.index('**Done when:**', h))
assert '**Status:' not in s[h:j], 'task already has a Status line'
s = s[:j] + '\n' + status + s[j:]
open(p, 'w', encoding='utf-8', newline='\n').write(s)

# CHANGELOG.md: one line under the entry's date heading in Unreleased.
m = re.match(r'\*\*(\d{4}-\d{2}-\d{2}): ', entry)
date = m.group(1) if m else None
if pr is None:
    pm = re.search(r'\(PR #(\d+)\)', entry)
    pr = pm.group(1) if pm else None
name = title.split('. ', 1)[1] if '. ' in title else title
line = f"- {task}: {name}" + (f" (#{pr})" if pr else "")
p = 'CHANGELOG.md'
s = open(p, encoding='utf-8').read()
if date:
    head = f'### {date}\n'
    # Only the Unreleased section is edited: it runs from its heading to the next "## " heading.
    u0 = s.index('## Unreleased\n')
    nxt = s.find('\n## ', u0 + 1)
    u1 = len(s) if nxt < 0 else nxt + 1
    sec = s[u0:u1].replace('(nothing yet)\n\n', '').replace('(nothing yet)\n', '')
    if head in sec:
        i = sec.index(head) + len(head)
        if sec[i:i + 1] == '\n':
            i += 1
        sec = sec[:i] + line + '\n' + sec[i:]
    else:
        u = len('## Unreleased\n')
        if sec[u:u + 1] == '\n':
            u += 1
        sec = sec[:u] + head + '\n' + line + '\n\n' + sec[u:]
    s = s[:u0] + sec + s[u1:]
    open(p, 'w', encoding='utf-8', newline='\n').write(s)
print('ok', task, '| changelog:', line)
