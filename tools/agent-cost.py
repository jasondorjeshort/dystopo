"""Per-agent cost of one task, from the lines the hooks record (T298; docs/reference/agent-cost.md).

Usage:
    python -I tools/agent-cost.py <task-id> [--dir <folder>] [--file <tsv> ...] [--json]

Reads every `<session>.agents.tsv` in `~/.claude/usage-state/` (or `--dir`, or only the `--file`s): the `launch` lines
`.claude/hooks/usage-gate.js` appends at an Agent call and the `stop` lines `.claude/hooks/agent-meter.js` appends at
SubagentStop. A stop joins its launch by `tool_use_id` (the hook reads it from the agent's meta.json); a stop without one
joins the earliest unjoined launch of the same file and agent type that came before it (by order). An agent's last stop
line counts (a continued or yielding agent writes several; the transcript sums are cumulative). An agent belongs to the
task when the task id stands as a word in its description (`T29` never matches `T298`).

Prints one cost line for the log entry; `--json` prints the agents and the totals instead. Exit 0 with a line saying
nothing matched when no agent did. Tested offline by tools/test_agent_cost.py.
"""
import glob
import json
import os
import re
import sys

NUMS = ['five_hour', 'seven_day', 'meter_age_s', 'requests', 'input', 'cache_write', 'cache_read', 'output', 'last_context']


def read_tsv(path):
    """The rows of one file as dicts, numbers as int or float or None. A file that cannot be read gives no rows."""
    try:
        with open(path, encoding='utf-8') as f:
            lines = f.read().splitlines()
    except OSError:
        return []
    if not lines:
        return []
    cols = lines[0].split('\t')
    rows = []
    for i, line in enumerate(lines[1:]):
        vals = line.split('\t')
        r = {c: (vals[k] if k < len(vals) else '') for k, c in enumerate(cols)}
        for c in NUMS:
            v = r.get(c, '')
            try:
                r[c] = int(v) if re.fullmatch(r'-?\d+', v) else (float(v) if v else None)
            except ValueError:
                r[c] = None
        r['file'] = os.path.basename(path)
        r['order'] = i
        rows.append(r)
    return rows


def join(rows):
    """Agents from one file's rows: [{launch, stop}], every launch once, each with its agent's last stop or None.
    Returns (agents, method counts)."""
    launches = [r for r in rows if r.get('event') == 'launch']
    last = {}
    for r in rows:
        if r.get('event') != 'stop':
            continue
        key = r.get('agent_id') or r.get('tool_use_id') or f"row{r['order']}"
        last.pop(key, None)
        last[key] = r
    by_id = {l['tool_use_id']: l for l in launches if l.get('tool_use_id')}
    agents = {id(l): {'launch': l, 'stop': None, 'join': None} for l in launches}
    methods = {'tool_use_id': 0, 'order': 0, 'none': 0}
    orphans = []
    for s in sorted(last.values(), key=lambda r: r['order']):
        l = by_id.get(s.get('tool_use_id') or '')
        if l is not None and agents[id(l)]['stop'] is None:
            agents[id(l)].update(stop=s, join='tool_use_id')
            methods['tool_use_id'] += 1
        else:
            orphans.append(s)
    for s in orphans:
        free = [l for l in launches if agents[id(l)]['stop'] is None and l['order'] < s['order']
                and (not s.get('agent_type') or l.get('agent_type') == s.get('agent_type'))]
        if free:
            agents[id(free[0])].update(stop=s, join='order')
            methods['order'] += 1
        else:
            agents[f"stop{s['order']}"] = {'launch': None, 'stop': s, 'join': None}
            methods['none'] += 1
    return list(agents.values()), methods


def matches(task, a):
    pat = re.compile(r'(?<![A-Za-z0-9])' + re.escape(task) + r'(?![0-9])')
    texts = [(a['launch'] or {}).get('description', ''), (a['stop'] or {}).get('description', '')]
    return any(pat.search(t or '') for t in texts)


def kilo(n):
    if n is None:
        return 'n/a'
    if n >= 1_000_000:
        return f'{n / 1_000_000:.1f}M'
    return f'{n / 1000:.1f}k' if n >= 1000 else str(n)


