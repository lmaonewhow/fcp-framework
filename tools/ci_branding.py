"""Brand and seed-menu acceptance on the existing disposable native CI fixture only."""
from pathlib import Path
import json
import os
import re
from native_client import NativeClient
from ci_ingestion import safe_diagnostic
from native_login import native_token

ROOT=Path(__file__).resolve().parents[1]


def verify_branding(fixture, base, build_id):
    if os.environ.get('GITHUB_ACTIONS')!='true' or not re.fullmatch(r'fcp_ci_[a-f0-9]{12}',fixture.database):
        raise RuntimeError('Branding acceptance requires the disposable native fixture')
    from playwright.sync_api import sync_playwright, expect
    output=ROOT/'reports/native-branding';output.mkdir(parents=True,exist_ok=True)
    report={'status':'RUNNING','buildId':build_id,'checks':[],'scope':'genuine native dual-UI branding and MySQL seed menus','production':'NOT_ACCEPTED'}
    def check(name,value):
        if not value:raise AssertionError(name)
        report['checks'].append({'name':name,'status':'PASS'})
    try:
        remaining=fixture.sql(f"USE `{fixture.database}`; SELECT COUNT(*) FROM sys_menu WHERE menu_id IN (4,5,1500,1501,1502,1503,1504,1505,1506,1507,1508,1509,1510,1511);").strip()
        check('fresh MySQL has no upstream demo or website menu seeds',remaining=='0')
        check('native accounts, business tables and generator menu are retained',fixture.sql(f"USE `{fixture.database}`; SELECT COUNT(*) FROM sys_menu WHERE menu_id=115 AND perms='tool:gen:list';").strip()=='1')
        with sync_playwright() as playwright:
            browser=playwright.chromium.launch()
            try:
                for shell,role in [('admin','admin'),('teacher','teacher')]:
                    # Capture only empty login forms, never credentials or tokens.
                    context=browser.new_context(viewport={'width':1440,'height':960})
                    try:
                        page=context.new_page();page.goto(base+'/'+shell+'/login',wait_until='domcontentloaded')
                        expect(page.locator('input[type=password]').first).to_be_visible(timeout=30000)
                        check(shell+' login title uses product name','智慧云校' in page.title())
                        check(shell+' no upstream promotion on login',not re.search(r'RuoYi|若依|Soybean',page.locator('body').inner_text(),re.I))
                        check(shell+' no default password prefill',page.locator('input[type=password]').first.input_value()=='')
                        icon=page.locator('link[rel=icon]').evaluate('(link) => link.href');response=context.request.get(icon)
                        check(shell+' school favicon is served',response.ok and '智慧云校' in response.text())
                        page.screenshot(path=str(output/(shell+'-login.png')),full_page=True)
                        page.set_viewport_size({'width':390,'height':844});page.screenshot(path=str(output/(shell+'-login-mobile.png')),full_page=True)
                    finally:context.close()
                    state=fixture.login(browser,base,role)
                    context=browser.new_context(storage_state=state,viewport={'width':1440,'height':960})
                    try:
                        page=context.new_page();page.goto(base+'/'+shell+'/fcp/ingestion',wait_until='domcontentloaded')
                        expect(page.get_by_test_id('fcp-ingestion')).to_be_visible(timeout=30000)
                        check(shell+' authenticated title uses product name','智慧云校' in page.title())
                        text=page.locator('body').inner_text()
                        check(shell+' no upstream brand, website or demo menu in authenticated UI',not re.search(r'RuoYi|若依|Soybean|PLUS官网|测试菜单|测试单表|测试树表',text,re.I))
                        client=NativeClient(base,native_token(context.storage_state(),base),'e5cd7e4891bf95d1d19206ce24a7b32e')
                        menus=client.data('/system/menu/getRouters' if shell=='admin' else '/fcp/ui/soybean-routes')
                        check(shell+' server route tree excludes demo/website',not re.search(r'demo/demo|demo/tree|gitee.com/dromara|测试菜单',json.dumps(menus,ensure_ascii=False)))
                        page.screenshot(path=str(output/(shell+'-workspace.png')),full_page=True)
                        page.reload(wait_until='domcontentloaded');expect(page.get_by_test_id('fcp-ingestion')).to_be_visible(timeout=30000)
                        check(shell+' deep link refresh remains native', '智慧云校' in page.title())
                        if shell=='admin':
                            page.goto(base+'/admin/index',wait_until='domcontentloaded');expect(page.get_by_test_id('smart-school-home')).to_be_visible(timeout=30000)
                            check('admin real business home replaces promotion','智慧云校' in page.get_by_test_id('smart-school-home').inner_text())
                            page.screenshot(path=str(output/'admin-home.png'),full_page=True)
                            page.go_back(wait_until='domcontentloaded');expect(page.get_by_test_id('fcp-ingestion')).to_be_visible(timeout=30000)
                            page.go_forward(wait_until='domcontentloaded');expect(page.get_by_test_id('smart-school-home')).to_be_visible(timeout=30000)
                            check('admin back and forward preserve home and workspace',True)
                    finally:context.close()
            finally:browser.close()
        report['status']='PASS'
    except Exception as error:
        report['status']='FAIL';report['error']=type(error).__name__+': '+safe_diagnostic(error,fixture.diagnostic_secrets);raise
    finally:
        report['totalChecks']=len(report['checks']);(output/'acceptance.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    return report
