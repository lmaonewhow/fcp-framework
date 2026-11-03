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
        ROOT/'sql/native/V001__fcp_tables.sql',ROOT/'sql/native/V002__fcp_permissions.sql',ROOT/'sql/native/V003__persistent_plugin_state.sql',ROOT/'sql/native/V004__ingestion_permissions.sql']
 for rel,blob in BLOBS['backend'].items():
  if rel.startswith('script/sql/'): check_blob(a.backend/rel,blob)
 stages=[{'name':f.name,'sha256':hashlib.sha256(f.read_bytes()).hexdigest()} for f in paths]
 print(json.dumps({'mode':'APPLY' if a.apply else 'PLAN_ONLY','database':a.database,'stages':stages},indent=2))
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
 if query('SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE();')!='0':
  raise ValueError('Schema is not empty. Refusing to change it; this command never repairs or resets an existing DB.')
 # MySQL DDL is not transactional. Keep a durable per-stage record; an incomplete run must be investigated.
 query('CREATE TABLE fcp_install_history(stage VARCHAR(100) PRIMARY KEY,sha256 CHAR(64),state VARCHAR(16),finished_at DATETIME NULL);')
 for f,stage in zip(paths,stages):
  sql=f.read_text(encoding='utf-8')
  if re.search(r'(?im)^\s*(USE\s|(?:CREATE|DROP)\s+DATABASE)',sql): raise ValueError('Script attempts to leave the selected schema')
  name,digest=stage['name'],stage['sha256']
  query(f"INSERT INTO fcp_install_history VALUES('{name}','{digest}','RUNNING',NULL);")
  try:
   query(sql)
   query(f"UPDATE fcp_install_history SET state='APPLIED',finished_at=NOW() WHERE stage='{name}';")
  except Exception:
   query(f"UPDATE fcp_install_history SET state='FAILED',finished_at=NOW() WHERE stage='{name}';")
   raise
 isolation.cleanup()
 print('Fresh acceptance schema initialized. Rotate upstream demo account passwords before exposing the service.')
 return 0
if __name__=='__main__':
 try: raise SystemExit(main())
 except Exception as exc:
  # Do not echo SQL/error output, which may contain credentials or private business data.
  print(f'INSTALL BLOCKED: {type(exc).__name__}. Inspect the isolated schema and its install history.',file=sys.stderr)
  raise SystemExit(2)
