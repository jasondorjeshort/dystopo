"""Integrator helper: records a merged task in the three places that must stay in step.

Usage (from anywhere inside the repo, after the coordinator's merge; the `wave` skill):
    python -I tools/merge-log.py <task-id> <entry-file> <status-file> [<pr-number>]

- <entry-file>: the dated log paragraph for docs/PLANNING.md. It starts with
  **YYYY-MM-DD: Tnn merged: title.** (older entries carry `(PR #n)`) and is inserted at the end of the development log.
- <status-file>: the **Status: merged.** line with follow-ups, appended to the task in docs/TASKS.md (a task is on the board until its wave closes).
- CHANGELOG.md gets one line under the entry's date in the Unreleased section (newest first).
T185 (the wave 22 close): an entry that does not start with the bold date and the task, `**YYYY-MM-DD: Tnn merged: <title>.**`
(or the older `**YYYY-MM-DD: Tnn merged (PR #n): <title>.**`), is refused before any file is written: wave 22's entries
began with a dash and the script printed a changelog line it never wrote.
Then `git add docs CHANGELOG.md && git commit -m "Planning log: Tnn merged"`. See the wave skill, .claude/skills/wave/SKILL.md (merge sequence).
Tested offline by tools/test_merge_log.py (T154).
"""
import os
import re
import subprocess
import sys

LOG_ANCHOR = 'Phase-0 algorithms live in `shared` from the start'
ENTRY_HEAD = re.compile(r'\*\*(\d{4}-\d{2}-\d{2}): (T\d+) merged(?: \(PR #\d+\))?: \S.*?\.\*\*')


def check_entry(task, entry):
    """T185: the entry's opening, `**YYYY-MM-DD: Tnn merged: <title>.**` for this task, or a ValueError that says what
    was found. The bold date is what the changelog line keys on."""
    e = entry.strip()
    m = ENTRY_HEAD.match(e)
    if not m:
        raise ValueError(f"the entry must start with the bold date, **YYYY-MM-DD: {task} merged: <title>.** "
                         f"(it starts {e[:60]!r}); nothing was written")
    if m.group(2) != task:
        raise ValueError(f"the entry is for {m.group(2)}, not {task}; nothing was written")
    return m.group(1)


def add_log(s, task, entry):
    """docs/PLANNING.md: the entry inserted before the paragraph that closes the development log."""
    assert LOG_ANCHOR in s and (task + ' merged') not in s, 'log anchor missing or task already logged'
    return s.replace(LOG_ANCHOR, entry.strip() + '\n\n' + LOG_ANCHOR, 1)


def add_status(s, task, status):
    """docs/TASKS.md: the Status line appended after the task's Done-when paragraph. Returns (text, title), the title
    such as "T59. Norway and Austria bare-earth elevation". The paragraph ends at the next blank line, or (T154: the last
    task of the board, the file ending without a blank line) at the end of the file."""
    h = s.index('### ' + task + '.')
    title = s[h + 4:s.index('\n', h)].strip()
    d = s.index('**Done when:**', h)
    j = s.find('\n\n', d)
    if j < 0:
        j = len(s.rstrip('\n'))
    assert '**Status:' not in s[h:j], 'task already has a Status line'
    tail = s[j:] if s[j:] else '\n'
    return s[:j] + '\n' + status.strip() + tail, title


def add_changelog(s, task, title, entry, pr=None):
    """CHANGELOG.md: one line under the entry's date heading in Unreleased. Returns (text, line); the text is
    unchanged when the entry has no date."""
    m = re.match(r'\*\*(\d{4}-\d{2}-\d{2}): ', entry.strip())
    date = m.group(1) if m else None
    if pr is None:
        pm = re.search(r'\(PR #(\d+)\)', entry)
        pr = pm.group(1) if pm else None
    name = title.split('. ', 1)[1] if '. ' in title else title
    line = f"- {task}: {name}" + (f" (#{pr})" if pr else "")
    if not date:
        return s, line
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
    return s[:u0] + sec + s[u1:], line


def main(argv):
    os.chdir(subprocess.check_output(['git', 'rev-parse', '--show-toplevel'], text=True).strip())
    task, entry_file, status_file = argv[1], argv[2], argv[3]
    pr = argv[4] if len(argv) > 4 else None
    entry = open(entry_file, encoding='utf-8').read().strip()
    status = open(status_file, encoding='utf-8').read().strip()
    try:
        check_entry(task, entry)
    except ValueError as e:
        sys.exit(f'merge-log: refused: {e}')
    # All three are computed before any is written, so a failed assertion leaves every file as it was.
    planning = add_log(open('docs/PLANNING.md', encoding='utf-8').read(), task, entry)
    tasks, title = add_status(open('docs/TASKS.md', encoding='utf-8').read(), task, status)
    changelog, line = add_changelog(open('CHANGELOG.md', encoding='utf-8').read(), task, title, entry, pr)
    for path, text in (('docs/PLANNING.md', planning), ('docs/TASKS.md', tasks), ('CHANGELOG.md', changelog)):
        open(path, 'w', encoding='utf-8', newline='\n').write(text)
    print('ok', task, '| changelog:', line)


if __name__ == '__main__':
    main(sys.argv)
