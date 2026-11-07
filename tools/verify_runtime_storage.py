#!/usr/bin/env python3
"""Pure storage contract checks. Actual MySQL and native deployment are separate acceptance gates."""
import hashlib,json,subprocess,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def main():
 report={'status':'FAIL','scope':'Java storage contract and declaration checks only','mysql':'NOT_RUN','nativeDeployment':'NOT_RUN','groups':[]}
 output=ROOT/'reports/runtime-storage';output.mkdir(parents=True,exist_ok=True)
 build=ROOT/'build/runtime-storage';build.mkdir(parents=True,exist_ok=True)
 sources=sorted((ROOT/'core/src/main/java').rglob('*.java'))+sorted((ROOT/'tests/runtime-storage').glob('*.java'))+[ROOT/'host/src/main/java/dev/fcp/host/PluginPolicy.java',ROOT/'host/src/main/java/dev/fcp/host/JdbcPluginPolicy.java']
 report['baselineCommit']=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
 report['workingTreeStatus']=subprocess.check_output(['git','status','--porcelain'],cwd=ROOT,text=True).splitlines()
 report['sourceHashes']={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sources}
 try:
  for name,cmd in [('compile',['javac','--release','17','-d',str(build),*[str(p) for p in sources]]),('validation',['java','-cp',str(build),'StorageValidationTest']),('declarations',['java','-cp',str(build),'fcp.storage.StorageDeclarationTest'])]:
   with (output/(name+'.log')).open('w') as f:r=subprocess.run(cmd,cwd=ROOT,stdout=f,stderr=subprocess.STDOUT,timeout=120)
   report['groups'].append({'name':name,'exitCode':r.returncode})
   if r.returncode:raise RuntimeError(name+' failed')
  report['status']='PASS'
 except Exception as e:report['error']=str(e)
 (output/'result.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2));return 0 if report['status']=='PASS' else 2
if __name__=='__main__':raise SystemExit(main())
