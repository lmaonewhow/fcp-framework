"""Fixture determinism and executable integration wiring, not a substitute for native acceptance."""
import hashlib
import io
from pathlib import Path
import unittest
import zipfile
from http_contract import package_bytes, PLUGINS

ROOT = Path(__file__).resolve().parents[2]


class ContractWiringTest(unittest.TestCase):
    def test_same_deterministic_archives_have_declared_dependency(self):
        for language in ('python', 'node'):
            data, manifest = package_bytes(language)
            self.assertEqual(hashlib.sha256(data).digest(), hashlib.sha256(package_bytes(language)[0]).digest())
            with zipfile.ZipFile(io.BytesIO(data)) as archive:
                self.assertEqual(set(archive.namelist()), {'plugin.json', manifest['runtime']['entry']})
            self.assertEqual(manifest['pluginId'], 'portability.'+language)
            self.assertEqual(bool(manifest['requires']), language == 'node')
        self.assertEqual(package_bytes('node')[1]['dependencies'][0]['pluginId'], PLUGINS[0])

    def test_both_real_hosts_call_shared_suite(self):
        development = (ROOT/'tests/runtime-http/test_portability.py').read_text()
        native = (ROOT/'tests/runtime-native/native_runtime_acceptance.py').read_text()
        for source in (development, native):
            self.assertIn('from http_contract import run_http_contract', source)
            self.assertIn('run_http_contract(', source)
        self.assertIn('self.login_fixture.login(browser,base,role)', native)
        self.assertLess(native.index('admin.evidence()'), native.index("self.report['portability']=run_http_contract(adapter)"))
        self.assertIn("NativeAdapter(admin,", native)

    def test_both_hosts_execute_same_student_business_contract(self):
        for path in ('tests/runtime-portability/development_business.py', 'tests/runtime-exchange/native_exchange_acceptance.py'):
            source = (ROOT/path).read_text()
            self.assertIn('from business_contract import run_business_contract', source)
            self.assertIn('run_business_contract(', source)
        self.assertIn("NativeModule('developmentBusiness'", (ROOT/'tools/ci_native.py').read_text())

    def test_retention_probe_accepts_only_explicit_business_fixture_identifier(self):
        from business_contract import CSV
        import csv
        rows = list(csv.DictReader(io.StringIO(CSV)))
        self.assertEqual(len(rows), 1)
        probe = (ROOT/'tests/runtime-exchange/NativeExportRetentionProbe.java').read_text()
        self.assertIn('"'+rows[0]['student_code']+'"', probe)
        self.assertIn('r.keySet().equals(Set.of("student_code"))', probe)

    def test_build_and_install_use_same_policy(self):
        self.assertIn('PluginDependencyPolicy.verify(contents)', (ROOT/'core/src/main/java/fcp/plugin/PackageInstaller.java').read_text())
        self.assertIn('fcp.plugin.PluginDependencyPolicy', (ROOT/'tools/check_plugin_dependencies.py').read_text())
        self.assertIn('check_plugin_directory(', (ROOT/'tools/package_exchange_fixtures.py').read_text())


class RevocationEvidenceTest(unittest.TestCase):
    def test_live_revocation_targets_provider_without_enablement_requirements(self):
        import inspect
        from http_contract import run_http_contract
        source = inspect.getsource(run_http_contract)
        self.assertFalse(package_bytes('python')[1]['requires'])
        self.assertTrue(package_bytes('node')[1]['requires'])
        self.assertIn('adapter.set_grant(PLUGINS[0], False)', source)
        self.assertIn("'PERMISSION_DENIED', '/fcp/capabilities/'+exported(PLUGINS[0])", source)
        self.assertIn('pages(revoked) == {PLUGINS[1]}', source)

    def test_response_projection_retains_only_safe_diagnostic_fields(self):
        from http_contract import response_evidence, ContractAssertion
        response = dict(code=503, msg='secret message', token='secret token',
                        data=dict(fcpCode='PLUGIN_DISABLED', traceId='a'*32,
                                  principal='secret principal', body='secret body'))
        evidence = response_evidence(503, response)
        self.assertEqual(evidence, dict(httpStatus=503, code=503,
                                       fcpCode='PLUGIN_DISABLED', traceId='a'*32))
        error = ContractAssertion('revocation', [dict(name='prior check', status='PASS')], evidence)
        self.assertNotIn('secret', str(error))
        self.assertNotIn('secret', str(error.report))
        self.assertEqual(error.report['checks'][0]['status'], 'PASS')
        self.assertEqual(error.report['status'], 'FAIL')

    def test_unknown_uppercase_and_nonstring_codes_never_leak(self):
        from http_contract import response_evidence, ContractAssertion
        for value in ('SECRET_TOKEN_VALUE', 'A'*10000, 403, True, None,
                      ['PERMISSION_DENIED'], {'secret': 'SECRET_TOKEN_VALUE'}):
            with self.subTest(value_type=type(value).__name__):
                evidence = response_evidence(403, {'code': 403, 'data': {'fcpCode': value}})
                self.assertIsNone(evidence['fcpCode'])
                error = ContractAssertion('revocation', [], evidence)
                self.assertNotIn('SECRET_TOKEN_VALUE', str(error))
                self.assertNotIn('SECRET_TOKEN_VALUE', str(error.report))
                self.assertNotIn('A'*100, str(error.report))

    def test_known_a35_and_recovery_codes_remain_exact(self):
        from http_contract import response_evidence
        expected = ('HOST_IMPLEMENTATION_DEPENDENCY', 'NOT_INSTALLED', 'CAPABILITY_NOT_GRANTED',
                    'INVALID_ARGUMENT', 'REVISION_CONFLICT', 'CONFIRMATION_REQUIRED',
                    'INVALID_INPUT', 'VERSION_CONFLICT', 'PERMISSION_DENIED', 'PLUGIN_DISABLED',
                    'OPERATION_PENDING', 'GRANT_REVISION_CONFLICT', 'NOT_READY')
        for code in expected:
            self.assertEqual(response_evidence(409, {'data': {'fcpCode': code}})['fcpCode'], code)

    def test_malformed_codes_and_trace_never_leak(self):
        from http_contract import response_evidence
        for reply in (None, [], {'code': {'secret': 1}, 'data': []},
                      {'code': 'secret', 'data': {'fcpCode': 'secret value', 'traceId': 'secret'}}):
            self.assertEqual(response_evidence(403, reply), dict(httpStatus=403, code=None, fcpCode=None))
        self.assertNotIn('traceId', response_evidence(403, {'data': {'traceId': '0'*32}}))
        self.assertIsNone(response_evidence(True, {'code': True})['httpStatus'])


if __name__ == '__main__':
    unittest.main()
