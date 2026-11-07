"""Local A11 fixture inputs/protocol checks only. Never native/MySQL acceptance."""
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
from storage_lifecycle_acceptance import PLUGIN, VERSIONS, StorageLifecycleFixture, package_bytes


class StorageLifecycleInputsTest(unittest.TestCase):
    def test_deterministic_verified_inputs_and_schema_boundary(self):
        manifests = []
        digests = []
        import hashlib
        for version in VERSIONS:
            archive, manifest = package_bytes(version)
            self.assertEqual(archive, package_bytes(version)[0])
            self.assertEqual(manifest['pluginId'], PLUGIN)
            with zipfile.ZipFile(io.BytesIO(archive)) as zipped:
                self.assertEqual(set(zipped.namelist()), {'plugin.json', 'worker.py'})
                self.assertEqual(json.loads(zipped.read('plugin.json')), manifest)
                source = zipped.read('worker.py').decode()
                self.assertIn("VERSION = '" + version + "'", source)
                self.assertNotIn('pymysql', source)
                self.assertNotIn('sqlite', source)
            manifests.append(manifest)
            digests.append(hashlib.sha256(archive).hexdigest())
        self.assertEqual(manifests[0]['storage'], manifests[1]['storage'])
        self.assertNotEqual(manifests[0]['storage'], manifests[2]['storage'])
        self.assertEqual(len(set(digests)), 3)
        self.assertEqual({c['id'] for c in manifests[0]['requires']}, {'host.storage.get', 'host.storage.list', 'host.storage.put', 'host.storage.delete'})
        self.assertFalse(manifests[0]['provides'][0]['sideEffects'])
        self.assertTrue(manifests[0]['provides'][1]['sideEffects'])

    def test_refuses_non_ci_before_authority_sql(self):
        calls = []
        for ci, database in [('false', 'fcp_ci_123456abcdef'), ('true', 'production')]:
            with patch.dict(os.environ, GITHUB_ACTIONS=ci):
                with self.assertRaises(RuntimeError):
                    StorageLifecycleFixture(database, calls.append, Path('/tmp'), None)
        self.assertEqual(calls, [])

    def test_real_python_emits_only_controlled_storage_callbacks(self):
        """Actual process, explicit fake callback replies: transport unit, NOT storage evidence."""
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'worker.py'
            with zipfile.ZipFile(io.BytesIO(package_bytes()[0])) as zipped:
                path.write_bytes(zipped.read('worker.py'))
            process = subprocess.Popen([sys.executable, str(path)], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            def send(value):
                process.stdin.write(json.dumps(value) + '\n'); process.stdin.flush()
            def receive():
                import selectors
                with selectors.DefaultSelector() as selector:
                    selector.register(process.stdout, selectors.EVENT_READ)
                    if not selector.select(5): self.fail('Fixture protocol timeout')
                return json.loads(process.stdout.readline())
            try:
                send(dict(jsonrpc='2.0', id=1, method='handshake', params=dict(nonce='fixture')))
                self.assertEqual(receive()['result'], dict(nonce='fixture'))
                send(dict(jsonrpc='2.0', id=2, method='invoke', params=dict(capability='retention.write', input=dict(action='delete', id='deleted', expectedRevision=1, receiptKey='test-only'), context=dict(tenantId='t', principal=dict(id='u')))))
                callback = receive()
                self.assertEqual(callback['method'], 'fcp.call')
                self.assertEqual(callback['params']['dependency'], 'host.storage.delete')
                self.assertEqual(callback['params']['input'], dict(collection='entries', dataScope='class:retention-a', id='deleted', expectedRevision=1, idempotencyKey='test-only'))
                send(dict(jsonrpc='2.0', id=callback['id'], result=dict(revision=2, deleted=True)))
                result = receive()['result']
                self.assertEqual(result['result'], dict(revision=2, deleted=True))
                self.assertEqual(result['pluginPid'], process.pid)
                send(dict(jsonrpc='2.0', id=3, method='shutdown', params={}))
                self.assertTrue(receive()['result']['ok'])
                self.assertEqual(process.wait(timeout=5), 0)
            finally:
                if process.poll() is None: process.kill(); process.wait(timeout=5)
                process.stdin.close(); process.stdout.close(); process.stderr.close()

    def test_cli_is_explicitly_not_run(self):
        result = subprocess.run([sys.executable, str(Path(__file__).parent / 'storage_lifecycle_acceptance.py')], capture_output=True, text=True, timeout=10)
        self.assertEqual(result.returncode, 2)
        self.assertEqual(json.loads(result.stdout)['status'], 'NOT_RUN')


if __name__ == '__main__':
    unittest.main()
