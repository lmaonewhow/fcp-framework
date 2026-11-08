"""File/plan/argument regressions, not native MySQL or browser acceptance."""
from contextlib import redirect_stderr
import hashlib
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from types import SimpleNamespace
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools'))
import ci_education_integration as integration
from ci_native import parse_args
from native import BuildError, git_blob


class EducationCompositionPlan(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.backend = self.root / 'backend'
        (self.backend / 'script/sql').mkdir(parents=True)
        self.base = self.backend / 'script/sql/ry_vue_5.X.sql'
        self.flow = self.backend / 'script/sql/ry_workflow.sql'
        self.base.write_text('\n'.join('CREATE TABLE `' + name + '` (id BIGINT);' for name in ('sys_user', 'sys_role', 'sys_user_role', 'sys_tenant')))
        self.flow.write_text('CREATE TABLE flow_case (id BIGINT);\n')
        directory = self.root / 'sql/native'
        directory.mkdir(parents=True)
        self.migration = directory / 'V001__test.sql'
        self.migration.write_text('CREATE TABLE IF NOT EXISTS fcp_test (id BIGINT);\n')
        self.lock = directory / 'migrations.lock.json'
        self.relock()
        self.blobs = {'script/sql/' + path.name: git_blob(path.read_bytes()) for path in (self.base, self.flow)}
        self.patch = patch.dict(integration.BLOBS, {'backend': self.blobs})
        self.patch.start()
        self.addCleanup(self.patch.stop)

    def relock(self):
        self.lock.write_text(json.dumps({'format': 1, 'files': {self.migration.name: hashlib.sha256(self.migration.read_bytes()).hexdigest()}}))

    def plan(self):
        return integration.verified_host_plan(self.backend, root=self.root)

    def test_expected_tables_come_from_verified_sql_and_include_install_ledgers(self):
        plan = self.plan()
        self.assertEqual(set(plan['expectedHostTables']), {'sys_user', 'sys_role', 'sys_user_role', 'sys_tenant', 'flow_case', 'fcp_test', 'fcp_install_history', 'fcp_migration_history'})
        self.assertEqual(len(plan['stages']), 3)
        self.assertTrue(all('sql' not in stage for stage in plan['stages']))
        self.assertEqual(self.plan()['planSha256'], plan['planSha256'])

    def test_upstream_drift_or_symlink_refuses(self):
        self.base.write_text(self.base.read_text() + '\n-- changed')
        with self.assertRaises(BuildError): self.plan()
        self.base.unlink()
        self.base.symlink_to(self.flow)
        with self.assertRaises(BuildError): self.plan()

    def test_locked_migration_drift_refuses(self):
        self.migration.write_text(self.migration.read_text() + '\n-- changed')
        with self.assertRaisesRegex(ValueError, 'checksum'): self.plan()

    def test_migration_escape_or_preexisting_education_table_is_not_adopted(self):
        for sql in ('USE different_database;\n', 'CREATE TABLE ed_unreviewed (id BIGINT);\n'):
            self.migration.write_text(sql)
            self.relock()
            with self.subTest(sql=sql), self.assertRaises(ValueError): self.plan()

    def test_unsafe_locked_name_refuses_before_path_access(self):
        self.lock.write_text(json.dumps({'format': 1, 'files': {'../unexpected.sql': '0' * 64}}))
        with self.assertRaisesRegex(ValueError, 'Unsafe'): self.plan()

    def test_native_education_needs_real_ingestion_and_its_own_mode(self):
        self.assertTrue(parse_args(['--education', '--ingestion']).education)
        for args in (['--education'], ['--education', '--ingestion', '--exchange'], ['--education', '--ingestion', '--plugin-releases']):
            with self.subTest(args=args), redirect_stderr(io.StringIO()), self.assertRaises(SystemExit): parse_args(args)

    def test_education_grants_are_seeded_after_old_native_modules(self):
        source = (ROOT / 'tools/ci_native.py').read_text()
        self.assertLess(source.index("NativeModule('runtimeFailureBoundaries'"), source.index('education_fixture.seed()'))
        self.assertLess(source.index('command(build)'), source.index('education_fixture=EducationFixture('))
        self.assertLess(source.index('prepare_schema(database,sql'), source.index("ROOT/'tools/mysql_install.py'"))
        self.assertIn("education_fixture.verify('http://127.0.0.1:18787'", source)
        self.assertIn("password={education_ddl_password}\\nssl-mode=REQUIRED\\n", source)
        self.assertLess(source.index('result=education_fixture.verify('), source.index('report[\'educationBrowserFixture\']=restore_browser_fixture_permission('))

    def test_browser_fixture_restoration_requires_owned_expected_state(self):
        from unittest.mock import Mock
        fixture = SimpleNamespace(plan=SimpleNamespace(database='fcp_ci_012345abcdef',applied=True,output=self.root),report={'status':'PASS'},sql=Mock(side_effect=['0|1','1','1|2']))
        with patch.dict('os.environ', {'GITHUB_ACTIONS':'true'}):
            self.assertEqual(integration.restore_browser_fixture_permission(fixture)['after'], '1|2')
            statements = [call.args[0] for call in fixture.sql.call_args_list]
            self.assertIn('AND enabled=0 AND row_version=1', statements[1])
            fixture.sql.reset_mock(side_effect=True)
            fixture.sql.side_effect = ['0|3']
            with self.assertRaisesRegex(ValueError,'expected'): integration.restore_browser_fixture_permission(fixture)
            self.assertEqual(fixture.sql.call_count,1)
            fixture.plan.database = 'existing_user_db'
            fixture.sql.reset_mock()
            with self.assertRaises(ValueError): integration.restore_browser_fixture_permission(fixture)
            fixture.sql.assert_not_called()


if __name__ == '__main__': unittest.main()
