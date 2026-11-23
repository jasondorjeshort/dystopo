"""T349: tools/main-news.sh and tools/push-verified.sh's main guard, offline, in a scratch clone pair (a bare origin
and two clones in a temporary folder; no network, no hooks). A commit pushed from one clone is listed by the other with
its short SHA, subject, the session id of its Claude-Session: trailer and the BOARD, HANDOFF or IDEAS tag (a merge read
against its first parent); the count line; --exit-code's 3 when anything is listed and 0 when nothing is;
push-verified.sh on main stopping with exit 3 and pushing nothing, and on a task branch pushing and printing VERIFIED
as before, with no main-news run; a hold/main branch on the remote refusing a push of main with exit 4 and its
message, not a task branch, until it is deleted. Needs Git Bash on Windows (found beside git) or bash elsewhere; skipped without one.

    python -I tools/test_main_news.py
"""
import os
import shutil
import stat
import subprocess
import sys
import tempfile
import unittest

here = os.path.dirname(os.path.abspath(__file__))
NEWS = os.path.join(here, "main-news.sh").replace("\\", "/")
PUSH = os.path.join(here, "push-verified.sh").replace("\\", "/")


def find_bash():
    if os.name != "nt":
        return shutil.which("bash")
    # Git Bash, not the WSL launcher in System32: walk up from git's exec path to the folder holding bin/bash.exe.
    try:
        p = subprocess.run(["git", "--exec-path"], capture_output=True, text=True, timeout=30).stdout.strip()
    except (OSError, subprocess.SubprocessError):
        return None
    d = os.path.normpath(p)
    for _ in range(5):
        cand = os.path.join(d, "bin", "bash.exe")
        if os.path.isfile(cand):
            return cand
        d = os.path.dirname(d)
    return None


def rmtree(path):
    """shutil.rmtree that also removes git's read-only object files on Windows."""
    def again(func, p, _):
        os.chmod(p, stat.S_IWRITE)
        func(p)
    shutil.rmtree(path, onerror=again)


BASH = find_bash()


