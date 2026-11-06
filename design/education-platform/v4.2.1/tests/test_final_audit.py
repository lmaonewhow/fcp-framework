"""4.2.1 regression: local code/relational model and explicitly simulated driver contracts.
No PyMySQL server, FCP or browser execution is implied by these tests.
"""
import contextlib
import copy
import io
import sys
import tempfile
import types
import unittest
from pathlib import Path
from unittest.mock import patch
from common import load_json,write_json
from check_design import validate
from gray_gate import decide,DEFAULT_POLICY
from query_compiler import Scope,Rejected,compile_query
from audit_contract import validate_audit_event,AuditRejected
from reference.relational_model import open_db,DB,insert
from mysql_support import initialize_session,SQL_MODE,NativeConstraintViolation
from tests.test_gray import valid_input
from tests import test_audit_contract as audit_fixture
import mysql_acceptance
ROOT=Path(__file__).resolve().parents[1]
NOW='2026-11-06T00:20:10Z'

class ProjectionTests(unittest.TestCase):
    def setUp(self):
        self.db=open_db();self.scope=Scope('t1','teacher',frozenset({'timeline.read'}),('111',),'teacher.timeline')
    def tearDown(self):self.db.rollback();self.db.close()
    def add(self,id,kind,visible=1,**kw):
        insert(self.db,'ed_reporting_timeline',id=id,person_id=100,class_id=111,source_domain='evaluation',
               source_type='observation',source_id='420',purpose_key='teacher.timeline',projection_kind=kind,is_visible=visible,**kw)
    def rows(self):
        q,a=compile_query({'fields':['id']},self.scope,'sqlite');return self.db.execute(q,a).fetchall()
    def test_formal_and_shadow_only_formal_visible(self):
        self.add(1,'timeline-v1');self.add(2,'shadow-v2');self.assertEqual(self.rows(),[(1,)])
    def test_shadow_only_does_not_become_formal(self):self.add(2,'shadow-v2');self.assertEqual(self.rows(),[])
    def test_withdrawn_formal_does_not_fallback_to_shadow(self):
        self.add(1,'timeline-v1',visible=0);self.add(2,'shadow-v2');self.assertEqual(self.rows(),[])
    def test_unknown_projection_does_not_leak(self):self.add(3,'timeline-v99');self.assertEqual(self.rows(),[])
    def test_client_cannot_select_projection(self):
        for request in ({'projection_kind':'shadow-v2'},{'filters':{'projection_kind':'shadow-v2'}}):
            with self.subTest(request=request),self.assertRaises(Rejected):compile_query(request,self.scope)
    def test_sql_example_pins_same_projection(self):
        s=(ROOT/'database/sql/Q042__reviewed_read_EXAMPLE.sql').read_text(encoding='utf-8-sig')
        self.assertIn("projection_kind='timeline-v1'",s)

class EmergencyPriorityTests(unittest.TestCase):
    def breached(self,policy):
        d=valid_input();d['confirmed_invariant_breach']=True;return decide(d,policy,now=NOW)
    def test_invalid_rate_policy_does_not_downgrade_harm(self):
        p=copy.deepcopy(DEFAULT_POLICY);p['minimum_coverage']=2;self.assertEqual(self.breached(p)['outcome'],'ABORT')
    def test_missing_policy_keys_still_abort(self):self.assertEqual(self.breached({})['outcome'],'ABORT')
    def test_wrong_policy_shape_still_abort(self):self.assertEqual(self.breached([])['outcome'],'ABORT')
    def test_nonfinite_policy_still_abort(self):
        p=copy.deepcopy(DEFAULT_POLICY);p['maximum_p95_ms']=float('nan');r=self.breached(p);self.assertEqual(r['outcome'],'ABORT');self.assertIsNone(r['policy_hash'])
    def test_hard_breach_and_missing_metrics_abort(self):self.assertEqual(decide({'confirmed_invariant_breach':True},{})['outcome'],'ABORT')
    def test_invalid_policy_without_confirmed_harm_pauses(self):self.assertEqual(decide(valid_input(),{},now=NOW)['outcome'],'PAUSE')

