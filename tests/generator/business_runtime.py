"""Real browser CRUD of the packaged native generator output; no intercepted responses."""
from pathlib import Path
import hashlib
import json
import re
import urllib.error
import urllib.parse
import urllib.request

from native_client import NativeClient, NoRedirect, require_loopback
from native_login import native_token

API = '/generatorprobe/probe'
PAGE = '/admin/tool/probe'
MANUAL_TITLE = '手工调整已保留：合成业务验收'


def settled(page):
    from playwright.sync_api import expect
    expect(page.locator('.el-loading-mask:visible')).to_have_count(0, timeout=20000)
    expect(page.locator('.el-message-box__wrapper:visible')).to_have_count(0)
    expect(page.locator('.el-message--error:visible, .el-notification--error:visible')).to_have_count(0)
    page.evaluate('document.fonts.ready')
    page.wait_for_function("document.getAnimations().every(a => a.playState !== 'running' || a.effect?.getTiming().iterations === Infinity)")


def native_request(base, token, path, method='GET', payload=None):
    require_loopback(base)
    parsed = urllib.parse.urlsplit(path)
    if parsed.path != '/system/menu/getRouters' and parsed.path != API and not parsed.path.startswith(API+'/'):
        raise ValueError('Only fixed generated business/native menu acceptance paths are allowed')
    if parsed.netloc or parsed.scheme or any(c in path for c in ('\r','\n','#','\\')):
        raise ValueError('Unsafe native business request')
    headers = {'Content-Type':'application/json','clientid':'e5cd7e4891bf95d1d19206ce24a7b32e'}
    if token: headers['Authorization'] = 'Bearer '+token
    request = urllib.request.Request(base+path, method=method, headers=headers, data=None if payload is None else json.dumps(payload).encode())
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}),NoRedirect())
    try:
        with opener.open(request,timeout=30) as response: status, raw = response.status, response.read(2*1024*1024)
    except urllib.error.HTTPError as error: status, raw = error.code,error.read(2*1024*1024)
    return status,json.loads(raw)


