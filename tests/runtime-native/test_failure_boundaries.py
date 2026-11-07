"""Fixture/schema/real-process tests ONLY; never native/MySQL acceptance."""
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile
sys.path.insert(0, str(Path(__file__).parent))
from failure_boundaries import FailureBoundaryFixture, package_bytes, PLUGIN


class FailureBoundariesTest(unittest.TestCase):
    def test_guard_precedes_all_seed_sql(self):
        calls = []
        for ci, database in [('false', 'fcp_ci_123456abcdef'), ('true', 'production')]:
            with patch.dict(os.environ, GITHUB_ACTIONS=ci):
                with self.assertRaises(RuntimeError):
                    FailureBoundaryFixture(database, calls.append, Path('/tmp'), None)
        with patch.dict(os.environ, GITHUB_ACTIONS='true'):
            with self.assertRaises(RuntimeError):
                FailureBoundaryFixture('fcp_ci_123456abcdef', calls.append, Path('/tmp'), None)
        self.assertEqual(calls, [])

    def test_deterministic_minimal_package(self):
        archive, manifest = package_bytes()
        self.assertEqual(archive, package_bytes()[0])
        self.assertEqual(manifest['pluginId'], PLUGIN)
        self.assertEqual(manifest['publisher'], 'test')
        self.assertEqual(manifest['requires'], [])
        self.assertFalse(manifest['provides'][0]['sideEffects'])
        with zipfile.ZipFile(io.BytesIO(archive)) as zipped:
            self.assertEqual(set(zipped.namelist()), {'plugin.json', 'runtime.py'})
            self.assertEqual(json.loads(zipped.read('plugin.json')), manifest)

    def test_actual_fixture_process_echoes_supplied_server_context(self):
        # This checks protocol fixture quality, NOT the trustworthiness of HTTP identity.
        request = dict(jsonrpc='2.0', id='one', method='invoke', params=dict(input=dict(message='unit'),
                       context=dict(tenantId='unit-tenant', principal=dict(kind='USER', id='unit-user'))))
        stop = dict(jsonrpc='2.0', id='two', method='shutdown', params={})
        process = subprocess.run([sys.executable, str(Path(__file__).parent/'plugins/runtime.py')],
                                 input=json.dumps(request)+'\n'+json.dumps(stop)+'\n', text=True,
                                 capture_output=True, timeout=10, check=True)
        reply = json.loads(process.stdout.splitlines()[0])['result']
        self.assertEqual(reply['tenantId'], 'unit-tenant')
        self.assertEqual(reply['principalId'], 'unit-user')
        self.assertEqual(reply['message'], 'unit')
        self.assertIsInstance(reply['pluginPid'], int)

    def test_cli_reports_not_run(self):
        result = subprocess.run([sys.executable, str(Path(__file__).parent/'failure_boundaries.py')],
                                capture_output=True, text=True, timeout=10)
        self.assertEqual(result.returncode, 2)
        self.assertEqual(json.loads(result.stdout)['status'], 'NOT_RUN')

    def test_fault_fixture_does_not_fake_jdbc_results(self):
        source = (Path(__file__).parent/'failure-fixtures/MysqlFailureBoundariesTest.java').read_text()
        self.assertIn('DriverManager.getConnection(url,user,password)', source)
        self.assertIn('Object value=invoke(real,m,a);', source)
        self.assertIn('AFTER real MySQL commit', source)
        self.assertIn('AFTER real MySQL DDL step', source)
        self.assertNotIn('new Class<?>[]{ResultSet.class}', source)
        self.assertIn('serverPowerLoss","NOT_RUN', source)

    def test_revocation_gate_is_explicitly_after_real_audit_write(self):
        # Fixture design invariant only. This does not run MySQL or prove rollback.
        source = (Path(__file__).parent/'failure-fixtures/MysqlFailureBoundariesTest.java').read_text()
        gate = source.split('static final class PreCommitGateSource', 1)[1].split('static Map<String,Object> declaration()', 1)[0]
        self.assertIn('Object result=invoke(statement,sm,sa);', gate)
        self.assertLess(gate.index('Object result=invoke(statement,sm,sa);'), gate.index('staged.countDown();'))
        self.assertIn('real.getAutoCommit()', gate)
        self.assertIn('count(real,"SELECT COUNT(*) FROM fcp_plugin_receipt', gate)
        self.assertIn('release.await(45,TimeUnit.SECONDS)', gate)
        self.assertIn('List.of(TrustedContext.Kind.USER,TrustedContext.Kind.TASK)', source)
        self.assertIn('nativeDrainingRevocation","NOT_RUN', source)


if __name__ == '__main__':
    unittest.main()
