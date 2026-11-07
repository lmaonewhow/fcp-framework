"""Actual native release composition acceptance. Never run as a component PASS.

The existing isolated CI owns MySQL, Redis, Spring and native encrypted login.
Every deployment changes the control plane through its authorized HTTP operations.
No policy setter, database release-state mutation or fabricated browser response exists.
"""
from __future__ import annotations
import hashlib
import json
import os
from pathlib import Path
import sys
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT/'tools'))
from ci_ingestion import safe_diagnostic
from native_client import require_loopback
from native_runtime_acceptance import Client, RuntimeFixture, exported, package_bytes
from ui_boundaries import is_rejected

CLIENT_ID = 'e5cd7e4891bf95d1d19206ce24a7b32e'
RELEASES = {'stable': 'ci-composite-stable', 'candidate': 'ci-composite-candidate'}


def manifest_digest(value):
    """VersionCodec v1 canonical encoding; fixture documents contain no floating point."""
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False,
                                     separators=(',', ':')).encode()).hexdigest()


def package_artifact(language, version, content):
    plugin = 'ci.'+language
    return dict(id='ci-package-'+language+'-'+version.replace('.', '-'), kind='PLUGIN',
                component=plugin, version=version, sha256=hashlib.sha256(content).hexdigest(),
                sizeBytes=len(content), location='package:'+plugin+'@'+version,
                compatibility={'hostContract': 'fcp.host/1'}, metadata={'purpose': 'native-composite-acceptance'})


class ReleaseClient(Client):
    """A real native HTTP client; affinity stays in memory and is never reported."""
    def request(self, path, body=None, token=True, affinity=None):
        if affinity is None:
            return super().request(path, body, token)
        if not isinstance(path, str) or not path.startswith('/fcp/') or any(c in path for c in ('\r', '\n', '#', '\\')):
            raise ValueError('Fixed local native endpoint required')
        headers = {'Content-Type': 'application/json', 'clientid': self.client_id,
                   'X-FCP-Release-Affinity': affinity}
        if token:
            headers['Authorization'] = 'Bearer '+self.token
        request = urllib.request.Request(self.base+path, headers=headers,
                    data=None if body is None else json.dumps(body, ensure_ascii=False).encode())
        try:
            with self.opener.open(request, timeout=30) as response:
                status, raw = response.status, response.read(2*1024*1024)
        except urllib.error.HTTPError as error:
            status, raw = error.code, error.read(2*1024*1024)
        return status, json.loads(raw)

    def selected(self, path, affinity, body=None):
        status, result = self.request(path, body, affinity=affinity)
        if status >= 400 or str(result.get('code')) != '200':
            raise RuntimeError('Release-affine request rejected: '+path+' HTTP='+str(status))
        return result['data']


