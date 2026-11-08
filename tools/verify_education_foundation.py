#!/usr/bin/env python3
"""Pure Java/request and Python source/plan checks, never a native acceptance claim."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import uuid
ROOT=Path(__file__).resolve().parents[1]
def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home',type=Path,required=True)
    parser.add_argument('--output',type=Path,default=ROOT/'reports/education-foundation')
    args=parser.parse_args()
    output=args.output/uuid.uuid4().hex;output.mkdir(parents=True,exist_ok=False)
    report={'status':'RUNNING','completed':False,'runId':output.name,
            'scope':'PURE_JAVA_REQUEST_BOUNDARIES_AND_PYTHON_PLAN_GUARDS',
            'nativeHost':'NOT_RUN','mysql':'NOT_RUN','browser':'NOT_RUN','production':'BLOCKED','steps':[]}
    def save():
        p=output/'result.tmp';p.write_text(json.dumps(report,indent=2)+'\n');p.replace(output/'result.json')
    def run(name,command):
        process=subprocess.run([str(v) for v in command],cwd=ROOT,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=120)
        (output/(name+'.log')).write_bytes(process.stdout)
        report['steps'].append({'name':name,'command':[str(v) for v in command],'exitCode':process.returncode,'logSha256':hashlib.sha256(process.stdout).hexdigest()})
        save()
        if process.returncode:raise RuntimeError(name+' failed')
    try:
        report['gitHead']=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
        report['gitStatus']=subprocess.check_output(['git','status','--porcelain'],cwd=ROOT,text=True)
        paths=[]
        for glob in ('core/src/main/java/fcp/education/*.java','integration/ruoyi/src/main/java/org/dromara/fcp/education/*.java','tests/education/*','sql/education/*','tools/*education*.py','contracts/education/*.json','docs/education/*.md'):
            paths.extend(p for p in ROOT.glob(glob) if p.is_file())
        report['sourceSha256']={p.relative_to(ROOT).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(set(paths))}
        save()
        run('python',[sys.executable,'-m','unittest','discover','-s','tests/education','-v'])
        classes=output/'classes';classes.mkdir()
        sources=[ROOT/'core/src/main/java/fcp/FcpException.java',ROOT/'core/src/main/java/fcp/Json.java',*sorted((ROOT/'core/src/main/java/fcp/education').glob('*.java')),*sorted((ROOT/'tests/education').glob('*Check.java'))]
        run('javac',[args.java_home/'bin/javac','--release','17','-encoding','UTF-8','-d',classes,*sources])
        for path in sorted((ROOT/'tests/education').glob('*Check.java')):
            run(path.stem,[args.java_home/'bin/java','-cp',classes,path.stem])
        report['artifactSha256']={p.relative_to(output).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in classes.rglob('*.class')}
        report['status']='PASS_LOCAL_BOUNDARIES_ONLY'
    except BaseException as error:
        report.update(status='FAIL',error=type(error).__name__+': '+str(error));raise
    finally:
        report['completed']=True;save();print(output/'result.json')
if __name__=='__main__':main()
