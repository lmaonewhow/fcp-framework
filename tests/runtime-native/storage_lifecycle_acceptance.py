"""A11 actual native/MySQL acceptance. Business writes only through real Python host callbacks.

Instantiation requires the enclosing disposable CI database and genuine native login fixture.
Local package/protocol checks are not database, native lifecycle, or A11 acceptance.
"""
from __future__ import annotations
import hashlib
import io
import json
import os
from pathlib import Path
import re
import sys
import time
import zipfile
import urllib.request
import urllib.error

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools'))
from native_client import require_loopback
from native_runtime_acceptance import Client, RuntimeFixture, exported
from ci_ingestion import safe_diagnostic

PLUGIN = 'ci.storage-retention'
COLLECTION = 'entries'
SCOPE = 'class:retention-a'
VERSIONS = ('1.0.0', '1.1.0', '2.0.0')


def storage_contract(operation):
    props = {'collection': {'type': 'string', 'minLength': 1, 'maxLength': 64},
             'dataScope': {'type': 'string', 'minLength': 1, 'maxLength': 192}}
    required = ['collection', 'dataScope']
    if operation == 'list':
        props.update(equals={'type': 'object'}, after={'type': 'string', 'maxLength': 64}, limit={'type': 'integer', 'minimum': 1, 'maximum': 100})
    else:
        props['id'] = {'type': 'string', 'minLength': 1, 'maxLength': 64}; required.append('id')
    write = operation not in ('get', 'list')
    if write:
        props.update(expectedRevision={'type': 'integer', 'minimum': 0}, idempotencyKey={'type': 'string', 'minLength': 1, 'maxLength': 128})
        required.extend(('expectedRevision', 'idempotencyKey'))
    if operation == 'put':
        props['body'] = {'type': 'object'}; required.append('body')
    permission = 'read' if not write else 'write' if operation == 'put' else operation
    return dict(id='host.storage.' + operation, version='1.0.0', permission='storage.' + permission,
                sideEffects=write, idempotent=True, timeoutMillis=30000,
                inputSchema=dict(type='object', properties=props, required=required, additionalProperties=False), outputSchema={})


def package_bytes(version='1.0.0'):
    if version not in VERSIONS:
        raise ValueError('Unknown fixed A11 package version')
    write_input = dict(type='object', properties=dict(action=dict(type='string', enum=['put', 'delete']),
        id=dict(type='string', enum=['active', 'deleted']), value=dict(type='string', maxLength=100),
        expectedRevision=dict(type='integer', minimum=0), receiptKey=dict(type='string', minLength=1, maxLength=128)),
        required=['action', 'id', 'expectedRevision', 'receiptKey'], additionalProperties=False)
    provides = [dict(id='retention.' + op, version='1.0.0', permission='retention.' + op, sideEffects=op == 'write',
        idempotent=True, timeoutMillis=30000, inputSchema=write_input if op == 'write' else dict(type='object', additionalProperties=False), outputSchema=dict(type='object')) for op in ('read', 'write')]
    storage = dict(schemaVersion=2 if version == '2.0.0' else 1, compatibleVersions=[2] if version == '2.0.0' else [1],
        tables=[dict(name=COLLECTION, fields=[dict(name='value', type='integer' if version == '2.0.0' else 'string', nullable=False, maxLength=100)], indexes=[])], migrations=[])
    manifest = dict(manifestVersion=1, pluginId=PLUGIN, version=version, publisher='test', contractVersion='1.0',
        runtime=dict(type='local', entry='worker.py', argv=[]), dependencies=[], provides=provides,
        requires=[storage_contract(op) for op in ('get', 'list', 'put', 'delete')], storage=storage, ui={})
    worker = (Path(__file__).parent / 'storage-retention' / 'worker.py').read_bytes().replace(b'__PACKAGE_VERSION__', version.encode())
    result = io.BytesIO()
    with zipfile.ZipFile(result, 'w', zipfile.ZIP_DEFLATED) as archive:
        for name, content in [('plugin.json', json.dumps(manifest, ensure_ascii=False, separators=(',', ':')).encode()), ('worker.py', worker)]:
            info = zipfile.ZipInfo(name, (2026, 1, 1, 0, 0, 0)); info.compress_type = zipfile.ZIP_DEFLATED; info.external_attr = 0o100644 << 16
            archive.writestr(info, content)
    return result.getvalue(), manifest


def rejected_upload(client, package, digest, key, revision):
    headers = {'Content-Type': 'application/octet-stream', 'clientid': client.client_id,
               'Authorization': 'Bearer ' + client.token, 'X-Fcp-Digest': digest,
               'Idempotency-Key': key, 'X-Fcp-Revision': str(revision)}
    request = urllib.request.Request(client.base + '/fcp/packages', data=package, headers=headers, method='POST')
    try:
        with client.opener.open(request, timeout=30) as response:
            return response.status, json.loads(response.read(2 * 1024 * 1024))
    except urllib.error.HTTPError as error:
        return error.code, json.loads(error.read(2 * 1024 * 1024))


