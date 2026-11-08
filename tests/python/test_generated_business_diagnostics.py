"""Diagnostics and actual-request assertion wiring; not a native browser test."""
from pathlib import Path
from types import SimpleNamespace
import sys
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
sys.path[:0] = [str(ROOT/'tools'),str(ROOT/'tests/generator')]
from business_runtime import capture_business_failure, PAGE


class BusinessDiagnosticsTests(unittest.TestCase):
    def test_failure_capture_follows_active_viewer_not_earlier_admin(self):
        admin = SimpleNamespace(url='http://127.0.0.1:18787'+PAGE,screenshot=mock.Mock())
        viewer = SimpleNamespace(url=admin.url,screenshot=mock.Mock())
        report = {}
        capture_business_failure(viewer,'viewer','http://127.0.0.1:18787',Path('/tmp/output'),report)
        viewer.screenshot.assert_called_once_with(path='/tmp/output/business-failure.png',full_page=True)
        admin.screenshot.assert_not_called()
        self.assertEqual(report['businessFailurePage']['role'],'viewer')
        self.assertEqual(report['businessFailurePage']['screenshot'],'business-failure.png')
    def test_failure_capture_never_exports_login_or_external_page(self):
        for url in ('http://127.0.0.1:18787/admin/login','http://example.com'+PAGE,'https://127.0.0.1:18787'+PAGE,'about:blank'):
            with self.subTest(url=url):
                page = SimpleNamespace(url=url,screenshot=mock.Mock());report={}
                capture_business_failure(page,'viewer','http://127.0.0.1:18787',Path('/tmp/output'),report)
                page.screenshot.assert_not_called()
                self.assertEqual(report['businessFailurePage']['screenshot'],'NOT_CAPTURED')
    def test_capture_failure_does_not_replace_original_failure(self):
        page = SimpleNamespace(url='http://127.0.0.1:18787'+PAGE,screenshot=mock.Mock(side_effect=RuntimeError('closed page')))
        report={}
        capture_business_failure(page,'admin','http://127.0.0.1:18787',Path('/tmp/output'),report)
        self.assertEqual(report['businessFailurePage']['screenshot'],'CAPTURE_FAILED')
    def test_viewer_errors_collected_and_checked_after_role_switch(self):
        source=(ROOT/'tests/generator/business_runtime.py').read_text()
        self.assertLess(source.index("active_page, active_role = viewer_page, 'viewer'"),source.index('viewer_page.goto'))
        self.assertLess(source.index("viewer_page.on('pageerror'"),source.index('viewer_page.goto'))
        self.assertIn("report['businessBrowserDiagnostics']['viewer']",source)
        self.assertIn("check('read-only generated browser has no uncaught exception',not viewer_errors)",source)
        self.assertIn('capture_business_failure(active_page,active_role,base,output,report)',source)
    def test_real_manual_date_input_and_request_bounds_remain_required(self):
        source=(ROOT/'tests/generator/business_runtime.py').read_text()
        self.assertIn("begin.fill('2026-10-13');end.fill('2026-10-13');end.press('Tab')",source)
        self.assertIn("expected_range=('2026-10-13 00:00:00','2026-10-13 23:59:59')",source)
        self.assertIn("expected_range=()",source)
        self.assertIn("urllib.parse.parse_qs(urllib.parse.urlsplit(received.value.url).query)",source)
        self.assertIn("params.get('params[beginEventTime]',[])",source)
        self.assertIn("params.get('params[endEventTime]',[])",source)
        self.assertIn("'2026-10-13 23:59:58'",source)
        self.assertIn("inclusive date range contains late-evening row and excludes adjacent dates",source)

if __name__=='__main__':unittest.main()
