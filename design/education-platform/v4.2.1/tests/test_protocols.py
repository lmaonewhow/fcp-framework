import concurrent.futures
from decimal import Decimal
from pathlib import Path
import tempfile
import threading
import unittest
from reference.protocols import Store,Conflict,Denied,Invalid,money_units

class ProtocolTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.path=Path(self.tmp.name)/'中文事务.db';self.s=Store(self.path);self.s.seed();self.payload={'student_id':'100','score':'1.0000'}
    def tearDown(self):self.tmp.cleanup()
    def test_contract_v1_v2_once(self):
        a=self.s.commit(self.s.prepare(),'i1',self.payload,'v1');b=self.s.commit(self.s.prepare(release='candidate'),'i1',{'student_id':'100','score':'1'},'v2');self.assertEqual(a,b);self.assertEqual(self.s.count('effects'),1)
    def test_contract_reverse_retry_once(self):
        a=self.s.commit(self.s.prepare(release='candidate'),'i1',self.payload,'v2');b=self.s.commit(self.s.prepare(),'i1',self.payload,'v1');self.assertEqual(a,b)
    def test_same_intent_changed_body_conflict(self):
        t=self.s.prepare();self.s.commit(t,'i1',self.payload)
        with self.assertRaises(Conflict):self.s.commit(t,'i1',{'student_id':'100','score':'2'})
    def test_intent_owner_checked(self):
        self.s.seed(actor='teacher2');self.s.commit(self.s.prepare(),'i1',self.payload)
        with self.assertRaises(Denied):self.s.commit(self.s.prepare(actor='teacher2'),'i1',self.payload)
    def test_candidate_stop_rejects_old_task(self):
        t=self.s.prepare(release='candidate');self.s.disable_release('candidate')
        with self.assertRaises(Denied):self.s.commit(t,'i1',self.payload)
        self.assertEqual(self.s.count('effects'),0)
    def test_candidate_stop_keeps_stable(self):
        t=self.s.prepare();self.s.disable_release('candidate');self.s.commit(t,'i1',self.payload);self.assertEqual(self.s.count('effects'),1)
    def test_actor_revocation_blocks_completed_receipt_read(self):
        t=self.s.prepare();self.s.commit(t,'i1',self.payload);self.s.revoke_actor()
        with self.assertRaises(Denied):self.s.commit(t,'i1',self.payload)
        self.assertEqual(self.s.count('effects'),1)
    def test_policy_stop_blocks_old_ticket(self):
        t=self.s.prepare();self.s.disable_policy()
        with self.assertRaises(Denied):self.s.commit(t,'i1',self.payload)
    def test_fence_survives_restart(self):
        t=self.s.prepare(release='candidate');self.s.disable_release('candidate');s2=Store(self.path)
        with self.assertRaises(Denied):s2.commit(t,'i1',self.payload)
    def test_transaction_rollback_and_denial(self):
        with self.assertRaises(Invalid):self.s.commit(self.s.prepare(),'i1',self.payload,fail_before_commit=True)
        self.assertEqual(self.s.count('effects'),0);self.assertEqual(self.s.count('audit'),0);self.assertEqual(self.s.count('denial'),1)
    def test_concurrent_retries_only_one_effect(self):
        barrier=threading.Barrier(4)
        def run(i):
            t=self.s.prepare(release='candidate' if i%2 else 'stable');barrier.wait(timeout=5);return self.s.commit(t,'i1',self.payload,'v2' if i%2 else 'v1')
        with concurrent.futures.ThreadPoolExecutor(4) as ex:r=list(ex.map(run,range(4)))
        self.assertEqual(len(set(r)),1);self.assertEqual(self.s.count('effects'),1);self.assertEqual(self.s.count('audit'),1)
    def test_concurrent_corrections_one_head(self):
        self.s.seed_series();barrier=threading.Barrier(2)
        def run(v):
            barrier.wait(timeout=5)
            try:self.s.correct(1,0,v);return 'ok'
            except Conflict:return 'conflict'
        with concurrent.futures.ThreadPoolExecutor(2) as ex:r=list(ex.map(run,('a','b')))
        self.assertCountEqual(r,['ok','conflict']);self.assertEqual(self.s.count('revision'),2)
    def test_precision_rejects_rounding(self):
        with self.assertRaises(Invalid):self.s.commit(self.s.prepare(),'i1',{'student_id':'100','score':'1.00001'})
    def test_negative_zero_normalizes(self):
        a=self.s.commit(self.s.prepare(),'i1',{'student_id':'100','score':'-0'});b=self.s.commit(self.s.prepare(),'i1',{'student_id':'100','score':'0.0000'});self.assertEqual(a,b)
    def test_other_tenant_same_intent_allowed(self):
        self.s.seed(tenant='t2');self.s.commit(self.s.prepare(),'i1',self.payload);self.s.commit(self.s.prepare(tenant='t2'),'i1',self.payload);self.assertEqual(self.s.count('effects'),2)
    def test_decimal_rejects_float_nan_overflow(self):
        for v in (1.1,True,'NaN','Infinity','9999999999999','0.00001'):
            with self.assertRaises(Invalid):money_units(v,4)
    def test_reward_zero_integer_policy(self):
        self.assertEqual(money_units('2',0),20000)
        with self.assertRaises(Invalid):money_units('2.1',0)
    def test_reward_partial_reversal_limit(self):
        self.s.seed_account();self.s.reward('award','10','AWARD');self.s.reward('r1','-6','REVERSAL',reverses='award');self.s.reward('r2','-4','REVERSAL',reverses='award')
        with self.assertRaises(Invalid):self.s.reward('r3','-1','REVERSAL',reverses='award')
    def test_reward_wrong_account_reversal(self):
        self.s.seed_account();self.s.seed_account('a2');self.s.reward('award','10','AWARD')
        with self.assertRaises(Invalid):self.s.reward('r1','-1','REVERSAL',account='a2',reverses='award')
    def test_reward_insufficient_balance(self):
        self.s.seed_account(balance='1')
        with self.assertRaises(Invalid):self.s.reward('spend','-2','SPEND')
    def test_reward_retry_dedup(self):
        self.s.seed_account();self.assertTrue(self.s.reward('award','10','AWARD'));self.assertFalse(self.s.reward('award','10.0000','AWARD'))
    def test_reward_sign(self):
        self.s.seed_account()
        with self.assertRaises(Invalid):self.s.reward('award','-1','AWARD')
    def test_reward_reverse_reversal_forbidden(self):
        self.s.seed_account();self.s.reward('award','10','AWARD');self.s.reward('r1','-1','REVERSAL',reverses='award')
        with self.assertRaises(Invalid):self.s.reward('r2','1','REVERSAL',reverses='r1')
