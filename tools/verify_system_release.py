#!/usr/bin/env python3
"""Component release evidence, explicitly not native/production acceptance."""
import hashlib,json,subprocess,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
REPORT=ROOT/'reports/system-release'
def main():
    REPORT.mkdir(parents=True,exist_ok=True)
    result={'status':'FAIL','scope':'system-version and release component contracts; real loopback services where test declares', 'nativeRuoYi':'NOT_RUN','mysql':'NOT_RUN','browser':'NOT_RUN','production':'NOT_ACCEPTED','groups':[]}
    result['baselineCommit']=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
    result['workingTreeStatus']=subprocess.check_output(['git','status','--porcelain'],cwd=ROOT,text=True).splitlines()
    roots=['core/src/main/java/fcp/release','host/src/main/java/dev/fcp/host','integration/frontend-release','tests/system-version','tests/release-control','tests/release-routing','tests/release-deploy','tests/release-host','tests/frontend-release']
    result['sourceHashes']={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for tree in roots for p in sorted((ROOT/tree).rglob('*')) if p.is_file() and '__pycache__' not in p.parts}
    commands=[('versions',['bash','tests/system-version/run.sh']),('controller',['bash','tests/release-control/run.sh']),('routing',['bash','tests/release-routing/run.sh']),('deployment',['bash','tests/release-deploy/run.sh']),('host',['bash','tests/release-host/run.sh']),('frontend',['node','--experimental-strip-types','--test','tests/frontend-release/controller.test.mjs'])]
    try:
        for name,command in commands:
            with (REPORT/(name+'.log')).open('w') as log: done=subprocess.run(command,cwd=ROOT,stdout=log,stderr=subprocess.STDOUT,timeout=180)
            result['groups'].append({'name':name,'command':command,'exitCode':done.returncode})
            if done.returncode: raise RuntimeError(name+' failed')
        result['status']='PASS'
    except Exception as error: result['error']=str(error)
    (REPORT/'result.json').write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps({k:v for k,v in result.items() if k!='sourceHashes'},indent=2))
    return 0 if result['status']=='PASS' else 2
if __name__=='__main__':sys.exit(main())
