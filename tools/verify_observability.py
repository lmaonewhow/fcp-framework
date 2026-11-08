#!/usr/bin/env python3
"""Exact source-bound correlation checks. Not native acceptance or an OTel backend claim."""
import hashlib,json,os,subprocess,sys,time
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def main():
 report=ROOT/'reports/observability';report.mkdir(parents=True,exist_ok=True)
 build=ROOT/'build/observability';build.mkdir(parents=True,exist_ok=True)
 result={'status':'FAIL','nativeRuoYi':'NOT_RUN','collector':'NOT_IMPLEMENTED','productionPerformance':'NOT_RUN','groups':[]}
 result['commit']=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
 result['dirty']=subprocess.check_output(['git','status','--porcelain'],cwd=ROOT,text=True).splitlines()
 result['sourceHashes']={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for folder in ['core/src','runtime','integration/ruoyi','tests/observability','tests/runtime-lifecycle/plugins'] for p in (ROOT/folder).rglob('*') if p.is_file() and '__pycache__' not in p.parts}
 commands=[('compile',['javac','--release','17','-d',str(build),*[str(p) for folder in ['core/src/main/java','tests/observability','tests/runtime-lifecycle/plugins'] for p in sorted((ROOT/folder).rglob('*.java'))]]),('context',['java','-cp',str(build),'TraceContextTest']),('queue',['java','-cp',str(build),'TraceRecorderTest']),('rpc-summary',['java','-cp',str(build),'TraceRpcSummaryTest']),('release-summary',['java','-cp',str(build),'TraceReleaseSummaryTest']),('rpc',['java','-cp',str(build),'TraceRpcTest',__import__('shutil').which('python3'),__import__('shutil').which('node')]),('python',[sys.executable,'-m','unittest','discover','-s','tests/observability','-p','test_trace.py','-v']),('microbenchmark',['java','-cp',str(build),'TraceBenchmark'])]
 try:
  for name,cmd in commands:
   start=time.monotonic()
   with (report/(name+'.log')).open('w') as output: done=subprocess.run(cmd,cwd=ROOT,stdout=output,stderr=subprocess.STDOUT,timeout=120)
   result['groups'].append({'name':name,'command':cmd,'exitCode':done.returncode,'elapsedSeconds':time.monotonic()-start})
   if done.returncode:raise RuntimeError(name+' failed; inspect raw log')
  result['status']='PASS'
 except Exception as error:result['error']=str(error)
 (report/'result.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps({k:v for k,v in result.items() if k!='sourceHashes'},indent=2));return 0 if result['status']=='PASS' else 2
if __name__=='__main__':raise SystemExit(main())
