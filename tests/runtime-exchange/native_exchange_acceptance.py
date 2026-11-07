"""Opt-in genuine native exchange acceptance. Never manufactures a login token.

Only isolated ci_native may instantiate this fixture. SQL seeds/revokes CI authority,
never business records. Packages, DDL, CSV/API imports and exports use real HTTP.
"""
from __future__ import annotations
import hashlib, io, json, os, re, sys, threading, time, urllib.error, urllib.parse, urllib.request, zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'tools'))
sys.path.insert(0,str(ROOT/'tests/runtime-native'))
from native_client import require_loopback
from native_runtime_acceptance import Client,RuntimeFixture,exported
from ci_ingestion import safe_diagnostic


SAME_OBJECT_CSV='student_code,full_name,class_code\ns1,Synthetic Alice,class-a\n'
def semantic_preview(preview):
    result=preview['result'];return {key:result[key] for key in ('rows','errors','valid')}


def package_bytes(plugin):
    if plugin not in ('roster','mock-exam'):raise ValueError('Unknown fixed fixture package')
    root=ROOT/'runtime/runtime-plugins'/plugin;manifest=json.loads((root/'plugin.json').read_text());output=io.BytesIO()
    with zipfile.ZipFile(output,'w',zipfile.ZIP_DEFLATED) as archive:
        for name in ('plugin.json','worker.py'):
            info=zipfile.ZipInfo(name,(2026,1,1,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;info.external_attr=0o100644<<16;archive.writestr(info,(root/name).read_bytes())
    return output.getvalue(),manifest


class ExchangeClient(Client):
    def binary(self,path,data=None):
        if not path.startswith('/fcp/exchange/') or any(c in path for c in ('\r','\n','#','\\')):raise ValueError('Fixed exchange path required')
        request=urllib.request.Request(self.base+path,data=data,headers={'Content-Type':'application/octet-stream','clientid':self.client_id,'Authorization':'Bearer '+self.token})
        try:
            with self.opener.open(request,timeout=30) as response:return response.status,response.headers,response.read(2*1024*1024)
        except urllib.error.HTTPError as error:return error.code,error.headers,error.read(2*1024*1024)
    def preview(self,config,csv):
        status,headers,data=self.binary('/fcp/exchange/configs/'+config+'/preview',csv.encode())
        body=json.loads(data)
        if status!=200 or str(body.get('code'))!='200':raise RuntimeError('Native CSV preview rejected: '+str(body.get('data',{}).get('fcpCode',status)))
        return body['data']
    def confirm_exchange(self,preview,key):
        return self.data('/fcp/exchange/previews/'+preview['previewId']+'/confirm',dict(digest=preview['digest'],idempotencyKey=key))


class ExchangeFixture:
    def __init__(self,database,sql,private_dir,login_fixture):
        if os.environ.get('GITHUB_ACTIONS')!='true' or not re.fullmatch(r'fcp_ci_[a-f0-9]{12}',database):raise RuntimeError('Exchange acceptance requires a new isolated CI database')
        if login_fixture is None or login_fixture.database!=database:raise RuntimeError('Matching genuine login fixture required')
        self.database,self.sql,self.private_dir,self.login_fixture=database,sql,private_dir,login_fixture
        self.output=ROOT/'reports/runtime-exchange-native';self.output.mkdir(parents=True,exist_ok=True)
        self.report=dict(status='NOT_RUN',production='NOT_ACCEPTED',scope='genuine RuoYi HTTP, MySQL, installed Python fixtures, fixed TASK scheduler',authorizationManagement='Explicit isolated CI SQL grants only; no production authorization endpoint claimed',checks=[],packages=[])
        self.requests=[];self.allow_second=False;owner=self
        class Handler(BaseHTTPRequestHandler):
            def log_message(self,*args):pass
            def do_GET(self):
                parsed=urllib.parse.urlsplit(self.path);cursor=urllib.parse.parse_qs(parsed.query,keep_blank_values=True).get('cursor',[''])[0]
                if parsed.path!='/campus':self.send_error(404);return
                owner.requests.append(cursor)
                if cursor=='task-page2' and not owner.allow_second:self.send_error(503);return
                if cursor not in ('','manual','task-page2','same-object'):self.send_error(400);return
                code={'':'task1','manual':'api1','task-page2':'task2','same-object':'s1'}[cursor]
                payload=json.dumps(dict(content=SAME_OBJECT_CSV if cursor=='same-object' else 'student_code,full_name,class_code\n'+code+',Synthetic '+code+',class-a\n',nextCursor='task-page2' if cursor=='' else None)).encode()
                self.send_response(200);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(payload)));self.end_headers();self.wfile.write(payload)
        self.server=ThreadingHTTPServer(('127.0.0.1',0),Handler);self.thread=threading.Thread(target=self.server.serve_forever,daemon=True);self.thread.start()
        self.endpoint='http://127.0.0.1:'+str(self.server.server_port)+'/campus'
        # Fixture-only role/class seed. No plugin installation, data write or default grant bypass.
        sql(f"USE `{database}`; INSERT INTO sys_role(role_id,tenant_id,role_name,role_key,role_sort,data_scope,status,del_flag,create_by,create_time) VALUES(950530,'000000','CI exchange operator','fcp_ci_exchange',95,'5','0','0',1,NOW()); INSERT INTO sys_user_role(user_id,role_id) VALUES(1,950530); INSERT IGNORE INTO fcp_eval_assignment VALUES('tenant-000000','1','class-a');")
        for offset,permission in enumerate(('exchange.import','exchange.read','storage.read','storage.write','exchange.operator')):
            menu=950540+offset
            sql(f"USE `{database}`; INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,menu_type,perms,icon,is_frame,is_cache,visible,status,create_by,create_time) VALUES({menu},'CI exchange {offset}',942020,99,'',NULL,'F','fcp:plugin:{permission}','#',1,1,'0','0',1,NOW()); INSERT INTO sys_role_menu(role_id,menu_id) VALUES(950530,{menu});")
        self._write()
    def properties(self):
        return '\nfcp.exchange.enabled=true\nfcp.exchange.schedules.enabled=true\nFCP_EXCHANGE_FIXTURE_CLASS=class-a\nFCP_EXCHANGE_FIXTURE_TENANT=000000\nFCP_EXCHANGE_FIXTURE_INTERVAL_SECONDS=5\nFCP_EXCHANGE_CAMPUS_FIXTURE_API='+self.endpoint+'\n'
    def _write(self): (self.output/'acceptance.json').write_text(json.dumps(self.report,ensure_ascii=False,indent=2)+'\n')
    def check(self,label,condition):
        if not condition:raise AssertionError(label)
        self.report['checks'].append(dict(name=label,status='PASS'));self._write()
    def seed_exchange_authority(self,plugin,digest):
        if plugin not in ('roster','mock-exam') or not re.fullmatch('[a-f0-9]{64}',digest):raise ValueError('Verified fixture package required')
        for op in ('import','export','task'):
            self.sql(f"USE `{self.database}`; INSERT INTO fcp_runtime_grant(tenant_id,plugin_id,capability_id,operation,permission,data_scope,package_digest,revision,del) VALUES('000000','{plugin}','exchange','{op}','fcp:plugin:exchange.operator','class:class-a','{digest}',1,0);")
    def task_authority(self,digest):
        if not re.fullmatch('[a-f0-9]{64}',digest):raise ValueError('Digest required')
        # Enumerate exact task rights; never clone administrator rights or write a broad wildcard.
        rights=[('exchange','task','class:class-a'),('exchange','import','class:class-a'),('storage','read','class:class-a'),('storage','write','class:class-a'),('exchange.preview','invoke','tenant:000000'),('exchange.commit','invoke','tenant:000000')]
        for cap,op,scope in rights:
            self.sql(f"USE `{self.database}`; INSERT INTO fcp_runtime_task_grant(tenant_id,task_id,plugin_id,capability_id,operation,data_scope,package_digest,revision,del,expires_at) VALUES('000000','roster-fixture-sync','roster','{cap}','{op}','{scope}','{digest}',1,0,DATE_ADD(NOW(),INTERVAL 1 HOUR)) ON DUPLICATE KEY UPDATE package_digest=VALUES(package_digest),revision=revision+1,del=0,expires_at=VALUES(expires_at);")
    def revoke_task(self):self.sql(f"USE `{self.database}`; UPDATE fcp_runtime_task_grant SET del=1,revision=revision+1 WHERE tenant_id='000000' AND task_id='roster-fixture-sync' AND plugin_id='roster';")
    def export_authority(self,allow):self.sql(f"USE `{self.database}`; UPDATE fcp_runtime_grant SET del={0 if allow else 1},revision=revision+1 WHERE tenant_id='000000' AND plugin_id='roster' AND capability_id='exchange' AND operation='export' AND data_scope='class:class-a';")
    def import_authority(self,allow):self.sql(f"USE `{self.database}`; UPDATE fcp_runtime_grant SET del={0 if allow else 1},revision=revision+1 WHERE tenant_id='000000' AND plugin_id='roster' AND capability_id='exchange' AND operation='import' AND data_scope='class:class-a';")
    def lifecycle(self,client,plugin,action,key):
        status=client.data('/fcp/plugins/'+plugin);plan=client.data('/fcp/plugins/'+plugin+'/plans',dict(action=action,expectedRevision=status['revision']));result=RuntimeFixture.confirm(client,plan,key);self.check(plugin+' '+action+' confirmed',result.get('phase')=='COMPLETE')
    def export_csv(self,client,config='roster-fixture',fields=None):
        handle=client.data('/fcp/exchange/configs/'+config+'/exports',dict(fields=fields or ['student_code']))
        status,headers,body=client.binary('/fcp/exchange/downloads/'+handle['handle'])
        if status!=200:raise RuntimeError('Native controlled download failed')
        return handle,body.decode(),headers
    def wait_rows(self,client,needle,timeout=30):
        until=time.monotonic()+timeout
        while time.monotonic()<until:
            if needle in self.export_csv(client)[1]:return True
            time.sleep(.5)
        return False
    def assert_no_fetch(self,label):
        time.sleep(.75);before=len(self.requests);time.sleep(11);self.check(label,len(self.requests)==before)
    def verify(self,base,build_id,pid_provider):
        require_loopback(base)
        if os.environ.get('FCP_ACCEPTANCE_DATABASE')!=self.database:raise RuntimeError('Acceptance database mismatch')
        from playwright.sync_api import sync_playwright
        started=time.monotonic();pid=pid_provider();self.report.update(status='RUNNING',buildId=build_id,nativeJavaPid=pid);self._write()
        try:
            with sync_playwright() as playwright:
                browser=playwright.chromium.launch()
                try:
                    state=self.login_fixture.login(browser,base,'admin');client=ExchangeClient(base,RuntimeFixture.token(state,base),'e5cd7e4891bf95d1d19206ce24a7b32e')
                    self.check('genuine pinned RuoYi MySQL build',client.evidence().get('buildId')==build_id)
                    status,body=client.request('/fcp/exchange/configs/roster-fixture/exports',dict(fields=['student_code']),token=False);self.check('exchange requires genuine native token',status>=400 or str(body.get('code'))!='200')
                    self.check('separate deployment identity explicitly configured',client.data('/fcp/storage/deployment')['configured'] is True)
                    digests={}
                    for plugin in ('roster','mock-exam'):
                        package,manifest=package_bytes(plugin);digest=hashlib.sha256(package).hexdigest();digests[plugin]=digest
                        staged=client.upload(package,digest,'exchange-stage-'+plugin,staged=True);self.check(plugin+' immutable package staged before DDL',staged['digest']==digest)
                        plan=client.data('/fcp/plugins/'+plugin+'/storage/plans',dict(version='1.0.0',packageDigest=digest));self.check(plugin+' immutable declared schema plan',plan['packageDigest']==digest and bool(plan['changes']))
                        result=client.data('/fcp/storage/plans/'+plan['planId']+'/apply',dict(digest=plan['digest'],idempotencyKey='exchange-schema-'+plugin));self.check(plugin+' declared schema applied by deployment identity',result['phase']=='COMPLETE')
                        installed=client.upload(package,digest,'exchange-install-'+plugin,client.data('/fcp/plugins')['revision']);self.check(plugin+' package installed only after declared schema applies',installed['digest']==digest)
                        options=client.data('/fcp/plugins/'+plugin+'/grant-options?targetTenant=000000')
                        grants=[dict(capabilityId=c['id'],dataScope='class:class-a' if c['id'].startswith('host.storage.') else 'tenant:000000',allow=True) for c in manifest['provides']+manifest['requires']]
                        plan=client.data('/fcp/plugins/'+plugin+'/grants/plans',dict(targetTenant='000000',expectedRevision=options['revision'],grants=grants));self.check(plugin+' exact platform grants confirmed over HTTP',RuntimeFixture.confirm(client,plan,'exchange-grants-'+plugin)['phase']=='COMPLETE')
                        self.seed_exchange_authority(plugin,digest);self.lifecycle(client,plugin,'ENABLE','exchange-enable-'+plugin)
                        self.report['packages'].append(dict(plugin=plugin,sha256=digest,bytes=len(package)))
                    status,body=client.request('/fcp/exchange/configs/roster-fixture/preview-api',dict(cursor='manual',url='http://127.0.0.1:1/forbidden'));self.check('client cannot supply connector URL',status==400)
                    catalog=client.data('/fcp/ui/catalog')['catalog']
                    for operation in ('exchange.read','exchange.preview','exchange.commit'):
                        status,body=client.request('/fcp/capabilities/'+exported('roster',operation)+'/invoke',dict(input={},expectedRevision=catalog['revision'],idempotencyKey='no-bypass-'+operation));self.check('generic '+operation+' cannot bypass controlled exchange',status==409 and body.get('data',{}).get('fcpCode')=='CONFIRMATION_REQUIRED')
                    preview=client.preview('roster-fixture',SAME_OBJECT_CSV);self.check('CSV preview validates without writing',preview['result']['valid'] and 's1' not in self.export_csv(client)[1])
                    same_api=client.data('/fcp/exchange/configs/roster-fixture/preview-api',dict(cursor='same-object'));self.check('identical CSV bytes through file and fixed API normalize identically',semantic_preview(preview)==semantic_preview(same_api))
                    receipt=client.confirm_exchange(preview,'native-csv');self.check('CSV confirmed through actual plugin storage',receipt['committed']);self.check('same confirmation receipt replay',client.confirm_exchange(preview,'native-csv')==receipt)
                    same_file_after=client.preview('roster-fixture',SAME_OBJECT_CSV);same_api_after=client.data('/fcp/exchange/configs/roster-fixture/preview-api',dict(cursor='same-object'));self.check('identical object becomes the same conflict through both entries',semantic_preview(same_file_after)==semantic_preview(same_api_after) and 'CONFLICT' in {r['code'] for r in same_api_after['result']['errors']})
                    status,body=client.request('/fcp/exchange/previews/'+same_api['previewId']+'/confirm',dict(digest=same_api['digest'],idempotencyKey='same-object-api'));self.check('old API preview cannot overwrite file-committed object',status==409 and body.get('data',{}).get('fcpCode')=='CONFLICT')
                    bad=client.preview('roster-fixture','student_code,full_name,class_code\ns1,Overwrite,class-a\ndup,One,class-a\ndup,Two,class-a\nwrong,Denied,other\n');codes={r['code'] for r in bad['result']['errors']};self.check('duplicate invalid scope conflict row details',{'DUPLICATE','CONFLICT','CLASS_SCOPE_DENIED'}<=codes)
                    status,body=client.request('/fcp/exchange/previews/'+bad['previewId']+'/confirm',dict(digest=bad['digest'],idempotencyKey='reject-bad'));self.check('invalid batch cannot confirm',status==409);self.check('conflicting row not overwritten','Synthetic Alice' in self.export_csv(client,fields=['full_name'])[1] and 'Overwrite' not in self.export_csv(client,fields=['full_name'])[1])
                    self.import_authority(False);before_denied_fetch=len(self.requests)
                    status,headers,body=client.binary('/fcp/exchange/configs/roster-fixture/preview',SAME_OBJECT_CSV.encode());self.check('shared import revocation denies file entry',status==403)
                    status,body=client.request('/fcp/exchange/configs/roster-fixture/preview-api',dict(cursor='same-object'));self.check('shared import revocation denies fixed API before source fetch',status==403 and len(self.requests)==before_denied_fetch);self.import_authority(True)
                    api=client.data('/fcp/exchange/configs/roster-fixture/preview-api',dict(cursor='manual'));self.check('fixed loopback API preview',api['result']['valid']);self.check('API explicit confirmation',client.confirm_exchange(api,'native-api')['committed'])
                    handle,text,headers=self.export_csv(client);self.check('controlled export field projection and no-store','api1' in text and 'Synthetic' not in text and 'no-store' in headers.get('Cache-Control',''))
                    self.export_authority(False);status,headers,body=client.binary('/fcp/exchange/downloads/'+handle['handle']);self.check('existing download denied after live export revocation',status==403);self.export_authority(True)
                    exam=client.preview('exam-fixture','exam_code,title,class_code\ne1,Synthetic Practice,class-a\n');self.check('second plugin independent commit',client.confirm_exchange(exam,'native-exam')['committed']);self.check('second plugin independent controlled export','e1' in self.export_csv(client,'exam-fixture',['exam_code'])[1])
                    self.assert_no_fetch('scheduler without task grants performs no source I/O')
                    self.task_authority(digests['roster']);self.check('independent TASK imports first page',self.wait_rows(client,'task1'))
                    self.revoke_task();self.assert_no_fetch('revoked TASK cannot fetch or advance cursor')
                    self.task_authority('0'*64);self.assert_no_fetch('wrong package_digest task authority rejected independently')
                    self.revoke_task();self.lifecycle(client,'roster','DISABLE','exchange-disable-task');self.task_authority(digests['roster']);self.assert_no_fetch('disabled plugin rejects task before source I/O')
                    self.lifecycle(client,'roster','ENABLE','exchange-reenable-task');self.allow_second=True;self.check('same durable cursor resumes remaining page',self.wait_rows(client,'task2'));self.assert_no_fetch('completed finite task does not reimport pages')
                    # Read-only MySQL evidence of actual TASK identity, never a direct business write.
                    task_audits=self.sql(f"USE `{self.database}`; SELECT COUNT(*) FROM fcp_plugin_storage_audit WHERE plugin_id='roster' AND principal_kind='TASK' AND principal_id='roster-fixture-sync';").strip();self.check('real MySQL write audit has TASK not administrator',int(task_audits)>=2)
                    self.check('native JVM unchanged for second plugin and task workflow',pid_provider()==pid)
                finally:browser.close()
            self.report.update(status='PASS',totalChecks=len(self.report['checks']),elapsedSeconds=round(time.monotonic()-started,3));self._write();return self.report
        except Exception as error:
            self.report.update(status='FAIL',error=safe_diagnostic(str(error),[self.login_fixture.password]),totalChecks=len(self.report['checks']));self._write();raise
    def close(self):self.server.shutdown();self.server.server_close();self.thread.join(timeout=5)

if __name__=='__main__':
    print(json.dumps(dict(status='NOT_RUN',reason='Instantiate only through ci_native --ingestion --exchange with genuine native login and disposable MySQL')));raise SystemExit(2)
