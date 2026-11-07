"""Local helper/guard unit tests only; these never claim native browser acceptance."""
import os
import unittest
from unittest.mock import patch
from ui_boundaries import require_fixture, exact_closure, is_rejected, native_logout

class UiBoundaryGuards(unittest.TestCase):
    def test_logout_never_reads_discarded_response_body(self):
        class Response:
            status=200
            url='http://127.0.0.1:8080/auth/logout'
            def json(self):raise AssertionError('Navigation may discard logout body')
            def body(self):raise AssertionError('Navigation may discard logout body')
        class Wait:
            def __init__(self,response):self.value=response
            def __enter__(self):return self
            def __exit__(self,*args):return False
        class Page:
            def __init__(self,response):self.response=response
            def locator(self,*args,**kwargs):return self
            def get_by_role(self,*args,**kwargs):return self
            def filter(self,*args,**kwargs):return self
            def click(self):pass
            def expect_response(self,predicate,**kwargs):
                if not predicate(self.response):raise AssertionError('Wrong endpoint')
                return Wait(self.response)
        for shell in ('admin','teacher'):
            native_logout(Page(Response()),shell)
            response=Response();response.status=500
            with self.assertRaises(AssertionError):native_logout(Page(response),shell)

    def test_disposable_database_and_loopback_required(self):
        env={'GITHUB_ACTIONS':'true','FCP_ACCEPTANCE_DATABASE':'fcp_ci_0123456789ab'}
        with patch.dict(os.environ,env):
            require_fixture('http://127.0.0.1:8080','fcp_ci_0123456789ab',('ci.python','ci.node'),'ci.python')
            with self.assertRaises(ValueError): require_fixture('https://example.com','fcp_ci_0123456789ab',('ci.python',),'ci.python')
            with self.assertRaises(RuntimeError): require_fixture('http://localhost:8080','production',('ci.python',),'ci.python')
            with self.assertRaises(ValueError): require_fixture('http://localhost:8080','fcp_ci_0123456789ab',('customer.plugin',),'customer.plugin')
        with patch.dict(os.environ,{'GITHUB_ACTIONS':'false'}):
            with self.assertRaises(RuntimeError): require_fixture('http://localhost:8080','fcp_ci_0123456789ab',('ci.python',),'ci.python')
    def test_exact_explicit_closure(self):
        self.assertEqual(exact_closure({'affectedPlugins':['ci.python','ci.node']},('ci.python','ci.node')),['ci.python','ci.node'])
        for value in ([],None,['ci.python','ci.python'],['ci.python','thirdparty']):
            with self.assertRaises(AssertionError): exact_closure({'affectedPlugins':value},('ci.python','ci.node'))
    def test_server_errors_are_not_denial_evidence(self):
        self.assertTrue(is_rejected(403,{'code':403}))
        self.assertTrue(is_rejected(200,{'code':409}))
        self.assertFalse(is_rejected(500,{'code':500}))
        self.assertFalse(is_rejected(200,{'code':200}))
        self.assertFalse(is_rejected(200,{'code':'UNKNOWN'}))

if __name__=='__main__': unittest.main()
