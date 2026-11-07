"""Genuine RuoYi acceptance on disposable CI only; never use DevHost or fabricate login.

ci_native owns the database/JVM. Native login uses ci_ingestion's real encrypted browser
flow and captcha from its dedicated Redis namespace. Tokens remain only in memory.
"""
from __future__ import annotations
import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
import zipfile

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'tools'))
from native_client import NativeClient,require_loopback
from ci_ingestion import safe_diagnostic


def package_bytes(language: str) -> tuple[bytes,dict]:
    if language not in ('python','node'):raise ValueError('Explicit Python/Node fixture only')
    plugin='ci.'+language;entry='runtime.py' if language=='python' else 'runtime.mjs'
    capability=dict(id='echo',version='1.0.0',permission='echo',sideEffects=False,idempotent=True,timeoutMillis=10000,
        inputSchema=dict(type='object',properties=dict(message=dict(type='string',maxLength=1000),dependency=dict(type='boolean')),additionalProperties=False),outputSchema=dict(type='object'))
    ui=dict(contractVersion='1.0',pages=[dict(id='demo',title='CI '+language.title()+' runtime',permissions=['echo'],capabilities=['echo'],components=['stack@1','text@1','field@1','button@1','table@1'],content=dict(type='stack',children=[dict(type='text',text='Native '+language+' plugin'),dict(type='field',field='message',text='Message'),dict(type='button',text='调用插件',capabilityId='echo'),dict(type='table',text='Native result')]))],slots=[dict(id='status',slotId='dashboard.status',order=1,permissions=['echo'],capabilities=['echo'],components=['text@1'],content=dict(type='text',text='Native '+language+' ready'))])
    manifest=dict(manifestVersion=1,pluginId=plugin,version='1.0.0',publisher='test',contractVersion='1.0',runtime=dict(type='local',entry=entry,argv=[]),provides=[capability],requires=[capability] if language=='node' else [],dependencies=[dict(pluginId='ci.python',versionRange='1.0.0')] if language=='node' else [],ui=ui)
    output=io.BytesIO()
    with zipfile.ZipFile(output,'w',compression=zipfile.ZIP_DEFLATED) as archive:
        for name,content in [('plugin.json',json.dumps(manifest,ensure_ascii=False,separators=(',',':')).encode()),(entry,(Path(__file__).parent/'plugins'/entry).read_bytes())]:
            info=zipfile.ZipInfo(name,date_time=(2026,1,1,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;info.external_attr=(0o100644<<16);archive.writestr(info,content)
    return output.getvalue(),manifest


def exported(plugin,capability='echo'):
    return 'e'+hashlib.sha256(json.dumps([plugin,capability],ensure_ascii=False,separators=(',',':')).encode()).hexdigest()[:40]


class Client(NativeClient):
    def upload(self,package,digest,key,revision=None,staged=False):
        headers={'Content-Type':'application/octet-stream','clientid':self.client_id,'Authorization':'Bearer '+self.token,'X-Fcp-Digest':digest,'Idempotency-Key':key,'X-Fcp-Revision':str(revision)}
        request=urllib.request.Request(self.base+('/fcp/packages/staged' if staged else '/fcp/packages'),data=package,headers=headers,method='POST')
        if staged:request.remove_header('X-fcp-revision')
        try:
            with self.opener.open(request,timeout=30) as response:status,data=response.status,response.read(2*1024*1024)
        except urllib.error.HTTPError as error:status,data=error.code,error.read(2*1024*1024)
        body=json.loads(data)
        if status>=400 or str(body.get('code'))!='200':raise RuntimeError('Native package upload rejected: HTTP='+str(status)+' code='+str(body.get('data',{}).get('fcpCode',body.get('code'))))
        return body['data']


class RuntimeFixture:
    def __init__(self,database,sql,private_dir,login_fixture):
        if os.environ.get('GITHUB_ACTIONS')!='true' or not re.fullmatch(r'fcp_ci_[a-f0-9]{12}',database):raise RuntimeError('Runtime acceptance requires a freshly-created disposable CI database')
        if login_fixture is None or login_fixture.database!=database:raise RuntimeError('Matching genuine native login fixture required')
        self.database,self.sql,self.private_dir,self.login_fixture=database,sql,private_dir,login_fixture
        self.output=ROOT/'reports/runtime-native';self.output.mkdir(parents=True,exist_ok=True)
        self.report=dict(status='NOT_RUN',scope='genuine RuoYi HTTP + MySQL grants + real Python/Node subprocesses + both native Vue shells',production='NOT_ACCEPTED',checks=[],screenshots=[],backendPidUnchanged='NOT_RUN',browser='NOT_RUN',packages=[])
        # This is CI fixture setup, not a production migration and not automatic role grants.
        sql(f"""USE `{database}`;
INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,menu_type,perms,icon,is_frame,is_cache,visible,status,create_by,create_time)
VALUES(950520,'CI runtime echo',942020,99,'',NULL,'F','fcp:plugin:echo','#',1,1,'0','0',1,NOW());
INSERT INTO sys_role(role_id,tenant_id,role_name,role_key,role_sort,data_scope,status,del_flag,create_by,create_time)
VALUES(950510,'000000','CI runtime manager','fcp_ci_runtime_manager',90,'5','0','0',1,NOW()),(950511,'000000','CI runtime viewer','fcp_ci_runtime_viewer',91,'5','0','0',1,NOW());
INSERT INTO sys_role_menu(role_id,menu_id) VALUES(950510,942000),(950510,942020),(950510,942021),(950510,942022),(950510,950520),(950511,942000),(950511,942020),(950511,950520);
INSERT INTO sys_user_role(user_id,role_id) VALUES(1,950510),(950001,950511);
""")
        self._write()

    def _write(self):
        (self.output/'acceptance.json').write_text(json.dumps(self.report,ensure_ascii=False,indent=2)+'\n')

    def check(self,label,condition):
        if not condition:raise AssertionError(label)
        self.report['checks'].append(dict(name=label,status='PASS'));self._write()

    @staticmethod
    def token(state,base):
        matches=[item['value'] for origin in state.get('origins',[]) if origin.get('origin')==base for item in origin.get('localStorage',[]) if item.get('name')=='FCP-Host-Token']
        if len(matches)!=1 or not matches[0]:raise RuntimeError('Genuine browser login did not yield exactly one native token')
        return matches[0]

    @staticmethod
    def confirm(client,plan,key):
        return client.data('/fcp/plans/'+plan['planId']+'/confirm',dict(digest=plan['digest'],affectedPlugins=plan['affectedPlugins'],idempotencyKey=key))

    def change_grant(self,client,plugin,allow,key):
        options=client.data('/fcp/plugins/'+plugin+'/grant-options?targetTenant=000000')
        plan=client.data('/fcp/plugins/'+plugin+'/grants/plans',dict(targetTenant='000000',expectedRevision=options['revision'],grants=[dict(capabilityId='echo',dataScope='tenant:000000',allow=allow)]))
        result=self.confirm(client,plan,key)
        self.check(plugin+' explicit '+('grant' if allow else 'revocation')+' plan confirmed',result.get('phase')=='COMPLETE')

    def lifecycle(self,client,plugin,action,key):
        current=client.data('/fcp/plugins/'+plugin)
        plan=client.data('/fcp/plugins/'+plugin+'/plans',dict(action=action,expectedRevision=current['revision']))
        result=self.confirm(client,plan,key)
        self.check(plugin+' '+action+' exact closure applied',result.get('phase')=='COMPLETE')
        return plan,result

    def assert_child(self,plugin_pid,backend_pid,label):
        if sys.platform!='linux':raise RuntimeError('Native CI process-parent evidence requires Linux /proc')
        lines=Path('/proc',str(plugin_pid),'status').read_text().splitlines()
        parents=[line.split(':',1)[1].strip() for line in lines if line.startswith('PPid:')]
        self.check(label+' is an actual child of the native JVM',parents==[str(backend_pid)])

    def assert_pid(self,pid,provider,label):
        self.check('native JVM PID unchanged '+label,provider()==pid)

    def verify(self,base,build_id,pid_provider):
        require_loopback(base)
        if os.environ.get('FCP_ACCEPTANCE_DATABASE')!=self.database:raise RuntimeError('Explicit isolated acceptance database mismatch')
        from playwright.sync_api import sync_playwright,expect
        started=time.monotonic();pid=pid_provider()
        if not isinstance(pid,int) or pid<1:raise RuntimeError('Native JVM process unavailable')
        self.report.update(status='RUNNING',buildId=build_id,nativeJavaPid=pid)
        try:
            with sync_playwright() as playwright:
                browser=playwright.chromium.launch();self.report['browserVersion']=browser.version
                try:
                    states={role:self.login_fixture.login(browser,base,role) for role in ('admin','teacher')}
                    admin=Client(base,self.token(states['admin'],base),'e5cd7e4891bf95d1d19206ce24a7b32e')
                    evidence=admin.evidence();self.check('genuine pinned native Spring MySQL build',evidence.get('buildId')==build_id)
                    self.report['nativeEvidence']={key:evidence.get(key) for key in ('implementation','backendCommit','springBoot','database','databaseName','buildId')}
                    status,body=admin.request('/fcp/plugins',token=False);self.check('runtime API rejects missing native token',status>=400 or str(body.get('code'))!='200')
                    revision=admin.data('/fcp/plugins')['revision']
                    packages={}
                    for language in ('python','node'):
                        package,manifest=package_bytes(language);digest=hashlib.sha256(package).hexdigest();plugin=manifest['pluginId'];packages[plugin]=dict(digest=digest,package=package)
                        result=admin.upload(package,digest,'native-install-'+language,revision);revision=result['revision']
                        self.check(plugin+' verified ZIP installed without JVM restart',result.get('digest')==digest and result.get('state')=='INSTALLED')
                        self.report['packages'].append(dict(pluginId=plugin,version='1.0.0',sha256=digest,bytes=len(package)))
                        self.assert_pid(pid,pid_provider,'after '+plugin+' install')
                    replay=admin.upload(packages['ci.python']['package'],packages['ci.python']['digest'],'native-install-python',0)
                    self.check('same package request replay uses installation receipt',replay.get('replayed') is True)
                    node=admin.data('/fcp/plugins/ci.node');status,body=admin.request('/fcp/plugins/ci.node/plans',dict(action='ENABLE',expectedRevision=node['revision']))
                    self.check('requires capability cannot enable before platform approval',status>=400 and body.get('data',{}).get('fcpCode')=='CAPABILITY_NOT_GRANTED')
                    for plugin in packages:self.change_grant(admin,plugin,True,'grant-'+plugin)
                    plan,_=self.lifecycle(admin,'ci.node','ENABLE','native-enable-both')
                    self.check('administrator explicitly approved dependency closure',set(plan['affectedPlugins'])==set(packages))
                    catalog=admin.data('/fcp/ui/catalog')['catalog']
                    self.check('both declared pages published',set(packages)<=set(page['pluginId'] for page in catalog['pages']))
                    for language in ('python','node'):
                        plugin='ci.'+language;current=admin.data('/fcp/ui/catalog')['catalog'];result=admin.data('/fcp/capabilities/'+exported(plugin)+'/invoke',dict(input=dict(message='native-'+language),expectedRevision=current['revision'],idempotencyKey='native-echo-'+language))
                        self.check(plugin+' real runtime invoke has server tenant and OS PID',result.get('language')==language and result.get('message')=='native-'+language and result.get('tenantId')=='000000' and isinstance(result.get('pluginPid'),int) and result['pluginPid']!=pid)
                        self.assert_child(result['pluginPid'],pid,plugin)
                    current=admin.data('/fcp/ui/catalog')['catalog'];result=admin.data('/fcp/capabilities/'+exported('ci.node')+'/invoke',dict(input=dict(message='dependency',dependency=True),expectedRevision=current['revision'],idempotencyKey='native-callback'))
                    self.check('Node invokes approved Python dependency through Engine',result.get('language')=='python' and result.get('callerLanguage')=='node')
                    self.assert_pid(pid,pid_provider,'after both real runtimes and callback')
                    # Both pages below are real built upstream shells, using native authenticated sessions.
                    for role,shell in [('admin','admin'),('teacher','teacher')]:
                        context=browser.new_context(storage_state=states[role],viewport=dict(width=1440,height=960))
                        try:
                            page=context.new_page();errors=[]
                            page.on('pageerror',lambda error:errors.append(safe_diagnostic(error,self.login_fixture.diagnostic_secrets)))
                            page.goto(base+'/'+shell+'/fcp/plugins/ci.python/demo',wait_until='domcontentloaded')
                            workspace=page.get_by_test_id('fcp-plugin-workspace');expect(workspace).to_be_visible(timeout=30000)
                            selected=page.get_by_test_id('plugin-page');expect(selected).to_be_visible(timeout=30000);expect(selected.get_by_role('heading')).to_have_text('CI Python runtime')
                            selected.get_by_role('textbox',name='Message',exact=True).fill('native-'+shell+'-browser')
                            with page.expect_response(lambda r:'/fcp/capabilities/' in r.url and r.url.endswith('/invoke'),timeout=30000) as response:selected.get_by_role('button',name='调用插件').click()
                            reply=response.value.json();self.check(shell+' native UI invokes real capability with its own user',str(reply.get('code'))=='200' and str(reply.get('data',{}).get('principalId'))==('1' if role=='admin' else '950001'))
                            if role=='teacher':expect(workspace.get_by_role('button',name='安装与权限管理')).to_have_count(0)
                            else:expect(workspace.get_by_role('button',name='安装与权限管理')).to_be_visible()
                            expect(selected.locator('table')).to_contain_text('native-'+shell+'-browser',timeout=30000)
                            self.check(shell+' runtime page has no uncaught JavaScript errors',not errors)
                            path=self.output/(shell+'-runtime.png');page.screenshot(path=str(path),full_page=True);self.report['screenshots'].append(str(path.relative_to(ROOT)))
                        except Exception:
                            if 'page' in locals():
                                try:
                                    from ui_field_diagnostics import capture_field_diagnostics
                                    capture_field_diagnostics(page,self.output/(shell+'-runtime-field-failure.json'))
                                except Exception:
                                    pass  # Preserve original failure; diagnostics are best effort.
                                failure_path=self.output/(shell+'-runtime-failure.png')
                                try:page.screenshot(path=str(failure_path),full_page=True);self.report['screenshots'].append(str(failure_path.relative_to(ROOT)))
                                except Exception:pass
                            raise
                        finally:context.close()
                    self.report['browser']='PASS';self.assert_pid(pid,pid_provider,'after both browser shells')
                    old=admin.data('/fcp/ui/catalog')['catalog'];self.change_grant(admin,'ci.node',False,'revoke-ci.node')
                    status,body=admin.request('/fcp/capabilities/'+exported('ci.node')+'/invoke',dict(input=dict(message='must-deny'),expectedRevision=old['revision'],idempotencyKey='revoked-old-entry'))
                    self.check('revoked permission denies previously obtained entry',status>=400 or str(body.get('code'))!='200')
                    current=admin.data('/fcp/ui/catalog')['catalog'];self.check('revoked plugin UI page is not projected','ci.node' not in {p['pluginId'] for p in current['pages']})
                    for role,shell in [('admin','admin'),('teacher','teacher')]:
                        context=browser.new_context(storage_state=states[role],viewport=dict(width=1440,height=960))
                        try:
                            page=context.new_page();page.goto(base+'/'+shell+'/fcp/plugins/ci.node/demo',wait_until='domcontentloaded')
                            expect(page.get_by_test_id('fcp-plugin-workspace')).to_be_visible(timeout=30000)
                            expect(page.get_by_text('该插件页面不存在、已停用或当前身份无权访问。',exact=True)).to_be_visible(timeout=30000)
                            expect(page.get_by_test_id('plugin-page')).to_have_count(0)
                            self.check(shell+' revoked deep link does not render executable page',True)
                            path=self.output/(shell+'-revoked-deeplink.png');page.screenshot(path=str(path),full_page=True);self.report['screenshots'].append(str(path.relative_to(ROOT)))
                        finally:context.close()

                    # Disable does not create grants. Cascade is explicitly confirmed even after revocation.
                    self.lifecycle(admin,'ci.python','DISABLE','native-disable-closure')
                    current=admin.data('/fcp/ui/catalog')['catalog'];self.check('disable removes both plugin pages',not (set(packages)&{p['pluginId'] for p in current['pages']}))
                    status,body=admin.request('/fcp/capabilities/'+exported('ci.python')+'/invoke',dict(input={},expectedRevision=current['revision'],idempotencyKey='disabled-entry'))
                    self.check('disabled entry rejects invocation',status>=400 or str(body.get('code'))!='200')
                    for plugin in packages:
                        state=admin.data('/fcp/plugins/'+plugin);self.check(plugin+' durable desired state is disabled',state.get('desired')=='DISABLED')
                    existing_records=self.sql(f'USE `{self.database}`; SELECT COUNT(*) FROM fcp_plugin_record;').strip()
                    for plugin in packages:
                        state=admin.data('/fcp/plugins/'+plugin)
                        result=admin.data('/fcp/plugins/'+plugin+'/uninstall',dict(expectedRevision=state['revision'],confirmedDigest=packages[plugin]['digest']))
                        self.check(plugin+' explicit disabled uninstall retains installation history',admin.data('/fcp/plugins/'+plugin).get('installation')=='UNINSTALLED')
                    self.check('uninstall did not delete existing plugin-record rows',self.sql(f'USE `{self.database}`; SELECT COUNT(*) FROM fcp_plugin_record;').strip()==existing_records)
                    self.assert_pid(pid,pid_provider,'after grant revoke disable and uninstall');self.report.update(status='PASS',backendPidUnchanged='PASS',totalChecks=len(self.report['checks']),elapsedSeconds=round(time.monotonic()-started,3))
                finally:browser.close()
        except Exception as error:
            self.report.update(status='FAIL',error=safe_diagnostic(type(error).__name__+': '+str(error),self.login_fixture.diagnostic_secrets),elapsedSeconds=round(time.monotonic()-started,3));raise
        finally:self._write()
        return self.report


if __name__=='__main__':
    import argparse
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--package-output',type=Path);args=parser.parse_args()
    if args.package_output:
        args.package_output.mkdir(parents=True,exist_ok=True)
        for language in ('python','node'):
            package,manifest=package_bytes(language);path=args.package_output/(manifest['pluginId']+'.zip');path.write_bytes(package);print(json.dumps(dict(pluginId=manifest['pluginId'],sha256=hashlib.sha256(package).hexdigest(),bytes=len(package))))
    else:
        print(json.dumps(dict(status='NOT_RUN',reason='Run via tools/ci_native.py --ingestion with its genuine native login, disposable MySQL/Redis and real JVM fixture.')))
        raise SystemExit(2)
