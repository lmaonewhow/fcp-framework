"""Local helper/guard unit tests only; these never claim native browser acceptance."""
import os
import unittest
from unittest.mock import patch
from ui_boundaries import require_fixture, exact_closure, is_rejected

class UiBoundaryGuards(unittest.TestCase):
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
