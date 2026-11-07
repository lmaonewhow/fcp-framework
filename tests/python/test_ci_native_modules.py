"""Pure orchestration tests, not native/MySQL/browser acceptance evidence."""
from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT/'tools'))
from ci_native_modules import NativeModule, run_native_modules


def module(name, verify, summary=lambda result: dict(status=result['status'], totalChecks=result['totalChecks'])):
    return NativeModule(name, verify, summary)


def passing():
    return dict(status='PASS', totalChecks=2)


class NativeModuleOrchestrationTests(unittest.TestCase):
    def run_modules(self, report, modules, **kwargs):
        options = dict(prerequisites_passed=True, host_alive=lambda: True, secrets_provider=lambda: [])
        options.update(kwargs)
        run_native_modules(report, modules, **options)

    def test_first_failure_runs_each_later_module_once_but_overall_remains_fail(self):
        calls = []
        report = dict(status='PASS', build=dict(buildId='verified'))
        def first():
            calls.append('runtime')
            raise AssertionError('Original UI assertion')
        def later(name):
            def verify():
                calls.append(name)
                self.assertEqual(report['status'], 'FAIL')
                return passing()
            return verify
        self.run_modules(report, [module('runtime', first), module('a11', later('a11')),
                                  module('boundary', later('boundary')), module('exchange', later('exchange'))])
        self.assertEqual(calls, ['runtime', 'a11', 'boundary', 'exchange'])
        self.assertEqual(report['status'], 'FAIL')
        self.assertEqual(report['runtime']['status'], 'FAIL')
        self.assertEqual(report['runtime']['errorType'], 'AssertionError')
        self.assertEqual(report['exchange']['status'], 'PASS')
        self.assertEqual(report['nativeModuleFailures'], ['runtime'])
        self.assertEqual(report['nativeModulesNotRun'], [])
        self.assertNotIn('Original UI assertion', report['error'])

    def test_success_never_overwrites_preexisting_overall_failure(self):
        report = dict(status='FAIL', error='Earlier prerequisite issue')
        self.run_modules(report, [module('later', passing)])
        self.assertEqual(report['later']['status'], 'PASS')
        self.assertEqual(report['status'], 'FAIL')
        self.assertEqual(report['error'], 'Earlier prerequisite issue')

    def test_all_pass_preserves_existing_pass(self):
        report = dict(status='PASS')
        self.run_modules(report, [module('first', passing), module('second', passing)])
        self.assertEqual(report['status'], 'PASS')
        self.assertEqual(report['nativeModuleFailures'], [])
        self.assertNotIn('error', report)

    def test_jvm_stops_during_first_module_and_later_modules_do_not_run(self):
        live = True
        calls = []
        def first():
            nonlocal live
            calls.append('first')
            live = False
            return passing()
        def later():
            calls.append('later')
            return passing()
        report = dict(status='PASS')
        self.run_modules(report, [module('first', first), module('later', later)], host_alive=lambda: live)
        self.assertEqual(calls, ['first'])
        self.assertEqual(report['first']['status'], 'FAIL')
        self.assertFalse(report['first']['nativeJvmAliveAfter'])
        self.assertEqual(report['later'], dict(status='NOT_RUN', reason='NATIVE_JVM_NOT_RUNNING'))
        self.assertEqual(report['status'], 'FAIL')
        self.assertEqual(report['nativeModulesNotRun'], ['later'])

    def test_jvm_is_already_dead_so_no_module_runs(self):
        report = dict(status='PASS')
        self.run_modules(report, [module('first', lambda: self.fail('Should not run'))], host_alive=lambda: False)
        self.assertEqual(report['first']['status'], 'NOT_RUN')
        self.assertEqual(report['status'], 'FAIL')

    def test_host_loss_is_latched_even_if_a_later_probe_would_recover(self):
        probes = iter([True, False, True, True])
        calls = []
        report = dict(status='PASS')
        self.run_modules(report, [module('first', passing), module('second', lambda: calls.append('unexpected'))],
                         host_alive=lambda: next(probes))
        self.assertEqual(calls, [])
        self.assertEqual(report['second']['status'], 'NOT_RUN')
        self.assertEqual(report['status'], 'FAIL')

    def test_failed_baseline_blocks_all_independent_modules(self):
        report = dict(status='PASS')
        self.run_modules(report, [module('first', lambda: self.fail('Should not run'))], prerequisites_passed=False)
        self.assertEqual(report['first'], dict(status='NOT_RUN', reason='BASELINE_NOT_PASSED'))
        self.assertEqual(report['status'], 'FAIL')

    def test_all_nonpass_or_malformed_returns_fail_closed_and_continue(self):
        for bad in [None, [], {}, dict(status='FAIL'), dict(status='BLOCKED'), dict(status='NOT_RUN'), dict(status='RUNNING'), dict(status='pass')]:
            with self.subTest(result=bad):
                report = dict(status='PASS')
                self.run_modules(report, [module('bad', lambda: bad), module('later', passing)])
                self.assertEqual(report['bad']['status'], 'FAIL')
                self.assertEqual(report['later']['status'], 'PASS')
                self.assertEqual(report['status'], 'FAIL')

    def test_missing_required_summary_fields_fail_closed(self):
        report = dict(status='PASS')
        self.run_modules(report, [module('bad', lambda: dict(status='PASS')), module('later', passing)])
        self.assertEqual(report['bad']['errorType'], 'KeyError')
        self.assertEqual(report['later']['status'], 'PASS')
        self.assertEqual(report['status'], 'FAIL')

    def test_nonpass_summary_also_fails_closed(self):
        report = dict(status='PASS')
        self.run_modules(report, [module('bad', passing, lambda result: dict(status='FAIL'))])
        self.assertEqual(report['bad']['status'], 'FAIL')
        self.assertEqual(report['status'], 'FAIL')

    def test_live_secret_list_is_used_after_failure_without_main_error_leak(self):
        secrets = ['fixture-password']
        def failed_login():
            secrets.append('token-created-during-module')
            raise RuntimeError('fixture-password token-created-during-module Bearer another-token https://local.invalid/path?secret=unlisted')
        report = dict(status='PASS')
        self.run_modules(report, [module('failure', failed_login)], secrets_provider=lambda: secrets)
        text = str(report)
        for secret in ['fixture-password', 'token-created-during-module', 'another-token', 'secret=unlisted']:
            self.assertNotIn(secret, text)
        self.assertIn('[REDACTED]', report['failure']['error'])
        self.assertEqual(report['error'], 'Native module acceptance failed or did not run: failure')

    def test_sanitizer_failure_never_falls_back_to_raw_secret(self):
        def failure():
            raise RuntimeError('highly-private-value')
        def broken_secrets():
            raise RuntimeError('also-private')
        report = dict(status='PASS')
        self.run_modules(report, [module('failure', failure)], secrets_provider=broken_secrets)
        self.assertNotIn('highly-private-value', str(report))
        self.assertNotIn('also-private', str(report))
        self.assertEqual(report['status'], 'FAIL')

    def test_host_probe_error_is_sanitized_and_blocks_execution(self):
        def failed_probe():
            raise RuntimeError('private-probe-value')
        report = dict(status='PASS')
        self.run_modules(report, [module('module', lambda: self.fail('Should not run'))],
                         host_alive=failed_probe, secrets_provider=lambda: ['private-probe-value'])
        self.assertEqual(report['module']['status'], 'NOT_RUN')
        self.assertNotIn('private-probe-value', str(report))
        self.assertEqual(report['status'], 'FAIL')

    def test_original_module_artifacts_are_not_overwritten(self):
        with tempfile.TemporaryDirectory() as directory:
            first_artifact = Path(directory)/'first.txt'
            later_artifact = Path(directory)/'later.txt'
            def first():
                first_artifact.write_text('original observed failure evidence')
                raise AssertionError('failure')
            def later():
                later_artifact.write_text('independent observed result')
                return passing()
            report = dict(status='PASS')
            self.run_modules(report, [module('first', first), module('later', later)])
            self.assertEqual(first_artifact.read_text(), 'original observed failure evidence')
            self.assertEqual(later_artifact.read_text(), 'independent observed result')
            self.assertEqual(report['status'], 'FAIL')

    def test_ci_only_calls_modules_after_baseline_and_retains_all_summaries(self):
        source = (ROOT/'tools/ci_native.py').read_text()
        self.assertLess(source.index('command(build)'), source.index("result=fixture.verify('http://127.0.0.1:18787'"))
        self.assertLess(source.index("result=fixture.verify('http://127.0.0.1:18787'"), source.index('   run_native_modules(report,modules,'))
        self.assertIn("prerequisites_passed=result.get('status')=='PASS' and bool(report.get('build',{}).get('buildId'))", source)
        for name in ('runtimePlugins', 'runtimeStorageLifecycle', 'runtimeFailureBoundaries', 'runtimeExchange'):
            self.assertIn("NativeModule('"+name+"'", source)
        self.assertIn('secrets_provider=lambda:[*fixture.diagnostic_secrets,fixture.password]', source)
        self.assertIn('host_alive=lambda:process is not None and process.poll() is None', source)


if __name__ == '__main__':
    unittest.main()
