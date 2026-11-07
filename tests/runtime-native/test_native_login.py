"""Control-flow regression doubles only. Real native redirect acceptance remains CI-only."""
from pathlib import Path
import sys
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[2]/'tools'))
from native_login import authenticated_storage_state, native_token

BASE='http://127.0.0.1:8080'
def state(values=('synthetic-unit-token',)):
    return {'origins':[{'origin':BASE,'localStorage':[{'name':'FCP-Host-Token','value':v} for v in values]}]}

class NativeLoginTests(unittest.TestCase):
    def test_wait_for_destination_and_visible_ui_before_single_context_snapshot(self):
        events=[]
        class Marker:
            def wait_for(self,**kwargs):
                self_test.assertEqual(kwargs,{'state':'visible','timeout':30000});events.append('visible')
        class Page:
            def wait_for_url(self,pattern,**kwargs):
                self_test.assertTrue(pattern.fullmatch(BASE+'/teacher/fcp/ingestion'))
                self_test.assertFalse(pattern.fullmatch(BASE+'/admin/login?redirect=/fcp/ingestion'))
                self_test.assertFalse(pattern.fullmatch('https://evil.example/teacher/fcp/ingestion'))
                events.append('redirect')
            def get_by_test_id(self,marker):
                self_test.assertEqual(marker,'fcp-ingestion');return Marker()
            def evaluate(self,*_):raise AssertionError('Must not evaluate in redirecting document')
        class Context:
            def storage_state(self):events.append('snapshot');return state()
        self_test=self
        saved,token=authenticated_storage_state(Page(),Context(),BASE,'ingestion')
        self.assertEqual(events,['redirect','visible','snapshot'])
        self.assertEqual(native_token(saved,BASE),token)
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
