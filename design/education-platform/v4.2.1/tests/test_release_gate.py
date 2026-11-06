import copy
from datetime import datetime,timezone
from pathlib import Path
import tempfile
import unittest
from common import canonical_hash,sha256,safe_file
from release_gate import REQUIRED,COMPONENTS,check

class EvidenceTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.root=Path(self.tmp.name);self.commit='a'*40
        comps=[]
        for name in sorted(COMPONENTS):
            p=self.root/(name+'.fixture');p.write_text('UNIT TEST ONLY; not a production artifact: '+name,encoding='utf-8')
            comps.append({'name':name,'path':p.name,'sha256':sha256(p)})
        self.manifest={'format':'education-release-manifest-v2','artifact_kind':'PRODUCTION','source_commit':self.commit,'components':comps,'compatibility':{'api_contract':'v1','schema_min':'4.1','schema_max':'4.1','old_client_policy':'explicit tested matrix'}}
        digest=canonical_hash(self.manifest);log=self.root/'raw.log';log.write_text('UNIT TEST FIXTURE ONLY. No native acceptance was run.\n',encoding='utf-8')
        self.e={'evidence_kind':'NATIVE_PRODUCT_ACCEPTANCE','manifest_digest':digest,'environment':{'database_engine':'MySQL','database_version':'8.4.2'},'checks':[]}
        for n in REQUIRED:self.e['checks'].append(dict(name=n,status='PASS',exit_code=0,executed_tests=1,failed_tests=0,skipped_tests=0,manifest_digest=digest,source_commit=self.commit,started_at='2026-01-01T00:00:00Z',ended_at='2026-01-01T00:00:01Z',raw_log={'path':'raw.log','sha256':sha256(log)}))
    def tearDown(self):self.tmp.cleanup()
    def errors(self):return check(self.e,self.root,self.manifest,self.root,'8.4.2',datetime(2026,11,6,tzinfo=timezone.utc))
    def test_complete_fixture_only(self):self.assertEqual(self.errors(),[])
    def test_not_run_blocked(self):self.e['checks'][0]['status']='NOT_RUN';self.assertTrue(self.errors())
    def test_reference_evidence_blocked(self):self.e['evidence_kind']='LOCAL_REFERENCE';self.assertTrue(self.errors())
    def test_skipped_blocked(self):self.e['checks'][0]['skipped_tests']=1;self.assertTrue(self.errors())
    def test_zero_tests_blocked(self):self.e['checks'][0]['executed_tests']=0;self.assertTrue(self.errors())
    def test_boolean_exit_blocked(self):self.e['checks'][0]['exit_code']=False;self.assertTrue(self.errors())
    def test_missing_observation_gate(self):self.e['checks']=[r for r in self.e['checks'] if r['name']!='observability_failure'];self.assertTrue(self.errors())
    def test_missing_async_gate(self):self.e['checks']=[r for r in self.e['checks'] if r['name']!='async_stop'];self.assertTrue(self.errors())
    def test_duplicate_check(self):self.e['checks'].append(copy.deepcopy(self.e['checks'][0]));self.assertTrue(self.errors())
    def test_component_tampering(self):(self.root/'teacher_ui.fixture').write_text('tampered',encoding='utf-8');self.assertTrue(self.errors())
    def test_component_removal(self):self.manifest['components'].pop();self.assertTrue(self.errors())
    def test_frontend_hash_changes_entire_manifest(self):
        old=canonical_hash(self.manifest);self.manifest['components'][0]['sha256']='d'*64;self.assertNotEqual(old,canonical_hash(self.manifest));self.assertTrue(self.errors())
    def test_manifest_wrong_commit(self):self.manifest['source_commit']='b'*40;self.assertTrue(self.errors())
    def test_manifest_not_production(self):self.manifest['artifact_kind']='REFERENCE_ONLY';self.assertTrue(self.errors())
    def test_wrong_mysql_patch(self):self.e['environment']['database_version']='8.4.3';self.assertTrue(self.errors())
    def test_wrong_engine(self):self.e['environment']['database_engine']='SQLite';self.assertTrue(self.errors())
    def test_stale_test_binding(self):self.e['checks'][0]['manifest_digest']='f'*64;self.assertTrue(self.errors())
    def test_path_escape(self):self.e['checks'][0]['raw_log']['path']='../raw.log';self.assertTrue(self.errors())
    def test_windows_path_escape(self):self.e['checks'][0]['raw_log']['path']='..\\raw.log';self.assertTrue(self.errors())
    def test_tampered_log(self):(self.root/'raw.log').write_text('modified',encoding='utf-8');self.assertTrue(self.errors())
    def test_invalid_time(self):self.e['checks'][0]['ended_at']='2025-01-01T00:00:00Z';self.assertTrue(self.errors())
    def test_future_evidence(self):self.e['checks'][0]['ended_at']='2030-01-01T00:00:00Z';self.assertTrue(self.errors())
    def test_unknown_check_name_type(self):self.e['checks'][0]['name']=[];self.assertTrue(self.errors())
    def test_symlink_rejected_when_supported(self):
        p=self.root/'link.log'
        try:p.symlink_to(self.root/'raw.log')
        except OSError:
            # Windows without symlink privilege: string traversal checks are separately tested.
            with self.assertRaises(ValueError):safe_file(self.root,'../raw.log')
            return
        self.e['checks'][0]['raw_log']['path']='link.log';self.assertTrue(self.errors())
