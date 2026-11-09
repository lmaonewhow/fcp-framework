"""Report/call-site and screenshot source guards; no native browser was run."""
import ast
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from types import SimpleNamespace

SOURCE = Path(__file__).with_name('native_browser.py')
spec = importlib.util.spec_from_file_location('roster_browser_contract_subject', SOURCE)
browser = importlib.util.module_from_spec(spec)
spec.loader.exec_module(browser)


class BrowserReportContract(unittest.TestCase):
    def test_success_return_shape_supports_ci_summary_accessors(self):
        # Synthetic completed checks, not an assertion that browser acceptance ran.
        report = dict(status='PASS', completed=True, checks=[{'name': 'synthetic', 'status': 'PASS'}], screenshots=[])
        with tempfile.TemporaryDirectory() as directory:
            browser._write_report(report, Path(directory))
            # Exact fields consumed by the native orchestration after verify_browser.
            summary = dict(status=report['status'], totalChecks=report['totalChecks'], screenshots=report['screenshots'])
            self.assertEqual(1, summary['totalChecks'])
            self.assertEqual(report, json.loads((Path(directory) / 'acceptance.json').read_text()))

    def test_failure_and_empty_reports_have_actual_count_without_claiming_success(self):
        for count in (0, 3):
            with self.subTest(count=count), tempfile.TemporaryDirectory() as directory:
                report = dict(status='FAIL', completed=False, checks=[{'status': 'PASS'} for _ in range(count)],
                              screenshots=[], totalChecks=999, error='synthetic failure')
                browser._write_report(report, Path(directory))
                saved = json.loads((Path(directory) / 'acceptance.json').read_text())
                self.assertEqual(count, saved['totalChecks'])
                self.assertEqual('FAIL', saved['status'])
                self.assertFalse(saved['completed'])
                self.assertEqual(saved, report)

    def test_runner_unconditionally_finalizes_shared_report_before_return(self):
        tree = ast.parse(SOURCE.read_text())
        runner = next(node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name == 'verify_browser')
        finalizers = [node for node in runner.body if isinstance(node, ast.Try) and node.finalbody]
        self.assertEqual(1, len(finalizers))
        call = finalizers[0].finalbody[0].value
        self.assertIsInstance(call, ast.Call)
        self.assertEqual('_write_report', call.func.id)
        self.assertEqual(['report', 'output'], [argument.id for argument in call.args])
        self.assertIsInstance(runner.body[-1], ast.Return)
        self.assertEqual('report', runner.body[-1].value.id)

    def test_capture_protocol_uses_fixed_viewport_and_real_scroll_without_dom_workarounds(self):
        # Structural guard only. Geometry and screenshots require actual native CI.
        source = SOURCE.read_text()
        tree = ast.parse(source)
        calls = [node for node in ast.walk(tree) if isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute)]
        captures = [node for node in calls if node.func.attr == 'screenshot']
        self.assertEqual(1, len(captures))
        full_page = next(keyword.value for keyword in captures[0].keywords if keyword.arg == 'full_page')
        self.assertIsInstance(full_page, ast.Constant)
        self.assertIs(False, full_page.value)
        self.assertIn('viewport=dict(width=1440, height=1080)', source)
        self.assertIn('window.scrollTo', source)
        self.assertIn('immediate = metrics(page)', source)
        self.assertIn('after = metrics(page)', source)
        self.assertFalse({'route', 'route_from_har', 'set_viewport_size', 'add_style_tag'}.intersection(node.func.attr for node in calls))
        self.assertIn('state = host.login(browser, base, role)', source)
        self.assertIn('response.status == 200', source)


