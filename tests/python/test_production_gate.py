"""Synthetic documents test the decision logic only; never real production evidence."""
import copy,hashlib,json,sys,tempfile,unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2];sys.path.insert(0,str(ROOT/'tools'))
from production_gate import evaluate,GROUPS,digest
class ProductionGateTests(unittest.TestCase):
 def setUp(self):
  self.temp=tempfile.TemporaryDirectory();self.root=Path(self.temp.name)
  (self.root/'fixture.log').write_text('EXPLICIT UNIT FIXTURE, NO REAL APPLICATION\n')
  self.targets=json.loads((ROOT/'config/production-targets.json').read_text());self.targetHash=digest(ROOT/'config/production-targets.json')
  self.build={'nativeBuild':'PASS','buildId':'a'*32,'jarSha256':'b'*64,'javaUnitTests':{'status':'PASS','tests':1,'skipped':0,'failures':0,'errors':0},
      'frontend':{key:{'productionBuild':'PASS','typecheck':'PASS','lockSha256':'c'*64,'lockPolicy':'restored-npm-lock' if key=='admin' else 'upstream-pnpm-lock'} for key in ['admin','teacher']}}
  self.evidence={}
  for key,checks in GROUPS.items():
   self.evidence[key]={'schemaVersion':1,'status':'PASS','execution':'REAL','buildId':'a'*32,'jarSha256':'b'*64,'targetsSha256':self.targetHash,
       'mocked':False,'skipped':0,'checks':[{'id':name,'passed':True} for name in checks],
       'artifacts':[{'path':'fixture.log','sha256':digest(self.root/'fixture.log')}],'metrics':{}}
  for role in ['admin','teacher','viewer']:
   path=self.root/(role+'.webm');path.write_bytes(b'\x1aE\xdf\xa3EXPLICIT NON-VIDEO UNIT FIXTURE')
   self.evidence['browser']['artifacts'].append({'path':path.name,'sha256':digest(path),'role':role})
  self.evidence['load']['metrics']={key:self.targets[key] for key in ['readP95Ms','writeP95Ms','javaIdleRssMiB','pythonRssMiB','startupSeconds','concurrentUsers','recordCount','tenantCount','durationSeconds','successRatio']}
  self.evidence['soak']['metrics']={'durationSeconds':86400,'rssGrowthRatio':.02,'crashes':0,'orphanProcesses':0}
  self.evidence['security']['metrics']={'unresolvedCritical':0,'unresolvedHigh':0,'successfulUnauthorizedOperations':0}
  self.evidence['restore']['metrics']={'rtoSeconds':300,'rpoSeconds':30}
 def tearDown(self):self.temp.cleanup()
 def gate(self):return evaluate(self.build,self.evidence,self.root,self.targets,self.targetHash,media_probe=lambda _:True)
 def test_complete_fixture_passes_only_decision_logic(self):self.assertEqual(self.gate()['status'],'PRODUCTION_READY')
 def test_empty_inputs_block(self):self.assertEqual(evaluate({}, {},self.root,self.targets,self.targetHash)['status'],'BLOCKED')
 def test_boolean_pass_cannot_replace_measurements(self):
  self.evidence['load']['metrics']={};self.assertEqual(self.gate()['status'],'BLOCKED')
 def test_mock_cannot_release(self):
  self.evidence['api']['mocked']=True;self.assertEqual(self.gate()['status'],'BLOCKED')
 def test_different_build_denied(self):
  self.evidence['browser']['buildId']='c'*32;self.assertEqual(self.gate()['status'],'BLOCKED')
 def test_different_jar_denied(self):
  self.evidence['migration']['jarSha256']='c'*64;self.assertEqual(self.gate()['status'],'BLOCKED')
 def test_target_changes_require_retest(self):
  self.evidence['load']['targetsSha256']='d'*64;self.assertEqual(self.gate()['status'],'BLOCKED')
 def test_short_soak_blocked(self):
  self.evidence['soak']['metrics']['durationSeconds']=600;self.assertEqual(self.gate()['status'],'BLOCKED')
 def test_excess_latency_blocked(self):
  self.evidence['load']['metrics']['readP95Ms']=301;self.assertEqual(self.gate()['status'],'BLOCKED')
 def test_nonfinite_measurement_blocked(self):
  self.evidence['load']['metrics']['readP95Ms']=float('nan');self.assertEqual(self.gate()['status'],'BLOCKED')
 def test_boolean_measurement_blocked(self):
  self.evidence['load']['metrics']['readP95Ms']=True;self.assertEqual(self.gate()['status'],'BLOCKED')
 def test_test_skip_blocked(self):
  self.build['javaUnitTests']['skipped']=1;self.assertEqual(self.gate()['status'],'BLOCKED')
 def test_raw_log_tampering_blocked(self):
  (self.root/'fixture.log').write_text('changed');self.assertEqual(self.gate()['status'],'BLOCKED')
 def test_missing_video_role_blocked(self):
  self.evidence['browser']['artifacts'].pop();self.assertEqual(self.gate()['status'],'BLOCKED')
 def test_evidence_path_escape_blocked(self):
  self.evidence['api']['artifacts'][0]['path']='../outside';self.assertEqual(self.gate()['status'],'BLOCKED')
 def test_missing_permission_assertion_blocked(self):
  self.evidence['api']['checks']=[];self.assertEqual(self.gate()['status'],'BLOCKED')
 def test_unresolved_high_vulnerability_blocked(self):
  self.evidence['security']['metrics']['unresolvedHigh']=1;self.assertEqual(self.gate()['status'],'BLOCKED')
 def test_failed_restore_blocks(self):
  self.evidence['restore']['checks'][0]['passed']=False;self.assertEqual(self.gate()['status'],'BLOCKED')
 def test_negative_measurement_blocked(self):
  self.evidence['load']['metrics']['readP95Ms']=-1;self.assertEqual(self.gate()['status'],'BLOCKED')
 def test_success_ratio_cannot_exceed_one(self):
  self.evidence['load']['metrics']['successRatio']=1.01;self.assertEqual(self.gate()['status'],'BLOCKED')
 def test_generated_unreviewed_lock_blocks_production(self):
  self.build['frontend']['admin']['lockPolicy']='generated-npm-lock';self.assertEqual(self.gate()['status'],'BLOCKED')
 def test_existing_local_redis_acceptance_cannot_release_production(self):
  self.build['localTestOnlyReasons']=['Existing loopback Redis has no password']
  self.assertEqual(self.gate()['status'],'BLOCKED')
 def test_real_video_probe_rejects_header_only_unit_files(self):
  self.assertEqual(evaluate(self.build,self.evidence,self.root,self.targets,self.targetHash)['status'],'BLOCKED')
if __name__=='__main__':unittest.main()