class TypeRangeTests(unittest.TestCase):
    def setUp(self):self.m=load_json(ROOT/'database/model.json');self.t=next(t for t in self.m['tables'] if t['name']=='ed_identity_person')
    def column(self):return next(c for c in self.t['columns'] if c['name']=='private_ref')
    def test_char_256_rejected(self):self.column()['type']='CHAR(256)';self.assertTrue(validate(self.m))
    def test_varchar_65536_rejected(self):self.column()['type']='VARCHAR(65536)';self.assertTrue(validate(self.m))
    def test_utf8mb4_width_exceeding_row_bound_rejected(self):self.column()['type']='VARCHAR(16384)';self.assertTrue(validate(self.m))
    def test_multiple_valid_strings_exceeding_row_bound_rejected(self):
        for i in range(5):self.t['columns'].append(dict(self.column(),name='large_'+str(i),type='VARCHAR(4000)'))
        self.assertTrue(validate(self.m))
    def test_char_255_and_varchar_100_valid(self):
        self.column()['type']='CHAR(255)';self.assertEqual(validate(self.m),[])
        self.column()['type']='VARCHAR(100)';self.assertEqual(validate(self.m),[])
    def test_enormous_numeric_declaration_is_rejected_without_crash(self):
        self.column()['type']='VARCHAR('+'9'*5000+')';self.assertTrue(validate(self.m))

class AuditRangeTests(unittest.TestCase):
    def event(self,time):
        x=audit_fixture.AuditContractTests();x.setUp();x.e['occurred_at']=time;return x.e
    def test_year_before_mysql_supported_range_rejected(self):
        with self.assertRaises(AuditRejected):validate_audit_event(self.event('0999-12-31T23:59:59Z'))
    def test_mysql_lower_supported_boundary_accepted(self):validate_audit_event(self.event('1000-01-01T00:00:00Z'))
    def test_mysql_documented_upper_boundary_accepted(self):validate_audit_event(self.event('9999-12-31T23:59:59.499999Z'))
    def test_above_documented_upper_boundary_rejected(self):
        with self.assertRaises(AuditRejected):validate_audit_event(self.event('9999-12-31T23:59:59.500000Z'))

class FakeMySQLError(Exception):pass
class FakeOperationalError(FakeMySQLError):pass
class FakeIntegrityError(FakeMySQLError):pass
DRIVER=types.SimpleNamespace(MySQLError=FakeMySQLError,OperationalError=FakeOperationalError,IntegrityError=FakeIntegrityError)
class SimulatedCursor:
    def __init__(self,rows=(),error=None):self.rows=rows;self.error=error;self.statements=[];self.closed=False
    def __enter__(self):return self
    def __exit__(self,*args):self.close()
    def execute(self,sql,args=()):
        self.statements.append((sql,args))
        if self.error:raise self.error
    def fetchall(self):return self.rows
    def fetchone(self):return self.rows[0] if self.rows else None
    def close(self):self.closed=True
class SimulatedConnection:
    def __init__(self,cursor):self.c=cursor
    def cursor(self):return self.c

