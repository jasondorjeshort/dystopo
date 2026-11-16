"""T298: agent-cost.py offline on synthetic agents.tsv files with the columns the hooks write: the join by tool_use_id,
the join by order when a stop has none, an agent's last stop line counted once, the task id matched as a word (T29 is not
T298), a launch with no stop, an empty folder, and the command line.

    python -I tools/test_agent_cost.py
"""
import importlib.util
import json
import os
import shutil
import subprocess
import sys
import tempfile

here = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("agent_cost", os.path.join(here, "agent-cost.py"))
ac = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ac)

COLS = ['time', 'event', 'tool_use_id', 'agent_id', 'agent_type', 'model', 'description', 'five_hour', 'seven_day',
        'meter_age_s', 'requests', 'input', 'cache_write', 'cache_read', 'output', 'last_context', 'note']


def row(**k):
    return '\t'.join(str(k.get(c, '')) for c in COLS)


def launch(t, tid, desc, model, seven, atype='general-purpose'):
    return row(time=t, event='launch', tool_use_id=tid, agent_type=atype, model=model, description=desc, five_hour=10, seven_day=seven, meter_age_s=30)


def stop(t, tid, aid, desc, seven, i, cw, cr, o, last, model='claude-test', note='', atype='general-purpose'):
    return row(time=t, event='stop', tool_use_id=tid, agent_id=aid, agent_type=atype, model=model, description=desc, five_hour=12,
               seven_day=seven, meter_age_s=20, requests=3, input=i, cache_write=cw, cache_read=cr, output=o, last_context=last, note=note)


# The column list must be the hook's own (the hook is the writer).
with open(os.path.join(here, '..', '.claude', 'hooks', 'agent-meter.js'), encoding='utf-8') as f:
    src = f.read()
assert "'" + "', '".join(COLS) + "'" in src.replace('\n  ', ' '), 'COLS differ from agent-meter.js'

tmp = tempfile.mkdtemp(prefix='agent-cost-')
try:
    a = os.path.join(tmp, 's1.agents.tsv')
    with open(a, 'w', encoding='utf-8') as f:
        f.write('\n'.join(['\t'.join(COLS),
            launch('2026-11-16T10:00:00Z', 'toolu_1', 'T298 implement: hooks', 'opus', 47),
            launch('2026-11-16T10:01:00Z', 'toolu_2', 'T29 something else', 'sonnet', 47),
            stop('2026-11-16T10:30:00Z', 'toolu_2', 'a2', 'T29 something else', 48, 1, 1, 1, 1, 4),
            # The implementer yields, then ends: the last line counts.
            stop('2026-11-16T10:40:00Z', 'toolu_1', 'a1', 'T298 implement: hooks', 48, 10, 1000, 50000, 200, 9000, note='yield'),
            stop('2026-11-16T11:00:00Z', 'toolu_1', 'a1', 'T298 implement: hooks', 49, 20, 2000, 90000, 400, 12000),
            # A verifier whose stop has no tool_use_id (no meta.json): joined by order.
            launch('2026-11-16T11:05:00Z', 'toolu_3', 'Verify T298', 'sonnet', 49),
            stop('2026-11-16T11:20:00Z', '', 'a3', '', 50, 5, 500, 30000, 100, 6000),
            # A chore launched, never stopped.
            launch('2026-11-16T11:25:00Z', 'toolu_4', 'Clean T298 worktree', 'haiku', 50),
        ]) + '\n')

    agents, total = ac.summarize('T298', [a])
    assert [x['description'] for x in agents] == ['T298 implement: hooks', 'Verify T298', 'Clean T298 worktree'], agents
    assert [x['join'] for x in agents] == ['tool_use_id', 'order', None], agents
    impl, ver, chore = agents
    assert impl['fresh_tokens'] == 20 + 2000 + 400 and impl['cache_read'] == 90000 and impl['last_context'] == 12000, impl
    assert ver['fresh_tokens'] == 605 and ver['model'] == 'claude-test', ver
    assert chore['stopped'] is None and chore['fresh_tokens'] is None, chore
    assert total['agents'] == 3 and total['stopped'] == 2 and total['fresh_tokens'] == 2420 + 605, total
    assert total['cache_read'] == 120000 and total['last_context'] == 18000, total
    assert total['joined_by'] == {'tool_use_id': 1, 'order': 1, 'none': 0}, total
    assert total['seven_day'] == [47, 50], total
    text = ac.line('T298', agents, total)
    assert text.startswith('T298: 3 agents, 3.0k fresh tokens (input, cache write, output), 120.0k cache read, 18.0k in final contexts; '
                           '7-day meter 47 to 50 (shared with other work) (joined 1 by tool_use_id, 1 by order). '), text
    assert 'Clean T298 worktree (haiku): no stop line' in text, text

    # T29 matches only its own agent.
    agents29, total29 = ac.summarize('T29', [a])
    assert [x['description'] for x in agents29] == ['T29 something else'] and total29['fresh_tokens'] == 3, agents29

    # A stop of another agent type is never joined by order to this launch; nothing matched says so.
    b = os.path.join(tmp, 's2.agents.tsv')
    with open(b, 'w', encoding='utf-8') as f:
        f.write('\n'.join(['\t'.join(COLS), launch('t1', 'toolu_9', 'T300 x', 'opus', 1),
                           stop('t2', '', 'a9', '', 2, 1, 1, 1, 1, 4, atype='Explore')]) + '\n')
    agents300, total300 = ac.summarize('T300', [b])
    assert len(agents300) == 1 and agents300[0]['stopped'] is None, agents300
    assert ac.line('T301', *ac.summarize('T301', [b])) == 'T301: no agent recorded (no description names it).'

    # The command line, on a folder and with --json; an empty folder and a missing file are not errors.
    r = subprocess.run([sys.executable, '-I', os.path.join(here, 'agent-cost.py'), 'T298', '--dir', tmp, '--json'], capture_output=True, text=True)
    assert r.returncode == 0 and json.loads(r.stdout)['total']['fresh_tokens'] == 3025, r
    empty = os.path.join(tmp, 'empty')
    os.mkdir(empty)
    r = subprocess.run([sys.executable, '-I', os.path.join(here, 'agent-cost.py'), 'T298', '--dir', empty], capture_output=True, text=True)
    assert r.returncode == 0 and 'no agent recorded' in r.stdout, r
    assert ac.read_tsv(os.path.join(tmp, 'missing.tsv')) == []
    r = subprocess.run([sys.executable, '-I', os.path.join(here, 'agent-cost.py')], capture_output=True, text=True)
    assert r.returncode == 2, r
finally:
    shutil.rmtree(tmp, ignore_errors=True)
print('test_agent_cost: ok')