class ReleaseCompositionFixture(RuntimeFixture):
    def __init__(self, runtime_fixture, tenant_fixture):
        # RuntimeFixture seeded genuine menus/roles only. Never call its legacy verify.
        self.database, self.sql = runtime_fixture.database, runtime_fixture.sql
        self.private_dir, self.login_fixture = runtime_fixture.private_dir, runtime_fixture.login_fixture
        # Deliberately grant release menus to a non-platform synthetic role. The
        # native host must still enforce the platform-tenant restriction.
        self.sql(f"USE `{self.database}`; INSERT INTO sys_role_menu(role_id,menu_id) VALUES(960611,942030),(960611,942031); UPDATE sys_tenant_package SET menu_ids=CONCAT(menu_ids,',942030,942031') WHERE package_id=960620;")
        self.ui_versions = None
        self.output = ROOT/'reports/native-release-composition'
        self.output.mkdir(parents=True, exist_ok=True)
        self.report = dict(status='NOT_RUN', browser='NOT_RUN', production='NOT_ACCEPTED',
                           scope='Real MySQL + encrypted native login + Spring composite executor + two rebuilt Vue shells + isolated Python/Node release processes',
                           checks=[], screenshots=[], operations=[], packages=[], observedBindings=[], browserErrors=[], backendPidUnchanged='NOT_RUN',
                           taskAffinity='NOT_RUN: native TASK scheduler/Exchange adapter is intentionally not enabled')
        self._write()

    def grant(self, admin, artifact, allow=True, tenant='000000'):
        target = dict(pluginId=artifact['component'], version=artifact['version'], digest=artifact['sha256'],
                      targetTenant=tenant, dataScope='tenant:'+tenant, principalKind='USER', principalId='*')
        options = admin.data('/fcp/plugin-releases/grant-options', target)
        echo = next(o for o in options['options'] if o['capability'] == 'echo')
        result = admin.data('/fcp/plugin-releases/grants', dict(**target, confirmedDigest=artifact['sha256'],
                    capability='echo', operation='invoke', allow=allow, expiresAt=None, expectedRevision=echo['revision']))
        self.check('exact '+artifact['component']+' '+artifact['version']+' '+tenant+' grant '+str(allow),
                   result['allowed'] is allow and result['digest'] == artifact['sha256'])
        return result

    def operation(self, admin, action, basis, phase):
        state = admin.data('/fcp/releases/state')
        identity = 'native-composite-'+str(len(self.report['operations']))+'-'+action.lower()
        command = dict(expectedRevision=state['revision'], operationId=identity, action=action, basisPoints=basis)
        result = admin.data('/fcp/releases/operations', command)
        # Reconcile only an accepted asynchronous result; never resubmit an uncertain action.
        until = time.monotonic()+30
        while 'pending' in result and time.monotonic() < until:
            time.sleep(.1)
            result = admin.data('/fcp/releases/reconcile', {})
        self.check(action+' observed by actual composite executor', result.get('phase') == phase and
                   result.get('basisPoints') == basis and result.get('observed', {}).get('complete') is True and
                   result.get('observed', {}).get('outcome') == 'SUCCEEDED')
        replay = admin.data('/fcp/releases/operations', command)
        self.check(action+' operation replay does not deploy twice', replay['revision'] == result['revision'])
        self.report['operations'].append(dict(action=action, phase=result['phase'], generation=result['generation'],
                    revision=result['revision'], observation=result['observed']))
        self._write()
        return result

    def selection(self, client, shell, stage=None, affinity=None):
        manifest = (client.data('/fcp/ui/releases/bootstrap?shell='+shell) if affinity is None else
                    client.selected('/fcp/ui/releases/bootstrap?shell='+shell, affinity))
        catalog = client.selected('/fcp/ui/catalog', manifest['affinity'])
        if stage is not None:
            self.check(shell+' '+stage+' immutable UI selected', manifest['releaseId'] == RELEASES[stage] and
                       manifest['bundleDigest'] == self.ui_versions['digests'][stage][shell])
        self.check(shell+' UI and plugin catalog share one release decision',
                   catalog['releaseId'] == manifest['releaseId'])
        self.login_fixture.diagnostic_secrets.extend([manifest['affinity'], catalog['affinity']])
        return catalog

    def invoke(self, client, catalog, version, message, plugin='ci.python', dependency=False, tenant='000000'):
        reply = client.selected('/fcp/capabilities/'+exported(plugin)+'/invoke', catalog['affinity'],
                    dict(input=dict(message=message, dependency=dependency),
                         expectedRevision=catalog['catalog']['revision'], idempotencyKey=message))
        self.check(message+' invokes exact package and server tenant', reply.get('packageVersion') == version and
                   reply.get('message') == message and reply.get('tenantId') == tenant)
        self.assert_child(reply['pluginPid'], self.pid, message)
        if dependency:
            self.check(message+' Node callback stays in same Python release', reply.get('callerLanguage') == 'node')
        return reply

    def browser_invoke(self, page, shell, version, label):
        from playwright.sync_api import expect
        stage = {'1.0.0':'stable', '1.1.0':'candidate'}[version]
        catalog = self.browser_catalog(page)
        bundle = page.locator('meta[name="fcp-ui-bundle"]').get_attribute('content')
        self.check(shell+' '+label+' UI/catalog/package composition agrees',
                   bundle == self.ui_versions['digests'][stage][shell] and catalog['releaseId'] == RELEASES[stage])
        selected = page.get_by_test_id('plugin-page')
        expect(selected).to_be_visible(timeout=30000)
        selected.get_by_role('textbox', name='Message', exact=True).fill(label)
        with page.expect_response(lambda r: '/fcp/capabilities/' in r.url and r.url.endswith('/invoke'), timeout=30000) as response:
            selected.get_by_role('button', name='调用插件', exact=True).click()
        reply = response.value.json()
        self.check(shell+' '+label+' UI invokes exact version', str(reply.get('code')) == '200' and
                   reply.get('data', {}).get('packageVersion') == version and
                   str(reply.get('data', {}).get('principalId')) == ('1' if shell == 'admin' else '950001'))
        self.check(shell+' '+label+' UI sends trusted affinity',
                   bool(response.value.request.headers.get('x-fcp-release-affinity')))
        expect(selected.locator('table')).to_contain_text(label)
        artifact = next(p for p in self.report['packages'] if p['component'] == 'ci.python' and p['version'] == version)
        self.report['observedBindings'].append(dict(shell=shell, releaseId=catalog['releaseId'],
                    bundleDigest=bundle, pluginId='ci.python', packageVersion=reply['data']['packageVersion'],
                    packageDigest=artifact['sha256'], pluginPid=reply['data']['pluginPid'], label=label))
        self._write()
        return reply['data']

    def browser_catalog(self, page):
        with page.expect_response(lambda r: r.url.split('?', 1)[0].endswith('/fcp/ui/catalog'), timeout=15000) as response:
            page.get_by_role('button', name='刷新目录', exact=True).click()
        payload = response.value.json()
        if response.value.status != 200 or str(payload.get('code')) != '200':
            raise AssertionError('Actual browser catalog renewal failed')
        selected = payload['data']
        self.login_fixture.diagnostic_secrets.append(selected['affinity'])
        return selected

    def verify(self, base, build_id, pid_provider):
        require_loopback(base)
        if os.environ.get('GITHUB_ACTIONS') != 'true' or os.environ.get('FCP_ACCEPTANCE_DATABASE') != self.database:
            raise RuntimeError('Exact isolated native CI database is required')
        if not self.ui_versions or self.ui_versions.get('sourceBuildId') != build_id:
            raise RuntimeError('Two genuine typed native UI builds must be prepared first')
        from playwright.sync_api import sync_playwright, expect
        from ui_release_acceptance import verify_ui_current
        self.pid = pid_provider()
        if not isinstance(self.pid, int) or self.pid < 1:
            raise RuntimeError('Live native JVM required')
        started = time.monotonic()
        self.report.update(status='RUNNING', buildId=build_id, nativeJavaPid=self.pid)
        try:
            with sync_playwright() as playwright:
                browser = playwright.chromium.launch()
                self.report['browserVersion'] = browser.version
                self.report['browser'] = 'RUNNING'
                contexts = []
                try:
                    states = {role: self.login_fixture.login(browser, base, role) for role in ('admin', 'teacher')}
                    clients = {role: ReleaseClient(base, self.token(state, base), CLIENT_ID) for role, state in states.items()}
                    admin, teacher = clients['admin'], clients['teacher']
                    self.check('genuine native Spring/MySQL host build', admin.evidence()['buildId'] == build_id)
                    state = admin.data('/fcp/releases/state')
                    self.check('real native composite executor advertises all release transitions', state['phase'] == 'EMPTY' and
                               set(state['capabilities']) == {'DEPLOY', 'CANARY', 'EXPAND', 'PAUSE', 'ABORT', 'PROMOTE', 'ROLLBACK', 'RETIRE'})
                    status, body = admin.request('/fcp/releases/state', token=False)
                    self.check('release control requires genuine native session', is_rejected(status, body))
                    packages = {}
                    for language, version in [('python', '1.0.0'), ('python', '1.1.0'), ('node', '1.0.0')]:
                        content, _manifest = package_bytes(language, version)
                        artifact = package_artifact(language, version, content)
                        uploaded = admin.upload(content, artifact['sha256'], 'composite-stage-'+artifact['id'], staged=True)
                        self.check(artifact['id']+' verified staged bytes', uploaded['digest'] == artifact['sha256'])
                        self.check(artifact['id']+' immutable registry record', admin.data('/fcp/releases/artifacts', artifact) == artifact)
                        packages[(language, version)] = artifact
                        self.report['packages'].append(artifact)
                    for artifact in self.ui_versions['artifacts']:
                        self.check(artifact['id']+' actual UI artifact registered', admin.data('/fcp/releases/artifacts', artifact) == artifact)
                    schema = int(self.sql(f'USE `{self.database}`; SELECT MAX(version_num) FROM fcp_migration_history;').strip())
                    releases = {}
                    for stage, version in [('stable', '1.0.0'), ('candidate', '1.1.0')]:
                        components = dict(self.ui_versions['components'][stage], python=packages[('python', version)]['id'], node=packages[('node', '1.0.0')]['id'])
                        release = dict(id=RELEASES[stage], version=version, components=components,
                            compatibility=dict(schemaMin=str(schema), schemaMax=str(schema), hostContract='fcp.host/1'),
                            metadata={'purpose': 'actual-native-composition'})
                        self.check(stage+' immutable system composition registered', admin.data('/fcp/releases/versions', release) == release)
                        releases[stage] = release
                    self.grant(admin, packages[('python', '1.0.0')])
                    self.grant(admin, packages[('node', '1.0.0')])
                    plan = dict(environment='ci', stableRelease=RELEASES['stable'], candidateRelease=RELEASES['candidate'],
                                stableDigest=manifest_digest(releases['stable']), candidateDigest=manifest_digest(releases['candidate']),
                                schemaVersion=schema, stableSchemaMin=schema, stableSchemaMax=schema,
                                candidateSchemaMin=schema, candidateSchemaMax=schema)
                    planned = admin.data('/fcp/releases/plans', dict(expectedRevision=state['revision'], operationId='native-composite-plan', plan=plan))
                    self.check('plan does not pretend deployment', planned['phase'] == 'PLANNED' and
                               not admin.data('/fcp/plugin-releases')['releases'])
                    status, body = teacher.request('/fcp/releases/operations', dict(expectedRevision=planned['revision'], operationId='teacher-denied', action='DEPLOY', basisPoints=0))
                    self.check('native release viewer cannot deploy', is_rejected(status, body))
                    self.operation(admin, 'DEPLOY', 0, 'READY')
                    for release_id in RELEASES.values():
                        actual = admin.data('/fcp/plugin-releases/'+release_id+'/status')
                        self.check(release_id+' actual processes prepared by composite executor', actual['ready'] and actual['enabled'])
                        for plugin, health in actual['processes'].items():
                            self.assert_child(health['pid'], self.pid, release_id+' '+plugin)
                    stable = self.selection(admin, 'admin', 'stable')
                    self.invoke(admin, stable, '1.0.0', 'stable-python-before-canary')
                    self.invoke(admin, stable, '1.0.0', 'stable-node-callback', 'ci.node', True)
                    verify_ui_current(self, browser, states, base, clients, RELEASES['stable'], self.ui_versions['digests']['stable'], 'stable')
                    stable = self.selection(admin, 'admin', 'stable')
                    stable_pages = []
                    for role, shell in [('admin', 'admin'), ('teacher', 'teacher')]:
                        context = browser.new_context(storage_state=states[role], viewport={'width':1440, 'height':960})
                        contexts.append(context)
                        page = context.new_page()
                        page.on('pageerror', lambda error: self.report['browserErrors'].append(safe_diagnostic(str(error), self.login_fixture.diagnostic_secrets)))
                        page.on('dialog', lambda dialog: dialog.accept() if dialog.type == 'beforeunload' else dialog.dismiss())
                        page.goto(base+'/'+shell+'/fcp/plugins/ci.python/demo', wait_until='domcontentloaded')
                        expect(page.locator('meta[name="fcp-ui-bundle"]')).to_have_attribute('content', self.ui_versions['digests']['stable'][shell])
                        self.browser_invoke(page, shell, '1.0.0', 'stable-'+shell+'-browser')
                        stable_pages.append((page, shell))
                    self.operation(admin, 'CANARY', 2500, 'CANARY')
                    first = self.selection(admin, 'admin')
                    second = self.selection(admin, 'admin')
                    same_tenant = self.selection(teacher, 'teacher')
                    self.check('canary tenant bucketing stable across requests and native users',
                               first['releaseId'] == second['releaseId'] == same_tenant['releaseId'])
                    self.operation(admin, 'PAUSE', 2500, 'PAUSED')
                    self.check('pause retains actual canary assignment', self.selection(admin, 'admin')['releaseId'] == first['releaseId'])
                    self.operation(admin, 'EXPAND', 5000, 'CANARY')
                    self.operation(admin, 'PROMOTE', 10000, 'FULL')
                    candidate = self.selection(admin, 'admin', 'candidate')
                    self.check('new package digest has no inherited authorization',
                               'ci.python' not in {page['pluginId'] for page in candidate['catalog']['pages']})
                    status, body = admin.request('/fcp/capabilities/'+exported('ci.python')+'/invoke',
                        dict(input={}, expectedRevision=candidate['catalog']['revision'], idempotencyKey='candidate-unapproved'), affinity=candidate['affinity'])
                    self.check('unapproved candidate actual invoke denied', is_rejected(status, body))
                    self.grant(admin, packages[('python', '1.1.0')])
                    candidate = self.selection(admin, 'admin', 'candidate')
                    new = self.invoke(admin, candidate, '1.1.0', 'candidate-python')
                    self.invoke(admin, candidate, '1.1.0', 'candidate-node-callback', 'ci.node', True)
                    # Renew, rather than re-bucket, the previously authenticated stable lease.
                    stable = self.browser_catalog(stable_pages[0][0])
                    self.check('resident stable browser renews original release after promote', stable['releaseId'] == RELEASES['stable'])
                    old = self.invoke(admin, stable, '1.0.0', 'stable-while-candidate-full')
                    self.check('both actual package versions coexist in separate JVM children', old['pluginPid'] != new['pluginPid'])
                    for page, shell in stable_pages:
                        self.browser_invoke(page, shell, '1.0.0', 'stable-'+shell+'-after-promotion')
                        self.check(shell+' old UI remains on original immutable bundle', page.locator('meta[name="fcp-ui-bundle"]').get_attribute('content') == self.ui_versions['digests']['stable'][shell])
                    candidate_pages = {}
                    verify_ui_current(self, browser, states, base, clients, RELEASES['candidate'], self.ui_versions['digests']['candidate'], 'candidate')
                    for role, shell in [('admin', 'admin'), ('teacher', 'teacher')]:
                        context = browser.new_context(storage_state=states[role], viewport={'width':1440, 'height':960})
                        contexts.append(context)
                        page = context.new_page()
                        page.on('pageerror', lambda error: self.report['browserErrors'].append(safe_diagnostic(str(error), self.login_fixture.diagnostic_secrets)))
                        page.on('dialog', lambda dialog: dialog.accept() if dialog.type == 'beforeunload' else dialog.dismiss())
                        page.goto(base+'/'+shell+'/fcp/plugins/ci.python/demo', wait_until='domcontentloaded')
                        expect(page.locator('meta[name="fcp-ui-bundle"]')).to_have_attribute('content', self.ui_versions['digests']['candidate'][shell])
                        self.browser_invoke(page, shell, '1.1.0', 'candidate-'+shell+'-browser')
                        page.reload(wait_until='domcontentloaded')
                        self.browser_invoke(page, shell, '1.1.0', 'candidate-'+shell+'-refresh')
                        screenshot = self.output/(shell+'-candidate-plugin.png')
                        page.screenshot(path=str(screenshot), full_page=True)
                        self.report['screenshots'].append(str(screenshot.relative_to(ROOT)))
                        candidate_pages[shell] = page
                    # Genuine second native tenant login. Its role has business permissions but no
                    # version grants. A T1 affinity cannot be replayed under T2's native identity.
                    from ui_boundaries import native_logout, make_tenant_relogin
                    tenant_page = candidate_pages['teacher']
                    original_context = tenant_page.context
                    native_logout(tenant_page, 'teacher')
                    raw = make_tenant_relogin(self.login_fixture, base)(tenant_page, 'teacher')
                    tenant = ReleaseClient(base, raw.token, raw.client_id)
                    self.check('same browser context performs genuine cross-tenant logout/login', tenant_page.context is original_context)
                    tenant_page.goto(base+'/teacher/fcp/plugins/ci.python/demo', wait_until='domcontentloaded')
                    expect(tenant_page.get_by_test_id('plugin-page')).to_have_count(0)
                    expect(tenant_page.get_by_text('该插件页面不存在、已停用或当前身份无权访问。', exact=True)).to_be_visible()
                    self.check('tenant switch clears old page/results before new grants', tenant_page.get_by_text('candidate-teacher-refresh', exact=True).count() == 0)
                    tenant_page.reload(wait_until='domcontentloaded')
                    expect(tenant_page.get_by_test_id('plugin-page')).to_have_count(0)
                    self.check('tenant refresh cannot recover another tenant page', True)
                    foreign = self.selection(tenant, 'teacher', 'candidate')
                    self.check('tenant without grants sees no foreign plugin pages', not foreign['catalog']['pages'])
                    candidate = self.selection(admin, 'admin', 'candidate')
                    status, body = tenant.request('/fcp/ui/catalog', affinity=candidate['affinity'])
                    self.check('cross-tenant release affinity rejected', is_rejected(status, body))
                    status, body = tenant.request('/fcp/releases/state')
                    self.check('non-platform release view denied despite explicit role grant', is_rejected(status, body))
                    status, body = tenant.request('/fcp/releases/reconcile', {})
                    self.check('non-platform release management denied despite explicit role grant', is_rejected(status, body))
                    for artifact in [packages[('python', '1.1.0')], packages[('node', '1.0.0')]]:
                        self.grant(admin, artifact, tenant='960001')
                    foreign = self.selection(tenant, 'teacher', 'candidate')
                    self.invoke(tenant, foreign, '1.1.0', 'tenant-two-own-invoke', tenant='960001')
                    self.grant(admin, packages[('python', '1.1.0')], False, tenant='960001')
                    status, body = tenant.request('/fcp/capabilities/'+exported('ci.python')+'/invoke',
                        dict(input={}, expectedRevision=foreign['catalog']['revision'], idempotencyKey='tenant-two-revoked'), affinity=foreign['affinity'])
                    self.check('tenant grant revocation rejects prior catalog immediately', is_rejected(status, body))
                    candidate = self.selection(admin, 'admin', 'candidate')
                    self.invoke(admin, candidate, '1.1.0', 'tenant-one-survives-foreign-revocation')
                    # Keep one candidate page open while ingress is fenced. Stale data never silently
                    # crosses the epoch or falls through to embedded assets on refresh.
                    page = candidate_pages['admin']
                    stable = self.browser_catalog(stable_pages[0][0])
                    candidate = self.browser_catalog(page)
                    self.operation(admin, 'ABORT', 0, 'ABORTED')
                    for old_selection in (stable, candidate):
                        status, body = admin.request('/fcp/ui/catalog', affinity=old_selection['affinity'])
                        self.check('ABORT denies stale '+old_selection['releaseId']+' affinity', is_rejected(status, body))
                    page.reload(wait_until='domcontentloaded')
                    expect(page.get_by_test_id('plugin-page')).to_have_count(0)
                    expect(page.get_by_text('当前系统版本不可用或身份已变化。未自动回切旧版，请刷新或联系管理员。', exact=True)).to_be_visible()
                    self.check('aborted browser refresh fails closed instead of fallback', True)
                    page.goto(base+'/admin/fcp/releases', wait_until='domcontentloaded')
                    expect(page.get_by_role('heading', name='系统发布、版本与灰度')).to_be_visible(timeout=30000)
                    self.check('trusted admin governance remains usable while routing stopped', page.locator('meta[name="fcp-ui-bundle"]').get_attribute('content') == 'embedded')
                    self.operation(admin, 'ROLLBACK', 0, 'ROLLED_BACK')
                    status, body = admin.request('/fcp/ui/catalog', affinity=candidate['affinity'])
                    self.check('rollback epoch rejects pre-abort candidate affinity', is_rejected(status, body))
                    recovered = self.selection(admin, 'admin', 'stable')
                    self.invoke(admin, recovered, '1.0.0', 'rollback-original-python')
                    states['teacher'] = self.login_fixture.login(browser, base, 'teacher')
                    clients['teacher'] = ReleaseClient(base, self.token(states['teacher'], base), CLIENT_ID)
                    verify_ui_current(self, browser, states, base, clients, RELEASES['stable'], self.ui_versions['digests']['stable'], 'rollback')
                    candidate_pids = [h['pid'] for h in admin.data('/fcp/plugin-releases/'+RELEASES['candidate']+'/status')['processes'].values()]
                    self.operation(admin, 'RETIRE', 0, 'RETIRED')
                    retired = admin.data('/fcp/plugin-releases/'+RELEASES['candidate']+'/status')
                    self.check('retire drains and stops only candidate runtime', not retired['enabled'] and not retired['ready'] and
                               retired['activeCalls'] == 0 and retired['pendingTasks'] == 0 and retired['activationState'] == 'STOPPED')
                    self.check('retired candidate OS children actually exit', all(not Path('/proc', str(pid)).exists() for pid in candidate_pids))
                    self.invoke(admin, self.selection(admin, 'admin', 'stable'), '1.0.0', 'stable-after-retire')
                    self.assert_pid(self.pid, pid_provider, 'after full native composite acceptance')
                    self.check('both real UI versions have no uncaught browser errors', not self.report['browserErrors'])
                    self.report.update(status='PASS', browser='PASS', backendPidUnchanged='PASS')
                except Exception:
                    # Only post-login synthetic workspaces are recorded, never native login forms.
                    for index, context in enumerate(contexts):
                        for page_index, failed_page in enumerate(context.pages):
                            if '/login' in failed_page.url:
                                continue
                            try:
                                target = self.output/('failure-'+str(index)+'-'+str(page_index)+'.png')
                                failed_page.screenshot(path=str(target), full_page=True)
                                self.report['screenshots'].append(str(target.relative_to(ROOT)))
                            except Exception:
                                pass
                    raise
                finally:
                    for context in contexts:
                        context.close()
                    browser.close()
        except Exception as error:
            self.report.update(status='FAIL', browser='FAIL' if self.report['browser'] == 'RUNNING' else self.report['browser'], errorType=type(error).__name__,
                               error=safe_diagnostic(str(error), self.login_fixture.diagnostic_secrets))
            raise
        finally:
            self.report['elapsedSeconds'] = round(time.monotonic()-started, 3)
            self.report['totalChecks'] = len(self.report['checks'])
            self._write()
        return self.report
