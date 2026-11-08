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
from http_contract import run_http_contract, ContractAssertion


class PortabilityHttpTest(unittest.TestCase):
    def test_same_http_semantics(self):
        self.run_contract()

    def test_revocation_remains_permission_denied_after_actual_recovery(self):
        self.run_contract(wait_for_recovery=True)

    def test_required_approval_revocation_cuts_off_admission_after_recovery(self):
        self.run_contract(wait_for_recovery=True, revoke_required=True)

    def run_contract(self, wait_for_recovery=False, revoke_required=False):
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
                if not allow and revoke_required:
                    # Negative control reproduces the former target-selection defect.
                    plugin = 'portability.node'
                identity['grants'] = [g for g in identity['grants'] if g['plugin'] != plugin]
                if allow:
                    identity['grants'].append(dict(plugin=plugin, capability='echo', operation='invoke', dataScope='tenant:t1', permission='fcp:plugin:echo'))
                private.write_text(json.dumps(config))
                if not allow and wait_for_recovery:
                    # A completion already in flight can still use pre-write grants.
                    # Take the boundary AFTER writing, then observe two completions:
                    # PluginRecovery has one thread and schedules the next tick only
                    # in the previous tick's finally, so the second must read new grants.
                    offset = (work/'host.log').stat().st_size
                    deadline = time.monotonic()+20
                    while time.monotonic() < deadline:
                        lines = (work/'host.log').read_text()[offset:].splitlines()
                        events = [json.loads(line) for line in lines if line.startswith('{') and line.endswith('}')]
                        recovered = [event for event in events if event.get('event') == 'task.recovery']
                        if len(recovered) >= 2:
                            self.assertEqual(recovered[-1]['outcome'], 'ERROR' if revoke_required else 'OK')
                            if revoke_required:
                                self.assertIn('FCP recovery failed: CAPABILITY_NOT_GRANTED', '\n'.join(lines))
                            break
                        time.sleep(.05)
                    else:
                        self.fail('Fewer than two real recovery completions observed after grant write')
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
                    adapter = DevelopmentAdapter(client, grant, 't1', 'portability-admin')
                    if revoke_required:
                        with self.assertRaises(ContractAssertion) as caught:
                            run_http_contract(adapter)
                        self.assertEqual(caught.exception.report['failedCheck'], 'live permission revocation semantic code')
                        self.assertEqual(caught.exception.report['response']['fcpCode'], 'PLUGIN_DISABLED')
                        self.assertGreaterEqual(caught.exception.report['response']['httpStatus'], 400)
                        report = ROOT/'reports/runtime-portability'
                        report.mkdir(parents=True, exist_ok=True)
                        (report/'required-approval-negative-control.json').write_text(json.dumps(dict(
                            status='PASS', scope='real development HTTP negative control; not native evidence',
                            observedContractFailure=caught.exception.report), indent=2)+'\n')
                        return
                    result = run_http_contract(adapter)
                    self.assertEqual(result['status'], 'PASS')
                    self.assertIsNone(process.poll(), 'Shared suite must not restart the host')
                    report = ROOT/'reports/runtime-portability'
                    report.mkdir(parents=True, exist_ok=True)
                    (report/('development-http-after-recovery.json' if wait_for_recovery else 'development-http.json')).write_text(json.dumps(result, indent=2)+'\n')
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
