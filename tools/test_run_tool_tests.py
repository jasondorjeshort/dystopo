"""T345: tools/run-tool-tests.py on a fixture tree (--root), never on this repo's own tests (it would run itself).

One passing file, one failing at its first assert with a later file still run and reported, a file that needs a
program the fixture's PATH lacks (skipped by name, not failed), the hook tests absent and then without node, the
report written by --out, and the exit codes.
"""
import json
import os
import subprocess
import sys
import tempfile
import unittest

here = os.path.dirname(os.path.abspath(__file__))
RUNNER = os.path.join(here, "run-tool-tests.py")


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)


class RunToolTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = self.tmp.name
        write(os.path.join(self.root, "tools", "test_a.py"), "print('a fine')\n")
        write(os.path.join(self.root, "tools", "test_b.py"), "print('before')\nassert 1 == 2, 'b broke'\n")
        write(os.path.join(self.root, "tools", "test_c.py"), "import os\nassert os.path.isdir('tools')  # cwd is the root\n")
        write(os.path.join(self.root, "tools", "test_verify_task.py"), "raise SystemExit(3)\n")
        write(os.path.join(self.root, "tools", "helper.py"), "raise SystemExit(5)  # not a test file\n")

    def tearDown(self):
        self.tmp.cleanup()

    def run_runner(self, *args, path=None):
        env = dict(os.environ)
        if path is not None:
            env["PATH"] = path
        p = subprocess.run([sys.executable, "-I", RUNNER, "--root", self.root, *args], capture_output=True, text=True,
                           env=env, timeout=120)
        return p.returncode, json.loads(p.stdout), p.stderr

    def test_a_failure_hides_no_other_file_and_a_missing_program_skips(self):
        code, rep, err = self.run_runner(path=os.path.join(self.root, "empty"))
        self.assertEqual(code, 1, err)
        files = {r["file"]: r for r in rep["files"]}
        self.assertEqual(list(files), ["tools/test_a.py", "tools/test_b.py", "tools/test_c.py", "tools/test_verify_task.py",
                                       ".claude/hooks/test-hooks.js"])
        self.assertTrue(files["tools/test_a.py"]["ok"])
        self.assertTrue(files["tools/test_c.py"]["ok"], files["tools/test_c.py"])
        b = files["tools/test_b.py"]
        self.assertFalse(b["ok"])
        self.assertEqual(b["exit"], 1)
        self.assertIn("AssertionError: b broke", b["tail"][-1])
        self.assertIn("before", b["tail"])
        self.assertIn("powershell", files["tools/test_verify_task.py"]["skipped"])
        self.assertIn("no .claude/hooks/test-hooks.js", files[".claude/hooks/test-hooks.js"]["skipped"])
        self.assertEqual((rep["ok"], rep["failed"], rep["passed"]), (False, ["tools/test_b.py"], 2))
        self.assertEqual(set(rep["skipped"]), {"tools/test_verify_task.py", ".claude/hooks/test-hooks.js"})
        self.assertIn("tools/test_b.py FAILED", err)

    def test_green_tree_exits_0_and_writes_the_report(self):
        os.remove(os.path.join(self.root, "tools", "test_b.py"))
        out = os.path.join(self.root, "build", "report.json")
        code, rep, _ = self.run_runner("--out", out, "--no-hooks", path=os.path.join(self.root, "empty"))
        self.assertEqual(code, 0)
        self.assertTrue(rep["ok"])
        with open(out, encoding="utf-8") as f:
            self.assertEqual(json.load(f)["files"], rep["files"])
        self.assertNotIn(".claude/hooks/test-hooks.js", [r["file"] for r in rep["files"]])

    def test_hooks_without_node_skip_by_name(self):
        write(os.path.join(self.root, ".claude", "hooks", "test-hooks.js"), "process.exit(1)\n")
        code, rep, _ = self.run_runner(path=os.path.join(self.root, "empty"))
        hooks = [r for r in rep["files"] if r["file"] == ".claude/hooks/test-hooks.js"][0]
        self.assertEqual(hooks["skipped"], "needs node to run the hook tests")
        self.assertTrue(hooks["ok"])
        self.assertEqual(code, 1)  # test_b.py still fails

    def test_hooks_run_beside_the_files_when_node_is_there(self):
        import shutil
        if not shutil.which("node"):
            self.skipTest("no node")
        write(os.path.join(self.root, ".claude", "hooks", "test-hooks.js"), "console.log('1 passed, 1 failed'); process.exit(1)\n")
        for name in ("test_b.py", "test_verify_task.py"):
            os.remove(os.path.join(self.root, "tools", name))
        code, rep, _ = self.run_runner()
        hooks = [r for r in rep["files"] if r["file"] == ".claude/hooks/test-hooks.js"][0]
        self.assertEqual((hooks["ok"], hooks["exit"], hooks["tail"]), (False, 1, ["1 passed, 1 failed"]))
        self.assertEqual((code, rep["failed"]), (1, [".claude/hooks/test-hooks.js"]))


if __name__ == "__main__":
    unittest.main()
