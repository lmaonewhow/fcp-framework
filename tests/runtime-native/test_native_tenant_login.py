"""Parser/control-flow doubles, not real native browser acceptance."""
import json
from pathlib import Path
import sys
import unittest
sys.path.insert(0, str(Path(__file__).resolve().parents[2]/'tools'))
from native_login import NativeLoginPreparationError, open_native_tenant_login, parse_native_tenant_list
from test_native_captcha import BASE, CHALLENGE, Clock, Response

TENANT = '/auth/tenant/list'
CODE = '/auth/code'
def tenant(data=None):
    return Response(dict(code=200, data=data if data is not None else dict(tenantEnabled=True,
                    voList=[dict(tenantId='960001', companyName='Synthetic CI tenant')])), url=BASE+TENANT)
def challenge(marker='a'):
    return Response(dict(code=200, data=dict(CHALLENGE, uuid=marker*32)))
def throttle(endpoint=TENANT, message='访问过于频繁，请稍候再试', status=200):
    return Response(dict(code=500, msg=message), status=status, url=BASE+endpoint)

class Page:
    def __init__(self, attempts, clock, stale=None):
        self.attempts, self.clock, self.stale = attempts, clock, stale
        self.listeners, self.waiters, self.navigations = {}, [], []
    def on(self, event, callback):self.listeners.setdefault(event, []).append(callback)
    def remove_listener(self, event, callback):self.listeners[event].remove(callback)
    def emit(self, event, obj):
        for callback in self.listeners.get(event, []):callback(obj)
    def expect_response(self, predicate, **kwargs):
        class Waiter:
            value = None
            def __enter__(self):return self
            def __exit__(self, *exc):
                if exc[0]:return
                if self.value is None:raise TimeoutError('sensitive details must not propagate')
        waiter=Waiter();self.waiters.append((predicate, waiter));return waiter
    def goto(self, url, **kwargs):
        attempt=self.attempts[len(self.navigations)]
        self.navigations.append((url,self.clock()))
        for response in ([self.stale] if self.stale else [])+attempt:
            class Request:
                method='GET'
            response.request=Request();response.request.url=response.url
            if response is not self.stale:self.emit('request',response.request)
            self.emit('response',response)
            for predicate,waiter in self.waiters:
                if predicate(response):waiter.value=response
        self.waiters=[]

