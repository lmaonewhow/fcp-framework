"""Real HTTP roster acceptance on the current run's already applied CI schema.

No standalone database creation, production migration, fake login or HTTP doubles.
The orchestrator runs the full foundation gate before granting these synthetic roles.
"""
from __future__ import annotations

from contextlib import contextmanager
from dataclasses import dataclass, replace
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
from urllib.error import HTTPError

from native_client import NativeClient, require_loopback

ROOT = Path(__file__).resolve().parents[1]
PREFIX = '/fcp/education/roster'
FOUNDATION = '/fcp/education/foundation'
PURPOSE = 'school-roster-metadata-v1'
APPROVAL = 'synthetic-ci-roster-explicit-approval'
MAX_U64 = '18446744073709551615'
KINDS = ('enrollments', 'memberships', 'assignments')
SCHOOL_IDS = ('800001', '800002', '800003')
RELATION_IDS = {'800001': ['9007199254740993', '18446744073709551614', MAX_U64],
                '800002': ['9007199254740994'], '800003': ['9007199254740995']}
STAMP = '2026-01-01 00:00:00.000000'
NUMERIC_COLUMNS = {'id', 'school_id', 'term_id', 'class_id', 'person_id', 'enrollment_id',
                   'native_role_id', 'role_id', 'user_id', 'menu_id', 'parent_id', 'epoch',
                   'enabled', 'row_version', 'role_sort', 'create_by'}
FORBIDDEN_FIELDS = {'personid', 'displayname', 'studentcode', 'subjectcode', 'changereason',
                    'privateref', 'account', 'accountid', 'accountlinks', 'externalmap',
                    'username', 'guardian', 'guardians'}
SENTINELS = ('ROSTER_PII_NAME_', 'ROSTER_PII_PRIVATE_', '000ROSTER_PII_STUDENT_',
             'ROSTER_PII_SUBJECT_', 'ROSTER_PII_REASON_')


def uint64(value):
    if not isinstance(value, str) or not re.fullmatch(r'0|[1-9][0-9]{0,19}', value) or int(value) > int(MAX_U64):
        raise ValueError('Expected an exact uint64 string')
    return value


def assert_public(value):
    """Check every response, including error envelopes, without logging payloads."""
    if isinstance(value, dict):
        for key, item in value.items():
            if re.sub('[^a-z0-9]', '', key.lower()) in FORBIDDEN_FIELDS:
                raise AssertionError('Roster response exposes a forbidden identity field')
            assert_public(item)
    elif isinstance(value, list):
        for item in value:
            assert_public(item)
    elif isinstance(value, str) and (any(marker in value for marker in SENTINELS) or value in ('880001', '880002', '880003', '880099')):
        raise AssertionError('Roster response exposes a synthetic private sentinel')


class _HeaderObserver:
    """Observe actual urllib responses, preserving NativeClient's transport/error handling."""
    def __init__(self, opener):
        self.opener, self.cache_control = opener, None

    def open(self, *args, **kwargs):
        self.cache_control = None
        try:
            response = self.opener.open(*args, **kwargs)
        except HTTPError as error:
            self.cache_control = error.headers.get('Cache-Control', '')
            raise
        self.cache_control = response.headers.get('Cache-Control', '')
        return response


class RosterClient(NativeClient):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.opener = _HeaderObserver(self.opener)


def check_record(row, kind, school):
    common = {'id', 'schoolId', 'validFrom', 'validTo', 'createdAt', 'rowVersion', 'personDisplay'}
    extra = {'enrollments': {'admissionKey', 'status'},
             'memberships': {'enrollmentId', 'classId', 'className', 'termId', 'termCode', 'termStatus'},
             'assignments': {'classId', 'className', 'termId', 'termCode', 'termStatus', 'roleCode'}}
    if not isinstance(row, dict) or set(row) != common | extra[kind] or row['schoolId'] != school:
        raise AssertionError('Roster relation differs from the closed metadata DTO')
    for key, value in row.items():
        if key == 'id' or key.endswith('Id') or key == 'rowVersion':
            uint64(value)
    for key in ('validFrom', 'validTo', 'createdAt'):
        value = row[key]
        if key == 'validTo' and value is None:
            continue
        if not isinstance(value, str) or not re.fullmatch(r'\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,6})?Z', value):
            raise AssertionError('Roster time is not an explicit UTC timestamp')
    if row['personDisplay'] != dict(status='PENDING', reason='PERSON_DISPLAY_POLICY_PENDING'):
        raise AssertionError('Roster person display policy was not kept pending')
    assert_public(row)


def literal(value, *, column=None):
    if value is None:
        return 'NULL'
    if column in NUMERIC_COLUMNS:
        # An integer column compared with a string expression can coerce through
        # DOUBLE in MySQL. Keep uint64 predicates decimal/integer-exact too.
        return uint64(str(value))
    # SQL is generated only from reviewed synthetic rows and database-returned
    # preimages. UTF-8 hex literals avoid dependence on SQL quoting/session modes.
    return "CONVERT(X'" + str(value).encode('utf-8').hex() + "' USING utf8mb4)"


