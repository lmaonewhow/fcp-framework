"""Deployment-fixture checks only; never a native acceptance PASS."""
import hashlib,hmac,io,json,sys,tempfile,unittest,zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
sys.path[:0]=[str(ROOT/'tools'),str(ROOT/'tests/runtime-native')]
from native_observation_acceptance import configure,package_bytes,FAULT_MESSAGE
class ObservationConfigurationTests(unittest.TestCase):
 def test_exact_digest_bindings_and_real_hash_selection(self):
  with tempfile.TemporaryDirectory() as folder:
   root=Path(folder);key=root/'key';props=configure(root,key)
   self.assertIn('fcp.release.identity-echo.enabled=true',props)
   config=json.loads((root/'identity-echo-bindings.json').read_text())
   self.assertEqual(config['format'],1)
   for binding,version in zip(config['bindings'],('1.0.0','1.1.0')):
    self.assertEqual(binding,dict(plugin='ci.python',capability='echo',sha256=hashlib.sha256(package_bytes('python',version)[0]).hexdigest(),contract='identity-echo/1'))
   self.assertEqual(len(key.read_bytes()),32)
   bucket=int.from_bytes(hmac.new(key.read_bytes(),b'["bucket-v1","native-ci","000000"]',hashlib.sha256).digest()[:4],'big')%10000
   self.assertLess(bucket,2500)
 def test_fault_is_actual_package_code_and_deterministic(self):
  stable,_=package_bytes('python','1.0.0');candidate,_=package_bytes('python','1.1.0')
  self.assertEqual(candidate,package_bytes('python','1.1.0')[0])
  with zipfile.ZipFile(io.BytesIO(stable)) as z:self.assertNotIn(FAULT_MESSAGE,z.read('runtime.py').decode())
  with zipfile.ZipFile(io.BytesIO(candidate)) as z:
   source=z.read('runtime.py').decode();compile(source,'synthetic-runtime.py','exec')
   self.assertIn(FAULT_MESSAGE,source);self.assertIn("result['tenantId']='synthetic-wrong-tenant'",source)
 def test_no_metric_or_security_injection_api(self):
  source=(ROOT/'tests/runtime-native/native_observation_acceptance.py').read_text()
  self.assertNotIn('securityViolation(',source)
  self.assertNotIn('MetricsGate.Sample',source)
  self.assertNotIn('/automation/safety',source)
  self.assertIn("'/fcp/capabilities/'",source)
if __name__=='__main__':unittest.main()
