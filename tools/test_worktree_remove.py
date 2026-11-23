"""T349: tools/worktree-remove.ps1 on scratch folders only (a scratch repository and its worktrees in a temporary
folder; offline, no Gradle). A worktree holding a path over 260 characters is deleted and pruned from git's list, a
locked one unlocked first; a leftover folder git does not list is deleted; a folder a process holds a file in is
refused with that process named by PID and nothing deleted; the refusals that keep the delete inside the worktrees
folder (outside it, the folder itself, the main checkout, a drive root, a worktree of another repository, a folder
holding a junction whose target survives, a -StopPid that is no Gradle daemon and is left running); uncommitted changes
refused unless -DiscardChanges. Windows only (PowerShell 5.1 and the \\\\?\\ prefix): skipped by name elsewhere.

    python -I tools/test_worktree_remove.py
"""
import os
import shutil
import stat
import subprocess
import sys
import tempfile
import unittest

here = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(here, "worktree-remove.ps1")
WINDOWS = os.name == "nt"
LP = "\\\\?\\"


def lp(p):
    return LP + os.path.abspath(p)


def rmtree(path):
    """shutil.rmtree that also removes git's read-only object files, through the long-path prefix."""
    def again(func, p, _):
        os.chmod(p, stat.S_IWRITE)
        func(p)
    if os.path.exists(path):
        shutil.rmtree(lp(path), onerror=again)


