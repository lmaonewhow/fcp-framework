"""Execute A35 common suite against a real DevHost JVM and both language processes."""
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT/'tools'))
sys.path.insert(0, str(ROOT/'tests/runtime-portability'))
from native_client import NativeClient
from host_adapters import DevelopmentAdapter
from http_contract import run_http_contract


class PortabilityHttpTest(unittest.TestCase):
    def test_same_http_semantics(self):
        with tempfile.TemporaryDirectory(prefix='fcp-portability-') as temporary:
            work = Path(temporary)
            node = shutil.which('node')
            self.assertIsNotNone(node, 'Node is required; portability cannot be marked PASS without it')
            token = secrets.token_hex(32)
            identity = dict(token=token, userId='portability-admin', tenantId='t1', scopes=['tenant:t1'],
                            permissions=['fcp:plugin:install', 'fcp:plugin:manage', 'fcp:plugin:view', 'fcp:plugin:echo'], grants=[])
            config = dict(development=True, trustedPublishers=['test'],
                          executables=dict(python=sys.executable, node=node), identities=[identity])
            private = work/'identities.json'
            private.write_text(json.dumps(config)); private.chmod(0o600)
            def grant(plugin, allow):
                identity['grants'] = [g for g in identity['grants'] if g['plugin'] != plugin]
                if allow:
                    identity['grants'].append(dict(plugin=plugin, capability='echo', operation='invoke', dataScope='tenant:t1', permission='fcp:plugin:echo'))
                private.write_text(json.dumps(config))
            with socket.socket() as sock:
                sock.bind(('127.0.0.1', 0)); port = sock.getsockname()[1]
            with (work/'host.log').open('w') as log:
                process = subprocess.Popen(['java', '-cp', str(ROOT/'build/runtime-http'), 'dev.fcp.dev.DevHost',
                                            '--development', str(private), str(work/'state'), str(port)], stdout=log, stderr=log)
                try:
                    client = NativeClient('http://127.0.0.1:'+str(port), token, 'development-test')
                    for _ in range(150):
                        try:
                            client.data('/fcp/plugins'); break
                        except OSError:
                            self.assertIsNone(process.poll(), 'Development JVM exited before HTTP readiness')
                            time.sleep(.05)
                    else:
                        self.fail('Development JVM did not become ready')
                    result = run_http_contract(DevelopmentAdapter(client, grant, 't1', 'portability-admin'))
                    self.assertEqual(result['status'], 'PASS')
                    self.assertIsNone(process.poll(), 'Shared suite must not restart the host')
                    report = ROOT/'reports/runtime-portability'
                    report.mkdir(parents=True, exist_ok=True)
                    (report/'development-http.json').write_text(json.dumps(result, indent=2)+'\n')
                finally:
                    process.terminate()
                    try:
                        process.wait(timeout=15)
                    except subprocess.TimeoutExpired:
                        process.kill(); process.wait()

    def test_production_refuses_development_authentication(self):
        result = subprocess.run(['java', '-cp', str(ROOT/'build/runtime-http'), 'dev.fcp.dev.DevHost',
                                 '--development', '/does-not-exist', '/does-not-exist', '0'],
                                env={**os.environ, 'FCP_ENV': 'production'}, capture_output=True, text=True, timeout=10)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('forbidden in production', result.stderr)
        self.assertNotIn('listening', result.stdout)


if __name__ == '__main__':
    unittest.main()
