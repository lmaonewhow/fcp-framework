"""Control-flow tests only, not a reproduction of Chromium's network lifecycle."""
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch
sys.path[:0] = [str(Path(__file__).resolve().parents[2]/'tools')]
from native_login import NativeLoginPreparationError, wait_native_logout
from release_composition_acceptance import ReleaseCompositionFixture

BASE='http://127.0.0.1:18787'

class Response:
    url=BASE+'/auth/logout'
    status=200
    headers={'content-type':'application/json','x-trace-id':'a'*32}
    def body(self):raise AssertionError('Logout body must not be fetched')

class Page:
    def __init__(self, error=None, token=None):
        self.error,self.token=error,token
        self.context=self
        self.calls=[]
    def wait_for_url(self, predicate, **kwargs):
        self.calls.append('native-login-route')
        for route in ('/admin/login','/teacher/login/pwd-login?redirect=private'):
            assert predicate.fullmatch(BASE+route)
        for route in ('/admin/fcp/plugins','/teacher/login-other'):
            assert not predicate.fullmatch(BASE+route)
        assert not predicate.fullmatch('http://evil.invalid/admin/login')
        if self.error:raise self.error
    def storage_state(self):
        self.calls.append('native-storage-read')
        return {'origins':[{'origin':BASE,'localStorage':[{'name':'FCP-Host-Token','value':self.token}]}]}

class LogoutCompletionTests(unittest.TestCase):
    def test_normal_route_precedes_storage_read_without_navigation_or_write(self):
        page=Page();events=[]
        wait_native_logout(page,Response(),BASE,events=events)
        self.assertEqual(page.calls,['native-login-route','native-storage-read'])
        self.assertEqual(events[0]['stage'],'logout-ui-complete')
        self.assertEqual(events[0]['bodyState'],'not-read')
    def test_retained_token_fails_without_clearing_it(self):
        page=Page(token='private-secret');events=[]
        with self.assertRaises(NativeLoginPreparationError) as error:
            wait_native_logout(page,Response(),BASE,events=events)
        self.assertEqual(error.exception.diagnostic['stage'],'logout-token-retained')
        self.assertEqual(page.token,'private-secret')
        self.assertNotIn('private',json.dumps(events)+str(error.exception))
    def test_navigation_failure_is_safe_and_does_not_inspect_storage(self):
        page=Page(error=TimeoutError('private URL query token'));events=[]
        with self.assertRaises(NativeLoginPreparationError) as error:
            wait_native_logout(page,Response(),BASE,events=events)
        self.assertEqual(page.calls,['native-login-route'])
        self.assertEqual(error.exception.diagnostic['stage'],'logout-ui-incomplete')
        self.assertEqual(events[0]['failureKind'],'timeout')
        self.assertNotIn('private',json.dumps(events)+str(error.exception))
    def test_different_loopback_origin_cannot_define_logout_completion(self):
        page=Page();response=Response();response.url='http://127.0.0.1:19797/auth/logout'
        with self.assertRaises(NativeLoginPreparationError) as error:
            wait_native_logout(page,response,BASE)
        self.assertEqual(error.exception.diagnostic['stage'],'logout-origin-mismatch')
        self.assertEqual(page.calls,[])
    def test_composition_persists_callback_events_and_failure_before_return(self):
        fixture=ReleaseCompositionFixture.__new__(ReleaseCompositionFixture)
        fixture.login_fixture=object();fixture.report={};saved=[]
        fixture._write=lambda: saved.append(json.loads(json.dumps(fixture.report)))
        error=NativeLoginPreparationError('/auth/tenant/list','body-read-failed',200,
                                           metadata={'failureKind':'resource-unavailable','traceId':'a'*32})
        def relogin(page,shell):
            relogin.diagnostics.append(dict(error.diagnostic,attempt=1))
            raise error
        relogin.diagnostics=[]
        with patch('ui_boundaries.make_tenant_relogin',return_value=relogin), patch('ui_boundaries.native_logout'):
            with self.assertRaises(NativeLoginPreparationError):fixture.tenant_relogin(object(),BASE)
        self.assertEqual(len(saved),1)
        self.assertEqual(saved[0]['loginFailure'],error.diagnostic)
        self.assertEqual(saved[0]['loginPreparation'],relogin.diagnostics)
    def test_composition_persists_logout_failure_and_never_relogs(self):
        fixture=ReleaseCompositionFixture.__new__(ReleaseCompositionFixture)
        fixture.login_fixture=object();fixture.report={};saved=[]
        fixture._write=lambda:saved.append(dict(fixture.report))
        error=NativeLoginPreparationError('/auth/logout','logout-token-retained',200)
        with patch('ui_boundaries.make_tenant_relogin') as factory, patch('ui_boundaries.native_logout',side_effect=error):
            factory.return_value.diagnostics=[]
            with self.assertRaises(NativeLoginPreparationError):fixture.tenant_relogin(object(),BASE)
            factory.return_value.assert_not_called()
        self.assertEqual(saved[0]['loginFailure'],error.diagnostic)
    def test_composition_success_saves_preparation(self):
        fixture=ReleaseCompositionFixture.__new__(ReleaseCompositionFixture)
        fixture.login_fixture=object();fixture.report={};saved=[]
        fixture._write=lambda:saved.append(dict(fixture.report))
        with patch('ui_boundaries.make_tenant_relogin') as factory, patch('ui_boundaries.native_logout'):
            factory.return_value.diagnostics=[{'stage':'validated','endpoint':'/auth/tenant/list'}]
            fixture.tenant_relogin(object(),BASE)
        self.assertEqual(len(saved[0]['loginPreparation']),1)
        self.assertNotIn('loginFailure',saved[0])

if __name__=='__main__':unittest.main()
