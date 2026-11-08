"""Deployment-fixture checks only; never a native acceptance PASS."""
import hashlib,hmac,io,json,sys,tempfile,unittest,zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
sys.path[:0]=[str(ROOT/'tools'),str(ROOT/'tests/runtime-native')]
from native_observation_acceptance import configure,package_bytes,FAULT_MESSAGE,identity_violation_rejected,identity_violation_diagnostic
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
 def test_identity_violation_requires_exact_typed_native_contract(self):
  import copy
  reply={'code':503,'msg':'Plugin operation unavailable; use the operation receipt to reconcile','data':{'fcpCode':'HOST_RESULT_IDENTITY_VIOLATION','traceId':'12345678-1234-1234-1234-123456789abc'}}
  from ui_boundaries import is_rejected
  self.assertFalse(is_rejected(503,reply))  # Reproduces the old generic-denial assertion mismatch.
  self.assertTrue(identity_violation_rejected(503,reply))
  hextrace=copy.deepcopy(reply);hextrace["data"]["traceId"]="a"*32;self.assertTrue(identity_violation_rejected(503,hextrace))
  for status in (200,400,403,409,422,500,502,504):self.assertFalse(identity_violation_rejected(status,reply))
  for field,value in [('code',500),('msg','Internal Server Error'),('result',{'tenantId':'secret'})]:
   wrong=copy.deepcopy(reply);wrong[field]=value;self.assertFalse(identity_violation_rejected(503,wrong))
  for field,value in [('fcpCode','OUTCOME_UNKNOWN'),('traceId','invalid'),('result','secret'),('tenantId','secret')]:
   wrong=copy.deepcopy(reply);wrong['data'][field]=value;self.assertFalse(identity_violation_rejected(503,wrong))
  summary=identity_violation_diagnostic(503,dict(reply,result='secret'), 'candidate',3,'a'*64)
  self.assertNotIn('secret',json.dumps(summary));self.assertNotIn('traceId',summary)
 def test_diagnostic_redacts_unknown_codes_and_key_names(self):
  import copy
  sentinel='SENSITIVE_RESPONSE_SENTINEL_DO_NOT_REPORT'
  valid={'code':503,'msg':'unused','data':{'fcpCode':'HOST_RESULT_IDENTITY_VIOLATION','traceId':'unused'}}
  variants=[]
  a=copy.deepcopy(valid);a['code']=sentinel;variants.append(a)
  a=copy.deepcopy(valid);a['data']['fcpCode']=sentinel;variants.append(a)
  a=copy.deepcopy(valid);a[sentinel]='value';variants.append(a)
  a=copy.deepcopy(valid);a['data'][sentinel]='value';variants.append(a)
  for body in variants:
   with self.subTest(body=variants.index(body)):
    diagnostic=identity_violation_diagnostic(503,body,'candidate',3,'a'*64)
    self.assertNotIn(sentinel,json.dumps(diagnostic))
    self.assertEqual(diagnostic['httpStatus'],503)
  self.assertEqual(identity_violation_diagnostic(503,variants[0],'candidate',3,'a'*64)['code'],'REDACTED')
  self.assertEqual(identity_violation_diagnostic(503,variants[1],'candidate',3,'a'*64)['fcpCode'],'REDACTED')
  self.assertEqual(identity_violation_diagnostic(503,variants[2],'candidate',3,'a'*64)['bodyUnknownKeyCount'],1)
  self.assertEqual(identity_violation_diagnostic(503,variants[3],'candidate',3,'a'*64)['dataUnknownKeyCount'],1)
  safe=identity_violation_diagnostic(503,valid,'candidate',3,'a'*64)
  self.assertEqual(safe['code'],503);self.assertEqual(safe['fcpCode'],'HOST_RESULT_IDENTITY_VIOLATION')
  self.assertEqual(safe['bodyKeyPresence'],{'code':True,'data':True,'msg':True})
  self.assertEqual(safe['dataKeyPresence'],{'fcpCode':True,'traceId':True})
 def test_no_metric_or_security_injection_api(self):
  source=(ROOT/'tests/runtime-native/native_observation_acceptance.py').read_text()
  self.assertNotIn('securityViolation(',source)
  self.assertNotIn('MetricsGate.Sample',source)
  self.assertNotIn('/automation/safety',source)
  self.assertIn("'/fcp/capabilities/'",source)
if __name__=='__main__':unittest.main()
