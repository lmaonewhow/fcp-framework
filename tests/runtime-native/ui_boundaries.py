"""Real dual-shell UI boundary probes; no response interception, fake tokens, or mocks.

Call before revoking grants/disabling ci.python. All declared fixture plugins must
already be installed, granted and enabled for the supplied native sessions. Caller
owns fixture setup and must place logout last, or supply fresh sessions afterward.
See UI_BOUNDARIES.md for the exact integration and optional slow-call contract.
"""
from __future__ import annotations
import json
import os
from pathlib import Path
import re
import sys
import time
import uuid
from urllib.parse import urlsplit

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'tools'))
from native_client import require_loopback
from native_login import wait_native_logout, open_native_tenant_login, NativeLoginPreparationError, authenticated_storage_state, require_encrypted_login, verify_native_identity, native_token


def require_fixture(base, database, fixture_plugins, plugin_id):
    require_loopback(base)
    if os.environ.get('GITHUB_ACTIONS') != 'true' or not re.fullmatch(r'fcp_ci_[a-f0-9]{12}', database):
        raise RuntimeError('Requires an explicitly selected disposable native CI database')
    if os.environ.get('FCP_ACCEPTANCE_DATABASE') != database:
        raise RuntimeError('Exact acceptance database must match')
    if plugin_id not in fixture_plugins or not fixture_plugins or any(not re.fullmatch(r'ci\.[a-z][a-z0-9._-]*', p) for p in fixture_plugins):
        raise ValueError('Only explicit synthetic ci.* plugin fixtures may be changed')


def exact_closure(plan, fixture_plugins):
    ids = plan.get('affectedPlugins')
    if not isinstance(ids, list) or not ids or len(set(ids)) != len(ids) or not set(ids) <= set(fixture_plugins):
        raise AssertionError('Plan closure is not confined to explicit fixture plugins')
    return ids


def is_rejected(status, body):
    # Network errors, generic 500s and invalid JSON are failures, not denial evidence.
    code = str(body.get('code', ''))
    return (400 <= status < 500) or (status == 200 and code in {'400', '401', '403', '404', '409', '410', '422'})


def native_logout(page, shell, *, events=None):
    """Execute locked native logout UI and wait for its native cleanup.

    This helper does not prove logout success: the caller must still check that
    the workspace disappears and the old token is rejected by the actual API.
    Do not read the response body, which Chromium may discard during redirect.
    """
    initial = urlsplit(page.url)
    origin = initial.scheme+'://'+initial.netloc
    require_loopback(origin)
    label = re.compile(r'^(退出登录|退出|注销|Log out|Logout)$', re.I)
    if shell == 'admin':
        page.locator('.avatar-wrapper').click()
        page.get_by_role('menuitem', name=label).click()
        positive = page.locator('.el-message-box').get_by_role('button', name='确定', exact=True)
    elif shell == 'teacher':
        # The plugin workspace contains no avatars; this is the upstream header NAvatar.
        page.locator('.n-avatar').click()
        page.locator('.n-dropdown-option-body').filter(has_text=label).click()
        positive = page.locator('.n-dialog').get_by_role('button', name=re.compile(r'^(确认|Confirm)$'))
    else:
        raise ValueError('Unsupported native shell')
    with page.expect_response(lambda response: response.url.split('?',1)[0] == origin+'/auth/logout', timeout=20000) as logged_out:
        positive.click()
    response = logged_out.value
    if response.status != 200:
        raise AssertionError('Native logout HTTP request did not complete with status 200')
    wait_native_logout(page, response, origin, events=events)


