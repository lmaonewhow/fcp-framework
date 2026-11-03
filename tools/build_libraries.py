#!/usr/bin/env python3
"""Build real framework and migration libraries with the installed JDK; no native host claims."""
from __future__ import annotations
import hashlib,json,shutil,subprocess,tempfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]

def main():
    for tool in ('java','javac','jar'):
        if not shutil.which(tool):raise SystemExit('Missing JDK tool: '+tool)
    subprocess.run(['java',str(ROOT/'core/tools/Build.java'),str(ROOT/'core')],check=True)
    target=ROOT/'dist';target.mkdir(exist_ok=True)
    shutil.copy2(ROOT/'core/dist/fcp-core-0.5.0-dev.1.jar',target/'fcp-core-0.5.0-dev.1.jar')
    with tempfile.TemporaryDirectory(prefix='fcp-migrator-build-') as name:
        directory=Path(name);classes=directory/'classes';classes.mkdir()
        files=sorted((ROOT/'core/src/main/java').rglob('*.java'))+sorted((ROOT/'deploy/src/main/java').rglob('*.java'))
        sources=directory/'sources.txt';sources.write_text('\n'.join('"'+p.as_posix()+'"' for p in files))
        subprocess.run(['javac','--release','17','-encoding','UTF-8','-d',str(classes),'@'+str(sources)],check=True)
        subprocess.run(['jar','--create','--file',str(target/'fcp-migrator-0.5.0-dev.1.jar'),'--main-class','dev.fcp.deploy.Migrations','-C',str(classes),'.'],check=True)
    report={'scope':'framework library and restricted deployment utility, NOT ruoyi-admin.jar','status':'BUILT','artifacts':[]}
    for path in sorted(target.glob('*.jar')):
        report['artifacts'].append({'file':path.name,'bytes':path.stat().st_size,'sha256':hashlib.sha256(path.read_bytes()).hexdigest()})
    (target/'LIBRARY-BUILD.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report,indent=2))
if __name__=='__main__':main()
