"""Pure fixture/configuration tests. These cannot certify native/browser acceptance."""
import contextlib
import io
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path[:0] = [str(ROOT/'tools'), str(ROOT/'tests/runtime-native')]
import ci_native
from release_composition_acceptance import manifest_digest, package_artifact, package_bytes, ReleaseCompositionFixture


class NativeCompositionConfigurationTests(unittest.TestCase):
    def test_legacy_modes_unchanged(self):
        self.assertFalse(ci_native.parse_args([]).plugin_releases)
        legacy = ci_native.parse_args(['--ingestion', '--exchange'])
        self.assertTrue(legacy.exchange)
        self.assertFalse(legacy.plugin_releases)

    def test_composition_requires_real_login_and_isolates_exchange(self):
        mode = ci_native.parse_args(['--ingestion', '--plugin-releases'])
        self.assertTrue(mode.plugin_releases)
        self.assertFalse(mode.exchange)
        for invalid in [['--plugin-releases'], ['--ingestion', '--exchange', '--plugin-releases']]:
            with self.subTest(args=invalid), contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                ci_native.parse_args(invalid)

    def test_canonical_manifest_digest_has_no_map_order_dependency(self):
        a = {'id':'example', 'components':{'teacher-ui':'b', 'admin-ui':'a'}, 'version':'1'}
        b = {'version':'1', 'components':{'admin-ui':'a', 'teacher-ui':'b'}, 'id':'example'}
        self.assertEqual(manifest_digest(a), manifest_digest(b))
        b['components']['admin-ui'] = 'changed'
        self.assertNotEqual(manifest_digest(a), manifest_digest(b))

    def test_package_artifacts_bind_two_real_distinct_fixture_versions(self):
        first, _ = package_bytes('python', '1.0.0')
        second, _ = package_bytes('python', '1.1.0')
        a = package_artifact('python', '1.0.0', first)
        b = package_artifact('python', '1.1.0', second)
        self.assertNotEqual(a['sha256'], b['sha256'])
        self.assertEqual(a['component'], b['component'])
        self.assertEqual((a['sizeBytes'], b['sizeBytes']), (len(first), len(second)))
        self.assertEqual(a, package_artifact('python', '1.0.0', package_bytes('python', '1.0.0')[0]))

    def test_acceptance_refuses_component_environment_before_browser(self):
        fixture = ReleaseCompositionFixture.__new__(ReleaseCompositionFixture)
        fixture.database = 'fcp_ci_0123456789ab'
        with patch.dict('os.environ', {'GITHUB_ACTIONS':'false'}):
            with self.assertRaisesRegex(RuntimeError, 'Exact isolated'):
                fixture.verify('http://127.0.0.1:18787', 'abc', lambda: 1)

    def test_acceptance_requires_both_actual_builds(self):
        fixture = ReleaseCompositionFixture.__new__(ReleaseCompositionFixture)
        fixture.database = 'fcp_ci_0123456789ab'
        fixture.ui_versions = None
        with patch.dict('os.environ', {'GITHUB_ACTIONS':'true', 'FCP_ACCEPTANCE_DATABASE':fixture.database}):
            with self.assertRaisesRegex(RuntimeError, 'Two genuine typed native UI builds'):
                fixture.verify('http://127.0.0.1:18787', 'abc', lambda: 1)

    def test_ci_calls_native_baseline_before_actual_variant(self):
        source = (ROOT/'tools/ci_native.py').read_text()
        self.assertLess(source.index('  command(build)'), source.index('   variant=build_ui_variant('))
        self.assertIn('FCP_RELEASE_EXECUTOR_MODE=native', source)
        self.assertIn('FCP_RELEASE_EXECUTOR_LOCK_FILE=', source)
        self.assertIn('if runtime_fixture and not a.plugin_releases:', source)
        self.assertIn('if failure_fixture and not a.plugin_releases:', source)

    def test_real_fixture_uses_only_control_plane_deployment(self):
        source = (ROOT/'tests/runtime-native/release_composition_acceptance.py').read_text()
        self.assertIn("admin.data('/fcp/releases/operations', command)", source)
        self.assertNotIn("'/activate'", source)
        self.assertNotIn('router.put', source)
        self.assertNotIn('page.route(', source)
        self.assertNotIn('route.fulfill(', source)


if __name__ == '__main__':
    unittest.main()