def make_tenant_relogin(login_fixture, base):
    """Return same-page genuine native encrypted login callback for seeded T2.

    Uses existing fcp_tenant_manager_ci / 960001 account. The caller must seed
    fcp:ui:admin for this synthetic role/package when checking both shell routes;
    this helper cannot grant a shell permission or any plugin capability.
    """
    require_loopback(base)
    def relogin(page, shell):
        from playwright.sync_api import expect
        from ci_ingestion import redis_get
        from native_client import NativeClient
        # No new browser context: native logout and T2 login share the prior storage.
        captcha = open_native_tenant_login(page, base, events=relogin.diagnostics)
        if not captcha.get('captchaEnabled', True) or not captcha.get('uuid'):
            raise AssertionError('Genuine native captcha must remain enabled')
        select = page.locator('.login-form .el-select')
        try:
            expect(select).to_be_visible(timeout=30000); select.click()
            page.get_by_role('option', name='Synthetic CI tenant', exact=True).click()
        except Exception:
            raise NativeLoginPreparationError('/auth/tenant/list', 'option-not-selectable', 200, '200') from None
        answer = json.loads(redis_get(login_fixture.redis_password, login_fixture.redis_prefix+':global:captcha_codes:'+captcha['uuid']))
        if not isinstance(answer,str): raise RuntimeError('Unexpected native captcha codec')
        page.get_by_placeholder(re.compile(r'^(用户名|Username)$')).fill('fcp_tenant_manager_ci')
        page.locator('input[type=password]').fill(login_fixture.password)
        page.get_by_placeholder(re.compile(r'^(验证码|Verification Code)$')).fill(answer)
        with page.expect_response(lambda r: r.url.endswith('/auth/login'), timeout=30000) as authenticated:
            page.locator('.login-form button.el-button--primary').click()
        require_encrypted_login(authenticated.value)
        _state, token = authenticated_storage_state(page, page.context, base, 'plugins')
        login_fixture.diagnostic_secrets.append(token)
        client = NativeClient(base,token,'e5cd7e4891bf95d1d19206ce24a7b32e')
        session = verify_native_identity(client, '960601', '960001')
        if shell not in session.get('allowedShells',[]):
            raise AssertionError('Synthetic T2 lacks required native shell permission; never bypass route guard')
        return client
    relogin.diagnostics = []
    return relogin


def verify_host_provenance(base, build_id, admin_api, viewer_apis, storage_states):
    """One authorized host-build proof; viewer proof uses only viewer-accessible APIs."""
    require_loopback(base)
    if set(viewer_apis) != {'admin','teacher'} or set(storage_states) != {'admin','teacher'}:
        raise ValueError('Both actual shell sessions are required')
    # Validate every destination before any credential-bearing request.
    if any(client.base != base.rstrip('/') for client in [admin_api,*viewer_apis.values()]):
        raise RuntimeError('Native API clients must share the tested browser origin')
    if admin_api.evidence().get('buildId') != build_id:
        raise RuntimeError('Native build identity differs from tested browser artifacts')
    for shell, client in viewer_apis.items():
        if native_token(storage_states[shell],base) != client.token:
            raise RuntimeError('Native API and browser must use the same authenticated session')
        session = client.data('/fcp/ui/session')
        context = client.data('/fcp/ui/catalog')['context']
        expected_user = {'admin':'1','teacher':'950001'}[shell]
        if (shell not in session.get('allowedShells', [])
                or str(session.get('subject')) != expected_user
                or session.get('scope') != 'tenant-000000'
                or str(context.get('userId')) != expected_user
                or context.get('tenantId') != '000000'):
            raise RuntimeError('Native viewer shell or authenticated identity mismatch')