def verify_business(generator, base, build):
    from playwright.sync_api import sync_playwright, expect
    fixture, report, output, check = generator.fixture, generator.report, generator.output, generator.check
    report['generatedBusinessRuntime'] = 'RUNNING'
    report['generatedBusinessBuild'] = build
    jar = Path(build['jarPath'])
    check('running generated JAR digest is bound to the packaging result', hashlib.sha256(jar.read_bytes()).hexdigest() == build['jarSha256'])
    sql = lambda query: fixture.sql(f'USE `{fixture.database}`; '+query).strip()
    with sync_playwright() as p:
        browser = p.chromium.launch()
        contexts = []
        try:
            state = fixture.login(browser, base, 'admin')
            token = native_token(state,base)
            evidence = NativeClient(base,token,'e5cd7e4891bf95d1d19206ce24a7b32e').evidence()
            check('generated host retains native identity, database and baseline build binding', evidence['buildId'] == build['baselineBuildId'])
            status, routers = native_request(base,token,'/system/menu/getRouters')
            def has_route(nodes):
                return any((node.get('path') in ('tool','/tool') and any(child.get('path') == 'probe' and child.get('component') == 'generatorprobe/probe/index' for child in node.get('children',[]))) or has_route(node.get('children',[])) for node in nodes)
            check('generated SQL exposes the genuine native tool/probe menu route',status == 200 and routers.get('code') == 200 and has_route(routers['data']))
            context = browser.new_context(storage_state=state,viewport=dict(width=1600,height=1040),record_video_dir=str(output/'business-video'),record_video_size=dict(width=1600,height=1040))
            contexts.append(context)
            page = context.new_page(); errors = []
            page.on('pageerror', lambda error: errors.append(type(error).__name__))
            def record_response(response):
                # Only fixed synthetic business responses. Never record login, tokens or headers.
                parsed = urllib.parse.urlsplit(response.url)
                if parsed.path != API and not parsed.path.startswith(API+'/'): return
                row = {'method':response.request.method,'path':parsed.path,'query':parsed.query,'status':response.status,'body':response.json()}
                with (output/'business-http.jsonl').open('a') as log: log.write(json.dumps(row,ensure_ascii=False)+'\n')
            def is_list(response): return urllib.parse.urlsplit(response.url).path == API+'/list'
            def list_action(action):
                with page.expect_response(is_list,timeout=30000) as received: action()
                body = received.value.json()
                record_response(received.value)
                check('native browser list request succeeds',received.value.status == 200 and body.get('code',200) == 200 and isinstance(body.get('rows'),list))
                settled(page)
                return body
            def screenshot(name):
                settled(page)
                page.screenshot(path=str(output/name),full_page=True)
                report.setdefault('businessScreenshots',[]).append(name)
            initial = list_action(lambda:page.goto(base+PAGE,wait_until='domcontentloaded'))
            expect(page.get_by_test_id('generator-business-note')).to_have_text(MANUAL_TITLE)
            check('initial native list excludes the seeded other-tenant row',initial['total'] == 0)
            names = ['合成验收 A','合成验收 B','合成验收 C']
            dates = ['2026-10-12 09:30:00','2026-10-13 23:59:58','2026-10-14 11:00:00']
            def save(method):
                dialog = page.get_by_role('dialog')
                def click():
                    with page.expect_response(lambda response: urllib.parse.urlsplit(response.url).path == API and response.request.method == method) as received:
                        dialog.get_by_role('button',name='确 定',exact=True).click()
                    record_response(received.value)
                    check('native generated '+method+' succeeds',received.value.status == 200 and received.value.json().get('code') == 200)
                    payload = received.value.request.post_data_json
                    check('browser checkbox submits a string API payload', isinstance(payload.get('tags'),str))
                value = list_action(click)
                expect(dialog).not_to_be_visible()
                return value
            def checkbox(dialog,label): return dialog.locator('.el-checkbox').filter(has_text=label).locator('input[type=checkbox]')
            for index,name in enumerate(names):
                page.get_by_role('button',name='新增',exact=True).click()
                dialog = page.get_by_role('dialog')
                dialog.get_by_placeholder('请输入名称',exact=True).fill(name)
                field = dialog.get_by_placeholder('请选择业务日期',exact=True)
                field.fill(dates[index]);field.press('Tab')
                if index != 2: checkbox(dialog,'正常').check()
                if index != 1: checkbox(dialog,'停用').check()
                if index == 0: screenshot('03-business-add-form.png')
                listed = save('POST')
                check('browser-created row is persisted and returned',any(row['name'] == name for row in listed['rows']))
            check('three generated browser inserts persisted with native tenant ownership', sql("SELECT COUNT(*) FROM fcp_codegen_probe WHERE tenant_id='000000';") == '3')
            check('native generated inserts fill all audit ownership fields', sql("SELECT COUNT(*) FROM fcp_codegen_probe WHERE tenant_id='000000' AND create_by=1 AND update_by=1 AND create_dept=103 AND create_time IS NOT NULL AND update_time IS NOT NULL;") == '3')
            check('late-evening business date persists without truncation', sql("SELECT DATE_FORMAT(event_time,'%Y-%m-%d %H:%i:%s') FROM fcp_codegen_probe WHERE name='合成验收 B';") == dates[1])
            check('checkbox multi-selection round trips as comma-separated values',sql("SELECT tags FROM fcp_codegen_probe WHERE name='合成验收 A';") == '0,1')
            screenshot('04-business-generated-list.png')
            def open_edit(name):
                row = page.locator('.el-table__body-wrapper tbody tr').filter(has_text=name)
                expect(row).to_have_count(1)
                row.get_by_role('checkbox').check()
                page.get_by_role('button',name='修改',exact=True).click()
                dialog = page.get_by_role('dialog')
                expect(dialog).to_be_visible()
                return dialog
            dialog = open_edit(names[0])
            expect(checkbox(dialog,'正常')).to_be_checked();expect(checkbox(dialog,'停用')).to_be_checked()
            checkbox(dialog,'停用').uncheck()
            dialog.get_by_placeholder('请输入名称',exact=True).fill('合成验收 A（已修改）')
            screenshot('05-business-edit-form.png')
            save('PUT')
            check('browser edit persists name and one checkbox value', sql("SELECT CONCAT(name,'|',tags) FROM fcp_codegen_probe WHERE name='合成验收 A（已修改）';") == '合成验收 A（已修改）|0')
            dialog = open_edit('合成验收 A（已修改）')
            expect(checkbox(dialog,'正常')).to_be_checked();expect(checkbox(dialog,'停用')).not_to_be_checked()
            checkbox(dialog,'正常').uncheck();save('PUT')
            check('browser clears all checkbox values without array/string errors',sql("SELECT COUNT(*) FROM fcp_codegen_probe WHERE name='合成验收 A（已修改）' AND tags='';") == '1')
            dialog = open_edit('合成验收 A（已修改）')
            expect(checkbox(dialog,'正常')).not_to_be_checked();expect(checkbox(dialog,'停用')).not_to_be_checked()
            dialog.get_by_role('button',name='取 消',exact=True).click();expect(dialog).not_to_be_visible()
            # Clear row selection through real list reload before search/delete.
            list_action(lambda:page.get_by_role('button',name='重置',exact=True).click())
            page.locator('.el-form--inline').get_by_placeholder('请输入名称',exact=True).fill('已修改')
            found = list_action(lambda:page.get_by_role('button',name='搜索',exact=True).click())
            check('native generated name LIKE search returns only edited row',found['total'] == 1 and found['rows'][0]['name'] == '合成验收 A（已修改）')
            list_action(lambda:page.get_by_role('button',name='重置',exact=True).click())
            # Fill actual Element Plus date range inputs, then blur to commit v-model.
            begin,end = page.get_by_placeholder('开始日期',exact=True),page.get_by_placeholder('结束日期',exact=True)
            begin.fill('2026-10-13');end.fill('2026-10-13');end.press('Tab')
            ranged = list_action(lambda:page.get_by_role('button',name='搜索',exact=True).click())
            check('inclusive date range contains late-evening row and excludes adjacent dates',ranged['total'] == 1 and ranged['rows'][0]['name'] == names[1])
            screenshot('06-business-date-search.png')
            reset = list_action(lambda:page.get_by_role('button',name='重置',exact=True).click())
            check('query reset restores every current-tenant business row',reset['total'] == 3)
            # Cross-tenant read/update/delete must remain constrained, even for native superadmin.
            _,foreign = native_request(base,token,API+'/970001')
            check('other-tenant generated detail is not disclosed',foreign.get('code') == 200 and foreign.get('data') is None)
            native_request(base,token,API,'PUT',dict(id='970001',name='不得跨租户修改',tags='1'))
            native_request(base,token,API+'/970001','DELETE')
            check('native tenant filter prevents foreign update and delete',sql("SELECT CONCAT(name,'|',tags) FROM fcp_codegen_probe WHERE id=970001 AND tenant_id='fcp-other-ci';") == '其他租户合成记录|0')
            row = page.locator('.el-table__body-wrapper tbody tr').filter(has_text=names[2]);row.get_by_role('checkbox').check()
            page.get_by_role('button',name='删除',exact=True).click()
            def delete():
                with page.expect_response(lambda response: response.request.method == 'DELETE' and urllib.parse.urlsplit(response.url).path.startswith(API+'/')) as received:
                    page.locator('.el-message-box').get_by_role('button',name='确定',exact=True).click()
                record_response(received.value)
                check('native browser delete succeeds', received.value.json().get('code') == 200)
            deleted = list_action(delete)
            check('browser deletion removes exactly its selected synthetic row',deleted['total'] == 2 and sql("SELECT COUNT(*) FROM fcp_codegen_probe WHERE name='合成验收 C';") == '0')
            screenshot('07-business-after-delete.png')
            persisted = list_action(lambda:page.reload(wait_until='domcontentloaded'))
            check('fresh business page reload retains real database edits',persisted['total'] == 2 and any(row['name'] == '合成验收 A（已修改）' for row in persisted['rows']))
            check('generated business browser has no uncaught exception',not errors)
            # Fresh login after SQL grants; do not reuse cached native permissions.
            readonly_state = fixture.login(browser,base,'viewer')
            readonly_token = native_token(readonly_state,base)
            readonly = browser.new_context(storage_state=readonly_state,viewport=dict(width=1600,height=1040));contexts.append(readonly)
            viewer_page = readonly.new_page()
            viewer_page.goto(base+PAGE,wait_until='domcontentloaded')
            expect(viewer_page.get_by_test_id('generator-business-note')).to_be_visible(timeout=30000)
            expect(viewer_page.locator('.el-table__body-wrapper tbody tr')).to_have_count(2,timeout=20000)
            settled(viewer_page)
            for label in ('新增','修改','删除','导出'):
                expect(viewer_page.get_by_role('button',name=label,exact=True)).to_have_count(0)
            expect(viewer_page.locator('.el-table__body-wrapper .el-button')).to_have_count(0)
            check('native list-only role sees business rows without write controls',True)
            viewer_page.screenshot(path=str(output/'08-business-read-only.png'),full_page=True)
            report.setdefault('businessScreenshots',[]).append('08-business-read-only.png')
            existing_id = str(persisted['rows'][0]['id'])
            for method,path,payload in [('POST',API,dict(name='无权新增',tags='0')),('PUT',API,dict(id=existing_id,name='无权修改',tags='0')),('DELETE',API+'/'+existing_id,None),('POST',API+'/export',{})]:
                status,value = native_request(base,readonly_token,path,method,payload)
                check('native read-only role denies generated '+method+' '+path, value.get('code') == 403 and status in (200,403))
            status,value = native_request(base,None,API+'/list')
            check('anonymous generated API requires genuine login',value.get('code') == 401 and status in (200,401))
            check('rejected writes leave all current/foreign rows intact',sql('SELECT COUNT(*) FROM fcp_codegen_probe;') == '3')
            report['generatedBusinessRuntime'] = 'PASS'
        except Exception:
            # Preserve visible synthetic business failure state; never screenshot a login form.
            if 'page' in locals() and urllib.parse.urlsplit(page.url).path == PAGE:
                try:
                    page.screenshot(path=str(output/'business-failure.png'),full_page=True)
                except Exception: pass
            raise
        finally:
            for context in contexts: context.close()
            browser.close()
