import copy,re
from pathlib import Path
import unittest
from common import load_json
from audit_contract import validate_audit_event,AuditRejected
ROOT=Path(__file__).resolve().parents[1]
class AuditContractTests(unittest.TestCase):
    def setUp(self):
        self.e={'audit_schema_version':'audit-v2','tenant_id':'t1','actor_key':'actor','action_key':'evaluation.record','owner_domain':'evaluation','object_type':'judgement','object_id':'1','operation_id':'11111111-1111-1111-1111-111111111111','request_attempt_id':'22222222-2222-2222-2222-222222222222','result':'COMMITTED','manifest_digest':'a'*64,'occurred_at':'2026-11-06T00:00:00Z','config_revision':'1','rule_revision':'1','trace_id':None,'changes_json':{'changed_fields':['score'],'after_digest':'b'*64}}
    def test_valid_without_sampled_trace(self):self.assertEqual(validate_audit_event(self.e),self.e)
    def test_oversized_object_and_revision_rejected(self):
        for key,n in [('object_id',191),('rule_revision',65),('object_type',65)]:
            e=copy.deepcopy(self.e);e[key]='x'*n
            with self.subTest(key=key),self.assertRaises(AuditRejected):validate_audit_event(e)
    def test_failure_cannot_be_success_audit(self):
        self.e['result']='FAILED'
        with self.assertRaises(AuditRejected):validate_audit_event(self.e)
    def test_free_text_changes_rejected(self):
        self.e['changes_json']['student_name']='sensitive'
        with self.assertRaises(AuditRejected):validate_audit_event(self.e)
    def test_utc_required(self):
        for time in ['2026-11-06T00:00:00','2026-11-06T08:00:00+08:00','broken']:
            self.e['occurred_at']=time
            with self.subTest(time=time),self.assertRaises(AuditRejected):validate_audit_event(self.e)
    def test_zero_or_malformed_trace_rejected(self):
        for v in ['0'*32,[],42]:
            self.e['trace_id']=v
            with self.subTest(value=v),self.assertRaises(AuditRejected):validate_audit_event(self.e)
    def test_contract_widths_match_model(self):
        schema=load_json(ROOT/'contracts/audit-event.schema.json');model=load_json(ROOT/'database/model.json')
        t=next(t for t in model['tables'] if t['name']=='ed_core_audit_event');cols={c['name']:c for c in t['columns']}
        for key,p in schema['properties'].items():
            self.assertIn(key,cols)
            if 'maxLength' in p:
                width=int(re.search(r'(?:VAR)?CHAR\((\d+)\)',cols[key]['type']).group(1));self.assertLessEqual(p['maxLength'],width)
        self.assertTrue(cols['trace_id']['nullable']);self.assertEqual(cols['audit_schema_version']['default'],"'audit-v2'")
