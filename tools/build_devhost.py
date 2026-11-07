#!/usr/bin/env python3
"""Build the standalone development host and copy its packaged frontend resources."""
from pathlib import Path
import shutil
import subprocess
ROOT = Path(__file__).resolve().parents[1]

def build(destination=None):
    destination = Path(destination or ROOT/'build/development-host')
    destination.mkdir(parents=True, exist_ok=True)
    sources = [str(p) for tree in ('core/src/main/java','business/src/main/java','host/src/main/java','development-host/src/main/java') for p in sorted((ROOT/tree).rglob('*.java'))]
    subprocess.run(['javac','--release','17','-encoding','UTF-8','-d',str(destination),*sources],cwd=ROOT,check=True,timeout=120)
    resources=ROOT/'development-host/src/main/resources'
    if resources.exists():
        shutil.copytree(resources,destination,dirs_exist_ok=True)
    return destination

if __name__=='__main__':
    print(build())
