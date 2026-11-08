"""Real native roster read-only browser checks for the isolated CI owner.

Execution requires the current-run roster fixture, real native login and HTTP.
No route mocks, token fabrication, DDL, direct business writes or browser retries.
The source is a runner, not evidence of a pass. Local Chromium execution is NOT_RUN.
"""
from __future__ import annotations

import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools'))
PREFIX = '/fcp/education/roster'
PAGE = '/admin/fcp/roster'
KINDS = {'enrollments': '学籍关系', 'memberships': '入班历史', 'assignments': '任教关系'}
FORBIDDEN = {'personId', 'displayName', 'studentCode', 'subjectCode', 'changeReason', 'privateRef', 'accountId', 'guardianId'}
COMMON = {'id', 'schoolId', 'validFrom', 'validTo', 'createdAt', 'rowVersion', 'personDisplay'}
FIELDS = {
    'enrollments': COMMON | {'admissionKey', 'status'},
    'memberships': COMMON | {'enrollmentId', 'classId', 'className', 'termId', 'termCode', 'termStatus'},
    'assignments': COMMON | {'classId', 'className', 'termId', 'termCode', 'termStatus', 'roleCode'},
}


def wait_stable_layout(page):
    return page.evaluate("""async () => {
      const start = performance.now(); let previous = '', stable = 0;
      return await new Promise((resolve, reject) => {
        const sample = () => {
          const nodes = ['.main-container','.sidebar-container','.app-main','[data-testid="education-roster"]']
            .map(s => document.querySelector(s)).filter(Boolean);
          const values = [innerWidth, innerHeight, scrollX, scrollY];
          for (const node of nodes) { const r = node.getBoundingClientRect(); values.push(r.x,r.y,r.width,r.height,node.scrollLeft); }
          const snapshot = JSON.stringify(values);
          const moving = nodes.some(n => n.getAnimations().some(a => a.playState === 'running' && a.effect?.getTiming().iterations !== Infinity));
          stable = !moving && snapshot === previous ? stable + 1 : 0; previous = snapshot;
          if (stable >= 4) return resolve(values);
          if (performance.now() - start > 10000) return reject(new Error('Roster layout did not stabilize'));
          requestAnimationFrame(sample);
        }; requestAnimationFrame(sample);
      });
    }""")