class TenantLoginTests(unittest.TestCase):
    def run_flow(self, attempts, stale=None):
        clock=Clock();page=Page(attempts,clock,stale);events=[]
        result=open_native_tenant_login(page,BASE,events=events,clock=clock,sleep=clock.sleep)
        self.assertTrue(all(not v for v in page.listeners.values()))
        return result,page,clock,events
    def fail_flow(self, attempts):
        clock=Clock();page=Page(attempts,clock);events=[]
        with self.assertRaises(NativeLoginPreparationError) as error:
            open_native_tenant_login(page,BASE,events=events,clock=clock,sleep=clock.sleep)
        self.assertTrue(all(not v for v in page.listeners.values()))
        return error.exception,page,clock,events
    def test_both_response_orders_and_exact_target(self):
        for responses in ([challenge(),tenant()],[tenant(),challenge()]):
            result,page,clock,events=self.run_flow([responses])
            self.assertEqual(result['uuid'],'a'*32);self.assertEqual(len(page.navigations),1)
            self.assertEqual(clock.waits,[]);self.assertEqual(events,[])
    def test_precise_throttle_reloads_both_and_returns_fresh_captcha(self):
        for endpoint in (CODE,TENANT):
            for msg in ('访问过于频繁，请稍候再试','Visit too frequently, please try again later'):
                first=[challenge(),throttle(message=msg)] if endpoint==TENANT else [throttle(CODE,msg),tenant()]
                result,page,clock,events=self.run_flow([first,[challenge('b'),tenant()]])
                self.assertEqual(result['uuid'],'b'*32);self.assertEqual(clock.waits,[60.0])
                self.assertEqual([t for _,t in page.navigations],[0,60])
                self.assertEqual(events[0]['endpoint'],endpoint)
    def test_non_json_http429_is_explicit_limit(self):
        limited=Response(ValueError('private'),status=429,url=BASE+TENANT)
        self.assertEqual(self.run_flow([[challenge(),limited],[challenge(),tenant()]])[2].waits,[60.0])
    def test_second_throttle_stops_and_never_reuses_old_success(self):
        error,page,clock,_=self.fail_flow([[challenge(),throttle()],[challenge('b'),throttle()]])
        self.assertEqual(len(page.navigations),2);self.assertEqual(clock.waits,[60.0]);self.assertTrue(error.limited)
        error,_,_,_=self.fail_flow([[challenge(),throttle()],[challenge('b')]])
        self.assertEqual(error.diagnostic['stage'],'response-not-observed')
    def test_invalid_envelopes_auth_and_missing_target_never_retry(self):
        bad=[Response(ValueError('private'),url=BASE+TENANT),Response([],url=BASE+TENANT),
             Response(dict(code=403,msg='访问过于频繁，请稍候再试'),status=403,url=BASE+TENANT),
             throttle(message='Database private error'),throttle(message=['访问过于频繁，请稍候再试']),
             tenant(dict(tenantEnabled=False,voList=[])),tenant(dict(tenantEnabled=True,voList=[])),
             tenant(dict(tenantEnabled=True,voList=[dict(tenantId='960002',companyName='Synthetic CI tenant')])),
             tenant(dict(tenantEnabled=True,voList=[dict(tenantId='960001',companyName='wrong')])),
             Response(dict(code='private-secret',data=None),url=BASE+TENANT)]
        for response in bad:
            error,page,clock,events=self.fail_flow([[challenge(),response]])
            self.assertEqual(len(page.navigations),1);self.assertEqual(clock.waits,[])
            self.assertNotIn('private',json.dumps(error.diagnostic)+str(error)+json.dumps(events))
    def test_concurrent_fatal_beats_throttle_without_wait(self):
        error,_,clock,_=self.fail_flow([[throttle(CODE),tenant(dict(tenantEnabled=True,voList=[]))]])
        self.assertEqual(error.diagnostic['stage'],'target-tenant-missing');self.assertEqual(clock.waits,[])
    def test_unobserved_response_no_retry_and_stale_response_not_accepted(self):
        error,_,clock,_=self.fail_flow([[challenge()]])
        self.assertEqual(error.diagnostic['endpoint'],TENANT);self.assertEqual(clock.waits,[])
        clock=Clock();page=Page([[challenge()]],clock,stale=tenant())
        with self.assertRaises(NativeLoginPreparationError):
            open_native_tenant_login(page,BASE,clock=clock,sleep=clock.sleep)
    def test_disabled_captcha_remains_fatal(self):
        bad=Response(dict(code=200,data=dict(CHALLENGE,captchaEnabled=False)))
        error,_,clock,_=self.fail_flow([[bad,tenant()]])
        self.assertEqual(error.diagnostic['stage'],'invalid-challenge');self.assertEqual(clock.waits,[])
    def test_incomplete_rate_window_and_non_loopback_never_reload(self):
        clock=Clock();page=Page([[challenge(),throttle()]],clock)
        with self.assertRaises(NativeLoginPreparationError) as error:
            open_native_tenant_login(page,BASE,clock=clock,sleep=lambda _:None)
        self.assertEqual(error.exception.diagnostic['stage'],'incomplete-rate-window')
        self.assertEqual(len(page.navigations),1)
        with self.assertRaises(ValueError):open_native_tenant_login(None,'https://remote.invalid')
    def test_ui_gate_and_safe_report_remain_connected(self):
        source=(Path(__file__).parent/'ui_boundaries.py').read_text()
        self.assertLess(source.index('captcha = open_native_tenant_login('),source.index("select = page.locator('.login-form .el-select')"))
        self.assertIn("report['loginFailure'] = error.diagnostic",source)
        self.assertIn("report['loginPreparation'] = getattr(tenant_login, 'diagnostics', [])",source)
        self.assertIn("'option-not-selectable', 200, '200'",source)
    def test_origin_mismatch_cannot_supply_tenant(self):
        response=tenant();response.url='https://evil.example'+TENANT
        error,_,_,_=self.fail_flow([[challenge(),response]])
        self.assertEqual(error.diagnostic['stage'],'response-not-observed')

if __name__=='__main__':unittest.main()
