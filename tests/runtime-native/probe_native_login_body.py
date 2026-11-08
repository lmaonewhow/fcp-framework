#!/usr/bin/env python3
"""Real Chromium + synthetic loopback HTTP lifecycle probe, NOT RuoYi acceptance.

No request interception, fake native service, production account or external host.
The deliberately synthetic JSON/UI exists only to test Playwright helper lifecycle.
"""
from __future__ import annotations
import argparse
import hashlib
from collections import Counter
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import sys
import threading
import time
from urllib.parse import urlsplit

ROOT=Path(__file__).resolve().parents[2]
sys.path[:0]=[str(ROOT/'tools'),str(Path(__file__).parent)]
from native_login import NativeLoginPreparationError, _failure_kind, _login_envelope, open_native_tenant_login
from ui_boundaries import native_logout

CHALLENGE={'code':200,'data':{'captchaEnabled':True,'uuid':'a'*32,'img':'synthetic-probe'}}
TENANTS={'code':200,'data':{'tenantEnabled':True,'voList':[{'tenantId':'960001','companyName':'Synthetic CI tenant'}]}}
WORKSPACE='''<!doctype html><meta charset="utf-8"><button class="n-avatar">Avatar</button>
<div class="n-dropdown-option-body">退出登录</div><div class="n-dialog"><button>确认</button></div>
<script>
localStorage.setItem('FCP-Host-Token','synthetic-loopback-probe');
document.querySelector('.n-dialog button').onclick = async () => {
  await (await fetch('/auth/logout')).json();
  localStorage.removeItem('FCP-Host-Token');
  location.assign('/teacher/login/pwd-login');
};
</script>'''
LOGIN='''<!doctype html><meta charset="utf-8"><div class="login-form">Synthetic probe only</div>
<script>fetch('/auth/code').then(r=>r.json()); fetch('/auth/tenant/list').then(r=>r.json());</script>'''

class Fixture:
    def __init__(self):
        self.counts=Counter()
        self.hold_logout=False
        self.release_logout=threading.Event()
        self.release_tenant=threading.Event()
        owner=self
        class Handler(BaseHTTPRequestHandler):
            def log_message(self,*args):pass
            def do_GET(self):
                path=urlsplit(self.path).path
                owner.counts[path]+=1
                content_type='text/html; charset=utf-8'
                delayed=None
                if path=='/teacher/fcp/plugins':body=WORKSPACE.encode()
                elif path=='/admin/login':body=LOGIN.encode()
                elif path=='/teacher/login/pwd-login':body=b'<!doctype html><p>Logged out</p>'
                elif path=='/probe/body':
                    body=b"<script>fetch('/auth/tenant/list?probe=delayed').then(r=>r.json()).catch(()=>{});</script>"
                elif path=='/auth/logout':
                    body=b'{"code":200,"data":null}';content_type='application/json'
                    delayed=owner.release_logout if owner.hold_logout else None
                elif path in ('/auth/code','/auth/tenant/list'):
                    body=json.dumps(CHALLENGE if path=='/auth/code' else TENANTS).encode()
                    content_type='application/json'
                    if urlsplit(self.path).query=='probe=delayed':delayed=owner.release_tenant
                else:body=b'<!doctype html><p>Blank</p>'
                self.send_response(200)
                self.send_header('Content-Type',content_type)
                self.send_header('Content-Length',str(len(body)))
                self.send_header('X-Trace-Id','1234567890abcdef1234567890abcdef')
                self.send_header('Cache-Control','no-store')
                self.end_headers();self.wfile.flush()
                if delayed is not None:delayed.wait(10)
                elif path=='/auth/logout':time.sleep(0.2)
                try:self.wfile.write(body);self.wfile.flush()
                except (BrokenPipeError,ConnectionResetError):pass
        self.server=ThreadingHTTPServer(('127.0.0.1',0),Handler)
        self.base='http://127.0.0.1:'+str(self.server.server_address[1])
        self.thread=threading.Thread(target=self.server.serve_forever,daemon=True)
        self.thread.start()
    def close(self):
        self.release_logout.set();self.release_tenant.set()
        self.server.shutdown();self.server.server_close();self.thread.join(timeout=5)


def has_token(page,base):
    return any(e.get('name')=='FCP-Host-Token' and bool(e.get('value'))
               for o in page.context.storage_state().get('origins',[]) if o.get('origin')==base
               for e in o.get('localStorage',[]))


def legacy_headers_only_logout(page,base):
    """Exact old helper boundary: click native-like controls and stop at headers."""
    page.locator('.n-avatar').click()
    page.locator('.n-dropdown-option-body').click()
    with page.expect_response(lambda r:r.url==base+'/auth/logout') as response:
        page.locator('.n-dialog').get_by_role('button',name='确认').click()
    assert response.value.status==200


