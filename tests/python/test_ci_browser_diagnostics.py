"""Keep native browser diagnostics useful without persisting login credentials."""
import pathlib
import sys
import unittest
ROOT = pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT/'tools'))
from ci_ingestion import safe_diagnostic

class NativeBrowserDiagnosticsTests(unittest.TestCase):
    def test_query_credentials_are_removed(self):
        text = safe_diagnostic('Failed https://localhost/resource/sse?Authorization=Bearer%20demo-secret&clientid=public')
        self.assertEqual(text, 'Failed https://localhost/resource/sse?[REDACTED_QUERY]')

    def test_known_secrets_and_bearer_values_are_removed(self):
        text = safe_diagnostic('password-test Bearer token-test', ['password-test'])
        self.assertNotIn('password-test', text)
        self.assertNotIn('token-test', text)

    def test_plain_failure_and_bounded_size_are_preserved(self):
        self.assertEqual(safe_diagnostic('Failed to load resource: HTTP 404'), 'Failed to load resource: HTTP 404')
        self.assertEqual(len(safe_diagnostic('x'*1000)), 600)

    def test_restart_keeps_strict_console_checks_and_prior_diagnostics(self):
        source = (ROOT/'tools/ci_ingestion.py').read_text()
        self.assertIn("self.check('admin no console errors before planned restart', not console_errors)", source)
        self.assertIn("self.check(role+' no browser console errors', not console_errors)", source)
        self.assertNotIn('console_errors.clear()', source)
        self.assertIn('page.close()\n                            if before_video:', source)
        self.assertIn("marker.get_attribute('data-build-id') == build_id", source)

if __name__ == '__main__':
    unittest.main()
