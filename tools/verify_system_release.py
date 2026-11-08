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
    roots=['core/src/main/java/fcp/release','host/src/main/java/dev/fcp/host','integration/frontend-release','tests/system-version','tests/release-control','tests/release-routing','tests/release-deploy','tests/release-host','tests/frontend-release','core/src/main/java/fcp/runtime/multiversion','integration/frontend-shared','tests/release-observation','tests/release-composition','tests/plugin-multiversion','tests/plugin-release-host','tests/plugin-multiversion-policy','tests/ui-release-assets','tests/ui-release-packaging']
    result['sourceHashes']={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for tree in roots for p in sorted((ROOT/tree).rglob('*')) if p.is_file() and '__pycache__' not in p.parts}
    commands=[('versions',['bash','tests/system-version/run.sh']),('controller',['bash','tests/release-control/run.sh']),('routing',['bash','tests/release-routing/run.sh']),('deployment',['bash','tests/release-deploy/run.sh']),('host',['bash','tests/release-host/run.sh']),('frontend',['node','--experimental-strip-types','--test','tests/frontend-release/controller.test.mjs','tests/frontend-release/observation.test.mjs','tests/frontend-release/observation-permission-host.test.mjs','tests/frontend-release/continuation-identity.test.mjs'])]
    commands += [(name,['bash',path]) for name,path in [('observation','tests/release-observation/run.sh'),('composition','tests/release-composition/run.sh'),('plugin-multiversion','tests/plugin-multiversion/run.sh'),('plugin-release-host','tests/plugin-release-host/run.sh'),('plugin-version-policy','tests/plugin-multiversion-policy/run.sh'),('ui-assets','tests/ui-release-assets/run.sh')]]
    commands += [('ui-packaging',['python3','tests/ui-release-packaging/verify.py']),('ui-session',['node','--experimental-strip-types','--test','tests/ui-release-assets/release-session.test.mjs','tests/ui-release-assets/ui-launcher.test.mjs']),('plugin-manager',['node','--experimental-strip-types','--test','tests/plugin-release-ui/controller.test.mjs','tests/plugin-release-ui/permission-host.test.mjs'])]
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