@dataclass(frozen=True)
class Row:
    table: str
    values: dict
    keys: tuple

    def __post_init__(self):
        if not re.fullmatch(r'[a-z][a-z0-9_]*', self.table) or any(not re.fullmatch(r'[a-z][a-z0-9_]*', k) for k in self.values):
            raise ValueError('Unsafe fixture SQL identifier')
        if not self.keys or not set(self.keys).issubset(self.values):
            raise ValueError('A bounded fixture key is required')

    def where(self, all_columns=False):
        return ' AND '.join('`' + k + '` <=> ' + literal(self.values[k], column=k)
                            for k in (self.values if all_columns else self.keys))

    def select(self, database):
        columns = ','.join('CAST(`' + name + '` AS CHAR)' for name in self.values)
        return f'SELECT JSON_ARRAY({columns}) FROM `{database}`.`{self.table}` WHERE {self.where()};'

    def insert(self):
        return (f'INSERT INTO `{self.table}` (' + ','.join('`' + k + '`' for k in self.values)
                + ') VALUES (' + ','.join(literal(v, column=k) for k, v in self.values.items()) + ');')

    def expected(self):
        return [None if value is None else str(value) for value in self.values.values()]

    def changed(self, **changes):
        if not set(changes).issubset(self.values):
            raise ValueError('Only snapshotted fixture columns may change')
        return replace(self, values={**self.values, **changes})


def seed_rows(digest):
    """Pure, deterministic seed plan. Never inserts foundation-owned prerequisites."""
    if not re.fullmatch('[a-f0-9]{64}', digest):
        raise ValueError('Actual native JAR SHA-256 required')
    rows = []
    def add(table, keys, **values):
        rows.append(Row(table, values, tuple(keys.split(','))))
    add('ed_core_write_fence', 'tenant_id,fence_key', tenant_id='000000', fence_key='cap:education.roster',
        epoch=0, enabled=1, created_at=STAMP, row_version=0)
    for scope in ('tenant', 'school:800001', 'school:800002'):
        add('ed_core_execution_grant', 'tenant_id,scope_key,capability_key,manifest_digest',
            tenant_id='000000', scope_key=scope, capability_key='education.roster', manifest_digest=digest,
            epoch=0, enabled=1, created_at=STAMP, row_version=0)
    for role, actor, name, kind, school, operations in (
        (988101, 1, 'Roster tenant admin', 'TENANT_ADMIN', 0, ('student:read', 'teacher:read')),
        (988102, 950001, 'Roster school student', 'SCHOOL_ADMIN', 800001, ('student:read',)),
        (988103, 950002, 'Roster school teacher', 'SCHOOL_ADMIN', 800001, ('teacher:read',))):
        if len(name) > 30:
            raise ValueError('Native role_name length exceeded')
        add('sys_role', 'role_id', role_id=role, tenant_id='000000', role_name=name,
            role_key='edu_roster_ci_' + str(role), role_sort=21, data_scope='5', status='0', del_flag='0',
            create_by=1, create_time='2026-01-01 00:00:00')
        add('sys_user_role', 'user_id,role_id', user_id=actor, role_id=role)
        menus = [942000, 942008, 986120, 986100]
        menus += [986101 if operation == 'student:read' else 986102 for operation in operations]
        for menu in menus:
            add('sys_role_menu', 'role_id,menu_id', role_id=role, menu_id=menu)
        for operation in operations:
            add('ed_host_roster_role', 'tenant_id,native_role_id,school_id,operation_key,purpose_key',
                tenant_id='000000', native_role_id=role, admin_kind=kind, school_id=school,
                operation_key=operation, purpose_key=PURPOSE, enabled=1, source_approval_id=APPROVAL,
                created_at=STAMP, row_version=0)
    for tenant, person, status in [('000000', 880001, 'ACTIVE'), ('000000', 880002, 'LEFT'),
                                  ('000000', 880003, 'RESTRICTED'), ('education-other', 880001, 'ACTIVE'),
                                  ('education-other', 880099, 'ACTIVE')]:
        add('ed_identity_person', 'tenant_id,id', tenant_id=tenant, id=person,
            display_name=SENTINELS[0] + str(person), private_ref=SENTINELS[1] + str(person),
            status=status, created_at=STAMP, row_version=0)
    for index, school in enumerate(SCHOOL_IDS):
        tenant = 'education-other' if school == '800003' else '000000'
        klass, term = str(890001 + index), str(810001 + index)
        add('ed_education_class', 'tenant_id,id', tenant_id=tenant, id=klass, school_id=school,
            term_id=term, class_code='ROSTER-CI-' + school, name='Synthetic roster class ' + school,
            grade_code='G1', created_at=STAMP, row_version=0)
        for position, ident in enumerate(RELATION_IDS[school]):
            person = str(880001 + position)
            start = '2099-01-01 00:00:00.000000' if position == 2 else STAMP
            end = '2026-06-01 00:00:00.000000' if position == 1 else None
            version = MAX_U64 if position == 2 else '0'
            add('ed_education_enrollment', 'tenant_id,id', tenant_id=tenant, id=ident, person_id=person,
                school_id=school, student_code=SENTINELS[2] + ident, admission_key='CI2026',
                valid_from=start, valid_to=end, status='GRADUATED' if position == 1 else 'ACTIVE',
                created_at=STAMP, row_version=version)
            add('ed_education_membership', 'tenant_id,id', tenant_id=tenant, id=ident,
                enrollment_id=ident, class_id=klass, school_id=school, valid_from=start, valid_to=end,
                change_reason=SENTINELS[4] + ident, created_at=STAMP, row_version=version)
            add('ed_education_assignment', 'tenant_id,id', tenant_id=tenant, id=ident, person_id=person,
                class_id=klass, subject_code=SENTINELS[3] + ident, role_code=('TEACHER', 'SUBSTITUTE', 'HEAD')[position],
                valid_from=start, valid_to=end, created_at=STAMP, row_version=version)
    return rows


