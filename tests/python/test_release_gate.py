import unittest,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'tools'))
from release_gate import evaluate

class NativeReleaseGateTests(unittest.TestCase):
 def documents(self):
  build={'nativeBuild':'PASS','buildId':'a'*32}
  results=[{'status':'PASS','host':{'buildId':'a'*32}} for _ in range(3)]
  return build,results
 def test_empty_evidence_fails_closed(self):
  self.assertEqual(evaluate({},{},{},{})['status'],'BLOCKED')
 def test_component_checks_cannot_replace_native(self):
  self.assertEqual(evaluate({'componentChecks':'PASS'},{},{},{})['status'],'BLOCKED')
 def test_same_build_required(self):
  build,results=self.documents();results[1]['host']['buildId']='b'*32
  self.assertEqual(evaluate(build,*results)['status'],'BLOCKED')
 def test_failed_api_cannot_release(self):
  build,results=self.documents();results[0]['status']='FAIL'
  self.assertEqual(evaluate(build,*results)['status'],'BLOCKED')
 def test_unbuilt_sources_cannot_release(self):
  build,results=self.documents();build['nativeBuild']='NOT_RUN'
  self.assertEqual(evaluate(build,*results)['status'],'BLOCKED')
 def test_matching_limited_evidence_passes_logic(self):
  # Unit-test fixtures exercise only the gate decision, not a real application.
  build,results=self.documents()
  self.assertEqual(evaluate(build,*results)['status'],'PASS')
if __name__=='__main__':unittest.main()
