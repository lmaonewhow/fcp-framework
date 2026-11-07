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

    def test_build_and_install_use_same_policy(self):
        self.assertIn('PluginDependencyPolicy.verify(contents)', (ROOT/'core/src/main/java/fcp/plugin/PackageInstaller.java').read_text())
        self.assertIn('fcp.plugin.PluginDependencyPolicy', (ROOT/'tools/check_plugin_dependencies.py').read_text())
        self.assertIn('check_plugin_directory(', (ROOT/'tools/package_exchange_fixtures.py').read_text())


if __name__ == '__main__':
    unittest.main()