class RosterFixture:
    def __init__(self, education_fixture):
        self.foundation = education_fixture
        self.host, self.plan, self.digest = education_fixture.host, education_fixture.plan, education_fixture.digest
        self.sql = education_fixture.sql
        self._guard()
        self.rows = seed_rows(self.digest)
        self.output = ROOT / 'reports/education-roster-native/business'
        self.output.mkdir(parents=True, exist_ok=False)
        self.report = dict(status='RUNNING', completed=False, scope='REAL_NATIVE_EDUCATION_ROSTER_READ_ONLY',
                           runId=self.plan.receipt['runId'], database=self.plan.database, checks=[], mutations=[],
                           production='BLOCKED', concurrentRevocation='NOT_RUN',
                           globalSessionRevocationOrdering='NOT_RUN', unknownCommitFaultInjection='NOT_APPLICABLE_READ_ONLY',
                           personDisplayPolicy='PENDING', seed='NOT_RUN', foundationOnly='NOT_RUN')
        self.synthetic_ids = {'schools': list(SCHOOL_IDS),
                              'classes': dict(zip(SCHOOL_IDS, ('890001', '890002', '890003'))),
                              **{kind: {key: list(ids) for key, ids in RELATION_IDS.items()} for kind in KINDS}}
        self._base = None
        self._save()

    def _guard(self, base=None):
        receipt = self.plan.receipt
        database = self.plan.database
        if (os.environ.get('GITHUB_ACTIONS') != 'true' or not re.fullmatch(r'fcp_ci_[a-f0-9]{12}', database)
                or not self.plan.applied or self.host.database != database
                or receipt.get('database') != database or receipt.get('createdThisRun') is not True
                or receipt.get('initialTableCount') != 0 or not re.fullmatch('[a-f0-9]{32}', receipt.get('runId', ''))):
            raise ValueError('Only this run\'s applied, fresh, owned disposable CI schema is permitted')
        if not re.fullmatch('[a-f0-9]{64}', self.digest):
            raise ValueError('Actual native JAR digest is required')
        if base is not None:
            require_loopback(base)
            if getattr(self, '_base', None) is not None and self._base != base.rstrip('/'):
                raise ValueError('Roster fixture cannot change its verified loopback origin')

    def _save(self):
        temporary = self.output / 'result.tmp'
        temporary.write_text(json.dumps(self.report, indent=2) + '\n')
        temporary.replace(self.output / 'result.json')

    def properties(self):
        self._guard()
        return '\nfcp.education.roster.enabled=true\nFCP_EDUCATION_ROSTER_MANIFEST_DIGEST=' + self.digest + '\n'

    def _check(self, name, condition):
        if not condition:
            raise AssertionError(name)
        self.report['checks'].append(dict(name=name, status='PASS'))

    def _snapshot(self, row):
        raw = self.sql(row.select(self.plan.database)).strip()
        return None if not raw else json.loads(raw)

    def _expect_row(self, row):
        if self._snapshot(row) != row.expected():
            raise ValueError('Owned synthetic row differs from its exact expected preimage: ' + row.table)

    def _read(self, query):
        if not query.startswith('SELECT ') or ';' in query:
            raise ValueError('A bounded SELECT statement is required')
        return self.sql(f'USE `{self.plan.database}`; {query};').strip()

    def _bind(self, build_id):
        if not re.fullmatch('[a-f0-9]{32}', build_id):
            raise ValueError('Exact native build ID required')
        old = self.report.get('buildId')
        if old is not None and old != build_id:
            raise ValueError('Native build ID changed between fixture stages')
        self.report.update(buildId=build_id, manifestDigest=self.digest, nativeJarSha256=self.digest,
                           sourceCommit=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
                           sourceSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest())

    def _request(self, client, route, token=True):
        status, envelope = client.request(PREFIX + route, token=token)
        self._check('actual roster success/error response is not cacheable',
                    'no-store' in {part.strip().lower() for part in (client.opener.cache_control or '').split(',')})
        assert_public(envelope)
        if not isinstance(envelope, dict):
            raise AssertionError('Native response envelope is not an object')
        return status, envelope

    def _data(self, client, route):
        status, envelope = self._request(client, route)
        self._check('real HTTP success ' + route, status == 200 and envelope.get('code') == 200)
        return envelope['data']

    def _denied(self, client, route):
        status, envelope = self._request(client, route)
        return status in (403, 404) and envelope.get('code') in (403, 404)

    def _clients(self, browser, base):
        from native_login import native_token
        clients = {role: RosterClient(base, native_token(self.host.login(browser, base, role), base),
                                      'e5cd7e4891bf95d1d19206ce24a7b32e')
                   for role in ('admin', 'teacher', 'viewer')}
        evidence = clients['admin'].evidence()
        self._check('server native build matches actual packaged artifact evidence',
                    evidence['buildId'] == self.report['buildId'])
        return clients

    def verify_foundation_only(self, base, build_id):
        """Real login/read before seed. No new roles, cap fence or grant exists yet."""
        self._guard(base)
        self._bind(build_id)
        if self.foundation.report.get('status') != 'PASS' or self.foundation.report.get('completed') is not True:
            raise ValueError('Foundation HTTP acceptance must finish successfully first')
        if self.report['seed'] != 'NOT_RUN' or self.report['foundationOnly'] != 'NOT_RUN':
            raise ValueError('Foundation-only gate is single-use and must precede seed')
        try:
            for row in self.rows:
                if self._snapshot(row) is not None:
                    raise ValueError('Roster fixture key is already occupied before seed')
            from playwright.sync_api import sync_playwright
            with sync_playwright() as playwright:
                browser = playwright.chromium.launch()
                try:
                    clients = self._clients(browser, base)
                    for role, client in clients.items():
                        self._check(role + ' retains real foundation read before roster seed',
                                    bool(client.data(FOUNDATION + '/schools')['items']))
                        status, envelope = self._request(client, '/capabilities', token=False)
                        self._check(role + ' unauthenticated roster request denied', status == 401 or envelope.get('code') == 401)
                        for route in ('/capabilities', '/schools', '/schools/800001/enrollments',
                                      '/schools/800001/memberships', '/schools/800001/assignments'):
                            self._check(role + ' foundation authority does not authorize roster ' + route,
                                        self._denied(client, route))
                finally:
                    browser.close()
            self._base = base.rstrip('/')
            self.report['foundationOnly'] = 'PASS'
        except BaseException as error:
            self.report.update(status='FAIL', errorType=type(error).__name__, stage='foundation-only')
            raise
        finally:
            self._save()
        return dict(status=self.report['foundationOnly'], totalChecks=len(self.report['checks']))

    def _prerequisites(self, browser_report):
        if (self.foundation.report.get('status') != 'PASS' or self.foundation.report.get('completed') is not True
                or browser_report.get('status') != 'PASS' or browser_report.get('completed') is not True
                or browser_report.get('buildId') != self.report.get('buildId')):
            raise ValueError('Full foundation HTTP and browser acceptance must pass before roster seed')
        pending = uint64(browser_report.get('pendingSchoolId'))
        if pending == '0' or pending in SCHOOL_IDS:
            raise ValueError('Expected the exact foundation-created pending school ID')
        for index, school in enumerate(SCHOOL_IDS):
            tenant = 'education-other' if school == '800003' else '000000'
            value = self._read(f"SELECT CONCAT(tenant_id,'|',id,'|',IFNULL(parent_id,'NULL'),'|',org_code,'|',org_kind,'|',name,'|',row_version) FROM ed_education_org_unit WHERE tenant_id='{tenant}' AND id={school}")
            expected = f'{tenant}|{school}|NULL|S{school}|SCHOOL|Synthetic school {school}|0'
            if value != expected:
                raise ValueError('Foundation school differs from the reviewed fixture prerequisite')
            value = self._read(f"SELECT CONCAT(tenant_id,'|',id,'|',school_id,'|',term_code,'|',status,'|',row_version) FROM ed_education_term WHERE tenant_id='{tenant}' AND id={810001 + index}")
            if value != f'{tenant}|{810001 + index}|{school}|T2026|OPEN|0':
                raise ValueError('Foundation term differs from the reviewed fixture prerequisite')
        for fence in ('policy:tenant', 'cap:education.foundation'):
            if self._read(f"SELECT CONCAT(epoch,'|',enabled,'|',row_version) FROM ed_core_write_fence WHERE tenant_id='000000' AND fence_key='{fence}'") != '0|1|0':
                raise ValueError('Foundation fence is missing or changed')
        for actor, ident in (('1', '820001'), ('950001', '820002'), ('950002', '820003')):
            if self._read(f"SELECT CONCAT(id,'|',subject_key,'|',epoch,'|',enabled,'|',row_version) FROM ed_core_access_epoch WHERE tenant_id='000000' AND id={ident}") != f'{ident}|{actor}|0|1|0':
                raise ValueError('Foundation actor epoch is missing or changed')
        for tenant in ('000000', 'education-other'):
            if self._read(f"SELECT CONCAT(host_kind,'|',host_tenant_key,'|',timezone,'|',status) FROM ed_core_tenant_binding WHERE tenant_id='{tenant}'") != f'ruoyi-vue-plus|{tenant}|Asia/Shanghai|ACTIVE':
                raise ValueError('Foundation tenant binding differs')
        for scope in ('tenant', 'school:800001', 'school:800002'):
            if self._read(f"SELECT CONCAT(manifest_digest,'|',epoch,'|',enabled,'|',row_version) FROM ed_core_execution_grant WHERE tenant_id='000000' AND scope_key='{scope}' AND capability_key='education.foundation'") != f'{self.digest}|0|1|0':
                raise ValueError('Foundation execution grant differs from the actual JAR digest')
        value = self._read(f"SELECT CONCAT(name,'|',org_kind,'|',IFNULL(parent_id,'NULL')) FROM ed_education_org_unit WHERE tenant_id='000000' AND id={pending}")
        if value != 'Browser pending synthetic school|SCHOOL|NULL':
            raise ValueError('Foundation browser pending school was not found')
        if self._read(f"SELECT COUNT(*) FROM ed_core_execution_grant WHERE tenant_id='000000' AND scope_key='school:{pending}'") != '0':
            raise ValueError('Pending foundation school unexpectedly received an execution grant')
        self.synthetic_ids['pendingSchoolId'] = pending

    def seed(self, *, foundation_browser_report):
        try:
            return self._seed(foundation_browser_report=foundation_browser_report)
        except BaseException as error:
            self.report.update(status='FAIL', errorType=type(error).__name__, stage='seed')
            if self.report['seed'] == 'NOT_RUN':
                self.report['seed'] = 'FAILED_BEFORE_APPLY'
            self._save()
            raise

    def _seed(self, *, foundation_browser_report):
        self._guard(self._base)
        if self._base is None or self.report['foundationOnly'] != 'PASS' or self.report['seed'] != 'NOT_RUN':
            raise ValueError('Successful real loopback foundation-only gate is required before a single seed')
        self._prerequisites(foundation_browser_report)
        # Source lock and menu verification are independent of granting native roles.
        lock = json.loads((ROOT / 'contracts/education/roster-sources.lock.json').read_text())
        if lock.get('format') != 2 or set(lock.get('sha256', {})) != {
                'sql/education/003__roster_authority.sql', 'sql/education/004__roster_menu.sql'}:
            raise ValueError('Unexpected roster extension source lock')
        for path, digest in lock['sha256'].items():
            if hashlib.sha256((ROOT / path).read_bytes()).hexdigest() != digest:
                raise ValueError('Roster SQL differs from the reviewed source lock')
        for menu in lock['menus']:
            actual = self._read(f"SELECT CONCAT(menu_id,'|',parent_id,'|',IFNULL(component,''),'|',perms,'|',is_cache,'|',status) FROM sys_menu WHERE menu_id={menu['id']}")
            if actual != '|'.join(str(menu[k]) for k in ('id', 'parentId', 'component', 'permission', 'isCache', 'status')):
                raise ValueError('Roster native menu differs from its reviewed source')
        for row in self.rows:
            if self._snapshot(row) is not None:
                raise ValueError('Seed refuses an occupied synthetic key: ' + row.table)
        statements = f'USE `{self.plan.database}`;\nSET SESSION time_zone=\'+00:00\';\nSTART TRANSACTION;\n'
        statements += '\n'.join(row.insert() for row in self.rows) + '\nCOMMIT;\n'
        seed_path = self.output / 'seed.sql'
        seed_path.write_text(statements)
        plan = dict(database=self.plan.database, runId=self.plan.receipt['runId'],
                    seedSha256=hashlib.sha256(statements.encode()).hexdigest(), manifestDigest=self.digest,
                    scope='SYNTHETIC_CURRENT_RUN_ONLY', foundationPrerequisites='VERIFIED_NOT_REINSERTED',
                    rowCount=len(self.rows), rows=[dict(table=row.table, keys={k: row.values[k] for k in row.keys}) for row in self.rows],
                    syntheticIds=self.synthetic_ids)
        (self.output / 'seed-plan.json').write_text(json.dumps(plan, indent=2) + '\n')
        self.report['seed'] = 'APPLYING'
        self._save()
        try:
            self.sql(statements)
            for row in self.rows:
                self._expect_row(row)
            self.report.update(seed='APPLIED_SYNTHETIC_ONLY', seedSha256=plan['seedSha256'], seedRowCount=len(self.rows),
                               syntheticIds=self.synthetic_ids)
        except BaseException as error:
            self.report.update(status='FAIL', seed='FAILED_NO_AUTOMATIC_RETRY', errorType=type(error).__name__)
            raise
        finally:
            self._save()
        return plan

    @contextmanager
    def mutation(self, name, before, **changes):
        """Temporarily change one owned row, CAS exact preimage, restore and verify.

        Missing-row cases move only an owned key. No DELETE, cleanup, DDL, or
        broad restoration is issued, even on failure. Uncertain writes are read
        back before deciding whether restoration is safe.
        """
        self._guard(self._base)
        if self.report['seed'] != 'APPLIED_SYNTHETIC_ONLY' or not changes:
            raise ValueError('A successfully seeded fixture is required for revocation')
        allowed = {'ed_core_write_fence': {'enabled', 'fence_key'},
                   'ed_core_execution_grant': {'enabled', 'scope_key', 'manifest_digest'},
                   'sys_role': {'status'}, 'sys_user_role': {'user_id'}, 'sys_role_menu': {'menu_id'},
                   'ed_host_roster_role': {'enabled'}, 'sys_menu': {'status'},
                   'ed_core_access_epoch': {'enabled', 'subject_key'}}
        if not set(changes).issubset(allowed.get(before.table, set())):
            raise ValueError('Mutation is not in the reviewed revocation plan')
        if before not in self.rows:
            permitted_menu = Row('sys_menu', dict(menu_id=986101, parent_id=986120,
                                                  perms='education:roster:student:read', status='0'), ('menu_id',))
            permitted_epoch = (before.table == 'ed_core_access_epoch'
                               and before.keys == ('tenant_id', 'id')
                               and {key: before.values.get(key) for key in ('tenant_id', 'id', 'subject_key', 'epoch', 'row_version', 'enabled')}
                               == dict(tenant_id='000000', id='820002', subject_key='950001', epoch='0', row_version='0', enabled='1'))
            if before != permitted_menu and not permitted_epoch:
                raise ValueError('Mutation target is not a reviewed current-run owned row')
        self._expect_row(before)
        after = before.changed(**changes)
        if before.where() != after.where() and self._snapshot(after) is not None:
            raise ValueError('Temporary missing-row target is already occupied')
        entry = dict(name=name, table=before.table, before=before.values, after=after.values,
                     action='ONE_OWNED_ROW_CAS', status='PENDING', restore='NOT_RUN')
        self.report['mutations'].append(entry)
        self._save()
        attempted = False
        def change(source, target):
            assignments = ','.join('`' + key + '`=' + literal(target.values[key], column=key) for key in changes)
            statement = f'UPDATE `{self.plan.database}`.`{source.table}` SET {assignments} WHERE {source.where(True)}; SELECT ROW_COUNT();'
            if self.sql(statement).strip() != '1':
                raise AssertionError('Revocation must affect exactly one owned row')
        try:
            attempted = True
            change(before, after)
            self._expect_row(after)
            entry['status'] = 'APPLIED_ONE_ROW'
            self._save()
            yield
        finally:
            if attempted:
                observed = self._snapshot(after)
                if observed == after.expected():
                    change(after, before)
                    self._expect_row(before)
                    entry['restore'] = 'PASS_EXACT_PREIMAGE'
                elif self._snapshot(before) == before.expected():
                    entry['restore'] = 'PASS_ALREADY_ORIGINAL'
                else:
                    entry['restore'] = 'FAIL_UNEXPECTED_STATE_NO_OVERWRITE'
                    self._save()
                    raise AssertionError('Owned mutation outcome is uncertain; broad restoration refused')
            self._save()

    def _owned(self, table, **keys):
        matches = [row for row in self.rows if row.table == table and all(str(row.values.get(k)) == str(v) for k, v in keys.items())]
        if len(matches) != 1:
            raise ValueError('Expected exactly one fixture-owned row')
        return matches[0]

    def _epoch_row(self):
        # This is an original, explicitly verified current-run foundation row,
        # shared by both modules. Snapshot timestamps, never create another epoch.
        columns = ('tenant_id', 'id', 'subject_key', 'epoch', 'changed_at', 'created_at', 'row_version', 'enabled')
        probe = Row('ed_core_access_epoch', dict(zip(columns, ('000000', '820002', '950001', '0', None, None, '0', '1'))), ('tenant_id', 'id'))
        snapshot = self._snapshot(probe)
        if (not isinstance(snapshot, list) or len(snapshot) != len(columns)
                or snapshot[:4] != ['000000', '820002', '950001', '0'] or snapshot[-2:] != ['0', '1']
                or not all(isinstance(value, str) and re.fullmatch(r'\d{4}-\d\d-\d\d \d\d:\d\d:\d\d(?:\.\d{1,6})?', value) for value in snapshot[4:6])):
            raise ValueError('Shared foundation epoch differs from the verified current-run row')
        return Row(probe.table, dict(zip(columns, snapshot)), probe.keys)

    def _pages(self, client, route, expected_ids, *, school=None):
        for limit in (1, 100):
            seen, cursor = [], '0'
            for _ in range(len(expected_ids) + 2):
                page = self._data(client, route + f'?afterId={cursor}&limit={limit}')
                self._check('closed page response fields', set(page) == {'items', 'nextAfterId'})
                items = page['items']
                self._check('bounded real page size', isinstance(items, list) and len(items) <= limit)
                current = [uint64(item['id']) for item in items]
                self._check('monotonic exact uint64 page IDs', current == sorted(current, key=int) and all(int(i) > int(cursor) for i in current))
                self._check('page has no unassigned or cross-school ID', all(i in expected_ids for i in current))
                if school:
                    for item in items:
                        check_record(item, route.rsplit('/', 1)[-1], school)
                        self._check('relation stays in admitted school', item.get('schoolId') == school)
                        self._check('person display remains PENDING', item.get('personDisplay') == dict(status='PENDING', reason='PERSON_DISPLAY_POLICY_PENDING'))
                        uint64(item['rowVersion'])
                seen += current
                next_cursor = page['nextAfterId']
                self._check('cursor is absent or exact last authorized visible ID',
                            next_cursor is None or bool(current) and next_cursor == current[-1] and len(current) == limit)
                if next_cursor is None:
                    break
                cursor = uint64(next_cursor)
            else:
                raise AssertionError('Pagination did not terminate')
            self._check('pagination returns each exact expected ID once at limit ' + str(limit),
                        seen == sorted(expected_ids, key=int) and len(seen) == len(set(seen)))

    def verify(self, base, build_id, native_pid):
        self._guard(base)
        self._bind(build_id)
        if self.report['seed'] != 'APPLIED_SYNTHETIC_ONLY':
            raise ValueError('Roster HTTP gate requires its completed synthetic seed')
        pid = native_pid()
        try:
            self._check('real native JVM is running', pid is not None)
            for row in self.rows:
                self._expect_row(row)
            self._check('real JDBC stage left every synthetic roster seed row intact', True)
            from playwright.sync_api import sync_playwright
            with sync_playwright() as playwright:
                browser = playwright.chromium.launch()
                try:
                    clients = self._clients(browser, base)
                    admin, teacher, viewer = (clients[role] for role in ('admin', 'teacher', 'viewer'))
                    for role, client in clients.items():
                        capability = self._data(client, '/capabilities')
                        operations = capability['operations']
                        self._check(role + ' capability status and PII policy', capability['status'] == 'READY'
                                    and capability['schemaVersion'] == 'education-roster-v1'
                                    and capability['readiness']['personDisplayPolicy'] == 'PENDING')
                        self._check(role + ' closed read/write operations',
                                    operations['enrollmentList'] == (role != 'viewer')
                                    and operations['membershipList'] == (role != 'viewer')
                                    and operations['assignmentList'] == (role != 'teacher')
                                    and not any(operations[key] for key in ('personDisplay', 'enrollmentWrite', 'membershipWrite', 'assignmentWrite', 'personRename', 'termWrite')))
                        schools = ['800001', '800002', self.synthetic_ids['pendingSchoolId']] if role == 'admin' else ['800001']
                        self._pages(client, '/schools', schools)
                        for route in ('/capabilities', '/schools', '/schools/800001/enrollments'):
                            status, envelope = self._request(client, route, token=False)
                            self._check('no login cannot read roster ' + route, status == 401 or envelope.get('code') == 401)
                    pending = self.synthetic_ids['pendingSchoolId']
                    directory = self._data(admin, '/schools?limit=100')['items']
                    self._check('only explicitly granted schools are ready',
                                {row['id'] for row in directory if row['readiness'] == 'READY'} == {'800001', '800002'})
                    item = next(row for row in directory if row['id'] == pending)
                    self._check('foundation pending school stays closed in roster', item['readiness'] == 'EXECUTION_GRANT_MISSING' and not any(item['operations'].values()))
                    for kind in KINDS:
                        self._check('no execution grant on foundation pending school ' + kind,
                                    self._denied(admin, f'/schools/{pending}/{kind}'))
                        for school in ('800001', '800002'):
                            route = f'/schools/{school}/{kind}'
                            self._pages(admin, route, RELATION_IDS[school], school=school)
                            for ident in RELATION_IDS[school]:
                                record = self._data(admin, route + '/' + ident)
                                check_record(record, kind, school)
                                self._check('exact school detail identity', record['id'] == ident and record['schoolId'] == school)
                                self._check('uint64 upper-bound row version is lossless', record['rowVersion'] == (MAX_U64 if ident == MAX_U64 else '0'))
                                self._check('detail person policy is PENDING', record['personDisplay']['status'] == 'PENDING')
                        permitted = viewer if kind == 'assignments' else teacher
                        forbidden = teacher if kind == 'assignments' else viewer
                        self._pages(permitted, f'/schools/800001/{kind}', RELATION_IDS['800001'], school='800001')
                        for route in (f'/schools/800001/{kind}', f'/schools/800001/{kind}/{RELATION_IDS["800001"][0]}'):
                            self._check('student and teacher permissions are separate ' + route, self._denied(forbidden, route))
                        for client in (teacher, viewer):
                            self._check('same tenant other school relation denied', self._denied(client, f'/schools/800002/{kind}'))
                            self._check('same tenant guessed detail denied', self._denied(client, f'/schools/800002/{kind}/{RELATION_IDS["800002"][0]}'))
                        self._check('other tenant relation denied', self._denied(admin, f'/schools/800003/{kind}'))
                        self._check('other tenant guessed detail denied', self._denied(admin, f'/schools/800003/{kind}/{RELATION_IDS["800003"][0]}'))
                        for ident in (RELATION_IDS['800002'][0], RELATION_IDS['800003'][0]):
                            self._check('foreign-school ID cannot be rebound under admitted school', self._denied(permitted, f'/schools/800001/{kind}/{ident}'))
                        page = self._data(permitted, f'/schools/800001/{kind}?afterId={MAX_U64}&limit=100')
                        self._check('uint64 terminal cursor has no hidden continuation', page == dict(items=[], nextAfterId=None))
                    self._check('shared natural person only resolves admitted school relations',
                                self._read("SELECT COUNT(DISTINCT school_id) FROM ed_education_enrollment WHERE tenant_id='000000' AND person_id=880001 AND id IN (9007199254740993,9007199254740994)") == '2')
                    invalid = ['/capabilities?limit=1', '/schools?unknown=x', '/schools?limit=0', '/schools?limit=101',
                               '/schools?limit=01', '/schools?limit=1&limit=2', '/schools?afterId=01', '/schools?afterId=-1',
                               '/schools?afterId=18446744073709551616', '/schools?tenantId=education-other',
                               '/schools?afterId=0&afterId=1', '/schools/800001/enrollments?personId=880001',
                               '/schools/800001/enrollments?limit=1&limit=2',
                               '/schools/800001/enrollments/' + MAX_U64 + '?limit=1',
                               '/schools/800001/enrollments/18446744073709551616']
                    for route in invalid:
                        status, envelope = self._request(admin, route)
                        self._check('extra or malformed request rejected ' + route, status == 400 and envelope.get('code') == 400)
                    status, envelope = self._request(admin, '/schools/800001/unknown')
                    self._check('unknown relation kind is not a route', status == 404 or envelope.get('code') == 404)
                    self._revocations(admin, teacher)
                    for row in self.rows:
                        self._expect_row(row)
                    self._check('every synthetic roster row is restored before browser acceptance', True)
                    self._check('native JVM stayed live and unchanged', native_pid() == pid)
                finally:
                    browser.close()
            self.report.update(status='PASS', totalChecks=len(self.report['checks']))
        except BaseException as error:
            from ci_ingestion import safe_diagnostic
            self.report.update(status='FAIL', errorType=type(error).__name__,
                               error=safe_diagnostic(str(error), self.host.diagnostic_secrets))
            raise
        finally:
            self.report['completed'] = True
            self._save()
        return self.report

    def _revocations(self, admin, teacher):
        route = '/schools/800001/enrollments'
        fence = self._owned('ed_core_write_fence')
        tenant_grant = self._owned('ed_core_execution_grant', scope_key='tenant')
        school_grant = self._owned('ed_core_execution_grant', scope_key='school:800001')
        cases = [('capability fence disabled', fence, dict(enabled=0)),
                 ('capability fence missing', fence, dict(fence_key='cap:education.roster:ci-missing')),
                 ('tenant capability grant disabled', tenant_grant, dict(enabled=0)),
                 ('tenant capability grant missing', tenant_grant, dict(scope_key='roster-ci-missing')),
                 ('actual manifest grant missing', tenant_grant, dict(manifest_digest='f' * 64 if self.digest != 'f' * 64 else 'e' * 64)),
                 ('school execution grant disabled', school_grant, dict(enabled=0)),
                 ('school execution grant missing', school_grant, dict(scope_key='school:roster-ci-missing')),
                 ('native roster role disabled', self._owned('sys_role', role_id=988102), dict(status='1')),
                 ('native role membership revoked', self._owned('sys_user_role', role_id=988102), dict(user_id=1)),
                 ('native roster menu permission revoked', self._owned('sys_role_menu', role_id=988102, menu_id=986101), dict(menu_id=986102)),
                 ('explicit roster role grant disabled', self._owned('ed_host_roster_role', native_role_id=988102), dict(enabled=0))]
        # The menu itself belongs to the reviewed new roster extension; never touch
        # foundation menus or 9871xx roles. Snapshot only after lock verification.
        menu = Row('sys_menu', dict(menu_id=986101, parent_id=986120, perms='education:roster:student:read', status='0'), ('menu_id',))
        cases.append(('new roster native menu disabled', menu, dict(status='1')))
        for name, row, changes in cases:
            with self.mutation(name, row, **changes):
                self._check(name + ' rejects old token list', self._denied(teacher, route))
                self._check(name + ' rejects old token detail', self._denied(teacher, route + '/' + RELATION_IDS['800001'][0]))
                self._check(name + ' does not remove foundation authority', bool(teacher.data(FOUNDATION + '/schools/800001/terms')['items']))
                if row == school_grant:
                    directory = self._data(admin, '/schools?limit=100')['items']
                    school = next(item for item in directory if item['id'] == '800001')
                    self._check('missing school grant closes only selected roster school', school['readiness'] == 'EXECUTION_GRANT_MISSING' and not any(school['operations'].values()))
                    self._check('other granted roster school remains readable', bool(self._data(admin, '/schools/800002/enrollments')['items']))
                else:
                    self._check(name + ' also rejects capability discovery', self._denied(teacher, '/capabilities'))
                    self._check(name + ' also rejects school directory', self._denied(teacher, '/schools'))
            self._check(name + ' restoration verified by same old token', bool(self._data(teacher, route)['items']))
        epoch = self._epoch_row()
        for name, changes in [('shared subject epoch disabled', dict(enabled=0)),
                              ('shared subject epoch missing', dict(subject_key='roster-ci-missing'))]:
            with self.mutation(name, epoch, **changes):
                self._check(name + ' rejects existing roster token', self._denied(teacher, route))
                self._check(name + ' rejects existing roster discovery', self._denied(teacher, '/capabilities'))
                status, value = teacher.request(FOUNDATION + '/schools/800001/terms')
                self._check(name + ' also closes same actor foundation authority', status in (403, 404) and value.get('code') in (403, 404))
                self._check(name + ' leaves another actor foundation read intact', bool(admin.data(FOUNDATION + '/schools/800001/terms')['items']))
            self._check(name + ' restores original actor foundation read', bool(teacher.data(FOUNDATION + '/schools/800001/terms')['items']))
            self._check(name + ' restores original actor roster read', bool(self._data(teacher, route)['items']))
