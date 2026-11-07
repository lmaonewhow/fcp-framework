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
                         "nativeAxiosBrowserCoverage='NOT_RUN by this check'", "origin=base"]:
            self.assertIn(required,source)

if __name__=='__main__':
    unittest.main()
