#!/usr/bin/env python3
"""Real process and loopback HTTP verification; never represents native RuoYi/MySQL/browser acceptance."""
import hashlib,json,os,subprocess,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
REPORT=ROOT/'reports/runtime-lifecycle'
def main():
 REPORT.mkdir(parents=True,exist_ok=True)
 report={'status':'FAIL','scope':'actual Java Engine, Python/Node processes and loopback development HTTP','nativeRuoYi':'NOT_RUN','mysql':'NOT_RUN','browser':'NOT_RUN','groups':[]}
 build=ROOT/'build/runtime-http';build.mkdir(parents=True,exist_ok=True)
 commands=[('lifecycle',['bash','tests/runtime-lifecycle/run.sh']),('compile-http',['javac','--release','17','-encoding','UTF-8','-d',str(build),*[str(p) for tree in ['core/src/main/java','business/src/main/java','host/src/main/java','development-host/src/main/java','tests/runtime-http/recovery'] for p in sorted((ROOT/tree).rglob('*.java'))]]),('recovery-backoff',['java','-cp',str(build),'dev.fcp.host.RecoveryBackoffTest']),('http',[sys.executable,'tests/runtime-http/test_dev_host.py'])]
 try:
  report['baselineCommit']=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
  report['workingTreeStatus']=subprocess.check_output(['git','status','--porcelain'],cwd=ROOT,text=True).splitlines()
  report['sourceHashes']={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for tree in ['core/src/main/java','business/src/main/java','host/src/main/java','development-host/src/main/java','tests/runtime-lifecycle','tests/runtime-http'] for p in sorted((ROOT/tree).rglob('*')) if p.is_file() and 'evidence' not in p.parts}
  for name,command in commands:
   with (REPORT/(name+'.log')).open('w') as log:result=subprocess.run(command,cwd=ROOT,stdout=log,stderr=subprocess.STDOUT,timeout=180)
   report['groups'].append({'name':name,'exitCode':result.returncode,'command':command})
   if result.returncode:raise RuntimeError(name+' failed')
  report['status']='PASS'
 except Exception as e:report['error']=str(e)
 (REPORT/'result.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2));return 0 if report['status']=='PASS' else 2
if __name__=='__main__':raise SystemExit(main())
