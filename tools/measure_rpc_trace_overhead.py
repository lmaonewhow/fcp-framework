#!/usr/bin/env python3
"""Small alternating real Python/Node RPC A/B. No native/production performance acceptance."""
import argparse, datetime, hashlib, io, json, platform, shutil, subprocess, tarfile, tempfile, uuid
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def main():
 parser=argparse.ArgumentParser();parser.add_argument('--baseline-ref',required=True);args=parser.parse_args()
 result={'status':'FAIL','scope':'real synchronous Python/Node stdio, empty synthetic input, trace context + real stderr sink; no native host/database/business work', 'productionPerformance':'NOT_RUN','performanceAcceptance':'NOT_EVALUATED','rounds':[]}
 result['baselineCommit']=subprocess.check_output(['git','rev-parse',args.baseline_ref+'^{commit}'],cwd=ROOT,text=True).strip()
 result['candidateCommit']=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
 result['candidateDirty']=subprocess.check_output(['git','status','--porcelain'],cwd=ROOT,text=True).splitlines()
 result['candidateSourceHashes']={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in (ROOT/'core/src').rglob('*.java')}
 result['benchmarkHashes']={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in [Path(__file__).resolve(),ROOT/'tests/observability/TraceRpcBenchmark.java']}
 result['environment']={'platform':platform.platform(),'java':subprocess.check_output(['java','-version'],stderr=subprocess.STDOUT,text=True).strip(),'python':subprocess.check_output(['python3','--version'],text=True).strip(),'node':subprocess.check_output(['node','--version'],text=True).strip()}
 run_id=datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')+'-'+uuid.uuid4().hex[:8]
 output=ROOT/'reports/observability/rpc-overhead'/run_id;output.mkdir(parents=True,exist_ok=False);result['runId']=run_id
 try:
  with tempfile.TemporaryDirectory(prefix='fcp-rpc-ab-') as tmp:
   tmp=Path(tmp);baseline=tmp/'baseline';baseline.mkdir()
   with tarfile.open(fileobj=io.BytesIO(subprocess.check_output(['git','archive',result['baselineCommit']],cwd=ROOT))) as archive:archive.extractall(baseline,filter='data')
   for label,source in [('baseline',baseline),('candidate',ROOT)]:
    target=tmp/(label+'-classes');target.mkdir()
    command=['javac','--release','17','-d',str(target),*[str(p) for p in (source/'core/src/main/java').rglob('*.java')],str(ROOT/'tests/observability/TraceRpcBenchmark.java')]
    with (output/(label+'-compile.log')).open('w') as log:subprocess.run(command,check=True,stdout=log,stderr=subprocess.STDOUT,timeout=120)
   python=tmp/'worker.py';python.write_text("import sys,json\nfor line in sys.stdin:\n r=json.loads(line);print(json.dumps({'jsonrpc':'2.0','id':r['id'],'result':{'ok':True}}),flush=True)\n")
   node=tmp/'worker.mjs';node.write_text("import readline from 'node:readline';\nfor await(const line of readline.createInterface({input:process.stdin})){const r=JSON.parse(line);process.stdout.write(JSON.stringify({jsonrpc:'2.0',id:r.id,result:{ok:true}})+'\\n');}\n")
   for language,exe,worker in [('python',shutil.which('python3'),python),('node',shutil.which('node'),node)]:
    for index,label in enumerate(['baseline','candidate','candidate','baseline']):
     command=['java','-cp',str(tmp/(label+'-classes')),'TraceRpcBenchmark',exe,str(worker)]
     path=output/f'{language}-{index}-{label}'
     with path.with_suffix('.log').open('w') as log:
      completed=subprocess.run(command,cwd=ROOT,stdout=subprocess.PIPE,stderr=log,text=True,timeout=120)
     path.with_suffix('.json').write_text(completed.stdout)
     if completed.returncode:raise RuntimeError(str(path.name)+' exit='+str(completed.returncode))
     round_result=json.loads(completed.stdout);round_result.update({'language':language,'label':label,'command':command,'exitCode':completed.returncode})
     emitted=sum('"event":"plugin.rpc"' in line for line in path.with_suffix('.log').read_text().splitlines());round_result['diagnosticEvents']=emitted
     if emitted!=round_result['requests']+round_result['warmupRequests'] or round_result['telemetry']['dropped'] or round_result['telemetry']['sinkFailures']:raise RuntimeError('Diagnostic loss: '+path.name)
     result['rounds'].append(round_result)
   result['status']='PASS'
   result['interpretation']='Short fresh-JVM alternating A/B measurements, affected by scheduling/JIT/IO noise. PASS means measurement completed without event loss, not a latency or throughput acceptance threshold.'
 except Exception as error:result['error']=type(error).__name__+': '+str(error)
 (output/'result.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps({k:v for k,v in result.items() if k!='candidateSourceHashes'},indent=2));return 0 if result['status']=='PASS' else 2
if __name__=='__main__':raise SystemExit(main())