def verify_ui_boundaries(*, browser, base, database, storage_states, viewer_apis,
                         admin_api, build_id, output_dir, capability_id,
                         fixture_plugins=('ci.python', 'ci.node'), plugin_id='ci.python',
                         page_id='demo', slot_text='Native python ready',
                         logout=native_logout, slow_message=None, tenant_login=None, timeout_ms=20000):
    """Synchronous Playwright; injected APIs are genuine NativeClient instances.

    logout(page, shell) must perform the native UI logout and return only after its
    real logout response. slow_message, if supplied, must trigger a genuine >=3s
    fixture runtime delay. No callback may intercept/mock network responses.
    Returns PASS only if core, logout/in-flight, and real T2 relogin checks actually pass.
    Otherwise optional missing prerequisites produce PARTIAL, never PASS.
    """
    require_fixture(base, database, fixture_plugins, plugin_id)
    if set(storage_states) != {'admin', 'teacher'} or set(viewer_apis) != {'admin', 'teacher'}:
        raise ValueError('Both actual authenticated shells and matching API clients are required')
    verify_host_provenance(base, build_id, admin_api, viewer_apis, storage_states)
    from playwright.sync_api import expect
    output = Path(output_dir); output.mkdir(parents=True, exist_ok=True)
    report = dict(status='RUNNING', buildId=build_id, checks=[], shells={},
                  tenantSwitch='NOT_RUN: no same-context second-tenant login callback supplied',
                  scope='Real native sessions, genuine HTTP, production shells; no mock responses')
    def save(): (output/'ui-boundaries.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n')
    def checked(name, condition=True):
        if not condition: raise AssertionError(name)
        report['checks'].append(dict(name=name, status='PASS')); save()
    def enable(plugin):
        state = admin_api.data('/fcp/plugins/'+plugin)
        plan = admin_api.data('/fcp/plugins/'+plugin+'/plans', dict(action='ENABLE', expectedRevision=state['revision']))
        closure = exact_closure(plan, fixture_plugins)
        result = admin_api.data('/fcp/plans/'+plan['planId']+'/confirm', dict(digest=plan['digest'], affectedPlugins=closure, idempotencyKey=str(uuid.uuid4())))
        checked('restore explicit fixture '+plugin, result.get('phase') == 'COMPLETE')
    contexts = []; pages = {}; save()
    try:
        # Each shell gets an independent real session/context, never fabricated storage.
        for shell in ('admin', 'teacher'):
            context = browser.new_context(storage_state=storage_states[shell], viewport=dict(width=1440,height=960)); contexts.append(context)
            page = context.new_page(); pages[shell] = page
            errors = []; page.on('pageerror', lambda _error, errors=errors: errors.append('uncaught JavaScript error'))
            path = base+'/'+shell+'/fcp/plugins/'+plugin_id+'/'+page_id
            page.goto(path, wait_until='domcontentloaded')
            selected = page.get_by_test_id('plugin-page'); expect(selected).to_be_visible(timeout=timeout_ms)
            page.reload(wait_until='domcontentloaded'); expect(selected).to_be_visible(timeout=timeout_ms)
            checked(shell+' legitimate deep link survives full reload')
            expect(page.locator('[data-slot="dashboard.status"]')).to_contain_text(slot_text, timeout=timeout_ms)
            checked(shell+' registered slot renders actual package content')
            catalog = viewer_apis[shell].data('/fcp/ui/catalog')['catalog']
            checked(shell+' catalog includes target page', any(p['pluginId'] == plugin_id for p in catalog['pages']))
            titles = [p['title'] for p in catalog['pages'] if p['pluginId'] == plugin_id]
            selected.get_by_role('textbox', name='Message', exact=True).fill('unsaved-'+shell)
            original = admin_api.data('/fcp/plugins')['plugins']
            restore = [p['pluginId'] for p in original if p['pluginId'] in fixture_plugins and p.get('desired') == 'ENABLED']
            checked(shell+' fixture target initially enabled', plugin_id in restore)
            # Different browser context performs the real administrator plan/confirmation UI.
            manager_context = browser.new_context(storage_state=storage_states['admin']); contexts.append(manager_context)
            manager = manager_context.new_page(); manager.goto(base+'/admin/fcp/plugins', wait_until='domcontentloaded')
            manager.get_by_role('button', name='安装与权限管理', exact=True).click(timeout=timeout_ms)
            row = manager.locator('tbody tr').filter(has=manager.locator('td').filter(has_text=re.compile(r'^'+re.escape(plugin_id)+r' / ')))
            with manager.expect_response(lambda r: r.url.endswith('/fcp/plugins/'+plugin_id+'/plans'), timeout=timeout_ms) as planned:
                row.get_by_role('button', name='预览停用影响', exact=True).click()
            envelope = planned.value.json(); checked(shell+' real disable preview accepted', str(envelope.get('code')) == '200')
            closure = exact_closure(envelope['data'], fixture_plugins)
            panel = manager.get_by_test_id('plugin-plan')
            for plugin in closure: panel.get_by_label(plugin, exact=True).check()
            start = time.monotonic()
            with manager.expect_response(lambda r: '/fcp/plans/' in r.url and r.url.endswith('/confirm'), timeout=timeout_ms) as confirmed:
                panel.get_by_role('button', name='确认完整影响列表并执行', exact=True).click()
            reply = confirmed.value.json(); acknowledged = time.monotonic()
            checked(shell+' second browser disable completes', str(reply.get('code')) == '200' and reply.get('data',{}).get('phase') == 'COMPLETE')
            expect(selected).to_have_count(0, timeout=timeout_ms)
            for title in titles:
                expect(page.get_by_role('navigation',name='插件页面',exact=True).get_by_role('button',name=title,exact=True)).to_have_count(0,timeout=timeout_ms)
            expect(page.locator('[data-slot="dashboard.status"]')).not_to_contain_text(slot_text, timeout=timeout_ms)
            expect(page.get_by_role('status').filter(has_text='未保存')).to_be_visible(timeout=timeout_ms)
            observed = time.monotonic()
            report['shells'][shell] = dict(disableToConvergenceMs=round((observed-start)*1000,3), receiptToConvergenceMs=round((observed-acknowledged)*1000,3), logout='NOT_RUN', inFlight='NOT_RUN')
            checked(shell+' dirty page and slot removed with unsaved notification')
            status, denied = viewer_apis[shell].request('/fcp/capabilities/'+capability_id+'/invoke', dict(input=dict(message='unsaved-'+shell),expectedRevision=catalog['revision'],idempotencyKey=str(uuid.uuid4())))
            checked(shell+' actual stale submit rejected', is_rejected(status,denied))
            page.reload(wait_until='domcontentloaded')
            expect(page.get_by_text('该插件页面不存在、已停用或当前身份无权访问。',exact=True)).to_be_visible(timeout=timeout_ms)
            expect(selected).to_have_count(0)
            checked(shell+' disabled deep link cannot revive removed page')
            checked(shell+' no uncaught bootstrap/render errors', not errors)
            page.screenshot(path=str(output/(shell+'-disabled.png')),full_page=True)
            manager_context.close(); contexts.remove(manager_context)
            # Restore only originally enabled members; no grants/install/uninstall or arbitrary cascade.
            for plugin in restore: enable(plugin)
        # Do this LAST: native logout invalidates session tokens used by earlier API checks.
        for shell, page in pages.items():
            if logout is None or slow_message is None:
                report['shells'][shell]['logout'] = 'NOT_RUN: native logout callback and real slow runtime fixture required'
                report['shells'][shell]['inFlight'] = 'NOT_RUN: no fabricated delay or intercepted response'
                continue
            page.reload(wait_until='domcontentloaded')
            selected = page.get_by_test_id('plugin-page'); expect(selected).to_be_visible(timeout=timeout_ms)
            original_identity = viewer_apis[shell].data('/fcp/ui/catalog')['context']
            checked(shell+' starts in genuine T1 tenant', original_identity['tenantId'] == '000000')
            cached_marker = 't1-visible-'+shell+'-'+uuid.uuid4().hex
            selected.get_by_role('textbox', name='Message', exact=True).fill(cached_marker)
            with page.expect_response(lambda r: r.url.endswith('/fcp/capabilities/'+capability_id+'/invoke'), timeout=timeout_ms) as cached:
                selected.get_by_role('button', name='调用插件', exact=True).click()
            checked(shell+' genuine T1 result was cached visibly', str(cached.value.json().get('code')) == '200')
            expect(selected.locator('table')).to_contain_text(cached_marker,timeout=timeout_ms)
            marker = slow_message+'-'+shell+'-'+uuid.uuid4().hex
            selected.get_by_role('textbox', name='Message', exact=True).fill(marker)
            finished = []
            page.on('response', lambda r, finished=finished: finished.append(True) if r.url.endswith('/fcp/capabilities/'+capability_id+'/invoke') else None)
            with page.expect_request(lambda r: r.url.endswith('/fcp/capabilities/'+capability_id+'/invoke'), timeout=timeout_ms) as pending_request:
                selected.get_by_role('button', name='调用插件', exact=True).click()
            old_submission = pending_request.value.post_data_json
            page.wait_for_timeout(200)
            checked(shell+' actual capability request remains in flight before logout', not finished)
            logout(page,shell)
            expect(page.get_by_test_id('fcp-plugin-workspace')).to_have_count(0, timeout=timeout_ms)
            page.wait_for_timeout(3500)
            expect(page.get_by_test_id('plugin-page')).to_have_count(0)
            expect(page.locator('body')).not_to_contain_text(marker)
            status, body = viewer_apis[shell].request('/fcp/ui/catalog')
            checked(shell+' native logout invalidates old authenticated API session', is_rejected(status,body))
            expect(page.locator('body')).not_to_contain_text(cached_marker)
            report['shells'][shell].update(logout='PASS',inFlight='PASS: visible old-identity result did not reappear after actual in-flight request')
            if tenant_login is not None:
                original_context = page.context
                tenant_client = tenant_login(page,shell)
                checked(shell+' tenant relogin reuses same browser context', page.context is original_context)
                identity = tenant_client.data('/fcp/ui/catalog')
                checked(shell+' genuine T2 identity replaces T1', identity['context']['tenantId'] == '960001' and str(identity['context']['userId']) == '960601' and identity['context']['userId'] != original_identity['userId'])
                checked(shell+' T2 has no grant-derived target page', not any(p['pluginId'] == plugin_id for p in identity['catalog']['pages']))
                page.goto(base+'/'+shell+'/fcp/plugins/'+plugin_id+'/'+page_id,wait_until='domcontentloaded')
                expect(page.get_by_test_id('fcp-plugin-workspace')).to_be_visible(timeout=timeout_ms)
                checked(shell+' stays in its requested authorized shell', '/'+shell+'/fcp/plugins/' in page.url)
                expect(page.get_by_text('该插件页面不存在、已停用或当前身份无权访问。',exact=True)).to_be_visible(timeout=timeout_ms)
                expect(page.get_by_test_id('plugin-page')).to_have_count(0)
                expect(page.locator('[data-slot="dashboard.status"]')).not_to_contain_text(slot_text)
                expect(page.locator('body')).not_to_contain_text(cached_marker)
                expect(page.locator('body')).not_to_contain_text(marker)
                status, denied = tenant_client.request('/fcp/capabilities/'+capability_id+'/invoke',old_submission)
                checked(shell+' T2 rejects exact T1 input/key/revision submission',is_rejected(status,denied))
                report['shells'][shell]['tenantSwitch']='PASS: native logout and encrypted T2 relogin in same context; not an in-session tenant-switch control'
                page.screenshot(path=str(output/(shell+'-tenant2.png')),full_page=True)
            else:
                report['shells'][shell]['tenantSwitch']='NOT_RUN: no real second-tenant login callback'
        if tenant_login is not None:
            report['tenantSwitch']='PASS' if all(s.get('tenantSwitch','').startswith('PASS:') for s in report['shells'].values()) else 'NOT_RUN'

        if tenant_login is not None:
            report['loginPreparation'] = getattr(tenant_login, 'diagnostics', [])
        report['status'] = 'PASS' if all(s['logout']=='PASS' for s in report['shells'].values()) and report['tenantSwitch']=='PASS' else 'PARTIAL'
        save(); return report
    except Exception as error:
        # Never include request headers, storage state, native token or raw exception details.
        report.update(status='FAIL', failureType=type(error).__name__)
        if isinstance(error, NativeLoginPreparationError):
            report['loginFailure'] = error.diagnostic
        if tenant_login is not None:
            report['loginPreparation'] = getattr(tenant_login, 'diagnostics', [])
        save(); raise
    finally:
        for context in contexts: context.close()
