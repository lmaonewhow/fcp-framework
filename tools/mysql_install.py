#!/usr/bin/env python3
"""Fresh isolated acceptance DB bootstrap. Refuses non-empty schemas and never drops databases.
Existing production databases require a reviewed deployment migration, not this initializer.
"""
from __future__ import annotations
import argparse, hashlib, json, os, re, shutil, subprocess, sys, tempfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'tools'))
from native import BLOBS, check_blob

def install_stages(query, stages):
 # Both ledgers describe actual execution, never an inferred/adopted success.
 # Keep the deployment ledger schema identical to deploy/Migrations.java.
 if query('SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE();')!='0':
  raise ValueError('Schema is not empty. Refusing to change it; this command never repairs or resets an existing DB.')
 query('CREATE TABLE fcp_install_history(stage VARCHAR(100) PRIMARY KEY,sha256 CHAR(64),state VARCHAR(16),finished_at DATETIME NULL);')
 query('CREATE TABLE fcp_migration_history(version_num INT PRIMARY KEY,script_name VARCHAR(180) NOT NULL,sha256 CHAR(64) NOT NULL,state VARCHAR(16) NOT NULL,updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)) ENGINE=InnoDB;')
 for stage in stages:
  name,digest,sql,version=stage['name'],stage['sha256'],stage['sql'],stage['version']
  query(f"INSERT INTO fcp_install_history VALUES('{name}','{digest}','RUNNING',NULL);")
  if version is not None:
   query(f"INSERT INTO fcp_migration_history(version_num,script_name,sha256,state) VALUES({version},'{name}','{digest}','RUNNING');")
  try:
   query(sql)
   query(f"UPDATE fcp_install_history SET state='APPLIED',finished_at=NOW() WHERE stage='{name}';")
   if version is not None:
    query(f"UPDATE fcp_migration_history SET state='APPLIED',updated_at=CURRENT_TIMESTAMP(6) WHERE version_num={version};")
  except Exception:
   # Either FAILED or the already durable RUNNING remains a dirty stop condition.
   for statement in [f"UPDATE fcp_install_history SET state='FAILED',finished_at=NOW() WHERE stage='{name}';"] + ([f"UPDATE fcp_migration_history SET state='FAILED',updated_at=CURRENT_TIMESTAMP(6) WHERE version_num={version};"] if version is not None else []):
    try: query(statement)
    except Exception: pass
   raise

def checked_stages(paths, lock):
 if lock.get('format')!=1: raise ValueError('Unsupported migration lock')
 result=[];versions=[];names=set()
 for path in paths:
  data=path.read_bytes();name=path.name;digest=hashlib.sha256(data).hexdigest();sql=data.decode('utf-8')
  if not re.fullmatch(r'[A-Za-z0-9_.-]+',name) or name in names: raise ValueError('Invalid or duplicate stage name')
  names.add(name)
  match=re.fullmatch(r'V(\d{3,})__[a-z0-9_]+\.sql',name)
  version=int(match.group(1)) if match else None
  if version is not None:
   if lock['files'].get(name)!=digest: raise ValueError('Locked migration checksum changed')
   versions.append(version)
  if re.search(r'(?im)^\s*(USE\s|(?:CREATE|DROP)\s+DATABASE)',sql): raise ValueError('Script attempts to leave the selected schema')
  result.append(dict(name=name,sha256=digest,sql=sql,version=version))
 if versions!=sorted(set(versions)) or set(lock['files'])!={s['name'] for s in result if s['version'] is not None}:
  raise ValueError('Missing or unordered locked migrations')
 return result

def main():
 p=argparse.ArgumentParser(description=__doc__)
 p.add_argument('--backend',type=Path,required=True)
 p.add_argument('--defaults-file',type=Path,required=True,help='MySQL client option file; must be owner-only on POSIX')
 p.add_argument('--database',required=True)
 p.add_argument('--apply',action='store_true')
 a=p.parse_args()
 if not re.fullmatch(r'fcp_[a-z0-9_]{1,48}',a.database): raise ValueError('Only explicitly named fcp_* acceptance schemas are accepted')
 if not a.defaults_file.is_file() or a.defaults_file.is_symlink(): raise ValueError('Invalid credential file')
 if os.name!='nt' and a.defaults_file.stat().st_mode & 0o077: raise ValueError('Credential file must have permissions 0600')
 paths=[a.backend/'script/sql/ry_vue_5.X.sql',a.backend/'script/sql/ry_workflow.sql',
        ROOT/'sql/native/V001__fcp_tables.sql',ROOT/'sql/native/V002__fcp_permissions.sql',ROOT/'sql/native/V003__persistent_plugin_state.sql',ROOT/'sql/native/V004__ingestion_permissions.sql',ROOT/'sql/native/V005__runtime_plugins.sql',ROOT/'sql/native/V006__runtime_plugin_permissions.sql',ROOT/'sql/native/V007__system_release_permissions.sql',ROOT/'sql/native/V008__plugin_version_grants.sql',ROOT/'sql/native/V009__remove_upstream_seed_menus.sql']
 for rel,blob in BLOBS['backend'].items():
  if rel.startswith('script/sql/'): check_blob(a.backend/rel,blob)
 stages=checked_stages(paths,json.loads((ROOT/'sql/native/migrations.lock.json').read_text()))
 print(json.dumps({'mode':'APPLY' if a.apply else 'PLAN_ONLY','database':a.database,'stages':[{k:v for k,v in stage.items() if k!='sql'} for stage in stages]},indent=2))
 if not a.apply: return 0
 executable=shutil.which('mysql')
 if not executable: raise ValueError('MySQL 8 command-line client not found')
 args=[executable,f'--defaults-file={a.defaults_file.resolve()}', '--default-character-set=utf8mb4',
       '--batch','--raw','--skip-column-names',a.database]
 # MySQL 8.0 lacks --no-login-paths (introduced in 8.2). An owner-only empty
 # login-path location prevents inherited credentials on both client versions.
 isolation=tempfile.TemporaryDirectory(prefix='fcp-mysql-login-')
 environment={k:v for k,v in os.environ.items() if not k.startswith('MYSQL_')}
 environment['MYSQL_TEST_LOGIN_FILE']=str(Path(isolation.name)/'absent.mylogin.cnf')
 def query(sql):
  result=subprocess.run(args,input=sql,text=True,encoding='utf-8',capture_output=True,timeout=180,env=environment)
  if result.returncode:
   code=re.search(r'ERROR\s+(\d+)\s*\(([A-Za-z0-9]+)\)(?: at line (\d+))?',result.stderr)
   safe='SQL error '+(':'.join(v for v in code.groups() if v) if code else 'unclassified client error')
   print('MYSQL INSTALL FAILURE: '+safe+'; SQL and credentials withheld',file=sys.stderr)
   raise RuntimeError(safe)
  return result.stdout.strip()
 install_stages(query,stages)
 isolation.cleanup()
 print('Fresh acceptance schema initialized. Rotate upstream demo account passwords before exposing the service.')
 return 0
if __name__=='__main__':
 try: raise SystemExit(main())
 except Exception as exc:
  # Do not echo SQL/error output, which may contain credentials or private business data.
  print(f'INSTALL BLOCKED: {type(exc).__name__}. Inspect the isolated schema and its install history.',file=sys.stderr)
  raise SystemExit(2)
