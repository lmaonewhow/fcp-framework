"""Opt-in native MySQL test harness. No existing schema is accepted or overwritten.
Requires PyMySQL, loopback MySQL, exact patch, and explicit provisioning consent.
The generated schema is deleted only if this process created it. Evidence survives.
This validates SQL and database protocols, not the user's running FCP application.
"""
import argparse
import contextlib
from datetime import datetime, timezone
import importlib
import io
import json
import os
from pathlib import Path
import re
import sys
import threading
import time
import unittest
import uuid
from common import load_json, write_json, sha256
from mysql_support import initialize_session

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT))

def split_sql(source):
    """Split this package's simple SQL, respecting single quotes and line comments."""
    out=[];buf=[];i=0;quote=False
    while i<len(source):
        c=source[i]
        if not quote and source[i:i+2]=='--':
            j=source.find('\n',i);i=len(source) if j<0 else j;continue
        if c=="'":
            if quote and source[i:i+2]=="''":buf.extend(["'","'"]);i+=2;continue
            quote=not quote
        if c==';' and not quote:
            if ''.join(buf).strip():out.append(''.join(buf).strip())
            buf=[]
        else:buf.append(c)
        i+=1
    if quote:raise ValueError('unclosed SQL string')
    if ''.join(buf).strip():out.append(''.join(buf).strip())
    return out

