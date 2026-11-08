"""Real native education browser acceptance; imported by the isolated CI owner only.

No route interception, response substitution, fake login, token artifacts or DDL.
HTTP failure injection/UNKNOWN is NOT_RUN here; component doubles are separate.
"""
from __future__ import annotations

import hashlib
import json
import re
import subprocess
import sys
import uuid
from pathlib import Path
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools'))
PREFIX = '/fcp/education/foundation'


def verify_browser(education_fixture, base: str, build_id: str) -> dict:
    from playwright.sync_api import sync_playwright, expect
    from native_client import NativeClient, require_loopback
    from native_login import native_token
    from ci_ingestion import safe_diagnostic

    require_loopback(base)
    database = education_fixture.plan.database
    if not re.fullmatch(r'fcp_ci_[a-f0-9]{12}', database) or not education_fixture.plan.applied:
        raise ValueError('An applied current-run isolated education fixture is required')
    if not build_id:
        raise ValueError('Exact native build ID is required')
    host = education_fixture.host
    output = Path(education_fixture.output) / 'browser'
    output.mkdir(parents=True, exist_ok=False)
    report = dict(status='RUNNING', completed=False, buildId=build_id,
                  sourceCommit=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
                  sourceSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                  scope='REAL_NATIVE_ADMIN_FOUNDATION_SYNTHETIC_FIXTURES', checks=[], screenshots=[], requests=[],
                  unknownCommitFaultInjection='NOT_RUN', production='BLOCKED')
    stem = uuid.uuid4().hex[:16]
    org_code, class_code, cancelled_code, school_code = (name + stem for name in ('browser-org-', 'browser-class-', 'browser-cancel-', 'browser-school-'))

    def check(name, condition):
        if not condition:
            raise AssertionError(name)
        report['checks'].append(dict(name=name, status='PASS'))

    def sql(query):
        # Every call from this module is a bounded read of synthetic fixture rows.
        if not query.startswith('SELECT ') or ';' in query:
            raise ValueError('Read-only single SQL statement required')
        return education_fixture.sql(f'USE `{database}`; {query};').strip()

    def identifier(value):
        if not isinstance(value, str) or not re.fullmatch(r'(0|[1-9][0-9]{0,19})', value) or int(value) > 18446744073709551615:
            raise AssertionError('Native record ID is not an exact uint64 string')
        return value

    def row_count(table, code_column, code):
        if table not in ('ed_education_org_unit', 'ed_education_class') or code_column not in ('org_code', 'class_code') or not re.fullmatch(r'[a-z0-9-]+', code):
            raise ValueError('Unexpected fixture lookup')
        return sql(f"SELECT COUNT(*) FROM {table} WHERE tenant_id='000000' AND {code_column}='{code}'")

    def screenshot(page, name):
        # Login runs in a separate context owned by host.login; never photographed.
        check(name + ' screenshot is business page only', urlsplit(page.url).path == '/admin/fcp/education')
        path = output / (name + '.png')
        page.screenshot(path=str(path), full_page=True)
        report['screenshots'].append(dict(path=str(path.relative_to(ROOT)), sha256=hashlib.sha256(path.read_bytes()).hexdigest()))

    def is_response(response, path, method='GET'):
        return urlsplit(response.url).path == PREFIX + path and response.request.method == method

    def response_data(response, expected=200):
        body = response.json()
        check('actual HTTP and native envelope status ' + str(expected), response.status == expected and body.get('code') == expected)
        return body.get('data')

    def wait_idle(page):
        # A real native button is disabled for every read/write in useWorkspace.
        # Waiting for Vue to re-enable it also waits for the same render flush
        # that updates rows/options. HTTP response arrival alone is insufficient.
        expect(page.get_by_test_id('education-workspace').get_by_role(
            'button', name='刷新学校和权限', exact=True)).to_be_enabled(timeout=30000)

    def open_page(browser, role):
        state = host.login(browser, base, role)  # Real encryption and Redis CAPTCHA verification.
        client = NativeClient(base, native_token(state, base), 'e5cd7e4891bf95d1d19206ce24a7b32e')
        context = browser.new_context(storage_state=state, viewport=dict(width=1440, height=1080))
        page = context.new_page()
        errors = []
        page.on('pageerror', lambda error: errors.append(safe_diagnostic(str(error), host.diagnostic_secrets)))
        posts = []
        def capture(request):
            parsed = urlsplit(request.url)
            if request.method != 'POST' or not parsed.path.startswith(PREFIX + '/'):
                return
            body = request.post_data_json
            # Explicit allowlist: do not record headers, auth, URLs with credentials,
            # query values, unrelated APIs, storage state or arbitrary response bodies.
            allowed = {'intentId', 'parentId', 'orgCode', 'orgKind', 'name', 'validFrom', 'termId', 'classCode', 'gradeCode', 'expectedRowVersion'}
            if not isinstance(body, dict) or set(body) - allowed:
                raise AssertionError('Unexpected browser command fields')
            entry = dict(role=role, method='POST', path=parsed.path, body=body)
            posts.append(entry)
            report['requests'].append(entry)
        page.on('request', capture)
        with page.expect_response(lambda response: is_response(response, '/schools'), timeout=45000) as schools:
            page.goto(base.rstrip('/') + '/admin/fcp/education', wait_until='domcontentloaded')
        values = response_data(schools.value)['items']
        marker = page.get_by_test_id('education-foundation')
        expect(marker).to_be_visible(timeout=30000)
        expect(marker).to_have_attribute('data-build-id', build_id)
        expect(page.get_by_test_id('education-workspace')).to_be_visible()
        wait_idle(page)
        return context, page, client, values, posts, errors

    def choose_school(page, ident):
        wait_idle(page)
        page.get_by_test_id('education-school').click()
        with page.expect_response(lambda response: is_response(response, f'/schools/{ident}/organizations')) as listed:
            page.locator('.el-select-dropdown__item:visible').filter(has_text=re.compile(r'（' + ident + r'）$')).click()
        values = response_data(listed.value)
        wait_idle(page)
        expect(page.get_by_test_id('education-records')).to_be_visible()
        return values

    def find_row(page, ident):
        table = page.get_by_test_id('education-records')
        for _ in range(100):
            wait_idle(page)
            row = table.locator('tr').filter(has_text=re.compile(r'\b' + re.escape(ident) + r'\b'))
            if row.count() == 1:
                return row
            more = page.get_by_role('button', name='加载更多记录', exact=True)
            if not more.count():
                break
            with page.expect_response(lambda response: response.request.method == 'GET' and urlsplit(response.url).path.startswith(PREFIX + '/schools/') and 'afterId=' in response.url) as listed:
                more.click()
            response_data(listed.value)
        raise AssertionError('Created fixture record absent from all available list pages')

    def refresh_detail(page, kind, ident):
        with page.expect_response(lambda response: response.request.method == 'GET' and urlsplit(response.url).path.endswith('/' + kind)) as listed:
            page.get_by_role('button', name='刷新列表', exact=True).click()
        response_data(listed.value)
        with page.expect_response(lambda response: is_response(response, f'/{kind}/{ident}')) as detail:
            find_row(page, ident).get_by_role('button', name='查看', exact=True).click()
        value = response_data(detail.value)
        expect(page.get_by_test_id('education-detail')).to_contain_text(ident)
        return value

    def submit(page, route, duplicate=False, expected=200):
        form = page.get_by_test_id('education-form')
        button = form.get_by_role('button', name='保存', exact=True)
        with page.expect_response(lambda response: is_response(response, route, 'POST')) as saved:
            if duplicate:
                # Two actual DOM clicks in one turn exercise the synchronous UI guard;
                # no transport is mocked and the effect is verified independently in SQL.
                button.evaluate('(button) => { button.click(); button.click(); }')
            else:
                button.click()
        value = response_data(saved.value, expected)
        wait_idle(page)
        return value

    contexts = []
    try:
        with sync_playwright() as playwright:
            browser = playwright.chromium.launch()
            report['browserVersion'] = browser.version
            try:
                admin_context, admin_page, admin, schools, _, admin_errors = open_page(browser, 'admin')
                contexts.append(admin_context)
                check('tenant administrator sees exactly two original own-tenant schools', {item['id'] for item in schools} == {'800001', '800002'})
                choose_school(admin_page, '800001')
                choose_school(admin_page, '800002')
                screenshot(admin_page, 'admin-school-switch-list')
                for ident in ('800003',):
                    status, value = admin.request(PREFIX + f'/schools/{ident}/terms')
                    check('administrator cross-tenant read rejected', status in (403, 404) and value.get('code') in (403, 404))

                teacher_context, page, teacher, schools, posts, teacher_errors = open_page(browser, 'teacher')
                contexts.append(teacher_context)
                check('school administrator receives only explicitly assigned school', {item['id'] for item in schools} == {'800001'})
                choose_school(page, '800001')
                check('school administrator has no school creation button', page.get_by_role('button', name='新建学校', exact=True).count() == 0)
                status, value = teacher.request(PREFIX + '/schools/800002/terms')
                check('school administrator same-tenant cross-school read rejected', status in (403, 404) and value.get('code') in (403, 404))

                page.get_by_role('button', name='新建组织', exact=True).click()
                form = page.get_by_test_id('education-form')
                form.locator('input:visible').nth(0).fill('Cancelled synthetic organization')
                form.locator('input:visible').nth(1).fill(cancelled_code)
                before = len(posts)
                form.get_by_role('button', name='取消', exact=True).click()
                expect(form).to_have_count(0)
                check('cancel sends no command and creates no row', len(posts) == before and row_count('ed_education_org_unit', 'org_code', cancelled_code) == '0')

                page.get_by_role('button', name='新建组织', exact=True).click()
                form.locator('input:visible').nth(0).fill('Browser synthetic organization')
                form.locator('input:visible').nth(1).fill(org_code)
                form.get_by_placeholder('2026-11-08T00:00:00Z').fill('2026-01-02T00:00:00.123456Z')
                screenshot(page, 'school-admin-organization-form')
                before = len(posts)
                receipt = submit(page, '/schools/800001/organizations', duplicate=True)
                org = receipt['record']; org_id = identifier(org['id'])
                check('double click creates one committed organization and one HTTP command', receipt['operation']['outcome'] == 'COMMITTED' and len(posts) == before + 1 and row_count('ed_education_org_unit', 'org_code', org_code) == '1')
                check('organization ID and CAS version are strings', isinstance(org_id, str) and org['rowVersion'] == '0')
                detail = refresh_detail(page, 'organizations', org_id)
                check('organization detail preserves explicit UTC microseconds', detail['validFrom'].endswith('.123456Z'))
                page.get_by_role('button', name='仅修改名称', exact=True).click()
                form.locator('input:visible').fill('Browser renamed organization')
                renamed = submit(page, '/organizations/' + org_id + '/rename')['record']
                check('organization name-only CAS persisted', renamed['rowVersion'] == '1' and sql(f"SELECT CONCAT(name,'|',row_version) FROM ed_education_org_unit WHERE tenant_id='000000' AND id={org_id}") == 'Browser renamed organization|1')

                # A second real authenticated request changes the version while the
                # browser retains its already-open form; no synthetic response is used.
                page.get_by_role('button', name='仅修改名称', exact=True).click()
                form.locator('input:visible').fill('Stale browser name must not commit')
                concurrent = teacher.data(PREFIX + '/organizations/' + org_id + '/rename', dict(intentId='browser-cas-' + uuid.uuid4().hex, expectedRowVersion='1', name='Concurrent synthetic rename'))
                check('real concurrent command advances version', concurrent['record']['rowVersion'] == '2')
                submit(page, '/organizations/' + org_id + '/rename', expected=409)
                expect(form).to_have_count(0)
                expect(page.get_by_text('记录版本或请求内容已冲突。请刷新并重新打开详情，核对后再修改。', exact=True)).to_be_visible()
                check('stale browser CAS did not overwrite database', sql(f"SELECT CONCAT(name,'|',row_version) FROM ed_education_org_unit WHERE tenant_id='000000' AND id={org_id}") == 'Concurrent synthetic rename|2')
                refresh_detail(page, 'organizations', org_id)
                screenshot(page, 'school-admin-organization-list-detail')

                page.locator('.el-radio-button').filter(has_text=re.compile(r'^班级$')).click()
                wait_idle(page)
                page.get_by_test_id('education-term').click()
                with page.expect_response(lambda response: is_response(response, '/schools/800001/classes')) as listed:
                    page.locator('.el-select-dropdown__item:visible').filter(has_text='T2026').click()
                response_data(listed.value)  # Existing HTTP/JDBC-created classes are permitted.
                page.get_by_role('button', name='新建班级', exact=True).click()
                inputs = form.locator('input:visible')
                inputs.nth(0).fill('Browser synthetic class'); inputs.nth(1).fill(class_code); inputs.nth(2).fill('G1')
                screenshot(page, 'school-admin-class-form')
                before = len(posts)
                receipt = submit(page, '/schools/800001/classes', duplicate=True)
                klass = receipt['record']; class_id = identifier(klass['id'])
                check('double click creates exactly one class', receipt['operation']['outcome'] == 'COMMITTED' and len(posts) == before + 1 and row_count('ed_education_class', 'class_code', class_code) == '1')
                detail = refresh_detail(page, 'classes', class_id)
                check('class belongs to selected school and term', detail['schoolId'] == '800001' and detail['termId'] == '810001')
                page.get_by_role('button', name='仅修改名称', exact=True).click()
                form.locator('input:visible').fill('Browser renamed class')
                renamed = submit(page, '/classes/' + class_id + '/rename')['record']
                check('class name-only CAS persisted', renamed['rowVersion'] == '1' and sql(f"SELECT CONCAT(name,'|',row_version) FROM ed_education_class WHERE tenant_id='000000' AND id={class_id}") == 'Browser renamed class|1')
                with page.expect_response(lambda response: is_response(response, '/schools')):
                    page.reload()
                choose_school(page, '800001')
                page.locator('.el-radio-button').filter(has_text=re.compile(r'^班级$')).click()
                wait_idle(page)
                page.get_by_test_id('education-term').click()
                with page.expect_response(lambda response: is_response(response, '/schools/800001/classes')):
                    page.locator('.el-select-dropdown__item:visible').filter(has_text='T2026').click()
                detail = refresh_detail(page, 'classes', class_id)
                check('full browser reload reads persisted class', detail['name'] == 'Browser renamed class' and detail['rowVersion'] == '1')
                screenshot(page, 'school-admin-class-persisted')
                for name in ('删除', '停用', '修改状态', '修改学期状态'):
                    check('no unsupported UI action ' + name, page.get_by_role('button', name=name, exact=True).count() == 0)
                check('no browser command attempts status or scope rebinding', all(not (set(item['body']) & {'status', 'tenantId', 'tenant', 'actor', 'epoch', 'schoolId', 'grant'}) for item in posts))

                viewer_context, viewer_page, viewer, schools, viewer_posts, viewer_errors = open_page(browser, 'viewer')
                contexts.append(viewer_context)
                check('readonly viewer sees only assigned school', {item['id'] for item in schools} == {'800001'})
                choose_school(viewer_page, '800001')
                refresh_detail(viewer_page, 'organizations', org_id)
                for name in ('新建学校', '新建组织', '新建班级', '仅修改名称', '保存', '删除'):
                    check('readonly viewer has no write control ' + name, viewer_page.get_by_role('button', name=name, exact=True).count() == 0)
                check('readonly browser sends no command', not viewer_posts)
                screenshot(viewer_page, 'readonly-organization-detail')

                # Last: do not change the original two-school expectation before HTTP
                # and browser role-scope checks. No deployment grant is auto-created.
                admin_page.get_by_role('button', name='新建学校', exact=True).click()
                admin_form = admin_page.get_by_test_id('education-form')
                admin_form.locator('input:visible').nth(0).fill('Browser pending synthetic school')
                admin_form.locator('input:visible').nth(1).fill(school_code)
                admin_form.get_by_placeholder('2026-11-08T00:00:00Z').fill('2026-01-02T00:00:00Z')
                receipt = submit(admin_page, '/schools')
                school_id = identifier(receipt['record']['id'])
                check('new school commits as waiting for deployment permission', receipt.get('activation') == dict(status='EXECUTION_GRANT_REQUIRED', schoolId=school_id) and row_count('ed_education_org_unit', 'org_code', school_code) == '1')
                check('school creation does not self-grant execution authority', sql(f"SELECT COUNT(*) FROM ed_core_execution_grant WHERE tenant_id='000000' AND scope_key='school:{school_id}'") == '0')
                with admin_page.expect_response(lambda response: is_response(response, '/schools')) as refreshed:
                    admin_page.get_by_role('button', name='刷新学校和权限', exact=True).click()
                created = next(item for item in response_data(refreshed.value)['items'] if item['id'] == school_id)
                wait_idle(admin_page)
                check('pending school has closed per-school operations', created.get('readiness') == 'EXECUTION_GRANT_MISSING' and not any(created.get('operations', {}).values()))
                admin_page.get_by_test_id('education-school').click()
                admin_page.locator('.el-select-dropdown__item:visible').filter(has_text=re.compile(r'（' + school_id + r'）$')).click()
                expect(admin_page.get_by_text('学校已创建，等待部署许可后可管理基础资料。', exact=True)).to_be_visible()
                check('pending school has no child creation controls', admin_page.get_by_role('button', name='新建组织', exact=True).count() == 0 and admin_page.get_by_role('button', name='新建班级', exact=True).count() == 0)
                screenshot(admin_page, 'admin-school-awaiting-activation')
                check('all role business pages have no uncaught JavaScript errors', not (admin_errors or teacher_errors or viewer_errors))
                report.update(status='PASS', organizationId=org_id, classId=class_id, pendingSchoolId=school_id)
            finally:
                for context in contexts:
                    context.close()
                browser.close()
    except BaseException as failure:
        report.update(status='FAIL', errorType=type(failure).__name__, error=safe_diagnostic(str(failure), host.diagnostic_secrets))
        raise
    finally:
        report.update(completed=True, totalChecks=len(report['checks']))
        (output / 'result.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    return report
