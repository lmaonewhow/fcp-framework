"""Regression tests for an offline, source-controlled native dependency pin."""
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('ingestion_dependency_pin', ROOT / 'tools/check_ingestion_lock.py')
PIN = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PIN)


class DependencyPinTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        (self.root / 'locks').mkdir()
        for path in ('locks/admin-package-lock.json', 'upstreams.lock.json'):
            shutil.copyfile(ROOT / path, self.root / path)

    def test_exact_accepted_lock(self):
        result = PIN.verify(self.root)
        self.assertEqual(result['status'], 'PASS')
        self.assertEqual(result['sha256'], PIN.EXPECTED_SHA256)

    def test_missing_lock_fails(self):
        (self.root / 'locks/admin-package-lock.json').unlink()
        with self.assertRaises(OSError):
            PIN.verify(self.root)

    def test_modified_bytes_fail(self):
        with (self.root / 'locks/admin-package-lock.json').open('ab') as stream:
            stream.write(b'\n')
        with self.assertRaisesRegex(ValueError, 'differs'):
            PIN.verify(self.root)

    def test_upstream_drift_fails(self):
        path = self.root / 'upstreams.lock.json'
        value = json.loads(path.read_text())
        value['upstreams']['admin']['commit'] = '0' * 40
        path.write_text(json.dumps(value))
        with self.assertRaisesRegex(ValueError, 'upstream changed'):
            PIN.verify(self.root)

    def test_cli_missing_lock_is_nonzero_and_machine_readable(self):
        (self.root / 'locks/admin-package-lock.json').unlink()
        process = subprocess.run([sys.executable, str(ROOT / 'tools/check_ingestion_lock.py'),
                                  '--root', str(self.root)], capture_output=True, text=True, timeout=10)
        self.assertEqual(process.returncode, 2)
        self.assertEqual(json.loads(process.stdout)['status'], 'FAIL')

    def test_native_ci_does_not_need_old_artifacts(self):
        workflow = (ROOT / '.github/workflows/native-ingestion.yml').read_text()
        self.assertNotIn('actions/download-artifact', workflow)
        self.assertNotIn('run-id:', workflow)
        self.assertIn('python3 tools/check_ingestion_lock.py', workflow)
        self.assertNotIn('contents: write', workflow)

    def test_critical_sources_trigger_native_acceptance(self):
        workflow = (ROOT / '.github/workflows/native-ingestion.yml').read_text()
        for path in ('core/**', 'business/**', 'host/**', 'runtime/**', 'tools/**',
                     'tests/**', 'integration/**', 'locks/**', 'upstreams.lock.json'):
            with self.subTest(path=path):
                self.assertIn("'" + path + "'", workflow)


if __name__ == '__main__':
    unittest.main()
