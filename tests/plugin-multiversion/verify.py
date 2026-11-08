#!/usr/bin/env python3
"""Capture exact component evidence; native acceptance remains a separate gate."""
import hashlib,json,subprocess
from pathlib import Path
root=Path(__file__).resolve().parents[2]
out=root/'reports/plugin-multiversion';out.mkdir(parents=True,exist_ok=True)
paths=[root/'core/src/main/java/fcp/runtime/EngineRuntimePort.java',root/'core/src/main/java/fcp/host/HostCommitFence.java',*list((root/'core/src/main/java/fcp/runtime/multiversion').glob('*.java')),*list((root/'tests/plugin-multiversion').glob('*'))]
report={'status':'FAIL','native':'NOT_RUN','mysql':'NOT_RUN','browser':'NOT_RUN','production':'NOT_ACCEPTED','baseline':subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip(),'sourceHashes':{str(p.relative_to(root)):hashlib.sha256(p.read_bytes()).hexdigest() for p in paths if p.is_file()}}
with (out/'verified.log').open('w') as log:result=subprocess.run(['bash','tests/plugin-multiversion/run.sh'],cwd=root,stdout=log,stderr=subprocess.STDOUT,timeout=120)
report['exitCode']=result.returncode
if result.returncode==0:report['status']='PASS'
(out/'result.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps({k:v for k,v in report.items() if k!='sourceHashes'}));raise SystemExit(result.returncode)