class NativeAdapterContractTests(unittest.TestCase):
    def db(self,cursor):
        with patch.dict(sys.modules,{'pymysql':DRIVER}):return DB(SimulatedConnection(cursor),'mysql')
    def test_infrastructure_errors_cannot_pass_constraint_test(self):
        for code in (1054,1205,1213,2006,2013):
            c=SimulatedCursor(error=FakeOperationalError(code,'simulated'));d=self.db(c)
            with self.subTest(code=code),self.assertRaises(FakeOperationalError):d.execute('SELECT 1')
            self.assertTrue(c.closed)
    def test_expected_constraint_codes_are_classified(self):
        for code in (1048,1062,1451,1452,3819):
            c=SimulatedCursor(error=FakeOperationalError(code,'simulated'));d=self.db(c)
            with self.subTest(code=code),self.assertRaises(NativeConstraintViolation) as caught:d.execute('INSERT INTO x VALUES (?)',(1,))
            self.assertEqual(caught.exception.mysql_errno,code)
    def test_fetchall_tuple_normalized_to_list(self):self.assertEqual(self.db(SimulatedCursor(((200,),))).execute('SELECT person_id').fetchall(),[(200,)])
    def test_empty_tuple_normalized_to_list(self):self.assertEqual(self.db(SimulatedCursor(())).execute('SELECT person_id').fetchall(),[])
    def test_session_profile_initialized_and_checked(self):
        c=SimulatedCursor((('+00:00',SQL_MODE,1,'REPEATABLE-READ'),));r=initialize_session(SimulatedConnection(c))
        self.assertEqual(r['time_zone'],'+00:00');self.assertTrue(any('sql_mode =' in s for s,_ in c.statements));self.assertTrue(c.closed)
    def test_wrong_session_mode_rejected(self):
        c=SimulatedCursor((('+00:00','',1,'REPEATABLE-READ'),))
        with self.assertRaises(RuntimeError):initialize_session(SimulatedConnection(c))
    def test_disabled_foreign_keys_rejected(self):
        c=SimulatedCursor((('+00:00',SQL_MODE,0,'REPEATABLE-READ'),))
        with self.assertRaises(RuntimeError):initialize_session(SimulatedConnection(c))

class NativeRunRecordTests(unittest.TestCase):
    def invoke(self,root,execute):
        argv=['mysql_acceptance.py','--expected-version','8.4.2','--output',str(root)]
        with patch.object(sys,'argv',argv),patch.object(mysql_acceptance,'_execute',execute),contextlib.redirect_stdout(io.StringIO()):return mysql_acceptance.main()
    def test_native_error_replaces_old_pass(self):
        with tempfile.TemporaryDirectory() as td:
            root=Path(td);write_json(root/'result.json',{'status':'PASS_NATIVE_DB_ONLY','run_id':'old'})
            def fail(a,report):raise RuntimeError('simulated')
            self.assertEqual(self.invoke(root,fail),1);r=load_json(root/'result.json');self.assertEqual(r['status'],'ERROR');self.assertNotEqual(r['run_id'],'old')
    def test_native_interrupt_replaces_old_pass(self):
        with tempfile.TemporaryDirectory() as td:
            root=Path(td)
            def stop(a,report):raise KeyboardInterrupt()
            self.assertEqual(self.invoke(root,stop),130);self.assertEqual(load_json(root/'result.json')['status'],'INTERRUPTED')
    def test_native_lock_prevents_overwrite(self):
        with tempfile.TemporaryDirectory() as td:
            root=Path(td);(root/'.native-verification.lock').write_text('other',encoding='utf-8')
            def never(a,report):self.fail('lock ignored')
            self.assertEqual(self.invoke(root,never),2)
    def test_native_running_record_precedes_execution(self):
        with tempfile.TemporaryDirectory() as td:
            root=Path(td)
            def notrun(a,report):
                r=load_json(root/'result.json');self.assertEqual(r['status'],'RUNNING');self.assertEqual(r['run_id'],report['run_id']);report['status']='NOT_RUN';return 2
            self.assertEqual(self.invoke(root,notrun),2);self.assertEqual(load_json(root/'result.json')['exit_code'],2)

    def test_native_missing_input_cannot_leave_previous_pass(self):
        with tempfile.TemporaryDirectory() as td:
            root=Path(td);write_json(root/'result.json',{'status':'PASS_NATIVE_DB_ONLY','run_id':'old'})
            def never(a,report):self.fail('executor called despite missing input')
            with patch.object(mysql_acceptance,'sha256',side_effect=FileNotFoundError('missing fixture')):
                self.assertEqual(self.invoke(root,never),1)
            r=load_json(root/'result.json');self.assertEqual(r['status'],'ERROR');self.assertNotEqual(r['run_id'],'old')