@unittest.skipUnless(WINDOWS, "worktree-remove.ps1 is Windows-only (PowerShell and the long-path prefix)")
class WorktreeRemove(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.mkdtemp(prefix="t349-wr-")
        cls.env = dict(os.environ)
        hooks = os.path.join(cls.tmp, "nohooks")
        os.makedirs(hooks)
        cfg = {"core.hooksPath": hooks.replace("\\", "/"), "commit.gpgsign": "false", "init.defaultBranch": "main",
               "user.name": "Test", "user.email": "test@example.invalid"}
        cls.env["GIT_CONFIG_COUNT"] = str(len(cfg))
        for i, (k, v) in enumerate(cfg.items()):
            cls.env[f"GIT_CONFIG_KEY_{i}"] = k
            cls.env[f"GIT_CONFIG_VALUE_{i}"] = v
        cls.main = os.path.join(cls.tmp, "main")
        cls.root = os.path.join(cls.tmp, "wt")
        os.makedirs(cls.root)
        cls.git(cls.tmp, "init", "-q", cls.main)
        with open(os.path.join(cls.main, ".gitignore"), "w") as f:
            f.write("build/\n")
        cls.git(cls.main, "add", ".gitignore")
        cls.git(cls.main, "commit", "-q", "-m", "start")
        cls.n = 0

    @classmethod
    def tearDownClass(cls):
        rmtree(cls.tmp)  # rmtree removes a junction itself, never its target's files (Python 3.8+)

    @classmethod
    def git(cls, cwd, *args):
        r = subprocess.run(["git", "-c", "core.longpaths=true", *args], cwd=cwd, env=cls.env, capture_output=True,
                           text=True, timeout=60)
        assert r.returncode == 0, f"git {args}: {r.stderr}"
        return r.stdout

    def worktree(self, deep=False):
        WorktreeRemove.n += 1
        path = os.path.join(self.root, f"T{self.n}")
        self.git(self.main, "worktree", "add", "-q", path, "-b", f"task/T{self.n}")
        if deep:
            d = os.path.join(path, "build", *(["d" * 50] * 5))
            os.makedirs(lp(d))
            with open(lp(os.path.join(d, "f.txt")), "w") as f:
                f.write("x")
            self.assertGreater(len(os.path.join(d, "f.txt")), 260)
        return path

    def run_script(self, path, *extra, root=None, main=None):
        args = ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", SCRIPT, "-Path", path,
                "-WorktreeRoot", root or self.root, "-Main", main or self.main, *extra]
        r = subprocess.run(args, env=self.env, capture_output=True, text=True, timeout=180)
        return r.returncode, r.stdout + r.stderr

    def listed(self, path):
        out = self.git(self.main, "worktree", "list", "--porcelain")
        return os.path.normcase(path.replace("\\", "/")) in os.path.normcase(out)

    def test_removes_a_worktree_with_a_path_over_260_characters(self):
        path = self.worktree(deep=True)
        code, out = self.run_script(path)
        self.assertEqual(code, 0, out)
        self.assertIn("longest path", out)
        self.assertIn("removed", out)
        self.assertFalse(os.path.exists(lp(path)), out)
        self.assertFalse(self.listed(path), out)

    def test_a_locked_worktree_is_unlocked_and_pruned(self):
        path = self.worktree()
        self.git(self.main, "worktree", "lock", path)
        code, out = self.run_script(path)
        self.assertEqual(code, 0, out)
        self.assertFalse(self.listed(path), out)

    def test_a_leftover_folder_is_deleted_and_a_missing_one_is_fine(self):
        path = os.path.join(self.root, "leftover")
        os.makedirs(os.path.join(path, "build", "x"))
        code, out = self.run_script(path)
        self.assertEqual(code, 0, out)
        self.assertFalse(os.path.exists(path))
        code, out = self.run_script(path)
        self.assertEqual(code, 0, out)
        self.assertIn("not there", out)

    def test_a_held_file_is_refused_naming_the_process(self):
        path = self.worktree()
        held = os.path.join(path, "build", "held.bin")
        os.makedirs(os.path.dirname(held))
        f = open(held, "wb")
        try:
            code, out = self.run_script(path)
            self.assertEqual(code, 4, out)
            self.assertIn("REFUSED", out)
            self.assertIn(f"(PID {os.getpid()})", out)
            self.assertTrue(os.path.exists(held), "nothing may be deleted")
            self.assertTrue(self.listed(path))
        finally:
            f.close()
        code, out = self.run_script(path)
        self.assertEqual(code, 0, out)

    def test_uncommitted_changes_are_refused_unless_discarded(self):
        path = self.worktree()
        with open(os.path.join(path, "notes.txt"), "w") as f:
            f.write("work\n")
        code, out = self.run_script(path)
        self.assertEqual(code, 5, out)
        self.assertTrue(os.path.exists(os.path.join(path, "notes.txt")))
        code, out = self.run_script(path, "-DiscardChanges")
        self.assertEqual(code, 0, out)

    def test_refuses_paths_outside_the_worktrees_folder(self):
        path = self.worktree()
        outside = os.path.join(self.tmp, "outside")
        os.makedirs(outside, exist_ok=True)
        for p, root in [(outside, None), (self.root, None), (self.main, None), (os.path.join(path, "build"), None),
                        (os.path.join(self.root, "..", "outside"), None), (path, self.tmp), (path, "C:\\")]:
            code, out = self.run_script(p, root=root)
            self.assertEqual(code, 2, f"{p} {root}: {out}")
            self.assertIn("REFUSED", out)
        self.assertTrue(os.path.exists(outside) and os.path.exists(self.main) and os.path.exists(path))

    def test_refuses_the_main_checkout_inside_the_worktrees_folder(self):
        root2 = os.path.join(self.tmp, "wt2")
        main2 = os.path.join(root2, "main2")
        self.git(self.tmp, "init", "-q", main2)
        code, out = self.run_script(main2, root=root2, main=main2)
        self.assertEqual(code, 2, out)
        self.assertTrue(os.path.isdir(os.path.join(main2, ".git")))

    def test_refuses_a_worktree_of_another_repository(self):
        other = os.path.join(self.tmp, "other")
        self.git(self.tmp, "init", "-q", other)
        self.git(other, "commit", "-q", "--allow-empty", "-m", "o")
        path = os.path.join(self.root, "foreign")
        self.git(other, "worktree", "add", "-q", path)
        code, out = self.run_script(path)
        self.assertEqual(code, 2, out)
        self.assertIn("another repository", out)
        self.assertTrue(os.path.exists(path))

    def test_refuses_a_folder_holding_a_junction_and_keeps_its_target(self):
        target = os.path.join(self.tmp, "target")
        os.makedirs(target, exist_ok=True)
        with open(os.path.join(target, "keep.txt"), "w") as f:
            f.write("keep")
        path = os.path.join(self.root, "linked")
        os.makedirs(os.path.join(path, "build"))
        link = os.path.join(path, "build", "j")
        r = subprocess.run(["cmd", "/c", "mklink", "/J", link, target], capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        try:
            code, out = self.run_script(path)
            self.assertEqual(code, 2, out)
            self.assertIn("junction or symbolic link", out)
            self.assertTrue(os.path.exists(os.path.join(target, "keep.txt")))
        finally:
            os.rmdir(link)  # the link only
        self.assertTrue(os.path.exists(os.path.join(target, "keep.txt")))

    def test_stop_pid_takes_only_a_gradle_daemon(self):
        path = self.worktree()
        child = subprocess.Popen([sys.executable, "-I", "-c", "import time; time.sleep(60)"])
        try:
            code, out = self.run_script(path, "-StopPid", str(child.pid))
            self.assertEqual(code, 2, out)
            self.assertIn("is not a Gradle daemon", out)
            self.assertIsNone(child.poll(), "the process must still run")
            self.assertTrue(os.path.exists(path))
        finally:
            child.kill()
            child.wait()


if __name__ == "__main__":
    unittest.main(verbosity=2)
