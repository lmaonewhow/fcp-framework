"""Control-flow regression doubles only. Real native redirect acceptance remains CI-only."""
from pathlib import Path
import sys
import unittest
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parents[2]/'tools'))
from native_login import authenticated_storage_state, native_token, require_encrypted_login, verify_native_identity

BASE='http://127.0.0.1:8080'
def state(values=('synthetic-unit-token',)):
    return {'origins':[{'origin':BASE,'localStorage':[{'name':'FCP-Host-Token','value':v} for v in values]}]}

class NativeLoginTests(unittest.TestCase):
    def test_auth_metadata_does_not_read_discarded_response_body(self):
        class Response:
            status = 200
            class request:
                headers = {'encrypt-key':'synthetic-unit-marker'}
            def json(self):raise AssertionError('Pre-navigation body must never be read')
            def body(self):raise AssertionError('Pre-navigation body must never be read')
        require_encrypted_login(Response())
        response=Response();response.status=401
        with self.assertRaises(AssertionError):require_encrypted_login(response)
    def test_fresh_session_verifies_exact_user_and_tenant(self):
        class Client:
            def data(self,path):
                self_test.assertEqual(path,'/fcp/ui/session')
                return {'subject':'960601','scope':'tenant-960001','allowedShells':['admin','teacher']}
        self_test=self
        self.assertEqual(verify_native_identity(Client(),'960601','960001')['subject'],'960601')
        with self.assertRaises(AssertionError):verify_native_identity(Client(),'1','960001')
        with self.assertRaises(AssertionError):verify_native_identity(Client(),'960601','000000')
    def test_native_index_then_explicit_authorized_workspace_navigation(self):
        for shell in ('admin','teacher'):
            events=[]
            class Marker:
                def wait_for(self,**kwargs):
                    self_test.assertEqual(kwargs,{'state':'visible','timeout':30000});events.append('visible')
            class Page:
                url=BASE+'/admin/index'
                def wait_for_url(self,pattern,**kwargs):
                    self_test.assertTrue(pattern.fullmatch(self.url))
                    for invalid in ('/admin/login','/admin/login?redirect=/fcp/ingestion','/teacher/login/pwd-login'):
                        self_test.assertFalse(pattern.fullmatch(BASE+invalid))
                    self_test.assertFalse(pattern.fullmatch('https://evil.example/admin/index'))
                    events.append('home' if self.url.endswith('/index') else 'workspace')
                def goto(self,url,**kwargs):
                    self_test.assertEqual(url,BASE+'/'+shell+'/fcp/ingestion')
                    self.url=url;events.append('goto')
                def get_by_test_id(self,marker):
                    self_test.assertEqual(marker,'fcp-ingestion');return Marker()
                def evaluate(self,*_):raise AssertionError('Must not evaluate in redirecting document')
            class Context:
                def storage_state(self):events.append('snapshot');return state()
            class Client:
                def __init__(self,*args):pass
                def data(self,path):
                    self_test.assertEqual(path,'/fcp/ui/session');events.append('session')
                    return {'defaultShell':shell,'allowedShells':[shell]}
            self_test=self
            with patch('native_login.NativeClient',Client):
                saved,token=authenticated_storage_state(Page(),Context(),BASE,'ingestion')
            self.assertEqual(events,['home','snapshot','session','goto','workspace','visible','snapshot'])
            self.assertEqual(native_token(saved,BASE),token)
    def test_untrusted_server_shell_cannot_choose_arbitrary_destination(self):
        class Page:
            def wait_for_url(self,*args,**kwargs):pass
            def goto(self,*args,**kwargs):raise AssertionError('Must not navigate to unauthorized shell')
        class Context:
            def storage_state(self):return state()
        for session in ({'defaultShell':'https://evil.example','allowedShells':['https://evil.example']}, {'defaultShell':'admin','allowedShells':['teacher']}):
            with patch('native_login.NativeClient') as client:
                client.return_value.data.return_value=session
                with self.assertRaisesRegex(AssertionError,'known default shell'):
                    authenticated_storage_state(Page(),Context(),BASE,'plugins')
    def test_failed_destination_never_extracts_token(self):
        class Page:
            def wait_for_url(self,*_,**__):raise TimeoutError('route did not authenticate')
        class Context:
            def storage_state(self):raise AssertionError('No snapshot after failed navigation')
        with self.assertRaises(TimeoutError):authenticated_storage_state(Page(),Context(),BASE,'plugins')
    def test_exact_origin_and_single_nonempty_token_required(self):
        for values in ((),('',),('a','b'),(None,)):
            with self.assertRaises(RuntimeError):native_token(state(values),BASE)
        wrong=state();wrong['origins'][0]['origin']='https://another.example'
        with self.assertRaises(RuntimeError):native_token(wrong,BASE)
    def test_unsupported_workspace_fails_before_browser_access(self):
        with self.assertRaises(ValueError):authenticated_storage_state(None,None,BASE,'login')

if __name__=='__main__':unittest.main()
