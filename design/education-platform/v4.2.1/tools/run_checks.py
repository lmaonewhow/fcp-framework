"""Run LOCAL reference checks. This command never connects to MySQL or starts FCP.
Use Python 3.11+ and Node 20+; explicit UTF-8 I/O works without -X utf8.
Node absence is NOT_RUN and prevents an aggregate pass. No npm install is needed.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import importlib
import io
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import sys
import time
import unittest
import uuid

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT))
from common import canonical_hash, sha256, write_json

class EvidenceResult(unittest.TextTestResult):
    def __init__(self,*args,**kwargs):
        super().__init__(*args,**kwargs);self.records=[]
    def addSuccess(self,test):
        super().addSuccess(test);self.records.append({'test':test.id(),'status':'PASS'})
    def addFailure(self,test,err):
        super().addFailure(test,err);self.records.append({'test':test.id(),'status':'FAIL'})
    def addError(self,test,err):
        super().addError(test,err);self.records.append({'test':test.id(),'status':'ERROR'})
    def addSkip(self,test,reason):
        super().addSkip(test,reason);self.records.append({'test':test.id(),'status':'SKIP','reason':reason})
    def addExpectedFailure(self,test,err):
        super().addExpectedFailure(test,err);self.records.append({'test':test.id(),'status':'EXPECTED_FAILURE'})
    def addUnexpectedSuccess(self,test):
        super().addUnexpectedSuccess(test);self.records.append({'test':test.id(),'status':'UNEXPECTED_SUCCESS'})

def unique_test_ids(suite):
    """Reject duplicate discovery instead of inflating the reported executed count."""
    ids=[]
    def visit(node):
        if isinstance(node,unittest.TestSuite):
            for child in node:visit(child)
        else:ids.append(node.id())
    visit(suite)
    if len(ids)!=len(set(ids)):raise ValueError('DUPLICATE_TEST_IDS')
    return ids

def execute_checks(output, report):
    if os.environ.get('EDU_MYSQL_TEST_SCHEMA'):
        report['reason']='Remove EDU_MYSQL_TEST_SCHEMA for the local-only runner.';report['status']='BLOCKED_ENVIRONMENT';return 2
    if not __debug__:
        report['reason']='Do not run this verifier with -O.';report['status']='BLOCKED_ENVIRONMENT';return 2
    codepaths=[]
    for prefix in ('tools','reference','tests','database','contracts','governance'):
        for f in (ROOT/prefix).rglob('*'):
            if f.is_file() and '__pycache__' not in f.parts and f.suffix in ('.py','.mjs','.json','.sql','.md','.yml','.csv'):
                codepaths.append({'path':f.relative_to(ROOT).as_posix(),'sha256':sha256(f)})
    codepaths.sort(key=lambda x:x['path']);write_json(output/'tested_sources.json',codepaths)
    report['tested_source_tree_sha256']=canonical_hash(codepaths)
    from check_design import validate
    from generate_schema import render
    from common import load_json
    model=load_json(ROOT/'database/model.json');errors=validate(model)
    sql=(ROOT/'database/sql/P042__fresh_isolated_schema.sql').read_text(encoding='utf-8-sig')
    if not errors and sql!=render(model):errors.append('generated SQL drift')
    report['steps'].append({'name':'model_and_exact_sql','status':'PASS' if not errors else 'FAIL','errors':errors})
    stream=io.StringIO();suite=unittest.defaultTestLoader.discover(str(ROOT/'tests'),pattern='test_*.py',top_level_dir=str(ROOT))
    ids=unique_test_ids(suite);report['unique_python_test_ids_sha256']=canonical_hash(sorted(ids))
    result=unittest.TextTestRunner(stream=stream,verbosity=2,resultclass=EvidenceResult).run(suite)
    (output/'python_tests.log').write_text(stream.getvalue(),encoding='utf-8')
    write_json(output/'python_test_cases.json',result.records)
    passed=result.wasSuccessful() and not result.skipped and not result.expectedFailures and result.testsRun>0
    report['steps'].append({'name':'python_reference_tests','status':'PASS' if passed else 'FAIL','executed':result.testsRun,
      'failures':len(result.failures),'errors':len(result.errors),'skipped':len(result.skipped),'expected_failures':len(result.expectedFailures),
      'raw_log_sha256':sha256(output/'python_tests.log')})
    node=shutil.which('node')
    if node:
        proc=subprocess.run([node,'--test','tests/frontend_release.test.mjs'],cwd=ROOT,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=60,check=False)
        raw=proc.stdout.decode('utf-8',errors='replace');(output/'frontend_policy.log').write_text(raw,encoding='utf-8')
        version=subprocess.run([node,'--version'],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,check=False,timeout=10).stdout.decode('utf-8',errors='replace').strip()
        import re
        counters={k:int(v) for k,v in re.findall(r'^# (tests|pass|fail|skipped|cancelled|todo) (\d+)\s*$',raw,re.M)}
        ok=proc.returncode==0 and counters.get('tests',0)>0 and counters.get('pass')==counters.get('tests') and not any(counters.get(k,0) for k in ('fail','skipped','cancelled','todo'))
        report['steps'].append({'name':'node_frontend_policy_tests','status':'PASS' if ok else 'FAIL','exit_code':proc.returncode,'node':version,**counters,'raw_log_sha256':sha256(output/'frontend_policy.log')})
    else:report['steps'].append({'name':'node_frontend_policy_tests','status':'NOT_RUN','reason':'Node executable missing'})
    report['status']='PASS_LOCAL_REFERENCE_ONLY' if all(s['status']=='PASS' for s in report['steps']) else 'FAIL_OR_NOT_RUN'
    return 0 if report['status']=='PASS_LOCAL_REFERENCE_ONLY' else 1

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',type=Path,default=ROOT/'evidence/local')
    args=parser.parse_args();output=args.output
    output.mkdir(parents=True,exist_ok=True)
    run_id=uuid.uuid4().hex;tick=time.perf_counter();lock=output/'.verification.lock'
    try:
        fd=os.open(lock,os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600)
    except FileExistsError:
        print(json.dumps({'status':'BLOCKED_OUTPUT_LOCKED','run_id':run_id,
              'reason':'Another run owns this output or left a stale lock. Verify process ownership before manual cleanup.'},ensure_ascii=True))
        return 2
    with os.fdopen(fd,'w',encoding='utf-8') as stream:
        json.dump({'run_id':run_id,'pid':os.getpid()},stream);stream.flush();os.fsync(stream.fileno())
    report={'scope':'LOCAL_REFERENCE_ONLY','run_id':run_id,'started_at':datetime.now(timezone.utc).isoformat(),
      'status':'RUNNING','completed':False,'production_release':'BLOCKED',
      'not_executed':['native MySQL','native Windows','actual FCP','actual Vue browser E2E','live telemetry stack','production load or recovery'],
      'environment':{'python':sys.version,'platform':platform.platform(),'utf8_mode':sys.flags.utf8_mode,
        'stdout_encoding':sys.stdout.encoding,'filesystem_encoding':sys.getfilesystemencoding()},'steps':[]}
    code=1
    try:
        # Replacing the previous result BEFORE work prevents an old PASS surviving this run.
        # A hard kill leaves RUNNING. Consumers require the invocation's run_id AND exit zero.
        write_json(output/'result.json',report)
        write_json(output/'invocation.json',{'run_id':run_id,'started_at':report['started_at'],'runner_sha256':sha256(Path(__file__))})
        code=execute_checks(output,report)
    except BaseException as exc:
        code=130 if isinstance(exc,KeyboardInterrupt) else 1
        report['status']='INTERRUPTED' if code==130 else 'ERROR'
        report['error_type']=type(exc).__name__
        # Never serialize arbitrary exception bodies, command env or credentials.
    finally:
        report['ended_at']=datetime.now(timezone.utc).isoformat()
        report['elapsed_seconds']=round(time.perf_counter()-tick,3)
        report['completed']=True;report['exit_code']=code
        try:write_json(output/'result.json',report)
        finally:
            # Delete only the lock created by this invocation.
            try:
                with lock.open('r',encoding='utf-8') as stream:owner=json.load(stream)
                if owner.get('run_id')==run_id:lock.unlink()
            except (OSError,ValueError):pass
    print(json.dumps(report,ensure_ascii=True,indent=2));return code

if __name__=='__main__':raise SystemExit(main())
