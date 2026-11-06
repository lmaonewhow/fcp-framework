"""Independent 4.2 regression cases derived from the 4.1 audit.
Reference-only: none of these are native FCP, browser, or MySQL acceptance.
"""
import contextlib
import copy
from datetime import datetime,timezone
import hashlib
import hmac
import io
import json
import subprocess
import sys
import tempfile
from pathlib import Path
import unittest
from unittest.mock import patch
from common import load_json,write_json
from check_design import validate
from gray_gate import decide,DEFAULT_POLICY
from audit_contract import validate_audit_event,AuditRejected
from reference.protocols import Store,Denied,Invalid,canonical_intent,canonical_scope
from reference.relational_model import open_db,insert,seed
from query_compiler import Scope,compile_query
from routing_policy import bucket,choose,RouteRejected
from tests.test_gray import valid_input
from tests import test_audit_contract as audit_fixtures
import run_checks
ROOT=Path(__file__).resolve().parents[1]
NOW='2026-11-06T00:20:10Z'

class GrayRegression(unittest.TestCase):
    def test_all_denied_aborts(self):
        d=valid_input();d['candidate'].update(committed_operations=0,denied_operations=1000)
        self.assertEqual(decide(d,now=NOW)['outcome'],'ABORT')
    def test_relative_denial_increase_aborts(self):
        d=valid_input();d['candidate'].update(committed_operations=985,denied_operations=15)
        self.assertIn('DENIAL_REGRESSION',decide(d,now=NOW)['reason_codes'])
    def test_healthy_small_equal_denials_continue(self):
        d=valid_input()
        for k in ('stable','candidate'):d[k].update(committed_operations=995,denied_operations=5)
        self.assertEqual(decide(d,now=NOW)['outcome'],'CONTINUE')
    def test_baseline_slow_pauses(self):
        d=valid_input();d['stable']['p95_ms']=900;self.assertEqual(decide(d,now=NOW)['outcome'],'PAUSE')
    def test_baseline_business_failure_pauses(self):
        d=valid_input();d['stable'].update(committed_operations=999,failed_operations=1);self.assertEqual(decide(d,now=NOW)['outcome'],'PAUSE')
    def test_baseline_denial_outage_pauses(self):
        d=valid_input();d['stable'].update(committed_operations=0,denied_operations=1000);self.assertEqual(decide(d,now=NOW)['outcome'],'PAUSE')
    def test_zero_denial_policy_is_valid(self):
        p=dict(DEFAULT_POLICY,maximum_denial_rate=0,maximum_denial_rate_increase=0)
        self.assertEqual(decide(valid_input(),p,now=NOW)['outcome'],'CONTINUE')
    def test_oversized_policy_integer_pauses(self):
        p=dict(DEFAULT_POLICY,maximum_p95_ms=10**1000)
        self.assertEqual(decide(valid_input(),p,now=NOW)['outcome'],'PAUSE')
    def test_oversized_counter_pauses(self):
        d=valid_input();d['candidate']['edge_requests']=10**1000
        self.assertEqual(decide(d,now=NOW)['outcome'],'PAUSE')
    def test_negative_denial_policy_pauses(self):
        p=dict(DEFAULT_POLICY,maximum_denial_rate=-1);self.assertEqual(decide(valid_input(),p,now=NOW)['outcome'],'PAUSE')

class InputRegression(unittest.TestCase):
    def test_decimal_range_checked(self):
        for ty in ('DECIMAL(66,4)','DECIMAL(12,31)','DECIMAL(2,4)','DECIMAL(0,0)'):
            m=load_json(ROOT/'database/model.json')
            next(c for t in m['tables'] if t['name']=='ed_eval_judgement' for c in t['columns'] if c['name']=='score')['type']=ty
            with self.subTest(ty=ty):self.assertTrue(validate(m))
    def test_decimal_boundary_valid(self):
        m=load_json(ROOT/'database/model.json');next(c for t in m['tables'] if t['name']=='ed_eval_judgement' for c in t['columns'] if c['name']=='score')['type']='DECIMAL(65,30)';self.assertEqual(validate(m),[])
    def test_oversized_student_is_contract_error(self):
        for sid in ('1'*5000,'1'*21,'0001','18446744073709551616'):
            with self.subTest(length=len(sid)),self.assertRaises(Invalid):canonical_intent({'student_id':sid,'score':'1'})
    def test_max_student_is_valid(self):
        self.assertIn('18446744073709551615',canonical_intent({'student_id':'18446744073709551615','score':'1'}))
    def test_noncanonical_audit_time_rejected(self):
        x=audit_fixtures.AuditContractTests();x.setUp()
        for value in ('20261106X000000+0000','2026-11-06X00:00:00Z','2026-11-06T00:00:00.1234567Z'):
            x.e['occurred_at']=value
            with self.subTest(value=value),self.assertRaises(AuditRejected):validate_audit_event(x.e)
    def test_exact_microseconds_utc_accepted(self):
        x=audit_fixtures.AuditContractTests();x.setUp();x.e['occurred_at']='2026-11-06T00:00:00.123456+00:00';self.assertEqual(validate_audit_event(x.e),x.e)

