"""Full-combination evidence completeness verifier, not an authenticity attestation.
Only protected CI/reviewer can make evidence trusted. Default templates fail closed.
"""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import re
from common import canonical_hash, load_json, safe_file, sha256, HEX64

REQUIRED=('mysql_schema','tenant_isolation','native_fcp','dual_ui','migration_upgrade','restore','load',
          'windows_encoding','authorization_fence','cross_version_idempotency','observability_failure',
          'gray_routing','gray_bad_data','async_stop','artifact_compatibility','frontend_old_assets')
COMPONENTS=frozenset({'backend','admin_ui','teacher_ui','python_plugin','database_schema','rules','configuration'})

def instant(v):
    if not isinstance(v,str):raise ValueError('timestamp type')
    dt=datetime.fromisoformat(v.replace('Z','+00:00'))
    if dt.tzinfo is None:raise ValueError('timestamp lacks timezone')
    return dt

def verify_manifest(manifest, artifact_root):
    errors=[]
    if not isinstance(manifest,dict):return ['manifest must be object']
    if manifest.get('format')!='education-release-manifest-v2':errors.append('manifest format mismatch')
    if manifest.get('artifact_kind')!='PRODUCTION':errors.append('not a production artifact manifest')
    if not isinstance(manifest.get('source_commit'),str) or not re.fullmatch('[0-9a-f]{40}',manifest['source_commit']):errors.append('full source commit missing')
    comps=manifest.get('components')
    if not isinstance(comps,list) or not comps or any(not isinstance(c,dict) for c in comps):return errors+['invalid components']
    names=[c.get('name') for c in comps]
    if any(not isinstance(n,str) for n in names):return errors+['invalid component name']
    if len(set(names))!=len(names):errors.append('duplicate component')
    if not COMPONENTS.issubset(names):errors.append('required component missing')
    paths=[]
    for c in comps:
        try:
            p=safe_file(artifact_root,c['path']);paths.append(c['path'])
            if not isinstance(c.get('sha256'),str) or not HEX64.fullmatch(c['sha256']) or sha256(p)!=c['sha256']:errors.append('component digest mismatch:'+c['name'])
            if not p.stat().st_size:errors.append('empty component:'+c['name'])
        except (KeyError,ValueError,TypeError,OSError):errors.append('invalid component file:'+str(c.get('name')))
    if len(set(paths))!=len(paths):errors.append('component paths must be distinct')
    compatibility=manifest.get('compatibility')
    if not isinstance(compatibility,dict) or not all(isinstance(compatibility.get(k),str) and compatibility[k] and 'REQUIRED' not in compatibility[k] for k in ('api_contract','schema_min','schema_max','old_client_policy')):
        errors.append('compatibility contract missing')
    return errors

def check(data, evidence_root, manifest, artifact_root, expected_db_version, now=None):
    errors=verify_manifest(manifest,artifact_root)
    if not isinstance(data,dict):return errors+['evidence must be object']
    if not isinstance(expected_db_version,str) or not re.fullmatch(r'8\.4\.\d+',expected_db_version):errors.append('exact MySQL 8.4 patch required')
    env=data.get('environment')
    if not isinstance(env,dict) or env.get('database_engine')!='MySQL' or env.get('database_version')!=expected_db_version:errors.append('native MySQL version mismatch')
    if data.get('evidence_kind')!='NATIVE_PRODUCT_ACCEPTANCE':errors.append('not native product acceptance')
    digest=canonical_hash(manifest)
    if data.get('manifest_digest')!=digest:errors.append('manifest binding mismatch')
    records=data.get('checks')
    if not isinstance(records,list) or any(not isinstance(x,dict) or not isinstance(x.get('name'),str) for x in records):return errors+['checks must be named object records']
    names=[r['name'] for r in records]
    if len(set(names))!=len(names):errors.append('duplicate check name')
    mapped={r['name']:r for r in records};clock=now or datetime.now(timezone.utc)
    for name in REQUIRED:
        r=mapped.get(name)
        if r is None:errors.append(name+':missing');continue
        if r.get('status')!='PASS' or type(r.get('exit_code'))is not int or r['exit_code']!=0:errors.append(name+':not PASS exit 0')
        if type(r.get('executed_tests'))is not int or r['executed_tests']<=0 or type(r.get('failed_tests'))is not int or r['failed_tests']!=0 or type(r.get('skipped_tests'))is not int or r['skipped_tests']!=0:errors.append(name+':invalid counts')
        if r.get('manifest_digest')!=digest or r.get('source_commit')!=manifest.get('source_commit'):errors.append(name+':binding mismatch')
        try:
            start,end=instant(r['started_at']),instant(r['ended_at'])
            if not start<end<=clock:raise ValueError('invalid time range')
        except (KeyError,ValueError,TypeError,OverflowError):errors.append(name+':invalid timestamps')
        log=r.get('raw_log')
        try:
            if not isinstance(log,dict):raise ValueError('log')
            p=safe_file(evidence_root,log['path'])
            if not p.stat().st_size or sha256(p)!=log.get('sha256'):raise ValueError('log digest')
        except (KeyError,ValueError,TypeError,OSError):errors.append(name+':invalid raw evidence')
    return errors

def main():
    p=argparse.ArgumentParser();p.add_argument('evidence',type=Path);p.add_argument('--manifest',type=Path,required=True);p.add_argument('--artifacts',type=Path,required=True);p.add_argument('--expected-db-version',required=True);a=p.parse_args()
    try:
        errors=check(load_json(a.evidence),a.evidence.parent,load_json(a.manifest),a.artifacts,a.expected_db_version)
        print(json.dumps({'status':'BLOCKED' if errors else 'COMPLETE_SUBMITTED_EVIDENCE','errors':errors,'trust_boundary':'protected CI and independent review required'},ensure_ascii=True,indent=2));return 2 if errors else 0
    except (OSError,TypeError,ValueError) as e:
        print(json.dumps({'status':'ERROR','error':str(e)},ensure_ascii=True));return 1
if __name__=='__main__':raise SystemExit(main())
