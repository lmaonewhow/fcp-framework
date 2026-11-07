#!/usr/bin/env python3
"""Initialize private opt-in dev identities; never print their tokens or overwrite config."""
import argparse
import json
import os
from pathlib import Path
import secrets
import shutil
import sys

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory',type=Path)
    args=parser.parse_args()
    args.directory.mkdir(mode=0o700,parents=True,exist_ok=True)
    if args.directory.is_symlink() or args.directory.stat().st_mode & 0o077:
        parser.error('Use a private non-symlink directory with permissions 0700')
    target=args.directory/'identities.json'
    identities=[]
    for tenant in ('dev-t1','dev-t2'):
        for role in ('admin','viewer'):
            identities.append(dict(token=secrets.token_hex(32),userId=tenant+'-'+role,tenantId=tenant,scopes=['tenant:'+tenant,'class:class-a'],permissions=['fcp:plugin:view']+(['fcp:plugin:install','fcp:plugin:manage'] if role=='admin' else []),grants=[]))
    config=dict(development=True,trustedPublishers=[],executables=dict(python=str(Path(sys.executable).resolve())),identities=identities)
    node=shutil.which('node')
    if node:config['executables']['node']=str(Path(node).resolve())
    try:
        fd=os.open(target,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
    except FileExistsError:
        parser.error('Refusing to overwrite an existing identity file')
    with os.fdopen(fd,'w') as output:json.dump(config,output,indent=2);output.write('\n')
    print('Private development identities created at '+str(target)+'. No publishers or business capabilities are granted.')
if __name__=='__main__':main()
