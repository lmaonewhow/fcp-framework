"""Deterministic metadata with a closed operation set. Never emits executable SQL."""
import hashlib
import json
from pathlib import Path
import re

from .scalars import ContractError, tenant_id, text, unsigned_bigint

BASELINE = 'design/education-platform/v4.2.1'
SOURCE_FILES = (
    'database/model.json', 'database/sql/P042__fresh_isolated_schema.sql',
    'contracts/data-contracts.json', 'docs/02_数据库完整设计基线.md',
    'docs/03_事务与版本协议.md', 'docs/11_最终核查与4.2.1修订.md',
    'tools/generate_schema.py', 'tools/check_design.py', 'tools/common.py',
)
ROOT = Path(__file__).resolve().parents[2]
LOCK = 'contracts/education/sources.lock.json'
CATALOG = 'contracts/education/catalog.json'


def read_json(path):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ContractError('Duplicate JSON key: ' + key)
            result[key] = value
        return result
    return json.loads(path.read_text(encoding='utf-8'), object_pairs_hook=unique)


def verify_sources(root=ROOT):
    root = Path(root)
    lock = read_json(root / LOCK)
    expected = {f'{BASELINE}/{name}' for name in SOURCE_FILES}
    if lock.get('format') != 1 or set(lock.get('sha256', {})) != expected:
        raise ContractError('Missing or unknown locked source')
    for name, digest in lock['sha256'].items():
        actual = hashlib.sha256((root / name).read_bytes()).hexdigest()
        if actual != digest:
            raise ContractError('Source digest mismatch: ' + name)
    return root


def generate(root=ROOT):
    root = verify_sources(root)
    model = read_json(root / BASELINE / 'database/model.json')
    if model.get('schema_version') != 'P042' or len(model['tables']) != 54:
        raise ContractError('Unexpected model revision or table count')
    tables = []
    for table in model['tables']:
        columns = []
        unresolved = ['DOMAIN_COMMANDS_NOT_IMPLEMENTED', 'NATIVE_AUTHORIZATION_NOT_BOUND']
        if 'immutable' not in table:
            unresolved.append('TABLE_IMMUTABILITY_UNSPECIFIED')
        for column in table['columns']:
            classification = column.get('classification')
            if classification is None:
                unresolved.append('FIELD_CLASSIFICATION_MISSING:' + column['name'])
            sql_type = column['type']
            scalar = ('opaque_tenant' if column['name'] == 'tenant_id' else
                      'unsigned_decimal_string' if sql_type == 'BIGINT UNSIGNED' else
                      'exact_decimal_string' if sql_type.startswith('DECIMAL(') else
                      'utc_timestamp' if sql_type == 'DATETIME(6)' else
                      'business_date' if sql_type == 'DATE' else 'requires_field_contract')
            columns.append({
                'name': column['name'], 'sql_type': sql_type, 'nullable': column['nullable'],
                'description': column['description'], 'scalar_contract': scalar,
                'classification': classification, 'client_writable': False,
                'display_policy': 'DENY_UNTIL_PURPOSE_AUTHORIZED',
                'sensitive_or_unclassified': classification != '普通',
                'immutable_identity': column['name'] in table['primary_key'],
                'server_managed': column['name'] in ('tenant_id', 'id', 'row_version', 'created_at', 'updated_at'),
                'reference': column.get('reference'),
                'reference_enforcement': column.get('reference_enforcement'),
            })
        names = [c['name'] for c in columns]
        if len(set(names)) != len(names) or not set(table['primary_key']) <= set(names):
            raise ContractError('Invalid column/key inventory')
        tables.append({
            'name': table['name'], 'owner': table['owner'], 'phase': table['phase'],
            'purpose': table['purpose'], 'immutable': table.get('immutable'),
            'primary_key': table['primary_key'], 'columns': columns,
            'cas': {'column': 'row_version', 'expected': 'canonical unsigned decimal string',
                    'commit': 'WHERE full_primary_key AND row_version = expected; increment atomically; require affected_rows = 1'}
                   if 'row_version' in names else None,
            'foreign_keys': table['foreign_keys'], 'unique': table['unique'],
            'checks': table['checks'], 'domain_invariants': table.get('domain_invariants', []),
            'operations': [], 'state_transitions': [], 'unresolved': unresolved,
        })
    if len({t['name'] for t in tables}) != 54:
        raise ContractError('Duplicate table')
    return {'format': 1, 'schema': 'P042', 'scope': 'METADATA_ONLY_NOT_NATIVE',
            'source_lock': read_json(root / LOCK), 'tables': tables}


def load_catalog(root=ROOT):
    """Recompute and compare, so editing generated permissions cannot grant access."""
    generated = generate(root)
    stored = read_json(Path(root) / CATALOG)
    if stored != generated:
        raise ContractError('Generated catalog drift')
    return generated


def describe_table(name, root=ROOT):
    for table in load_catalog(root)['tables']:
        if table['name'] == name:
            return table
    raise ContractError('Unknown table')


def require_operation(table_name, operation, root=ROOT):
    describe_table(table_name, root)
    # No source currently specifies an implemented/authorized domain command.
    # This remains DENY even for SELECT: a catalog is not a data-read grant.
    raise ContractError('Operation not enabled: reviewed domain and host contract required')


def validate_key(table_name, values, trusted_tenant, root=ROOT):
    """Validate a full internal key, not a browser authorization or query request."""
    table = describe_table(table_name, root)
    tenant_id(trusted_tenant)
    if type(values) is not dict or set(values) != set(table['primary_key']):
        raise ContractError('Exactly the full composite primary key is required')
    if values.get('tenant_id') != trusted_tenant:
        raise ContractError('Tenant mismatch')
    columns = {c['name']: c for c in table['columns']}
    for name, value in values.items():
        sql_type = columns[name]['sql_type']
        if name == 'tenant_id':
            tenant_id(value)
        elif sql_type == 'BIGINT UNSIGNED':
            unsigned_bigint(value)
        elif match := re.fullmatch(r'(?:VAR)?CHAR\(([0-9]+)\)(?: .*|)', sql_type):
            text(value, int(match[1]))
            if 'CHARACTER SET ascii' in sql_type and not value.isascii():
                raise ContractError('ASCII key required')
        else:
            raise ContractError('Unreviewed key scalar type')
    return dict(values)


def cas_precondition(table_name, key, trusted_tenant, expected_version, root=ROOT):
    """Describe a precondition only. Caller still must enforce it in a real transaction."""
    table = describe_table(table_name, root)
    if table['cas'] is None or table['immutable'] is not False:
        raise ContractError('Table has no reviewed mutable-row CAS contract')
    checked = validate_key(table_name, key, trusted_tenant, root)
    unsigned_bigint(expected_version)
    if expected_version == '18446744073709551615':
        raise ContractError('Version exhausted')
    return {'key': checked, 'expected_version': expected_version,
            'next_version': str(int(expected_version) + 1), 'required_affected_rows': 1}
