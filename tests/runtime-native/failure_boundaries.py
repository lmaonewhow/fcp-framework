"""Independent native failure-boundary probes. No fake tokens/responses or business SQL.

Integration:
  failure = FailureBoundaryFixture(database, sql, private_dir, login_fixture)
  # Construct after RuntimeFixture, before JVM startup (account/authorization seed only).
  failure.verify(base, build_id, pid_provider)  # after ordinary runtime acceptance
  run_mysql_failure_boundaries(driver, storage_env)  # fresh V005 schema + distinct DML/DDL

The MySQL runner is separately usable on a newly-created disposable loopback test DB.
NOT_RUN/BLOCKED are never converted into PASS. This is not a server power-loss test.
"""
from __future__ import annotations
import concurrent.futures
import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT/'tools'))
sys.path.insert(0, str(Path(__file__).parent))
from ci_ingestion import redis_get, safe_diagnostic
from native_client import require_loopback
from native_login import authenticated_storage_state, require_encrypted_login, verify_native_identity, open_native_login
from native_runtime_acceptance import Client, RuntimeFixture, exported

PLUGIN = 'ci.boundary'
TENANT = '960001'
COMPANY = 'Synthetic CI tenant'
CLIENT_ID = 'e5cd7e4891bf95d1d19206ce24a7b32e'


