#!/usr/bin/env python3
"""Apply the one-time reviewed 5.6 migration to exact preimages; never pushes itself."""
import hashlib
import json
from pathlib import Path
import subprocess

ROOT=Path(__file__).resolve().parents[1]
ALLOWED={'upstreams.lock.json','tools/native.py','tools/native_client.py','tools/mysql_install.py',
 'tests/python/test_local_acceptance.py','integration/ruoyi/src/main/java/org/dromara/fcp/school/FcpSchoolController.java',
 'deploy/src/main/java/dev/fcp/deploy/Migrations.java','tests/java/MigrationRegression.java',
 'sql/native/migrations.lock.json','sql/native/V004__ingestion_permissions.sql'}


def run(*args):
    return subprocess.check_output(args,cwd=ROOT,text=True).strip()


def main():
    bundle=ROOT/'tools/patches'
    manifest=json.loads((bundle/'ruoyi56-native.json').read_text())
    patch=bundle/'ruoyi56-native.patch'
    if (manifest.get('format')!=1 or manifest.get('patchFile')!=patch.name
            or set(manifest.get('preimages',{}))!=ALLOWED
            or hashlib.sha256(patch.read_bytes()).hexdigest()!=manifest.get('sha256')):
        raise RuntimeError('Reviewed patch manifest mismatch')
    for name,expected in manifest['preimages'].items():
        target=ROOT/name
        if expected is None:
            if target.exists():raise RuntimeError('New migration path already exists')
        elif target.is_symlink() or not target.is_file() or run('git','hash-object',name)!=expected:
            raise RuntimeError('Reviewed preimage changed: '+name)
    changed={line.split('\t',2)[2] for line in run('git','apply','--numstat',str(patch)).splitlines()}
    if changed!=ALLOWED:raise RuntimeError('Patch paths differ from reviewed allowlist')
    run('git','apply','--unidiff-zero','--check','--index','--whitespace=error',str(patch))
    run('git','apply','--unidiff-zero','--index','--whitespace=error',str(patch))
    print('Applied exact reviewed source migration; no branch was changed by this script.')


if __name__=='__main__':main()
