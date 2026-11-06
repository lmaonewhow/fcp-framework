import json
import unittest
from telemetry_contract import safe_event,TelemetryRejected

class TelemetryTests(unittest.TestCase):
    def setUp(self):self.trusted={'tenant_ref':'a'*64,'manifest_digest':'b'*64,'trace_id':'c'*32,'operation_id':'11111111-1111-1111-1111-111111111111'}
    def test_sensitive_fields_removed_before_serialization(self):
        raw={'event':'command.committed','message':'学生 张三 13800000000','url':'/?token=secret','Authorization':'Bearer SECRET','sql':'select private','exception':'student name','request_body':{'name':'张三'}}
        text=json.dumps(safe_event(raw,self.trusted),ensure_ascii=False)
        for forbidden in ('张三','13800000000','secret','SECRET','student name'):self.assertNotIn(forbidden,text)
    def test_browser_tenant_cannot_override(self):
        x=safe_event({'event':'page.ready','tenant_ref':'d'*64,'manifest_digest':'e'*64},self.trusted,'browser');self.assertEqual(x['tenant_ref'],'a'*64);self.assertEqual(x['actual_manifest_digest'],'b'*64)
    def test_browser_claim_separate(self):
        x=safe_event({'event':'page.ready','claimed_frontend_digest':'d'*64},self.trusted,'browser');self.assertEqual(x['claimed_frontend_digest'],'d'*64);self.assertFalse(x['authoritative_business_result'])
    def test_unknown_message_event_rejected(self):
        with self.assertRaises(TelemetryRejected):safe_event({'event':'student 张三'},self.trusted)
    def test_raw_trace_not_used(self):
        x=safe_event({'event':'command.committed','trace_id':'0'*32},self.trusted);self.assertEqual(x['trace_id'],'c'*32)
    def test_invalid_duration(self):
        for n in (True,float('nan'),float('inf'),-1,'12'):
            with self.assertRaises(TelemetryRejected):safe_event({'event':'command.committed','duration_ms':n},self.trusted)
    def test_invalid_trusted_id(self):
        with self.assertRaises(TelemetryRejected):safe_event({'event':'command.committed'},dict(self.trusted,tenant_ref='school-name'))
    def test_unknown_error_code(self):
        with self.assertRaises(TelemetryRejected):safe_event({'event':'command.denied','error_code':'TOKEN=secret'},self.trusted)
    def test_browser_cannot_emit_authoritative_commit(self):
        with self.assertRaises(TelemetryRejected):safe_event({'event':'command.committed'},self.trusted,'browser')
