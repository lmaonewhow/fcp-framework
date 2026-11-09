import copy
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from runtime.education_contracts.scalars import (
    ContractError, tenant_id, unsigned_bigint, decimal_string, utc_timestamp, business_date,
)
from runtime.education_contracts.catalog import (
    BASELINE, SOURCE_FILES, LOCK, CATALOG, generate, load_catalog,
    describe_table, require_operation, validate_key, cas_precondition,
)


class Scalars(unittest.TestCase):
    def reject_each(self, function, values):
        for value in values:
            with self.subTest(value=repr(value)):
                with self.assertRaises(ContractError):
                    function(value)

    def test_tenant_is_opaque(self):
        for value in ('  原始租户  ', 'tenant-000000', 'Tenant', 'tenant', 'é', 'e\u0301', ' '):
            self.assertEqual(tenant_id(value), value)
        self.assertNotEqual(tenant_id('tenant '), tenant_id('tenant'))
        self.assertNotEqual(tenant_id('é'), tenant_id('e\u0301'))

    def test_tenant_character_not_byte_limit(self):
        self.assertEqual(tenant_id('校' * 100), '校' * 100)
        self.assertEqual(tenant_id('😀' * 100), '😀' * 100)
        self.reject_each(tenant_id, ['', '校' * 101, None, 100, True, [], {}, '\ud800'])

    def test_bigint_extremes_and_javascript_unsafe_values(self):
        for value in ('0', '1', '9007199254740993', '18446744073709551615'):
            self.assertEqual(unsigned_bigint(value), value)
        self.reject_each(unsigned_bigint, ['18446744073709551616', '00', '01', '-1', '+1',
            '1.0', '1e3', ' 1', '1\n', '１', '١', '', 1, 1.0, True, None, '9' * 10000])

    def test_school_ids_are_explicitly_positive(self):
        self.assertEqual(unsigned_bigint('1', positive=True), '1')
        with self.assertRaises(ContractError):
            unsigned_bigint('0', positive=True)

    def test_decimal_exact_and_scale(self):
        for value in ('0', '-0.0000', '99999999.9999', '-99999999.9999', '0.1000'):
            self.assertEqual(decimal_string(value, 12, 4), value)
        self.assertEqual(decimal_string('999999999999.9999', 16, 4), '999999999999.9999')
        self.reject_each(lambda v: decimal_string(v, 12, 4), [1, 0.1, True, None, 'NaN',
            'Infinity', '1e1', '1.00000', '100000000', '-100000000', '+1', '.1', '1.',
            '01', ' 1', '1\n', '١.١', '9' * 10000])

    def test_account_unit_scale_never_rounds(self):
        self.assertEqual(decimal_string('10.12', 16, 4, unit_scale=2), '10.12')
        with self.assertRaises(ContractError):
            decimal_string('10.120', 16, 4, unit_scale=2)
        with self.assertRaises(ContractError):
            decimal_string('10.1', 16, 4, unit_scale=0)
        for scale in (-1, 5, True):
            with self.assertRaises(ContractError):
                decimal_string('1', 16, 4, unit_scale=scale)

    def test_decimal_declaration_rejected(self):
        for p, s in ((0, 0), (66, 4), (12, 13), (12, -1), (True, 1), (12, True)):
            with self.assertRaises(ContractError):
                decimal_string('1', p, s)

    def test_utc_persistence_boundaries(self):
        for value in ('1000-01-01T00:00:00Z', '2024-02-29T23:59:59.123456Z',
                      '9999-12-31T23:59:59.499999Z'):
            self.assertEqual(utc_timestamp(value), value)
        self.reject_each(utc_timestamp, ['0999-12-31T23:59:59Z',
            '9999-12-31T23:59:59.500000Z', '2025-02-29T00:00:00Z',
            '2026-01-01T00:00:00.1234567Z', '2026-01-01T00:00:00+00:00',
            '2026-01-01 00:00:00Z', '2026-01-01T00:00:60Z', '2026-01-01', None])

    def test_business_date_is_not_timestamp(self):
        self.assertEqual(business_date('2024-02-29'), '2024-02-29')
        self.reject_each(business_date, ['2025-02-29', '2026-1-01',
            '2026-01-01T00:00:00Z', '0999-01-01', None, 20260101])


