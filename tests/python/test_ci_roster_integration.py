"""Source/plan gates only; no native database, browser or PII policy claim."""
import ast
from contextlib import redirect_stderr
import hashlib
import io
import json
from pathlib import Path
import sys
import tempfile
import subprocess
import time
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools'))
import ci_education
from ci_native import parse_args
import ci_roster_integration


class RosterCompositionGuards(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.sql = self.root / 'extension.sql'
        self.menu = self.root / 'menu.sql'
        self.lock = self.root / 'sources.lock.json'
        self.sql.write_text('CREATE TABLE ed_test(id BIGINT);\n')
        self.menu.write_text('-- no grant\n')
        self.lock_data = dict(format=2, objects=[], menus=[], sha256={p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in (self.sql, self.menu)})
        self.lock.write_text(json.dumps(self.lock_data))

    def read(self):
        return ci_education.reviewed_extension(self.root, self.lock, (self.sql, self.menu))

    def test_roster_requires_fresh_education_and_genuine_ingestion(self):
        self.assertTrue(parse_args(['--ingestion', '--education', '--roster']).roster)
        for args in (['--roster'], ['--ingestion', '--roster'], ['--education', '--roster'], ['--ingestion', '--education', '--roster', '--plugin-releases']):
            with self.subTest(args=args), redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                parse_args(args)
        self.assertFalse(parse_args(['--ingestion', '--education']).roster)

    def test_login_batches_wait_full_native_window_without_weakening_limit(self):
        for stage in ('foundation-only', 'roster-http', 'roster-browser'):
            with patch.object(time, 'sleep') as sleep, patch.object(time, 'monotonic', side_effect=[10, 70.025]):
                evidence = ci_roster_integration.wait_native_login_window(stage)
            sleep.assert_called_once_with(60)
            self.assertEqual(evidence, dict(stage=stage, waitSeconds=60.025, nativeRateLimitChanged=False))
        with patch.object(time, 'sleep'), patch.object(time, 'monotonic', side_effect=[10, 69.9]), self.assertRaises(RuntimeError):
            ci_roster_integration.wait_native_login_window('roster-browser')
        with self.assertRaises(ValueError):
            ci_roster_integration.wait_native_login_window('unknown')

    def test_reviewed_lock_is_bound_to_exact_named_sources(self):
        self.assertEqual(self.read(), self.lock_data)
        self.lock_data['sha256']['outside.sql'] = '0' * 64
        self.lock.write_text(json.dumps(self.lock_data))
        with self.assertRaisesRegex(ValueError, 'Invalid'):
            self.read()

    def test_source_drift_missing_or_symlink_refuses(self):
        self.sql.write_text(self.sql.read_text() + '-- changed\n')
        with self.assertRaisesRegex(ValueError, 'differs'):
            self.read()
        self.sql.unlink()
        with self.assertRaisesRegex(ValueError, 'differs'):
            self.read()
        self.sql.symlink_to(self.menu)
        with self.assertRaisesRegex(ValueError, 'differs'):
            self.read()

    def test_lock_symlink_or_missing_refuses(self):
        self.lock.unlink()
        with self.assertRaisesRegex(ValueError, 'required'):
            self.read()
        self.lock.symlink_to(self.menu)
        with self.assertRaisesRegex(ValueError, 'required'):
            self.read()

    def test_objects_and_menus_must_be_explicit(self):
        for key in ('objects', 'menus'):
            value = dict(self.lock_data)
            value.pop(key)
            self.lock.write_text(json.dumps(value))
            with self.subTest(key=key), self.assertRaisesRegex(ValueError, 'required'):
                self.read()

    def test_foundation_only_plan_does_not_adopt_roster(self):
        with patch.dict('os.environ', {'GITHUB_ACTIONS': 'true'}):
            plan = ci_education.schema_plan('fcp_ci_012345abcdef')
        self.assertNotIn('roster', plan)
        self.assertEqual(len(plan['sources']), 3)
        self.assertEqual([item['name'] for item in plan['expectedExtension']], ['ed_host_education_role'])
        self.assertEqual(len(plan['expectedMenus']), 8)
        self.assertFalse(any('roster' in item['path'] for item in plan['sources']))


    def test_artifact_harness_rejects_zero_exit_or_unrelated_failure(self):
        jar = self.root / 'app.jar'; jar.write_bytes(b'unit-only artifact, not a real native build')
        private = self.root / 'private'; private.mkdir()
        config = private / 'fcp-runtime.properties'
        config.write_text('FCP_TEST_SECRET=synthetic-private-test-value\n'); config.chmod(0o600)
        manifest = hashlib.sha256(jar.read_bytes()).hexdigest()
        for code, log in [(0, 'ROSTER_EXECUTION_ARTIFACT_MISMATCH'), (1, 'unrelated startup failure')]:
            output = self.root / ('rejected-' + str(code))
            class FakeProcess:
                def wait(self, timeout): return code
                def poll(self): return code
            def launch(command, **kwargs):
                kwargs['stdout'].write(log + '\n'); kwargs['stdout'].flush()
                return FakeProcess()
            with patch.dict('os.environ', {'GITHUB_ACTIONS': 'true'}), patch.object(ci_roster_integration.subprocess, 'Popen', side_effect=launch):
                # check_output also uses Popen, so retain a separate source stub.
                with patch.object(ci_roster_integration.subprocess, 'check_output', return_value='unit-source\n'):
                    with self.assertRaises(RuntimeError):
                        ci_roster_integration.verify_artifact_mismatch(jar=jar, private_dir=private, output=output, manifest=manifest)
            self.assertEqual(json.loads((output / 'result.json').read_text())['status'], 'FAIL')
            (private / 'roster-artifact-mismatch-startup.log').unlink()

    def test_artifact_harness_requires_exact_digest_nonzero_and_redacts_private_values(self):
        jar = self.root / 'app.jar'; jar.write_bytes(b'unit-only artifact, not a real native build')
        private = self.root / 'private'; private.mkdir()
        config = private / 'fcp-runtime.properties'; config.write_text('FCP_TEST_SECRET=synthetic-private-test-value\n'); config.chmod(0o600)
        manifest = hashlib.sha256(jar.read_bytes()).hexdigest(); output = self.root / 'exact'
        class FakeProcess:
            def wait(self, timeout): return 1
            def poll(self): return 1
        def launch(command, **kwargs):
            # Match the actual native launch prefix, rather than a weaker negative-only one.
            tree = ast.parse((ROOT / 'tools/ci_native.py').read_text())
            start = next(node for node in ast.walk(tree) if isinstance(node, ast.FunctionDef) and node.name == 'start_host')
            actual = next(node for node in ast.walk(start) if isinstance(node, ast.Call)
                          and isinstance(node.func, ast.Attribute) and node.func.attr == 'Popen')
            prefix = [ast.literal_eval(value) for value in actual.args[0].elts[:5]]
            self.assertEqual(prefix, ['java', '-Duser.timezone=UTC', '-Xms128m', '-Xmx1024m', '-jar'])
            self.assertEqual(command[:5], prefix)
            self.assertIn('--FCP_EDUCATION_ROSTER_MANIFEST_DIGEST=' + ('0' if manifest[0] != '0' else '1') + manifest[1:], command)
            kwargs['stdout'].write('ROSTER_EXECUTION_ARTIFACT_MISMATCH synthetic-private-test-value\n'); kwargs['stdout'].flush()
            return FakeProcess()
        with patch.dict('os.environ', {'GITHUB_ACTIONS': 'true'}), patch.object(ci_roster_integration.subprocess, 'check_output', return_value='unit-source\n'), patch.object(ci_roster_integration.subprocess, 'Popen', side_effect=launch):
            result = ci_roster_integration.verify_artifact_mismatch(jar=jar, private_dir=private, output=output, manifest=manifest)
        self.assertEqual(result['status'], 'PASS')  # Harness unit only, not native execution evidence.
        self.assertNotIn('synthetic-private-test-value', (output / 'startup-redacted.log').read_text())
        with patch.dict('os.environ', {'GITHUB_ACTIONS': 'false'}), self.assertRaises(ValueError):
            ci_roster_integration.verify_artifact_mismatch(jar=jar, private_dir=private, output=self.root / 'blocked', manifest=manifest)

    def test_jdbc_timeout_retains_redacted_bytes_and_is_not_a_pass(self):
        # Process unit double verifies error recording, not MySQL execution.
        url = 'jdbc:mysql://127.0.0.1:33306/fcp_ci_012345abcdef?sslMode=DISABLED'
        password = 'synthetic-roster-private-value'
        output = self.root / 'probe-evidence'
        partial = b'last real process line synthetic-roster-private-value\n'
        effects = [subprocess.CompletedProcess(['javac'], 0, '', ''),
                   subprocess.TimeoutExpired(['java'], 180, output=partial, stderr=b'lock wait incomplete')]
        with patch.dict('os.environ', {'GITHUB_ACTIONS': 'true'}), \
             patch.object(ci_roster_integration.subprocess, 'check_output', return_value='unit-source\n'), \
             patch.object(ci_roster_integration.subprocess, 'run', side_effect=effects), \
             self.assertRaises(subprocess.TimeoutExpired):
            ci_roster_integration.run_roster_native_probe(database='fcp_ci_012345abcdef', url=url,
                user='unit-user', password=password, manifest='a' * 64,
                driver=self.root/'driver.jar', output=output, root=self.root)
        report = json.loads((output/'result.json').read_text())
        self.assertEqual(report['status'], 'FAIL')
        self.assertEqual(report['errorType'], 'TimeoutExpired')
        self.assertEqual(report['commands'][-1]['status'], 'TIMEOUT')
        self.assertIsNone(report['commands'][-1]['exitCode'])
        log = (output/'run.log').read_text()
        self.assertIn('last real process line', log)
        self.assertIn('lock wait incomplete', log)
        self.assertNotIn(password, log)


if __name__ == '__main__':
    unittest.main()