class BrowserTableScrollContract(unittest.TestCase):
    def page(self, fail_wait=False):
        # Owned unit doubles record commands; these are not native browser results.
        calls = []
        table = SimpleNamespace(evaluate=lambda script: calls.append(('evaluate', script)))
        def locator(selector):
            calls.append(('locator', selector))
            return table
        def get_by_test_id(test_id):
            calls.append(('test_id', test_id))
            return SimpleNamespace(locator=locator)
        def wait_for_function(script, **kwargs):
            calls.append(('wait', script, kwargs))
            if fail_wait:
                raise TimeoutError('synthetic scroller did not return left')
        return SimpleNamespace(get_by_test_id=get_by_test_id, wait_for_function=wait_for_function), calls

    def test_reset_uses_native_table_scroll_and_waits_for_left_edge(self):
        page, calls = self.page()
        browser.reset_roster_table_scroll(page)
        self.assertEqual(['test_id', 'locator', 'evaluate', 'wait'], [call[0] for call in calls])
        self.assertEqual('roster-records', calls[0][1])
        self.assertEqual('.el-table__body-wrapper .el-scrollbar__wrap', calls[1][1])
        self.assertEqual('(node) => node.scrollTo({left:0,top:node.scrollTop,behavior:"instant"})', calls[2][1])
        self.assertIn('[data-testid="roster-records"] .el-table__body-wrapper .el-scrollbar__wrap', calls[3][1])
        self.assertIn('Math.abs(node.scrollLeft) <= 1', calls[3][1])
        self.assertEqual({'timeout': 10000}, calls[3][2])
        for prohibited in ('innerHTML', 'textContent', '.style', 'remove(', 'setAttribute', 'viewport', 'screenshot'):
            self.assertNotIn(prohibited, calls[2][1] + calls[3][1])

    def test_reset_failure_is_not_suppressed_or_retried(self):
        page, calls = self.page(fail_wait=True)
        with self.assertRaisesRegex(TimeoutError, 'synthetic scroller did not return left'):
            browser.reset_roster_table_scroll(page)
        self.assertEqual(['test_id', 'locator', 'evaluate', 'wait'], [call[0] for call in calls])

    def test_each_capture_resets_then_waits_then_measures_before_screenshot(self):
        tree = ast.parse(SOURCE.read_text())
        capture = next(node for node in ast.walk(tree) if isinstance(node, ast.FunctionDef) and node.name == 'screenshot')
        loop = next(node for node in ast.walk(capture) if isinstance(node, ast.For))
        calls = []
        for statement in loop.body:
            value = getattr(statement, 'value', None)
            if isinstance(value, ast.Call):
                name = value.func.id if isinstance(value.func, ast.Name) else value.func.attr
                calls.append(name)
        self.assertEqual(['evaluate', 'reset_roster_table_scroll', 'wait_stable_layout', 'metrics', 'geometry'], calls[:5])
        self.assertLess(calls.index('metrics'), calls.index('screenshot'))
        self.assertEqual(1, calls.count('reset_roster_table_scroll'))

    def test_body_and_header_are_observed_and_exact_detail_assertion_remains(self):
        source = SOURCE.read_text()
        tree = ast.parse(source)
        functions = {node.name: ast.get_source_segment(source, node) for node in ast.walk(tree) if isinstance(node, ast.FunctionDef)}
        for selector in ('.el-table__body-wrapper .el-scrollbar__wrap', '.el-table__header-wrapper'):
            self.assertIn(selector, functions['wait_stable_layout'])
            self.assertIn(selector, functions['metrics'])
        self.assertIn('node.scrollLeft', functions['wait_stable_layout'])
        self.assertIn('stable >= 4', functions['wait_stable_layout'])
        for key in ('tableBody', 'tableHeader'):
            self.assertIn(key, functions['metrics'])
            self.assertIn(key, functions['geometry'])
        self.assertIn("abs(m[key]['scrollLeft']) <= 1", functions['geometry'])
        self.assertIn("geometry(immediate,", functions['screenshot'])
        self.assertIn("geometry(after,", functions['screenshot'])
        self.assertIn("expect(page.get_by_test_id('roster-detail')).to_contain_text(first['id'])", source)


