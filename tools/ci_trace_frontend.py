"""Real response-to-clipboard trace checks in the existing disposable native CI.

Uses genuine saved native login and UI actions only. No intercepted network, test
response, exposed application hook, new token, database write or business mutation.
This covers the shared ingestion fetch client in both compiled native shells; it
must not be described as a real-browser exercise of every native Axios error path.
"""
from __future__ import annotations
import os
from pathlib import Path
import re
from urllib.parse import parse_qs, urlsplit


def verify(fixture, browser, base: str, admin_state, build_id: str) -> None:
    origin = urlsplit(base)
    if (os.environ.get('GITHUB_ACTIONS') != 'true'
            or not re.fullmatch(r'fcp_ci_[a-f0-9]{12}', fixture.database)
            or origin.scheme != 'http' or origin.hostname != '127.0.0.1'
            or origin.username or origin.password or origin.query or origin.fragment
            or origin.path not in ('', '/')):
        raise RuntimeError('Trace browser checks require the disposable native loopback CI')
    from playwright.sync_api import expect
    report = dict(status='RUNNING', buildId=build_id,
                  scope='Real native login, backend and both compiled ingestion fetch clients; no network doubles',
                  nativeAxiosBrowserCoverage='NOT_RUN by this check', shells=[])
    fixture.report['traceFrontend'] = report

    def rejected_read(response):
        url = urlsplit(response.url)
        return (url.scheme == origin.scheme and url.netloc == origin.netloc
                and url.path == '/fcp/ingestion/data/roster'
                and parse_qs(url.query).get('classId') == ['!']
                and response.request.method == 'GET')

    try:
        # Native admin is explicitly allowed in both shells. Do not broaden a
        # teacher's grants, change identity or rewrite the teacher's option list.
        for shell in ('admin', 'teacher'):
            context = browser.new_context(storage_state=admin_state,
                                          viewport=dict(width=1440, height=960))
            context.grant_permissions(['clipboard-read', 'clipboard-write'], origin=base)
            page = context.new_page()
            exceptions = []
            page.on('pageerror', lambda error: exceptions.append(type(error).__name__))
            try:
                page.goto(base + '/' + shell + '/fcp/ingestion', wait_until='domcontentloaded')
                marker = page.get_by_test_id('fcp-ingestion')
                expect(marker).to_be_visible(timeout=30000)
                expect(marker).to_have_attribute('aria-busy', 'false')
                fixture.check(shell + ' trace uses the actual compiled native build',
                              marker.get_attribute('data-build-id') == build_id
                              and marker.get_attribute('data-shell') == shell)
                page.get_by_test_id('ingestion-entity').select_option('roster')
                expect(marker).to_have_attribute('aria-busy', 'false')
                class_input = page.get_by_test_id('ingestion-class')
                fixture.check(shell + ' trace uses authorized native allClasses UI',
                              class_input.evaluate('(element) => element.tagName') == 'INPUT')
                ids = []
                for attempt in range(2):
                    with page.expect_response(rejected_read, timeout=20000) as received:
                        if attempt == 0:
                            class_input.fill('!')
                            class_input.press('Tab')
                        else:
                            page.get_by_role('button', name='刷新状态', exact=True).click()
                    response = received.value
                    payload = response.json()
                    trace_id = response.header_value('X-Trace-Id') or ''
                    fixture.check(shell + f' trace read {attempt + 1} is a real backend rejection',
                                  response.status == 400 and payload.get('code') == 400
                                  and payload.get('data', {}).get('fcpCode') == 'INVALID_INPUT')
                    fixture.check(shell + f' trace read {attempt + 1} has a valid server ID',
                                  bool(re.fullmatch(r'[0-9a-f]{32}', trace_id)) and trace_id != '0' * 32)
                    expect(marker).to_have_attribute('aria-busy', 'false')
                    notice = page.get_by_test_id('request-trace')
                    expect(notice).to_have_count(1)
                    expect(notice).to_be_visible()
                    expect(notice.locator('code')).to_have_text(trace_id)
                    expect(page.get_by_test_id('ingestion-error')).to_contain_text(trace_id)
                    fixture.check(shell + f' trace read {attempt + 1} retains exactly the response ID', True)
                    if ids:
                        fixture.check(shell + ' independent read errors do not reuse a trace', trace_id != ids[-1])
                    ids.append(trace_id)
                    notice.get_by_role('button', name='复制 Trace ID', exact=True).click()
                    expect(notice.get_by_role('button', name='已复制', exact=True)).to_be_visible()
                    copied = page.evaluate('() => navigator.clipboard.readText()')
                    fixture.check(shell + f' trace read {attempt + 1} copies only its server ID', copied == trace_id)
                screenshot = Path(fixture.output) / ('trace-' + shell + '.png')
                page.screenshot(path=str(screenshot), full_page=True)
                notice.get_by_role('button', name='关闭', exact=True).click()
                expect(page.get_by_test_id('request-trace')).to_have_count(0)
                fixture.check(shell + ' trace notice is dismissible without a page exception', not exceptions)
                report['shells'].append(dict(shell=shell, status='PASS', traceIds=ids,
                                             screenshot=screenshot.name, requestMethod='GET'))
            finally:
                context.close()
        report['status'] = 'PASS'
    except Exception as failure:
        # Do not retain browser state, headers, tokens or full request diagnostics.
        report.update(status='FAIL', errorType=type(failure).__name__)
        raise
