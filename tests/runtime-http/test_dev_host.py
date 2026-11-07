"""Real loopback HTTP + Java host + verified ZIP + Python process. Not RuoYi auth."""
import hashlib,json,os,secrets,socket,subprocess,tempfile,time,unittest,urllib.request,urllib.error,zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
class DevHostTest(unittest.TestCase):
 def test_install_publish_invoke_disable(self):
  with tempfile.TemporaryDirectory(prefix='fcp-http-') as tmp:
   work=Path(tmp);token=secrets.token_hex(32)
   config={'development':True,'trustedPublishers':['test'],'executables':{'python':os.environ.get('FCP_TEST_PYTHON',__import__('sys').executable)},'identities':[{'token':token,'userId':'admin','tenantId':'t1','scopes':['tenant:t1'],'permissions':['fcp:plugin:install','fcp:plugin:manage','fcp:plugin:view','fcp:plugin:echo'],'grants':[{'plugin':'test.echo','capability':'echo','operation':'invoke','dataScope':'tenant:t1','permission':'fcp:plugin:echo'}]}]}
   private=work/'identities.json';private.write_text(json.dumps(config));private.chmod(0o600)
   cap={'id':'echo','version':'1.0.0','permission':'echo','sideEffects':False,'idempotent':False,'timeoutMillis':2000,'inputSchema':{'type':'object'},'outputSchema':{'type':'object'}}
   manifest={'manifestVersion':1,'pluginId':'test.echo','version':'1.0.0','publisher':'test','contractVersion':'1.0','runtime':{'type':'local','entry':'runtime.py','argv':[]},'provides':[cap],'ui':{'contractVersion':'1.0','pages':[{'id':'home','title':'Echo','permissions':['echo'],'components':['button@1'],'capabilities':['echo'],'content':{'type':'button','text':'Echo','capabilityId':'echo'}}],'slots':[]}}
   package=work/'test.zip'
   with zipfile.ZipFile(package,'w',zipfile.ZIP_DEFLATED) as z:z.writestr('plugin.json',json.dumps(manifest));z.write(ROOT/'tests/runtime-lifecycle/plugins/runtime.py','runtime.py')
   with socket.socket() as s:s.bind(('127.0.0.1',0));port=s.getsockname()[1]
   log=(work/'server.log').open('w')
   process=subprocess.Popen(['java','-cp',str(ROOT/'build/runtime-http'),'dev.fcp.dev.DevHost','--development',str(private),str(work/'state'),str(port)],stdout=log,stderr=log)
   last_trace=[]
   def request(path,body=None,headers=None,auth=True):
    h={'Authorization':'Bearer '+token} if auth else {}
    h.update(headers or {})
    if isinstance(body,dict):body=json.dumps(body).encode();h['Content-Type']='application/json'
    req=urllib.request.Request(f'http://127.0.0.1:{port}'+path,data=body,headers=h)
    with urllib.request.urlopen(req,timeout=15) as r:
     self.assertRegex(r.headers.get('X-Trace-Id',''),r'^[0-9a-f]{32}$');last_trace[:]=[r.headers['X-Trace-Id']]
     return json.load(r)['data']
   succeeded=False
   try:
    for _ in range(100):
     try:request('/fcp/ui/catalog');break
     except (OSError,ValueError):
      if process.poll() is not None:self.fail('Development server failed; inspect private temp log')
      time.sleep(.05)
    else:self.fail('Development server start timeout')
    with self.assertRaises(urllib.error.HTTPError) as e:request('/fcp/ui/catalog',auth=False)
    self.assertEqual(e.exception.code,401)
    failure_trace=e.exception.headers.get('X-Trace-Id','');self.assertRegex(failure_trace,r'^[0-9a-f]{32}$')
    self.assertEqual(json.load(e.exception)['data']['traceId'],failure_trace)
    # Public headers never pick a host trace, including valid-looking W3C IDs.
    spoof='00-'+'1'*32+'-'+'2'*16+'-01'
    req=urllib.request.Request(f'http://127.0.0.1:{port}/fcp/session',headers={'Authorization':'Bearer '+token,'traceparent':spoof})
    with urllib.request.urlopen(req) as r:
     self.assertRegex(r.headers['X-Trace-Id'],r'^[0-9a-f]{32}$');self.assertNotEqual(r.headers['X-Trace-Id'],'1'*32)
    data=package.read_bytes();headers={'Content-Type':'application/octet-stream','X-Fcp-Digest':hashlib.sha256(data).hexdigest(),'Idempotency-Key':'install-one','X-Fcp-Revision':'0'}
    installed=request('/fcp/packages',data,headers);self.assertEqual(installed['state'],'INSTALLED')
    replay=request('/fcp/packages',data,headers);self.assertTrue(replay['replayed'])
    self.assertEqual(request('/fcp/ui/catalog')['catalog']['pages'],[])
    status=request('/fcp/plugins/test.echo')
    plan=request('/fcp/plugins/test.echo/plans',{'action':'enable','expectedRevision':status['revision']})
    result=request('/fcp/plans/'+plan['planId']+'/confirm',{'digest':plan['digest'],'affectedPlugins':plan['affectedPlugins'],'idempotencyKey':'enable-one'})
    self.assertEqual(result['outcome'],'APPLIED')
    catalog=request('/fcp/ui/catalog')['catalog'];self.assertEqual(len(catalog['pages']),1)
    export=catalog['pages'][0]['capabilities'][0]
    response=request('/fcp/capabilities/'+export+'/invoke',{'input':{'message':'hello','tenantId':'forged'},'expectedRevision':catalog['revision'],'idempotencyKey':'read-one'})
    self.assertEqual(response['tenant'],'t1')
    self.assertEqual(response['traceId'],last_trace[0]);self.assertEqual(response['traceparent'][3:35],last_trace[0])
    # Live config revocation is enforced on both the old endpoint and the next catalog.
    granted=config['identities'][0]['grants'];config['identities'][0]['grants']=[];private.write_text(json.dumps(config))
    with self.assertRaises(urllib.error.HTTPError) as revoked:request('/fcp/capabilities/'+export+'/invoke',{'input':{},'expectedRevision':catalog['revision']})
    self.assertEqual(revoked.exception.code,403)
    self.assertEqual(request('/fcp/ui/catalog')['catalog']['pages'],[])
    config['identities'][0]['grants']=granted;private.write_text(json.dumps(config))
    # Explicit whole-host restart recovers persistent desired state using restricted infrastructure identity.
    process.terminate();process.wait(timeout=10)
    process=subprocess.Popen(['java','-cp',str(ROOT/'build/runtime-http'),'dev.fcp.dev.DevHost','--development',str(private),str(work/'state'),str(port)],stdout=log,stderr=log)
    for _ in range(100):
     try:
      recovered=request('/fcp/ui/catalog')['catalog']
      if recovered['pages']:break
     except OSError:pass
     time.sleep(.05)
    else:self.fail('Persisted enabled catalog did not recover')
    self.assertEqual(len(recovered['pages']),1)
    saved=request('/fcp/plans/'+plan['planId']+'/confirm',{'digest':plan['digest'],'affectedPlugins':plan['affectedPlugins'],'idempotencyKey':'enable-one'})
    self.assertEqual(saved['operationId'],result['operationId'])
    self.assertEqual(saved['outcome'],'APPLIED')
    with self.assertRaises(urllib.error.HTTPError) as conflict:
     request('/fcp/plans/'+plan['planId']+'/confirm',{'digest':'0'*64,'affectedPlugins':plan['affectedPlugins'],'idempotencyKey':'enable-one'})
    self.assertEqual(conflict.exception.code,409)
    self.assertEqual(process.poll(),None)
    status=request('/fcp/plugins/test.echo');plan=request('/fcp/plugins/test.echo/plans',{'action':'disable','expectedRevision':status['revision']})
    request('/fcp/plans/'+plan['planId']+'/confirm',{'digest':plan['digest'],'affectedPlugins':plan['affectedPlugins'],'idempotencyKey':'disable-one'})
    self.assertEqual(request('/fcp/ui/catalog')['catalog']['pages'],[])
    with self.assertRaises(urllib.error.HTTPError):request('/fcp/capabilities/'+export+'/invoke',{'input':{},'expectedRevision':catalog['revision']})
    succeeded=True
   finally:
    if not succeeded or os.environ.get("FCP_TEST_RECOVERY_DIAGNOSTICS")=="1":
     log.flush()
     print("RECOVERY DIAGNOSTICS pid_exit="+str(process.poll()),flush=True)
     print((work/"server.log").read_text().replace(token,"[REDACTED]").replace(str(work),"[TEMP]"),flush=True)
     journal=work/"state/control/lifecycle.json"
     if journal.exists():
      state=json.loads(json.loads(journal.read_text())["json"])
      print("RECOVERY STATE "+json.dumps({k:state.get(k) for k in ("revision","tenants","deployments")}),flush=True)
    process.terminate()
    try:process.wait(timeout=10)
    except subprocess.TimeoutExpired:process.kill();process.wait()
    log.close()
if __name__=='__main__':unittest.main()
