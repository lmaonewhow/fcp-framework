#!/usr/bin/env python3
"""Actual standalone Java / Python / browser acceptance. No mocked API responses."""
import hashlib, json, os, secrets, shutil, socket, subprocess, sys, tempfile, time, urllib.request, zipfile
from pathlib import Path
from build_devhost import build
ROOT = Path(__file__).resolve().parents[1]
REPORT = ROOT/'reports/development-host'

def main():
    REPORT.mkdir(parents=True, exist_ok=True)
    checks=[]
    def check(name, value):
        if not value: raise AssertionError(name)
        checks.append({'name':name,'status':'PASS'})
    result={'status':'FAIL','checks':checks,'scope':'Standalone development shell, not RuoYi or production acceptance',
            'sourceCommit':subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),
            'dirty':bool(subprocess.check_output(['git','status','--porcelain'],cwd=ROOT,text=True)),
            'sourceHashes':{str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for tree in ['development-host','integration/frontend-runtime'] for p in sorted((ROOT/tree).rglob('*')) if p.is_file()}}
    try:
        with tempfile.TemporaryDirectory(prefix='fcp-shell-') as temp:
            work=Path(temp)
            subprocess.run(['tsc',str(ROOT/'integration/frontend-runtime/index.ts'),'--target','ES2022','--module','ES2022','--strict','--outDir',str(work/'compiled')],check=True,timeout=90)
            check('bundled runtime exactly matches shared contract compiler output',(work/'compiled/index.js').read_bytes()==(ROOT/'development-host/src/main/resources/dev-ui/runtime.js').read_bytes())
            classes=build()
            subprocess.run(['javac','--release','17','-cp',str(classes),'-d',str(classes),str(ROOT/'tests/development-host/DevelopmentBackendTest.java')],check=True,timeout=60)
            backend=subprocess.run(['java','-cp',str(classes),'dev.fcp.dev.DevelopmentBackendTest'],capture_output=True,text=True,timeout=60)
            (REPORT/'backend-tests.log').write_text(backend.stdout+backend.stderr)
            check('development backend persistence and restrictions',backend.returncode==0)
            config={'development':True,'trustedPublishers':['test'],'executables':{'python':sys.executable},'identities':[]}
            tokens=[secrets.token_hex(32),secrets.token_hex(32)]
            for tenant,token in zip(['t1','t2'],tokens):
                config['identities'].append({'token':token,'userId':'shell-admin','tenantId':tenant,'scopes':['tenant:'+tenant],
                    'permissions':['fcp:plugin:install','fcp:plugin:manage','fcp:plugin:view','fcp:plugin:echo'],
                    'grants':[{'plugin':'test.echo','capability':'echo','operation':'invoke','dataScope':'tenant:'+tenant,'permission':'fcp:plugin:echo'}]})
            private=work/'identities.json'; private.write_text(json.dumps(config));private.chmod(0o600)
            cap={'id':'echo','version':'1.0.0','permission':'echo','sideEffects':False,'idempotent':True,'timeoutMillis':2000,'inputSchema':{'type':'object'},'outputSchema':{'type':'object'}}
            manifest={'manifestVersion':1,'pluginId':'test.echo','version':'1.0.0','publisher':'test','contractVersion':'1.0','runtime':{'type':'local','entry':'runtime.py','argv':[]},'provides':[cap],
                'ui':{'contractVersion':'1.0','pages':[{'id':'home','title':'Echo test','permissions':['echo'],'components':['stack@1','field@1','button@1','table@1'],'capabilities':['echo'],
                    'content':{'type':'stack','children':[{'type':'field','field':'message','text':'消息'},{'type':'button','text':'发送 Echo','capabilityId':'echo'},{'type':'table','text':'实际调用结果'}]}}],
                    'slots':[{'id':'status','slotId':'dashboard.status','order':1,'permissions':['echo'],'components':['text@1'],'capabilities':[],'content':{'type':'text','text':'Echo slot active'}}]}}
            package=work/'echo.zip'
            with zipfile.ZipFile(package,'w',zipfile.ZIP_DEFLATED) as z:
                z.writestr('plugin.json',json.dumps(manifest));z.write(ROOT/'tests/runtime-lifecycle/plugins/runtime.py','runtime.py')
            with socket.socket() as s:s.bind(('127.0.0.1',0));port=s.getsockname()[1]
            base=f'http://127.0.0.1:{port}'
            with (REPORT/'server.log').open('w') as log:
                process=subprocess.Popen(['java','-cp',str(classes),'dev.fcp.dev.DevHost','--development',str(private),str(work/'state'),str(port)],stdout=log,stderr=log)
                try:
                    for _ in range(150):
                        try:
                            with urllib.request.urlopen(base+'/dev/',timeout=1) as r:
                                check('static shell has restrictive CSP',"script-src 'self'" in r.headers.get('Content-Security-Policy',''));break
                        except OSError:
                            if process.poll() is not None:raise RuntimeError('DevHost startup failed; inspect server.log')
                            time.sleep(.1)
                    else:raise RuntimeError('DevHost startup timeout')
                    from playwright.sync_api import sync_playwright, expect
                    with sync_playwright() as playwright:
                        browser=playwright.chromium.launch(headless=True, **({'executable_path':os.environ['FCP_BROWSER_EXECUTABLE']} if os.environ.get('FCP_BROWSER_EXECUTABLE') else {}))
                        page=browser.new_page(viewport={'width':1280,'height':900});errors=[]
                        page.on('pageerror',lambda error:errors.append(str(error)))
                        page.goto(base+'/dev/');page.locator('#token').fill(tokens[0]);page.get_by_role('button',name='连接',exact=True).click()
                        expect(page.locator('#identity')).to_contain_text('租户：t1')
                        check('token not persisted',page.evaluate('localStorage.length === 0 && sessionStorage.length === 0 && document.cookie === ""'))
                        page.locator('#package').set_input_files(str(package));page.locator('#trusted').check();page.get_by_role('button',name='安装插件包',exact=True).click()
                        expect(page.locator('#plugins')).to_contain_text('test.echo')
                        page.get_by_role('button',name='预览启用',exact=True).click();expect(page.locator('#plan')).to_be_visible()
                        page.get_by_role('button',name='取消计划',exact=True).click();expect(page.locator('#plan')).to_be_hidden()
                        check('cancel does not publish page',page.locator('#pages button').count()==0)
                        page.get_by_role('button',name='预览启用',exact=True).click();page.locator('#approve').check();page.get_by_role('button',name='确认执行计划',exact=True).dblclick()
                        expect(page.locator('#pages')).to_contain_text('Echo test');expect(page.locator('#slots')).to_contain_text('Echo slot active')
                        check('repeated confirmation one ready plugin',page.locator('#pages button').count()==1)
                        page.get_by_role('button',name='Echo test',exact=True).click();page.get_by_label('消息',exact=True).fill('<img src=x onerror=alert(1)>')
                        page.get_by_role('button',name='发送 Echo',exact=True).click();expect(page.locator('#page table')).to_contain_text('<img src=x onerror=alert(1)>')
                        expect(page.locator('#page table')).to_contain_text('t1');check('result rendered as literal text',page.locator('#page img').count()==0)
                        page.screenshot(path=str(REPORT/'standalone-shell.png'),full_page=True)
                        page.go_back();page.go_forward();expect(page.get_by_label('消息',exact=True)).to_be_visible();check('back forward restores namespaced page',True)
                        page.get_by_role('button',name='预览停用',exact=True).click();page.locator('#approve').check();page.get_by_role('button',name='确认执行计划',exact=True).click()
                        expect(page.locator('#pages button')).to_have_count(0);expect(page.locator('#slots')).to_be_empty();expect(page.locator('#page')).to_contain_text('已停用')
                        check('disable clears pages slots stale fields',page.get_by_label('消息',exact=True).count()==0)
                        page.locator('#logout').click();page.locator('#token').fill(tokens[1]);page.get_by_role('button',name='连接',exact=True).click();expect(page.locator('#identity')).to_contain_text('租户：t2')
                        check('second tenant does not inherit first tenant page',page.locator('#pages button').count()==0)
                        page.reload();expect(page.locator('#login')).to_be_visible();expect(page.locator('#workspace')).to_be_hidden()
                        check('refresh drops memory only authentication',page.locator('#token').input_value()=='')
                        check('no browser execution errors',not errors);browser.close()
                finally:
                    process.terminate()
                    try:process.wait(timeout=15)
                    except subprocess.TimeoutExpired:process.kill();process.wait()
        result['status']='PASS'
    except Exception as error:
        result['error']=str(error)
        if 'Executable doesn\'t exist' in str(error) or 'Operation not permitted' in str(error): result['status']='BLOCKED'
        raise
    finally:
        (REPORT/'shell.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
    print(json.dumps({'status':result['status'],'checks':len(checks),'report':str(REPORT/'shell.json')}))
if __name__=='__main__':main()
