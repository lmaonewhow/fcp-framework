import copy
from types import SimpleNamespace
import unittest
from ui_navigation_evidence import NavigationEvidence
from ui_tenant_navigation_recovery import is_proved_tenant_limit
from test_ui_navigation_evidence import Fixture,Page,Response,BASE
from test_ui_tenant_navigation_recovery import limited

class NavigationReviewTests(unittest.TestCase):
    def collector(self):
        f=Fixture();p=Page();c=NavigationEvidence(p,f,BASE,'admin','rollback');c.begin('navigate')
        return c,f,p

    def test_inflight_getinfo_blocks_otherwise_proved_limit(self):
        c,f,p=self.collector()
        request=SimpleNamespace(url=BASE+'/system/user/getInfo',resource_type='fetch')
        handler=p.handlers.get('request')
        if handler:handler(request)
        c.current.update({key:value for key,value in limited().items() if key not in ('inflightApiCount',)})
        self.assertFalse(is_proved_tenant_limit(c.current))

    def test_header_exception_after_finish_never_mutates_closed_document(self):
        c,f,p=self.collector();r=Response(path='/admin/fcp/plugins')
        snapshot=[]
        def header(name):
            c.finish();snapshot.append(copy.deepcopy(f.report));raise RuntimeError('closed')
        r.header_value=header;c.response(r)
        self.assertEqual(f.report,snapshot[0])
        self.assertEqual(len(snapshot),1)

    def test_gate_pumps_queued_body_and_finish_without_discarding_error(self):
        c,f,p=self.collector();now=[0.0]
        request=SimpleNamespace(url=BASE+'/auth/tenant/list',resource_type='fetch')
        p.handlers['request'](request)
        response=c.append('response',endpoint='/auth/tenant/list',status=200,bodyRead='pending')
        c.page_error(SimpleNamespace(name='Error',message='访问过于频繁，请稍候再试 (Trace ID: '+'a'*32+')'))
        self.assertFalse(is_proved_tenant_limit(c.current))
        calls=[]
        def pump(milliseconds):
            calls.append(milliseconds);now[0]+=milliseconds/1000
            if len(calls)==2:
                response.update(bodyRead='json',businessCode=500,category='native-rate-limit',traceId='a'*32)
                p.handlers['requestfinished'](request)
        self.assertTrue(c.settle(pump=pump,clock=lambda:now[0]))
        self.assertEqual(c.current['pageErrorCount'],1)
        self.assertEqual(c.current['inflightApiCount'],0)
        self.assertTrue(is_proved_tenant_limit(c.current))
        self.assertLessEqual(now[0],2)

    def test_unanswered_request_and_pending_body_timeout_are_not_recovered(self):
        for pending_body in (False,True):
            c,f,p=self.collector();now=[0.0]
            c.current.update(limited())
            if pending_body:
                c.append('response',endpoint='/system/user/getInfo',status=200,bodyRead='pending')
            else:
                p.handlers['request'](SimpleNamespace(url=BASE+'/system/user/getInfo',resource_type='fetch'))
            def pump(milliseconds):now[0]+=milliseconds/1000
            self.assertFalse(c.settle(pump=pump,clock=lambda:now[0]))
            self.assertEqual(c.current['settleStatus'],'TIMEOUT')
            self.assertFalse(is_proved_tenant_limit(c.current))
            self.assertLessEqual(now[0],2.001)
            with self.assertRaises(AssertionError):c.check_clean('uncaught')
            self.assertEqual(c.current['pageErrorCount'],1)

    def test_sse_is_separate_and_duplicate_finish_does_not_clear_other_request(self):
        c,f,p=self.collector()
        sse=SimpleNamespace(url=BASE+'/resource/sse?token=private',resource_type='fetch')
        a=SimpleNamespace(url=BASE+'/system/user/getInfo',resource_type='fetch')
        b=SimpleNamespace(url=BASE+'/unknown-private-api',resource_type='xhr',failure='net::ERR_FAILED')
        for request in (sse,a,b):p.handlers['request'](request)
        self.assertEqual(c.current['streamRequestCount'],1)
        self.assertEqual(c.current['inflightApiCount'],2)
        p.handlers['requestfinished'](a);p.handlers['requestfinished'](a)
        self.assertEqual(c.current['inflightApiCount'],1)
        p.handlers['requestfailed'](b)
        self.assertEqual(c.current['inflightApiCount'],0)
        self.assertTrue(c.settle(pump=lambda ms:None))
        self.assertEqual(c.current['events'][-1]['kind'],'requestfailed')
        c.finish();before=copy.deepcopy(f.report)
        for event,request in [('requestfinished',b),('requestfailed',b),('request',a)]:p.handlers[event](request)
        self.assertEqual(f.report,before)

    def test_closed_body_exception_and_old_phase_completion_are_immutable(self):
        c,f,p=self.collector();r=Response(body={})
        snapshots=[]
        def body():
            c.finish();snapshots.append(copy.deepcopy(f.report));raise RuntimeError('closed')
        r.json=body;c.response(r)
        self.assertEqual(f.report,snapshots[0])
        c,f,p=self.collector()
        a=SimpleNamespace(url=BASE+'/system/user/getInfo',resource_type='fetch')
        p.handlers['request'](a)
        old=c.current;c.begin('refresh');p.handlers['requestfinished'](a)
        self.assertEqual(old['inflightApiCount'],1)
        self.assertEqual(c.current['inflightApiCount'],0)
        before=copy.deepcopy(f.report)
        a.failure='net::ERR_ABORTED';p.handlers['requestfailed'](a)
        r=Response();r.request=a;c.response(r)
        self.assertEqual(f.report,before)

if __name__=='__main__':unittest.main()
