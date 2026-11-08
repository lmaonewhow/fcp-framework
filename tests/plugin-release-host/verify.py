#!/usr/bin/env python3
"""Application contract evidence only, never native acceptance."""
import hashlib,json,subprocess
from pathlib import Path
root=Path(__file__).resolve().parents[2]
out=root/'reports/plugin-release-host';out.mkdir(parents=True,exist_ok=True)
report={'status':'FAIL','scope':'real verified plugin archives and Python processes with fixture host authorization','nativeRuoYi':'NOT_RUN','mysql':'NOT_RUN','browser':'NOT_RUN','production':'NOT_ACCEPTED','baselineCommit':subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip(),'workingTreeStatus':subprocess.check_output(['git','status','--porcelain'],cwd=root,text=True).splitlines(),'command':['bash','tests/plugin-release-host/run.sh']}
paths=[*list((root/'core/src/main/java/fcp/runtime/multiversion').glob('*.java')),*list((root/'host/src/main/java/dev/fcp/host').glob('*VersionPluginPolicy.java')),root/'host/src/main/java/dev/fcp/host/PluginReleaseApplication.java',root/'host/src/main/java/dev/fcp/host/ReleaseTaskApplication.java',*list((root/'integration/ruoyi/src/main/java/org/dromara/fcp/plugin').glob('FcpPluginRelease*.java')),root/'integration/ruoyi/src/main/java/org/dromara/fcp/plugin/FcpPluginController.java',root/'integration/ruoyi/src/main/java/org/dromara/fcp/plugin/FcpPluginAdvice.java',root/'integration/ruoyi/src/main/java/org/dromara/fcp/plugin/FcpPluginConfiguration.java',root/'integration/ruoyi/src/main/java/org/dromara/fcp/plugin/FcpPluginVersionController.java',*list((root/'tests/plugin-release-host').glob('*'))]
report['sourceHashes']={str(p.relative_to(root)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(paths) if p.is_file()}
with (out/'final.log').open('w') as log:result=subprocess.run(report['command'],cwd=root,stdout=log,stderr=subprocess.STDOUT,timeout=180)
report['exitCode']=result.returncode
if result.returncode==0:report['status']='PASS'
(out/'result.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps({k:v for k,v in report.items() if k not in ['sourceHashes','workingTreeStatus']},indent=2))
raise SystemExit(result.returncode)