def summarize(task, files):
    agents, methods = [], {'tool_use_id': 0, 'order': 0, 'none': 0}
    for f in files:
        agents += [x for x in join(read_tsv(f))[0] if matches(task, x)]
    for x in agents:
        if x['stop'] is not None:
            methods[x['join'] or 'none'] += 1
    out = []
    for a in agents:
        l, s = a['launch'] or {}, a['stop'] or {}
        fresh = None if not s else (s.get('input') or 0) + (s.get('cache_write') or 0) + (s.get('output') or 0)
        out.append({
            'description': l.get('description') or s.get('description') or '',
            'agent_type': l.get('agent_type') or s.get('agent_type') or '',
            'model': s.get('model') or l.get('model') or '',
            'launched': l.get('time'), 'stopped': s.get('time'), 'join': a['join'],
            'fresh_tokens': fresh, 'cache_read': s.get('cache_read'), 'output': s.get('output'),
            'requests': s.get('requests'), 'last_context': s.get('last_context'),
            'seven_day': [l.get('seven_day'), s.get('seven_day')], 'five_hour': [l.get('five_hour'), s.get('five_hour')],
            'note': s.get('note') or '', 'file': l.get('file') or s.get('file'),
        })
    out.sort(key=lambda x: x['launched'] or x['stopped'] or '')
    stopped = [x for x in out if x['stopped']]
    total = {
        'agents': len(out), 'stopped': len(stopped),
        'fresh_tokens': sum(x['fresh_tokens'] or 0 for x in stopped),
        'cache_read': sum(x['cache_read'] or 0 for x in stopped),
        'output': sum(x['output'] or 0 for x in stopped),
        'last_context': sum(x['last_context'] or 0 for x in stopped),
        'joined_by': methods,
    }
    sevens = [v for x in out for v in x['seven_day'] if v is not None]
    total['seven_day'] = [sevens[0], sevens[-1]] if sevens else None
    return out, total


def line(task, agents, total):
    if not agents:
        return f'{task}: no agent recorded (no description names it).'
    parts = []
    for a in agents:
        if a['stopped']:
            parts.append(f"{a['description']} ({a['model'] or a['agent_type']}): {kilo(a['fresh_tokens'])} fresh, "
                         f"{kilo(a['cache_read'])} cache read, {kilo(a['last_context'])} final context"
                         + (f" [{a['note']}]" if a['note'] else ''))
        else:
            parts.append(f"{a['description']} ({a['model'] or a['agent_type']}): no stop line")
    meter = ''
    if total['seven_day']:
        meter = f"; 7-day meter {total['seven_day'][0]:g} to {total['seven_day'][1]:g} (shared with other work)"
    j = total['joined_by']
    joined = ', '.join(f'{v} by {k}' for k, v in j.items() if v)
    return (f"{task}: {total['agents']} agents, {kilo(total['fresh_tokens'])} fresh tokens (input, cache write, output), "
            f"{kilo(total['cache_read'])} cache read, {kilo(total['last_context'])} in final contexts{meter}"
            f"{' (joined ' + joined + ')' if joined else ''}. " + '; '.join(parts) + '.')


def main(argv):
    args = argv[1:]
    if not args or args[0].startswith('-'):
        sys.stderr.write(__doc__)
        return 2
    task, files, folder, as_json = args[0], [], os.path.join(os.path.expanduser('~'), '.claude', 'usage-state'), False
    i = 1
    while i < len(args):
        if args[i] == '--json':
            as_json = True
        elif args[i] == '--dir' and i + 1 < len(args):
            folder = args[i + 1]; i += 1
        elif args[i] == '--file' and i + 1 < len(args):
            files.append(args[i + 1]); i += 1
        else:
            sys.stderr.write(f'agent-cost: unknown or incomplete argument: {args[i]}\n')
            return 2
        i += 1
    if not files:
        files = sorted(glob.glob(os.path.join(folder, '*.agents.tsv')))
    agents, total = summarize(task, files)
    if as_json:
        print(json.dumps({'task': task, 'agents': agents, 'total': total}, indent=1))
    else:
        print(line(task, agents, total))
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