class StorageLifecycleFixture:
    def __init__(self, database, sql, private_dir, login_fixture):
        if os.environ.get('GITHUB_ACTIONS') != 'true' or not re.fullmatch(r'fcp_ci_[a-f0-9]{12}', database):
            raise RuntimeError('A11 requires a newly provisioned disposable CI database')
        if login_fixture is None or login_fixture.database != database:
            raise RuntimeError('Matching genuine native login fixture required')
        self.database, self.sql, self.private_dir, self.login_fixture = database, sql, private_dir, login_fixture
        self.output = ROOT / 'reports/runtime-native/storage-lifecycle'; self.output.mkdir(parents=True, exist_ok=True)
        self.report = dict(status='NOT_RUN', acceptance='A11', production='NOT_ACCEPTED',
            scope='actual RuoYi + MySQL + Python host-storage callbacks across uninstall/reinstall', checks=[], snapshots=[], packages=[])
        # Authority fixtures only. No SQL writes to any plugin business record or schema table.
        sql(f"""USE `{database}`;
INSERT INTO sys_role(role_id,tenant_id,role_name,role_key,role_sort,data_scope,status,del_flag,create_by,create_time)
VALUES(950580,'000000','CI storage retention operator','fcp_ci_storage_retention',96,'5','0','0',1,NOW());
INSERT INTO sys_user_role(user_id,role_id) VALUES(1,950580);
INSERT INTO sys_role_menu(role_id,menu_id) VALUES(950580,942000),(950580,942020),(950580,942021),(950580,942022);
INSERT INTO fcp_eval_assignment(scope_id,subject_id,class_id) VALUES('tenant-000000','1','retention-a');
""")
        for offset, permission in enumerate(('retention.read', 'retention.write', 'storage.read', 'storage.write', 'storage.delete')):
            menu = 950581 + offset
            sql(f"USE `{database}`; INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,menu_type,perms,icon,is_frame,is_cache,visible,status,create_by,create_time) VALUES({menu},'CI retention {offset}',942020,99,'',NULL,'F','fcp:plugin:{permission}','#',1,1,'0','0',1,NOW()); INSERT INTO sys_role_menu(role_id,menu_id) VALUES(950580,{menu});")
        self.write_report()

    def write_report(self):
        (self.output / 'acceptance.json').write_text(json.dumps(self.report, ensure_ascii=False, indent=2) + '\n')

    def check(self, name, condition):
        if not condition:
            raise AssertionError(name)
        self.report['checks'].append(dict(name=name, status='PASS')); self.write_report()

    def snapshot(self, label):
        expected = 'fcp_p_' + hashlib.sha256(PLUGIN.encode()).hexdigest()[:16] + '_' + hashlib.sha256(COLLECTION.encode()).hexdigest()[:16]
        actual = self.sql(f"USE `{self.database}`; SELECT table_name FROM fcp_plugin_physical_schema WHERE plugin_id='{PLUGIN}' AND structure_id='{COLLECTION}';").strip()
        if actual != expected:
            raise AssertionError('A11 physical table owner mismatch')
        rows = self.sql(f"USE `{self.database}`; SELECT JSON_OBJECT('tenantId',tenant_id,'dataScope',data_scope,'id',record_id,'value',`value`,'revision',revision,'del',del) FROM `{expected}` ORDER BY tenant_id,data_scope,record_id;").strip()
        records = [json.loads(line) for line in rows.splitlines()] if rows else []
        self.report['snapshots'].append(dict(stage=label, records=records, sha256=hashlib.sha256(json.dumps(records, sort_keys=True, separators=(',', ':')).encode()).hexdigest()))
        self.write_report(); return records

    def invoke(self, client, capability, payload=None, key=''):
        catalog = client.data('/fcp/ui/catalog')['catalog']
        return client.data('/fcp/capabilities/' + exported(PLUGIN, capability) + '/invoke', dict(input=payload or {}, expectedRevision=catalog['revision'], idempotencyKey=key or 'retention-' + capability))

    def change(self, client, action, key):
        current = client.data('/fcp/plugins/' + PLUGIN)
        plan = client.data('/fcp/plugins/' + PLUGIN + '/plans', dict(action=action, expectedRevision=current['revision']))
        self.check(action + ' affects only the A11 synthetic plugin', plan['affectedPlugins'] == [PLUGIN])
        result = RuntimeFixture.confirm(client, plan, key)
        self.check(action + ' lifecycle completed', result.get('phase') == 'COMPLETE')

    def grant(self, client, manifest, key):
        options = client.data('/fcp/plugins/' + PLUGIN + '/grant-options?targetTenant=000000')
        grants = [dict(capabilityId=c['id'], dataScope=SCOPE if c['id'].startswith('host.storage.') else 'tenant:000000', allow=True) for c in manifest['provides'] + manifest['requires']]
        plan = client.data('/fcp/plugins/' + PLUGIN + '/grants/plans', dict(targetTenant='000000', expectedRevision=options['revision'], grants=grants))
        self.check('explicit package-bound grant confirmed ' + key, RuntimeFixture.confirm(client, plan, key)['phase'] == 'COMPLETE')

    def uninstall(self, client, digest, key):
        current = client.data('/fcp/plugins/' + PLUGIN)
        result = client.data('/fcp/plugins/' + PLUGIN + '/uninstall', dict(expectedRevision=current['revision'], confirmedDigest=digest))
        self.check('uninstall retains metadata ' + key, result.get('state') == 'UNINSTALLED' and result.get('dataRetained') is True)

    def assert_process(self, result, native_pid, version):
        status = Path('/proc', str(result['pluginPid']), 'status').read_text().splitlines()
        parent = [line.split(':', 1)[1].strip() for line in status if line.startswith('PPid:')]
        self.check('actual Python child for package ' + version, parent == [str(native_pid)] and result['packageVersion'] == version and result['tenantId'] == '000000' and result['principalId'] == '1')

    def verify(self, base, build_id, pid_provider):
        require_loopback(base)
        if os.environ.get('FCP_ACCEPTANCE_DATABASE') != self.database:
            raise RuntimeError('Explicit A11 acceptance database mismatch')
        from playwright.sync_api import sync_playwright
        pid, started = pid_provider(), time.monotonic()
        self.report.update(status='RUNNING', buildId=build_id, nativeJavaPid=pid); self.write_report()
        try:
            with sync_playwright() as playwright:
                browser = playwright.chromium.launch()
                try:
                    state = self.login_fixture.login(browser, base, 'admin')
                    client = Client(base, RuntimeFixture.token(state, base), 'e5cd7e4891bf95d1d19206ce24a7b32e')
                    self.check('genuine native build bound to evidence', client.evidence().get('buildId') == build_id)
                    self.check('explicit isolated schema deployment identity available', client.data('/fcp/storage/deployment')['configured'] is True)
                    packages = {}
                    for version in VERSIONS:
                        archive, manifest = package_bytes(version); digest = hashlib.sha256(archive).hexdigest()
                        staged = client.upload(archive, digest, 'retention-stage-' + version, staged=True)
                        self.check('verified package staged ' + version, staged.get('digest') == digest)
                        packages[version] = dict(archive=archive, manifest=manifest, digest=digest)
                        self.report['packages'].append(dict(pluginId=PLUGIN, version=version, sha256=digest, bytes=len(archive)))
                    original = packages['1.0.0']
                    plan = client.data('/fcp/plugins/' + PLUGIN + '/storage/plans', dict(version='1.0.0', packageDigest=original['digest']))
                    deployed = client.data('/fcp/storage/plans/' + plan['planId'] + '/apply', dict(digest=plan['digest'], idempotencyKey='retention-schema'))
                    self.check('physical schema deployed through explicit native endpoint', deployed.get('phase') == 'COMPLETE')
                    installed = client.upload(original['archive'], original['digest'], 'retention-install-original', client.data('/fcp/plugins')['revision'])
                    self.check('original package registered after schema readiness', installed.get('state') == 'INSTALLED')
                    self.grant(client, original['manifest'], 'retention-grants-original'); self.change(client, 'ENABLE', 'retention-enable-original')
                    for number, (action, record, value, revision) in enumerate((('put', 'active', 'active-v1', 0), ('put', 'active', 'active-v2', 1), ('put', 'deleted', 'deleted-value', 0), ('delete', 'deleted', None, 1))):
                        payload = dict(action=action, id=record, expectedRevision=revision, receiptKey='retention-write-' + str(number))
                        if value is not None: payload['value'] = value
                        result = self.invoke(client, 'retention.write', payload, 'retention-top-' + str(number))
                        self.check('actual storage callback mutation ' + str(number), result['result']['revision'] == revision + 1)
                        self.assert_process(result, pid, '1.0.0')
                    visible = self.invoke(client, 'retention.read')
                    expected_active = dict(id='active', body=dict(value='active-v2'), revision=2, deleted=False)
                    self.check('ordinary callback queries hide the explicit tombstone before uninstall', visible['result'] == dict(active=[expected_active], deletedLookup=None))
                    before = self.snapshot('before-disable')
                    expected_rows = [dict(tenantId='000000', dataScope=SCOPE, id='active', value='active-v2', revision=2, **{'del': 0}), dict(tenantId='000000', dataScope=SCOPE, id='deleted', value='deleted-value', revision=2, **{'del': 1})]
                    self.check('MySQL holds actual active and deleted records with nontrivial revisions', before == expected_rows)
                    audits_before = self.sql(f"USE `{self.database}`; SELECT COUNT(*) FROM fcp_plugin_storage_audit WHERE plugin_id='{PLUGIN}' AND action IN ('physical.write','physical.delete');").strip()
                    self.check('all business writes have durable host-storage audit', audits_before == '4')
                    self.change(client, 'DISABLE', 'retention-disable-original')
                    self.check('disable preserves every stored field revision and deletion flag', self.snapshot('after-disable') == before)
                    self.uninstall(client, original['digest'], 'original')
                    self.check('uninstall preserves active and tombstone rows unchanged', self.snapshot('after-uninstall') == before)
                    bad = packages['2.0.0']; current = client.data('/fcp/plugins/' + PLUGIN)
                    status, body = rejected_upload(client, bad['archive'], bad['digest'], 'retention-incompatible-install', current['revision'])
                    self.check('incompatible schema reinstall is rejected', status >= 400 and body.get('data', {}).get('fcpCode') in ('STORAGE_VERSION_INCOMPATIBLE', 'SCHEMA_VERSION_CONFLICT'))
                    after_bad = client.data('/fcp/plugins/' + PLUGIN + '/versions')['current']
                    self.check('failed incompatible reinstall leaves uninstalled original metadata', after_bad.get('state') == 'UNINSTALLED' and after_bad.get('digest') == original['digest'])
                    self.check('incompatible reinstall changes no stored record', self.snapshot('after-incompatible-rejection') == before)
                    compatible = packages['1.1.0']
                    installed = client.upload(compatible['archive'], compatible['digest'], 'retention-install-compatible', client.data('/fcp/plugins')['revision'])
                    self.check('compatible fresh package reinstalled', installed.get('state') == 'INSTALLED' and installed.get('digest') == compatible['digest'])
                    self.check('reinstall alone preserves tombstone and revisions', self.snapshot('after-compatible-reinstall') == before)
                    current = client.data('/fcp/plugins/' + PLUGIN)
                    status, body = client.request('/fcp/plugins/' + PLUGIN + '/plans', dict(action='ENABLE', expectedRevision=current['revision']))
                    self.check('new package digest cannot inherit prior storage approvals', status >= 400 and body.get('data', {}).get('fcpCode') == 'CAPABILITY_NOT_GRANTED')
                    self.grant(client, compatible['manifest'], 'retention-grants-compatible'); self.change(client, 'ENABLE', 'retention-enable-compatible')
                    recovered = self.invoke(client, 'retention.read', key='retention-recovered-read')
                    self.assert_process(recovered, pid, '1.1.0')
                    self.check('reinstall starts a fresh Python process identity', recovered['processToken'] != visible['processToken'])
                    self.check('compatible process recovers active record and does not auto-undelete', recovered['result'] == dict(active=[expected_active], deletedLookup=None))
                    self.check('regrant and enable preserve all database rows byte-semantically', self.snapshot('after-regrant-enable') == before)
                    audits_after = self.sql(f"USE `{self.database}`; SELECT COUNT(*) FROM fcp_plugin_storage_audit WHERE plugin_id='{PLUGIN}' AND action IN ('physical.write','physical.delete','physical.restore');").strip()
                    self.check('lifecycle and default reads cause no hidden writes or restores', audits_after == audits_before)
                    self.check('native JVM unchanged across A11 lifecycle', pid_provider() == pid)
                    self.change(client, 'DISABLE', 'retention-final-disable'); self.uninstall(client, compatible['digest'], 'compatible')
                    self.check('final uninstall still retains both fixture records', self.snapshot('after-final-uninstall') == before)
                finally:
                    browser.close()
            self.report.update(status='PASS', totalChecks=len(self.report['checks']), elapsedSeconds=round(time.monotonic() - started, 3)); self.write_report(); return self.report
        except Exception as error:
            self.report.update(status='FAIL', error=safe_diagnostic(str(error), [self.login_fixture.password]), totalChecks=len(self.report['checks'])); self.write_report(); raise


if __name__ == '__main__':
    if len(sys.argv) == 3 and sys.argv[1] == '--package-output':
        target = Path(sys.argv[2]); target.mkdir(parents=True, exist_ok=True)
        for version in VERSIONS:
            package, _ = package_bytes(version); path = target / (PLUGIN + '-' + version + '.zip'); path.write_bytes(package)
            print(json.dumps(dict(version=version, sha256=hashlib.sha256(package).hexdigest(), bytes=len(package))))
    else:
        print(json.dumps(dict(status='NOT_RUN', acceptance='A11', reason='Run only through isolated native CI with actual MySQL, native login and Python storage callbacks.')))
        raise SystemExit(2)
