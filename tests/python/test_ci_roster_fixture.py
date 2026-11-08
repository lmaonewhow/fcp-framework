"""Local source/seed/ownership boundary regressions, NOT native HTTP/MySQL PASS."""
import json
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools'))
import ci_roster as roster


class RosterFixtureBoundary(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.environment = patch.dict('os.environ', GITHUB_ACTIONS='true')
        self.environment.start()
        self.addCleanup(self.environment.stop)
        self.root_patch = patch.object(roster, 'ROOT', self.root)
        self.root_patch.start()
        self.addCleanup(self.root_patch.stop)
        self.database = 'fcp_ci_012345abcdef'
        self.host = SimpleNamespace(database=self.database, diagnostic_secrets=[])
        self.plan = SimpleNamespace(database=self.database, applied=True,
                                    receipt=dict(database=self.database, createdThisRun=True,
                                                 initialTableCount=0, runId='a' * 32))
        self.sql = Mock()
        self.foundation = SimpleNamespace(host=self.host, plan=self.plan, digest='b' * 64,
                                          sql=self.sql, report=dict(status='PASS', completed=True))

    def fixture(self):
        return roster.RosterFixture(self.foundation)

    def test_constructor_requires_current_run_applied_owned_schema_before_any_sql(self):
        cases = [('database', 'user_database'), ('applied', False)]
        for key, invalid in cases:
            original = getattr(self.plan, key)
            setattr(self.plan, key, invalid)
            with self.subTest(key=key), self.assertRaises(ValueError):
                self.fixture()
            setattr(self.plan, key, original)
        for key, invalid in [('database', 'fcp_ci_ffffffffffff'), ('createdThisRun', False),
                             ('initialTableCount', 1), ('runId', 'old')]:
            original = self.plan.receipt[key]
            self.plan.receipt[key] = invalid
            with self.subTest(key=key), self.assertRaises(ValueError):
                self.fixture()
            self.plan.receipt[key] = original
        with patch.dict('os.environ', GITHUB_ACTIONS='false'), self.assertRaises(ValueError):
            self.fixture()
        self.sql.assert_not_called()

    def test_bad_manifest_or_mismatched_host_refuses(self):
        self.foundation.digest = 'not-an-artifact'
        with self.assertRaises(ValueError):
            self.fixture()
        self.foundation.digest = 'b' * 64
        self.host.database = 'fcp_ci_abcdef012345'
        with self.assertRaises(ValueError):
            self.fixture()
        self.sql.assert_not_called()

    def test_properties_separate_capability_and_actual_jar_digest(self):
        fixture = self.fixture()
        self.assertEqual(fixture.properties(), '\nfcp.education.roster.enabled=true\nFCP_EDUCATION_ROSTER_MANIFEST_DIGEST=' + 'b' * 64 + '\n')
        self.assertEqual(fixture.output, self.root / 'reports/education-roster-native/business')
        self.assertEqual(fixture.report['concurrentRevocation'], 'NOT_RUN')
        self.assertEqual(fixture.report['personDisplayPolicy'], 'PENDING')
        self.sql.assert_not_called()

    def test_non_loopback_or_changed_origin_refused_before_http_or_sql(self):
        fixture = self.fixture()
        for target in ('https://example.com', 'http://127.0.0.1/private', 'http://secret@localhost', 'http://localhost?token=secret'):
            with self.subTest(target=target), self.assertRaises(ValueError):
                fixture.verify_foundation_only(target, 'a' * 32)
        fixture._base = 'http://127.0.0.1:18787'
        with self.assertRaises(ValueError):
            fixture._guard('http://127.0.0.1:8080')
        self.sql.assert_not_called()

    def test_seed_cannot_precede_real_preseed_gate_or_repeat(self):
        fixture = self.fixture()
        with self.assertRaises(ValueError):
            fixture.seed(foundation_browser_report={})
        fixture._base = 'http://127.0.0.1:18787'
        fixture.report['foundationOnly'] = 'PASS'
        fixture.report['seed'] = 'APPLIED_SYNTHETIC_ONLY'
        with self.assertRaises(ValueError):
            fixture.seed(foundation_browser_report={})
        self.sql.assert_not_called()

    def test_seed_requires_completed_same_build_foundation_browser(self):
        fixture = self.fixture()
        fixture.report['buildId'] = 'c' * 32
        for report in ({}, dict(status='FAIL', completed=True, buildId='c' * 32),
                       dict(status='PASS', completed=False, buildId='c' * 32),
                       dict(status='PASS', completed=True, buildId='d' * 32),
                       dict(status='PASS', completed=True, buildId='c' * 32, pendingSchoolId='800001')):
            with self.subTest(report=report), self.assertRaises(ValueError):
                fixture._prerequisites(report)
        self.sql.assert_not_called()

    def test_seed_plan_never_reinserts_foundation_prerequisites(self):
        rows = roster.seed_rows('b' * 64)
        self.assertFalse(any(row.table in ('ed_core_tenant_binding', 'ed_core_access_epoch',
                                          'ed_education_org_unit', 'ed_education_term') for row in rows))
        fences = [row for row in rows if row.table == 'ed_core_write_fence']
        self.assertEqual([row.values['fence_key'] for row in fences], ['cap:education.roster'])
        grants = [row for row in rows if row.table == 'ed_core_execution_grant']
        self.assertEqual({row.values['scope_key'] for row in grants}, {'tenant', 'school:800001', 'school:800002'})
        self.assertTrue(all(row.values['capability_key'] == 'education.roster' and row.values['manifest_digest'] == 'b' * 64 for row in grants))
        all_keys = [(row.table, row.where()) for row in rows]
        self.assertEqual(len(all_keys), len(set(all_keys)))

    def test_role_names_native_length_and_authority_split_are_exact(self):
        rows = roster.seed_rows('b' * 64)
        roles = [row for row in rows if row.table == 'sys_role']
        self.assertEqual({row.values['role_id'] for row in roles}, {988101, 988102, 988103})
        self.assertTrue(all(len(row.values['role_name']) <= 30 for row in roles))
        grants = [row.values for row in rows if row.table == 'ed_host_roster_role']
        self.assertEqual({(r['native_role_id'], r['admin_kind'], r['school_id'], r['operation_key']) for r in grants},
                         {(988101, 'TENANT_ADMIN', 0, 'student:read'), (988101, 'TENANT_ADMIN', 0, 'teacher:read'),
                          (988102, 'SCHOOL_ADMIN', 800001, 'student:read'), (988103, 'SCHOOL_ADMIN', 800001, 'teacher:read')})
        self.assertTrue(all(r['purpose_key'] == roster.PURPOSE and r['source_approval_id'] == roster.APPROVAL for r in grants))
        self.assertFalse(any(row.values.get('role_id', 0) in (987101, 987102, 987103) for row in rows))

    def test_synthetic_ids_cross_school_shared_person_and_max_uint_are_real_seed_values(self):
        rows = roster.seed_rows('b' * 64)
        for kind in roster.KINDS:
            table = {'enrollments': 'ed_education_enrollment', 'memberships': 'ed_education_membership', 'assignments': 'ed_education_assignment'}[kind]
            records = [r.values for r in rows if r.table == table]
            self.assertEqual(len(records), 5)
            max_record = next(r for r in records if r['id'] == roster.MAX_U64)
            self.assertEqual(max_record['row_version'], roster.MAX_U64)
        shared = [r.values for r in rows if r.table == 'ed_education_enrollment' and r.values['tenant_id'] == '000000' and r.values['person_id'] == '880001']
        self.assertEqual({r['school_id'] for r in shared}, {'800001', '800002'})
        persons = [r.values for r in rows if r.table == 'ed_identity_person']
        self.assertEqual([r['tenant_id'] for r in persons if r['id'] == 880099], ['education-other'])
        self.assertEqual({r['status'] for r in persons}, {'ACTIVE', 'LEFT', 'RESTRICTED'})
        self.assertTrue(all(r['display_name'].startswith(roster.SENTINELS[0]) and r['private_ref'].startswith(roster.SENTINELS[1]) for r in persons))

    def test_recursive_identity_fields_and_sentinel_rejected(self):
        for key in ('personId', 'display_name', 'studentCode', 'subjectCode', 'changeReason', 'private_ref', 'account', 'guardian'):
            with self.subTest(key=key), self.assertRaises(AssertionError):
                roster.assert_public({'items': [{'nested': {key: 'synthetic'}}]})
        for sentinel in roster.SENTINELS:
            with self.subTest(sentinel=sentinel), self.assertRaises(AssertionError):
                roster.assert_public({'error': {'message': sentinel + '123'}})
        roster.assert_public(dict(items=[dict(id=roster.MAX_U64, personDisplay=dict(status='PENDING'))]))

    def test_metadata_dto_is_closed_and_ids_cannot_be_coerced(self):
        record = dict(id=roster.MAX_U64, schoolId='800001', validFrom='2026-01-01T00:00:00.123456Z',
                      validTo=None, createdAt='2026-01-01T00:00:00Z', rowVersion=roster.MAX_U64,
                      personDisplay=dict(status='PENDING', reason='PERSON_DISPLAY_POLICY_PENDING'),
                      admissionKey='CI2026', status='ACTIVE')
        roster.check_record(record, 'enrollments', '800001')
        for change in (dict(id=int(roster.MAX_U64)), dict(personId='880001'), dict(schoolId='800002'),
                       dict(validFrom='2026-01-01 00:00:00'), dict(personDisplay=dict(status='VISIBLE'))):
            with self.subTest(change=change), self.assertRaises((AssertionError, ValueError)):
                roster.check_record({**record, **change}, 'enrollments', '800001')

    def test_seed_columns_match_locked_model_and_strings_fit_declared_lengths(self):
        import re
        model = json.loads((ROOT / 'design/education-platform/v4.2.1/database/model.json').read_text())
        tables = {table['name']: {column['name']: column for column in table['columns']} for table in model['tables']}
        for row in roster.seed_rows('b' * 64):
            if row.table not in tables:
                continue
            columns = tables[row.table]
            self.assertTrue(set(row.values).issubset(columns), row.table)
            for name, value in row.values.items():
                size = re.search(r'(?:VAR)?CHAR\((\d+)\)', columns[name]['type'])
                if size and value is not None:
                    self.assertLessEqual(len(str(value)), int(size.group(1)), (row.table, name))
            for name, column in columns.items():
                if not column['nullable'] and column.get('default') is None:
                    self.assertIn(name, row.values, (row.table, name))

    def test_uint64_is_string_only_and_lossless(self):
        self.assertEqual(roster.uint64(roster.MAX_U64), roster.MAX_U64)
        self.assertEqual(roster.uint64('9007199254740993'), '9007199254740993')
        for invalid in (18446744073709551615, True, '01', '-1', '18446744073709551616', '', '1.0'):
            with self.subTest(value=invalid), self.assertRaises(ValueError):
                roster.uint64(invalid)

    def test_sql_literals_do_not_depend_on_quote_escaping_and_identifiers_are_bounded(self):
        self.assertEqual(roster.literal("a'b\\c"), "CONVERT(X'6127625c63' USING utf8mb4)")
        self.assertEqual(roster.literal(None), 'NULL')
        with self.assertRaises(ValueError):
            roster.Row('sys_role; DELETE', {'id': 1}, ('id',))
        with self.assertRaises(ValueError):
            roster.Row('sys_role', {'id': 1}, ())

    def test_numeric_sql_predicates_never_coerce_uint64_through_string_or_double(self):
        row = roster.Row('ed_education_enrollment', dict(tenant_id='000000', id=roster.MAX_U64,
                                                        row_version='18446744073709551614'), ('tenant_id', 'id'))
        self.assertIn('`id` <=> 18446744073709551615', row.where())
        self.assertIn('`row_version` <=> 18446744073709551614', row.where(True))
        self.assertIn(',18446744073709551615,18446744073709551614)', row.insert())
        with self.assertRaises(ValueError):
            roster.literal('18446744073709551616', column='id')

    def _mutable(self):
        fixture = self.fixture()
        fixture._base = 'http://127.0.0.1:18787'
        fixture.report['seed'] = 'APPLIED_SYNTHETIC_ONLY'
        return fixture, fixture._owned('ed_core_write_fence')

    def test_mutation_cas_and_restore_exact_preimage_even_on_assertion_failure(self):
        fixture, before = self._mutable()
        after = before.changed(enabled=0)
        self.sql.side_effect = [json.dumps(before.expected()), '1', json.dumps(after.expected()),
                                json.dumps(after.expected()), '1', json.dumps(before.expected())]
        with self.assertRaisesRegex(AssertionError, 'business rejection'):
            with fixture.mutation('test disabled', before, enabled=0):
                raise AssertionError('business rejection')
        entry = fixture.report['mutations'][0]
        self.assertEqual(entry['restore'], 'PASS_EXACT_PREIMAGE')
        statements = [call.args[0] for call in self.sql.call_args_list]
        self.assertIn(before.where(True), statements[1])
        self.assertIn(after.where(True), statements[4])
        self.assertTrue(all('DELETE' not in sql and 'DROP' not in sql and 'TRUNCATE' not in sql for sql in statements))

    def test_mutation_bad_preimage_never_updates(self):
        fixture, before = self._mutable()
        self.sql.return_value = json.dumps(before.changed(epoch=5).expected())
        with self.assertRaises(ValueError):
            with fixture.mutation('bad state', before, enabled=0):
                self.fail('Should not enter mutation')
        self.assertEqual(self.sql.call_count, 1)
        self.assertFalse(fixture.report['mutations'])

    def test_mutation_refuses_unowned_or_foundation_role_before_read(self):
        fixture, before = self._mutable()
        for row, changes in ((roster.Row('sys_role', dict(role_id=987102, status='0'), ('role_id',)), dict(status='1')),
                             (before, dict(epoch=1))):
            with self.subTest(table=row.table), self.assertRaises(ValueError):
                with fixture.mutation('outside plan', row, **changes):
                    self.fail('Must not enter')
        self.sql.assert_not_called()

    def test_uncertain_mutation_does_not_overwrite_unknown_state(self):
        fixture, before = self._mutable()
        after = before.changed(enabled=0)
        unknown = before.changed(epoch=9).expected()
        self.sql.side_effect = [json.dumps(before.expected()), RuntimeError('connection interrupted'),
                                json.dumps(unknown), json.dumps(unknown)]
        with self.assertRaisesRegex(AssertionError, 'uncertain'):
            with fixture.mutation('uncertain', before, enabled=0):
                self.fail('Must not enter')
        self.assertEqual(sum(call.args[0].startswith('UPDATE ') for call in self.sql.call_args_list), 1)
        self.assertEqual(fixture.report['mutations'][0]['restore'], 'FAIL_UNEXPECTED_STATE_NO_OVERWRITE')

    def test_uncertain_mutation_reads_outcome_then_restores_only_if_exact(self):
        fixture, before = self._mutable()
        after = before.changed(enabled=0)
        self.sql.side_effect = [json.dumps(before.expected()), RuntimeError('connection interrupted'),
                                json.dumps(after.expected()), '1', json.dumps(before.expected())]
        with self.assertRaisesRegex(RuntimeError, 'interrupted'):
            with fixture.mutation('uncertain', before, enabled=0):
                self.fail('Must not enter')
        self.assertEqual(fixture.report['mutations'][0]['restore'], 'PASS_EXACT_PREIMAGE')

    def test_key_movement_refuses_occupied_temporary_target(self):
        fixture, before = self._mutable()
        after = before.changed(fence_key='cap:education.roster:ci-missing')
        self.sql.side_effect = [json.dumps(before.expected()), json.dumps(after.expected())]
        with self.assertRaisesRegex(ValueError, 'occupied'):
            with fixture.mutation('missing', before, fence_key=after.values['fence_key']):
                self.fail('Must not enter')
        self.assertFalse(any(call.args[0].startswith('UPDATE ') for call in self.sql.call_args_list))

    def test_shared_epoch_requires_exact_original_actor_and_captures_timestamps(self):
        fixture = self.fixture()
        values = ['000000', '820002', '950001', '0', roster.STAMP, roster.STAMP, '0', '1']
        self.sql.return_value = json.dumps(values)
        self.assertEqual(fixture._epoch_row().expected(), values)
        values[2] = '950002'
        self.sql.return_value = json.dumps(values)
        with self.assertRaises(ValueError):
            fixture._epoch_row()

    def test_source_uses_real_native_login_and_no_http_response_substitution(self):
        source = (ROOT / 'tools/ci_roster.py').read_text()
        self.assertIn('native_token(self.host.login(browser, base, role), base)', source)
        self.assertIn('playwright.chromium.launch()', source)
        self.assertIn('self._clients(browser, base)', source)
        self.assertNotIn('route.fulfill(', source)
        self.assertNotIn('page.route(', source)
        self.assertNotIn('DROP DATABASE', source)
        self.assertNotIn('TRUNCATE ', source)
        self.assertIn("unknownCommitFaultInjection='NOT_APPLICABLE_READ_ONLY'", source)
        self.assertIn("concurrentRevocation='NOT_RUN'", source)


if __name__ == '__main__':
    unittest.main()
