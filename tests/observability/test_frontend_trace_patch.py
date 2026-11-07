"""Pinned local source required; no network or fabricated upstream commits."""
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('frontend_trace', ROOT / 'tools/native_trace_frontend.py')
helper = importlib.util.module_from_spec(spec)
spec.loader.exec_module(helper)

class FrontendTracePatchTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='fcp-trace-patch-')
        self.addCleanup(self.temp.cleanup)
        self.work = Path(self.temp.name)

    def checkout(self, name):
        source = os.environ.get('FCP_TRACE_' + name.upper() + '_SOURCE')
        if not source:
            self.skipTest('Exact local source required: FCP_TRACE_' + name.upper() + '_SOURCE')
        pin = json.loads((ROOT / 'integration/request-trace/manifest.json').read_text())['upstreams'][name]
        project = self.work / name
        # A partial checkout of genuine Git objects, never a synthetic upstream commit.
        subprocess.run(['git', 'clone', '--shared', '--no-checkout', source, str(project)], check=True, capture_output=True)
        subprocess.run(['git', 'reset', '--soft', pin['commit']], cwd=project, check=True, capture_output=True)
        data = subprocess.check_output(['git', 'show', f"{pin['commit']}:{pin['path']}"], cwd=project)
        target = project / pin['path']
        target.parent.mkdir(parents=True)
        target.write_bytes(data)
        return project, target

    def test_admin_pinned_apply_and_reapply_rejected(self):
        project, target = self.checkout('admin')
        helper.apply_frontend_trace(project, 'admin')
        self.assertIn('withResponseTrace(error, error.response)', target.read_text())
        with self.assertRaisesRegex(RuntimeError, 'drift'):
            helper.apply_frontend_trace(project, 'admin')

    def test_teacher_pinned_apply(self):
        project, target = self.checkout('teacher')
        helper.apply_frontend_trace(project, 'teacher')
        self.assertIn('responseError(response.data.msg', target.read_text())

    def test_modified_upstream_fails_before_write(self):
        project, target = self.checkout('admin')
        target.write_bytes(target.read_bytes() + b'\n// upstream change\n')
        before = target.read_bytes()
        with self.assertRaisesRegex(RuntimeError, 'drift'):
            helper.apply_frontend_trace(project, 'admin')
        self.assertEqual(before, target.read_bytes())

    def test_modified_patch_fails_before_write(self):
        project, target = self.checkout('teacher')
        bundle = self.work / 'bundle'
        shutil.copytree(ROOT / 'integration/request-trace', bundle / 'integration/request-trace')
        shutil.copy(ROOT / 'upstreams.lock.json', bundle / 'upstreams.lock.json')
        patch = bundle / 'integration/request-trace/teacher.patch'
        patch.write_bytes(patch.read_bytes() + b'\n')
        before = target.read_bytes()
        original = helper.ROOT
        try:
            helper.ROOT = bundle
            with self.assertRaisesRegex(RuntimeError, 'drift'):
                helper.apply_frontend_trace(project, 'teacher')
        finally:
            helper.ROOT = original
        self.assertEqual(before, target.read_bytes())

    def test_unknown_frontend_rejected(self):
        with self.assertRaisesRegex(RuntimeError, 'Unknown'):
            helper.apply_frontend_trace(self.work, '../backend')

if __name__ == '__main__':
    unittest.main()