def package_bytes():
    capability = dict(id='echo', version='1.0.0', permission='echo', sideEffects=False,
                      idempotent=True, timeoutMillis=10000,
                      inputSchema=dict(type='object', properties=dict(message=dict(type='string', maxLength=1000)), additionalProperties=False),
                      outputSchema=dict(type='object'))
    manifest = dict(manifestVersion=1, pluginId=PLUGIN, version='1.0.0', publisher='test',
                    contractVersion='1.0', runtime=dict(type='local', entry='runtime.py', argv=[]),
                    provides=[capability], requires=[], dependencies=[], ui={})
    output = io.BytesIO()
    with zipfile.ZipFile(output, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
        for name, content in [('plugin.json', json.dumps(manifest, separators=(',', ':')).encode()),
                              ('runtime.py', (Path(__file__).parent/'plugins/runtime.py').read_bytes())]:
            info = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            archive.writestr(info, content)
    return output.getvalue(), manifest


def run_mysql_failure_boundaries(driver, env, output=None):
    """Compile/run only own Java entrypoint, with actual Connector/J and passed private env.

    Required env: FCP_STORAGE_TEST_URL/USER/PASSWORD/DEPLOY_USER/DEPLOY_PASSWORD.
    Caller owns V005 initialization and distinct DML/deployment grants. No SQL fixtures
    or cleanup are executed by this wrapper. Returns only observed Java report.
    """
    output = Path(output or ROOT/'reports/runtime-failure-boundaries/mysql.json')
    output.parent.mkdir(parents=True, exist_ok=True)
    source = ROOT/'tests/runtime-native/failure-fixtures/MysqlFailureBoundariesTest.java'
    classes = ROOT/'build/runtime-failure-boundaries'
    classes.mkdir(parents=True, exist_ok=True)
    metadata = dict(sourceHashes={str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest() for path in sorted((ROOT/'core/src/main/java').rglob('*.java'))+[source, Path(__file__)]},
                    gitHead=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
                    scope='Actual MySQL with JDBC post-success faults and controlled-policy pre-commit USER/TASK revocation; not native HTTP/draining, TCP disconnect or server power loss')
    output.write_text(json.dumps(dict(status='NOT_RUN', **metadata), indent=2)+'\n')
    try:
        with (output.parent/'mysql-compile.log').open('w') as log:
            subprocess.run(['javac', '--release', '17', '-encoding', 'UTF-8', '-sourcepath', str(ROOT/'core/src/main/java'), '-d', str(classes), str(source)],
                           cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, timeout=120, check=True)
        # Java writes the report progressively, including observed failure; credentials
        # remain in the process environment and never appear on the command line.
        with (output.parent/'mysql-run.log').open('w') as log:
            process = subprocess.run(['java', '-cp', str(classes)+os.pathsep+str(driver), 'MysqlFailureBoundariesTest', str(output)],
                                     cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT, timeout=180)
        result = json.loads(output.read_text())
        result.update(metadata, exitCode=process.returncode)
        output.write_text(json.dumps(result, indent=2)+'\n')
        if process.returncode or result.get('status') != 'PASS':
            raise RuntimeError('Actual MySQL failure-boundary test did not pass; inspect sanitized report and logs')
        return result
    except Exception:
        result = json.loads(output.read_text())
        if result.get('status') == 'NOT_RUN':
            result.update(status='FAIL', reason='Compilation or test process failed before evidence was emitted')
            output.write_text(json.dumps(result, indent=2)+'\n')
        raise


class FailureBoundaryFixture:
    """Genuine RuoYi HTTP, independently installed package, explicit synthetic identities."""
    def __init__(self, database, sql, private_dir, login_fixture):
        if os.environ.get('GITHUB_ACTIONS') != 'true' or not re.fullmatch(r'fcp_ci_[a-f0-9]{12}', database):
            raise RuntimeError('Failure boundaries require a newly-created disposable CI database')
        if login_fixture is None or login_fixture.database != database:
            raise RuntimeError('Matching genuine login fixture is required')
        self.database, self.sql, self.private_dir, self.login_fixture = database, sql, private_dir, login_fixture
        self.output = ROOT/'reports/runtime-failure-boundaries/http.json'
        self.output.parent.mkdir(parents=True, exist_ok=True)
        self.report = dict(status='NOT_RUN', scope='Actual native encrypted login, HTTP, MySQL grants and shared Python process',
                           production='NOT_ACCEPTED', checks=[], inFlightUserTaskWriteRevocation='NOT_RUN', multiInstanceGrantRace='NOT_RUN')
        # Only account/tenant/role/menu authorization seed. Every package/grant/lifecycle
        # action below uses native HTTP and every token comes from genuine encrypted login.
        import bcrypt
        hashed = bcrypt.hashpw(login_fixture.password.encode(), bcrypt.gensalt(rounds=10, prefix=b'2a')).decode()
        sql(f"""USE `{database}`;
INSERT INTO sys_tenant_package(package_id,package_name,menu_ids,status,del_flag,create_by,create_time)
VALUES(960620,'CI boundaries','942000,942008,942020,942021,942022,950520','0','0',1,NOW());
INSERT INTO sys_tenant(id,tenant_id,company_name,package_id,status,del_flag,create_by,create_time)
VALUES(960620,'{TENANT}','{COMPANY}',960620,'0','0',1,NOW());
INSERT INTO sys_dept(dept_id,tenant_id,parent_id,ancestors,dept_name,status,del_flag,create_by,create_time)
VALUES(960620,'{TENANT}',0,'0','CI boundaries','0','0',1,NOW());
INSERT INTO sys_role(role_id,tenant_id,role_name,role_key,role_sort,data_scope,status,del_flag,create_by,create_time)
VALUES(960610,'000000','CI second manager','fcp_ci_second_manager',92,'5','0','0',1,NOW()),
(960611,'{TENANT}','CI tenant manager','fcp_ci_tenant_manager',93,'5','0','0',1,NOW());
INSERT INTO sys_role_menu(role_id,menu_id) VALUES
(960610,942000),(960610,942020),(960610,942021),(960610,942022),(960610,950520),
(960611,942000),(960611,942008),(960611,942020),(960611,942021),(960611,942022),(960611,950520);
INSERT INTO sys_user(user_id,tenant_id,dept_id,user_name,nick_name,password,status,del_flag,create_time)
VALUES(960600,'000000',103,'fcp_second_manager_ci','Synthetic CI manager','{hashed}','0','0',NOW()),
(960601,'{TENANT}',960620,'fcp_tenant_manager_ci','Synthetic CI tenant manager','{hashed}','0','0',NOW());
INSERT INTO sys_user_role(user_id,role_id) VALUES(960600,960610),(960601,960611);
""")
        self._write()

    def _write(self):
        self.output.write_text(json.dumps(self.report, ensure_ascii=False, indent=2)+'\n')

    def check(self, name, condition):
        if not condition:
            raise AssertionError(name)
        self.report['checks'].append(dict(name=name, status='PASS'))
        self._write()

    def _login(self, browser, base, username, tenant):
        """Same pinned Vue encrypted login/captcha flow, with explicit native tenant choice."""
        from playwright.sync_api import expect
        context = browser.new_context(viewport=dict(width=1440, height=960))
        page = context.new_page()
        try:
            captcha = open_native_login(page, base, 'plugins', wait_events=self.report.setdefault('captchaRateLimitWaits', []))
            self.check(username+' native captcha is enabled', captcha.get('captchaEnabled', True) and bool(captcha.get('uuid')))
            if tenant != '000000':
                select = page.locator('.login-form .el-select')
                expect(select).to_be_visible(timeout=30000)
                select.click()
                page.get_by_role('option', name=COMPANY, exact=True).click()
            answer = json.loads(redis_get(self.login_fixture.redis_password,
                                         self.login_fixture.redis_prefix+':global:captcha_codes:'+captcha['uuid']))
            if not isinstance(answer, str):
                raise RuntimeError('Unexpected native captcha codec')
            page.get_by_placeholder(re.compile(r'^(用户名|Username)$')).fill(username)
            page.locator('input[type=password]').fill(self.login_fixture.password)
            page.get_by_placeholder(re.compile(r'^(验证码|Verification Code)$')).fill(answer)
            with page.expect_response(lambda r: r.url.endswith('/auth/login'), timeout=30000) as response:
                page.locator('.login-form button.el-button--primary').click()
            require_encrypted_login(response.value)
            _state, token = authenticated_storage_state(page, context, base, 'plugins')
            self.login_fixture.diagnostic_secrets.append(token)
            client = Client(base, token, CLIENT_ID)
            verify_native_identity(client, {'fcp_second_manager_ci':'960600','fcp_tenant_manager_ci':'960601'}[username], tenant)
            self.check(username+' genuine encrypted login and server identity verified', True)
            return client
        finally:
            context.close()

    @staticmethod
    def _confirm(client, plan, key):
        return client.request('/fcp/plans/'+plan['planId']+'/confirm',
                              dict(digest=plan['digest'], affectedPlugins=plan['affectedPlugins'], idempotencyKey=key))

    @staticmethod
    def _ok(response):
        return response[0] < 400 and str(response[1].get('code')) == '200'

    @staticmethod
    def _code(response):
        data = response[1].get('data')
        return data.get('fcpCode') if isinstance(data, dict) else None

    def _grant_plan(self, client, tenant, allow):
        options = client.data('/fcp/plugins/'+PLUGIN+'/grant-options?targetTenant='+tenant)
        return client.data('/fcp/plugins/'+PLUGIN+'/grants/plans',
                           dict(targetTenant=tenant, expectedRevision=options['revision'],
                                grants=[dict(capabilityId='echo', dataScope='tenant:'+tenant, allow=allow)]))

    def _grant(self, client, tenant, allow, key):
        response = self._confirm(client, self._grant_plan(client, tenant, allow), key)
        self.check(tenant+' explicit '+('grant' if allow else 'revocation')+' '+key, self._ok(response) and response[1]['data'].get('phase') == 'COMPLETE')

    def _lifecycle(self, client, action, key):
        revision = client.data('/fcp/plugins/'+PLUGIN)['revision']
        plan = client.data('/fcp/plugins/'+PLUGIN+'/plans', dict(action=action, expectedRevision=revision))
        result = self._confirm(client, plan, key)
        self.check(key+' lifecycle closure completes', self._ok(result) and result[1]['data'].get('phase') == 'COMPLETE')

    def _invoke(self, client, key, message='boundary'):
        catalog = client.data('/fcp/ui/catalog')['catalog']
        return client.request('/fcp/capabilities/'+exported(PLUGIN)+'/invoke',
                              dict(input=dict(message=message), expectedRevision=catalog['revision'], idempotencyKey=key))

    def _forged_header(self, client, key, tenant):
        catalog = client.data('/fcp/ui/catalog')['catalog']
        body = dict(input=dict(message='forged-header'), expectedRevision=catalog['revision'], idempotencyKey=key)
        headers = {'Content-Type': 'application/json', 'clientid': CLIENT_ID,
                   'Authorization': 'Bearer '+client.token, 'tenant-id': tenant,
                   'X-Tenant-Id': tenant, 'X-Fcp-Tenant': tenant, 'X-Fcp-Principal': '1', 'X-Fcp-Principal-Kind': 'TASK'}
        request = urllib.request.Request(client.base+'/fcp/capabilities/'+exported(PLUGIN)+'/invoke', data=json.dumps(body).encode(), headers=headers)
        try:
            with client.opener.open(request, timeout=20) as response:
                return response.status, json.loads(response.read(2*1024*1024))
        except urllib.error.HTTPError as error:
            return error.code, json.loads(error.read(2*1024*1024))

    def verify(self, base, build_id, pid_provider):
        require_loopback(base)
        if os.environ.get('FCP_ACCEPTANCE_DATABASE') != self.database:
            raise RuntimeError('Explicit acceptance database mismatch')
        from playwright.sync_api import sync_playwright
        started = time.monotonic()
        pid = pid_provider()
        self.report.update(status='RUNNING', buildId=build_id, nativeJavaPid=pid)
        self._write()
        try:
            with sync_playwright() as playwright:
                browser = playwright.chromium.launch()
                try:
                    state = self.login_fixture.login(browser, base, 'admin')
                    admin = Client(base, RuntimeFixture.token(state, base), CLIENT_ID)
                    second = self._login(browser, base, 'fcp_second_manager_ci', '000000')
                    tenant = self._login(browser, base, 'fcp_tenant_manager_ci', TENANT)
                    self.check('genuine native MySQL build identity matches', admin.evidence()['buildId'] == build_id)
                    archive, manifest = package_bytes()
                    digest = hashlib.sha256(archive).hexdigest()
                    result = admin.upload(archive, digest, 'boundary-install', admin.data('/fcp/plugins')['revision'])
                    self.check('independent boundary package installed through native HTTP', result.get('digest') == digest)
                    self.report['packageSha256'] = digest
                    # Same revision/planned previous grant rows, two separately authenticated
                    # administrators, overlapping HTTP requests. Single native JVM, not HA.
                    plans = [(admin, self._grant_plan(admin, '000000', True)),
                             (second, self._grant_plan(second, '000000', False))]
                    barrier = threading.Barrier(2)
                    def confirm(index):
                        client, plan = plans[index]
                        barrier.wait(timeout=15)
                        return self._confirm(client, plan, 'boundary-grant-race-'+str(index))
                    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
                        results = list(pool.map(confirm, range(2)))
                    self.check('A05 concurrent native administrator confirmations have one winner and one stale-plan conflict',
                               sum(self._ok(r) for r in results) == 1 and sum(self._code(r) == 'VERSION_CONFLICT' for r in results) == 1)
                    self._grant(admin, '000000', True, 'boundary-grant-primary')
                    self._lifecycle(admin, 'ENABLE', 'boundary-enable-primary')
                    primary = self._invoke(admin, 'boundary-primary-before-tenant')
                    self.check('primary native identity comes from authenticated server session',
                               self._ok(primary) and primary[1]['data'].get('tenantId') == '000000' and str(primary[1]['data'].get('principalId')) == '1')
                    # No grant for second tenant may be inherited from first tenant.
                    denied = self._invoke(tenant, 'boundary-no-foreign-grant')
                    self.check('ungranted second native tenant is rejected before enablement', not self._ok(denied))
                    self._grant(admin, TENANT, True, 'boundary-grant-second-tenant')
                    self._lifecycle(tenant, 'ENABLE', 'boundary-enable-second-tenant')
                    other = self._invoke(tenant, 'boundary-second-tenant')
                    self.check('A07 second genuine login retains its server tenant and USER principal',
                               self._ok(other) and other[1]['data'].get('tenantId') == TENANT and str(other[1]['data'].get('principalId')) == '960601' and other[1]['data'].get('principalKind') == 'USER')
                    self.check('A07 both native tenants invoke the same real plugin process',
                               primary[1]['data'].get('pluginPid') == other[1]['data'].get('pluginPid') and isinstance(other[1]['data'].get('pluginPid'), int))
                    for client, expected_tenant, principal, forged in [(admin, '000000', '1', TENANT), (tenant, TENANT, '960601', '000000')]:
                        response = self._forged_header(client, 'forged-header-'+expected_tenant, forged)
                        self.check('A20 spoofed tenant/principal headers never switch '+expected_tenant+' identity',
                                   not self._ok(response) or (response[1]['data'].get('tenantId') == expected_tenant and str(response[1]['data'].get('principalId')) == principal and response[1]['data'].get('principalKind') == 'USER'))
                        catalog = client.data('/fcp/ui/catalog')['catalog']
                        for field, value in [('tenantId', forged), ('principal', dict(kind='TASK', id='forged-task')), ('dataScope', 'tenant:'+forged), ('pluginId', 'ci.python')]:
                            body = dict(input=dict(message='forged-body'), expectedRevision=catalog['revision'], idempotencyKey='forged-'+expected_tenant+'-'+field)
                            body[field] = value
                            response = client.request('/fcp/capabilities/'+exported(PLUGIN)+'/invoke', body)
                            self.check('A20 unknown identity envelope field '+field+' rejected for '+expected_tenant, not self._ok(response) and self._code(response) == 'INVALID_ARGUMENT')
                    self._grant(admin, TENANT, False, 'boundary-revoke-second-only')
                    self.check('A20 enabled second tenant cannot inherit first tenant grant after its own grant is revoked', not self._ok(self._invoke(tenant, 'boundary-second-revoked')))
                    self.check('revoking second tenant leaves first tenant independently authorized', self._ok(self._invoke(admin, 'boundary-primary-unaffected')))
                    self._lifecycle(tenant, 'DISABLE', 'boundary-disable-second')
                    self._lifecycle(admin, 'DISABLE', 'boundary-disable-primary')
                    current = admin.data('/fcp/plugins/'+PLUGIN)
                    admin.data('/fcp/plugins/'+PLUGIN+'/uninstall', dict(expectedRevision=current['revision'], confirmedDigest=digest))
                    self.check('native JVM remains unchanged through concurrency and tenant boundaries', isinstance(pid, int) and pid_provider() == pid)
                    self.report.update(status='PASS', totalChecks=len(self.report['checks']))
                finally:
                    browser.close()
        except Exception as error:
            self.report.update(status='FAIL', error=safe_diagnostic(type(error).__name__+': '+str(error), self.login_fixture.diagnostic_secrets))
            raise
        finally:
            self.report['elapsedSeconds'] = round(time.monotonic()-started, 3)
            self._write()
        return self.report


if __name__ == '__main__':
    print(json.dumps(dict(status='NOT_RUN', reason='Use explicit isolated native fixture or run_mysql_failure_boundaries with genuine MySQL credentials.')))
    raise SystemExit(2)
