"""Read-only browser probe for an already genuinely logged-in disposable native host.

Call from the native acceptance fixture after real login and permission setup.
No token fabrication, route interception, backend replacement or state persistence.
This checks the BLOCKED foundation shell only, not business CRUD acceptance.
"""
from urllib.parse import urlparse


def verify_foundation_page(page, base_url: str, expected_build_id: str):
    parsed = urlparse(base_url)
    if parsed.scheme not in ('http', 'https') or parsed.hostname not in ('127.0.0.1', 'localhost', '::1'):
        raise ValueError('Disposable loopback native host required')
    if not expected_build_id:
        raise ValueError('Expected native build ID required')
    from playwright.sync_api import expect
    target = base_url.rstrip('/') + '/admin/fcp/education'
    with page.expect_response(lambda response: response.url.split('?')[0].endswith('/fcp/education/foundation/capabilities')) as response:
        page.goto(target)
    envelope = response.value.json()
    assert response.value.status == 200 and envelope.get('code') == 200
    data = envelope['data']
    assert data['schemaVersion'] == 'education-foundation-v1' and data['status'] == 'BLOCKED'
    assert data['operations'] and all(value is False for value in data['operations'].values())
    shell = page.get_by_test_id('education-foundation')
    expect(shell).to_be_visible()
    expect(shell).to_have_attribute('data-build-id', expected_build_id)
    expect(shell.get_by_text('基础资料尚未开放', exact=True)).to_be_visible()
    expect(shell.locator('input')).to_have_count(0)
    expect(shell.locator('button')).to_have_count(1)
    with page.expect_response(lambda response: response.url.split('?')[0].endswith('/fcp/education/foundation/capabilities')):
        shell.get_by_test_id('education-refresh').click()
    expect(shell.get_by_text('基础资料尚未开放', exact=True)).to_be_visible()
    page.reload()
    expect(shell.get_by_text('基础资料尚未开放', exact=True)).to_be_visible()
    return {'status': 'PASS', 'scope': 'blocked-foundation-shell-only', 'nativeBuildId': expected_build_id}


def verify_organization_roundtrip(page, base_url: str, expected_build_id: str, school_id: str):
    """Write probe for a disposable, explicitly authorized school fixture only.

    Caller owns the empty test DB, real login, school grant and execution grant.
    Creates one organization, reads detail, renames with CAS, and verifies reload.
    Never use an existing user/production school. Rows are retained for evidence.
    """
    from datetime import datetime, timezone
    from uuid import uuid4
    from playwright.sync_api import expect
    parsed = urlparse(base_url)
    if parsed.scheme not in ('http', 'https') or parsed.hostname not in ('127.0.0.1', 'localhost', '::1'):
        raise ValueError('Disposable loopback native host required')
    if not expected_build_id or not school_id.isascii() or not school_id.isdecimal():
        raise ValueError('Explicit build and fixture school required')
    suffix = uuid4().hex
    name, renamed = '浏览器验收组织-' + suffix[:8], '浏览器验收更名-' + suffix[:8]
    page.goto(base_url.rstrip('/') + '/admin/fcp/education')
    shell = page.get_by_test_id('education-foundation')
    expect(shell).to_have_attribute('data-build-id', expected_build_id)
    page.get_by_test_id('education-school').click()
    page.locator('.el-select-dropdown__item').filter(has_text='（' + school_id + '）').click()
    expect(page.get_by_test_id('education-records')).to_be_visible()
    page.get_by_role('button', name='新建组织', exact=True).click()
    form = page.get_by_test_id('education-form')
    form.locator('input').nth(0).fill(name)
    form.locator('input').nth(1).fill('browser-' + suffix)
    form.get_by_placeholder('2026-11-08T00:00:00Z').fill(datetime.now(timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ'))
    with page.expect_response(lambda r: r.request.method == 'POST' and r.url.endswith('/organizations')) as created:
        form.get_by_role('button', name='保存', exact=True).click()
    payload = created.value.json()
    assert created.value.status == 200 and payload.get('code') == 200
    assert payload['data']['operation']['outcome'] == 'COMMITTED'
    record = payload['data']['record']
    assert isinstance(record['id'], str) and isinstance(record['rowVersion'], str)
    expect(page.get_by_test_id('education-detail')).to_contain_text(record['id'])
    page.get_by_role('button', name='仅修改名称', exact=True).click()
    form.locator('input').first.fill(renamed)
    with page.expect_response(lambda r: r.request.method == 'POST' and r.url.endswith('/rename')) as changed:
        form.get_by_role('button', name='保存', exact=True).click()
    receipt = changed.value.json()
    assert changed.value.status == 200 and receipt.get('code') == 200
    assert receipt['data']['record']['name'] == renamed
    expect(page.get_by_test_id('education-detail')).to_contain_text(renamed)
    page.get_by_role('button', name='刷新列表', exact=True).click()
    # This fixture must be small enough to include its newly created row on page one.
    row = page.get_by_test_id('education-records').locator('tr').filter(has_text=record['id'])
    expect(row).to_contain_text(renamed)
    row.get_by_role('button', name='查看', exact=True).click()
    expect(page.get_by_test_id('education-detail')).to_contain_text(renamed)
    assert page.get_by_role('button', name='删除', exact=True).count() == 0
    return {'status': 'PASS', 'scope': 'native-organization-create-detail-rename', 'nativeBuildId': expected_build_id, 'recordId': record['id']}