def _write_report(report: dict, output: Path) -> None:
    """Finalize the shared return/disk contract on success and failure alike."""
    report['totalChecks'] = len(report['checks'])
    (output / 'acceptance.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def verify_browser(fixture, base: str, build_id: str) -> dict:
    from playwright.sync_api import sync_playwright, expect
    from native_client import require_loopback
    from ci_ingestion import safe_diagnostic

    require_loopback(base)
    if not re.fullmatch(r'fcp_ci_[a-f0-9]{12}', fixture.plan.database) or not fixture.plan.applied:
        raise ValueError('Applied current-run isolated roster fixture required')
    if not build_id:
        raise ValueError('Exact build ID required')
    host = fixture.host
    output = Path(fixture.output) / 'browser'
    output.mkdir(parents=True, exist_ok=False)
    report = dict(status='RUNNING', completed=False, buildId=build_id,
                  sourceCommit=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
                  sourceSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                  scope='REAL_NATIVE_ADMIN_ROSTER_METADATA_SYNTHETIC_FIXTURES',
                  checks=[], screenshots=[], requests=[], writes='NOT_OPEN', personDisplay='PENDING',
                  unknownFaultInjection='NOT_RUN', production='BLOCKED')

    def check(name, condition):
        if not condition:
            raise AssertionError(name)
        report['checks'].append(dict(name=name, status='PASS'))

    def matches(response, path):
        return urlsplit(response.url).path == PREFIX + path and response.request.method == 'GET'

    def data(response):
        body = response.json()
        check('actual roster HTTP and envelope succeed', response.status == 200 and body.get('code') == 200)
        return body['data']

    def safe_row(row, kind, sid):
        check(kind + ' exact response whitelist', set(row) == FIELDS[kind] and not FORBIDDEN.intersection(row))
        check(kind + ' school is exact selected scope', row['schoolId'] == sid)
        for field in ('id', 'schoolId', 'rowVersion'):
            check(kind + ' exact decimal string ' + field, isinstance(row[field], str) and
                  bool(re.fullmatch(r'(0|[1-9][0-9]{0,19})', row[field])) and int(row[field]) <= 18446744073709551615)
        check(kind + ' pending disclosure is explicit', row['personDisplay'] == dict(status='PENDING', reason='PERSON_DISPLAY_POLICY_PENDING'))
        for field in ('validFrom', 'createdAt'):
            check(kind + ' UTC precision ' + field, bool(re.fullmatch(r'\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?Z', row[field])))

    def metrics(page):
        return page.evaluate("""() => {
          const rect = n => { if (!n) return null; const r = n.getBoundingClientRect(), s = getComputedStyle(n);
            return {x:r.x,y:r.y,width:r.width,height:r.height,right:r.right,scrollLeft:n.scrollLeft,
              scrollWidth:n.scrollWidth,clientWidth:n.clientWidth,display:s.display,visibility:s.visibility}; };
          const root = document.querySelector('[data-testid="education-roster"]');
          return {scrollX,scrollY,innerWidth,innerHeight,document:rect(document.documentElement),
            sidebar:rect(document.querySelector('.sidebar-container')), heading:rect(root?.querySelector('h1')),
            labels:Array.from(root?.querySelectorAll('.el-form-item__label') || []).map(rect)};
        }""")

    def geometry(m, label):
        check(label + ' no page horizontal overflow', abs(m['scrollX']) <= 1 and m['document']['scrollWidth'] <= m['document']['clientWidth'] + 1)
        sidebar = m['sidebar']
        if sidebar and sidebar['display'] != 'none' and sidebar['visibility'] != 'hidden' and sidebar['right'] > 1:
            check(label + ' heading and labels are clear of native sidebar', all(r and r['x'] >= sidebar['right'] - 1 for r in [m['heading'], *m['labels']]))

    def screenshot(page, name):
        check(name + ' business page only', urlsplit(page.url).path == PAGE)
        page.keyboard.press('Escape')
        expect(page.locator('.el-select-dropdown:visible')).to_have_count(0)
        wait_stable_layout(page)
        original = metrics(page)
        check(name + ' fixed viewport', page.viewport_size == dict(width=1440, height=1080))
        bottom = page.evaluate('Math.max(document.documentElement.scrollHeight,document.body.scrollHeight)-innerHeight')
        positions = [('top', 0)] + ([('bottom', bottom)] if bottom > 1 else [])
        try:
            for position, y in positions:
                page.evaluate('(y) => window.scrollTo({left:0,top:y,behavior:"instant"})', y)
                wait_stable_layout(page)
                before = metrics(page)
                geometry(before, name + '-' + position)
                path = output / (name + '-' + position + '.png')
                page.screenshot(path=str(path), full_page=False)
                immediate = metrics(page)
                wait_stable_layout(page)
                after = metrics(page)
                check(name + ' viewport preserved after capture', before['innerWidth'] == immediate['innerWidth'] == after['innerWidth'] == 1440 and before['innerHeight'] == immediate['innerHeight'] == after['innerHeight'] == 1080)
                geometry(immediate, name + '-' + position + '-immediate')
                geometry(after, name + '-' + position + '-after')
                report['screenshots'].append(dict(path=str(path.relative_to(ROOT)), sha256=hashlib.sha256(path.read_bytes()).hexdigest(), capture='FIXED_VIEWPORT', position=position, scrollY=before['scrollY']))
                report.setdefault('layoutDiagnostics', []).append(dict(name=name, position=position, before=before, immediate=immediate, after=after))
        finally:
            page.evaluate('(p) => window.scrollTo({left:p.scrollX,top:p.scrollY,behavior:"instant"})', original)
            wait_stable_layout(page)

    def idle(page):
        expect(page.get_by_test_id('roster-refresh')).to_be_enabled(timeout=30000)

    def select_school(page, school, expected_kind=None):
        select = page.get_by_test_id('roster-school')
        select.click()
        option = page.locator('.el-select-dropdown:visible').get_by_role('option', name=school['name'] + '（' + school['id'] + '）', exact=True)
        if expected_kind:
            with page.expect_response(lambda r: matches(r, '/schools/' + school['id'] + '/' + expected_kind)) as observed:
                option.click()
            result = data(observed.value)
        else:
            option.click()
            result = None
        idle(page)
        expect(page.get_by_test_id('roster-detail')).to_have_count(0)
        return result

    try:
        with sync_playwright() as playwright:
            browser = playwright.chromium.launch(headless=True)
            try:
                # Historical host login names are NOT interpreted as application permissions.
                # The dedicated fixture explicitly maps these accounts to independent grants.
                role_kinds = {'admin': list(KINDS), 'teacher': ['enrollments', 'memberships'], 'viewer': ['assignments']}
                for role, allowed_kinds in role_kinds.items():
                    state = host.login(browser, base, role)
                    context = browser.new_context(storage_state=state, viewport=dict(width=1440, height=1080))
                    page = context.new_page()
                    errors, mutations = [], []
                    page.on('pageerror', lambda error: errors.append(safe_diagnostic(str(error), host.diagnostic_secrets)))
                    def capture(request):
                        parsed = urlsplit(request.url)
                        if parsed.path.startswith(PREFIX):
                            report['requests'].append(dict(role=role, path=parsed.path, method=request.method))
                            if request.method != 'GET':
                                mutations.append(parsed.path)
                    page.on('request', capture)
                    try:
                        with page.expect_response(lambda r: matches(r, '/capabilities')) as cap_response, page.expect_response(lambda r: matches(r, '/schools')) as school_response:
                            page.goto(base + PAGE)
                        capabilities = data(cap_response.value)
                        schools = data(school_response.value)['items']
                        check(role + ' metadata ready but person policy pending', capabilities['status'] == 'READY' and capabilities['readiness']['personDisplayPolicy'] == 'PENDING')
                        expect(page.get_by_test_id('education-roster')).to_have_attribute('data-build-id', build_id)
                        expect(page.get_by_test_id('roster-person-policy')).to_contain_text('关联信息尚未开放')
                        idle(page)
                        if role == 'admin':
                            check('admin has exactly two ready fixture schools', {s['id'] for s in schools if s['readiness'] == 'READY'} == {'800001', '800002'})
                            check('admin never gets other tenant school', all(s['id'] != '800003' for s in schools))
                        else:
                            check(role + ' has only school A', [s['id'] for s in schools] == ['800001'])
                        school_a = next(s for s in schools if s['id'] == '800001')
                        current_kind = 'enrollments'
                        listed = select_school(page, school_a, current_kind if current_kind in allowed_kinds else None)
                        for kind in KINDS:
                            if current_kind != kind:
                                if kind in allowed_kinds:
                                    with page.expect_response(lambda r, k=kind: matches(r, '/schools/800001/' + k)) as response:
                                        page.get_by_role('tab', name=KINDS[kind], exact=True).click()
                                    listed = data(response.value)
                                else:
                                    page.get_by_role('tab', name=KINDS[kind], exact=True).click()
                                current_kind = kind
                            idle(page)
                            if kind not in allowed_kinds:
                                expect(page.get_by_test_id('roster-tab-denied')).to_be_visible()
                                expect(page.get_by_test_id('roster-records')).to_have_count(0)
                                continue
                            check(role + ' ' + kind + ' three same-school rows', len(listed['items']) == 3)
                            for item in listed['items']:
                                safe_row(item, kind, '800001')
                            first = listed['items'][0]
                            # Exact first cell, no word-boundary regex on adjacent table cells.
                            exact = page.get_by_test_id('roster-records').locator('tbody tr').filter(has=page.locator('td:first-child .cell').filter(has_text=re.compile(r'^\s*' + re.escape(first['id']) + r'\s*$')))
                            expect(exact).to_have_count(1)
                            with page.expect_response(lambda r, k=kind, rid=first['id']: matches(r, '/schools/800001/' + k + '/' + rid)) as detail_response:
                                exact.get_by_role('button', name='查看', exact=True).click()
                            safe_row(data(detail_response.value), kind, '800001')
                            expect(page.get_by_test_id('roster-detail')).to_contain_text(first['id'])
                            screenshot(page, role + '-' + kind)
                            page.get_by_role('button', name='关闭详情', exact=True).click()
                            expect(page.get_by_test_id('roster-detail')).to_have_count(0)
                        if role == 'admin':
                            school_b = next(s for s in schools if s['id'] == '800002')
                            result = select_school(page, school_b, current_kind)
                            check('admin school switch replaces records', len(result['items']) == 1)
                            safe_row(result['items'][0], current_kind, '800002')
                            screenshot(page, 'admin-school-b')
                            pending = [s for s in schools if s['readiness'] == 'EXECUTION_GRANT_MISSING']
                            for school_pending in pending:
                                select_school(page, school_pending)
                                expect(page.get_by_text('该学校的执行许可尚未就绪，查询保持关闭。', exact=True)).to_be_visible()
                                expect(page.get_by_test_id('roster-records')).to_have_count(0)
                        check(role + ' no browser errors', not errors)
                        check(role + ' read-only page made no mutation requests', not mutations)
                    finally:
                        context.close()
            finally:
                browser.close()
        report.update(status='PASS', completed=True)
    except Exception as error:
        report.update(status='FAIL', error=safe_diagnostic(str(error), host.diagnostic_secrets))
        raise
    finally:
        _write_report(report, output)
    return report
