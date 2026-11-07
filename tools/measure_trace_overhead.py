#!/usr/bin/env python3
"""Disposable loopback development-host A/B benchmark. No native/database/production claim.
Uses synthetic private credentials in a temporary directory; never prints or exports them.
"""
import argparse,datetime,uuid,concurrent.futures,hashlib,http.client,io,json,math,os,platform,secrets,socket,subprocess,sys,tarfile,tempfile,time
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def main():
 parser=argparse.ArgumentParser();parser.add_argument('--baseline-ref',required=True);parser.add_argument('--requests',type=int,default=8000);parser.add_argument('--concurrency',type=int,default=4);args=parser.parse_args()
 if not 100<=args.requests<=20000 or not 1<=args.concurrency<=16:parser.error('Bounded benchmark sizes required')
 report={'status':'FAIL','scope':'loopback development GET /fcp/session, synthetic identity, no native RuoYi/MySQL/Redis/plugin workload','productionPerformance':'NOT_RUN','performanceAcceptance':'NOT_EVALUATED','rounds':[]}
 report['baselineCommit']=subprocess.check_output(['git','rev-parse',args.baseline_ref+'^{commit}'],cwd=ROOT,text=True).strip()
 report['measurementScriptSha256']=hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
 report['candidateCommit']=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
 report['candidateDirty']=subprocess.check_output(['git','status','--porcelain'],cwd=ROOT,text=True).splitlines()
 report['candidateSourceHashes']={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for folder in ['core/src','business/src','host/src','development-host/src'] for p in (ROOT/folder).rglob('*.java')}
 report['environment']={'platform':platform.platform(),'java':subprocess.check_output(['java','-version'],stderr=subprocess.STDOUT,text=True).splitlines()[0]}
 run_id=datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')+'-'+uuid.uuid4().hex[:8]
 output=ROOT/'reports/observability/http-overhead'/run_id;output.mkdir(parents=True,exist_ok=False)
 report['runId']=run_id;report['evidenceDirectory']=str(output.relative_to(ROOT))
 try:
  with tempfile.TemporaryDirectory(prefix='fcp-trace-ab-') as temp:
   temp=Path(temp);baseline=temp/'baseline';baseline.mkdir()
   archive=subprocess.check_output(['git','archive',report['baselineCommit']],cwd=ROOT)
   with tarfile.open(fileobj=io.BytesIO(archive)) as tar:tar.extractall(baseline,filter='data')
   for label,source in [('baseline',baseline),('candidate',ROOT)]:
    target=temp/(label+'-classes');target.mkdir()
    files=[str(p) for folder in ['core/src/main/java','business/src/main/java','host/src/main/java','development-host/src/main/java'] for p in (source/folder).rglob('*.java')]
    subprocess.run(['javac','--release','17','-d',str(target),*files],check=True,stdout=subprocess.DEVNULL,timeout=120)
   token=secrets.token_hex(32)
   config={'development':True,'trustedPublishers':['test'],'executables':{'python':sys.executable},'identities':[{'token':token,'userId':'synthetic-benchmark','tenantId':'trace-test','scopes':['tenant:trace-test'],'permissions':['fcp:plugin:view'],'grants':[]}]}
   private=temp/'identity.json';private.write_text(json.dumps(config));private.chmod(0o600)
   for index,label in enumerate(['baseline','candidate','candidate','baseline']):
    with socket.socket() as sock:sock.bind(('127.0.0.1',0));port=sock.getsockname()[1]
    # Keep the diagnostic sink real and separate from measurements; logs contain no credentials.
    with (output/f'http-{index}-{label}.log').open('w') as log:
     process=subprocess.Popen(['java','-cp',str(temp/(label+'-classes')),'dev.fcp.dev.DevHost','--development',str(private),str(temp/('state-'+str(index))),str(port)],stdout=log,stderr=log)
     def request():
      connection=http.client.HTTPConnection('127.0.0.1',port,timeout=5)
      try:
       start=time.perf_counter_ns();connection.request('GET','/fcp/session',headers={'Authorization':'Bearer '+token,'Connection':'close'})
       response=connection.getresponse();body=response.read();latency=(time.perf_counter_ns()-start)/1e6
       if response.status!=200 or json.loads(body).get('code')!=200:raise RuntimeError('Benchmark HTTP failure')
       if label=='candidate' and len(response.getheader('X-Trace-Id',''))!=32:raise RuntimeError('Candidate missing trace')
       return latency
      finally:connection.close()
     try:
      for _ in range(100):
       try:request();break
       except OSError:
        if process.poll() is not None:raise RuntimeError('Benchmark process exited')
        time.sleep(.05)
      else:raise RuntimeError('Benchmark startup timeout')
      for _ in range(1000):request()
      start=time.perf_counter()
      with concurrent.futures.ThreadPoolExecutor(max_workers=args.concurrency) as pool:latencies=list(pool.map(lambda _:request(),range(args.requests)))
      elapsed=time.perf_counter()-start;latencies.sort()
      pct=lambda p:latencies[math.ceil(len(latencies)*p)-1]
      report['rounds'].append({'label':label,'requests':args.requests,'concurrency':args.concurrency,'warmupRequests':1000,'elapsedSeconds':elapsed,'requestsPerSecond':args.requests/elapsed,'p50Ms':pct(.5),'p95Ms':pct(.95),'p99Ms':pct(.99),'failures':0})
     finally:
      process.terminate()
      try:process.wait(timeout=10)
      except subprocess.TimeoutExpired:process.kill();process.wait()
     log.flush()
     if label=='candidate':
      lines=(output/f'http-{index}-{label}.log').read_text().splitlines()
      report['rounds'][-1]['diagnosticEvents']=sum('"event":"http.request"' in line for line in lines)
      report['rounds'][-1]['expectedEvents']=args.requests+1001
      if report['rounds'][-1]['diagnosticEvents']!=args.requests+1001:raise RuntimeError('Diagnostic event loss in this benchmark; inspect retained logs')
   report['status']='PASS'
   report['interpretation']='Short same-machine alternating A/B rounds with fresh JVMs; includes HTTP/auth/file reads/log queue and JIT/noise. Inspect all rounds; do not infer a production latency budget or average percentiles.'
 except Exception as error:report['error']=type(error).__name__+': '+str(error)
 (output/'http-overhead.json').write_text(json.dumps(report,indent=2)+'\n')
 # Convenience latest snapshot; the matching raw logs remain immutable in its run-id directory.
 (ROOT/'reports/observability/http-overhead.json').write_text(json.dumps(report,indent=2)+'\n')
 print(json.dumps({k:v for k,v in report.items() if k!='candidateSourceHashes'},indent=2))
 return 0 if report['status']=='PASS' else 2
if __name__=='__main__':raise SystemExit(main())
