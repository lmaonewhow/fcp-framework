#!/usr/bin/env python3
"""Component evidence: actual static HTTP, filesystem and JS state; not native browser acceptance."""
import hashlib,json,subprocess,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2];out=ROOT/'reports/ui-release-assets';out.mkdir(parents=True,exist_ok=True)
result={'status':'FAIL','nativeRuoYi':'NOT_RUN','mysql':'NOT_RUN','browser':'NOT_RUN','production':'NOT_ACCEPTED','groups':[],'baselineCommit':subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),'workingTreeStatus':subprocess.check_output(['git','status','--porcelain'],cwd=ROOT,text=True).splitlines()}
roots=['host/src/main/java/dev/fcp/host/ui','integration/frontend-shared','integration/ruoyi/src/main/java/org/dromara/fcp/release','tests/ui-release-assets','tests/ui-release-packaging']
result['sourceHashes']={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for root in roots for p in sorted((ROOT/root).rglob('*')) if p.is_file() and '__pycache__' not in p.parts}
try:
 for name,cmd in [('assets',['bash','tests/ui-release-assets/run.sh']),('session',['node','--experimental-strip-types','--test','tests/ui-release-assets/release-session.test.mjs']),('packaging',[sys.executable,'-m','unittest','discover','-s','tests/ui-release-packaging','-v'])]:
  with (out/(name+'.log')).open('w') as log:r=subprocess.run(cmd,cwd=ROOT,stdout=log,stderr=subprocess.STDOUT,timeout=180)
  result['groups'].append({'name':name,'command':cmd,'exitCode':r.returncode})
  if r.returncode:raise RuntimeError(name+' failed')
 result['status']='PASS'
except Exception as error:result['error']=str(error)
(out/'result.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps({k:v for k,v in result.items() if k not in ('sourceHashes','workingTreeStatus')},indent=2));sys.exit(0 if result['status']=='PASS' else 2)
