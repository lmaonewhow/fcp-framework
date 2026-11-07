"""Safety/static checks only, never counted as native browser acceptance."""
import ast
import importlib.util
import os
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
PATH = ROOT / 'tools/ci_trace_frontend.py'
spec = importlib.util.spec_from_file_location('ci_trace_frontend', PATH)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

class TraceFrontendGateTest(unittest.TestCase):
    def test_requires_isolated_ci_before_browser_access(self):
        for environment, database, base in [
            ({}, 'fcp_ci_0123456789ab', 'http://127.0.0.1:18787'),
            ({'GITHUB_ACTIONS':'true'}, 'production', 'http://127.0.0.1:18787'),
            ({'GITHUB_ACTIONS':'true'}, 'fcp_ci_0123456789ab', 'https://example.com'),
            ({'GITHUB_ACTIONS':'true'}, 'fcp_ci_0123456789ab', 'http://localhost:18787'),
            ({'GITHUB_ACTIONS':'true'}, 'fcp_ci_0123456789ab', 'http://user:password@127.0.0.1'),
            ({'GITHUB_ACTIONS':'true'}, 'fcp_ci_0123456789ab', 'http://127.0.0.1/?token=x'),
        ]:
            with self.subTest(database=database,base=base), patch.dict(os.environ,environment,clear=True):
                with self.assertRaisesRegex(RuntimeError, 'disposable native loopback CI'):
                    module.verify(SimpleNamespace(database=database), None, base, None, 'test')

    def test_no_network_mock_or_business_mutation_calls(self):
        tree = ast.parse(PATH.read_text())
        calls = [node for node in ast.walk(tree) if isinstance(node,ast.Call)]
        names = {node.func.attr for node in calls if isinstance(node.func,ast.Attribute)}
        self.assertFalse(names & {'route','unroute','fulfill','continue_','post','put','delete','sql','storage_state','add_init_script'})
        self.assertNotIn('fetch(', PATH.read_text())
        evaluations = [node.args[0].value for node in calls if isinstance(node.func,ast.Attribute)
                       and node.func.attr=='evaluate' and node.args and isinstance(node.args[0],ast.Constant)]
        self.assertEqual(set(evaluations), {'(element) => element.tagName','() => navigator.clipboard.readText()'})

    def test_real_response_clipboard_and_single_panel_are_asserted(self):
        source = PATH.read_text()
        for required in ["response.header_value('X-Trace-Id')", "response.request.method == 'GET'",
                         "expect(notice).to_have_count(1)", "copied == trace_id", "trace_id != ids[-1]",
                         "nativeAxiosBrowserCoverage=dict(admin=dict(status='NOT_RUN')", "origin=base"]:
            self.assertIn(required,source)

    def test_native_response_selector_is_exact_and_read_only(self):
        base = 'http://127.0.0.1:18787'
        def response(url, method='GET'):
            return SimpleNamespace(url=url, request=SimpleNamespace(method=method))
        target = base + module.NATIVE_READ_PATH
        self.assertTrue(module.native_rejected_read(response(target), base))
        for url, method in [
            (target, 'POST'), (target + '?token=x', 'GET'),
            (target + '#x', 'GET'), (target + '0', 'GET'),
            (target.replace(':18787', ':18788'), 'GET'),
            (target.replace('http:', 'https:'), 'GET'),
            (target.replace('/system/', '/fcp/'), 'GET'),
        ]:
            with self.subTest(url=url, method=method):
                self.assertFalse(module.native_rejected_read(response(url, method), base))

    def test_native_case_keeps_fetch_exception_gate_and_honest_scope(self):
        source = PATH.read_text()
        self.assertIn("response.status == 200 and payload.get('code') == 500", source)
        self.assertIn("'/admin/system/user-auth/role/' + NATIVE_OVERFLOW_ID", source)
        self.assertIn("teacher=dict(status='NOT_RUN'", source)
        self.assertIn("trace notice is dismissible without a page exception', not exceptions", source)
        self.assertIn("pageErrorCount=error_count", source)
        self.assertIn('no zero-error claim', source)
        self.assertNotIn('str(error)', source)
        self.assertNotIn('str(failure)', source)
        self.assertNotIn("payload.get('msg')", source)

    def test_native_error_gate_accepts_only_exact_optional_rejection(self):
        trace = 'a' * 32
        message = ("请求参数类型不匹配，参数[userId]要求类型为：'java.lang.Long'，但输入值为：'"
                   + module.NATIVE_OVERFLOW_ID + "' (Trace ID: " + trace + ")")
        error = SimpleNamespace(message=message)
        self.assertTrue(module.expected_native_page_errors([], 0, trace))
        self.assertTrue(module.expected_native_page_errors([error], 1, trace))
        for errors, count, trace_id in [
            ([error, error], 2, trace), ([error], 2, trace),
            ([], 1, trace), ([error], 1, 'b' * 32),
            ([error], 1, ''), ([], 0, '0' * 32),
            ([SimpleNamespace()], 1, trace),
        ]:
            self.assertFalse(module.expected_native_page_errors(errors, count, trace_id))
        for unexpected in [message + ' ', message + ' extra', 'Error: ' + message,
                           message.replace('userId', 'roleId'),
                           message.replace('java.lang.Long', 'java.lang.Integer'),
                           message.replace(module.NATIVE_OVERFLOW_ID, '1'),
                           'unrelated page error', message.split(' (Trace ID:')[0]]:
            self.assertFalse(module.expected_native_page_errors(
                [SimpleNamespace(message=unexpected)], 1, trace))

if __name__=='__main__':
    unittest.main()