def _execute(a,report):
    created=False;db=None;schema=None;raw=io.StringIO();code=1
    def block(reason):
        report['status']='NOT_RUN';report['reason']=reason;return 2
    if not __debug__:return block('Python optimized mode is not allowed for this assertion-based native harness')
    if not re.fullmatch(r'8\.4\.\d+',a.expected_version):return block('exact MySQL 8.4 patch required')
    if not a.provision_isolated:return block('explicit --provision-isolated consent required')
    try:import pymysql
    except ImportError:return block('PyMySQL dependency missing; no native test executed')
    if not os.environ.get('EDU_MYSQL_USER') or 'EDU_MYSQL_PASSWORD' not in os.environ:return block('isolated test credentials missing')
    try:
        port=int(os.environ.get('EDU_MYSQL_PORT','3306'))
        if not 1<=port<=65535:raise ValueError('port')
    except ValueError:return block('invalid test MySQL port')
    kwargs=dict(host='127.0.0.1',port=port,user=os.environ['EDU_MYSQL_USER'],password=os.environ['EDU_MYSQL_PASSWORD'],charset='utf8mb4',connect_timeout=5,read_timeout=10,write_timeout=10,autocommit=False)
    schema='fcp_design_verify_'+uuid.uuid4().hex[:16]
    def connection():
        c=pymysql.connect(**kwargs,database=schema)
        try:initialize_session(c)
        except BaseException:c.close();raise
        return c
    try:
        db=pymysql.connect(**kwargs)
        report['session_profile']=initialize_session(db)
        report['driver_version']=getattr(pymysql,'VERSION_STRING','not_reported')
        with db.cursor() as cur:
            cur.execute('SELECT VERSION(),@@version_comment');version,comment=cur.fetchone()
            report['environment']={'database_engine':'MySQL','database_version':version,'version_comment':comment,'port':port,'os':sys.platform}
            if version!=a.expected_version or 'mariadb' in comment.lower():raise ValueError('server identity/patch mismatch')
            cur.execute('CREATE DATABASE `'+schema+'` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin');created=True;report['schema_created']=True;report['isolated_schema']=schema
            cur.execute('USE `'+schema+'`')
            sqlpath=ROOT/'database/sql/P042__fresh_isolated_schema.sql'
            for stmt in split_sql(sqlpath.read_text(encoding='utf-8-sig')):cur.execute(stmt)
            model=load_json(ROOT/'database/model.json')
            cur.execute('SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=%s',(schema,));assert cur.fetchone()[0]==len(model['tables'])
            cur.execute("SELECT COUNT(*) FROM information_schema.table_constraints WHERE table_schema=%s AND constraint_type='FOREIGN KEY'",(schema,));assert cur.fetchone()[0]==sum(len(t['foreign_keys']) for t in model['tables'])
            report['checks'].append({'name':'native_ddl_and_counts','status':'PASS','schema_sha256':sha256(sqlpath)})
            for a1,b in [('school-a','school-a '),('A','a'),('é','e\u0301'),('01','1')]:
                cur.execute('SELECT CONVERT(%s USING utf8mb4) COLLATE utf8mb4_0900_bin = CONVERT(%s USING utf8mb4) COLLATE utf8mb4_0900_bin',(a1,b));assert cur.fetchone()[0]==0
            cur.execute("SELECT _utf8mb4'ALIce' COLLATE utf8mb4_0900_ai_ci = _utf8mb4'alice'");assert cur.fetchone()[0]==1
            cur.execute('SELECT @@session.time_zone, NOW(6) = UTC_TIMESTAMP(6)');zone,equal=cur.fetchone();assert zone=='+00:00' and equal==1
            report['checks'].append({'name':'native_collation_and_utc','status':'PASS'})
        db.commit()
        os.environ['EDU_MYSQL_TEST_SCHEMA']=schema
        suite=unittest.TestSuite([unittest.defaultTestLoader.loadTestsFromName('tests.test_relations'),unittest.defaultTestLoader.loadTestsFromName('tests.test_second_review.NewRelations')])
        result=unittest.TextTestRunner(stream=raw,verbosity=2).run(suite)
        report['relation_tests']={'executed':result.testsRun,'failures':len(result.failures),'errors':len(result.errors),'skipped':len(result.skipped)}
        if not result.wasSuccessful() or result.skipped or result.testsRun<=0 or result.expectedFailures:
            raise ValueError('native relational tests failed or incomplete')
        # Verify shared-lock ordering using real independent connections. No FCP code is implied.
        seed=connection()
        with seed.cursor() as cur:cur.execute('INSERT INTO ed_core_execution_grant(tenant_id,scope_key,capability_key,manifest_digest,epoch,enabled) VALUES (%s,%s,%s,%s,0,1)',('test','tenant','eval','a'*64))
        seed.commit();seed.close()
        first=connection();second=connection();started=threading.Event();finished=threading.Event();errs=[]
        try:
            with first.cursor() as cur:
                cur.execute('SELECT epoch,enabled FROM ed_core_execution_grant WHERE tenant_id=%s AND scope_key=%s AND capability_key=%s AND manifest_digest=%s FOR SHARE',('test','tenant','eval','a'*64));assert cur.fetchone()==(0,1)
            def revoke():
                try:
                    started.set()
                    with second.cursor() as cur:cur.execute('UPDATE ed_core_execution_grant SET epoch=epoch+1,enabled=0 WHERE tenant_id=%s AND scope_key=%s AND capability_key=%s AND manifest_digest=%s',('test','tenant','eval','a'*64))
                    second.commit()
                except Exception as e:errs.append(e.args[0] if e.args else type(e).__name__);second.rollback()
                finally:finished.set()
            with second.cursor() as cur:cur.execute('SET SESSION innodb_lock_wait_timeout=1')
            thread=threading.Thread(target=revoke,daemon=True);thread.start();assert started.wait(2)
            assert finished.wait(5);thread.join(1);assert errs==[1205], 'expected native lock-wait timeout while shared authorization lock is held'
            first.commit()
            with second.cursor() as cur:cur.execute('UPDATE ed_core_execution_grant SET epoch=epoch+1,enabled=0 WHERE tenant_id=%s AND scope_key=%s AND capability_key=%s AND manifest_digest=%s',('test','tenant','eval','a'*64))
            second.commit()
            with first.cursor() as cur:
                cur.execute('SELECT epoch,enabled FROM ed_core_execution_grant WHERE tenant_id=%s AND scope_key=%s AND capability_key=%s AND manifest_digest=%s FOR SHARE',('test','tenant','eval','a'*64));assert cur.fetchone()==(1,0)
            first.rollback();report['checks'].append({'name':'native_shared_lock_revoke_order','status':'PASS'})
        finally:first.close();second.close()
        report['status']='PASS_NATIVE_DB_ONLY';code=0
    except Exception as exc:
        report['status']='FAIL' if created else 'NOT_RUN';report['error_type']=type(exc).__name__;report['error_code']=exc.args[0] if exc.args and type(exc.args[0]) is int else None;code=1 if created else 2
    finally:
        os.environ.pop('EDU_MYSQL_TEST_SCHEMA',None)
        if db:
            try:
                db.rollback()
                if created and not a.keep_schema:
                    with db.cursor() as cur:cur.execute('DROP DATABASE `'+schema+'`')
                    report['cleanup']='deleted only own generated schema'
                elif created:report['cleanup']='kept own generated schema by explicit request'
            except Exception as exc:report['cleanup']='FAILED:'+type(exc).__name__;report['status']='FAIL';code=1
            db.close()
        report['ended_at']=datetime.now(timezone.utc).isoformat()
        (a.output/'native_relations.log').write_text(raw.getvalue() or 'No native relation tests executed.\n',encoding='utf-8')
        report['raw_log_sha256']=sha256(a.output/'native_relations.log')
    return code


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--expected-version',required=True)
    parser.add_argument('--provision-isolated',action='store_true')
    parser.add_argument('--output',type=Path,default=ROOT/'evidence/native_mysql')
    parser.add_argument('--keep-schema',action='store_true')
    a=parser.parse_args();a.output.mkdir(parents=True,exist_ok=True)
    run_id=uuid.uuid4().hex;lock=a.output/'.native-verification.lock';tick=time.monotonic()
    try:fd=os.open(lock,os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600)
    except FileExistsError:
        print(json.dumps({'status':'BLOCKED_OUTPUT_LOCKED','run_id':run_id}));return 2
    with os.fdopen(fd,'w',encoding='utf-8') as f:
        json.dump({'run_id':run_id,'pid':os.getpid()},f);f.flush();os.fsync(f.fileno())
    report={'status':'RUNNING','run_id':run_id,'completed':False,
            'evidence_kind':'NATIVE_MYSQL_SCHEMA_AND_PROTOCOL_ONLY',
            'started_at':datetime.now(timezone.utc).isoformat(),'production_release':'BLOCKED',
            'fcp_executed':False,'schema_created':False,'checks':[],
            'requested_database_version':a.expected_version,'harness_sha256':None,'schema_sha256':None}
    code=1
    try:
        write_json(a.output/'result.json',report)
        report['harness_sha256']=sha256(Path(__file__))
        report['schema_sha256']=sha256(ROOT/'database/sql/P042__fresh_isolated_schema.sql')
        write_json(a.output/'invocation.json',{'run_id':run_id,'started_at':report['started_at'],'harness_sha256':report['harness_sha256']})
        code=_execute(a,report)
    except BaseException as exc:
        code=130 if isinstance(exc,KeyboardInterrupt) else 1
        report['status']='INTERRUPTED' if code==130 else 'ERROR';report['error_type']=type(exc).__name__
    finally:
        report['completed']=True;report['exit_code']=code
        report['ended_at']=datetime.now(timezone.utc).isoformat();report['elapsed_seconds']=round(time.monotonic()-tick,3)
        try:write_json(a.output/'result.json',report)
        finally:
            try:
                if load_json(lock).get('run_id')==run_id:lock.unlink()
            except (OSError,ValueError,TypeError):pass
    print(json.dumps(report,ensure_ascii=True,indent=2));return code

if __name__=='__main__':raise SystemExit(main())
