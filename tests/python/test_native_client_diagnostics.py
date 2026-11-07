import sys,unittest
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[2]/'tools'))
from native_client import NativeClient

class NativeClientDiagnosticsTests(unittest.TestCase):
 def client(self,result,status=503):
  client=NativeClient('http://127.0.0.1:8080','fixture-token','fixture-client')
  client.request=lambda *args:(status,result)
  return client
 def test_failure_retains_structured_code_not_message_or_other_data(self):
  with self.assertRaisesRegex(RuntimeError,'fcpCode=RESTART_BUDGET') as found:
   self.client(dict(code=503,msg='private detail',data=dict(fcpCode='RESTART_BUDGET',secret='private value'))).data('/fcp/plugins')
  self.assertNotIn('private',str(found.exception))
 def test_null_data_is_explicit_not_attribute_error(self):
  with self.assertRaisesRegex(RuntimeError,'fcpCode=UNAVAILABLE'):
   self.client(dict(code=500,data=None)).data('/fcp/plugins')
 def test_arbitrary_error_text_is_not_a_machine_code(self):
  with self.assertRaisesRegex(RuntimeError,'fcpCode=UNAVAILABLE') as found:
   self.client(dict(code=500,data=dict(fcpCode='secret detail\nother'))).data('/fcp/plugins')
  self.assertNotIn('secret',str(found.exception))
 def test_nonobject_response_fails_explicitly(self):
  with self.assertRaisesRegex(RuntimeError,'non-object native envelope'):self.client(None).data('/fcp/plugins')
 def test_success_unchanged(self):
  self.assertEqual({'ok':True},self.client(dict(code=200,data={'ok':True}),200).data('/fcp/plugins'))
if __name__=='__main__':unittest.main()