class SchoolScopeRegression(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.store=Store(Path(self.tmp.name)/'scope.db');self.payload={'student_id':'100','score':'1'}
        self.store.seed(scope_key='school:100');self.store.seed(scope_key='school:200')
    def tearDown(self):self.tmp.cleanup()
    def test_disable_one_school_keeps_other_school(self):
        a=self.store.prepare(release='candidate',scope_key='school:100');b=self.store.prepare(release='candidate',scope_key='school:200')
        self.store.disable_release('candidate',scope_key='school:100')
        with self.assertRaises(Denied):self.store.commit(a,'a',self.payload)
        self.assertTrue(self.store.commit(b,'b',self.payload));self.assertEqual(self.store.count('effects'),1)
    def test_disable_one_school_keeps_its_stable(self):
        stable=self.store.prepare(release='stable',scope_key='school:100');self.store.disable_release('candidate',scope_key='school:100');self.assertTrue(self.store.commit(stable,'s',self.payload))
    def test_tenant_stop_overrides_every_school(self):
        tickets=[self.store.prepare(release='candidate',scope_key=s) for s in ('school:100','school:200')]
        self.store.disable_release('candidate',scope_key='tenant')
        for i,t in enumerate(tickets):
            with self.subTest(s=t.scope_key),self.assertRaises(Denied):self.store.commit(t,str(i),self.payload)
    def test_missing_school_has_no_tenant_fallback(self):
        with self.assertRaises(Denied):self.store.prepare(scope_key='school:300')
    def test_other_school_cannot_read_receipt(self):
        a=self.store.prepare(scope_key='school:100');b=self.store.prepare(scope_key='school:200');self.store.commit(a,'intent',self.payload)
        with self.assertRaises(Denied):self.store.commit(b,'intent',self.payload)
        self.assertEqual(self.store.count('effects'),1)
    def test_school_receipt_cross_version_same_result(self):
        a=self.store.prepare(scope_key='school:100');b=self.store.prepare(release='candidate',scope_key='school:100')
        self.assertEqual(self.store.commit(a,'i',self.payload,'v1'),self.store.commit(b,'i',self.payload,'v2'));self.assertEqual(self.store.count('effects'),1)
    def test_invalid_scope_rejected(self):
        for v in ('school:01','school:0','school:18446744073709551616','school:1 ',None,[],True):
            with self.subTest(value=v),self.assertRaises(Invalid):canonical_scope(v)
    def test_actor_revoke_still_wins_school_scope(self):
        t=self.store.prepare(scope_key='school:100');self.store.revoke_actor()
        with self.assertRaises(Denied):self.store.commit(t,'x',self.payload)
    def test_old_buckets_preserved(self):
        secret=b'a'*32
        for unit in ('tenant','actor'):
            source=['r','t1','actor1' if unit=='actor' else None]
            expected=int.from_bytes(hmac.new(secret,json.dumps(source,ensure_ascii=False,separators=(',',':')).encode('utf-8'),hashlib.sha256).digest()[:8],'big')%10000
            self.assertEqual(bucket(secret,'r','t1','actor1',unit),expected)
    def test_school_bucket_stable(self):
        self.assertEqual(bucket(b'a'*32,'r','t1','100','school'),bucket(b'a'*32,'r','t1','100','school'))
        with self.assertRaises(RouteRejected):bucket(b'a'*32,'r','t1','01','school')

class NewRelations(unittest.TestCase):
    def setUp(self):self.db=open_db();seed(self.db)
    def tearDown(self):self.db.rollback();self.db.close()
    def seed_account_links(self):
        for pid in (100,200):insert(self.db,'ed_identity_person',id=pid)
        insert(self.db,'ed_identity_account_link',id=1,host_user_key='login-a',person_id=100,valid_from='2026-11-01 00:00:00.000000',valid_to='2026-11-02 00:00:00.000000')
        insert(self.db,'ed_identity_account_link',id=2,host_user_key='login-a',person_id=200,valid_from='2026-11-02 00:00:00.000000',valid_to=None)
    def test_current_account_has_only_one_pointer(self):
        self.seed_account_links();insert(self.db,'ed_identity_account_current',host_user_key='login-a',account_link_id=1)
        with self.assertRaises(self.db.integrity_errors):insert(self.db,'ed_identity_account_current',host_user_key='login-a',account_link_id=2)
    def test_current_account_cannot_reference_other_login(self):
        self.seed_account_links()
        with self.assertRaises(self.db.integrity_errors):insert(self.db,'ed_identity_account_current',host_user_key='login-b',account_link_id=2)
    def test_current_pointer_determines_person_not_history_limit(self):
        self.seed_account_links();insert(self.db,'ed_identity_account_current',host_user_key='login-a',account_link_id=2)
        rows=self.db.execute('SELECT l.person_id FROM ed_identity_account_current c JOIN ed_identity_account_link l ON c.tenant_id=l.tenant_id AND c.host_user_key=l.host_user_key AND c.account_link_id=l.id WHERE c.tenant_id=? AND c.host_user_key=?',('t1','login-a')).fetchall()
        self.assertEqual(rows,[(200,)])
    def test_null_current_has_no_fallback_person(self):
        self.seed_account_links();insert(self.db,'ed_identity_account_current',host_user_key='login-a',account_link_id=None)
        rows=self.db.execute('SELECT l.person_id FROM ed_identity_account_current c JOIN ed_identity_account_link l ON c.tenant_id=l.tenant_id AND c.host_user_key=l.host_user_key AND c.account_link_id=l.id WHERE c.tenant_id=?',('t1',)).fetchall();self.assertEqual(rows,[])
    def test_person_observation_can_have_no_class(self):insert(self.db,'ed_eval_observation',id=999,subject_kind='PERSON',subject_id=100,class_id=None)
    def test_class_observation_must_name_same_class(self):
        for cls in (None,211):
            with self.subTest(cls=cls),self.assertRaises(self.db.integrity_errors):insert(self.db,'ed_eval_observation',id=999,subject_kind='CLASS',subject_id=111,class_id=cls)
    def test_unclassified_projection_is_not_teacher_query_result(self):
        insert(self.db,'ed_reporting_timeline',id=1,person_id=100,class_id=None,source_domain='growth',source_type='home',source_id='h1',purpose_key='teacher.timeline')
        sql,args=compile_query({},Scope('t1','teacher',frozenset({'timeline.read'}),('111',),'teacher.timeline'),'sqlite')
        self.assertEqual(self.db.execute(sql,args).fetchall(),[])
    def test_two_school_grants_coexist(self):
        for scope in ('school:100','school:200'):insert(self.db,'ed_core_execution_grant',scope_key=scope,capability_key='eval',manifest_digest='a'*64)
        self.assertEqual(self.db.execute('SELECT COUNT(*) FROM ed_core_execution_grant').fetchone()[0],2)

class RunnerDurability(unittest.TestCase):
    def invoke(self,root,fn):
        with patch.object(sys,'argv',['run_checks.py','--output',str(root)]),patch.object(run_checks,'execute_checks',fn),contextlib.redirect_stdout(io.StringIO()):
            return run_checks.main()
    def test_timeout_replaces_previous_pass(self):
        with tempfile.TemporaryDirectory() as td:
            root=Path(td);write_json(root/'result.json',{'status':'PASS_LOCAL_REFERENCE_ONLY','run_id':'old'})
            def fail(out,report):raise subprocess.TimeoutExpired(['node','--test'],60)
            self.assertEqual(self.invoke(root,fail),1);r=load_json(root/'result.json');self.assertEqual(r['status'],'ERROR');self.assertNotEqual(r['run_id'],'old');self.assertEqual(r['error_type'],'TimeoutExpired')
    def test_interrupt_cannot_leave_pass(self):
        with tempfile.TemporaryDirectory() as td:
            root=Path(td)
            def stop(out,report):raise KeyboardInterrupt()
            self.assertEqual(self.invoke(root,stop),130);self.assertEqual(load_json(root/'result.json')['status'],'INTERRUPTED')
    def test_output_lock_prevents_concurrent_overwrite(self):
        with tempfile.TemporaryDirectory() as td:
            root=Path(td);(root/'.verification.lock').write_text('other',encoding='utf-8');write_json(root/'result.json',{'run_id':'other','status':'RUNNING'})
            def never(out,report):self.fail('lock was ignored')
            self.assertEqual(self.invoke(root,never),2);self.assertEqual(load_json(root/'result.json')['run_id'],'other')
    def test_running_marker_exists_before_executor(self):
        with tempfile.TemporaryDirectory() as td:
            root=Path(td)
            def succeed(out,report):
                on_disk=load_json(out/'result.json');self.assertEqual(on_disk['status'],'RUNNING');self.assertEqual(on_disk['run_id'],report['run_id']);report['status']='PASS_LOCAL_REFERENCE_ONLY';return 0
            self.assertEqual(self.invoke(root,succeed),0);r=load_json(root/'result.json');self.assertTrue(r['completed']);self.assertEqual(r['run_id'],load_json(root/'invocation.json')['run_id']);self.assertFalse((root/'.verification.lock').exists())
    def test_duplicate_test_ids_are_rejected(self):
        f=lambda: None
        suite=unittest.TestSuite([unittest.FunctionTestCase(f),unittest.FunctionTestCase(f)])
        with self.assertRaises(ValueError):run_checks.unique_test_ids(suite)
    def test_json_serialization_failure_preserves_old_complete_file(self):
        with tempfile.TemporaryDirectory() as td:
            p=Path(td)/'中文.json';write_json(p,{'old':'中文'})
            with self.assertRaises(ValueError):write_json(p,{'x':float('nan')})
            self.assertEqual(load_json(p),{'old':'中文'})