class RealCatalog(unittest.TestCase):
    def test_inventory_matches_real_model(self):
        catalog = load_catalog()
        model = json.loads((ROOT / BASELINE / 'database/model.json').read_text())
        self.assertEqual(len(catalog['tables']), 54)
        self.assertEqual(sum(len(t['columns']) for t in catalog['tables']), 548)
        self.assertEqual(sum(len(t['foreign_keys']) for t in catalog['tables']), 40)
        for actual, source in zip(catalog['tables'], model['tables']):
            self.assertEqual(actual['primary_key'], source['primary_key'])
            self.assertEqual(actual['checks'], source['checks'])
            self.assertEqual(actual['foreign_keys'], source['foreign_keys'])
            self.assertEqual(actual['unique'], source['unique'])
            self.assertEqual(actual['domain_invariants'], source.get('domain_invariants', []))

    def test_locked_model_generates_exact_sql(self):
        process = subprocess.run([sys.executable, str(ROOT / BASELINE / 'tools/generate_schema.py')],
                                 text=True, capture_output=True)
        self.assertEqual(process.returncode, 0, process.stdout + process.stderr)
        self.assertIn('PASS_GENERATION_PARITY_ONLY', process.stdout)

    def test_every_operation_is_closed(self):
        for table in load_catalog()['tables']:
            self.assertEqual(table['operations'], [])
            self.assertEqual(table['state_transitions'], [])
            for operation in ('CREATE', 'READ', 'UPDATE', 'DELETE', 'raw_sql', '', None):
                with self.subTest(table=table['name'], operation=operation):
                    with self.assertRaises(ContractError):
                        require_operation(table['name'], operation)

    def test_all_fields_closed_and_unknown_classification_sensitive(self):
        unknown = []
        for table in load_catalog()['tables']:
            for field in table['columns']:
                self.assertFalse(field['client_writable'])
                self.assertEqual(field['display_policy'], 'DENY_UNTIL_PURPOSE_AUTHORIZED')
                if field['classification'] is None:
                    unknown.append((table['name'], field['name']))
                    self.assertTrue(field['sensitive_or_unclassified'])
        self.assertEqual(len(unknown), 6)
        current = describe_table('ed_identity_account_current')
        self.assertIsNone(current['immutable'])
        self.assertIn('TABLE_IMMUTABILITY_UNSPECIFIED', current['unresolved'])

    def test_unknown_table_rejected(self):
        for name in ('sys_user', 'ed_education_class; DROP TABLE x', '', None):
            with self.assertRaises(ContractError):
                describe_table(name)

    def test_composite_key_preserves_original_tenant(self):
        key = {'tenant_id': ' x ', 'id': '9007199254740993'}
        self.assertEqual(validate_key('ed_education_class', key, ' x '), key)
        for invalid in ({'id': '1'}, {'tenant_id': 'x', 'id': '1'},
                        {'tenant_id': ' x ', 'id': 1}, dict(key, extra='x')):
            with self.assertRaises(ContractError):
                validate_key('ed_education_class', invalid, ' x ')

    def test_natural_composite_key_is_not_an_id(self):
        key = {'tenant_id': 't', 'scope_key': 'school:1', 'capability_key': 'write',
               'manifest_digest': 'a' * 64}
        self.assertEqual(validate_key('ed_core_execution_grant', key, 't'), key)
        with self.assertRaises(ContractError):
            validate_key('ed_core_execution_grant', dict(key, manifest_digest='校' * 64), 't')
        current = {'tenant_id': 't', 'host_user_key': ' original '}
        self.assertEqual(validate_key('ed_identity_account_current', current, 't'), current)

    def test_cas_is_full_key_plus_expected_version(self):
        result = cas_precondition('ed_education_class', {'tenant_id': 't', 'id': '1'}, 't', '0')
        self.assertEqual(result, {'key': {'tenant_id': 't', 'id': '1'},
            'expected_version': '0', 'next_version': '1', 'required_affected_rows': 1})
        for version in ('18446744073709551615', None, 0, '00'):
            with self.assertRaises(ContractError):
                cas_precondition('ed_education_class', {'tenant_id': 't', 'id': '1'}, 't', version)

    def test_immutable_or_unspecified_cannot_get_mutation_precondition(self):
        for table, key in [('ed_eval_judgement', {'tenant_id': 't', 'id': '1'}),
                           ('ed_identity_account_current', {'tenant_id': 't', 'host_user_key': 'u'})]:
            with self.assertRaises(ContractError):
                cas_precondition(table, key, 't', '0')

    def test_scalar_kinds_keep_dates_separate(self):
        columns = [c for t in load_catalog()['tables'] for c in t['columns']]
        self.assertEqual(sum(c['scalar_contract'] == 'business_date' for c in columns), 3)
        self.assertEqual(sum(c['scalar_contract'] == 'utc_timestamp' for c in columns), 87)
        self.assertEqual(sum(c['scalar_contract'] == 'opaque_tenant' for c in columns), 54)


class Drift(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        for name in [f'{BASELINE}/{s}' for s in SOURCE_FILES] + [LOCK, CATALOG]:
            destination = self.root / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / name, destination)

    def test_model_sql_contract_and_docs_byte_drift_each_rejected(self):
        for name in SOURCE_FILES:
            path = self.root / BASELINE / name
            original = path.read_bytes()
            path.write_bytes(original + b'\n')
            with self.subTest(name=name):
                with self.assertRaises(ContractError):
                    load_catalog(self.root)
            path.write_bytes(original)

    def test_missing_source_fails(self):
        (self.root / BASELINE / 'database/model.json').unlink()
        with self.assertRaises(FileNotFoundError):
            load_catalog(self.root)

    def test_missing_lock_entry_fails(self):
        path = self.root / LOCK
        lock = json.loads(path.read_text())
        lock['sha256'].pop(next(iter(lock['sha256'])))
        path.write_text(json.dumps(lock))
        with self.assertRaises(ContractError):
            load_catalog(self.root)

    def test_tampering_generated_operations_does_not_grant_access(self):
        path = self.root / CATALOG
        data = json.loads(path.read_text())
        data['tables'][0]['operations'] = ['DELETE']
        path.write_text(json.dumps(data))
        with self.assertRaises(ContractError):
            require_operation(data['tables'][0]['name'], 'DELETE', self.root)

    def test_missing_generated_table_fails(self):
        path = self.root / CATALOG
        data = json.loads(path.read_text())
        data['tables'].pop()
        path.write_text(json.dumps(data))
        with self.assertRaises(ContractError):
            load_catalog(self.root)

    def test_duplicate_json_key_fails(self):
        (self.root / LOCK).write_text('{"format":1,"format":1,"sha256":{}}')
        with self.assertRaises(ContractError):
            load_catalog(self.root)


if __name__ == '__main__':
    unittest.main()