def exercise(browser,report,iterations):
    fixture=Fixture()
    try:
        report['stage']='headers-only-negative-control'
        context=browser.new_context();page=context.new_page()
        try:
            fixture.hold_logout=True
            page.goto(fixture.base+'/teacher/fcp/plugins')
            legacy_headers_only_logout(page,fixture.base)
            assert has_token(page,fixture.base), 'Negative control did not expose the old incomplete boundary'
            assert urlsplit(page.url).path=='/teacher/fcp/plugins'
            # The old caller immediately overwrote this document with its own goto.
            page.goto(fixture.base+'/admin/login',wait_until='domcontentloaded')
            assert has_token(page,fixture.base), 'Negative control did not retain the uncleaned state'
            report['headersOnlyControl']={'status':'EXPECTED_FAILURE','nativeCleanupCompleted':False,
                                           'tokenRetainedAfterCallerNavigation':True}
        finally:
            fixture.release_logout.set();context.close()
        fixture.hold_logout=False
        report['stage']='discarded-body-negative-control'
        context=browser.new_context();page=context.new_page()
        try:
            before=fixture.counts['/auth/tenant/list']
            with page.expect_response(lambda r:urlsplit(r.url).path=='/auth/tenant/list') as response:
                page.goto(fixture.base+'/probe/body',wait_until='domcontentloaded')
            page.goto(fixture.base+'/blank',wait_until='domcontentloaded')
            fixture.release_tenant.set()
            try:response.value.json()
            except Exception:legacy_stage='invalid-json'
            else:raise AssertionError('Negative body control unexpectedly retained the delayed response')
            try:_login_envelope(response.value,'/auth/tenant/list')
            except NativeLoginPreparationError as error:
                assert error.diagnostic['stage']=='body-read-failed'
                assert not error.limited
                report['discardedBodyControl']={'status':'EXPECTED_FAILURE','legacyStage':legacy_stage,
                                                 'diagnostic':error.diagnostic,'retried':False}
            else:raise AssertionError('Unavailable body was not rejected')
            assert fixture.counts['/auth/tenant/list']==before+1
        finally:context.close()
        report['fixedIterations']=[]
        for index in range(iterations):
            report['stage']='fixed-helper-iteration'
            report['iteration']=index+1
            context=browser.new_context();page=context.new_page()
            try:
                page.goto(fixture.base+'/teacher/fcp/plugins')
                logout=[];preparation=[]
                native_logout(page,'teacher',events=logout)
                assert not has_token(page,fixture.base)
                assert urlsplit(page.url).path=='/teacher/login/pwd-login'
                before=fixture.counts.copy()
                captcha=open_native_tenant_login(page,fixture.base,events=preparation,
                    sleep=lambda _:(_ for _ in ()).throw(AssertionError('Probe must never retry')))
                assert captcha==CHALLENGE['data']
                assert all(e['stage']=='validated' and e['attempt']==1 for e in preparation)
                assert len(preparation)==2
                assert fixture.counts['/auth/code']==before['/auth/code']+1
                assert fixture.counts['/auth/tenant/list']==before['/auth/tenant/list']+1
                report['fixedIterations'].append({'iteration':index+1,'status':'PASS',
                                                  'logout':logout,'preparation':preparation})
            finally:context.close()
    finally:fixture.close()


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',type=Path,default=ROOT/'reports/native-login-probe')
    parser.add_argument('--browser-executable')
    parser.add_argument('--iterations',type=int,default=5)
    args=parser.parse_args()
    if not 1<=args.iterations<=20:parser.error('iterations must be between 1 and 20')
    args.output.mkdir(parents=True,exist_ok=True)
    report={'status':'NOT_RUN','scope':'Synthetic loopback HTTP + real Chromium helper lifecycle only',
            'nativeRuoYiAcceptance':'NOT_RUN','production':'NOT_ACCEPTED',
            'sourceFiles':{name:hashlib.sha256((ROOT/name).read_bytes()).hexdigest() for name in (
                'tools/native_login.py','tests/runtime-native/ui_boundaries.py',
                'tests/runtime-native/probe_native_login_body.py')}}
    started=time.monotonic();exit_code=1
    try:
        from playwright.sync_api import sync_playwright
        with sync_playwright() as playwright:
            try:
                options={'headless':True}
                if args.browser_executable:options['executable_path']=args.browser_executable
                browser=playwright.chromium.launch(**options)
            except Exception as error:
                report['failureKind']=_failure_kind(error)
                report.update(status='BLOCKED',reason='chromium-launch-unavailable');exit_code=2
            else:
                try:
                    report['browserVersion']=browser.version
                    exercise(browser,report,args.iterations)
                    report['status']='PASS';exit_code=0
                finally:browser.close()
    except OSError:
        report.update(status='BLOCKED',reason='loopback-fixture-unavailable');exit_code=2
    except Exception as error:
        report['failureKind']=_failure_kind(error)
        report.update(status='FAIL',reason='lifecycle-probe-assertion-or-operation-failed')
    finally:
        report['elapsedSeconds']=round(time.monotonic()-started,3)
        (args.output/'probe.json').write_text(json.dumps(report,indent=2)+'\n')
        print(json.dumps({'status':report['status'],'nativeRuoYiAcceptance':'NOT_RUN','exitCode':exit_code}))
    return exit_code

if __name__=='__main__':raise SystemExit(main())
