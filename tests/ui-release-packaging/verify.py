#!/usr/bin/env python3
"""Bind synthetic-dist packaging evidence to the actual source; not native UI acceptance."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys

ROOT=Path(__file__).resolve().parents[2]
OUTPUT=ROOT/'reports/ui-release-packaging'
OUTPUT.mkdir(parents=True,exist_ok=True)
command=[sys.executable,'-m','unittest','discover','-s','tests/ui-release-packaging','-v']
result={'status':'FAIL','scope':'actual files and concurrent packager processes using synthetic dist bytes','nativeVueBuild':'NOT_RUN','browser':'NOT_RUN','production':'NOT_ACCEPTED','command':command}
result['baselineCommit']=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
result['workingTreeStatus']=subprocess.check_output(['git','status','--porcelain'],cwd=ROOT,text=True).splitlines()
files=[ROOT/'tools/ui_release_bundle.py',*sorted((ROOT/'tests/ui-release-packaging').glob('*.py'))]
result['sourceHashes']={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in files}
with (OUTPUT/'test.log').open('w') as log:
    completed=subprocess.run(command,cwd=ROOT,stdout=log,stderr=subprocess.STDOUT,timeout=120)
result['exitCode']=completed.returncode
if completed.returncode==0:result['status']='PASS'
(OUTPUT/'result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
print(json.dumps(result,ensure_ascii=False,indent=2))
raise SystemExit(completed.returncode)
