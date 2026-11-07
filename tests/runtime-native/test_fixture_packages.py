"""Pure local fixture checks. Passing these is NOT native HTTP/browser acceptance."""
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile
sys.path.insert(0,str(Path(__file__).parent))
from native_runtime_acceptance import package_bytes,exported,RuntimeFixture

class FixturePackagesTest(unittest.TestCase):
    def test_two_deterministic_declarative_archives(self):
        for language in ('python','node'):
            archive,manifest=package_bytes(language)
            self.assertEqual(archive,package_bytes(language)[0])
            with zipfile.ZipFile(io.BytesIO(archive)) as zipped:
                self.assertEqual(set(zipped.namelist()),{'plugin.json',manifest['runtime']['entry']})
                self.assertEqual(json.loads(zipped.read('plugin.json')),manifest)
            self.assertEqual(manifest['publisher'],'test')
            self.assertEqual(manifest['ui']['pages'][0]['capabilities'],['echo'])
            self.assertFalse(manifest['provides'][0]['sideEffects'])
            self.assertEqual(len(exported(manifest['pluginId'])),41)
        self.assertTrue(package_bytes('node')[1]['requires'])
        self.assertFalse(package_bytes('python')[1]['requires'])

    def test_version_fixture_packages_are_distinct_and_schema_is_explicit(self):
        archives=[]
        for version in ('1.0.0','1.1.0','1.2.0'):
            archive,manifest=package_bytes('python',version,version=='1.2.0')
            self.assertEqual(archive,package_bytes('python',version,version=='1.2.0')[0])
            self.assertEqual(manifest['version'],version)
            with zipfile.ZipFile(io.BytesIO(archive)) as zipped:
                self.assertIn("'packageVersion':"+repr(version),zipped.read('runtime.py').decode())
            self.assertEqual(bool(manifest.get('storage')),version=='1.2.0')
            archives.append(archive)
        self.assertEqual(len(set(archives)),3)
        self.assertEqual(package_bytes('node')[1]['dependencies'][0]['versionRange'],'^1.0.0')
        with self.assertRaises(ValueError):package_bytes('python','9.0.0')
        with self.assertRaises(ValueError):package_bytes('python','1.1.0',True)

    def test_refuses_non_ci_database_before_any_sql(self):
        calls=[]
        for environment,database in [('false','fcp_ci_123456abcdef'),('true','production')]:
            with patch.dict(os.environ,GITHUB_ACTIONS=environment):
                with self.assertRaises(RuntimeError):RuntimeFixture(database,calls.append,Path('/tmp'),None)
        self.assertEqual(calls,[])

    def test_native_authorization_is_not_fabricated(self):
        source=(Path(__file__).parent/'native_runtime_acceptance.py').read_text()
        self.assertIn('self.login_fixture.login(browser,base,role)',source)
        self.assertNotIn('DevHost',source.split('class RuntimeFixture:',1)[1])
        self.assertIn('admin.evidence()',source)
        self.assertIn('backendPidUnchanged',source)

    def test_cli_does_not_claim_unrun_acceptance(self):
        result=subprocess.run([sys.executable,str(Path(__file__).parent/'native_runtime_acceptance.py')],text=True,capture_output=True)
        self.assertEqual(result.returncode,2)
        self.assertEqual(json.loads(result.stdout)['status'],'NOT_RUN')

if __name__=='__main__':unittest.main()