class BrowserDiagnosticContract(unittest.TestCase):
    def report(self):
        return dict(status='FAIL', completed=False, checks=[{'name': 'synthetic', 'status': 'PASS'}], screenshots=[])

    def test_pageerror_retains_phase_stack_and_all_error_types_in_failed_report(self):
        report = self.report()
        diagnostic = browser._RolePageErrors(report, 'admin', [])
        diagnostic.stage('select-pending-school')
        error = SimpleNamespace(name='TypeError', message="Cannot read properties of null (reading 'offsetWidth')",
                                stack="TypeError: Cannot read properties of null\n    at layout (http://127.0.0.1:18787/admin/assets/table.js:1:27)")
        diagnostic.capture(error)
        diagnostic.stage('close-context')
        diagnostic.capture(SimpleNamespace(name='Error', message='ResizeObserver loop completed with undelivered notifications.', stack=''))
        with tempfile.TemporaryDirectory() as directory:
            browser._write_report(report, Path(directory))
            saved = json.loads((Path(directory) / 'acceptance.json').read_text())
        entry = saved['browserDiagnostics'][0]
        self.assertEqual(2, entry['pageErrorCount'])
        self.assertEqual('select-pending-school', entry['pageErrors'][0]['stage'])
        self.assertIn('table.js:1:27', entry['pageErrors'][0]['stack'])
        self.assertIn('\n', entry['pageErrors'][0]['stack'])
        self.assertEqual(error.message, entry['pageErrors'][0]['message'])
        self.assertIn('ResizeObserver', entry['pageErrors'][1]['message'])
        self.assertEqual('FAIL', saved['status'])

    def test_secrets_queries_fragments_and_response_shaped_text_never_persist(self):
        report = self.report()
        diagnostic = browser._RolePageErrors(report, 'admin', ['known-password', 'native-token'])
        diagnostic.stage('open-page')
        diagnostic.capture(SimpleNamespace(
            name='Error',
            message='known-password native-token Bearer unknown-bearer-value https://user:pass@example.test/app.js?code=private-query#private-fragment',
            stack='TypeError: known-password\n    at a (http://127.0.0.1:18787/admin/app.js?token=private-value#secret-fragment)\npassword=unlisted-secret\n' + '{\n"data":"PRIVATE_RESPONSE_BODY"\n}'))
        encoded = json.dumps(report)
        for value in ['known-password', 'native-token', 'unknown-bearer-value', 'user:pass', 'private-query', 'private-fragment', 'private-value', 'secret-fragment', 'unlisted-secret', 'PRIVATE_RESPONSE_BODY']:
            self.assertNotIn(value, encoded)
        self.assertIn('TypeError', encoded)
        self.assertIn('/admin/app.js', encoded)

    def test_bounds_preserve_total_counts_and_report_dropped_errors(self):
        report = self.report()
        diagnostic = browser._RolePageErrors(report, 'teacher', [])
        error = SimpleNamespace(name='Error', message='x' * 1000, stack='\n'.join('at ' + 'x' * 500 for _ in range(30)))
        for _ in range(23):
            diagnostic.capture(error)
        entry = report['browserDiagnostics'][0]
        self.assertEqual(23, entry['pageErrorCount'])
        self.assertEqual(20, len(entry['pageErrors']))
        self.assertEqual(3, entry['droppedPageErrors'])
        for item in entry['pageErrors']:
            self.assertLessEqual(len(item['message']), 600)
            self.assertLessEqual(len(item['stack']), 3600)
            self.assertLessEqual(len(item['stack'].splitlines()), 12)
            self.assertTrue(item['stackTruncated'])
        self.assertEqual('[OVERSIZED_ERROR_TEXT_OMITTED]', browser._error_text('x' * 65537))

    def test_same_origin_http_and_envelope_failures_keep_only_safe_metadata(self):
        report = self.report()
        diagnostic = browser._RolePageErrors(report, 'admin', [])
        diagnostic.stage('open-page')
        def response(url, status, code):
            return SimpleNamespace(request=SimpleNamespace(url=url, method='GET'), status=status,
                                   header_value=lambda key: 'application/json',
                                   json=lambda: {'code': code, 'msg': 'DO_NOT_COLLECT_MSG', 'data': {'token': 'DO_NOT_COLLECT_BODY'}})
        diagnostic.response(response('http://127.0.0.1:18787/auth/tenant/list?nonce=SECRET_QUERY#SECRET_FRAGMENT', 200, 429))
        diagnostic.response(response('http://127.0.0.1:18787/auth/tenant/list', 429, 429))
        diagnostic.response(response('http://127.0.0.1:18787/fcp/education/roster/capabilities', 200, 200))
        diagnostic.response(response('https://other.example/auth?secret=OTHER', 500, 500))
        entry = report['browserDiagnostics'][0]
        self.assertEqual(2, entry['httpFailureCount'])
        self.assertEqual({'stage': 'open-page', 'completedChecks': 1, 'path': '/auth/tenant/list', 'method': 'GET', 'httpStatus': 200, 'envelopeCode': 429}, entry['httpFailures'][0])
        for value in ['SECRET_QUERY', 'SECRET_FRAGMENT', 'DO_NOT_COLLECT', 'other.example']:
            self.assertNotIn(value, json.dumps(report))

    def test_http_diagnostics_are_bounded_and_ignore_non_numeric_body_fields(self):
        diagnostic = browser._RolePageErrors(self.report(), 'admin', [])
        def response(code):
            return SimpleNamespace(request=SimpleNamespace(url='http://127.0.0.1:18787/auth/tenant/list', method='GET'),
                                   status=503, header_value=lambda key: 'application/json', json=lambda: {'code': code})
        for _ in range(23):
            diagnostic.response(response({'token': 'PRIVATE_PAYLOAD'}))
        self.assertEqual(23, diagnostic.entry['httpFailureCount'])
        self.assertEqual(20, len(diagnostic.entry['httpFailures']))
        self.assertEqual(3, diagnostic.entry['droppedHttpFailures'])
        self.assertIsNone(diagnostic.entry['httpFailures'][0]['envelopeCode'])
        self.assertNotIn('PRIVATE_PAYLOAD', json.dumps(diagnostic.entry))

    def test_request_failure_bounds_redaction_and_role_isolation(self):
        report = self.report()
        admin = browser._RolePageErrors(report, 'admin', ['native-token'])
        viewer = browser._RolePageErrors(report, 'viewer', [])
        admin.stage('capture-detail:enrollments')
        request = SimpleNamespace(url='http://127.0.0.1:18787/auth/tenant/list?token=SECRET', method='GET', failure='net::ERR_ABORTED native-token')
        for _ in range(24):
            admin.request_failed(request)
        self.assertEqual(24, admin.entry['requestFailedCount'])
        self.assertEqual(20, len(admin.entry['requestFailures']))
        self.assertEqual(4, admin.entry['droppedRequestFailures'])
        self.assertEqual(0, viewer.entry['requestFailedCount'])
        self.assertEqual('net::ERR_ABORTED [REDACTED]', admin.entry['requestFailures'][0]['reason'])
        self.assertNotIn('SECRET', json.dumps(report))

    def test_native_runner_registers_diagnostics_before_navigation_without_error_filter(self):
        source = SOURCE.read_text()
        self.assertLess(source.index("page.on('pageerror', diagnostics.capture)"), source.index('page.goto(base + PAGE)'))
        self.assertIn("page.on('response', diagnostics.response)", source)
        self.assertIn("page.on('requestfailed', diagnostics.request_failed)", source)
        self.assertIn("check(role + ' no browser errors', diagnostics.entry['pageErrorCount'] == 0)", source)
        self.assertNotIn("if 'ResizeObserver'", source)


if __name__ == '__main__':
    unittest.main()
