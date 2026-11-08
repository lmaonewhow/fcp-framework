import hashlib,json,subprocess,sys
from pathlib import Path
root=Path(__file__).resolve().parents[2];out=root/'reports/release-observation';out.mkdir(parents=True,exist_ok=True)
paths=[p for tree in ['core/src/main/java/fcp/release','core/src/main/java/fcp/observability','tests/release-observation','integration/ruoyi/src/main/java/org/dromara/fcp/plugin'] for p in (root/tree).rglob('*') if p.is_file() and '__pycache__' not in p.parts]
report={'scope':'actual local services and file journals; trusted observation component integration','native':'NOT_RUN','mysql':'NOT_RUN','browser':'NOT_RUN','production':'NOT_ACCEPTED','baselineCommit':subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip(),'workingTreeStatus':subprocess.check_output(['git','status','--porcelain'],cwd=root,text=True).splitlines(),'sourceHashes':{str(p.relative_to(root)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(paths)},'command':['bash','tests/release-observation/run.sh']}
with (out/'test.log').open('w') as log:result=subprocess.run(report['command'],cwd=root,stdout=log,stderr=subprocess.STDOUT,timeout=180)
report.update(exitCode=result.returncode,status='PASS' if result.returncode==0 else 'FAIL');(out/'result.json').write_text(json.dumps(report,indent=2)+'\n');print(report['status']);sys.exit(result.returncode)
