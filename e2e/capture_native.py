#!/usr/bin/env python3
"""Record real native browser interaction. No set_content, mocked routes, fake API or synthetic token.
Use --headed for normal manual login/captcha, or supply storage states saved after real native login.
"""
from __future__ import annotations
import argparse,json,os,sys,time,uuid
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'tools'))
from native_client import NativeClient,envelope,require_loopback

def main():
 parser=argparse.ArgumentParser(description=__doc__)
 parser.add_argument('--base',default='http://127.0.0.1:8080')
 parser.add_argument('--headed',action='store_true')
 parser.add_argument('--browser-channel',choices=['chrome','msedge'],help='Use an already installed Chromium browser in an isolated context')
 parser.add_argument('--expect-admin-statistics-limit',action='store_true',help='Fixture has over 1000 admin-visible rows; require explicit RESULT_LIMIT instead of Python statistics for admin')
 parser.add_argument('--states-dir',type=Path)
 parser.add_argument('--output',type=Path,default=ROOT/'reports/native-browser')
 args=parser.parse_args()
 admin_client=None;original_enabled=None
 report={'status':'BLOCKED','kind':'real-native-browser','roles':[],'viewport':{'width':1440,'height':900}}
 out=args.output;out.mkdir(parents=True,exist_ok=True)
 try:
  require_loopback(args.base)
  if not args.headed and args.states_dir is None: raise ValueError('Headless mode requires actual previously authenticated browser storage states')
  from playwright.sync_api import sync_playwright,expect
  with sync_playwright() as p:
   launch={'headless':not args.headed}
   if args.browser_channel:launch['channel']=args.browser_channel
   browser=p.chromium.launch(**launch)
   report['browser']={'channel':args.browser_channel or 'playwright-chromium','version':browser.version}
   try:
    for role,shell in [('admin','admin'),('teacher','teacher'),('viewer','teacher')]:
     options=dict(viewport=report['viewport'],record_video_dir=str(out/'raw'),record_video_size=report['viewport'])
     if args.states_dir:
      state=args.states_dir/(role+'.json')
      if not state.is_file(): raise ValueError('Missing genuine login storage state for '+role)
      options['storage_state']=str(state)
     context=browser.new_context(**options)
     page=context.new_page();video=page.video
     errors=[];page.on('pageerror',lambda error:errors.append(str(error)[:300]))
     try:
      url=args.base.rstrip('/')+'/'+shell+'/fcp/school'
      page.goto(url,wait_until='domcontentloaded')
      if not args.states_dir: print('请在真实浏览器中完成 '+role+' 账号登录及验证码。页面需获得对应 FCP 角色授权。',flush=True)
      marker=page.get_by_test_id('fcp-native-'+shell)
      expect(marker).to_be_visible(timeout=180000 if args.headed else 30000)
      # Tokens are only read for requests to this same host, never logged or written to the report.
      token=page.evaluate("localStorage.getItem('FCP-Host-Token')")
      client=NativeClient(args.base,token,os.environ.get('FCP_CLIENT_ID','e5cd7e4891bf95d1d19206ce24a7b32e'))
      snapshot=client.snapshot()
      if role=='admin':
       report['host']=client.evidence();admin_client=client;original_enabled=snapshot['pythonEnabled']
       if not original_enabled:
        with page.expect_response(lambda response:response.url.endswith('/fcp/school/composition')) as enabled:
         page.get_by_test_id('toggle-python').click()
        if enabled.value.json().get('code')!=200: raise AssertionError('Real admin could not enable Python')
        expect(page.get_by_test_id('analyze')).to_be_visible()
        snapshot=client.snapshot()
      elif snapshot['allowedShells']!=['teacher']: raise AssertionError('Wrong account role for '+role)
      if marker.get_attribute('data-build-id')!=report['host']['buildId']: raise AssertionError('UI and backend build IDs differ')
      if not page.title(): raise AssertionError('Missing real document title')
      if page.locator('vite-error-overlay').count(): raise AssertionError('Framework error overlay')
      expect(page.locator('.el-card' if shell=='admin' else '.n-card').first).to_be_visible()
      if role in ('admin','teacher'):
       if 'create' not in snapshot['operations']: raise AssertionError('Account lacks expected create permission')
       name='browser-'+role+'-'+uuid.uuid4().hex[:8]
       marker.locator('input[data-testid="student"], [data-testid="student"] input').fill(name)
       with page.expect_response(lambda r:r.url.endswith('/fcp/school/evaluation/create')) as pending:
        page.get_by_role('button',name='保存评价',exact=True).click()
       if pending.value.json().get('code')!=200: raise AssertionError('Real backend rejected create')
       expect(page.get_by_test_id('save-confirmation')).to_contain_text(name)
       with page.expect_response(lambda r:r.url.endswith('/fcp/school/evaluation/analyze')) as pending:
        page.get_by_test_id('analyze').click()
       body=pending.value.json()
       if role=='admin' and args.expect_admin_statistics_limit:
        if body.get('code')==200 or body.get('data',{}).get('fcpCode')!='RESULT_LIMIT':
         raise AssertionError('Expected explicit admin statistics limit rejection')
        expect(marker.get_by_text(body['msg'],exact=True)).to_be_visible()
       else:
        if body.get('code')!=200 or body['data']['engine']!='python-stdlib' or body['data']['pid']<=0:
         raise AssertionError('Real Python provider evidence missing')
        expect(page.get_by_test_id('analysis-result')).to_contain_text('Python PID')
      else:
       if 'create' in snapshot['operations']: raise AssertionError('Viewer account was overprivileged')
       if page.get_by_role('button',name='保存评价',exact=True).count(): raise AssertionError('Readonly UI exposes save')
       _,denied=client.invoke(snapshot,'create',{'data':{'student':'forbidden-viewer','classId':'class-a','indicator':'课堂参与','score':80,'comment':''},'requestKey':'blocked-'+uuid.uuid4().hex})
       if denied.get('code')!=403: raise AssertionError('Readonly API denial failed')
      page.screenshot(path=str(out/(role+'-desktop.png')),full_page=True)
      page.set_viewport_size({'width':390,'height':844})
      # Native layout watches and its sidebar transition settle asynchronously.
      # Keep the strict overflow assertion, but require the responsive state first.
      page.wait_for_function('document.documentElement.scrollWidth <= innerWidth + 2',timeout=5000)
      page.screenshot(path=str(out/(role+'-mobile.png')),full_page=True)
      if page.evaluate('document.documentElement.scrollWidth > innerWidth + 2'): raise AssertionError('Whole-document mobile overflow')
      if errors: raise AssertionError('Browser JavaScript errors: '+str(len(errors)))
      report['roles'].append({'role':role,'shell':shell,'status':'PASS','url':page.url,'title':page.title(),'jsErrors':errors})
     finally:
      context.close()
      if video: video.save_as(str(out/(role+'.webm')))
    report['status']='PASS'
   finally:browser.close()
 except Exception as exc:
  report.update(status='FAIL' if report['roles'] else 'BLOCKED',error=f'{type(exc).__name__}: {exc}')
 finally:
  if admin_client is not None and original_enabled is not None:
   try:
    current=admin_client.snapshot()
    if current['pythonEnabled']!=original_enabled:
     admin_client.data('/fcp/school/composition',envelope(current,{'enabled':original_enabled}))
   except Exception as error:report.update(status='FAIL',restoreError=type(error).__name__)
  (out/'browser-report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
  print(json.dumps(report,ensure_ascii=False,indent=2))
 return 0 if report['status']=='PASS' else 2
if __name__=='__main__':raise SystemExit(main())
