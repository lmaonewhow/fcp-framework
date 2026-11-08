import copy
import unittest
from ui_tenant_navigation_recovery import (NativeTenantNavigationLimited,
    is_proved_tenant_limit, run_with_tenant_limit_recovery)
from test_ui_navigation_evidence import Fixture

TRACE='a'*32


def limited():
    return dict(shell='admin',stage='rollback',phase='navigate',pageErrorCount=1,droppedEvents=0,
        inflightApiCount=0,requestTrackingOverflow=False,settleStatus='COMPLETE',
        events=[dict(kind='response',endpoint='/auth/tenant/list',status=200,businessCode=500,
                     bodyRead='json',category='native-rate-limit',traceId=TRACE),
                dict(kind='pageerror',category='native-rate-limit',traceId=TRACE)])


class TenantNavigationRecoveryTests(unittest.TestCase):
    def test_exact_same_trace_only_and_known_statuses(self):
        for status,code in ((200,500),(500,500),(200,429),(429,429)):
            n=limited();n['events'][0].update(status=status,businessCode=code)
            self.assertTrue(is_proved_tenant_limit(n))

    def test_every_ambiguous_or_extra_failure_refuses_retry(self):
        changes=[('shell','teacher'),('phase','refresh'),('phase','retry-navigate'),
                 ('pageErrorCount',0),('pageErrorCount',2),('droppedEvents',1)]
        for key,value in changes:
            n=limited();n[key]=value
            with self.subTest(key=key,value=value):self.assertFalse(is_proved_tenant_limit(n))
        response_changes=[('endpoint','/auth/code'),('traceId','b'*32),('status',401),('status',403),
                          ('status',502),('businessCode',200),('businessCode',None),
                          ('category','unclassified-redacted'),('bodyRead','pending'),
                          ('bodyRead','unavailable'),('bodyRead','non-object')]
        for key,value in response_changes:
            n=limited();n['events'][0][key]=value
            with self.subTest(key=key,value=value):self.assertFalse(is_proved_tenant_limit(n))
        for key,value in [('category','unclassified-redacted'),('traceId','b'*32),('traceId','0'*32),('traceId','INVALID')]:
            n=limited();n['events'][1][key]=value
            self.assertFalse(is_proved_tenant_limit(n))
        for extra in [dict(kind='requestfailed'),dict(kind='pageerror'),
                      dict(kind='response',status=500,bodyRead='json',businessCode=500),
                      dict(kind='response',status=200,bodyRead='json',businessCode=401),
                      dict(kind='response',status=200,bodyRead='pending'),
                      dict(kind='response',status=200,bodyRead='json',businessCode=None),
                      limited()['events'][0]]:
            n=limited();n['events'].append(extra)
            self.assertFalse(is_proved_tenant_limit(n))

    def test_context_closed_before_wait_and_one_attempt_then_original_failure_kept(self):
        f=Fixture();n=limited();f.report['uiNavigations']=[n];before=copy.deepcopy(n)
        order=[];now=[0]
        def attempt(number):
            try:
                order.append('open'+str(number))
                if number==0:raise NativeTenantNavigationLimited(n)
                return 'verified gates'
            finally:order.append('close'+str(number))
        def sleep(seconds):
            self.assertEqual(order[-1],'close0');self.assertEqual(seconds,60)
            self.assertEqual(f.saved[-1]['uiNavigations'][0],before)
            order.append('wait');now[0]+=seconds
        result=run_with_tenant_limit_recovery(attempt,f,clock=lambda:now[0],sleep=sleep)
        self.assertEqual(result,'verified gates')
        self.assertEqual(order,['open0','close0','wait','open1','close1'])
        self.assertEqual(n,before)
        self.assertEqual(f.report['uiNavigationRecovery'][0]['status'],'PASS_AFTER_WINDOW')
        self.assertEqual(f.report['uiNavigationRecovery'][0]['waitedSeconds'],60)

    def test_second_failure_and_unknown_failures_are_never_retried(self):
        for failure in (AssertionError('uncaught'),NativeTenantNavigationLimited(limited())):
            f=Fixture();calls=[];now=[0]
            def attempt(number):
                calls.append(number)
                if number==0:raise NativeTenantNavigationLimited(limited())
                raise failure
            def sleep(seconds):now[0]+=seconds
            with self.assertRaises(type(failure)):
                run_with_tenant_limit_recovery(attempt,f,clock=lambda:now[0],sleep=sleep)
            self.assertEqual(calls,[0,1])
            self.assertEqual(f.report['uiNavigationRecovery'][0]['status'],'FAIL_RETRY')
        f=Fixture();calls=[]
        def unknown(number):calls.append(number);raise AssertionError('unknown')
        with self.assertRaises(AssertionError):
            run_with_tenant_limit_recovery(unknown,f,sleep=lambda s:self.fail('Unexpected wait'))
        self.assertEqual(calls,[0])

    def test_early_wakeup_and_unproved_exception_do_not_retry(self):
        f=Fixture();calls=[]
        def attempt(number):calls.append(number);raise NativeTenantNavigationLimited(limited())
        with self.assertRaisesRegex(RuntimeError,'window'):
            run_with_tenant_limit_recovery(attempt,f,clock=lambda:0,sleep=lambda s:None)
        self.assertEqual(calls,[0])
        self.assertEqual(f.report['uiNavigationRecovery'][0]['status'],'FAIL_INCOMPLETE_WINDOW')
        n=limited();n['events'][0]['traceId']='b'*32
        def unproved(number):raise NativeTenantNavigationLimited(n)
        with self.assertRaises(NativeTenantNavigationLimited):
            run_with_tenant_limit_recovery(unproved,Fixture(),sleep=lambda s:self.fail('Unexpected wait'))

if __name__=='__main__':unittest.main()
