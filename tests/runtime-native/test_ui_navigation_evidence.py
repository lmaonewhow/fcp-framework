"""Local collector tests only; no native acceptance or browser pass implied."""
import copy
import json
from types import SimpleNamespace
import unittest
from ui_navigation_evidence import NavigationEvidence, message_category, numeric_code

BASE = 'http://127.0.0.1:18787'
SECRET = 'private-token-must-never-appear'


class Fixture:
    def __init__(self):self.report={};self.saved=[]
    def _write(self):self.saved.append(copy.deepcopy(self.report))
    def check(self, label, condition):
        if not condition:raise AssertionError(label)


class Page:
    url = BASE+'/admin/login?token='+SECRET
    def __init__(self):self.handlers={}
    def on(self, name, fn):self.handlers[name]=fn


class Response:
    def __init__(self, path='/system/user/getInfo', body=None, status=200):
        self.url=BASE+path+'?token='+SECRET;self.status=status;self.body=body
    def header_value(self, name):
        return 'a'*32 if name=='x-trace-id' else SECRET
    def json(self):
        if isinstance(self.body, Exception):raise self.body
        return self.body


class NavigationEvidenceTests(unittest.TestCase):
    def collector(self):
        fixture=Fixture();page=Page()
        collector=NavigationEvidence(page,fixture,BASE,'admin','rollback',clock=lambda:1)
        collector.begin('navigate')
        return collector,fixture,page

    def test_failure_is_persisted_before_assert_with_only_safe_fields(self):
        c,f,p=self.collector()
        c.response(Response(body={'code':500,'msg':'访问过于频繁，请稍候再试','data':SECRET}))
        c.page_error(SimpleNamespace(name='Error',message=SECRET,stack=SECRET))
        with self.assertRaisesRegex(AssertionError,'uncaught'):
            c.check_clean('uncaught')
        saved=f.saved[-1]['uiNavigations'][0]
        self.assertEqual(saved['pageErrorCount'],1)
        self.assertEqual(saved['location'],'/admin/login')
        self.assertEqual(saved['events'][0]['category'],'native-rate-limit')
        self.assertEqual(saved['events'][0]['businessCode'],500)
        self.assertEqual(saved['events'][0]['traceId'],'a'*32)
        self.assertNotIn('requestId',saved['events'][0])
        self.assertNotIn(SECRET,json.dumps(f.report))
        self.assertNotIn('访问',json.dumps(f.report,ensure_ascii=False))
        c.finish()
        c.page_error(SimpleNamespace(name='Error',message='Failed to fetch'))
        c.request_failed(SimpleNamespace(url=BASE+'/fcp/ui/catalog',failure='net::ERR_ABORTED'))
        self.assertEqual(f.report,f.saved[-1])

    def test_unknown_paths_and_origins_are_omitted(self):
        c,f,p=self.collector()
        for url in (BASE+'/'+SECRET, 'https://example.com/system/user/getInfo',BASE+'.evil/system/user/getInfo'):
            r=Response();r.url=url;c.response(r)
        self.assertEqual(c.current['events'],[])
        p.url=BASE+'/'+SECRET;c.persist()
        self.assertEqual(c.current['location'],'other-redacted')
        self.assertNotIn(SECRET,json.dumps(f.report))

    def test_http_business_and_transport_failures_are_distinct(self):
        c,f,p=self.collector()
        for code in (401,403,500):
            c.response(Response(body={'code':code,'msg':SECRET},status=200))
        c.response(Response(body=RuntimeError(SECRET),status=500))
        c.request_failed(SimpleNamespace(url=BASE+'/system/user/getInfo',failure='net::ERR_ABORTED'))
        c.request_failed(SimpleNamespace(url=BASE+'/fcp/ui/catalog',failure=SECRET))
        events=c.current['events']
        self.assertEqual([e['businessCode'] for e in events[:3]],[401,403,500])
        self.assertEqual(events[3]['bodyRead'],'unavailable')
        self.assertEqual(events[4]['failure'],'net::ERR_ABORTED')
        self.assertEqual(events[5]['failure'],'other-redacted')
        self.assertNotIn(SECRET,json.dumps(f.report))

    def test_sse_and_documents_never_read_body(self):
        c,f,p=self.collector()
        for path in ('/resource/sse','/admin/fcp/plugins','/fcp/ui/assets/'+SECRET):
            r=Response(path=path,status=404)
            def forbidden():self.fail('Non-JSON response read')
            r.json=forbidden;c.response(r)
        self.assertEqual(len(c.current['events']),3)
        self.assertNotIn(SECRET,json.dumps(f.report))

    def test_bounded_events_never_hide_uncaught_errors_and_refresh_is_separate(self):
        c,f,p=self.collector()
        for _ in range(202):c.page_error(SimpleNamespace(name='TypeError',message='Failed to fetch'))
        self.assertEqual(len(c.current['events']),200)
        self.assertEqual(c.current['pageErrorCount'],202)
        self.assertEqual(c.current['droppedEvents'],2)
        with self.assertRaises(AssertionError):c.check_clean('uncaught')
        c.begin('refresh');c.check_clean('refresh')
        self.assertEqual(len(f.report['uiNavigations']),2)
        self.assertEqual(f.report['uiNavigations'][0]['pageErrorCount'],202)

    def test_codes_and_messages_are_strictly_classified_not_copied(self):
        for code in (True,None,{},[],SECRET,-1,1000000):self.assertIsNone(numeric_code(code))
        for message in (None,{},[],SECRET,'访问过于频繁，请稍候再试 '+SECRET):
            self.assertEqual(message_category(message),'unclassified-redacted')
        self.assertEqual(message_category('Request failed with status code 403'),'http-403')

if __name__=='__main__':unittest.main()
