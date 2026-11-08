"""Report/call-site and screenshot source guards; no native browser was run."""
import ast
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

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


if __name__ == '__main__':
    unittest.main()
