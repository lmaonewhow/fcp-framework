"""Local fixture contract tests only, never native acceptance evidence."""
import io,json,os,subprocess,sys,unittest,zipfile
from pathlib import Path
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).parent))
from native_exchange_acceptance import ExchangeFixture,package_bytes
ROOT=Path(__file__).resolve().parents[2]

class FixtureTests(unittest.TestCase):
    def test_deterministic_packages_are_original_declarations(self):
        for plugin in ('roster','mock-exam'):
            data,manifest=package_bytes(plugin)
            self.assertEqual(data,package_bytes(plugin)[0])
            with zipfile.ZipFile(io.BytesIO(data)) as archive:
                self.assertEqual(set(archive.namelist()),{'plugin.json','worker.py'})
                self.assertEqual(json.loads(archive.read('plugin.json')),manifest)
            self.assertEqual(manifest['pluginId'],plugin)
            self.assertEqual(manifest['publisher'],'fcp-fixtures')
            self.assertTrue(manifest['storage']['tables'])
    def test_isolation_rejected_before_sql_or_server(self):
        writes=[]
        for ci,database in [('false','fcp_ci_123456abcdef'),('true','production')]:
            with patch.dict(os.environ,GITHUB_ACTIONS=ci),patch('native_exchange_acceptance.ThreadingHTTPServer') as server:
                with self.assertRaises(RuntimeError):ExchangeFixture(database,writes.append,Path('/tmp'),None)
                server.assert_not_called()
        self.assertEqual(writes,[])
    def test_task_grants_are_exact_and_separately_digest_bound(self):
        f=ExchangeFixture.__new__(ExchangeFixture);f.database='fcp_ci_123456abcdef';statements=[];f.sql=statements.append
        f.task_authority('a'*64)
        self.assertEqual(len(statements),6)
        for sql in statements:
            self.assertIn('fcp_runtime_task_grant',sql);self.assertIn('package_digest',sql);self.assertIn('a'*64,sql)
            self.assertIn('roster-fixture-sync',sql);self.assertNotIn('INSERT INTO fcp_plugin_record',sql)
        with self.assertRaises(ValueError):f.task_authority('bad-digest')
    def test_explicit_platform_operations_only(self):
        f=ExchangeFixture.__new__(ExchangeFixture);f.database='fcp_ci_123456abcdef';statements=[];f.sql=statements.append
        f.seed_exchange_authority('roster','a'*64);self.assertEqual(len(statements),3)
        self.assertTrue(all('fcp_runtime_grant' in s and 'class:class-a' in s for s in statements))
        with self.assertRaises(ValueError):f.seed_exchange_authority('unapproved','a'*64)
    def test_no_fake_native_login_or_sql_business_writes(self):
        source=(Path(__file__).parent/'native_exchange_acceptance.py').read_text()
        self.assertIn("self.login_fixture.login(browser,base,'admin')",source)
        self.assertIn('client.evidence()',source)
        self.assertNotIn('INSERT INTO fcp_plugin_record',source)
        self.assertNotIn('UPDATE fcp_plugin_record',source)
        self.assertNotIn('DevHost',source)
        self.assertIn('/storage/plans/',source)
    def test_unrun_cli_does_not_report_pass(self):
        result=subprocess.run([sys.executable,str(Path(__file__).parent/'native_exchange_acceptance.py')],capture_output=True,text=True)
        self.assertEqual(result.returncode,2);self.assertEqual(json.loads(result.stdout)['status'],'NOT_RUN')
    def test_ci_exchange_opt_in_requires_ingestion(self):
        result=subprocess.run([sys.executable,str(ROOT/'tools/ci_native.py'),'--exchange'],capture_output=True,text=True)
        self.assertEqual(result.returncode,2);self.assertIn('--exchange requires genuine',result.stderr)
        source=(ROOT/'tools/ci_native.py').read_text();self.assertIn('if a.exchange:',source)
        self.assertIn('fcp_ci_crash_',source)
        self.assertIn('GRANT SELECT,INSERT,UPDATE ON `{crash_database}`.fcp_runtime_state',source)

if __name__=='__main__':unittest.main()
