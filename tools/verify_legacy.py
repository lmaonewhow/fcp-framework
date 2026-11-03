#!/usr/bin/env python3
"""Rerun preserved baseline core regressions with explicit fixture-only runtime and stores."""
import json,os,shutil,subprocess,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def main():
 reports=ROOT/'reports/legacy';reports.mkdir(parents=True,exist_ok=True)
 classes=ROOT/'build/legacy-classes';classes.mkdir(parents=True,exist_ok=True)
 result={'status':'BLOCKED','scope':'preserved pure-JDK baseline regression; JDBC proxies/test stores, not native MySQL','suites':[]}
 env={**os.environ,'PYTHONIOENCODING':'utf-8','FCP_TEST_SECRET':'synthetic-canary'}
 def run(name,args,timeout=90):
  p=subprocess.run(args,cwd=ROOT,env=env,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=timeout)
  (reports/(name+'.log')).write_text(p.stdout+p.stderr)
  if p.returncode:raise RuntimeError(name+' failed; inspect its log')
  return p.stdout
 try:
  files=[p for tree in ['core/src/main/java','business/src/main/java','tests/legacy/java'] for p in (ROOT/tree).rglob('*.java')]
  argfile=ROOT/'build/legacy-sources.txt';argfile.write_text('\n'.join('"'+p.as_posix()+'"' for p in files))
  run('compile',[shutil.which('javac'),'--release','17','-encoding','UTF-8','-d',str(classes),'@'+str(argfile)])
  for name in ['KernelRegression','DualFlowRegression','RequestBoundaryRegression','AuditRegression','CompatibilityRegression','OptimizationRegression','ProcessBoundsRegression']:
   args=[shutil.which('java')]
   if name=='ProcessBoundsRegression':args+=['-Dfcp.maxProcesses=2']
   args+=['-cp',str(classes),name]
   if name in ['DualFlowRegression','AuditRegression','CompatibilityRegression']:args+=[str(ROOT/'fixtures/legacy-runtime')]
   parsed=json.loads(run(name,args));result['suites'].append(parsed)
   if parsed.get('failed'):raise RuntimeError(name+' reported failed assertions')
   print(name,parsed.get('passed'),'PASS',flush=True)
  result.update(status='PASS',passed=sum(s['passed'] for s in result['suites']),failed=0)
 except Exception as exc:result.update(status='FAIL',error=str(exc))
 (ROOT/'reports/legacy-regressions.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
 print(json.dumps({k:v for k,v in result.items() if k!='suites'},ensure_ascii=False))
 return 0 if result['status']=='PASS' else 2
if __name__=='__main__':raise SystemExit(main())