class MainNews(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="t349-news-")
        hooks = os.path.join(self.tmp, "nohooks")
        os.makedirs(hooks)
        self.env = dict(os.environ)
        cfg = {"core.hooksPath": hooks.replace("\\", "/"), "commit.gpgsign": "false", "init.defaultBranch": "main",
               "user.name": "Test", "user.email": "test@example.invalid", "core.autocrlf": "false"}
        self.env["GIT_CONFIG_COUNT"] = str(len(cfg))
        for i, (k, v) in enumerate(cfg.items()):
            self.env[f"GIT_CONFIG_KEY_{i}"] = k
            self.env[f"GIT_CONFIG_VALUE_{i}"] = v
        self.env.pop("GIT_DIR", None)
        self.env.pop("GIT_WORK_TREE", None)
        self.origin = os.path.join(self.tmp, "origin.git")
        self.a = os.path.join(self.tmp, "a")
        self.b = os.path.join(self.tmp, "b")
        self.git(self.tmp, "init", "-q", "--bare", "-b", "main", self.origin)
        self.git(self.tmp, "clone", "-q", self.origin, self.a)
        self.commit(self.a, "README.md", "start", "Start")
        self.git(self.a, "push", "-q", "origin", "main")
        self.git(self.tmp, "clone", "-q", self.origin, self.b)

    def tearDown(self):
        rmtree(self.tmp)

    def git(self, cwd, *args):
        r = subprocess.run(["git", *args], cwd=cwd, env=self.env, capture_output=True, text=True, timeout=60)
        self.assertEqual(r.returncode, 0, f"git {args}: {r.stderr}")
        return r.stdout.strip()

    def commit(self, repo, path, text, message):
        full = os.path.join(repo, *path.split("/"))
        os.makedirs(os.path.dirname(full), exist_ok=True)
        with open(full, "w", encoding="utf-8", newline="\n") as f:
            f.write(text + "\n")
        self.git(repo, "add", path)
        self.git(repo, "commit", "-q", "-m", message)
        return self.git(repo, "rev-parse", "--short", "HEAD")

    def bash(self, cwd, *args):
        r = subprocess.run([BASH, *args], cwd=cwd, env=self.env, capture_output=True, text=True, timeout=120)
        return r.returncode, r.stdout, r.stderr

    def push_from_a(self):
        board = self.commit(self.a, "docs/TASKS.md", "board", "Board: a task\n\nBody.\n\nClaude-Session: https://example.invalid/code/session_side01")
        idea = self.commit(self.a, "docs/ideas/2026-01-01-x.md", "idea", "Ideas: one\n\nClaude-Session: session_side02")
        plain = self.commit(self.a, "src/x.txt", "x", "Code: no trailer")
        self.git(self.a, "checkout", "-q", "-b", "side")
        self.commit(self.a, "docs/HANDOFF.md", "handoff", "Handoff: rewritten")
        self.git(self.a, "checkout", "-q", "main")
        self.git(self.a, "merge", "-q", "--no-ff", "side", "-m", "Merge side\n\nClaude-Session: session_side03")
        merge = self.git(self.a, "rev-parse", "--short", "HEAD")
        self.git(self.a, "push", "-q", "origin", "main")
        return board, idea, plain, merge

    @unittest.skipIf(BASH is None, "no bash")
    def test_lists_what_the_other_clone_pushed(self):
        board, idea, plain, merge = self.push_from_a()
        code, out, err = self.bash(self.b, NEWS)
        self.assertEqual(code, 0, err)
        lines = [l for l in out.splitlines() if not l.startswith("repo ")]
        self.assertIn(f"{board} Board: a task  session=session_side01 BOARD", lines)
        self.assertIn(f"{idea} Ideas: one  session=session_side02 IDEAS", lines)
        self.assertIn(f"{plain} Code: no trailer  session=none", lines)
        self.assertIn(f"{merge} Merge side  session=session_side03 HANDOFF", lines)
        # The side branch's own commit is in main's history too and touches the handoff.
        self.assertTrue(any(l.endswith("Handoff: rewritten  session=none HANDOFF") for l in lines), lines)
        self.assertEqual(lines[-1], "main-news: 5 new (4 touch the board, handoff or ideas)")
        self.assertNotIn("https://", out)
        code, out, _ = self.bash(self.b, NEWS, "--exit-code")
        self.assertEqual(code, 3, out)
        # Nothing new after a pull; a base other than main is honoured.
        self.git(self.b, "pull", "-q", "--ff-only")
        code, out, _ = self.bash(self.b, NEWS, "--exit-code")
        self.assertEqual(code, 0, out)
        self.assertIn("main-news: 0 new (0 touch the board, handoff or ideas)", out)
        code, out, _ = self.bash(self.b, NEWS, "HEAD~1")
        self.assertIn("main-news: 2 new (2 touch the board, handoff or ideas)", out)  # the merge and its side commit
        code, out, err = self.bash(self.b, NEWS, "no-such-ref")
        self.assertEqual(code, 1, out)

    @unittest.skipIf(BASH is None, "no bash")
    def test_push_verified_stops_on_main_and_is_unchanged_on_a_task_branch(self):
        board, _, _, _ = self.push_from_a()
        a_head = self.git(self.a, "rev-parse", "HEAD")
        self.commit(self.b, "docs/notes.md", "mine", "Mine")
        code, out, err = self.bash(self.b, PUSH)
        self.assertEqual(code, 3, out + err)
        self.assertIn(f"{board} Board: a task  session=session_side01 BOARD", out)
        self.assertIn("push-verified: origin/main has commits this main lacks", err)
        self.assertNotIn("VERIFIED", out)
        self.assertEqual(self.git(self.b, "ls-remote", "origin", "refs/heads/main").split()[0], a_head)
        # A task branch: pushed and verified, no main-news run.
        self.git(self.b, "checkout", "-q", "-b", "task/T1")
        code, out, err = self.bash(self.b, PUSH)
        self.assertEqual(code, 0, out + err)
        self.assertIn("VERIFIED: origin/task/T1 equals HEAD", out)
        self.assertNotIn("main-news", out + err)
        # Main after the merge of what came in: nothing new, pushed and verified.
        self.git(self.b, "checkout", "-q", "main")
        self.git(self.b, "merge", "-q", "--no-edit", "origin/main")
        code, out, err = self.bash(self.b, PUSH)
        self.assertEqual(code, 0, out + err)
        self.assertIn("main-news: 0 new", out)
        self.assertIn("VERIFIED: origin/main equals HEAD", out)

    @unittest.skipIf(BASH is None, "no bash")
    def test_a_hold_on_main_refuses_the_push_until_it_is_deleted(self):
        # The coordinator's recipe (the wave skill): an empty commit on main naming who, why and the lift, pushed as hold/main.
        hold = self.git(self.a, "commit-tree", "HEAD^{tree}", "-p", "HEAD", "-m",
                        "Hold: closing sweep of wave 9 runs in the other clone\n\nLift: the coordinator deletes hold/main at its end.")
        self.git(self.a, "push", "-q", "origin", f"{hold}:refs/heads/hold/main")
        start = self.git(self.b, "rev-parse", "HEAD")
        self.commit(self.b, "docs/notes.md", "mine", "Mine")
        code, out, err = self.bash(self.b, PUSH)
        self.assertEqual(code, 4, out + err)
        self.assertIn("origin/hold/main exists", err)
        self.assertIn("Hold: closing sweep of wave 9 runs in the other clone", err)
        self.assertIn("Lift: the coordinator deletes hold/main at its end.", err)
        self.assertNotIn("VERIFIED", out)
        self.assertEqual(self.git(self.b, "ls-remote", "origin", "refs/heads/main").split()[0], start)
        # A task branch is not held.
        self.git(self.b, "checkout", "-q", "-b", "task/T2")
        code, out, err = self.bash(self.b, PUSH)
        self.assertEqual(code, 0, out + err)
        self.git(self.b, "checkout", "-q", "main")
        # Deleted: the push goes through.
        self.git(self.a, "push", "-q", "origin", "--delete", "hold/main")
        code, out, err = self.bash(self.b, PUSH)
        self.assertEqual(code, 0, out + err)
        self.assertIn("VERIFIED: origin/main equals HEAD", out)


if __name__ == "__main__":
    unittest.main(verbosity=2)
