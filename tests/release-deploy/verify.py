#!/usr/bin/env python3
"""Evidence for concrete local process executor, not native or production acceptance."""
import hashlib,json,subprocess,sys
from pathlib import Path
root=Path(__file__).resolve().parents[2]
report=root/'reports/release-deploy';report.mkdir(parents=True,exist_ok=True)
command=['bash','tests/release-deploy/run.sh']
with (report/'test.log').open('w') as log:
    result=subprocess.run(command,cwd=root,stdout=log,stderr=subprocess.STDOUT)
files=[p for base in ['core/src/main/java/fcp/release','core/src/main/java/fcp/control','core/src/main/java/fcp/host','tests/release-deploy'] for p in (root/base).rglob('*') if p.is_file()]
evidence={'status':'PASS' if result.returncode==0 else 'FAIL','command':command,'exitCode':result.returncode,'baselineCommit':subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip(),'workingTreeStatus':subprocess.check_output(['git','status','--porcelain'],cwd=root,text=True).splitlines(),'sourceHashes':{str(p.relative_to(root)):hashlib.sha256(p.read_bytes()).hexdigest() for p in files},'scope':'Real Java controller + actual Python stable/candidate processes + signed HTTP requests and durable routing','nativeRuoYi':'NOT_RUN','mysql':'NOT_RUN','browser':'NOT_RUN','production':'NOT_ACCEPTED'}
(report/'result.json').write_text(json.dumps(evidence,indent=2)+'\n')
print(json.dumps({k:v for k,v in evidence.items() if k not in ['sourceHashes','workingTreeStatus']},indent=2))
sys.exit(result.returncode)
